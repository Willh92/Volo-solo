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
package org.b3log.solo.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.b3log.latke.Keys;
import org.b3log.latke.ioc.Inject;
import org.apache.logging.log4j.Level;
import org.b3log.solo.cache.CommentCache;
import org.b3log.solo.logging.Logger;
import org.b3log.latke.model.Pagination;
import org.b3log.latke.model.Role;
import org.b3log.latke.model.User;
import org.b3log.latke.repository.Query;
import org.b3log.latke.repository.SortDirection;
import org.b3log.latke.repository.Transaction;
import org.b3log.latke.service.ServiceException;
import org.b3log.latke.service.annotation.Service;
import org.b3log.latke.util.Paginator;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Comment;
import org.b3log.solo.model.Common;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.repository.CommentRepository;
import org.b3log.solo.repository.PageRepository;
import org.b3log.solo.util.Markdowns;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Comment query service.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 0.3.5
 */
@Service
public class CommentQueryService {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(CommentQueryService.class);

    /**
     * User service.
     */
    @Inject
    private UserQueryService userQueryService;

    /**
     * Comment repository.
     */
    @Inject
    private CommentRepository commentRepository;

    /**
     * Article repository.
     */
    @Inject
    private ArticleRepository articleRepository;

    /**
     * Page repository.
     */
    @Inject
    private PageRepository pageRepository;

    @Inject
    private CommentCache commentCache;

    /**
     * Can the specified user access a comment specified by the given comment id?
     *
     * @param commentId the given comment id
     * @param user      the specified user
     * @return {@code true} if the current user can access the comment, {@code false} otherwise
     * @throws Exception exception
     */
    public boolean canAccessComment(final String commentId, final JSONObject user) throws Exception {
        if (StringUtils.isBlank(commentId)) {
            return false;
        }

        if (null == user) {
            return false;
        }

        if (Role.ADMIN_ROLE.equals(user.optString(User.USER_ROLE))) {
            return true;
        }

        final JSONObject comment = commentRepository.get(commentId);
        if (null == comment) {
            return false;
        }

        final String onId = comment.optString(Comment.COMMENT_ON_ID);
        final JSONObject article = articleRepository.get(onId);
        if (null == article) {
            return false;
        }

        final String currentUserId = user.getString(Keys.OBJECT_ID);

        return article.getString(Article.ARTICLE_AUTHOR_ID).equals(currentUserId);
    }

    /**
     * Gets comments with the specified request json object, request and response.
     *
     * @param requestJSONObject the specified request json object
     * @return comments result JSON
     * @throws ServiceException service exception
     * @see Pagination
     */
    public JSONObject getComments(final JSONObject requestJSONObject) throws ServiceException {
        return getComments(requestJSONObject, false);
    }

