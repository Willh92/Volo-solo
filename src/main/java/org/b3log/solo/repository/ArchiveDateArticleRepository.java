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
package org.b3log.solo.repository;

import org.b3log.latke.Keys;
import org.b3log.latke.ioc.Inject;
import org.apache.logging.log4j.Level;
import org.b3log.solo.logging.Logger;
import org.b3log.latke.repository.*;
import org.b3log.latke.repository.annotation.Repository;
import org.b3log.solo.model.ArchiveDate;
import org.b3log.solo.model.Article;
import org.b3log.solo.util.QueryResults;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Archive date-Article repository.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 0.3.1
 */
@Repository
public class ArchiveDateArticleRepository extends AbstractRepository {

    @Inject
    private ArticleRepository articleRepository;

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(ArchiveDateArticleRepository.class);

    /**
     * Public constructor.
     */
    public ArchiveDateArticleRepository() {
        super((ArchiveDate.ARCHIVE_DATE + "_" + Article.ARTICLE).toLowerCase());
    }

    /**
     * Gets published article count of an archive date specified by the given archive data id.
     *
     * @param archiveDateId the given archive date id
     * @return published article count, returns {@code -1} if occurred an exception
     */
    public int getPublishedArticleCount(final String archiveDateId) {
        return getPublishedArticleCount(Collections.singletonList(archiveDateId));
    }

    /** Batch-counts published articles grouped by archiveDate_oId in a single SQL query. */
    public java.util.Map<String, Integer> getPublishedArticleCountsByArchiveDate() throws RepositoryException {
        final java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        final List<JSONObject> rows = select(
                "SELECT r.archiveDate_oId, COUNT(DISTINCT a.oId) AS C FROM " + getName()
                        + " AS r INNER JOIN " + articleRepository.getName()
                        + " AS a ON a.oId = r.article_oId WHERE a.articleStatus = ? GROUP BY r.archiveDate_oId",
                Article.ARTICLE_STATUS_C_PUBLISHED);
        if (rows != null) {
            final String archiveDateCol = ArchiveDate.ARCHIVE_DATE + "_" + Keys.OBJECT_ID;
            for (final JSONObject row : rows) {
                if (null == row) {
                    continue;
                }
                final String archiveDateId = QueryResults.optStringIgnoreCase(row, archiveDateCol);
                if (!archiveDateId.isEmpty()) {
                    counts.put(archiveDateId, QueryResults.optIntIgnoreCase(row, "C"));
                }
            }
        }
        return counts;
    }

    /** Counts each published article once across duplicate records for the same month. */
    public int getPublishedArticleCount(final List<String> archiveDateIds) {
        if (null == archiveDateIds || archiveDateIds.isEmpty()) return 0;
        try {
            final List<Object> params = new ArrayList<>();
            params.add(Article.ARTICLE_STATUS_C_PUBLISHED);
            params.addAll(archiveDateIds);
            final List<JSONObject> articlesCountResult = select("SELECT count(*) AS C FROM " + articleRepository.getName()
                    + " AS a WHERE a.articleStatus = ? AND EXISTS (SELECT 1 FROM " + getName()
                    + " AS r WHERE r.article_oId = a.oId AND r.archiveDate_oId IN ("
                    + String.join(",", Collections.nCopies(archiveDateIds.size(), "?")) + "))", params.toArray());
            if (null == articlesCountResult || articlesCountResult.isEmpty()) {
                return 0;
            }
            return QueryResults.optIntIgnoreCase(articlesCountResult.get(0), "C");
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Gets archivedates' published article count failed", e);

            return -1;
        }
    }

    /**
     * Gets an archive date-article relations by the specified article id.
     *
     * @param articleId the specified article id
     * @return for example
     * <pre>
     * {
     *     "archiveDate_oId": "",
     *     "article_oId": articleId
     * }, returns {@code null} if not found
     * </pre>
     * @throws RepositoryException repository exception
     */
    public JSONObject getByArticleId(final String articleId) throws RepositoryException {
        if (null == articleId || articleId.isBlank()) {
            return null;
        }
        final String articleCol = Article.ARTICLE + "_" + Keys.OBJECT_ID;
        final String archiveDateCol = ArchiveDate.ARCHIVE_DATE + "_" + Keys.OBJECT_ID;
        final Query query = new Query()
                .setFilter(new PropertyFilter(articleCol, FilterOperator.EQUAL, articleId))
                .setPage(1, 1)
                .setPageCount(1);
        final JSONObject result = get(query);
        final JSONArray array = QueryResults.array(result);
        if (0 == array.length()) {
            return null;
        }

        final JSONObject row = array.optJSONObject(0);
        if (null == row) {
            return null;
        }
        final String id = QueryResults.optStringIgnoreCase(row, Keys.OBJECT_ID);
        final String archiveDateId = QueryResults.optStringIgnoreCase(row, archiveDateCol);
        final String resolvedArticleId = QueryResults.optStringIgnoreCase(row, articleCol, articleId);
        QueryResults.removeIgnoreCase(row, Keys.OBJECT_ID);
        QueryResults.removeIgnoreCase(row, archiveDateCol);
        QueryResults.removeIgnoreCase(row, articleCol);
        if (!id.isEmpty()) {
            row.put(Keys.OBJECT_ID, id);
        }
        row.put(archiveDateCol, archiveDateId);
        row.put(articleCol, resolvedArticleId);
        return row;
    }
}
