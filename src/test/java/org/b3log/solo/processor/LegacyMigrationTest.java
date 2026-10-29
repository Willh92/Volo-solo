/* Volo, derived from Bolo. Copyright (c) 2020-present, bolo-blog. AGPL-3.0-or-later. */
package org.b3log.solo.processor;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.testng.Assert;
import org.testng.annotations.Test;

public class LegacyMigrationTest {
    @Test
    public void administratorInputsAreStoredAsDataAndOtherUsersSurvive() throws Exception {
        try (Connection connection = database("literal")) {
            final String name = "owner', 'injected', 'adminRole' --";
            final String site = "https://example.test/it's-a-blog";
            final String password = "old'password";
            OAuthProcessor.migrateAdministrator(connection, "migration_", name, password, site);
            connection.commit();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT * FROM migration_user WHERE userRole = 'adminRole'")) {
                Assert.assertTrue(rows.next());
                Assert.assertEquals(rows.getString("userName"), name);
                Assert.assertEquals(rows.getString("userURL"), site);
                Assert.assertEquals(rows.getString("userB3Key"), password);
                Assert.assertEquals(rows.getString("userAvatar"), site + "/images/volo-mark.svg");
                Assert.assertFalse(rows.next());
            }
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM migration_user WHERE userName = 'writer'")) {
                rows.next();
                Assert.assertEquals(rows.getInt(1), 1);
            }
        }
    }

    @Test
    public void aFailedReplacementCanRollBackWithoutLosingTheOwner() throws Exception {
        try (Connection connection = database("rollback")) {
            Assert.expectThrows(SQLException.class, () -> OAuthProcessor.migrateAdministrator(
                    connection, "migration_", "blocked", "password", "https://example.test"));
            connection.rollback();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT userName FROM migration_user WHERE userRole = 'adminRole'")) {
                Assert.assertTrue(rows.next());
                Assert.assertEquals(rows.getString(1), "owner");
                Assert.assertFalse(rows.next());
            }
        }
    }

    private static Connection database(final String name) throws SQLException {
        final Connection connection = DriverManager.getConnection("jdbc:h2:mem:migration_" + name + ";MODE=MySQL", "sa", "");
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE migration_user (oId VARCHAR(40) PRIMARY KEY, userName VARCHAR(256) CHECK (userName <> 'blocked'),"
                    + " userURL VARCHAR(256), userRole VARCHAR(40), userAvatar VARCHAR(512), userB3Key VARCHAR(256), userGitHubId VARCHAR(40))");
            statement.execute("INSERT INTO migration_user (oId, userName, userRole) VALUES ('old-owner', 'owner', 'adminRole'), ('writer', 'writer', 'defaultRole')");
        }
        connection.setAutoCommit(false);
        return connection;
    }
}