    public JSONObject getComments(final JSONObject requestJSONObject, final boolean administrator) throws ServiceException {
        try {
            final JSONObject ret = new JSONObject();

            final int currentPageNum = requestJSONObject.getInt(Pagination.PAGINATION_CURRENT_PAGE_NUM);
            final int pageSize = requestJSONObject.getInt(Pagination.PAGINATION_PAGE_SIZE);
            final int windowSize = requestJSONObject.getInt(Pagination.PAGINATION_WINDOW_SIZE);

            final Query query = new Query().setPage(currentPageNum, pageSize).addSort(Comment.COMMENT_CREATED,
                    SortDirection.DESCENDING);
            if (administrator) org.b3log.solo.util.AdminListSearch.apply(query, requestJSONObject, Comment.COMMENT_CONTENT,
                    Comment.COMMENT_NAME, "commentNotifyEmail", org.b3log.solo.util.IpAddresses.COMMENT_IP);
            else org.b3log.solo.util.AdminListSearch.apply(query, requestJSONObject, Comment.COMMENT_CONTENT, Comment.COMMENT_NAME);
            final String keyword = requestJSONObject.optString(Common.KEYWORD).strip().toLowerCase(java.util.Locale.ROOT);
            if (administrator && !keyword.isEmpty()) {
                final var legacyIds = org.b3log.solo.bolo.prop.MailService.getUserMailContext().stream()
                        .filter(binding -> binding.getCommentEmail() != null && binding.getCommentEmail().toLowerCase(java.util.Locale.ROOT).contains(keyword))
                        .map(binding -> binding.getCommentId()).distinct().toList();
                if (!legacyIds.isEmpty()) query.setFilter(org.b3log.latke.repository.CompositeFilterOperator.or(query.getFilter(),
                        new org.b3log.latke.repository.PropertyFilter(Keys.OBJECT_ID, org.b3log.latke.repository.FilterOperator.IN, legacyIds)));
            }
            final String statusFilter = requestJSONObject.optString("status", "all").trim().toLowerCase(java.util.Locale.ROOT);
            final Integer statusCode = switch (statusFilter) {
                case "pending" -> Comment.COMMENT_STATUS_C_PENDING;
                case "approved" -> Comment.COMMENT_STATUS_C_APPROVED;
                case "rejected" -> Comment.COMMENT_STATUS_C_REJECTED;
                default -> null;
            };
            if (statusCode != null) {
                final var pf = new org.b3log.latke.repository.PropertyFilter(Comment.COMMENT_STATUS,
                        org.b3log.latke.repository.FilterOperator.EQUAL, statusCode);
                query.setFilter(query.getFilter() == null ? pf
                        : org.b3log.latke.repository.CompositeFilterOperator.and(query.getFilter(), pf));
            }
            final JSONObject result = commentRepository.get(query);
            final JSONArray comments = org.b3log.solo.util.QueryResults.array(result);
            final Map<String, JSONObject> preloadedArticles = preloadCommentArticles(comments);
            final JSONArray validComments = new JSONArray();

            // Sets comment title and content escaping
            for (int i = 0; i < comments.length(); i++) {
                final JSONObject comment = comments.getJSONObject(i);
                final String onId = comment.getString(Comment.COMMENT_ON_ID);
                final JSONObject article = null != preloadedArticles
                        ? preloadedArticles.get(onId)
                        : articleRepository.get(onId);
                if (null == article) {
                    // 某种情况下导致的数据不一致：文章已经被删除了，但是评论还在
                    // 为了保持数据一致性，需要删除该条评论 https://ld246.com/article/1556060195022
                    final Transaction transaction = commentRepository.beginTransaction();
                    final String commentId = comment.optString(Keys.OBJECT_ID);
                    commentRepository.remove(commentId);
                    transaction.commit();
                    if (null != commentCache) {
                        commentCache.invalidateArticleComments(onId);
                    }

                    continue;
                }

                final String title = article.getString(Article.ARTICLE_TITLE);
                comment.put(Common.TYPE, Common.ARTICLE_COMMENT_TYPE);
                comment.put(Common.COMMENT_TITLE, title);
                final String permalink = article.optString(Article.ARTICLE_PERMALINK);
                if (!permalink.isEmpty() && !comment.has(Comment.COMMENT_SHARP_URL)) {
                    comment.put(Comment.COMMENT_SHARP_URL, permalink + "#" + comment.optString(Keys.OBJECT_ID));
                }

                String commentContent = comment.optString(Comment.COMMENT_CONTENT);
                commentContent = comment.optBoolean("commentDeleted") ? "<p class=\"comment-tombstone\">这条评论已删除</p>" : Markdowns.toHTML(commentContent);
                commentContent = Markdowns.clean(commentContent);
                comment.put(Comment.COMMENT_CONTENT, commentContent);

                org.b3log.solo.util.CommentNames.apply(comment);

                comment.put(Comment.COMMENT_TIME, comment.optLong(Comment.COMMENT_CREATED));
                comment.remove(Comment.COMMENT_CREATED);
                comment.remove(org.b3log.solo.util.CommentOwnership.KEY_HASH);
                if (!administrator) org.b3log.solo.util.IpAddresses.hidePrivateAddress(comment);
                else comment.put("commentRegion", org.b3log.solo.util.IpGeo.region(comment.optString(org.b3log.solo.util.IpAddresses.COMMENT_IP)));
                validComments.put(comment);
            }

            final int pageCount = result.getJSONObject(Pagination.PAGINATION).getInt(Pagination.PAGINATION_PAGE_COUNT);
            final JSONObject pagination = new JSONObject();
            final List<Integer> pageNums = Paginator.paginate(currentPageNum, pageSize, pageCount, windowSize);

            pagination.put(Pagination.PAGINATION_PAGE_COUNT, pageCount);
            pagination.put(Pagination.PAGINATION_PAGE_NUMS, pageNums);

            ret.put(Comment.COMMENTS, validComments);
            ret.put(Pagination.PAGINATION, pagination);
            ret.put("pendingCount", commentRepository.countPendingComments());

            return ret;
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Gets comments failed", e);

            throw new ServiceException(e);
        }
    }

