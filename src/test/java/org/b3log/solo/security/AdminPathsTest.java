/*
 * Bolo - A stable and beautiful blogging system based in Solo.
 * Copyright (c) 2020-present, https://github.com/bolo-blog
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.b3log.solo.security;

import java.util.HashMap;
import java.util.Properties;
import org.b3log.latke.Latkes;
import org.b3log.solo.util.AdminPaths;
import org.testng.Assert;
import org.testng.annotations.Test;

public class AdminPathsTest {
    @Test public void onlyAdministratorsReceiveManagementNavigationAndConfiguredPaths() throws Exception {
        try {
            configure("/private-panel"); Latkes.setContextPath("/blog");
            final var original = new java.util.ArrayList<org.json.JSONObject>();
            original.add(new org.json.JSONObject().put("pageTitle", "自定义链接"));
            Assert.assertEquals(AdminPaths.navigation(original, false).size(), 1);
            final var administrator = AdminPaths.navigation(original, true);
            Assert.assertEquals(administrator.size(), 3); Assert.assertEquals(original.size(), 1, "Do not mutate shared/stored navigation");
            Assert.assertEquals(administrator.get(1).getString("pagePermalink"), Latkes.getServePath() + "/private-panel");
            Assert.assertEquals(administrator.get(2).getString("pagePermalink"), Latkes.getServePath() + "/logout");
            Assert.assertEquals(administrator.get(1).getString("pageOpenTarget"), "_self");
            Assert.assertEquals(administrator.get(1).getString("pageIcon"), "/blog/images/admin-panel.svg");
            Assert.assertEquals(AdminPaths.navigation(original, false).size(), 1, "An admin request cannot affect later guest requests");
            final var visibleLogin = AdminPaths.navigation(original, false, true);
            Assert.assertEquals(visibleLogin.size(), 2);
            Assert.assertEquals(visibleLogin.get(1).getString("pagePermalink"), Latkes.getServePath() + "/private-panel/login");
            Assert.assertEquals(AdminPaths.navigation(original, true, true).size(), 3, "The visitor switch must not add a login link for an administrator");
        } finally { LatkeTestSupport.configure(); }
    }
    @Test public void missingSettingHasDefaultEntryPaths() throws Exception {
        LatkeTestSupport.configure();
        Assert.assertEquals(AdminPaths.panel(), "/admin-index.do");
        Assert.assertEquals(AdminPaths.login(), "/start");
        Assert.assertEquals(AdminPaths.pageBase(), "/");
    }

    private void configure(final String value) throws Exception {
        LatkeTestSupport.configure();
        final Properties properties = new Properties();
        properties.setProperty("adminPath", value);
        Latkes.setLocalProps(properties);
    }

    @Test public void blankPreservesExistingBookmarks() throws Exception {
        try {
            configure("");
            Assert.assertEquals(AdminPaths.panel(), "/admin-index.do");
            Assert.assertEquals(AdminPaths.login(), "/start");
            Assert.assertEquals(AdminPaths.route("/admin-main.do"), "/admin-main.do");
            Assert.assertFalse(AdminPaths.legacyEntry("/start"));
            Assert.assertFalse(AdminPaths.reserved("/an-article"));
        } finally { LatkeTestSupport.configure(); }
    }

    @Test public void customRoutesUseOneLocalPrefix() throws Exception {
        try {
            configure("/author/private-panel");
            Assert.assertEquals(AdminPaths.route("/start"), "/author/private-panel/login");
            Assert.assertEquals(AdminPaths.route("/admin-index.do"), "/author/private-panel");
            Assert.assertEquals(AdminPaths.route("/admin-preference.do"), "/author/private-panel/admin-preference.do");
            Assert.assertEquals(AdminPaths.route("/console/preference/"), "/console/preference/");
            Assert.assertTrue(AdminPaths.reserved("/author/private-panel"));
            Assert.assertTrue(AdminPaths.reserved("/author/private-panel/article.html"));
            Assert.assertFalse(AdminPaths.reserved("/author/private-panel-other"));
            Assert.assertTrue(AdminPaths.legacyEntry("/start"));
            Assert.assertTrue(AdminPaths.legacyEntry("/admin-main.do"));
            Assert.assertFalse(AdminPaths.legacyEntry("/author/private-panel/login"));
            Latkes.setContextPath("/blog");
            final HashMap<String, Object> model = new HashMap<>();
            AdminPaths.fill(model);
            Assert.assertEquals(model.get("adminLoginPath"), "/blog/author/private-panel/login");
            Assert.assertEquals(model.get("adminPageBase"), "/blog/author/private-panel/");
        } finally { LatkeTestSupport.configure(); }
    }

    @Test public void unsafeOrConflictingPrefixesAreRejected() throws Exception {
        try {
            for (final String invalid : new String[]{"/", "//example.org", "https://example.org", "/../admin", "/ops/",
                    "/ops?query", "/ops#hash", "/js/manage", "/CONSOLE", "/images", "/start", "/oauth/private", "/a%2fb"}) {
                configure(invalid);
                Assert.expectThrows(IllegalArgumentException.class, AdminPaths::prefix);
            }
        } finally { LatkeTestSupport.configure(); }
    }
}
