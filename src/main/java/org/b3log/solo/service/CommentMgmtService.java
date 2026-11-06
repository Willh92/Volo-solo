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

import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.event.EventManager;
import org.b3log.latke.ioc.Inject;
import org.apache.logging.log4j.Level;
import org.b3log.solo.cache.CommentCache;
import org.b3log.solo.cache.PublicViewCache;
import org.b3log.solo.logging.Logger;
import org.b3log.latke.repository.RepositoryException;
import org.b3log.latke.repository.Transaction;
import org.b3log.latke.service.LangPropsService;
import org.b3log.latke.service.ServiceException;
import org.b3log.latke.service.annotation.Service;
import org.b3log.latke.util.Ids;
import org.b3log.solo.bolo.Global;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Comment;
import org.b3log.solo.model.Common;
import org.b3log.solo.model.Option;
import org.b3log.solo.model.UserExt;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.repository.CommentRepository;
import org.b3log.solo.repository.UserRepository;
import org.b3log.solo.repository.OptionRepository;
import org.b3log.solo.util.CommentThreads;
import org.b3log.solo.util.Markdowns;
import org.b3log.solo.util.Solos;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import jodd.http.HttpRequest;
import jodd.http.HttpResponse;

/**
 * Comment management service.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 0.3.5
 */
@Service
public class CommentMgmtService {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(CommentMgmtService.class);

    /**
     * Minimum length of comment name.
     */
    private static final int MIN_COMMENT_NAME_LENGTH = 2;

    /**
     * Maximum length of comment name.
     */
    private static final int MAX_COMMENT_NAME_LENGTH = 20;

    /**
     * Minimum length of comment content.
     */
    private static final int MIN_COMMENT_CONTENT_LENGTH = 2;

    /**
     * Maximum length of comment content.
     */
    private static final int MAX_COMMENT_CONTENT_LENGTH = 500;

    /**
     * Event manager.
     */
    @Inject
    private static EventManager eventManager;

    /**
     * Article management service.
     */
    @Inject
    private ArticleMgmtService articleMgmtService;

    @Inject
    private ArticleQueryService articleQueryService;

    @Inject
    private UserQueryService userQueryService;

    @Inject
    private CommentQueryService commentQueryService;

    /**
     * Comment repository.
     */
    @Inject
    private CommentRepository commentRepository;

    @Inject
    private OptionRepository optionRepository;

    /**
     * Article repository.
     */
    @Inject
    private ArticleRepository articleRepository;

    /**
     * User repository.
     */
    @Inject
    private UserRepository userRepository;

    /**
     * Statistic management service.
     */
    @Inject
    private StatisticMgmtService statisticMgmtService;

    /**
     * Option query service.
     */
    @Inject
    private OptionQueryService optionQueryService;

    /**
     * Language service.
     */
    @Inject
    private LangPropsService langPropsService;

    @Inject
    private WebhookNotifyService webhookNotifyService;

    @Inject
    private CommentCache commentCache;

    @Inject
    private PublicViewCache publicViewCache;

    @Inject
    private org.b3log.solo.cache.StatisticCache statisticCache;

    public void invalidateCommentAndPublicCaches(final String articleId) {
        if (null != commentCache) {
            commentCache.invalidateArticleComments(articleId);
        }
        if (null != publicViewCache) {
            publicViewCache.invalidateArticle(articleId);
        }
        if (null != statisticCache) {
            statisticCache.invalidateCounts();
        }
    }

