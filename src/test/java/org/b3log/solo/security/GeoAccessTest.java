/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.security;

import java.lang.reflect.Field;
import org.b3log.solo.model.Option;
import org.b3log.solo.service.GeoAccessService;
import org.b3log.solo.service.OptionMgmtService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.util.IpGeo;
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
        inject(service, "optionMgmtService", new OptionMgmtService() { @Override public String addOrUpdateOption(final JSONObject value) { stored[0] = new JSONObject(value.toString()); return value.getString("oId"); } });
        service.save(new JSONObject().put("mode", "allow").put("countries", "cn").put("unknown", "deny"), "203.0.113.1");
        Assert.assertEquals(service.settings("203.0.113.1").getString("countries"), "CN");
        Assert.assertTrue(stored[0].getString(Option.OPTION_VALUE).contains("CN"));
        Assert.expectThrows(IllegalArgumentException.class, () -> service.save(new JSONObject().put("mode", "deny").put("countries", "CN"), "203.0.113.1"));
        Assert.assertEquals(service.settings("203.0.113.1").getString("mode"), "allow");
        Assert.expectThrows(IllegalArgumentException.class, () -> service.save(new JSONObject().put("mode", "deny").put("regions", "CN/浙江/杭州"), "203.0.113.1"));
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
