/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.security;

import java.lang.reflect.Field;
import java.sql.DriverManager;
import java.util.List;
import org.b3log.latke.Keys;
import org.b3log.solo.model.Comment;
import org.b3log.solo.model.Option;
import org.b3log.solo.service.IpBanService;
import org.b3log.solo.service.OptionMgmtService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.upgrade.V434_20261101;
import org.b3log.solo.util.CommentThreads;
import org.b3log.solo.util.CommentOwnership;
import org.b3log.solo.util.IpAddresses;
import org.b3log.solo.util.IpGeo;
import org.b3log.solo.util.IpRange;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.Test;

public class CommentIdentityTest {
    @Test public void migratedFieldsUseTheSameUnquotedNamesAsLatkeQueries() throws Exception {
        for (final String settings : new String[]{"", ";MODE=MySQL", ";MODE=MariaDB", ";DATABASE_TO_LOWER=TRUE"}) {
            try (var db = DriverManager.getConnection("jdbc:h2:mem:field_case" + settings); var sql = db.createStatement()) {
                sql.executeUpdate("CREATE TABLE legacy_comment (oId VARCHAR(19) PRIMARY KEY, commentContent VARCHAR(100), \"commentClientIP\" VARCHAR(45))");
                sql.executeUpdate("INSERT INTO legacy_comment (oId,commentContent,\"commentClientIP\") VALUES ('legacy','Preserved','192.0.2.1')");
                V434_20261101.ensureColumns(db, "legacy_comment");
                V434_20261101.ensureColumns(db, "legacy_comment");
                try (var row = sql.executeQuery("SELECT commentClientIP,commentNotifyEmail,commentGuestKeyHash,commentDeleted FROM legacy_comment WHERE oId='legacy'")) {
                    Assert.assertTrue(row.next()); Assert.assertEquals(row.getString(1), "192.0.2.1");
                    Assert.assertEquals(row.getString(2), ""); Assert.assertEquals(row.getString(3), ""); Assert.assertFalse(row.getBoolean(4));
                }
            }
        }
    }
    @Test public void freshCommentTableUsesTheActualLatkeH2TypeMappings() throws Exception {
        final var source = new JSONObject(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/repository.json")));
        final var repositories = source.getJSONArray("repositories");
        JSONObject comment = null;
        for (int i = 0; i < repositories.length(); i++) if (repositories.getJSONObject(i).getString("name").equals("comment")) comment = repositories.getJSONObject(i);
        Assert.assertNotNull(comment);
        final var definition = new org.b3log.latke.repository.jdbc.util.RepositoryDefinition();
        definition.setName("fresh_comment");
        final var fields = new java.util.ArrayList<org.b3log.latke.repository.jdbc.util.FieldDefinition>();
        for (int i = 0; i < comment.getJSONArray("keys").length(); i++) {
            final var property = comment.getJSONArray("keys").getJSONObject(i);
            final var field = new org.b3log.latke.repository.jdbc.util.FieldDefinition();
            field.setName(property.getString("name")); field.setType(property.getString("type"));
            field.setLength(property.optInt("length", 255)); field.setIsKey(property.getString("name").equals("oId"));
            field.setNullable(property.optBoolean("nullable", false)); fields.add(field);
        }
        definition.setKeys(fields);
        final var solution = new org.b3log.latke.repository.h2.H2JdbcDatabaseSolution();
        final StringBuilder sql = new StringBuilder();
        for (final String method : new String[]{"createTableHead", "createTableBody", "createTableEnd"}) {
            final var stage = solution.getClass().getDeclaredMethod(method, StringBuilder.class, definition.getClass());
            stage.setAccessible(true); stage.invoke(solution, sql, definition);
        }
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:fresh_comment;MODE=MySQL"); var statement = connection.createStatement()) {
            statement.executeUpdate(sql.toString());
            V434_20261101.ensureColumns(connection, "fresh_comment");
        }
    }