    /**
     * Checks the specified comment adding request.
     * <p>
     * XSS process (name) in this method.
     * </p>
     *
     * @param requestJSONObject the specified comment adding request
     * @return check result
     */
    public JSONObject checkAddCommentRequest(final JSONObject requestJSONObject) {
        final JSONObject ret = new JSONObject();

        try {
            ret.put(org.b3log.solo.model.Common.STATUS_CODE, false);
            if (null == requestJSONObject) {
                ret.put(Keys.MSG, langPropsService.get("addFailLabel"));
                return ret;
            }
            final JSONObject preference = optionQueryService.getPreference();

            if (null == preference || !preference.optBoolean(Option.ID_C_COMMENTABLE)) {
                ret.put(Keys.MSG, langPropsService.get("notAllowCommentLabel"));

                return ret;
            }

            final String id = requestJSONObject.optString(Keys.OBJECT_ID);
            final String type = requestJSONObject.optString(Common.TYPE);

            if (Article.ARTICLE.equals(type)) {
                final JSONObject article = articleRepository.getBriefForAccessCheck(id);

                if (null == article || article.optInt(Article.ARTICLE_STATUS) != Article.ARTICLE_STATUS_C_PUBLISHED
                        || !article.optBoolean(Article.ARTICLE_COMMENTABLE)) {
                    ret.put(Keys.MSG, langPropsService.get("notAllowCommentLabel"));

                    return ret;
                }
            } else {
                ret.put(Keys.MSG, langPropsService.get("notAllowCommentLabel"));

                return ret;
            }

            String commentName = requestJSONObject.getString(Comment.COMMENT_NAME);
            if (org.b3log.solo.util.CommentNames.hasMarkup(commentName)) {
                ret.put(Keys.MSG, "昵称不能包含 HTML 标签。");
                return ret;
            }
            if (MAX_COMMENT_NAME_LENGTH < commentName.length() || MIN_COMMENT_NAME_LENGTH > commentName.length()) {
                LOGGER.log(Level.WARN, "Comment nickname length is invalid");
                ret.put(Keys.MSG, langPropsService.get("nameTooLongLabel"));

                return ret;
            }

            final String parentId = requestJSONObject.optString(Comment.COMMENT_ORIGINAL_COMMENT_ID);
            if (parentId.length() > 64 || org.b3log.solo.util.CommentNames.hasMarkup(parentId)) {
                ret.put(Keys.MSG, "回复的评论编号不正确。");
                return ret;
            }
            if (!parentId.isBlank()) {
                final JSONObject parent = commentRepository.get(parentId);
                if (parent == null || !id.equals(parent.optString(Comment.COMMENT_ON_ID))
                        || parent.optBoolean("commentDeleted")
                        || parent.optInt(Comment.COMMENT_STATUS, Comment.COMMENT_STATUS_C_APPROVED) != Comment.COMMENT_STATUS_C_APPROVED) {
                    ret.put(Keys.MSG, "回复的评论不存在，或不属于当前文章。");
                    return ret;
                }
            }
            final String commentURL = requestJSONObject.optString(Comment.COMMENT_URL);

            if (!org.b3log.solo.util.OutboundUrls.isHttpOrHttps(commentURL)) {
                requestJSONObject.put(Comment.COMMENT_URL, "");
            }

            String commentContent = requestJSONObject.optString(Comment.COMMENT_CONTENT);

            if (MAX_COMMENT_CONTENT_LENGTH < commentContent.length()
                    || MIN_COMMENT_CONTENT_LENGTH > commentContent.length()) {
                LOGGER.log(Level.WARN, "Comment content length is invalid[{0}]", commentContent.length());
                ret.put(Keys.MSG, langPropsService.get("commentContentCannotEmptyLabel"));

                return ret;
            }

            ret.put(org.b3log.solo.model.Common.STATUS_CODE, true);
            requestJSONObject.put(Comment.COMMENT_CONTENT, commentContent);

            return ret;
        } catch (final Exception e) {
            LOGGER.log(Level.WARN, "Checks add comment request failed", e);

            ret.put(org.b3log.solo.model.Common.STATUS_CODE, false);
            ret.put(Keys.MSG, langPropsService.get("addFailLabel"));

            return ret;
        }
    }

