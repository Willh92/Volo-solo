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

    /**
     * Removes a comment of an article by the specified request.
     * <p>
     * Renders the response with a json object, for example,
     * <pre>
     * {
     *     "sc": boolean,
     *     "msg": ""
     * }
     * </pre>
     * </p>
     *
     * @param context the specified request context
     */
    public void removeArticleComment(final RequestContext context) {
        if (!org.b3log.solo.util.AdminRequests.allowWrite(context)) return;
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
     * <p>
     * The request URI contains the pagination arguments. For example, the
     * request URI is /console/comments/1/10/20, means the current page is 1, the
     * page size is 10, and the window size is 20.
     * </p>
     * <p>
     * Renders the response with a json object, for example,
     * <pre>
     * {
     *     "sc": boolean,
     *     "pagination": {
     *         "paginationPageCount": 100,
     *         "paginationPageNums": [1, 2, 3, 4, 5]
     *     },
     *     "comments": [{
     *         "oId": "",
     *         "commentTitle": "",
     *         "commentName": "",
     *         "thumbnailUrl": "",
     *         "commentURL": "",
     *         "commentContent": "",
     *         "commentTime": long,
     *         "commentSharpURL": ""
     *      }, ....]
     * }
     * </pre>
     * </p>
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
            final JSONObject result = commentQueryService.getComments(requestJSONObject, Solos.isAdminLoggedIn(context));
            if (Solos.isAdminLoggedIn(context)) {
                final java.util.Map<String, String> emails = new java.util.HashMap<>();
                for (final var binding : org.b3log.solo.bolo.prop.MailService.getUserMailContext()) {
                    emails.put(binding.getCommentId(), binding.getCommentEmail());
                }
                final var comments = result.optJSONArray(Comment.COMMENTS);
                if (comments != null) for (int i = 0; i < comments.length(); i++) {
                    final JSONObject comment = comments.getJSONObject(i);
                    final String currentEmail = comment.optString("commentNotifyEmail");
                    final String email = currentEmail.isBlank() ? emails.getOrDefault(comment.optString(Keys.OBJECT_ID), "") : currentEmail;
                    comment.remove("commentNotifyEmail");
                    if (!email.isBlank()) comment.put("commentEmail", email);
                }
            }

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
     * Gets comments of an article specified by the article id for administrator.
     * <p>
     * Renders the response with a json object, for example,
     * <pre>
     * {
     *     "sc": boolean,
     *     "comments": [{
     *         "oId": "",
     *         "commentName": "",
     *         "thumbnailUrl": "",
     *         "commentURL": "",
     *         "commentContent": "",
     *         "commentTime": long,
     *         "commentSharpURL": "",
     *         "isReply": boolean
     *      }, ....]
     * }
     * </pre>
     * </p>
     *
     * @param context the specified request context
     */
    public void getArticleComments(final RequestContext context) {
        final JsonRenderer renderer = new JsonRenderer();
        context.setRenderer(renderer);
        final JSONObject ret = new JSONObject();
        renderer.setJSONObject(ret);

        try {
            final String articleId = context.pathVar("id");
            final List<JSONObject> comments = commentQueryService.getComments(articleId);

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