    @Test public void migrationPreservesExistingRowsAndCanResumePartialDdl() throws Exception {
        for (final String mode : new String[]{"", ";MODE=MySQL", ";MODE=MariaDB"}) {
            try (var connection = DriverManager.getConnection("jdbc:h2:mem:migration" + mode, "sa", "")) {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("CREATE TABLE legacy_comment (oId VARCHAR(19), commentContent VARCHAR(100))");
                    statement.executeUpdate("INSERT INTO legacy_comment VALUES ('original', 'old comment')");
                    statement.executeUpdate("ALTER TABLE legacy_comment ADD COLUMN \"commentClientIP\" VARCHAR(45)");
                }
                V434_20261101.ensureColumns(connection, "legacy_comment");
                V434_20261101.ensureColumns(connection, "legacy_comment");
                try (var statement = connection.createStatement(); var row = statement.executeQuery("SELECT * FROM legacy_comment")) {
                    Assert.assertTrue(row.next());
                    Assert.assertEquals(row.getString("commentContent"), "old comment");
                    Assert.assertEquals(row.getString("commentNotifyEmail"), "");
                    Assert.assertFalse(row.next());
                }
            }
        }
    }

    @Test public void missingTableCannotBeMarkedAsMigrated() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:no_comment_table")) {
            Assert.expectThrows(IllegalStateException.class, () -> V434_20261101.ensureColumns(connection, "missing_comment"));
        }
    }

    @Test public void publicCommentsNeverContainNotificationEmailOrFullIp() {
        final JSONObject guest = new JSONObject().put(IpAddresses.COMMENT_IP, "203.0.113.44")
                .put("commentNotifyEmail", "private@example.test").put("commentEmail", "legacy@example.test");
        IpAddresses.hidePrivateAddress(guest);
        Assert.assertEquals(guest.getString(IpAddresses.PUBLIC_IP), "203.0.*.*");
        Assert.assertFalse(guest.toString().contains("203.0.113.44"));
        Assert.assertFalse(guest.toString().contains("@example.test"));
        final JSONObject administrator = new JSONObject().put(IpAddresses.COMMENT_IP, "");
        IpAddresses.hidePrivateAddress(administrator);
        Assert.assertEquals(administrator.getString(IpAddresses.PUBLIC_IP), "");
        Assert.assertEquals(administrator.getString("commentRegion"), "");
    }

    @Test public void cidrWorksForBothFamiliesAndCannotResolveHostnames() {
        Assert.assertTrue(IpRange.parse("192.0.2.129/24").contains("192.0.2.1"));
        Assert.assertFalse(IpRange.parse("192.0.2.0/24").contains("192.0.3.1"));
        Assert.assertTrue(IpRange.parse("2001:db8:abcd::/48").contains("2001:db8:abcd:ffff::2"));
        Assert.assertFalse(IpRange.parse("2001:db8:abcd::/48").contains("2001:db8:abce::2"));
        Assert.assertTrue(IpRange.parse("::/0").contains("::1"));
        Assert.assertFalse(IpRange.parse("::/0").contains("127.0.0.1"));
        Assert.assertEquals(IpAddresses.canonical("::ffff:192.0.2.1"), "192.0.2.1");
        for (final String invalid : new String[]{"example.com", "127.1", "192.0.2.1/33", "::1/129", "::1/", "1.2.3.4/-1", "1.2.3.4/8/9"}) {
            Assert.expectThrows(IllegalArgumentException.class, () -> IpRange.parse(invalid));
        }
    }

    @Test public void largeBanListsArePagedFilteredAndClampedAfterRemoval() throws Exception {
        final org.json.JSONArray data = new org.json.JSONArray();
        for (int i = 0; i < 250; i++) data.put(new JSONObject().put("range", "198.18.0." + i)
                .put("reason", i % 2 == 0 ? "advertising" : "spam").put("created", i));
        final JSONObject[] stored = {new JSONObject().put(Option.OPTION_VALUE, data.toString())};
        final IpBanService bans = bans(stored);
        final JSONObject first = bans.page(1, 10, "");
        Assert.assertEquals(first.getInt("total"), 250);
        Assert.assertEquals(first.getInt("pageCount"), 25);
        Assert.assertEquals(first.getJSONArray("rules").length(), 10);
        Assert.assertEquals(first.getJSONArray("rules").getJSONObject(0).getString("range"), "198.18.0.249");
        Assert.assertEquals(bans.page(2, 10, "").getJSONArray("rules").getJSONObject(0).getString("range"), "198.18.0.239");
        Assert.assertEquals(bans.page(1, 10, "ADVERTISING").getInt("total"), 125);
        Assert.assertEquals(bans.page(1, 10, "198.18.0.249").getInt("total"), 1);
        Assert.assertEquals(bans.page(Integer.MAX_VALUE, 10, "").getInt("page"), 25);
        Assert.assertEquals(bans.page(1, 10, "missing").getJSONArray("rules").length(), 0);
        Assert.expectThrows(IllegalArgumentException.class, () -> bans.page(1, 51, ""));
        Assert.expectThrows(IllegalArgumentException.class, () -> bans.page(0, 10, ""));
    }

    @Test public void bansPersistReloadUnbanAndRejectSelfLockout() throws Exception {
        final JSONObject[] stored = {null};
        final IpBanService bans = bans(stored);
        Assert.assertFalse(bans.blocked("192.0.2.11"));
        bans.add("192.0.2.129/24", "comment abuse", "198.51.100.2");
        bans.add("2001:db8::/32", "IPv6 abuse", "198.51.100.2");
        Assert.assertTrue(bans.blocked("192.0.2.11"));
        Assert.assertTrue(bans.blocked("2001:db8::cafe"));
        Assert.assertTrue(bans(stored).blocked("192.0.2.55"), "Rules must survive a service restart");
        Assert.expectThrows(IllegalArgumentException.class, () -> bans.add("198.51.100.0/24", "", "198.51.100.2"));
        Assert.assertFalse(bans.blocked("198.51.100.2"));
        bans.remove("192.0.2.9/24");
        Assert.assertFalse(bans.blocked("192.0.2.11"));
        Assert.assertTrue(bans.blocked("2001:db8::2"));
    }

    @Test public void descendantDeletionHasArticleBoundaryAndRemovesOnlyMatchingMailBindings() {
        final List<JSONObject> comments = List.of(comment("root", "a", ""), comment("child", "a", "root"),
                comment("grandchild", "a", "child"), comment("independent", "a", ""), comment("other", "b", "root"));
        final var removed = CommentThreads.descendants(comments, "a", "root");
        Assert.assertEquals(removed, java.util.Set.of("root", "child", "grandchild"));
        Assert.assertEquals(CommentThreads.removeMailBindings("root:a:a@x.test;independent:b:b@x.test;child:c:c@x.test", removed), "independent:b:b@x.test");
    }

    @Test public void locationDoesNotPublishProviderOrDuplicateProvince() {
        Assert.assertEquals(IpGeo.location("中国|广东省|深圳市|电信|CN"), "中国 · 广东省 · 深圳市");
        Assert.assertEquals(IpGeo.location("中国|上海市|上海市|联通|CN"), "中国 · 上海市");
        Assert.assertEquals(IpGeo.location("0|0|0|0|0"), "");
    }

    @Test public void editingRequiresBothOriginalIpAndUnlostBrowserIdentity() {
        final String key = "a".repeat(64);
        final JSONObject comment = new JSONObject().put(IpAddresses.COMMENT_IP, "2001:db8::1")
                .put(CommentOwnership.KEY_HASH, CommentOwnership.hash(key));
        Assert.assertTrue(CommentOwnership.owns(comment, "2001:db8:0:0:0:0:0:1", key));
        Assert.assertFalse(CommentOwnership.owns(comment, "2001:db8::2", key));
        Assert.assertFalse(CommentOwnership.owns(comment, "2001:db8::1", "b".repeat(64)));
        Assert.assertFalse(CommentOwnership.owns(comment, "2001:db8::1", ""));
        Assert.assertFalse(CommentOwnership.owns(new JSONObject().put(IpAddresses.COMMENT_IP, "2001:db8::1"), "2001:db8::1", key), "Legacy guests cannot be reclaimed by nickname or IP alone");
        comment.put("commentDeleted", true);
        Assert.assertFalse(CommentOwnership.owns(comment, "2001:db8::1", key));
        final JSONObject serialized = new JSONObject(comment.toString());
        IpAddresses.hidePrivateAddress(serialized);
        Assert.assertFalse(serialized.has(CommentOwnership.KEY_HASH));
        Assert.assertEquals(serialized.getString(IpAddresses.PUBLIC_IP), "");
    }

    @Test public void deepReplyChainsDisplayAsTwoLevelsWithoutLosingTheActualTarget() {
        final List<JSONObject> comments = List.of(comment("grandchild", "a", "child"), comment("child", "a", "root"),
                comment("root", "a", ""), comment("new-root", "a", ""));
        final var ordered = CommentThreads.twoLevels(comments);
        Assert.assertEquals(ordered.size(), 4);
        for (final JSONObject item : ordered) {
            if (item.getString(Keys.OBJECT_ID).equals("child") || item.getString(Keys.OBJECT_ID).equals("grandchild")) {
                Assert.assertEquals(item.getInt("commentDepth"), 1);
                Assert.assertEquals(item.getString("commentThreadId"), "root");
            } else Assert.assertEquals(item.getInt("commentDepth"), 0);
        }
        Assert.assertEquals(comments.get(0).getString(Comment.COMMENT_ORIGINAL_COMMENT_ID), "child");
        final var broken = CommentThreads.twoLevels(List.of(comment("cycle-b", "a", "cycle-a"), comment("cycle-a", "a", "cycle-b"), comment("orphan", "a", "missing")));
        Assert.assertEquals(broken.size(), 3, "Legacy cycles and missing parents must not lose visible comments");
        Assert.assertTrue(broken.stream().allMatch(item -> item.getInt("commentDepth") <= 1));
    }

    private static JSONObject comment(final String id, final String article, final String parent) {
        return new JSONObject().put(Keys.OBJECT_ID, id).put(Comment.COMMENT_ON_ID, article).put(Comment.COMMENT_ORIGINAL_COMMENT_ID, parent);
    }
    private static IpBanService bans(final JSONObject[] stored) throws Exception {
        final IpBanService service = new IpBanService();
        inject(service, "optionQueryService", new OptionQueryService() {
            @Override public JSONObject getOptionById(final String id) { return stored[0]; }
        });
        inject(service, "optionMgmtService", new OptionMgmtService() {
            @Override public void storeDocument(String id, String category, String value) { stored[0] = new JSONObject().put("oId", id).put(Option.OPTION_CATEGORY, category).put(Option.OPTION_VALUE, value); }
        });
        return service;
    }
    private static void inject(final Object object, final String name, final Object value) throws Exception {
        final Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
}
