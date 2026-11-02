/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.security;

import java.util.HashMap;
import java.util.Map;
import org.b3log.latke.repository.Transaction;
import org.b3log.solo.model.Option;
import org.b3log.solo.repository.OptionRepository;
import org.b3log.solo.service.OptionMgmtService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.util.OptionDocuments;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.Test;

public class OptionDocumentsTest {
    @Test public void largeDocumentsRoundTripWithinExistingColumnAndOldChunksAreRemovedAtomically() throws Exception {
        LatkeTestSupport.configure(); final var local = new java.util.Properties(); local.setProperty("runtimeDatabase", "NONE"); org.b3log.latke.Latkes.setLocalProps(local);
        final Map<String, JSONObject> rows = new HashMap<>(); final boolean[] fail = {false};
        final OptionRepository repository = new OptionRepository() {
            @Override public Transaction beginTransaction() {
                final Map<String, JSONObject> before = new HashMap<>(rows);
                return new Transaction() { boolean active = true;
                    public boolean isActive() { return active; }
                    public void commit() { active = false; }
                    public void rollback() { rows.clear(); rows.putAll(before); active = false; }
                };
            }
            @Override public JSONObject get(String id) { return rows.get(id); }
            @Override public String add(JSONObject value) {
                if (fail[0] && rows.size() > 1) throw new IllegalStateException("simulated storage failure");
                Assert.assertTrue(value.getString(Option.OPTION_VALUE).length() <= 10240);
                rows.put(value.getString("oId"), new JSONObject(value.toString())); return value.getString("oId");
            }
            @Override public void update(String id, JSONObject value, String... fields) { rows.put(id, new JSONObject(value.toString())); }
            @Override public void remove(String id) { rows.remove(id); }
        };
        final OptionMgmtService management = new OptionMgmtService();
        final var field = OptionMgmtService.class.getDeclaredField("optionRepository"); field.setAccessible(true); field.set(management, repository);
        final OptionQueryService query = new OptionQueryService() { @Override public JSONObject getOptionById(String id) { return rows.get(id); } };
        management.storeDocument("voloIpBans", "security", "[]");
        Assert.assertEquals(OptionDocuments.read(query, "voloIpBans", ""), "[]");
        final String large = "[\"" + "规则😀".repeat(11000) + "\"]";
        management.storeDocument("voloIpBans", "security", large);
        Assert.assertTrue(rows.size() > 2); Assert.assertEquals(OptionDocuments.read(query, "voloIpBans", ""), large);
        final int size = rows.size(); fail[0] = true;
        Assert.expectThrows(Exception.class, () -> management.storeDocument("voloIpBans", "security", large + " "));
        Assert.assertEquals(rows.size(), size); Assert.assertEquals(OptionDocuments.read(query, "voloIpBans", ""), large);
        fail[0] = false; management.storeDocument("voloIpBans", "security", "[]");
        Assert.assertEquals(rows.size(), 1); Assert.assertEquals(OptionDocuments.read(query, "voloIpBans", ""), "[]");
        LatkeTestSupport.configure();
    }
    @Test public void dynamicLoginRouteDefaultsAndLegacyPathsAreUnambiguous() {
        try {
            org.b3log.solo.util.AdminPaths.configureLogin("/private-sign-in");
            Assert.assertEquals(org.b3log.solo.util.AdminPaths.login(), "/private-sign-in");
            Assert.assertTrue(org.b3log.solo.util.AdminPaths.legacyEntry("/start"));
            Assert.assertFalse(org.b3log.solo.util.AdminPaths.legacyEntry("/private-sign-in"));
            org.b3log.solo.util.AdminPaths.configureLogin("");
            Assert.assertEquals(org.b3log.solo.util.AdminPaths.login(), "/start");
            for (String invalid : new String[]{"https://evil.example/", "/console", "/captcha", "/search", "/foo/../bar"})
                Assert.expectThrows(IllegalArgumentException.class, () -> org.b3log.solo.util.AdminPaths.validateLogin(invalid));
        } finally { org.b3log.solo.util.AdminPaths.configureLogin(null); }
    }
}
