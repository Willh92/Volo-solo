/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.b3log.latke.Keys;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.service.annotation.Service;
import org.b3log.solo.model.Option;
import org.b3log.solo.util.IpRange;
import org.b3log.solo.util.IpGeo;
import org.json.JSONArray;
import org.json.JSONObject;

/** Persisted deny rules. Management writes and the published snapshot are serialized. */
@Service
public class IpBanService {
    private static final String OPTION_ID = "voloIpBans";
    @Inject private OptionQueryService optionQueryService;
    @Inject private OptionMgmtService optionMgmtService;
    private record Rule(JSONObject data, IpRange range) { }
    private volatile List<Rule> snapshot;
    protected long now() { return System.currentTimeMillis(); }
    private boolean active(final Rule rule) { return rule.data().optLong("expiresAt") == 0 || rule.data().optLong("expiresAt") > now(); }

    private synchronized List<Rule> rules() throws Exception {
        if (snapshot == null) {
            final JSONArray data = new JSONArray(org.b3log.solo.util.OptionDocuments.read(optionQueryService, OPTION_ID, "[]"));
            final List<Rule> entries = new ArrayList<>();
            for (int i = 0; i < data.length(); i++) {
                final JSONObject entry = data.getJSONObject(i);
                entries.add(new Rule(entry, IpRange.parse(entry.getString("range"))));
            }
            snapshot = List.copyOf(entries);
        }
        return snapshot;
    }

    public boolean blocked(final String address) throws Exception {
        final byte[] bytes = org.b3log.solo.util.IpAddresses.bytes(address);
        for (final Rule entry : rules()) if (active(entry) && entry.range().contains(bytes)) return true;
        return false;
    }

    public JSONArray list() throws Exception {
        final JSONArray ret = new JSONArray();
        for (final Rule stored : rules()) {
            final JSONObject entry = new JSONObject(stored.data().toString());
            entry.put("region", IpGeo.region(stored.range().baseAddress()));
            ret.put(entry);
        }
        return ret;
    }

    /** Resolve locations only for the requested page, not every persisted rule. */
    public JSONObject page(final int requestedPage, final int pageSize, final String keyword) throws Exception {
        return page(requestedPage, pageSize, keyword, "all", "active");
    }

    public JSONObject page(final int requestedPage, final int pageSize, final String keyword, final String source, final String status) throws Exception {
        if (requestedPage < 1 || pageSize < 1 || pageSize > 50 || keyword.length() > 100) {
            throw new IllegalArgumentException("封禁列表查询参数不正确。");
        }
        if (!java.util.Set.of("all", "manual", "automatic").contains(source)
                || !java.util.Set.of("all", "active", "expired").contains(status)) throw new IllegalArgumentException("封禁筛选条件不正确。");
        final String search = keyword.strip().toLowerCase(Locale.ROOT);
        final List<Rule> filtered = rules().stream()
                .filter(rule -> source.equals("all") || rule.data().optString("source", "manual").equals(source))
                .filter(rule -> status.equals("all") || active(rule) == status.equals("active"))
                .filter(rule -> search.isEmpty()
                || rule.range().toString().contains(search)
                || rule.data().optString("reason").toLowerCase(Locale.ROOT).contains(search)
                || rule.data().optString("note").toLowerCase(Locale.ROOT).contains(search))
                .sorted(java.util.Comparator.comparingLong((Rule rule) -> rule.data().optLong("created")).reversed())
                .toList();
        final int pageCount = Math.max(1, (filtered.size() + pageSize - 1) / pageSize);
        final int page = Math.min(requestedPage, pageCount), from = (page - 1) * pageSize;
        final JSONArray entries = new JSONArray();
        for (final Rule rule : filtered.subList(from, Math.min(from + pageSize, filtered.size()))) {
            entries.put(new JSONObject(rule.data().toString()).put("targetType", "ip")
                    .put("source", rule.data().optString("source", "manual")).put("active", active(rule))
                    .put("expiresAt", rule.data().optLong("expiresAt")).put("region", IpGeo.region(rule.range().baseAddress())));
        }
        return new JSONObject().put("rules", entries).put("total", filtered.size()).put("page", page)
                .put("pageSize", pageSize).put("pageCount", pageCount);
    }

    public synchronized void add(final String input, final String reason, final String currentAddress) throws Exception {
        add(input, reason, currentAddress, 0);
    }

    public synchronized void add(final String input, final String reason, final String currentAddress, final long durationSeconds) throws Exception {
        final IpRange range = IpRange.parse(input);
        if (range.contains(currentAddress)) throw new IllegalArgumentException("此网段包含你当前的访问 IP，请通过其他连接管理，避免封禁自己。");
        if (durationSeconds < 0 || durationSeconds > 3650L * 86400) throw new IllegalArgumentException("封禁时长应为 0（永久）至 3650 天。");
        if (reason != null && reason.length() > 200) throw new IllegalArgumentException("封禁备注最多 200 个字符。");
        final List<Rule> updated = new ArrayList<>(rules());
        updated.removeIf(entry -> entry.range().toString().equals(range.toString()));
        makeRoom(updated);
        updated.add(new Rule(new JSONObject().put("range", range.toString()).put("reason", reason == null ? "" : reason)
                .put("targetType", "ip").put("source", "manual").put("reasonCode", "manual")
                .put("created", now()).put("expiresAt", durationSeconds == 0 ? 0 : now() + durationSeconds * 1000), range));
        save(updated);
    }

