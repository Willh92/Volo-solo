/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.security;

import java.lang.reflect.Field;
import org.b3log.solo.model.Option;
import org.b3log.solo.service.GeoAccessService;
import org.b3log.solo.service.OptionMgmtService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.util.IpGeo;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.Test;

public class GeoAccessTest {
    @Test public void countryAllowAndDenyModesHandleUnknownLocationsExplicitly() {
        final var deny = GeoAccessService.Policy.read(new JSONObject().put("mode", "deny").put("countries", "us,ca"));
        Assert.assertTrue(deny.blocks("US")); Assert.assertFalse(deny.blocks("CN")); Assert.assertFalse(deny.blocks(""));
        final var allow = GeoAccessService.Policy.read(new JSONObject().put("mode", "allow").put("countries", "CN").put("unknown", "deny"));
        Assert.assertFalse(allow.blocks("CN")); Assert.assertTrue(allow.blocks("US")); Assert.assertTrue(allow.blocks(""));
        Assert.assertFalse(GeoAccessService.Policy.read(new JSONObject()).blocks("US"));
        for (final JSONObject invalid : new JSONObject[]{new JSONObject().put("mode", "allow"),
                new JSONObject().put("mode", "deny").put("countries", "China"), new JSONObject().put("mode", "invalid")}) {
            Assert.expectThrows(IllegalArgumentException.class, () -> GeoAccessService.Policy.read(invalid));
        }
    }
    @Test public void policyPersistsAndCannotBlockItsOwnManagementConnection() throws Exception {
        final JSONObject[] stored = {null};
        final GeoAccessService service = new GeoAccessService() {
            @Override protected IpGeo.Location location(final String address) { return new IpGeo.Location("中国 · 浙江省 · 杭州市", "CN", "浙江省", "杭州市"); }
            @Override protected boolean enabled() { return true; }
        };
        inject(service, "optionQueryService", new OptionQueryService() { @Override public JSONObject getOptionById(final String id) { return stored[0]; } });
        inject(service, "optionMgmtService", new OptionMgmtService() { @Override public void storeDocument(String id, String category, String value) { stored[0] = new JSONObject().put("oId", id).put(Option.OPTION_CATEGORY, category).put(Option.OPTION_VALUE, value); } });
        service.save(new JSONObject().put("mode", "allow").put("countries", "cn").put("unknown", "deny"), "203.0.113.1");
        Assert.assertEquals(service.settings("203.0.113.1").getJSONArray("rules").getJSONObject(0).getString("countries"), "CN");
        Assert.assertTrue(stored[0].getString(Option.OPTION_VALUE).contains("CN"));
        Assert.expectThrows(IllegalArgumentException.class, () -> service.save(new JSONObject().put("mode", "deny").put("countries", "CN"), "203.0.113.1"));
        Assert.assertTrue(service.settings("203.0.113.1").getBoolean("active"));
        Assert.assertEquals(service.settings("203.0.113.1").getJSONArray("rules").getJSONObject(0).getString("onMiss"), "deny");
        Assert.expectThrows(IllegalArgumentException.class, () -> service.save(new JSONObject().put("mode", "deny").put("regions", "CN/浙江/杭州"), "203.0.113.1"));
    }
    @Test public void mixedRulesUseTheFirstEnabledMatchAndTheExplicitDefault() {
        final JSONObject city = rule("allow", "", "CN/广东/深圳");
        final JSONObject province = rule("deny", "", "CN/广东");
        final JSONObject countries = rule("allow", "CN,CA", "");
        final var policy = GeoAccessService.Policy.read(policy("deny", city, province, countries));
        Assert.assertFalse(policy.blocks(new IpGeo.Location("", "CN", "广东省", "深圳市")));
        Assert.assertTrue(policy.blocks(new IpGeo.Location("", "CN", "广东省", "广州市")));
        Assert.assertFalse(policy.blocks(new IpGeo.Location("", "CN", "浙江省", "杭州市")));
        Assert.assertFalse(policy.blocks("CA"));
        Assert.assertTrue(policy.blocks("US"));
        Assert.assertTrue(GeoAccessService.Policy.read(policy("allow", province, city)).blocks(new IpGeo.Location("", "CN", "广东", "深圳")), "List order controls exceptions");
        Assert.assertFalse(GeoAccessService.Policy.read(policy("deny", province.put("enabled", false), countries)).blocks("CN"), "Disabled rules must not participate");
        Assert.assertFalse(GeoAccessService.Policy.read(policy("allow", rule("deny", "US", ""), rule("allow", "CN", ""))).blocks("CA"));
    }
    @Test public void missingPriorityRuleDataUsesUnknownInsteadOfFallingThrough() {
        final JSONObject value = policy("allow", rule("deny", "", "CN/广东/深圳"), rule("allow", "CN", ""));
        Assert.assertTrue(GeoAccessService.Policy.read(value).blocks(new IpGeo.Location("", "CN", "广东", "")));
        Assert.assertFalse(GeoAccessService.Policy.read(value.put("unknown", "allow")).blocks(new IpGeo.Location("", "CN", "广东", "")));
        Assert.assertFalse(GeoAccessService.Policy.read(value).blocks(new IpGeo.Location("", "CN", "浙江", "")), "Missing a city is irrelevant when the province differs");
        Assert.assertFalse(GeoAccessService.Policy.read(value.put("active", false)).blocks(""));
        Assert.assertTrue(GeoAccessService.Policy.read(policy("deny")).blocks("CN"), "An empty active list can use deny-by-default");
        Assert.assertFalse(GeoAccessService.Policy.read(policy("allow")).blocks("CN"));
    }
    @Test public void orderedRulesPersistAndReloadWithoutChangingBehavior() throws Exception {
        final JSONObject[] stored = {new JSONObject().put(Option.OPTION_VALUE, new JSONObject().put("mode", "deny").put("countries", "US").toString())};
        final GeoAccessService first = service(stored);
        Assert.assertTrue(first.blocked("US")); Assert.assertFalse(first.blocked("CN"));
        first.save(policy("deny", rule("allow", "CN", "").put("name", "允许国内"), rule("deny", "US", "")), "CN");
        Assert.assertEquals(new JSONObject(stored[0].getString(Option.OPTION_VALUE)).getInt("version"), 3);
        final GeoAccessService reloaded = service(stored);
        Assert.assertFalse(reloaded.blocked("CN")); Assert.assertTrue(reloaded.blocked("US")); Assert.assertTrue(reloaded.blocked("CA"));
        Assert.assertEquals(reloaded.settings("CN").getJSONArray("rules").getJSONObject(0).getString("name"), "允许国内");
        Assert.expectThrows(IllegalArgumentException.class, () -> reloaded.save(policy("allow", rule("deny", "CN", "")), "CN"));
        Assert.assertFalse(reloaded.blocked("CN"));
        Assert.assertEquals(service(stored).settings("CN").getJSONArray("rules").length(), 2, "Rejected saves must preserve the stored snapshot");
        Assert.expectThrows(IllegalArgumentException.class, () -> reloaded.save(new JSONObject(), "CN"));
        Assert.expectThrows(IllegalArgumentException.class, () -> reloaded.save(new JSONObject().put("mode", JSONObject.NULL), "CN"));
        Assert.assertEquals(service(stored).settings("CN").getJSONArray("rules").length(), 2, "Malformed legacy writes must not disable a deployed rule list");
    }
    @Test public void malformedOrderedRulesAreRejectedBeforeSaving() {
        final JSONArray tooMany = new JSONArray(); for (int i = 0; i < 129; i++) tooMany.put(rule("deny", "US", ""));
        for (final JSONObject invalid : new JSONObject[]{policy("invalid"), policy("allow").put("rules", "bad"),
                policy("allow").put("rules", tooMany), policy("allow").put("active", "true"),
                policy("allow", rule("invalid", "US", "")), policy("allow", rule("allow", "", "")),
                policy("allow", rule("deny", "US", "").put("enabled", "true")),
                policy("allow", rule("deny", "US", "").put("name", "x".repeat(81))),
                policy("allow", rule("deny", "XX", "")), policy("allow").put("unknown", "off")}) {
            Assert.expectThrows(IllegalArgumentException.class, () -> GeoAccessService.Policy.read(invalid));
        }
    }
    @Test public void eachRuleCanContinueAllowOrDenyWhenItsRangeDoesNotMatch() {
        final JSONObject denyUS = rule("deny", "US", "").put("onMiss", "next");
        final JSONObject allowCN = rule("allow", "CN", "").put("onMiss", "deny");
        final JSONObject value = policy("allow", denyUS, allowCN).put("version", 3);
        Assert.assertTrue(GeoAccessService.Policy.read(value).blocks("US"));
        Assert.assertFalse(GeoAccessService.Policy.read(value).blocks("CN"));
        Assert.assertTrue(GeoAccessService.Policy.read(value).blocks("CA"));
        denyUS.put("onMiss", "allow");
        Assert.assertFalse(GeoAccessService.Policy.read(value).blocks("CA"));
        denyUS.put("enabled", false);
        Assert.assertTrue(GeoAccessService.Policy.read(value).blocks("CA"));
        Assert.expectThrows(IllegalArgumentException.class, () -> GeoAccessService.Policy.read(policy("allow", rule("deny", "US", "").put("onMiss", "invalid"))));
    }
    @Test public void countryCodesAndBothSlashStylesAreAcceptedInTheRangeField() {
        for (final String range : new String[]{"CN/浙江省/杭州市", "CN\\浙江省\\杭州市", "CN\\浙江省/杭州市"}) {
            final var parsed = GeoAccessService.Policy.read(policy("allow", rule("deny", "", range)));
            Assert.assertTrue(parsed.blocks(new IpGeo.Location("", "CN", "浙江", "杭州")));
            Assert.assertFalse(parsed.blocks(new IpGeo.Location("", "CN", "浙江", "宁波")));
        }
        Assert.assertTrue(GeoAccessService.Policy.read(policy("allow", rule("deny", "", "cn"))).blocks("CN"));
        Assert.assertFalse(GeoAccessService.Policy.read(policy("allow", rule("deny", "", "cn"))).blocks("US"));
    }
    @Test public void multibyteRulesAreBoundedByTheirStoredUtf8Size() {
        final JSONArray entries = new JSONArray();
        for (int i = 0; i < 90; i++) entries.put(rule("deny", "", "CN/" + "省".repeat(80) + "/" + "市".repeat(80)).put("name", "注".repeat(80)));
        final JSONObject value = policy("allow").put("rules", entries);
        Assert.assertTrue(value.toString().length() < 60000, "Character count alone would incorrectly accept this value");
        Assert.expectThrows(IllegalArgumentException.class, () -> GeoAccessService.Policy.read(value));
    }
    private static JSONObject rule(final String action, final String countries, final String regions) {
        return new JSONObject().put("action", action).put("countries", countries).put("regions", regions);
    }
    private static JSONObject policy(final String fallback, final JSONObject... rules) {
        return new JSONObject().put("active", true).put("defaultAction", fallback).put("unknown", "deny").put("rules", new JSONArray(rules));
    }
    private static GeoAccessService service(final JSONObject[] stored) throws Exception {
        final GeoAccessService service = new GeoAccessService() {
            @Override protected IpGeo.Location location(final String address) { return new IpGeo.Location("", address); }
            @Override protected boolean enabled() { return true; }
        };
        inject(service, "optionQueryService", new OptionQueryService() { @Override public JSONObject getOptionById(final String id) { return stored[0]; } });
        inject(service, "optionMgmtService", new OptionMgmtService() { @Override public void storeDocument(String id, String category, String value) { stored[0] = new JSONObject().put("oId", id).put(Option.OPTION_CATEGORY, category).put(Option.OPTION_VALUE, value); } });
        return service;
    }
    @Test public void provinceAndCityRulesMatchExactAdministrativeAreasAndHandleMissingData() {
        final var deny = GeoAccessService.Policy.read(new JSONObject().put("mode", "deny").put("regions", "CN/浙江省\nCN/广东/深圳"));
        Assert.assertTrue(deny.blocks(new IpGeo.Location("", "CN", "浙江", "杭州市")));
        Assert.assertTrue(deny.blocks(new IpGeo.Location("", "CN", "广东省", "深圳市")));
        Assert.assertFalse(deny.blocks(new IpGeo.Location("", "CN", "广东省", "广州市")));
        Assert.assertFalse(deny.blocks(new IpGeo.Location("", "CN", "浙江东部", "杭州")), "No fuzzy substring matching");
        Assert.assertFalse(deny.blocks(new IpGeo.Location("", "US", "浙江", "杭州")), "A locality name never matches a different country");
        final var allow = GeoAccessService.Policy.read(new JSONObject().put("mode", "allow").put("regions", "CN/广东/深圳"));
        Assert.assertFalse(allow.blocks(new IpGeo.Location("", "CN", "广东省", "深圳市")));
        Assert.assertTrue(allow.blocks(new IpGeo.Location("", "CN", "广东省", "")), "Missing required city uses the unknown policy");
        Assert.assertTrue(allow.blocks(new IpGeo.Location("", "CN", "", "")));
        Assert.assertTrue(allow.blocks("US"));
        final var broad = GeoAccessService.Policy.read(new JSONObject().put("mode", "allow").put("countries", "CN").put("regions", "US/California"));
        Assert.assertFalse(broad.blocks("CN"), "A country rule includes all its provinces even when more specific rules exist");
        for (final String invalid : new String[]{"浙江省", "XX/浙江省", "CN//深圳", "CN/广东/深圳/南山"}) {
            Assert.expectThrows(IllegalArgumentException.class, () -> GeoAccessService.Policy.read(new JSONObject().put("regions", invalid)));
        }
    }
    private static void inject(final Object target, final String name, final Object value) throws Exception {
        final Field field = GeoAccessService.class.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
}
