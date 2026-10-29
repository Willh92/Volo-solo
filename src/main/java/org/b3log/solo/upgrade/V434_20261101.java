/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.upgrade;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Set;
import org.b3log.latke.Latkes;
import org.b3log.latke.ioc.BeanManager;
import org.b3log.latke.repository.jdbc.util.Connections;
import org.b3log.solo.SoloApplication;
import org.b3log.solo.model.Option;
import org.b3log.solo.service.OptionMgmtService;
import org.b3log.solo.service.OptionQueryService;
import org.json.JSONObject;

/** The first date-version migration, shared by MySQL, MariaDB and H2. */
public final class V434_20261101 {
    private V434_20261101() { }

    public static void perform() throws Exception {
        // Recheck metadata even after an interrupted DDL or a same-version restart.
        try (Connection connection = Connections.getConnection()) {
            ensureColumns(connection, Latkes.getLocalProperty("jdbc.tablePrefix") + "_comment");
            if (!connection.getAutoCommit()) connection.commit();
        }
        final BeanManager beans = BeanManager.getInstance();
        final OptionQueryService query = beans.getReference(OptionQueryService.class);
        final JSONObject version = query.getOptionById(Option.ID_C_VERSION);
        if (!SoloApplication.DATABASE_VERSION.equals(version.optString(Option.OPTION_VALUE))) {
            version.put(Option.OPTION_VALUE, SoloApplication.DATABASE_VERSION);
            beans.getReference(OptionMgmtService.class).addOrUpdateOption(version);
        }
    }

    public static void ensureColumns(final Connection connection, final String configuredTable) throws Exception {
        final String schema = connection.getSchema();
        String table = null;
        try (ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), schema, null, new String[]{"TABLE", "BASE TABLE"})) {
            while (tables.next()) if (configuredTable.equalsIgnoreCase(tables.getString("TABLE_NAME"))) {
                table = tables.getString("TABLE_NAME");
                break;
            }
        }
        if (table == null) throw new IllegalStateException("Comment table not found: " + configuredTable);
        final Set<String> columns = new HashSet<>();
        final java.util.Map<String, String> actualColumns = new HashMap<>();
        try (ResultSet result = connection.getMetaData().getColumns(connection.getCatalog(), schema, null, null)) {
            while (result.next()) if (table.equals(result.getString("TABLE_NAME"))) {
                columns.add(result.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                actualColumns.put(result.getString("COLUMN_NAME").toLowerCase(Locale.ROOT), result.getString("COLUMN_NAME"));
            }
        }
        String quote = connection.getMetaData().getIdentifierQuoteString().trim();
        if (quote.isEmpty()) quote = "\"";
        final String quotedTable = quote + table.replace(quote, quote + quote) + quote;
        try (Statement statement = connection.createStatement()) {
            // Latke emits unquoted field names. H2 folds those names according to its identifier settings.
            // Also repair a partially completed migration that used quoted mixed-case column names.
            for (final String field : new String[]{"commentClientIP", "commentNotifyEmail", "commentGuestKeyHash", "commentDeleted"}) {
                final String actual = actualColumns.get(field.toLowerCase(Locale.ROOT));
                final String expected = connection.getMetaData().storesUpperCaseIdentifiers() ? field.toUpperCase(Locale.ROOT)
                        : connection.getMetaData().storesLowerCaseIdentifiers() ? field.toLowerCase(Locale.ROOT) : field;
                if (actual != null && !actual.equals(expected) && (connection.getMetaData().storesUpperCaseIdentifiers()
                        || connection.getMetaData().storesLowerCaseIdentifiers())) {
                    statement.executeUpdate("ALTER TABLE " + quotedTable + " RENAME COLUMN " + quote + actual.replace(quote, quote + quote) + quote + " TO " + field);
                }
            }
            if (!columns.contains("commentclientip")) statement.executeUpdate("ALTER TABLE " + quotedTable
                    + " ADD COLUMN commentClientIP VARCHAR(45) DEFAULT ''");
            if (!columns.contains("commentnotifyemail")) statement.executeUpdate("ALTER TABLE " + quotedTable
                    + " ADD COLUMN commentNotifyEmail VARCHAR(254) DEFAULT ''");
            if (!columns.contains("commentguestkeyhash")) statement.executeUpdate("ALTER TABLE " + quotedTable
                    + " ADD COLUMN commentGuestKeyHash VARCHAR(64) DEFAULT ''");
            if (!columns.contains("commentdeleted")) statement.executeUpdate("ALTER TABLE " + quotedTable
                    + " ADD COLUMN commentDeleted BOOLEAN DEFAULT FALSE");
            statement.executeUpdate("UPDATE " + quotedTable + " SET commentDeleted = FALSE WHERE commentDeleted IS NULL");
        }
    }
}