    /**
     * Adds an article comment with the specified request json object.
     *
     * @param requestJSONObject the specified request json object
     * @return add result
     * @throws ServiceException service exception
     */
    public synchronized JSONObject addArticleComment(final JSONObject requestJSONObject) throws ServiceException {
        if (null == requestJSONObject) {
            throw new ServiceException("评论请求不能为空。");
        }
        final JSONObject ret = new JSONObject();
        ret.put(Common.IS_REPLY, false);

        String committedArticleId = "";
        final Transaction transaction = commentRepository.beginTransaction();

        try {
            final String articleId = requestJSONObject.getString(Keys.OBJECT_ID);
            committedArticleId = articleId;
            final JSONObject article = articleRepository.getBriefForAccessCheck(articleId);
            if (article == null || article.optInt(Article.ARTICLE_STATUS) != Article.ARTICLE_STATUS_C_PUBLISHED
                    || !article.optBoolean(Article.ARTICLE_COMMENTABLE)) throw new ServiceException("文章暂不接受评论。");
            ret.put(Article.ARTICLE, article);
            final String commentName = requestJSONObject.getString(Comment.COMMENT_NAME);
            final String commentURL = requestJSONObject.optString(Comment.COMMENT_URL);
            final String commentContent = requestJSONObject.getString(Comment.COMMENT_CONTENT);
            final String originalCommentId = requestJSONObject.optString(Comment.COMMENT_ORIGINAL_COMMENT_ID);
            if (org.b3log.solo.util.CommentNames.hasMarkup(commentName)
                    || commentName.length() < MIN_COMMENT_NAME_LENGTH || commentName.length() > MAX_COMMENT_NAME_LENGTH
                    || commentContent.length() < MIN_COMMENT_CONTENT_LENGTH || commentContent.length() > MAX_COMMENT_CONTENT_LENGTH
                    || originalCommentId.length() > 64) throw new ServiceException("评论内容或昵称不正确。");
            ret.put(Comment.COMMENT_ORIGINAL_COMMENT_ID, originalCommentId);
            final JSONObject comment = new JSONObject();
            comment.put(Comment.COMMENT_ORIGINAL_COMMENT_ID, "");
            comment.put(Comment.COMMENT_ORIGINAL_COMMENT_NAME, "");
            comment.put(Comment.COMMENT_NAME, commentName);
            comment.put(Comment.COMMENT_URL, commentURL);
            comment.put(Comment.COMMENT_CONTENT, commentContent);
            comment.put("commentNotifyEmail", requestJSONObject.optString("email"));
            comment.put(org.b3log.solo.util.CommentOwnership.KEY_HASH, requestJSONObject.optString(org.b3log.solo.util.CommentOwnership.KEY_HASH));
            comment.put("commentDeleted", false);
            final int commentStatus = requestJSONObject.optInt(Comment.COMMENT_STATUS, Comment.COMMENT_STATUS_C_APPROVED);
            final String commentUserId = requestJSONObject.optString(Comment.COMMENT_USER_ID, "");
            comment.put(Comment.COMMENT_STATUS, commentStatus);
            comment.put(Comment.COMMENT_USER_ID, commentUserId);
            ret.put(Comment.COMMENT_STATUS, commentStatus);
            ret.put("pending", commentStatus == Comment.COMMENT_STATUS_C_PENDING);
            comment.put(org.b3log.solo.util.IpAddresses.COMMENT_IP,
                    org.b3log.solo.util.IpAddresses.canonical(requestJSONObject.optString(org.b3log.solo.util.IpAddresses.COMMENT_IP)));
            final JSONObject preference = optionQueryService.getPreference();
            final Date date = new Date();
            comment.put(Comment.COMMENT_CREATED, date.getTime());
            ret.put(Comment.COMMENT_T_DATE, DateFormatUtils.format(date, "yyyy-MM-dd HH:mm:ss"));
            ret.put("commentDate2", date);
            ret.put(Common.COMMENTABLE,
                    null != preference && preference.optBoolean(Option.ID_C_COMMENTABLE) && article.optBoolean(Article.ARTICLE_COMMENTABLE));
            ret.put(Common.PERMALINK, article.optString(Article.ARTICLE_PERMALINK));
            ret.put(Comment.COMMENT_NAME, commentName);
            String cmtContent = Markdowns.toHTML(commentContent);
            cmtContent = Markdowns.clean(cmtContent);
            ret.put(Comment.COMMENT_CONTENT, cmtContent);
            ret.put(Comment.COMMENT_URL, commentURL);

            JSONObject originalComment;
            if (StringUtils.isNotBlank(originalCommentId)) {
                originalComment = commentRepository.get(originalCommentId);
                if (originalComment == null || !articleId.equals(originalComment.optString(Comment.COMMENT_ON_ID))) {
                    throw new ServiceException("回复的评论不存在，或不属于当前文章。");
                }
                comment.put(Comment.COMMENT_ORIGINAL_COMMENT_ID, originalCommentId);
                final String originalCommentName = org.b3log.solo.util.CommentNames.display(originalComment.optString(Comment.COMMENT_NAME));
                comment.put(Comment.COMMENT_ORIGINAL_COMMENT_NAME, originalCommentName);
                ret.put(Comment.COMMENT_ORIGINAL_COMMENT_NAME, originalCommentName);
                ret.put(Common.IS_REPLY, true);
            }
            setCommentThumbnailURL(comment);
            ret.put(Comment.COMMENT_THUMBNAIL_URL, comment.getString(Comment.COMMENT_THUMBNAIL_URL));
            comment.put(Comment.COMMENT_ON_ID, articleId);
            final String commentId = Ids.genTimeMillisId();
            comment.put(Keys.OBJECT_ID, commentId);
            ret.put(Keys.OBJECT_ID, commentId);
            final String commentSharpURL = Comment.getCommentSharpURLForArticle(article, commentId);
            comment.put(Comment.COMMENT_SHARP_URL, commentSharpURL);
            ret.put(Comment.COMMENT_SHARP_URL, commentSharpURL);

            ret.put(org.b3log.solo.util.IpAddresses.COMMENT_IP, comment.optString(org.b3log.solo.util.IpAddresses.COMMENT_IP));
            org.b3log.solo.util.CommentNames.apply(ret);
            org.b3log.solo.util.IpAddresses.hidePrivateAddress(ret);
            commentRepository.add(comment);
            if (commentStatus == Comment.COMMENT_STATUS_C_APPROVED) {
                articleMgmtService.incArticleCommentCount(articleId);
            }

            transaction.commit();
        } catch (final Exception e) {
            if (null != transaction && transaction.isActive()) {
                transaction.rollback();
            }

            throw new ServiceException(e);
        }

        invalidateCommentAndPublicCaches(committedArticleId);

        if (null != webhookNotifyService) {
            try {
                final boolean pending = ret.optInt(Comment.COMMENT_STATUS, Comment.COMMENT_STATUS_C_APPROVED) == Comment.COMMENT_STATUS_C_PENDING;
                final JSONObject art = ret.optJSONObject(Article.ARTICLE);
                final String artTitle = art == null ? "" : art.optString(Article.ARTICLE_TITLE);
                webhookNotifyService.notifyEvent(
                        pending ? "comment.pending" : "comment.created",
                        (pending ? "新评论待审核：" : "收到新评论：") + ret.optString(Comment.COMMENT_NAME),
                        new JSONObject()
                                .put("article", artTitle)
                                .put("author", ret.optString(Comment.COMMENT_NAME))
                                .put("status", pending ? "pending" : "approved"));
            } catch (final Exception ignored) {
            }
        }
        return ret;
    }

