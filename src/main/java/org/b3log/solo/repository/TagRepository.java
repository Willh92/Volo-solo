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
import org.b3log.latke.repository.*;
import org.b3log.latke.repository.annotation.Repository;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Tag;
import org.b3log.solo.util.QueryResults;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Tag repository.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 0.3.1
 */
@Repository
public class TagRepository extends AbstractRepository {

    /**
     * Public constructor.
     */
    public TagRepository() {
        super(Tag.TAG);
    }

    /**
     * Tag-Article relation repository.
     */
    @Inject
    private TagArticleRepository tagArticleRepository;

    @Inject
    private ArticleRepository articleRepository;

    /**
     * Gets tags of an article specified by the article id.
     *
     * @param articleId the specified article id
     * @return a list of tags of the specified article, returns an empty list if not found
     * @throws RepositoryException repository exception
     */
    public List<JSONObject> getByArticleId(final String articleId) throws RepositoryException {
        if (null == articleId || articleId.isBlank() || null == tagArticleRepository) {
            return new ArrayList<>();
        }
        final List<JSONObject> rows = select("SELECT t.oId, t.tagTitle FROM " + getName() + " AS t JOIN "
                + tagArticleRepository.getName() + " AS r ON r.tag_oId = t.oId WHERE r.article_oId = ?", articleId);
        if (null == rows || rows.isEmpty()) {
            return new ArrayList<>();
        }
        final List<JSONObject> normalized = new ArrayList<>(rows.size());
        for (final JSONObject row : rows) {
            if (null == row) {
                continue;
            }
            final String id = QueryResults.optStringIgnoreCase(row, Keys.OBJECT_ID);
            final String title = QueryResults.optStringIgnoreCase(row, Tag.TAG_TITLE);
            QueryResults.removeIgnoreCase(row, Keys.OBJECT_ID);
            QueryResults.removeIgnoreCase(row, Tag.TAG_TITLE);
            row.put(Keys.OBJECT_ID, id);
            row.put(Tag.TAG_TITLE, title);
            normalized.add(row);
        }
        return normalized;
    }

    /**
     * Gets all unused tags (tags with zero rows in {@code tag_article}) in a single {@code NOT EXISTS} SQL query.
     *
     * @return list of unused tags ordered by {@code tagTitle ASC}
     * @throws RepositoryException repository exception
     */
    public List<JSONObject> getUnusedTags() throws RepositoryException {
        if (null == tagArticleRepository) {
            return new ArrayList<>();
        }
        final List<JSONObject> rows = select(
                "SELECT t.oId, t.tagTitle FROM " + getName() + " t "
                        + "WHERE NOT EXISTS (SELECT 1 FROM " + tagArticleRepository.getName()
                        + " r WHERE r.tag_oId = t.oId) ORDER BY t.tagTitle ASC");
        if (null == rows || rows.isEmpty()) {
            return new ArrayList<>();
        }
        final List<JSONObject> normalized = new ArrayList<>(rows.size());
        for (final JSONObject row : rows) {
            if (null == row) {
                continue;
            }
            final String id = QueryResults.optStringIgnoreCase(row, Keys.OBJECT_ID);
            final String title = QueryResults.optStringIgnoreCase(row, Tag.TAG_TITLE);
            if (!id.isEmpty()) {
                normalized.add(new JSONObject()
                        .put(Keys.OBJECT_ID, id)
                        .put(Tag.TAG_TITLE, title));
            }
        }
        return normalized;
    }

    /**
     * Gets a tag by the specified tag title with its published article count in a single query.
     *
     * @param tagTitle the specified tag title
     * @return a tag, {@code null} if not found
     * @throws RepositoryException repository exception
     */
    public JSONObject getByTitle(final String tagTitle) throws RepositoryException {
        if (null == tagTitle || tagTitle.isBlank()) {
            return null;
        }
        if (null != articleRepository && null != tagArticleRepository) {
            final List<JSONObject> rows = select(
                    "SELECT t.oId, t.tagTitle, (SELECT count(*) FROM " + articleRepository.getName()
                            + " AS a WHERE a.articleStatus = ? AND EXISTS (SELECT 1 FROM "
                            + tagArticleRepository.getName()
                            + " AS r WHERE r.article_oId = a.oId AND r.tag_oId = t.oId)) AS C FROM "
                            + getName() + " AS t WHERE t.tagTitle = ? LIMIT 1",
                    Article.ARTICLE_STATUS_C_PUBLISHED, tagTitle);
            if (null == rows || rows.isEmpty() || null == rows.get(0)) {
                return null;
            }
            final JSONObject ret = rows.get(0);
            final String id = QueryResults.optStringIgnoreCase(ret, Keys.OBJECT_ID);
            final String title = QueryResults.optStringIgnoreCase(ret, Tag.TAG_TITLE, tagTitle);
            final int count = QueryResults.optIntIgnoreCase(ret, "C");
            QueryResults.removeIgnoreCase(ret, "C");
            QueryResults.removeIgnoreCase(ret, Keys.OBJECT_ID);
            QueryResults.removeIgnoreCase(ret, Tag.TAG_TITLE);
            ret.put(Keys.OBJECT_ID, id);
            ret.put(Tag.TAG_TITLE, title);
            ret.put(Tag.TAG_T_PUBLISHED_REFERENCE_COUNT, count);
            return ret;
        }

        final Query query = new Query()
                .setFilter(new PropertyFilter(Tag.TAG_TITLE, FilterOperator.EQUAL, tagTitle))
                .setPage(1, 1)
                .setPageCount(1);
        final JSONObject ret = getFirst(query);
        if (null == ret) {
            return null;
        }

        final String tagId = QueryResults.optStringIgnoreCase(ret, Keys.OBJECT_ID);
        final String resolvedTitle = QueryResults.optStringIgnoreCase(ret, Tag.TAG_TITLE, tagTitle);
        QueryResults.removeIgnoreCase(ret, Keys.OBJECT_ID);
        QueryResults.removeIgnoreCase(ret, Tag.TAG_TITLE);
        ret.put(Keys.OBJECT_ID, tagId);
        ret.put(Tag.TAG_TITLE, resolvedTitle);
        final int articleCount = null == tagArticleRepository ? 0 : tagArticleRepository.getPublishedArticleCount(tagId);
        ret.put(Tag.TAG_T_PUBLISHED_REFERENCE_COUNT, articleCount);

        return ret;
    }
}
