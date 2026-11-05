/*
 * Volo - A lightweight, secure, and modern blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.repository;

import java.util.List;
import org.b3log.latke.Keys;
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
     * Prunes older snapshots beyond {@code maxRevisions}.
     */
    public void pruneExcess(final String articleId, final int maxRevisions) throws RepositoryException {
        final List<JSONObject> all = listByArticleId(articleId, 200);
        if (all.size() <= maxRevisions) {
            return;
        }
        for (int i = maxRevisions; i < all.size(); i++) {
            final String id = all.get(i).optString(Keys.OBJECT_ID);
            if (!id.isEmpty()) {
                remove(id);
            }
        }
    }

    /**
     * Removes all revisions associated with the specified article id.
     */
    public void removeByArticleId(final String articleId) throws RepositoryException {
        final List<JSONObject> revisions = listByArticleId(articleId, Integer.MAX_VALUE);
        for (final JSONObject rev : revisions) {
            final String id = rev.optString(Keys.OBJECT_ID);
            if (!id.isEmpty()) {
                remove(id);
            }
        }
    }
}
