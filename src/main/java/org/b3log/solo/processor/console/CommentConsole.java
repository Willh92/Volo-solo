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
package org.b3log.solo.processor.console;

import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.ioc.Inject;
import org.apache.logging.log4j.Level;
import org.b3log.solo.logging.Logger;
import org.b3log.latke.service.LangPropsService;
import org.b3log.latke.http.RequestContext;
import org.b3log.solo.web.annotation.Before;
import org.b3log.latke.ioc.Singleton;
import org.b3log.latke.http.renderer.JsonRenderer;
import org.b3log.solo.model.Comment;
import org.b3log.solo.service.ArticleQueryService;
import org.b3log.solo.service.CommentMgmtService;
import org.b3log.solo.service.CommentQueryService;
import org.b3log.solo.util.Solos;
import org.json.JSONObject;

import java.util.List;

/**
 * Comment console request processing.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 0.4.0
 */
@Singleton
@Before(ConsoleAuthAdvice.class)
public class CommentConsole {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(CommentConsole.class);

    /**
     * Comment query service.
     */
    @Inject
    private CommentQueryService commentQueryService;

    /**
     * Comment management service.
     */
    @Inject
    private CommentMgmtService commentMgmtService;

    /**
     * Language service.
     */
    @Inject
    private LangPropsService langPropsService;

    @Inject
    private org.b3log.solo.service.OptionQueryService optionQueryService;

    @Inject
    private ArticleQueryService articleQueryService;

