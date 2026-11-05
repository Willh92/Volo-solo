/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.processor;

import org.b3log.latke.Keys;
import org.b3log.latke.http.HttpMethod;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.ioc.Singleton;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Comment;
import org.b3log.solo.model.Option;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.repository.CommentRepository;
import org.b3log.solo.service.CommentMgmtService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.util.AdminRequests;
import org.b3log.solo.util.CommentOwnership;
import org.b3log.solo.util.LoginThrottle;
import org.b3log.solo.util.Markdowns;
import org.b3log.solo.util.Solos;
import org.b3log.solo.web.annotation.RequestProcessing;
import org.json.JSONArray;
import org.json.JSONObject;

/** Comment edits do not grant a visitor a login session or access to other private data. */
@Singleton
public class CommentEditProcessor {
    private final org.b3log.solo.util.CommentThrottle edits = new org.b3log.solo.util.CommentThrottle(System::nanoTime,
            new org.b3log.solo.util.CommentThrottle.Limits(1, 60, 30, 100, 500));
    @Inject private CommentRepository commentRepository;
    @Inject private ArticleRepository articleRepository;
    @Inject private CommentMgmtService commentMgmtService;
    @Inject private org.b3log.solo.service.CommentFilterService commentFilterService;
    @Inject private OptionQueryService optionQueryService;

    private static boolean ownsByAccount(final JSONObject currentUser, final JSONObject comment) {
        if (currentUser == null || comment == null || comment.optBoolean("commentDeleted")) {
            return false;
        }
        final String ownerId = comment.optString(Comment.COMMENT_USER_ID);
        return !ownerId.isBlank() && ownerId.equals(currentUser.optString(Keys.OBJECT_ID));
    }

    private boolean canNonAdminEditByAccessMode(final JSONObject currentUser) {
        final JSONObject preference = optionQueryService == null ? null : optionQueryService.getPreference();
        if (preference == null) {
            return true;
        }
        final String accessMode = Option.effectiveCommentAccessMode(preference);
        if ("off".equals(accessMode)) {
            return false;
        }
        return !"registered".equals(accessMode) || currentUser != null;
    }

    private boolean owns(final RequestContext context, final JSONObject comment, final boolean allowWhenCommentsClosed) throws Exception {
        if (Solos.isAdminLoggedIn(context)) return true;
        if (comment == null) return false;
        if (comment.optInt(Comment.COMMENT_STATUS, Comment.COMMENT_STATUS_C_APPROVED) != Comment.COMMENT_STATUS_C_APPROVED) {
            return false;
        }
        final JSONObject article = articleRepository.get(comment.optString(Comment.COMMENT_ON_ID));
        if (article == null || article.optInt(Article.ARTICLE_STATUS) != Article.ARTICLE_STATUS_C_PUBLISHED
                || (!allowWhenCommentsClosed && !article.optBoolean(Article.ARTICLE_COMMENTABLE))) {
            return false;
        }
        final JSONObject currentUser = Solos.getCurrentUser(context.getRequest(), context.getResponse());
        if (!allowWhenCommentsClosed && !canNonAdminEditByAccessMode(currentUser)) {
            return false;
        }
        return ownsByAccount(currentUser, comment)
                || CommentOwnership.owns(comment, LoginThrottle.clientAddress(context.getRequest()), context.header("X-Volo-Visitor-Key"));
    }

    @RequestProcessing(value = "/article/comment-permissions", method = HttpMethod.GET)
    public void permissions(final RequestContext context) {
        context.setHeader("Cache-Control", "no-store");
        try {
            final String articleId = context.param("articleId");
            if (articleId == null || articleId.length() > 64) { AdminRequests.reject(context, 400, "文章编号不正确。"); return; }
            final JSONArray ids = new JSONArray();
            final JSONArray moderation = new JSONArray();
            final boolean administrator = Solos.isAdminLoggedIn(context);
            final JSONObject currentUser = Solos.getCurrentUser(context.getRequest(), context.getResponse());
            final String address = LoginThrottle.clientAddress(context.getRequest()), key = context.header("X-Volo-Visitor-Key");
            if (!administrator) {
                final JSONObject article = articleRepository.get(articleId);
                if ((currentUser == null && CommentOwnership.hash(key).isEmpty()) || article == null || !article.optBoolean(Article.ARTICLE_COMMENTABLE)
                        || article.optInt(Article.ARTICLE_STATUS) != Article.ARTICLE_STATUS_C_PUBLISHED
                        || !canNonAdminEditByAccessMode(currentUser)) {
                    context.renderJSON(new JSONObject().put("sc", true).put("editable", ids)); return;
                }
            }
            for (final JSONObject comment : commentRepository.getComments(articleId, 1, Integer.MAX_VALUE)) {
                if (comment.optInt(Comment.COMMENT_STATUS, Comment.COMMENT_STATUS_C_APPROVED) != Comment.COMMENT_STATUS_C_APPROVED && !administrator) {
                    continue;
                }
                if (!comment.optBoolean("commentDeleted") && (administrator || ownsByAccount(currentUser, comment) || CommentOwnership.owns(comment, address, key))) ids.put(comment.optString(Keys.OBJECT_ID));
                if (administrator && !comment.optBoolean("commentDeleted")) {
                    final String source = org.b3log.solo.util.IpAddresses.canonical(comment.optString(org.b3log.solo.util.IpAddresses.COMMENT_IP));
                    if (!source.isEmpty()) moderation.put(new JSONObject().put("id", comment.optString(Keys.OBJECT_ID))
                            .put("address", source).put("region", org.b3log.solo.util.IpGeo.region(source)));
                }
            }
            final JSONObject result = new JSONObject().put("sc", true).put("editable", ids);
            if (administrator) result.put("moderation", moderation);
            context.renderJSON(result);
        } catch (Exception failure) { AdminRequests.reject(context, 503, "评论权限暂时无法读取。"); }
    }

