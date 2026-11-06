/*
 * Volo - A lightweight, secure, and modern blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.upgrade;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.Level;
import org.b3log.latke.Latkes;
import org.b3log.latke.repository.jdbc.util.Connections;
import org.b3log.solo.logging.Logger;

/**
 * Idempotent secondary SQL index migration for MySQL, MariaDB, and H2.
 */
public final class DatabaseIndexMigration {

    private static final Logger LOGGER = Logger.getLogger(DatabaseIndexMigration.class);
    private static final int MAX_INDEX_NAME_LENGTH = 60;

    private record IndexSpec(String table, String suffix, String... columns) {
    }

    private static final List<IndexSpec> CORE_INDEXES = List.of(
            // article
            new IndexSpec("article", "permalink", "articlePermalink"),
            new IndexSpec("article", "st_top_upd", "articleStatus", "articlePutTop", "articleUpdated"),
            new IndexSpec("article", "st_top_crt", "articleStatus", "articlePutTop", "articleCreated"),
            new IndexSpec("article", "st_crt", "articleStatus", "articleCreated"),
            new IndexSpec("article", "st_upd", "articleStatus", "articleUpdated"),
            new IndexSpec("article", "st_view_upd", "articleStatus", "articleViewCount", "articleUpdated"),
            new IndexSpec("article", "st_cmt_upd", "articleStatus", "articleCommentCount", "articleUpdated"),
            new IndexSpec("article", "st_rand", "articleStatus", "articleRandomDouble"),
            new IndexSpec("article", "auth_st_top_upd", "articleAuthorId", "articleStatus", "articlePutTop", "articleUpdated"),
            new IndexSpec("article", "auth_st_top_crt", "articleAuthorId", "articleStatus", "articlePutTop", "articleCreated"),
            new IndexSpec("article", "st_sched", "articleStatus", "articleScheduleTime"),
            // comment
            new IndexSpec("comment", "on_st_id", "commentOnId", "commentStatus", "oId"),
            new IndexSpec("comment", "st_crt", "commentStatus", "commentCreated"),
            new IndexSpec("comment", "usr_st", "commentUserId", "commentStatus"),
            // option
            new IndexSpec("option", "cat", "optionCategory"),
            // tag & tag_article
            new IndexSpec("tag", "title", "tagTitle"),
            new IndexSpec("tag_article", "tag_art", "tag_oId", "article_oId"),
            new IndexSpec("tag_article", "art_tag", "article_oId", "tag_oId"),
            // archivedate & archivedate_article
            new IndexSpec("archivedate", "time", "archiveTime"),
            new IndexSpec("archivedate_article", "arch_art", "archiveDate_oId", "article_oId"),
            new IndexSpec("archivedate_article", "art_arch", "article_oId", "archiveDate_oId"),
            // category & category_tag
            new IndexSpec("category", "uri", "categoryURI"),
            new IndexSpec("category", "ord", "categoryOrder"),
            new IndexSpec("category_tag", "tag_cat", "tag_oId", "category_oId"),
            new IndexSpec("category_tag", "cat_tag", "category_oId", "tag_oId"),
            // page
            new IndexSpec("page", "permalink", "pagePermalink"),
            new IndexSpec("page", "ord", "pageOrder"),
            // user
            new IndexSpec("user", "name", "userName"),
            new IndexSpec("user", "email", "userEmail"),
            new IndexSpec("user", "role", "userRole"),
            // article_revision
            new IndexSpec("article_revision", "article", "articleOId", "revisionCreated")
    );

    private DatabaseIndexMigration() {
    }

    /**
     * Acquires a JDBC connection and ensures all core secondary indexes exist idempotently.
     *
     * @throws Exception if obtaining or committing the connection fails
     */
    public static void ensureCoreIndexes() throws Exception {
        final String prefix = Latkes.getLocalProperty("jdbc.tablePrefix");
        final String p = (prefix == null || prefix.isBlank()) ? "" : prefix.trim() + "_";
        try (Connection connection = Connections.getConnection()) {
            ensureCoreIndexes(connection, p);
            if (!connection.getAutoCommit()) {
                connection.commit();
            }
        }
    }