    public synchronized void updateCommentContentAndStatus(final String commentId, final String content, final int status) throws ServiceException {
        JSONObject updatedComment = null;
        String articleId = "";
        final Transaction transaction = commentRepository.beginTransaction();
        try {
            final JSONObject comment = commentRepository.get(commentId);
            if (comment == null || comment.optBoolean("commentDeleted")) throw new ServiceException("评论已删除。");
            comment.put(Comment.COMMENT_CONTENT, content);
            comment.put(Comment.COMMENT_STATUS, status);
            commentRepository.update(commentId, comment, Comment.COMMENT_CONTENT, Comment.COMMENT_STATUS);
            articleId = comment.optString(Comment.COMMENT_ON_ID);
            if (!articleId.isBlank()) {
                setArticleCommentCount(articleId, commentRepository.getApprovedArticleCommentCount(articleId));
            }
            updatedComment = comment;
            transaction.commit();
        } catch (Exception failure) {
            if (transaction.isActive()) transaction.rollback();
            throw new ServiceException(failure);
        }
        invalidateCommentAndPublicCaches(articleId);
        if (status == Comment.COMMENT_STATUS_C_PENDING && null != webhookNotifyService && null != updatedComment) {
            try {
                webhookNotifyService.notifyEvent(
                        "comment.pending",
                        "编辑评论待审核：" + updatedComment.optString(Comment.COMMENT_NAME),
                        new JSONObject()
                                .put("author", updatedComment.optString(Comment.COMMENT_NAME))
                                .put("status", "pending"));
            } catch (final Exception ignored) {
            }
        }
    }

