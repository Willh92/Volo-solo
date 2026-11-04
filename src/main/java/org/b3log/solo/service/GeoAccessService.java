/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.service;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.service.annotation.Service;
import org.b3log.solo.model.Option;
import org.b3log.solo.util.IpGeo;
import org.json.JSONArray;
import org.json.JSONObject;

/** Offline country/province/city policy, independent from the optional request-rate firewall. */
@Service
public class GeoAccessService {
    private static final String OPTION_ID = "voloGeoAccess";
    private static final Set<String> COUNTRIES = Set.copyOf(Arrays.asList(Locale.getISOCountries()));
    @Inject private OptionQueryService optionQueryService;
    @Inject private OptionMgmtService optionMgmtService;
    private volatile Policy current;

    public record Area(String country, String province, String city) {
        private static Area read(final String value) {
            final String[] parts = value.strip().replace('\\', '/').split("/", -1);
            if (parts.length < 2 || parts.length > 3 || !COUNTRIES.contains(parts[0].strip().toUpperCase(Locale.ROOT))) {
                throw new IllegalArgumentException("省市规则请使用 国家代码/省，或 国家代码/省/市。");
            }
            for (int i = 1; i < parts.length; i++) {
                parts[i] = parts[i].strip();
                if (parts[i].isEmpty() || parts[i].length() > 80 || parts[i].chars().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("省、市名称不能为空或超过 80 个字符。");
                }
            }
            return new Area(parts[0].strip().toUpperCase(Locale.ROOT), parts[1], parts.length == 3 ? parts[2] : "");
        }
        String text() { return country + "/" + province + (city.isEmpty() ? "" : "/" + city); }
        private boolean same(final String expected, final String actual) {
            return normalize(country, expected).equals(normalize(country, actual));
        }
        private static String normalize(final String country, final String value) {
            String result = value.strip().toLowerCase(Locale.ROOT);
            if (country.equals("CN")) {
                // Accept common administrative suffixes without substring/fuzzy matching.
                for (final String suffix : List.of("壮族自治区", "回族自治区", "维吾尔自治区", "特别行政区", "自治区", "省", "市")) {
                    if (result.length() > suffix.length() && result.endsWith(suffix)) return result.substring(0, result.length() - suffix.length());
                }
            }
            return result;
        }
    }

    private enum Match { YES, NO, UNKNOWN }

    public record Rule(String action, boolean enabled, String name, Set<String> countries, List<Area> regions, String onMiss) {
        private static Rule read(final JSONObject value) {
            final String action = GeoAccessService.action(value, "action", "deny");
            final String name = value.optString("name").strip();
            if (name.length() > 80 || name.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("地区规则备注最多 80 个字符，不能包含控制字符。");
            }
            final String input = value.optString("countries");
            if (input.length() > 1000) throw new IllegalArgumentException("国家或地区列表过长。");
            final Set<String> codes = new TreeSet<>();
            for (final String part : input.toUpperCase(Locale.ROOT).split("[,，\\s]+")) {
                if (part.isEmpty()) continue;
                if (!COUNTRIES.contains(part)) throw new IllegalArgumentException("无效的国家或地区代码：" + part);
                codes.add(part);
            }
            final String regionInput = value.optString("regions");
            if (regionInput.length() > 10000) throw new IllegalArgumentException("省市规则列表过长。");
            final var areas = new java.util.LinkedHashSet<Area>();
            for (final String line : regionInput.split("[\\r\\n,，]+")) {
                if (line.isBlank()) continue;
                final String country = line.strip().toUpperCase(Locale.ROOT);
                if (COUNTRIES.contains(country)) codes.add(country);
                else areas.add(Area.read(line));
            }
            if (areas.size() > 128) throw new IllegalArgumentException("省市规则最多 128 条。");
            if (codes.isEmpty() && areas.isEmpty()) throw new IllegalArgumentException("每条规则至少填写一个国家代码或省市规则。");
            final Object onMiss = value.opt("onMiss");
            if (onMiss != null && (!(onMiss instanceof String) || !Set.of("next", "allow", "deny").contains(onMiss))) {
                throw new IllegalArgumentException("未命中规则时请选择继续下一条、允许或拒绝。");
            }
            return new Rule(action, flag(value, "enabled", true), name, Set.copyOf(codes), List.copyOf(areas), onMiss == null ? "next" : (String) onMiss);
        }
        private Match match(final IpGeo.Location location) {
            final String code = location.countryCode().toUpperCase(Locale.ROOT);
            if (countries.contains(code)) return Match.YES;
            boolean incomplete = false;
            for (final Area area : regions) {
                if (!area.country().equals(code)) continue;
                if (location.province().isBlank()) { incomplete = true; continue; }
                if (!area.same(area.province(), location.province())) continue;
                if (area.city().isEmpty()) return Match.YES;
                if (location.city().isBlank()) { incomplete = true; continue; }
                if (area.same(area.city(), location.city())) return Match.YES;
            }
            return incomplete ? Match.UNKNOWN : Match.NO;
        }
        private JSONObject json() {
            return new JSONObject().put("action", action).put("enabled", enabled).put("name", name).put("onMiss", onMiss)
                    .put("countries", String.join(",", new TreeSet<>(countries)))
                    .put("regions", String.join("\n", regions.stream().map(Area::text).toList()));
        }
    }

