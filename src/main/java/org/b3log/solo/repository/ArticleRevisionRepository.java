/*
 * Volo - A lightweight, secure, and modern blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.repository;

import java.util.ArrayList;
import java.util.List;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.repository.AbstractRepository;
import org.b3log.latke.repository.FilterOperator;
import org.b3log.latke.repository.PropertyFilter;
import org.b3log.latke.repository.Query;
import org.b3log.latke.repository.RepositoryException;
import org.b3log.latke.repository.SortDirection;
import org.b3log.latke.repository.annotation.Repository;
import org.b3log.solo.model.ArticleRevision;
import org.json.JSONObject;

/**
 * Repository for lightweight article revision history snapshots.
 */
@Repository
public class ArticleRevisionRepository extends AbstractRepository {

    public ArticleRevisionRepository() {
        super(ArticleRevision.ARTICLE_REVISION);
    }

    private boolean isSubclassWithoutDatabase() {
        return getClass() != ArticleRevisionRepository.class
                && "NONE".equalsIgnoreCase(Latkes.getLocalProperty("runtimeDatabase"));
    }

    /**
     * Returns revisions of an article ordered from newest to oldest.
     */
    public List<JSONObject> listByArticleId(final String articleId, final int limit) throws RepositoryException {
        final Query query = new Query()
                .setFilter(new PropertyFilter(ArticleRevision.ARTICLE_OID, FilterOperator.EQUAL, articleId))
                .addSort(ArticleRevision.REVISION_CREATED, SortDirection.DESCENDING)
                .setPage(1, Math.max(1, limit))
                .setPageCount(1);
        return getList(query);
    }

    /**
     * Returns lightweight revision metadata (without loading full {@code revisionContent} CLOBs) ordered from
     * newest to oldest, computing {@code contentLength} directly in SQL via {@code CHAR_LENGTH(revisionContent)}.
     */
    public List<JSONObject> listMetadataByArticleId(final String articleId, final int limit) throws RepositoryException {
        if (null == articleId || articleId.isBlank() || limit <= 0) {
            return new ArrayList<>();
        }
        if (isSubclassWithoutDatabase()) {
            final List<JSONObject> rows = listByArticleId(articleId, limit);
            final List<JSONObject> summaries = new ArrayList<>(rows.size());
            for (final JSONObject row : rows) {
                final String content = row.optString(ArticleRevision.REVISION_CONTENT, "");
                summaries.add(new JSONObject()
                        .put(Keys.OBJECT_ID, row.optString(Keys.OBJECT_ID))
                        .put(ArticleRevision.ARTICLE_OID, row.optString(ArticleRevision.ARTICLE_OID))
                        .put(ArticleRevision.REVISION_TITLE, row.optString(ArticleRevision.REVISION_TITLE))
                        .put(ArticleRevision.REVISION_AUTHOR_NAME, row.optString(ArticleRevision.REVISION_AUTHOR_NAME))
                        .put(ArticleRevision.REVISION_NOTE, row.optString(ArticleRevision.REVISION_NOTE))
                        .put(ArticleRevision.REVISION_CREATED, row.optLong(ArticleRevision.REVISION_CREATED))
                        .put("contentLength", content.length()));
            }
            return summaries;
        }

        final List<JSONObject> rows = select(
                "SELECT oId, articleOId, revisionTitle, revisionCreated, revisionAuthorName, revisionNote, "
                        + "CHAR_LENGTH(revisionContent) AS contentLength FROM " + getName()
                        + " WHERE articleOId = ? ORDER BY revisionCreated DESC LIMIT ?",
                articleId, Math.max(1, limit));
        if (null == rows || rows.isEmpty()) {
            return new ArrayList<>();
        }
        final List<JSONObject> summaries = new ArrayList<>(rows.size());
        for (final JSONObject row : rows) {
            if (null == row) {
                continue;
            }
            summaries.add(new JSONObject()
                    .put(Keys.OBJECT_ID, optStringIgnoreCase(row, Keys.OBJECT_ID, "OID"))
                    .put(ArticleRevision.ARTICLE_OID, optStringIgnoreCase(row, ArticleRevision.ARTICLE_OID, "ARTICLEOID"))
                    .put(ArticleRevision.REVISION_TITLE, optStringIgnoreCase(row, ArticleRevision.REVISION_TITLE, "REVISIONTITLE"))
                    .put(ArticleRevision.REVISION_AUTHOR_NAME, optStringIgnoreCase(row, ArticleRevision.REVISION_AUTHOR_NAME, "REVISIONAUTHORNAME"))
                    .put(ArticleRevision.REVISION_NOTE, optStringIgnoreCase(row, ArticleRevision.REVISION_NOTE, "REVISIONNOTE"))
                    .put(ArticleRevision.REVISION_CREATED, optLongIgnoreCase(row, ArticleRevision.REVISION_CREATED, "REVISIONCREATED"))
                    .put("contentLength", optIntIgnoreCase(row, "contentLength", "CONTENTLENGTH")));
        }
        return summaries;
    }

