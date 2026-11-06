/*
 * Volo - A lightweight, secure, and modern blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.upgrade;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.b3log.latke.Latkes;
import org.b3log.latke.ioc.BeanManager;
import org.b3log.latke.repository.jdbc.util.Connections;
import org.b3log.solo.SoloApplication;
import org.b3log.solo.bolo.prop.Options;
import org.b3log.solo.model.Option;
import org.b3log.solo.service.OptionMgmtService;
import org.b3log.solo.service.OptionQueryService;
import org.json.JSONObject;

/**
 * Idempotent date-version schema migration to 2026.11.05, compatible with MySQL, MariaDB and H2.
 */
public final class V20261101_20261105 {

    private V20261101_20261105() {
    }

    public static void perform() throws Exception {
        final String prefix = Latkes.getLocalProperty("jdbc.tablePrefix");
        final String p = (prefix == null || prefix.isBlank()) ? "" : prefix.trim() + "_";
        try (Connection connection = Connections.getConnection()) {
            ensureUserColumns(connection, p + "user");
            ensureCommentColumns(connection, p + "comment");
            ensureArticleColumns(connection, p + "article");
            ensureArticleRevisionTable(connection, p + "article_revision");
            DatabaseIndexMigration.ensureCoreIndexes(connection, p);
            if (!connection.getAutoCommit()) {
                connection.commit();
            }
        }
        final BeanManager beans = BeanManager.getInstance();
        final OptionQueryService query = beans.getReference(OptionQueryService.class);
        final OptionMgmtService mgmt = beans.getReference(OptionMgmtService.class);
        ensureDefaultOptions(query, mgmt);
        migrateBooleanSwitchOptions(query, mgmt);
        final JSONObject version = query.getOptionById(Option.ID_C_VERSION);
        if (version != null && !SoloApplication.DATABASE_VERSION.equals(version.optString(Option.OPTION_VALUE))) {
            version.put(Option.OPTION_VALUE, SoloApplication.DATABASE_VERSION);
            mgmt.addOrUpdateOption(version);
        }
    }

    private static void ensureDefaultOptions(final OptionQueryService query, final OptionMgmtService mgmt) throws Exception {
        final Map<String, String> defaults = Map.ofEntries(
                Map.entry(Option.ID_C_ALLOW_REGISTER, "false"),
                Map.entry(Option.ID_C_COMMENT_ACCESS_MODE, "all"),
                Map.entry(Option.ID_C_COMMENT_MODERATION_MODE, "off"),
                Map.entry(Option.ID_C_ARTICLE_OUTDATED_DAYS, "365"),
                Map.entry(Option.ID_C_ENABLE_READING_STATS, "true"),
                Map.entry(Option.ID_C_ENABLE_INSTANT_SEARCH, "true"),
                Map.entry(Option.ID_C_LOCAL_BACKUP_AUTO, "false"),
                Map.entry(Option.ID_C_LOCAL_BACKUP_KEEP_COUNT, "7"),
                Map.entry(Option.ID_C_LOCAL_BACKUP_DIR, ""),
                Map.entry(Option.ID_C_IMAGE_WEBP_ADAPTIVE, "false"),
                Map.entry(Option.ID_C_INDEXNOW_ENABLED, "false"),
                Map.entry(Option.ID_C_INDEXNOW_KEY, "")
        );
        for (final Map.Entry<String, String> entry : defaults.entrySet()) {
            if (query.getOptionById(entry.getKey()) == null) {
                final JSONObject opt = new JSONObject();
                opt.put(org.b3log.latke.Keys.OBJECT_ID, entry.getKey());
                opt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
                opt.put(Option.OPTION_VALUE, entry.getValue());
                mgmt.addOrUpdateOption(opt);
            }
        }
        Options.get(Option.ID_C_ALLOW_REGISTER);
    }

