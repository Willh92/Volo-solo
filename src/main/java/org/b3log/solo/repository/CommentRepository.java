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

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.repository.*;
import org.b3log.latke.repository.annotation.Repository;
import org.b3log.solo.cache.CommentCache;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Comment;
import org.b3log.solo.util.QueryResults;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    private boolean isSubclassWithoutDatabase() {
        return getClass() != CommentRepository.class
                && "NONE".equalsIgnoreCase(Latkes.getLocalProperty("runtimeDatabase"));
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
        JSONObject ret = null == commentCache ? null : commentCache.getComment(id);
        if (null != ret) {
            return ret;
        }

        ret = super.get(id);
        if (null == ret) {
            return null;
        }

        if (null != commentCache) {
            commentCache.putComment(ret);
        }

        return org.b3log.solo.util.Solos.clone(ret);
    }

    @Override
    public void update(final String id, final JSONObject comment, final String... propertyNames) throws RepositoryException {
        super.update(id, comment, propertyNames);

        comment.put(Keys.OBJECT_ID, id);
        if (null != commentCache) {
            if (null != propertyNames && propertyNames.length > 0 && !comment.has(Comment.COMMENT_CONTENT)) {
                final JSONObject cached = commentCache.getComment(id);
                if (null != cached) {
                    final String prevOnId = cached.optString(Comment.COMMENT_ON_ID);
                    for (final String prop : propertyNames) {
                        if (comment.has(prop)) {
                            cached.put(prop, comment.opt(prop));
                        }
                    }
                    if (!prevOnId.equals(cached.optString(Comment.COMMENT_ON_ID))) {
                        commentCache.removeComment(id);
                    }
                    commentCache.putComment(cached);
                } else {
                    String onId = comment.optString(Comment.COMMENT_ON_ID);
                    if (onId.isEmpty()) {
                        final JSONObject persisted = super.get(id);
                        if (null != persisted) {
                            onId = persisted.optString(Comment.COMMENT_ON_ID);
                        }
                    }
                    if (!onId.isEmpty()) {
                        final JSONObject carrier = new JSONObject();
                        carrier.put(Keys.OBJECT_ID, id);
                        carrier.put(Comment.COMMENT_ON_ID, onId);
                        commentCache.putComment(carrier);
                    }
                    commentCache.removeComment(id);
                }
            } else {
                commentCache.putComment(comment);
            }
        }
        if (null != statisticCache) {
            statisticCache.invalidateCounts();
        }
    }

    /**
     * Gets recent public approved comments with the specified fetch size, excluding deleted comments,
     * unpublished articles, and password-protected articles directly in SQL.
     *
     * @param fetchSize the specified fetch size
     * @return a list of recent public comments, returns an empty list if not found
     * @throws RepositoryException repository exception
     */
    public List<JSONObject> getRecentComments(final int fetchSize) throws RepositoryException {
        if (fetchSize <= 0) {
            return new ArrayList<>();
        }
        if (null != articleRepository && !isSubclassWithoutDatabase()
                && Latkes.RuntimeDatabase.NONE != Latkes.getRuntimeDatabase()) {
            final List<JSONObject> rows = select(
                    "SELECT c.* FROM " + getName() + " c "
                            + "INNER JOIN " + articleRepository.getName() + " a ON c.commentOnId = a.oId "
                            + "WHERE c.commentStatus = ? AND (c.commentDeleted IS NULL OR c.commentDeleted = ?) "
                            + "AND a.articleStatus = ? AND (a.articleViewPwd IS NULL OR a.articleViewPwd = '') "
                            + "ORDER BY c.commentCreated DESC LIMIT ?",
                    Comment.COMMENT_STATUS_C_APPROVED,
                    false,
                    Article.ARTICLE_STATUS_C_PUBLISHED,
                    fetchSize);
            return null == rows ? new ArrayList<>() : new ArrayList<>(rows);
        }

        final Query query = new Query()
                .setFilter(CompositeFilterOperator.and(
                        new PropertyFilter(Comment.COMMENT_STATUS, FilterOperator.EQUAL, Comment.COMMENT_STATUS_C_APPROVED),
                        new PropertyFilter("commentDeleted", FilterOperator.EQUAL, false)))
                .addSort(Comment.COMMENT_CREATED, SortDirection.DESCENDING)
                .setPage(1, fetchSize)
                .setPageCount(1);
        final List<JSONObject> ret = getList(query);
        removeForUnpublishedOrProtectedArticles(ret);
        return ret;
    }

    public boolean hasApprovedCommentByUser(final String userId) throws RepositoryException {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        if (!isSubclassWithoutDatabase() && Latkes.RuntimeDatabase.NONE != Latkes.getRuntimeDatabase()) {
            final List<JSONObject> rows = select(
                    "SELECT oId FROM " + getName()
                            + " WHERE commentUserId = ? AND commentStatus = ? AND (commentDeleted IS NULL OR commentDeleted = ?) LIMIT 1",
                    userId, Comment.COMMENT_STATUS_C_APPROVED, false);
            return null != rows && !rows.isEmpty();
        }
        final Query query = new Query().setFilter(CompositeFilterOperator.and(
                new PropertyFilter(Comment.COMMENT_USER_ID, FilterOperator.EQUAL, userId),
                new PropertyFilter(Comment.COMMENT_STATUS, FilterOperator.EQUAL, Comment.COMMENT_STATUS_C_APPROVED)))
                .setPage(1, 50).setPageCount(1);
        for (final JSONObject row : getList(query)) {
            if (null != row && !row.optBoolean("commentDeleted")) {
                return true;
            }
        }
        return false;
    }

    public long countPendingComments() throws RepositoryException {
        if (!isSubclassWithoutDatabase() && Latkes.RuntimeDatabase.NONE != Latkes.getRuntimeDatabase()) {
            final List<JSONObject> rows = select(
                    "SELECT COUNT(*) AS C FROM " + getName()
                            + " WHERE commentStatus = ? AND (commentDeleted IS NULL OR commentDeleted = ?)",
                    Comment.COMMENT_STATUS_C_PENDING, false);
            if (null == rows || rows.isEmpty()) {
                return 0L;
            }
            return Math.max(0L, QueryResults.optLongIgnoreCase(rows.get(0), "C"));
        }
        return count(new Query().setFilter(CompositeFilterOperator.and(
                new PropertyFilter(Comment.COMMENT_STATUS, FilterOperator.EQUAL, Comment.COMMENT_STATUS_C_PENDING),
                new PropertyFilter("commentDeleted", FilterOperator.EQUAL, false))));
    }

    public long countPendingCommentsByAuthor(final String authorId) throws RepositoryException {
        return countPendingCommentsByAuthor(authorId, null);
    }

    public long countPendingCommentsByAuthor(final String authorId,
                                             final Collection<String> fallbackArticleIds) throws RepositoryException {
        if (StringUtils.isBlank(authorId)) {
            return 0L;
        }
        if (!isSubclassWithoutDatabase()
                && Latkes.RuntimeDatabase.NONE != Latkes.getRuntimeDatabase()
                && null != articleRepository) {
            final List<JSONObject> rows = select(
                    "SELECT COUNT(*) AS C FROM " + getName() + " c "
                            + "INNER JOIN " + articleRepository.getName() + " a ON c.commentOnId = a.oId "
                            + "WHERE a.articleAuthorId = ? AND c.commentStatus = ? AND (c.commentDeleted IS NULL OR c.commentDeleted = ?)",
                    authorId, Comment.COMMENT_STATUS_C_PENDING, false);
            if (null == rows || rows.isEmpty()) {
                return 0L;
            }
            return Math.max(0L, QueryResults.optLongIgnoreCase(rows.get(0), "C"));
        }
        if (null == fallbackArticleIds || fallbackArticleIds.isEmpty()) {
            return 0L;
        }
        if (isSubclassWithoutDatabase()) {
            long cnt = 0L;
            for (final String articleId : fallbackArticleIds) {
                for (final JSONObject item : getComments(articleId, 1, Integer.MAX_VALUE)) {
                    if (null != item && !item.optBoolean("commentDeleted")
                            && item.optInt(Comment.COMMENT_STATUS, Comment.COMMENT_STATUS_C_APPROVED) == Comment.COMMENT_STATUS_C_PENDING) {
                        cnt++;
                    }
                }
            }
            return cnt;
        }
        return count(new Query().setFilter(CompositeFilterOperator.and(
                new PropertyFilter(Comment.COMMENT_ON_ID, FilterOperator.IN, fallbackArticleIds),
                new PropertyFilter(Comment.COMMENT_STATUS, FilterOperator.EQUAL, Comment.COMMENT_STATUS_C_PENDING),
                new PropertyFilter("commentDeleted", FilterOperator.EQUAL, false))));
    }

    /**
     * Counts approved, non-deleted comments belonging to the specified article.
     *
     * @param articleId the specified article id
     * @return approved active comment count
     * @throws RepositoryException repository exception
     */
    public int getApprovedArticleCommentCount(final String articleId) throws RepositoryException {
        if (null == articleId || articleId.isBlank()) {
            return 0;
        }
        if (isSubclassWithoutDatabase()) {
            final List<JSONObject> comments = getComments(articleId, 1, Integer.MAX_VALUE);
            int cnt = 0;
            for (final JSONObject item : comments) {
                if (null != item && !item.optBoolean("commentDeleted")
                        && item.optInt(Comment.COMMENT_STATUS, Comment.COMMENT_STATUS_C_APPROVED) == Comment.COMMENT_STATUS_C_APPROVED) {
                    cnt++;
                }
            }
            return cnt;
        }
        if (Latkes.RuntimeDatabase.NONE != Latkes.getRuntimeDatabase()) {
            final List<JSONObject> rows = select(
                    "SELECT COUNT(*) AS C FROM " + getName()
                            + " WHERE commentOnId = ? AND commentStatus = ? AND (commentDeleted IS NULL OR commentDeleted = ?)",
                    articleId, Comment.COMMENT_STATUS_C_APPROVED, false);
            if (null == rows || rows.isEmpty()) {
                return 0;
            }
            return Math.max(0, QueryResults.optIntIgnoreCase(rows.get(0), "C"));
        }
        return (int) count(new Query().setFilter(CompositeFilterOperator.and(
                new PropertyFilter(Comment.COMMENT_ON_ID, FilterOperator.EQUAL, articleId),
                new PropertyFilter(Comment.COMMENT_STATUS, FilterOperator.EQUAL, Comment.COMMENT_STATUS_C_APPROVED),
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
     * Removes comments with the specified on id in a single bulk query.
     *
     * @param onId the specified on id
     * @return removed count
     * @throws RepositoryException repository exception
     */
    public int removeComments(final String onId) throws RepositoryException {
        if (null == onId || onId.isBlank()) {
            return 0;
        }
        if (isSubclassWithoutDatabase()) {
            final List<JSONObject> comments = getComments(onId, 1, Integer.MAX_VALUE);
            for (final JSONObject comment : comments) {
                remove(comment.optString(Keys.OBJECT_ID));
            }
            return comments.size();
        }

        final Query query = new Query().setFilter(new PropertyFilter(Comment.COMMENT_ON_ID, FilterOperator.EQUAL, onId));
        final int removedCnt = (int) count(query);
        remove(query);

        if (null != commentCache) {
            commentCache.removeCommentsByArticleId(onId);
        }
        if (null != statisticCache) {
            statisticCache.invalidateCounts();
        }

        LOGGER.log(Level.DEBUG, "Removed comments[onId={0}, removedCnt={1}]", onId, removedCnt);

        return removedCnt;
    }

    /**
     * Fallback filter removing comments belonging to deleted, unpublished, or password-protected articles.
     */
    private void removeForUnpublishedOrProtectedArticles(final List<JSONObject> comments) throws RepositoryException {
        if (null == comments || comments.isEmpty()) {
            return;
        }
        final Set<String> candidateArticleIds = new LinkedHashSet<>();
        final Iterator<JSONObject> iterator = comments.iterator();
        while (iterator.hasNext()) {
            final JSONObject comment = iterator.next();
            if (null == comment
                    || comment.optBoolean("commentDeleted")
                    || comment.optInt(Comment.COMMENT_STATUS, Comment.COMMENT_STATUS_C_APPROVED) != Comment.COMMENT_STATUS_C_APPROVED) {
                iterator.remove();
                continue;
            }
            final String articleId = comment.optString(Comment.COMMENT_ON_ID);
            if (articleId.isBlank()) {
                iterator.remove();
                continue;
            }
            candidateArticleIds.add(articleId);
        }
        if (comments.isEmpty() || null == articleRepository) {
            return;
        }

        final Set<String> publicArticleIds = new LinkedHashSet<>();
        for (final String articleId : candidateArticleIds) {
            final JSONObject article = articleRepository.getBriefForAccessCheck(articleId);
            if (null != article
                    && Article.ARTICLE_STATUS_C_PUBLISHED == article.optInt(Article.ARTICLE_STATUS)
                    && StringUtils.isBlank(article.optString(Article.ARTICLE_VIEW_PWD))) {
                publicArticleIds.add(articleId);
            }
        }
        comments.removeIf(comment -> !publicArticleIds.contains(comment.optString(Comment.COMMENT_ON_ID)));
    }
}