    @RequestProcessing(value = "/article/comments/{id}", method = HttpMethod.GET)
    public void read(final RequestContext context) {
        context.setHeader("Cache-Control", "no-store");
        try {
            final JSONObject comment = commentRepository.get(context.pathVar("id"));
            if (comment == null || !owns(context, comment, false)) { AdminRequests.reject(context, 403, "当前身份或 IP 无法编辑这条评论。"); return; }
            if (comment.optBoolean("commentDeleted")) { AdminRequests.reject(context, 410, "这条评论已删除。"); return; }
            context.renderJSON(new JSONObject().put("sc", true).put("commentContent", comment.optString(Comment.COMMENT_CONTENT)));
        } catch (Exception failure) { AdminRequests.reject(context, 503, "评论暂时无法读取。"); }
    }

    @RequestProcessing(value = "/article/comments/{id}", method = HttpMethod.PUT)
    public void edit(final RequestContext context) { mutate(context, false); }

    @RequestProcessing(value = "/article/comments/{id}", method = HttpMethod.DELETE)
    public void delete(final RequestContext context) { mutate(context, true); }

    private void mutate(final RequestContext context, final boolean delete) {
        context.setHeader("Cache-Control", "no-store");
        final boolean adminLoggedIn = Solos.isAdminLoggedIn(context);
        if (!adminLoggedIn && !CommentProcessor.sameOrigin(context)) return;
        if (adminLoggedIn && !AdminRequests.allowWrite(context)) return;
        try {
            final JSONObject comment = commentRepository.get(context.pathVar("id"));
            if (comment == null || !owns(context, comment, delete)) { AdminRequests.reject(context, 403, "当前身份或 IP 无法修改这条评论。"); return; }
            if (!adminLoggedIn) {
                final var attempt = edits.acquire(LoginThrottle.clientAddress(context.getRequest()), comment.optString(Comment.COMMENT_ON_ID),
                        context.method() + context.pathVar("id") + (delete ? "" : context.requestJSON().optString("commentContent")));
                if (!attempt.allowed()) { context.setHeader("Retry-After", String.valueOf(attempt.retryAfter())); AdminRequests.reject(context, 429, "修改操作过于频繁，请稍后再试。"); return; }
            }
            if (delete) {
                if ("thread".equals(context.param("scope"))) {
                    if (!adminLoggedIn) { AdminRequests.reject(context, 403, "游客不能删除其他人的回复。"); return; }
                    commentMgmtService.removeArticleCommentThread(context.pathVar("id"));
                } else commentMgmtService.removeArticleComment(context.pathVar("id"));
                context.renderJSON(new JSONObject().put("sc", true));
            } else {
                final JSONObject payload = context.requestJSON();
                if (org.b3log.solo.service.RiskControlService.rejectCommentXss(context, payload)) return;
                if (!CommentProcessor.checkCommentCaptcha(context, payload, adminLoggedIn)) return;
                final String content = payload.optString("commentContent");
                if (content.isBlank() || content.length() < 2 || content.length() > 500) { AdminRequests.reject(context, 400, "评论内容需要 2 到 500 个字符。"); return; }
                if (!adminLoggedIn && commentFilterService.blocked(content)) {
                    AdminRequests.reject(context, 400, org.b3log.solo.service.CommentFilterService.REJECTION);
                    return;
                }
                if (!adminLoggedIn) {
                    final JSONObject preference = optionQueryService == null ? null : optionQueryService.getPreference();
                    final JSONObject currentUser = Solos.getCurrentUser(context.getRequest(), context.getResponse());
                    final String userId = currentUser == null ? comment.optString(Comment.COMMENT_USER_ID) : currentUser.optString(Keys.OBJECT_ID);
                    final int nextStatus = commentMgmtService.resolveInitialStatus(preference, false, userId, content);
                    if (nextStatus == Comment.COMMENT_STATUS_C_PENDING) {
                        commentMgmtService.updateCommentContentAndStatus(context.pathVar("id"), content, Comment.COMMENT_STATUS_C_PENDING);
                        context.renderJSON(new JSONObject()
                                .put("sc", true)
                                .put("pending", true)
                                .put("msg", "修改后的评论已提交审核，待站长通过后展示。"));
                        return;
                    }
                }
                commentMgmtService.updateCommentContent(context.pathVar("id"), content);
                context.renderJSON(new JSONObject().put("sc", true).put("commentHTML", Markdowns.clean(Markdowns.toHTML(content))));
            }
        } catch (org.json.JSONException invalid) { AdminRequests.reject(context, 400, "评论请求格式不正确。"); }
        catch (Exception failure) { AdminRequests.reject(context, 503, "评论保存失败，请重试。"); }
    }
}