    /**
     * Migrates all 2-state boolean switch options in {@code option} from legacy {@code "on"}/{@code "off"} (or {@code "1"}/{@code "0"})
     * to canonical {@code "true"}/{@code "false"} idempotently on startup.
     */
    public static void migrateBooleanSwitchOptions(final OptionQueryService query, final OptionMgmtService mgmt) throws Exception {
        final Map<String, Boolean> booleanDefaults = new HashMap<>();
        for (final String trueKey : new String[]{
                "smtpAuth",
                Option.ID_C_COMMENT_FILTER_BUILTIN,
                Option.ID_C_INTERACTIVE,
                Option.ID_C_WAF_POWER,
                Option.ID_C_ADMIN_ACTIVE_SENT_TO_MAILBOX,
                "riskControlEnabled",
                "commentCaptchaEnabled",
                Option.ID_C_SYNC_GITHUB,
                Option.ID_C_PULL_GITHUB,
                Option.ID_C_COMMENTABLE,
                Option.ID_C_ENABLE_READING_STATS,
                Option.ID_C_ENABLE_INSTANT_SEARCH
        }) {
            booleanDefaults.put(trueKey, Boolean.TRUE);
        }
        for (final String falseKey : new String[]{
                Option.ID_C_SHOW_LOGIN_ENTRY,
                Option.ID_C_ALLOW_REGISTER,
                Option.ID_C_SHOW_CODE_BLOCK_LN,
                Option.ID_C_ENABLE_ARTICLE_UPDATE_HINT,
                Option.ID_C_ALLOW_VISIT_DRAFT_VIA_PERMALINK,
                Option.ID_C_WELFARE_LUTE_SERVICE,
                Option.ID_C_ENABLE_AUTO_FLUSH_GITHUB,
                Option.ID_C_ENABLE_AUTO_FLUSH_BLOG_TO_GITHUB_PROFILE,
                Option.ID_C_HELP_IMPROVE_PLAN,
                Option.ID_C_LOCAL_BACKUP_AUTO,
                Option.ID_C_IMAGE_WEBP_ADAPTIVE,
                Option.ID_C_INDEXNOW_ENABLED
        }) {
            booleanDefaults.put(falseKey, Boolean.FALSE);
        }
        for (final Map.Entry<String, Boolean> entry : booleanDefaults.entrySet()) {
            final JSONObject existing = query.getOptionById(entry.getKey());
            if (existing == null) {
                continue;
            }
            final String raw = existing.optString(Option.OPTION_VALUE, "");
            final String normalized = normalizeLegacySwitchValue(raw, entry.getValue());
            if (!normalized.equals(raw)) {
                existing.put(Option.OPTION_VALUE, normalized);
                mgmt.addOrUpdateOption(existing);
            }
        }
    }