    private static String optStringIgnoreCase(final JSONObject row, final String exactKey, final String upperKey) {
        if (row.has(exactKey)) {
            return row.optString(exactKey, "");
        }
        if (row.has(upperKey)) {
            return row.optString(upperKey, "");
        }
        final String lowerKey = exactKey.toLowerCase(java.util.Locale.ROOT);
        return row.optString(lowerKey, "");
    }

    private static long optLongIgnoreCase(final JSONObject row, final String exactKey, final String upperKey) {
        if (row.has(exactKey)) {
            return row.optLong(exactKey, 0L);
        }
        if (row.has(upperKey)) {
            return row.optLong(upperKey, 0L);
        }
        return row.optLong(exactKey.toLowerCase(java.util.Locale.ROOT), 0L);
    }

    private static int optIntIgnoreCase(final JSONObject row, final String exactKey, final String upperKey) {
        if (row.has(exactKey)) {
            return row.optInt(exactKey, 0);
        }
        if (row.has(upperKey)) {
            return row.optInt(upperKey, 0);
        }
        return row.optInt(exactKey.toLowerCase(java.util.Locale.ROOT), 0);
    }

    /**
     * Prunes older snapshots beyond {@code maxRevisions} by selecting only {@code oId} and batch-deleting excess IDs
     * in chunks of {@code 200} until at most {@code maxRevisions} snapshots remain.
     */
    public void pruneExcess(final String articleId, final int maxRevisions) throws RepositoryException {
        if (null == articleId || articleId.isBlank()) {
            return;
        }
        final int keepCount = Math.max(0, maxRevisions);
        final int batchSize = 200;
        final int fetchLimit = keepCount + batchSize;
        String previousFirstExcessId = null;

        while (true) {
            final Query query = new Query()
                    .select(Keys.OBJECT_ID)
                    .setFilter(new PropertyFilter(ArticleRevision.ARTICLE_OID, FilterOperator.EQUAL, articleId))
                    .addSort(ArticleRevision.REVISION_CREATED, SortDirection.DESCENDING)
                    .setPage(1, fetchLimit)
                    .setPageCount(1);
            final List<JSONObject> all = getList(query);
            if (null == all || all.size() <= keepCount) {
                return;
            }
            final List<String> excessIds = new ArrayList<>(all.size() - keepCount);
            for (int i = keepCount; i < all.size(); i++) {
                final String id = all.get(i).optString(Keys.OBJECT_ID);
                if (!id.isEmpty()) {
                    excessIds.add(id);
                }
            }
            if (excessIds.isEmpty() || excessIds.get(0).equals(previousFirstExcessId)) {
                return;
            }
            previousFirstExcessId = excessIds.get(0);
            remove(new Query().setFilter(new PropertyFilter(Keys.OBJECT_ID, FilterOperator.IN, excessIds)));
            if (all.size() < fetchLimit) {
                return;
            }
        }
    }

    /**
     * Removes all revisions associated with the specified article id in a single bulk query.
     */
    public void removeByArticleId(final String articleId) throws RepositoryException {
        if (null == articleId || articleId.isBlank()) {
            return;
        }
        remove(new Query().setFilter(new PropertyFilter(ArticleRevision.ARTICLE_OID, FilterOperator.EQUAL, articleId)));
    }
}