    /**
     * Removes a comment of an article by the specified request.
     *
     * @param context the specified request context
     */
    public void removeArticleComment(final RequestContext context) {
        context.setHeader("Cache-Control", "no-store");
        if (!Solos.isAdminOrAuthorLoggedIn(context)) { org.b3log.solo.util.AdminRequests.reject(context, 401, "请先登录管理后台。"); return; }
        if (!org.b3log.solo.util.AdminRequests.verifySameOriginWrite(context)) return;
        final JsonRenderer renderer = new JsonRenderer();
        context.setRenderer(renderer);
        final JSONObject ret = new JSONObject();
        renderer.setJSONObject(ret);

        try {
            final String commentId = context.pathVar("id");
            final JSONObject currentUser = Solos.getCurrentUser(context.getRequest(), context.getResponse());
            if (!commentQueryService.canAccessComment(commentId, currentUser)) {
                ret.put(org.b3log.solo.model.Common.STATUS_CODE, false);
                ret.put(Keys.MSG, langPropsService.get("forbiddenLabel"));

                return;
            }

            if ("thread".equals(context.param("scope"))) {
                if (!Solos.isAdminLoggedIn(context)) { org.b3log.solo.util.AdminRequests.reject(context, 403, "仅管理员可以删除整个讨论。"); return; }
                commentMgmtService.removeArticleCommentThread(commentId);
            } else commentMgmtService.removeArticleComment(commentId);

            ret.put(org.b3log.solo.model.Common.STATUS_CODE, true);
            ret.put(Keys.MSG, langPropsService.get("removeSuccLabel"));
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, e.getMessage(), e);

            ret.put(org.b3log.solo.model.Common.STATUS_CODE, false);
            ret.put(Keys.MSG, langPropsService.get("removeFailLabel"));
        }
    }

    /**
     * Gets comments by the specified request.
     *
     * @param context the specified request context
     */
    public void getComments(final RequestContext context) {
        final JsonRenderer renderer = new JsonRenderer();
        context.setRenderer(renderer);

        try {
            final String requestURI = context.requestURI();
            final String path = requestURI.substring((Latkes.getContextPath() + "/console/comments/").length());

            final JSONObject requestJSONObject = Solos.buildPaginationRequest(path);
            requestJSONObject.put(org.b3log.solo.model.Common.KEYWORD, context.param("k") == null ? "" : context.param("k"));
            requestJSONObject.put("status", context.param("status") == null ? "all" : context.param("status"));
            final boolean adminLoggedIn = Solos.isAdminLoggedIn(context);
            final JSONObject result = commentQueryService.getComments(requestJSONObject, adminLoggedIn);
            final java.util.Map<String, String> emails = new java.util.HashMap<>();
            if (adminLoggedIn) {
                for (final var binding : org.b3log.solo.bolo.prop.MailService.getUserMailContext()) {
                    emails.put(binding.getCommentId(), binding.getCommentEmail());
                }
            }
            final var comments = result.optJSONArray(Comment.COMMENTS);
            if (comments != null) {
                for (int i = 0; i < comments.length(); i++) {
                    final JSONObject comment = comments.getJSONObject(i);
                    final String currentEmail = comment.optString("commentNotifyEmail");
                    comment.remove("commentNotifyEmail");
                    comment.remove("commentGuestKeyHash");
                    if (adminLoggedIn) {
                        final String email = currentEmail.isBlank() ? emails.getOrDefault(comment.optString(Keys.OBJECT_ID), "") : currentEmail;
                        if (!email.isBlank()) comment.put("commentEmail", email);
                    } else {
                        comment.remove("commentEmail");
                        comment.remove(org.b3log.solo.util.IpAddresses.COMMENT_IP);
                    }
                }
            }

            result.put("canModerate", adminLoggedIn);
            result.put(org.b3log.solo.model.Common.STATUS_CODE, true);

            renderer.setJSONObject(result);
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, e.getMessage(), e);

            final JSONObject jsonObject = new JSONObject().put(org.b3log.solo.model.Common.STATUS_CODE, false);
            renderer.setJSONObject(jsonObject);
            jsonObject.put(Keys.MSG, langPropsService.get("getFailLabel"));
        }
    }

    /**
     * Moderates a comment status (approve / reject).
     *
     * @param context the specified request context
     */
    public void moderateComment(final RequestContext context) {
        context.setHeader("Cache-Control", "no-store");
        if (!Solos.isAdminOrAuthorLoggedIn(context)) {
            org.b3log.solo.util.AdminRequests.reject(context, 401, "请先登录管理后台。");
            return;
        }
        if (!Solos.isAdminLoggedIn(context)) {
            org.b3log.solo.util.AdminRequests.reject(context, 403, "仅管理员可以审核评论。");
            return;
        }
        if (!org.b3log.solo.util.AdminRequests.verifySameOriginWrite(context)) {
            return;
        }
        try {
            final String commentId = context.pathVar("id");
            final JSONObject currentUser = Solos.getCurrentUser(context.getRequest(), context.getResponse());
            if (!commentQueryService.canAccessComment(commentId, currentUser)) {
                org.b3log.solo.util.AdminRequests.reject(context, 403, langPropsService.get("forbiddenLabel"));
                return;
            }
            final JSONObject payload = context.requestJSON();
            if (payload == null || !payload.has("status")) {
                org.b3log.solo.util.AdminRequests.reject(context, 400, "缺少审核状态参数。");
                return;
            }
            final int targetStatus = payload.optInt("status", -1);
            if (targetStatus != Comment.COMMENT_STATUS_C_APPROVED && targetStatus != Comment.COMMENT_STATUS_C_REJECTED) {
                org.b3log.solo.util.AdminRequests.reject(context, 400, "不支持的审核状态。");
                return;
            }
            final JSONObject modResult = commentMgmtService.moderateComment(commentId, targetStatus);
            final int previousStatus = modResult.optInt("previousStatus", Comment.COMMENT_STATUS_C_APPROVED);
            final JSONObject comment = modResult.optJSONObject("comment");
            if (previousStatus == Comment.COMMENT_STATUS_C_PENDING
                    && targetStatus == Comment.COMMENT_STATUS_C_APPROVED
                    && comment != null) {
                final String parentId = comment.optString(Comment.COMMENT_ORIGINAL_COMMENT_ID);
                if (!parentId.isBlank()) {
                    try {
                        final JSONObject pref = optionQueryService.getPreference();
                        final String blogTitle = pref == null ? "Volo" : pref.optString(org.b3log.solo.model.Option.ID_C_BLOG_TITLE, "Volo");
                        org.b3log.solo.bolo.prop.CommentMailService.remindCommentedGuy(
                                parentId, Latkes.getServePath(), comment.optString(Comment.COMMENT_NAME), blogTitle);
                    } catch (final Exception ignored) {
                    }
                }
            }
            context.renderJSON(new JSONObject()
                    .put(org.b3log.solo.model.Common.STATUS_CODE, true)
                    .put("status", targetStatus)
                    .put(Keys.MSG, targetStatus == Comment.COMMENT_STATUS_C_APPROVED ? "已通过审核并公开显示。" : "已驳回该条评论。"));
        } catch (final Exception e) {
            LOGGER.log(Level.WARN, "Moderates comment failed: " + e.getMessage());
            org.b3log.solo.util.AdminRequests.reject(context, 400, e.getMessage() == null ? "审核操作失败。" : e.getMessage());
        }
    }

    public void getArticleComments(final RequestContext context) {
        final JsonRenderer renderer = new JsonRenderer();
        context.setRenderer(renderer);
        final JSONObject ret = new JSONObject();
        renderer.setJSONObject(ret);

        try {
            final String articleId = context.pathVar("id");
            final JSONObject currentUser = Solos.getCurrentUser(context.getRequest(), context.getResponse());
            if (articleQueryService != null && !articleQueryService.canAccessArticle(articleId, currentUser)) {
                ret.put(org.b3log.solo.model.Common.STATUS_CODE, false);
                ret.put(Keys.MSG, langPropsService.get("forbiddenLabel"));
                return;
            }
            final boolean adminLoggedIn = Solos.isAdminLoggedIn(context);
            final List<JSONObject> comments = commentQueryService.getComments(articleId);
            for (final JSONObject comment : comments) {
                comment.remove("commentNotifyEmail");
                comment.remove("commentGuestKeyHash");
                if (!adminLoggedIn) {
                    comment.remove("commentEmail");
                    comment.remove(org.b3log.solo.util.IpAddresses.COMMENT_IP);
                }
            }

            ret.put(Comment.COMMENTS, comments);
            ret.put(org.b3log.solo.model.Common.STATUS_CODE, true);
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, e.getMessage(), e);

            final JSONObject jsonObject = new JSONObject().put(org.b3log.solo.model.Common.STATUS_CODE, false);
            renderer.setJSONObject(jsonObject);
            jsonObject.put(Keys.MSG, langPropsService.get("getFailLabel"));
        }
    }
}