    public synchronized void updateCommentContent(final String commentId, final String content) throws ServiceException {
        String articleId = "";
        final Transaction transaction = commentRepository.beginTransaction();
        try {
            final JSONObject comment = commentRepository.get(commentId);
            if (comment == null || comment.optBoolean("commentDeleted")) throw new ServiceException("评论已删除。");
            articleId = comment.optString(Comment.COMMENT_ON_ID);
            comment.put(Comment.COMMENT_CONTENT, content);
            commentRepository.update(commentId, comment, Comment.COMMENT_CONTENT);
            transaction.commit();
        } catch (Exception failure) {
            if (transaction.isActive()) transaction.rollback();
            throw new ServiceException(failure);
        }
        invalidateCommentAndPublicCaches(articleId);
    }

    /** Remove the content while preserving the actual parent relation and other visitors' replies. */
    public synchronized void removeArticleComment(final String commentId) throws ServiceException {
        String articleId = "";
        final Transaction transaction = commentRepository.beginTransaction();
        try {
            final JSONObject comment = commentRepository.get(commentId);
            if (comment == null) throw new ServiceException("评论不存在。");
            comment.put(Comment.COMMENT_CONTENT, "").put("commentDeleted", true).put("commentNotifyEmail", "");
            commentRepository.update(commentId, comment, Comment.COMMENT_CONTENT, "commentDeleted", "commentNotifyEmail");
            articleId = comment.optString(Comment.COMMENT_ON_ID);
            if (!articleId.isBlank()) {
                setArticleCommentCount(articleId, commentRepository.getApprovedArticleCommentCount(articleId));
            }
            final JSONObject mail = optionRepository.get(Option.ID_C_MAIL_USER_CONTEXT);
            if (mail != null) {
                mail.put(Option.OPTION_VALUE, CommentThreads.removeMailBindings(mail.optString(Option.OPTION_VALUE), java.util.Set.of(commentId)));
                optionRepository.update(Option.ID_C_MAIL_USER_CONTEXT, mail);
            }
            transaction.commit();
        } catch (Exception failure) {
            if (transaction.isActive()) transaction.rollback();
            throw new ServiceException(failure);
        }
        invalidateCommentAndPublicCaches(articleId);
    }

    public synchronized void removeArticleCommentThread(final String commentId) throws ServiceException {
        String articleId = "";
        final Transaction transaction = commentRepository.beginTransaction();

        try {
            final JSONObject comment = commentRepository.get(commentId);
            if (comment == null) throw new ServiceException("评论不存在或已被删除。");
            articleId = comment.getString(Comment.COMMENT_ON_ID);
            final java.util.List<JSONObject> comments = commentRepository.getComments(articleId, 1, Integer.MAX_VALUE);
            final String rootId = CommentThreads.twoLevels(comments).stream().filter(item -> commentId.equals(item.optString(Keys.OBJECT_ID)))
                    .map(item -> item.getString("commentThreadId")).findFirst().orElse(commentId);
            final java.util.Set<String> removed = CommentThreads.descendants(comments, articleId, rootId);
            for (final String id : removed) commentRepository.remove(id);
            setArticleCommentCount(articleId, commentRepository.getApprovedArticleCommentCount(articleId));
            final JSONObject mail = optionRepository.get(Option.ID_C_MAIL_USER_CONTEXT);
            if (mail != null) {
                mail.put(Option.OPTION_VALUE, CommentThreads.removeMailBindings(mail.optString(Option.OPTION_VALUE), removed));
                optionRepository.update(Option.ID_C_MAIL_USER_CONTEXT, mail);
            }

            transaction.commit();
        } catch (final Exception e) {
            if (transaction.isActive()) {
                transaction.rollback();
            }

            LOGGER.log(Level.ERROR, "Removes a comment of an article failed", e);
            throw new ServiceException(e);
        }
        invalidateCommentAndPublicCaches(articleId);
    }