    public record Policy(boolean active, String defaultAction, List<Rule> rules, String unknown) {
        public static Policy read(final JSONObject value) {
            if (value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 60000) {
                throw new IllegalArgumentException("地区规则内容过多，请减少规则或备注。");
            }
            if (!value.has("rules") && !value.has("active") && !value.has("defaultAction")) return legacy(value);
            final JSONArray data = value.optJSONArray("rules");
            if (data == null || data.length() > 128) throw new IllegalArgumentException("地区规则必须为列表，最多 128 条。");
            final List<Rule> rules = new ArrayList<>();
            for (int i = 0; i < data.length(); i++) {
                final JSONObject rule = data.optJSONObject(i);
                if (rule == null) throw new IllegalArgumentException("地区规则格式不正确。");
                rules.add(Rule.read(rule));
            }
            String fallback = action(value, "defaultAction", "allow");
            if (value.optInt("version", 2) < 3 && !rules.isEmpty()) {
                for (int i = rules.size() - 1; i >= 0; i--) {
                    final Rule last = rules.get(i);
                    if (!last.enabled()) continue;
                    if (!data.getJSONObject(i).has("onMiss")) {
                        rules.set(i, new Rule(last.action(), last.enabled(), last.name(), last.countries(), last.regions(), fallback));
                        fallback = "allow";
                    }
                    break;
                }
            }
            return new Policy(flag(value, "active", false), fallback,
                    List.copyOf(rules), action(value, "unknown", "allow"));
        }
        /** Preserve the behavior of the previously deployed single-mode configuration. */
        private static Policy legacy(final JSONObject value) {
            final Object inputMode = value.has("mode") ? value.opt("mode") : "off";
            if (!(inputMode instanceof String mode) || !Set.of("off", "allow", "deny").contains(mode)) {
                throw new IllegalArgumentException("地区访问规则模式不正确。");
            }
            final String fallback = mode.equals("allow") ? "deny" : "allow";
            final JSONObject rule = new JSONObject(value.toString()).put("action", mode.equals("allow") ? "allow" : "deny").put("onMiss", fallback);
            final List<Rule> rules = mode.equals("off") && value.optString("countries").isBlank() && value.optString("regions").isBlank()
                    ? List.of() : List.of(Rule.read(rule));
            return new Policy(!mode.equals("off"), rules.isEmpty() ? fallback : "allow", rules, action(value, "unknown", fallback));
        }
        public boolean blocks(final String code) {
            return blocks(new IpGeo.Location("", code == null ? "" : code));
        }
        public boolean blocks(final IpGeo.Location location) {
            if (!active) return false;
            if (!COUNTRIES.contains(location.countryCode().toUpperCase(Locale.ROOT))) return unknown.equals("deny");
            for (final Rule rule : rules) {
                if (!rule.enabled()) continue;
                final Match match = rule.match(location);
                if (match == Match.YES) return rule.action().equals("deny");
                // Do not bypass a higher-priority regional rule when its required location data is missing.
                if (match == Match.UNKNOWN) return unknown.equals("deny");
                if (!rule.onMiss().equals("next")) return rule.onMiss().equals("deny");
            }
            return defaultAction.equals("deny");
        }
        JSONObject json() {
            final JSONArray data = new JSONArray();
            for (final Rule rule : rules) data.put(rule.json());
            return new JSONObject().put("version", 3).put("active", active).put("defaultAction", defaultAction)
                    .put("unknown", unknown).put("rules", data);
        }
    }

    private static String action(final JSONObject value, final String key, final String fallback) {
        final Object action = value.opt(key);
        if (action == null) return fallback;
        if (!(action instanceof String text) || !Set.of("allow", "deny").contains(text)) {
            throw new IllegalArgumentException("地区规则的允许或拒绝设置不正确。");
        }
        return (String) action;
    }

    private static boolean flag(final JSONObject value, final String key, final boolean fallback) {
        final Object flag = value.opt(key);
        if (flag == null) return fallback;
        if (!(flag instanceof Boolean)) throw new IllegalArgumentException("地区规则开关格式不正确。");
        return (Boolean) flag;
    }

    private Policy policy() throws Exception {
        final Policy cached = current;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (current == null) {
                current = Policy.read(new JSONObject(org.b3log.solo.util.OptionDocuments.read(optionQueryService, OPTION_ID, "{}")));
            }
            return current;
        }
    }

    public boolean blocked(final String address) throws Exception {
        if (!enabled()) return false;
        final Policy policy = policy();
        return policy.active() && policy.blocks(location(address));
    }

    protected IpGeo.Location location(final String address) { return IpGeo.details(address); }
    protected boolean enabled() { return !"false".equalsIgnoreCase(Latkes.getLocalProperty("geoAccessEnabled")); }

    public JSONObject settings(final String address) throws Exception {
        final IpGeo.Location location = location(address);
        final String code = location.countryCode();
        final String name = code.isEmpty() ? "无法识别" : new Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.SIMPLIFIED_CHINESE);
        return policy().json().put("currentCountry", code).put("currentCountryName", name).put("currentRegion", location.region())
                .put("enabled", enabled());
    }

    public synchronized void save(final JSONObject value, final String address) throws Exception {
        if (!value.has("rules") && !value.has("mode")) throw new IllegalArgumentException("缺少地区访问规则，请重新加载设置后重试。");
        final Policy next = Policy.read(value);
        if (next.blocks(location(address))) throw new IllegalArgumentException("该规则会封禁你当前的管理连接，请调整地区列表或未知位置策略。");
        final JSONObject data = next.json();
        if (data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 60000) {
            throw new IllegalArgumentException("地区规则内容过多，请减少规则或备注。");
        }
        optionMgmtService.storeDocument(OPTION_ID, "security", data.toString());
        current = next;
    }
}