    private Map<String, JSONObject> preloadCommentArticles(final JSONArray comments) {
        if (null == comments || 0 == comments.length()) {
            return Collections.emptyMap();
        }
        if (null == articleRepository
                || org.b3log.latke.Latkes.RuntimeDatabase.NONE == org.b3log.latke.Latkes.getRuntimeDatabase()) {
            return null;
        }
        try {
            final Set<String> onIds = new LinkedHashSet<>(comments.length());
            for (int i = 0; i < comments.length(); i++) {
                final JSONObject comment = comments.optJSONObject(i);
                if (null != comment) {
                    final String onId = comment.optString(Comment.COMMENT_ON_ID);
                    if (!onId.isEmpty()) {
                        onIds.add(onId);
                    }
                }
            }
            if (onIds.isEmpty()) {
                return Collections.emptyMap();
            }
            final String placeholders = String.join(",", Collections.nCopies(onIds.size(), "?"));
            final List<JSONObject> rows = articleRepository.select(
                    "SELECT oId, articleTitle, articlePermalink FROM " + articleRepository.getName()
                            + " WHERE oId IN (" + placeholders + ")",
                    onIds.toArray());
            if (null == rows) {
                return null;
            }
            final Map<String, JSONObject> map = new HashMap<>(rows.size() * 2);
            for (final JSONObject row : rows) {
                final String id = row.optString(Keys.OBJECT_ID);
                if (!id.isEmpty()) {
                    map.put(id, row);
                }
            }
            return map;
        } catch (final Exception e) {
            LOGGER.log(Level.DEBUG, "Batch article preload for comments fell back to per-comment lookup", e);
            return null;
        }
    }

    /**
     * Gets comments of an article or page specified by the on id.
     *
     * @param onId the specified on id
     * @return a list of comments, returns an empty list if not found
     * @throws ServiceException service exception
     */
    public List<JSONObject> getComments(final String onId) throws ServiceException {
        if (StringUtils.isBlank(onId)) {
            return new ArrayList<>();
        }
        if (null != commentCache) {
            final List<JSONObject> cached = commentCache.getCommentsByArticleId(onId);
            if (null != cached) {
                return cached;
            }
        }
        try {
            final List<JSONObject> ret = new ArrayList<>();
            final List<JSONObject> comments = commentRepository.getComments(onId, 1, Integer.MAX_VALUE);
            for (final JSONObject comment : comments) {
                if (comment.optInt(Comment.COMMENT_STATUS, Comment.COMMENT_STATUS_C_APPROVED) != Comment.COMMENT_STATUS_C_APPROVED) {
                    continue;
                }
                comment.put("commentRegistered", !comment.optString(Comment.COMMENT_USER_ID).isBlank());
                comment.remove(Comment.COMMENT_USER_ID);
                comment.put(Comment.COMMENT_TIME, comment.optLong(Comment.COMMENT_CREATED));
                comment.put(Comment.COMMENT_T_DATE, new Date(comment.optLong(Comment.COMMENT_CREATED)));
                comment.put("commentDate2", new Date(comment.optLong(Comment.COMMENT_CREATED))); // 1.9.0 向后兼容
                comment.put(Comment.COMMENT_NAME, comment.getString(Comment.COMMENT_NAME));
                String url = comment.getString(Comment.COMMENT_URL);
                if (StringUtils.contains(url, "<")) { // legacy issue https://github.com/b3log/solo/issues/12091
                    url = "";
                }
                comment.put(Comment.COMMENT_URL, url);
                String commentContent = comment.optString(Comment.COMMENT_CONTENT);
                commentContent = comment.optBoolean("commentDeleted") ? "<p class=\"comment-tombstone\">这条评论已删除</p>" : Markdowns.toHTML(commentContent);
                commentContent = Markdowns.clean(commentContent);
                comment.put(Comment.COMMENT_CONTENT, commentContent);

                org.b3log.solo.util.CommentNames.apply(comment);

                org.b3log.solo.util.IpAddresses.hidePrivateAddress(comment);
                ret.add(comment);
            }

            final List<JSONObject> display = org.b3log.solo.util.CommentThreads.forDisplay(ret);
            if (null != commentCache) {
                commentCache.putCommentsByArticleId(onId, display);
            }
            return display;
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Gets comments failed", e);
            throw new ServiceException(e);
        }
    }
}
