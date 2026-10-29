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
import org.b3log.latke.ioc.BeanManager;
import org.b3log.latke.ioc.Inject;
import org.apache.logging.log4j.Level;
import org.b3log.solo.logging.Logger;
import org.b3log.latke.repository.*;
import org.b3log.latke.repository.annotation.Repository;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Tag;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Tag-Article repository.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 0.3.1
 */
@Repository
public class TagArticleRepository extends AbstractRepository {

    @Inject
    private ArticleRepository articleRepository;

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(TagArticleRepository.class);

    /**
     * Public constructor.
     */
    public TagArticleRepository() {
        super(Tag.TAG + "_" + Article.ARTICLE);
    }

    /**
     * Gets most used tags with the specified number.
     *
     * @param num the specified number
     * @return a list of most used tags, returns an empty list if not found
     * @throws RepositoryException repository exception
     */
    public List<JSONObject> getMostUsedTags(final int num) throws RepositoryException {
        if (num <= 0) return new ArrayList<>();
        final TagRepository tags = getTagRepository();
        final List<JSONObject> records = tags.select("SELECT t.oId, t.tagTitle, count(DISTINCT a.oId) AS C FROM "
                + tags.getName() + " AS t JOIN " + getName() + " AS r ON r.tag_oId = t.oId JOIN "
                + articleRepository.getName() + " AS a ON a.oId = r.article_oId WHERE a.articleStatus = ?"
                + " GROUP BY t.oId, t.tagTitle ORDER BY C DESC, t.tagTitle LIMIT ?",
                Article.ARTICLE_STATUS_C_PUBLISHED, num);
        for (final JSONObject record : records) {
            record.put(Tag.TAG_T_PUBLISHED_REFERENCE_COUNT, record.getInt("C"));
            record.remove("C");
        }
        return records;
    }

    protected TagRepository getTagRepository() {
        // Resolve after bean construction: TagRepository already depends on this repository.
        return BeanManager.getInstance().getReference(TagRepository.class);
    }

    /**
     * Gets article count of a tag specified by the given tag id.
     *
     * @param tagId the given tag id
     * @return article count, returns {@code -1} if occurred an exception
     */
    public int getArticleCount(final String tagId) {
        final Query query = new Query().setFilter(new PropertyFilter(Tag.TAG + "_" + Keys.OBJECT_ID, FilterOperator.EQUAL, tagId));
        try {
            return (int) count(query);
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Gets tag [" + tagId + "]'s article count failed", e);

            return -1;
        }
    }

    /**
     * Gets published article count of a tag specified by the given tag id.
     *
     * @param tagId the given tag id
     * @return published article count, returns {@code -1} if occurred an exception
     */
    public int getPublishedArticleCount(final String tagId) {
        try {
            final List<JSONObject> result = select("SELECT count(*) AS C FROM " + articleRepository.getName()
                    + " AS a WHERE a.articleStatus = ? AND EXISTS (SELECT 1 FROM " + getName()
                    + " AS t WHERE t.article_oId = a.oId AND t.tag_oId = ?)",
                    Article.ARTICLE_STATUS_C_PUBLISHED, tagId);
            return result.get(0).optInt("C");
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Gets tag [" + tagId + "]'s published article count failed", e);

            return -1;
        }
    }

    /** Returns published articles after filtering, so drafts and duplicate links cannot consume a page. */
    public List<JSONObject> getPublishedArticles(final String tagId, final int currentPageNum, final int pageSize)
            throws RepositoryException {
        if (currentPageNum < 1 || pageSize < 1) return new ArrayList<>();
        return articleRepository.select("SELECT a.* FROM " + articleRepository.getName()
                + " AS a WHERE a.articleStatus = ? AND EXISTS (SELECT 1 FROM " + getName()
                + " AS t WHERE t.article_oId = a.oId AND t.tag_oId = ?)"
                + " ORDER BY a.oId DESC LIMIT ? OFFSET ?", Article.ARTICLE_STATUS_C_PUBLISHED, tagId,
                pageSize, (long) (currentPageNum - 1) * pageSize);
    }

    /**
     * Gets tag-article relations by the specified article id.
     *
     * @param articleId the specified article id
     * @return for example
     * <pre>
     * [{
     *         "oId": "",
     *         "tag_oId": "",
     *         "article_oId": articleId
     * }, ....], returns an empty list if not found
     * </pre>
     * @throws RepositoryException repository exception
     */
    public List<JSONObject> getByArticleId(final String articleId) throws RepositoryException {
        final Query query = new Query().setFilter(new PropertyFilter(Article.ARTICLE + "_" + Keys.OBJECT_ID, FilterOperator.EQUAL, articleId)).
                setPageCount(1);

        return getList(query);
    }

    /**
     * Gets tag-article relations by the specified tag id.
     *
     * @param tagId          the specified tag id
     * @param currentPageNum the specified current page number, MUST greater
     *                       then {@code 0}
     * @param pageSize       the specified page size(count of a page contains objects),
     *                       MUST greater then {@code 0}
     * @return for example
     * <pre>
     * {
     *     "pagination": {
     *       "paginationPageCount": 88250
     *     },
     *     "rslts": [{
     *         "oId": "",
     *         "tag_oId": tagId,
     *         "article_oId": ""
     *     }, ....]
     * }
     * </pre>
     * @throws RepositoryException repository exception
     */
    public JSONObject getByTagId(final String tagId, final int currentPageNum, final int pageSize) throws RepositoryException {
        final Query query = new Query().setFilter(new PropertyFilter(Tag.TAG + "_" + Keys.OBJECT_ID, FilterOperator.EQUAL, tagId)).
                addSort(Article.ARTICLE + "_" + Keys.OBJECT_ID, SortDirection.DESCENDING).
                setPage(currentPageNum, pageSize);

        return get(query);
    }
}