    /** A missing duration preserves the deadline; an explicit duration restarts it from now. */
    public synchronized void update(final String input, final String note, final String currentAddress,
                                    final Long durationSeconds) throws Exception {
        update(input, note, currentAddress, durationSeconds, null);
    }

    public synchronized void update(final String input, final String note, final String currentAddress,
                                    final Long durationSeconds, final Integer occurrences) throws Exception {
        final IpRange range = IpRange.parse(input);
        if (note == null || note.length() > 200) throw new IllegalArgumentException("封禁备注最多 200 个字符。");
        if (durationSeconds != null && (durationSeconds < 0 || durationSeconds > 3650L * 86400)) {
            throw new IllegalArgumentException("封禁时长应为 0（永久）至 3650 天。");
        }
        if (occurrences != null && (occurrences < 0 || occurrences > 1_000_000)) {
            throw new IllegalArgumentException("自动风控次数应为 0 至 1000000 的整数。");
        }
        final List<Rule> updated = new ArrayList<>(rules());
        for (int i = 0; i < updated.size(); i++) {
            final Rule existing = updated.get(i);
            if (!existing.range().toString().equals(range.toString())) continue;
            if (occurrences != null && !existing.data().optString("source").equals("automatic")) {
                throw new IllegalArgumentException("仅自动风控记录可以修改累计次数。");
            }
            final long timestamp = now();
            final long expiresAt = durationSeconds == null ? existing.data().optLong("expiresAt")
                    : durationSeconds == 0 ? 0 : timestamp + durationSeconds * 1000;
            if ((expiresAt == 0 || expiresAt > timestamp) && range.contains(currentAddress)) {
                throw new IllegalArgumentException("此网段包含你当前的访问 IP，请通过其他连接管理，避免封禁自己。");
            }
            // Keep the original automatic/manual attribution and trigger as audit information.
            final JSONObject data = new JSONObject(existing.data().toString()).put("note", note)
                    .put("updated", timestamp).put("expiresAt", expiresAt);
            if (occurrences != null) data.put("occurrences", occurrences);
            updated.set(i, new Rule(data, range));
            save(updated);
            return;
        }
        throw new IllegalArgumentException("封禁记录已不存在，请刷新列表。");
    }

    /** An automatic detection cannot replace a manual rule or extend an existing active ban. */
    public synchronized void automatic(final String address, final org.b3log.solo.util.AttackTracker.Trigger trigger) throws Exception {
        if (org.b3log.solo.util.AttackTracker.internal(address) || blocked(address)) return;
        final IpRange range = IpRange.parse(address);
        final List<Rule> updated = new ArrayList<>(rules());
        for (int i = 0; i < updated.size(); i++) {
            final Rule previous = updated.get(i);
            if (!previous.range().toString().equals(range.toString()) || !previous.data().optString("source").equals("automatic")) continue;
            final int occurrences = Math.min(1_000_000, Math.max(0, previous.data().optInt("occurrences", 1)) + 1);
            final long days = occurrences == 1 ? 1 : occurrences == 2 ? 3 : occurrences == 3 ? 7 : 30;
            final JSONObject data = new JSONObject(previous.data().toString()).put("occurrences", occurrences)
                    .put("updated", now()).put("reasonCode", trigger.code()).put("reason", trigger.reason())
                    .put("expiresAt", trigger.code().equals("xss_payload") ? 0 : now() + days * 86_400_000L);
            updated.set(i, new Rule(data, range));
            save(updated);
            return;
        }
        updated.removeIf(entry -> !active(entry) && entry.range().toString().equals(range.toString()));
        makeRoom(updated);
        updated.add(new Rule(new JSONObject().put("targetType", "ip").put("range", range.toString())
                .put("source", "automatic").put("reasonCode", trigger.code()).put("reason", trigger.reason())
                .put("created", now()).put("occurrences", 1)
                .put("expiresAt", trigger.code().equals("xss_payload") ? 0 : now() + 86_400_000L), range));
        save(updated);
    }

    private void makeRoom(final List<Rule> updated) {
        // Keep recent expired rows for inspection; never evict an active manual rule.
        if (updated.size() >= 1024) updated.removeIf(rule -> !active(rule));
        if (updated.size() >= 1024) throw new IllegalArgumentException("封禁规则已达到 1024 条，请先清理规则。");
    }

    public synchronized void remove(final String input) throws Exception {
        final String range = IpRange.parse(input).toString();
        final List<Rule> updated = new ArrayList<>(rules());
        updated.removeIf(entry -> entry.range().toString().equals(range));
        save(updated);
    }

    private void save(final List<Rule> updated) throws Exception {
        final JSONArray data = new JSONArray();
        for (final Rule rule : updated) data.put(rule.data());
        optionMgmtService.storeDocument(OPTION_ID, "security", data.toString());
        snapshot = List.copyOf(updated);
    }
}
