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
import org.b3log.solo.cache.CommentCache;
import org.b3log.solo.model.Comment;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.List;

/**
 * Comment repository.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 0.3.1
 */
@Repository
public class CommentRepository extends AbstractRepository {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(CommentRepository.class);

    /**
     * Article repository.
     */
    @Inject
    private ArticleRepository articleRepository;

    /**
     * Comment cache.
     */
    @Inject
    private CommentCache commentCache;

    @Inject
    private org.b3log.solo.cache.StatisticCache statisticCache;

    /**
     * Public constructor.
     */
    public CommentRepository() {
        super(Comment.COMMENT);
    }

    @Override
    public String add(final JSONObject comment) throws RepositoryException {
        if (comment.isNull("commentDeleted")) comment.put("commentDeleted", false);
        if (comment.isNull(Comment.COMMENT_STATUS)) comment.put(Comment.COMMENT_STATUS, Comment.COMMENT_STATUS_C_APPROVED);
        if (comment.isNull(Comment.COMMENT_USER_ID)) comment.put(Comment.COMMENT_USER_ID, "");
        final String id = super.add(comment);
        comment.put(Keys.OBJECT_ID, id);
        if (null != commentCache) {
            commentCache.putComment(comment);
        }
        if (null != statisticCache) {
            statisticCache.invalidateCounts();
        }
        return id;
    }

    @Override
    public void remove(final String id) throws RepositoryException {
        super.remove(id);

        if (null != commentCache) {
            commentCache.removeComment(id);
        }
        if (null != statisticCache) {
            statisticCache.invalidateCounts();
        }
    }

    @Override
    public JSONObject get(final String id) throws RepositoryException {
        JSONObject ret = commentCache.getComment(id);
        if (null != ret) {
            return ret;
        }

        ret = super.get(id);
        if (null == ret) {
            return null;
        }

        commentCache.putComment(ret);

        return org.b3log.solo.util.Solos.clone(ret);
    }

    @Override
    public void update(final String id, final JSONObject comment, final String... propertyNames) throws RepositoryException {
        super.update(id, comment, propertyNames);

        comment.put(Keys.OBJECT_ID, id);
        if (null != commentCache) {
            commentCache.putComment(comment);
        }
        if (null != statisticCache) {
            statisticCache.invalidateCounts();
        }
    }

    /**
     * Gets post comments recently with the specified fetch.
     *
     * @param fetchSize the specified fetch size
     * @return a list of comments recently, returns an empty list if not found
     * @throws RepositoryException repository exception
     */
    public List<JSONObject> getRecentComments(final int fetchSize) throws RepositoryException {
        final Query query = new Query().
                setFilter(new PropertyFilter(Comment.COMMENT_STATUS, FilterOperator.EQUAL, Comment.COMMENT_STATUS_C_APPROVED)).
                addSort(Keys.OBJECT_ID, SortDirection.DESCENDING).
                setPage(1, fetchSize).setPageCount(1);
        final List<JSONObject> ret = getList(query);
        // Removes unpublished article related comments
        removeForUnpublishedArticles(ret);

        return ret;
    }

    public boolean hasApprovedCommentByUser(final String userId) throws RepositoryException {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        final Query query = new Query().setFilter(CompositeFilterOperator.and(
                new PropertyFilter(Comment.COMMENT_USER_ID, FilterOperator.EQUAL, userId),
                new PropertyFilter(Comment.COMMENT_STATUS, FilterOperator.EQUAL, Comment.COMMENT_STATUS_C_APPROVED),
                new PropertyFilter("commentDeleted", FilterOperator.EQUAL, false)))
                .setPage(1, 1).setPageCount(1);
        return !getList(query).isEmpty();
    }

    public long countPendingComments() throws RepositoryException {
        return count(new Query().setFilter(CompositeFilterOperator.and(
                new PropertyFilter(Comment.COMMENT_STATUS, FilterOperator.EQUAL, Comment.COMMENT_STATUS_C_PENDING),
                new PropertyFilter("commentDeleted", FilterOperator.EQUAL, false))));
    }

    /**
     * Gets comments with the specified on id, current page number and
     * page size.
     *
     * @param onId           the specified on id
     * @param currentPageNum the specified current page number
     * @param pageSize       the specified page size
     * @return a list of comments, returns an empty list if not found
     * @throws RepositoryException repository exception
     */
    public List<JSONObject> getComments(final String onId, final int currentPageNum, final int pageSize) throws RepositoryException {
        final Query query = new Query().
                addSort(Keys.OBJECT_ID, SortDirection.DESCENDING).
                setFilter(new PropertyFilter(Comment.COMMENT_ON_ID, FilterOperator.EQUAL, onId)).
                setPage(currentPageNum, pageSize).setPageCount(1);

        return getList(query);
    }

    /**
     * Removes comments with the specified on id.
     *
     * @param onId the specified on id
     * @return removed count
     * @throws RepositoryException repository exception
     */
    public int removeComments(final String onId) throws RepositoryException {
        final List<JSONObject> comments = getComments(onId, 1, Integer.MAX_VALUE);
        for (final JSONObject comment : comments) {
            final String commentId = comment.optString(Keys.OBJECT_ID);
            remove(commentId);
        }

        LOGGER.log(Level.DEBUG, "Removed comments[onId={0}, removedCnt={1}]", onId, comments.size());

        return comments.size();
    }

    /**
     * Removes comments of unpublished articles for the specified comments.
     *
     * @param comments the specified comments
     * @throws RepositoryException repository exception
     */
    private void removeForUnpublishedArticles(final List<JSONObject> comments) throws RepositoryException {
        LOGGER.debug("Removing unpublished articles' comments....");
        final Iterator<JSONObject> iterator = comments.iterator();

        while (iterator.hasNext()) {
            final JSONObject comment = iterator.next();
            if (comment.optInt(Comment.COMMENT_STATUS, Comment.COMMENT_STATUS_C_APPROVED) != Comment.COMMENT_STATUS_C_APPROVED) {
                iterator.remove();
                continue;
            }
            final String articleId = comment.optString(Comment.COMMENT_ON_ID);
            if (!articleRepository.isPublished(articleId)) {
                iterator.remove();
            }
        }

        LOGGER.debug("Removed unpublished articles' comments....");
    }
}