    static String normalizeLegacySwitchValue(final String raw, final boolean defaultValue) {
        if (raw == null) {
            return Boolean.toString(defaultValue);
        }
        final String trimmed = raw.strip().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return Boolean.toString(defaultValue);
        }
        return switch (trimmed) {
            case "true", "on", "1" -> "true";
            case "false", "off", "0" -> "false";
            default -> Boolean.toString(defaultValue);
        };
    }

    public static void ensureUserColumns(final Connection connection, final String configuredTable) throws Exception {
        final TableMeta meta = inspectTable(connection, configuredTable, true);
        try (Statement st = connection.createStatement()) {
            normalizeCaseIfNeeded(connection, st, meta,
                    "userEmail", "userEmailVerified", "userPasswordHash", "userStatus", "userCreated");
            if (!meta.columns.contains("useremail")) {
                st.executeUpdate("ALTER TABLE " + meta.quotedTable + " ADD COLUMN userEmail VARCHAR(254) DEFAULT ''");
            }
            if (!meta.columns.contains("useremailverified")) {
                st.executeUpdate("ALTER TABLE " + meta.quotedTable + " ADD COLUMN userEmailVerified BOOLEAN DEFAULT FALSE");
                st.executeUpdate("UPDATE " + meta.quotedTable + " SET userEmailVerified = FALSE WHERE userEmailVerified IS NULL");
            }
            if (!meta.columns.contains("userpasswordhash")) {
                st.executeUpdate("ALTER TABLE " + meta.quotedTable + " ADD COLUMN userPasswordHash VARCHAR(255) DEFAULT ''");
            }
            if (!meta.columns.contains("userstatus")) {
                st.executeUpdate("ALTER TABLE " + meta.quotedTable + " ADD COLUMN userStatus INT DEFAULT 0");
                st.executeUpdate("UPDATE " + meta.quotedTable + " SET userStatus = 0 WHERE userStatus IS NULL");
            }
            if (!meta.columns.contains("usercreated")) {
                st.executeUpdate("ALTER TABLE " + meta.quotedTable + " ADD COLUMN userCreated BIGINT DEFAULT 0");
            }
        }
    }

    public static void ensureCommentColumns(final Connection connection, final String configuredTable) throws Exception {
        final TableMeta meta = inspectTable(connection, configuredTable, true);
        try (Statement st = connection.createStatement()) {
            normalizeCaseIfNeeded(connection, st, meta, "commentStatus", "commentUserId");
            if (!meta.columns.contains("commentstatus")) {
                st.executeUpdate("ALTER TABLE " + meta.quotedTable + " ADD COLUMN commentStatus INT DEFAULT 0");
                st.executeUpdate("UPDATE " + meta.quotedTable + " SET commentStatus = 0 WHERE commentStatus IS NULL");
            }
            if (!meta.columns.contains("commentuserid")) {
                st.executeUpdate("ALTER TABLE " + meta.quotedTable + " ADD COLUMN commentUserId VARCHAR(19) DEFAULT ''");
            }
        }
    }

    public static void ensureArticleColumns(final Connection connection, final String configuredTable) throws Exception {
        final TableMeta meta = inspectTable(connection, configuredTable, true);
        try (Statement st = connection.createStatement()) {
            normalizeCaseIfNeeded(connection, st, meta,
                    "articleScheduleTime", "articleAudioURL", "articleAudioDuration", "articleAudioSize");
            if (!meta.columns.contains("articlescheduletime")) {
                st.executeUpdate("ALTER TABLE " + meta.quotedTable + " ADD COLUMN articleScheduleTime BIGINT DEFAULT 0");
                st.executeUpdate("UPDATE " + meta.quotedTable + " SET articleScheduleTime = 0 WHERE articleScheduleTime IS NULL");
            }
            if (!meta.columns.contains("articleaudiourl")) {
                st.executeUpdate("ALTER TABLE " + meta.quotedTable + " ADD COLUMN articleAudioURL VARCHAR(512) DEFAULT ''");
            }
            if (!meta.columns.contains("articleaudioduration")) {
                st.executeUpdate("ALTER TABLE " + meta.quotedTable + " ADD COLUMN articleAudioDuration INT DEFAULT 0");
            }
            if (!meta.columns.contains("articleaudiosize")) {
                st.executeUpdate("ALTER TABLE " + meta.quotedTable + " ADD COLUMN articleAudioSize BIGINT DEFAULT 0");
            }
        }
    }

    public static void ensureArticleRevisionTable(final Connection connection, final String configuredTable) throws Exception {
        final TableMeta existing = inspectTable(connection, configuredTable, false);
        final String tableDdlName = configuredTable.replaceAll("[^A-Za-z0-9_]", "");
        if (tableDdlName.isEmpty()) {
            throw new IllegalStateException("Invalid revision table name: " + configuredTable);
        }
        if (existing.actualTable != null) {
            return;
        }
        final String product = String.valueOf(connection.getMetaData().getDatabaseProductName()).toLowerCase(Locale.ROOT);
        final boolean mysqlFamily = product.contains("mysql") || product.contains("mariadb");
        final String largeTextType = mysqlFamily ? "MEDIUMTEXT" : "CLOB";
        final String abstractType = mysqlFamily ? "TEXT" : "CLOB";
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + tableDdlName + " ("
                    + "oId VARCHAR(19) NOT NULL PRIMARY KEY, "
                    + "articleOId VARCHAR(19) NOT NULL, "
                    + "revisionTitle VARCHAR(255) NOT NULL, "
                    + "revisionAbstract " + abstractType + ", "
                    + "revisionContent " + largeTextType + ", "
                    + "revisionCreated BIGINT NOT NULL, "
                    + "revisionAuthorName VARCHAR(64) DEFAULT '', "
                    + "revisionNote VARCHAR(255) DEFAULT ''"
                    + ")" + (mysqlFamily ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci" : ""));
            try {
                st.executeUpdate("CREATE INDEX idx_" + tableDdlName + "_article ON " + tableDdlName + " (articleOId, revisionCreated)");
            } catch (Exception ignoredIndexAlreadyExists) {
                // Index creation is best-effort and idempotent across restarts.
            }
        }
    }

    private record TableMeta(String actualTable, String quotedTable, String quote, Set<String> columns, Map<String, String> actualColumns) {
    }

    private static TableMeta inspectTable(final Connection connection, final String configuredTable, final boolean required) throws Exception {
        final String schema = connection.getSchema();
        String table = null;
        try (ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), schema, null, new String[]{"TABLE", "BASE TABLE"})) {
            while (tables.next()) {
                if (configuredTable.equalsIgnoreCase(tables.getString("TABLE_NAME"))) {
                    table = tables.getString("TABLE_NAME");
                    break;
                }
            }
        }
        if (table == null) {
            if (required) {
                throw new IllegalStateException("Table not found: " + configuredTable);
            }
            return new TableMeta(null, null, "\"", Set.of(), Map.of());
        }
        final Set<String> columns = new HashSet<>();
        final Map<String, String> actualColumns = new HashMap<>();
        try (ResultSet result = connection.getMetaData().getColumns(connection.getCatalog(), schema, table, null)) {
            while (result.next()) {
                if (table.equalsIgnoreCase(result.getString("TABLE_NAME"))) {
                    final String colName = result.getString("COLUMN_NAME");
                    columns.add(colName.toLowerCase(Locale.ROOT));
                    actualColumns.put(colName.toLowerCase(Locale.ROOT), colName);
                }
            }
        }
        String quote = connection.getMetaData().getIdentifierQuoteString().trim();
        if (quote.isEmpty()) {
            quote = "\"";
        }
        final String quotedTable = quote + table.replace(quote, quote + quote) + quote;
        return new TableMeta(table, quotedTable, quote, columns, actualColumns);
    }

    private static void normalizeCaseIfNeeded(final Connection connection, final Statement st, final TableMeta meta, final String... fields)
            throws Exception {
        for (final String field : fields) {
            final String actual = meta.actualColumns.get(field.toLowerCase(Locale.ROOT));
            final String expected = connection.getMetaData().storesUpperCaseIdentifiers() ? field.toUpperCase(Locale.ROOT)
                    : connection.getMetaData().storesLowerCaseIdentifiers() ? field.toLowerCase(Locale.ROOT) : field;
            if (actual != null && !actual.equals(expected)
                    && (connection.getMetaData().storesUpperCaseIdentifiers() || connection.getMetaData().storesLowerCaseIdentifiers())) {
                st.executeUpdate("ALTER TABLE " + meta.quotedTable + " RENAME COLUMN "
                        + meta.quote + actual.replace(meta.quote, meta.quote + meta.quote) + meta.quote + " TO " + field);
            }
        }
    }
}
