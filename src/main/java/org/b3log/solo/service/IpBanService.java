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

    private synchronized List<Rule> rules() throws Exception {
        if (snapshot == null) {
            final JSONObject option = optionQueryService.getOptionById(OPTION_ID);
            final JSONArray data = option == null ? new JSONArray() : new JSONArray(option.optString(Option.OPTION_VALUE, "[]"));
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
        for (final Rule entry : rules()) if (entry.range().contains(bytes)) return true;
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
        if (requestedPage < 1 || pageSize < 1 || pageSize > 50 || keyword.length() > 100) {
            throw new IllegalArgumentException("封禁列表查询参数不正确。");
        }
        final String search = keyword.strip().toLowerCase(Locale.ROOT);
        final List<Rule> filtered = rules().stream().filter(rule -> search.isEmpty()
                || rule.range().toString().contains(search)
                || rule.data().optString("reason").toLowerCase(Locale.ROOT).contains(search))
                .sorted(java.util.Comparator.comparingLong((Rule rule) -> rule.data().optLong("created")).reversed())
                .toList();
        final int pageCount = Math.max(1, (filtered.size() + pageSize - 1) / pageSize);
        final int page = Math.min(requestedPage, pageCount), from = (page - 1) * pageSize;
        final JSONArray entries = new JSONArray();
        for (final Rule rule : filtered.subList(from, Math.min(from + pageSize, filtered.size()))) {
            entries.put(new JSONObject(rule.data().toString()).put("region", IpGeo.region(rule.range().baseAddress())));
        }
        return new JSONObject().put("rules", entries).put("total", filtered.size()).put("page", page)
                .put("pageSize", pageSize).put("pageCount", pageCount);
    }

    public synchronized void add(final String input, final String reason, final String currentAddress) throws Exception {
        final IpRange range = IpRange.parse(input);
        if (range.contains(currentAddress)) throw new IllegalArgumentException("此网段包含你当前的访问 IP，请通过其他连接管理，避免封禁自己。");
        if (reason != null && reason.length() > 200) throw new IllegalArgumentException("封禁备注最多 200 个字符。");
        final List<Rule> updated = new ArrayList<>(rules());
        for (final Rule entry : updated) if (entry.range().toString().equals(range.toString())) return;
        if (updated.size() >= 1024) throw new IllegalArgumentException("封禁规则已达到 1024 条，请先清理过期规则。");
        updated.add(new Rule(new JSONObject().put("range", range.toString()).put("reason", reason == null ? "" : reason)
                .put("created", System.currentTimeMillis()), range));
        save(updated);
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
        optionMgmtService.addOrUpdateOption(new JSONObject().put(Keys.OBJECT_ID, OPTION_ID)
                .put(Option.OPTION_CATEGORY, "security").put(Option.OPTION_VALUE, data.toString()));
        snapshot = List.copyOf(updated);
    }
}
