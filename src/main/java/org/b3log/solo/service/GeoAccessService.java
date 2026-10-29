/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.service;

import java.util.Arrays;
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
            final String[] parts = value.strip().split("/", -1);
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

    public record Policy(String mode, Set<String> countries, List<Area> regions, String unknown) {
        public static Policy read(final JSONObject value) {
            final String mode = value.optString("mode", "off");
            final String unknown = value.optString("unknown", mode.equals("allow") ? "deny" : "allow");
            if (!Set.of("off", "allow", "deny").contains(mode) || !Set.of("allow", "deny").contains(unknown)) {
                throw new IllegalArgumentException("地区访问规则模式不正确。");
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
                if (!line.isBlank()) areas.add(Area.read(line));
            }
            if (areas.size() > 128) throw new IllegalArgumentException("省市规则最多 128 条。");
            if (!mode.equals("off") && codes.isEmpty() && areas.isEmpty()) throw new IllegalArgumentException("请至少填写一个国家代码或省市规则。");
            return new Policy(mode, Set.copyOf(codes), List.copyOf(areas), unknown);
        }
        public boolean blocks(final String code) {
            return blocks(new IpGeo.Location("", code == null ? "" : code));
        }
        public boolean blocks(final IpGeo.Location location) {
            if (mode.equals("off")) return false;
            final String code = location.countryCode().toUpperCase(Locale.ROOT);
            if (!COUNTRIES.contains(code)) return unknown.equals("deny");
            boolean match = countries.contains(code);
            boolean incomplete = false;
            for (final Area area : regions) {
                if (!area.country().equals(code)) continue;
                if (location.province().isBlank()) { incomplete = true; continue; }
                if (!area.same(area.province(), location.province())) continue;
                if (area.city().isEmpty()) { match = true; break; }
                if (location.city().isBlank()) { incomplete = true; continue; }
                if (area.same(area.city(), location.city())) { match = true; break; }
            }
            if (!match && incomplete) return unknown.equals("deny");
            return mode.equals("allow") != match;
        }
        JSONObject json() {
            return new JSONObject().put("mode", mode).put("countries", String.join(",", new TreeSet<>(countries)))
                    .put("regions", String.join("\n", regions.stream().map(Area::text).toList())).put("unknown", unknown);
        }
    }

    private synchronized Policy policy() throws Exception {
        if (current == null) {
            final JSONObject option = optionQueryService.getOptionById(OPTION_ID);
            current = Policy.read(option == null ? new JSONObject() : new JSONObject(option.optString(Option.OPTION_VALUE, "{}")));
        }
        return current;
    }

    public boolean blocked(final String address) throws Exception {
        if (!enabled()) return false;
        final Policy policy = policy();
        return !policy.mode().equals("off") && policy.blocks(location(address));
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
        final Policy next = Policy.read(value);
        if (next.blocks(location(address))) throw new IllegalArgumentException("该规则会封禁你当前的管理连接，请调整地区列表或未知位置策略。");
        optionMgmtService.addOrUpdateOption(new JSONObject().put(Keys.OBJECT_ID, OPTION_ID)
                .put(Option.OPTION_CATEGORY, "security").put(Option.OPTION_VALUE, next.json().toString()));
        current = next;
    }
}