    /**
     * Ensures all core secondary indexes exist on present tables for the given connection.
     * Tables or columns that are absent in partial schemas are silently skipped.
     *
     * @param connection                active JDBC connection
     * @param tablePrefixWithUnderscore table prefix including trailing underscore (or empty)
     * @throws Exception if metadata inspection fails
     */
    public static void ensureCoreIndexes(final Connection connection, final String tablePrefixWithUnderscore) throws Exception {
        if (connection == null) {
            return;
        }
        final String prefix = tablePrefixWithUnderscore == null ? "" : tablePrefixWithUnderscore;
        final DatabaseMetaData metaData = connection.getMetaData();
        final String catalog = connection.getCatalog();
        final String schema = connection.getSchema();
        String quote = metaData.getIdentifierQuoteString();
        quote = quote == null ? "\"" : quote.trim();
        if (quote.isEmpty()) {
            quote = "\"";
        }

        final Map<String, String> existingTables = new HashMap<>();
        try (ResultSet tables = metaData.getTables(catalog, schema, null, new String[]{"TABLE", "BASE TABLE"})) {
            while (tables.next()) {
                final String tableName = tables.getString("TABLE_NAME");
                if (tableName != null && !tableName.isBlank()) {
                    existingTables.put(tableName.toLowerCase(Locale.ROOT), tableName);
                }
            }
        }
        if (existingTables.isEmpty()) {
            return;
        }

        final Map<String, List<IndexSpec>> specsByTable = new HashMap<>();
        for (final IndexSpec spec : CORE_INDEXES) {
            specsByTable.computeIfAbsent(spec.table(), k -> new ArrayList<>()).add(spec);
        }

        try (Statement statement = connection.createStatement()) {
            for (final Map.Entry<String, List<IndexSpec>> entry : specsByTable.entrySet()) {
                final String configuredTable = prefix + entry.getKey();
                final String actualTable = existingTables.get(configuredTable.toLowerCase(Locale.ROOT));
                if (actualTable == null) {
                    continue;
                }

                final Map<String, String> actualColumns = inspectColumns(metaData, catalog, schema, actualTable);
                if (actualColumns.isEmpty()) {
                    continue;
                }
                final Set<String> existingIndexes = inspectIndexes(metaData, catalog, schema, actualTable);
                final String quotedTable = quoteIdentifier(actualTable, quote);

                for (final IndexSpec spec : entry.getValue()) {
                    final String indexName = buildIndexName(configuredTable, spec.suffix());
                    if (indexName.isEmpty() || existingIndexes.contains(indexName.toLowerCase(Locale.ROOT))) {
                        continue;
                    }

                    final List<String> quotedCols = new ArrayList<>(spec.columns().length);
                    boolean allColumnsPresent = true;
                    for (final String col : spec.columns()) {
                        final String actualCol = actualColumns.get(col.toLowerCase(Locale.ROOT));
                        if (actualCol == null) {
                            allColumnsPresent = false;
                            break;
                        }
                        quotedCols.add(quoteIdentifier(actualCol, quote));
                    }
                    if (!allColumnsPresent) {
                        continue;
                    }

                    if (executeCreateIndexWithFallback(statement, indexName, quotedTable, quotedCols)) {
                        existingIndexes.add(indexName.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
    }

    /**
     * Executes {@code CREATE INDEX} idempotently, ignoring duplicate-index errors, retrying single-column
     * {@code VARCHAR} indexes with a {@code (191)} prefix on legacy MySQL/MariaDB 767-byte key-length limits
     * (error 1071), and logging unexpected DDL failures at {@code WARN}.
     */
    public static boolean executeCreateIndexWithFallback(final Statement statement,
                                                         final String indexName,
                                                         final String quotedTable,
                                                         final List<String> quotedCols) {
        final String ddl = "CREATE INDEX " + indexName + " ON " + quotedTable
                + " (" + String.join(", ", quotedCols) + ")";
        try {
            statement.executeUpdate(ddl);
            return true;
        } catch (final SQLException e) {
            if (isDuplicateIndexError(e)) {
                return true;
            }
            if (isKeyTooLongError(e) && quotedCols.size() == 1) {
                final String fallbackDdl = "CREATE INDEX " + indexName + " ON " + quotedTable
                        + " (" + quotedCols.get(0) + "(191))";
                try {
                    statement.executeUpdate(fallbackDdl);
                    return true;
                } catch (final SQLException retryEx) {
                    if (isDuplicateIndexError(retryEx)) {
                        return true;
                    }
                    LOGGER.log(Level.WARN, "Creates prefix-fallback index [" + indexName + "] failed: " + retryEx.getMessage());
                    return false;
                }
            }
            LOGGER.log(Level.WARN, "Creates index [" + indexName + "] failed: " + e.getMessage());
            return false;
        }
    }

    public static boolean isDuplicateIndexError(final SQLException e) {
        if (e == null) {
            return false;
        }
        if (e.getErrorCode() == 1061 || e.getErrorCode() == 42111) {
            return true;
        }
        final String sqlState = e.getSQLState();
        if ("42S11".equalsIgnoreCase(sqlState) || "42111".equalsIgnoreCase(sqlState)) {
            return true;
        }
        final String msg = e.getMessage();
        if (msg == null) {
            return false;
        }
        final String lower = msg.toLowerCase(Locale.ROOT);
        return lower.contains("duplicate key name") || lower.contains("already exists");
    }

    public static boolean isKeyTooLongError(final SQLException e) {
        if (e == null) {
            return false;
        }
        if (e.getErrorCode() == 1071) {
            return true;
        }
        final String msg = e.getMessage();
        if (msg == null) {
            return false;
        }
        final String lower = msg.toLowerCase(Locale.ROOT);
        return lower.contains("specified key was too long") || lower.contains("max key length");
    }

    private static Map<String, String> inspectColumns(final DatabaseMetaData metaData,
                                                      final String catalog,
                                                      final String schema,
                                                      final String actualTable) throws SQLException {
        final Map<String, String> actualColumns = new HashMap<>();
        try (ResultSet rs = metaData.getColumns(catalog, schema, actualTable, null)) {
            while (rs.next()) {
                if (actualTable.equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                    final String colName = rs.getString("COLUMN_NAME");
                    if (colName != null && !colName.isBlank()) {
                        actualColumns.put(colName.toLowerCase(Locale.ROOT), colName);
                    }
                }
            }
        }
        return actualColumns;
    }

    private static Set<String> inspectIndexes(final DatabaseMetaData metaData,
                                              final String catalog,
                                              final String schema,
                                              final String actualTable) {
        final Set<String> indexes = new HashSet<>();
        try (ResultSet rs = metaData.getIndexInfo(catalog, schema, actualTable, false, true)) {
            while (rs.next()) {
                final String idxName = rs.getString("INDEX_NAME");
                if (idxName != null && !idxName.isBlank()) {
                    indexes.add(idxName.toLowerCase(Locale.ROOT));
                }
            }
        } catch (final SQLException ignored) {
            // Fall back to CREATE INDEX best-effort if driver getIndexInfo is unsupported on a table.
        }
        return indexes;
    }

    static String buildIndexName(final String configuredTable, final String suffix) {
        final String cleanSuffix = suffix == null ? "" : suffix.replaceAll("[^A-Za-z0-9_]", "").toLowerCase(Locale.ROOT);
        String cleanTable = configuredTable == null ? "" : configuredTable.replaceAll("[^A-Za-z0-9_]", "").toLowerCase(Locale.ROOT);
        if (cleanTable.isEmpty() || cleanSuffix.isEmpty()) {
            return "";
        }
        final int maxTableLen = MAX_INDEX_NAME_LENGTH - 5 - cleanSuffix.length(); // "idx_" + cleanTable + "_" + cleanSuffix
        if (cleanTable.length() > maxTableLen) {
            final String hash = String.format(Locale.ROOT, "%04x", cleanTable.hashCode() & 0xffff);
            final int keep = Math.max(1, maxTableLen - 5);
            cleanTable = cleanTable.substring(0, keep) + "_" + hash;
        }
        return "idx_" + cleanTable + "_" + cleanSuffix;
    }

    private static String quoteIdentifier(final String identifier, final String quote) {
        return quote + identifier.replace(quote, quote + quote) + quote;
    }
}