    public void removeArticleAllComment(final String articleId) throws ServiceException {
        try {
            commentRepository.removeComments(articleId);
            setArticleCommentCount(articleId, 0);
            invalidateCommentAndPublicCaches(articleId);
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Removes a comment of an article failed", e);
            throw new ServiceException(e);
        }
    }

    public int resolveInitialStatus(final JSONObject preference, final boolean adminLoggedIn,
            final String userId, final String content) throws RepositoryException {
        if (adminLoggedIn) {
            return Comment.COMMENT_STATUS_C_APPROVED;
        }
        final String mode = preference == null ? "off"
                : preference.optString(Option.ID_C_COMMENT_MODERATION_MODE, "off").trim().toLowerCase(java.util.Locale.ROOT);
        if ("all".equals(mode)) {
            return Comment.COMMENT_STATUS_C_PENDING;
        }
        if ("smart".equals(mode)) {
            if (countExternalLinks(content) >= 2) {
                return Comment.COMMENT_STATUS_C_PENDING;
            }
            if (userId == null || userId.isBlank()) {
                return Comment.COMMENT_STATUS_C_PENDING;
            }
            return commentRepository != null && commentRepository.hasApprovedCommentByUser(userId)
                    ? Comment.COMMENT_STATUS_C_APPROVED
                    : Comment.COMMENT_STATUS_C_PENDING;
        }
        return Comment.COMMENT_STATUS_C_APPROVED;
    }

    public static int countExternalLinks(final String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        final java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)https?://").matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    public synchronized JSONObject moderateComment(final String commentId, final int targetStatus) throws ServiceException {
        if (targetStatus != Comment.COMMENT_STATUS_C_APPROVED && targetStatus != Comment.COMMENT_STATUS_C_REJECTED) {
            throw new ServiceException("不支持的审核状态。");
        }
        String articleId = "";
        final Transaction transaction = commentRepository.beginTransaction();
        try {
            final JSONObject comment = commentRepository.get(commentId);
            if (comment == null || comment.optBoolean("commentDeleted")) {
                throw new ServiceException("评论不存在或已删除。");
            }
            final int previousStatus = comment.optInt(Comment.COMMENT_STATUS, Comment.COMMENT_STATUS_C_APPROVED);
            comment.put(Comment.COMMENT_STATUS, targetStatus);
            commentRepository.update(commentId, comment, Comment.COMMENT_STATUS);
            articleId = comment.optString(Comment.COMMENT_ON_ID);
            if (!articleId.isBlank()) {
                setArticleCommentCount(articleId, commentRepository.getApprovedArticleCommentCount(articleId));
            }
            transaction.commit();
            invalidateCommentAndPublicCaches(articleId);
            return new JSONObject()
                    .put("previousStatus", previousStatus)
                    .put("status", targetStatus)
                    .put("comment", comment);
        } catch (final ServiceException se) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
            throw se;
        } catch (final Exception e) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
            throw new ServiceException(e);
        }
    }

    public void recomputeArticleCommentCount(final String articleId)
            throws JSONException, RepositoryException {
        if (StringUtils.isBlank(articleId)) {
            return;
        }
        setArticleCommentCount(articleId, commentRepository.getApprovedArticleCommentCount(articleId));
    }

    private void setArticleCommentCount(final String articleId, final int cnt)
            throws JSONException, RepositoryException {
        final JSONObject article = articleRepository.getPersisted(articleId);
        if (article == null) {
            return;
        }
        final JSONObject newArticle = Solos.clone(article);
        newArticle.put(Article.ARTICLE_COMMENT_COUNT, cnt);
        articleRepository.update(articleId, newArticle, Article.ARTICLE_COMMENT_COUNT);
    }

    /**
     * Sets commenter thumbnail URL for the specified comment.
     *
     * @param comment the specified comment
     * @throws Exception exception
     */
    public void setCommentThumbnailURL(final JSONObject comment) throws Exception {
        final String commenterName = comment.optString(Comment.COMMENT_NAME);
        final JSONObject commenter = userRepository.getByUserName(commenterName);
        final String fallback = Latkes.getStaticServePath() + "/images/volo-mark.svg";
        final String avatarURL = commenter == null ? fallback : StringUtils.defaultIfBlank(commenter.optString(UserExt.USER_AVATAR), fallback);
        comment.put(Comment.COMMENT_THUMBNAIL_URL, avatarURL);
    }

    public void syncAllArticleCommentFromFishPI() {
        try {
            LOGGER.log(Level.INFO, "Sync comment from fishpi start");
            final JSONObject fishPiArticleRef = optionQueryService.getOptions("fishPiArticleRef");
            if (fishPiArticleRef == null) {
                LOGGER.log(Level.WARN, "fishPiArticleRef is null, sync aborted.");
                return;
            }
            final Map<String, String> fishPiArticleRefMap = new HashMap<>();
            final Iterator<String> keys = fishPiArticleRef.keys();
            while (keys.hasNext()) {
                final String key = keys.next();
                fishPiArticleRefMap.put(key, fishPiArticleRef.optString(key));
            }
            for (final Map.Entry<String, String> entry : fishPiArticleRefMap.entrySet()) {
                final String[] parts = entry.getKey().split("_", 2);
                if (parts.length < 2 || parts[1].isBlank() || StringUtils.isBlank(entry.getValue())) {
                    continue;
                }
                final long localId;
                final long remoteId;
                try {
                    localId = Long.parseLong(parts[1]);
                    remoteId = Long.parseLong(entry.getValue().trim());
                } catch (final NumberFormatException ignored) {
                    continue;
                }
                LOGGER.log(Level.DEBUG, String.format("===> Article: %s , PAGE 1 <===", localId));
                final int pageCount = syncCommentFromFishPI(localId, remoteId, 1);
                if (pageCount < 0) {
                    LOGGER.log(Level.WARN, "评论同步失败，无法连接到摸鱼派服务器或 apiKey 错误。");
                } else if (pageCount == 0) {
                    LOGGER.log(Level.WARN, "远端文章评论为空");
                } else if (pageCount > 1) {
                    final int cappedPages = Math.min(pageCount, 1000);
                    for (int i = 2; i <= cappedPages; i++) {
                        LOGGER.log(Level.DEBUG, String.format("===> Article: %s , PAGE %s <===", localId, i));
                        if (syncCommentFromFishPI(localId, remoteId, i) < 0) {
                            break;
                        }
                    }
                }
            }
            LOGGER.log(Level.INFO, "Sync comment from fishpi success");
        } catch (final Throwable e) {
            LOGGER.log(Level.ERROR, "Sync comment from fishpi failed", e);
        }
    }

    public int syncCommentFromFishPI(final long localaid, final long remoteaid, final int page) {
        final String articleId = String.valueOf(localaid);
        final JSONObject article = articleQueryService.getArticleById(articleId);
        if (null == article) {
            return -1;
        }
        final String fetchURL = "https://" + Global.FISH_PI_DOMAIN + "/api/article/" + remoteaid;
        final Map<String, String> query = new HashMap<>();
        query.put("p", Integer.toString(page));
        query.put("apiKey", StringUtils.defaultString(userQueryService.getFishKey()));
        final JSONObject result;
        try {
            final HttpResponse res = HttpRequest.get(fetchURL).query(query).connectionTimeout(3000).timeout(7000)
                    .header("User-Agent", Solos.USER_AGENT).send();
            if (200 != res.statusCode()) {
                return -1;
            }
            res.charset("UTF-8");
            result = new JSONObject(res.bodyText());
        } catch (final Exception e) {
            LOGGER.log(Level.WARN, "Fetch comment from fishpi failed", e);
            return -1;
        }

        final JSONObject data = result.optJSONObject("data");
        if (null == data) {
            return -1;
        }
        final JSONObject remoteArticle = data.optJSONObject("article");
        if (null == remoteArticle) {
            return -1;
        }
        final JSONArray rslt = remoteArticle.optJSONArray("articleComments");
        if (null == rslt) {
            return -1;
        }
        if (0 == rslt.length()) {
            return 0;
        }
        final JSONObject pagination = data.optJSONObject("pagination");
        final int pageCount = null == pagination ? 1 : Math.max(0, pagination.optInt("paginationPageCount", 1));

        final String canonicalArticleId = article.optString(Keys.OBJECT_ID, articleId);
        final Transaction tx = commentRepository.beginTransaction();
        boolean changed = false;
        try {
            final HashMap<String, String> idNameMapping = new HashMap<>();
            for (int i = 0; i < rslt.length(); i++) {
                final JSONObject object = rslt.optJSONObject(i);
                if (null == object) {
                    continue;
                }
                final String id = object.optString("oId").trim();
                if (id.isEmpty()) {
                    continue;
                }
                final String author = object.optString("commentAuthorName");
                idNameMapping.put(id, author);
                if (null != commentRepository.get(id)) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.log(Level.DEBUG, "Comment import skipped [id={0}]", id);
                    }
                    continue;
                }
                final long createdMillis;
                try {
                    createdMillis = Long.parseLong(id);
                } catch (final NumberFormatException ignored) {
                    continue;
                }
                final String link = String.format("https://%s/member/%s", Global.FISH_PI_DOMAIN, author);
                final String avatar = object.optString("commentAuthorThumbnailURL");
                final String comment = object.optString("commentContent");
                final String commentOriginalCommentId = object.optString(Comment.COMMENT_ORIGINAL_COMMENT_ID);
                final JSONObject commentObject = new JSONObject();
                commentObject.put(Comment.COMMENT_ORIGINAL_COMMENT_ID, commentOriginalCommentId);
                commentObject.put(Comment.COMMENT_ORIGINAL_COMMENT_NAME,
                        idNameMapping.getOrDefault(commentOriginalCommentId, ""));
                commentObject.put(Comment.COMMENT_NAME, author);
                commentObject.put(Comment.COMMENT_URL, link);
                commentObject.put(Comment.COMMENT_CONTENT, comment);
                commentObject.put(Comment.COMMENT_CREATED, createdMillis);
                commentObject.put(Comment.COMMENT_THUMBNAIL_URL, avatar);
                commentObject.put(Comment.COMMENT_ON_ID, canonicalArticleId);
                commentObject.put(Comment.COMMENT_STATUS, Comment.COMMENT_STATUS_C_APPROVED);
                commentObject.put("commentDeleted", false);
                commentObject.put(Keys.OBJECT_ID, id);
                final long date = article.optLong(Article.ARTICLE_CREATED);
                commentObject.put(Comment.COMMENT_SHARP_URL, "/articles/" + DateFormatUtils.format(date, "yyyy/MM/dd")
                        + "/" + canonicalArticleId + ".html#" + createdMillis);
                commentRepository.add(commentObject);
                changed = true;
            }
            if (changed) {
                setArticleCommentCount(canonicalArticleId,
                        commentRepository.getApprovedArticleCommentCount(canonicalArticleId));
            }
            tx.commit();
        } catch (final Exception e) {
            if (tx.isActive()) {
                tx.rollback();
            }
            LOGGER.log(Level.ERROR, "Save synced fishpi comments failed", e);
            return -1;
        }
        if (changed) {
            invalidateCommentAndPublicCaches(canonicalArticleId);
        }

        return pageCount;
    }
}
