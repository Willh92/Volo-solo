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
package org.b3log.solo.processor;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.b3log.latke.http.Response;

import org.apache.commons.lang3.time.DateFormatUtils;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.ioc.Inject;
import org.apache.logging.log4j.Level;
import org.b3log.solo.logging.Logger;
import org.b3log.latke.model.User;
import org.b3log.latke.repository.CompositeFilterOperator;
import org.b3log.latke.repository.FilterOperator;
import org.b3log.latke.repository.PropertyFilter;
import org.b3log.latke.repository.Query;
import org.b3log.latke.repository.RepositoryException;
import org.b3log.latke.repository.SortDirection;
import org.b3log.latke.repository.Transaction;
import org.b3log.latke.service.LangPropsService;
import org.b3log.latke.http.HttpMethod;
import org.b3log.latke.http.RequestContext;
import org.b3log.solo.web.annotation.RequestProcessing;
import org.b3log.latke.ioc.Singleton;
import org.b3log.latke.http.renderer.JsonRenderer;
import org.b3log.solo.bolo.Global;
import org.b3log.solo.bolo.prop.CommentMailService;
import org.b3log.solo.bolo.prop.MailService;
import org.b3log.solo.bolo.prop.Options;
import org.b3log.solo.bolo.prop.ServerJiangService;
import org.b3log.solo.bolo.tool.AntiXSS;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Comment;
import org.b3log.solo.model.Common;
import org.b3log.solo.model.Option;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.repository.CommentRepository;
import org.b3log.solo.repository.OptionRepository;
import org.b3log.solo.repository.UserRepository;
import org.b3log.solo.service.ArticleMgmtService;
import org.b3log.solo.service.ArticleQueryService;
import org.b3log.solo.service.CommentMgmtService;
import org.b3log.solo.service.CommentQueryService;
import org.b3log.solo.service.OptionMgmtService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.service.UserMgmtService;
import org.b3log.solo.service.UserQueryService;
import org.b3log.solo.util.Skins;
import org.b3log.solo.util.AdminRequests;
import org.b3log.solo.util.Solos;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import freemarker.template.Template;
import jodd.http.Cookie;
import jodd.http.HttpRequest;
import jodd.http.HttpResponse;
import org.b3log.solo.util.CommentThrottle;
import org.b3log.solo.util.LoginThrottle;

/**
 * Comment processor.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @author <a href="https://ld246.com/member/armstrong">ArmstrongCN</a>
 * @since 0.3.1
 */
@Singleton
public class CommentProcessor {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(CommentProcessor.class);
    private final CommentThrottle commentThrottle = new CommentThrottle();
    /**
     * Language service.
     */
    @Inject
    private LangPropsService langPropsService;
    /**
     * Comment management service.
     */
    @Inject
    private CommentMgmtService commentMgmtService;
    @Inject
    private org.b3log.solo.service.CommentFilterService commentFilterService;
    /**
     * User query service.
     */
    @Inject
    private UserQueryService userQueryService;
    /**
     * User management service.
     */
    @Inject
    private UserMgmtService userMgmtService;
    /**
     * Option query service.
     */
    @Inject
    private OptionQueryService optionQueryService;

    @Inject
    private OptionMgmtService optionMgmtService;
    /**
     * User repository.
     */
    @Inject
    private UserRepository userRepository;

    /**
     * Option repository.
     */
    @Inject
    private OptionRepository optionRepository;

    /**
     * Article query service.
     */
    @Inject
    private ArticleQueryService articleQueryService;

    /**
     * Comment repository.
     */
    @Inject
    private CommentRepository commentRepository;

    /**
     * Comment service.
     */
    @Inject
    private CommentQueryService commentQueryService;

    /**
     * Article management service.
     */
    @Inject
    private ArticleMgmtService articleMgmtService;

    /**
     * Article repository.
     */
    @Inject
    private ArticleRepository articleRepository;

    /**
     * Adds a comment to an article.
     *
     * <p>
     * Request json:
     * 
     * <pre>
     * {
     *     "captcha": "",
     *     "oId": articleId,
     *     "commentName": "",
     *     "commentURL": "",
     *     "commentContent": "",
     *     "commentOriginalCommentId": "" // optional, if exists this key, the comment is an reply
     * }
     * </pre>
     * </p>
     * <p>
     * Renders the response with a json object, for example,
     * 
     * <pre>
     * {
     *     "oId": generatedCommentId,
     *     "sc": "COMMENT_ARTICLE_SUCC",
     *     "commentDate": "", // yyyy/MM/dd HH:mm:ss
     *     "commentSharpURL": "",
     *     "commentThumbnailURL": "",
     *     "commentOriginalCommentName": "", // if exists this key, the comment is an reply
     *     "commentContent": ""
     * }
     * </pre>
     * </p>
     *
     * @param context the specified context, including a request json object
     */
    public static boolean sameOrigin(final RequestContext context) {
        final java.net.URI publicUrl = java.net.URI.create(Latkes.getServePath());
        final String expectedOrigin = publicUrl.getScheme() + "://" + publicUrl.getRawAuthority();
        if ("cross-site".equals(context.header("Sec-Fetch-Site"))
                || (context.header("Origin") != null && !expectedOrigin.equals(context.header("Origin")))) {
            AdminRequests.reject(context, 403, "请从本站文章页面提交评论。");
            return false;
        }
        return true;
    }

    @RequestProcessing(value = "/article/comments", method = HttpMethod.POST)
    public void addArticleComment(final RequestContext context) {
        if (!sameOrigin(context)) return;
        final String contentType = context.header("Content-Type");
        if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).split(";", 2)[0].trim().equals("application/json")) {
            AdminRequests.reject(context, 415, "评论请求需要使用 JSON 格式。");
            return;
        }
        // 为 false 时不发送提醒邮件至管理员邮箱
        boolean sendEmailToAdmin = true;

        final JSONObject requestJSONObject;
        try {
            requestJSONObject = context.requestJSON();
            if (org.b3log.solo.service.RiskControlService.rejectCommentXss(context, requestJSONObject)) return;
            if (requestJSONObject == null || requestJSONObject.optString("oId").isBlank()
                    || requestJSONObject.optString("oId").length() > 64
                    || requestJSONObject.optString("commentContent").isBlank()
                    || requestJSONObject.optString("commentContent").length() < 2
                    || requestJSONObject.optString("commentContent").length() > 500
                    || requestJSONObject.optString("boloUser").length() > 80) {
                AdminRequests.reject(context, 400, "请填写有效的昵称和 2 到 500 个字符的评论。");
                return;
            }
        } catch (RuntimeException invalid) {
            AdminRequests.reject(context, 400, "评论请求格式不正确。");
            return;
        }
        requestJSONObject.put(Common.TYPE, Article.ARTICLE);

        final JSONObject currentUser = Solos.getCurrentUser(context.getRequest(), context.getResponse());
        final boolean adminLoggedIn = Solos.isAdminLoggedIn(context);
        if (!checkCommentCaptcha(context, requestJSONObject, adminLoggedIn)) return;
        if (!adminLoggedIn && "off".equals(optionQueryService.getPreference().optString(Option.ID_C_INTERACTIVE, "on"))) {
            AdminRequests.reject(context, 403, "本站暂未开放评论。");
            return;
        }
        String site = currentUser == null ? requestJSONObject.optString("boloSite") : currentUser.optString(User.USER_URL);
        // XSS 处理
        site = AntiXSS.getSafeStringXSS(site.trim());
        if (site.length() > 255 || (!site.isEmpty() && !site.matches("(?i)^https?://.+"))) {
            AdminRequests.reject(context, 400, "主页请填写不超过 255 个字符的 HTTP 或 HTTPS 网址，或留空。");
            return;
        }
        if (site.isEmpty()) {
            site = Latkes.getServePath();
        }
        requestJSONObject.put(Comment.COMMENT_URL, site);

        String username = currentUser == null ? requestJSONObject.optString("boloUser") : currentUser.optString(User.USER_NAME);
        if (org.b3log.solo.util.CommentNames.hasMarkup(username)) {
            AdminRequests.reject(context, 400, "昵称不能包含 HTML 标签。");
            return;
        }
        // XSS 处理
        username = AntiXSS.getSafeStringXSS(username);
        // 替换空格
        username = username.replaceAll(" ", "_");
        if (username.isEmpty()) {
            username = currentUser == null ? "" : currentUser.optString(User.USER_NAME);

            if (username.isEmpty()) {
                AdminRequests.reject(context, 400, "请填写昵称后再留言。");
                return;
            }
        }
        requestJSONObject.put(Comment.COMMENT_NAME, username);

        final String email = currentUser == null ? requestJSONObject.optString("email").trim() : "";
        if (email.length() > 254 || (!email.isEmpty() && !email.matches("[^\\s@;:]+@[^\\s@;:]+\\.[^\\s@;:]+"))) {
            AdminRequests.reject(context, 400, "请填写有效的通知邮箱，或留空。");
            return;
        }
        requestJSONObject.put("email", email);
        requestJSONObject.put(org.b3log.solo.util.CommentOwnership.KEY_HASH,
                adminLoggedIn ? "" : org.b3log.solo.util.CommentOwnership.hash(requestJSONObject.optString("visitorKey")));
        requestJSONObject.put(org.b3log.solo.util.IpAddresses.COMMENT_IP,
                adminLoggedIn ? "" : LoginThrottle.clientAddress(context.getRequest()));
        final CommentThrottle.Attempt admission = adminLoggedIn ? null : commentThrottle.acquire(
                LoginThrottle.clientAddress(context.getRequest()), requestJSONObject.optString("oId"), requestJSONObject.optString("commentContent"));
        if (admission != null && !admission.allowed()) {
            context.setHeader("Retry-After", String.valueOf(admission.retryAfter()));
            AdminRequests.reject(context, 429, admission.message());
            return;
        }
        boolean commentPersisted = false;
        try {

        final JSONObject jsonObject = commentMgmtService.checkAddCommentRequest(requestJSONObject);
        final JsonRenderer renderer = new JsonRenderer();
        context.setRenderer(renderer);
        renderer.setJSONObject(jsonObject);
        if (!jsonObject.optBoolean("sc")) {
            return;
        }

        // 禁止冒用管理员评论
        JSONObject admin = new JSONObject();
        try {
            admin = userRepository.getAdmin();
        } catch (RepositoryException RE) {
            RE.printStackTrace();
        }
        if (admin.optString(User.USER_NAME).equals(username)) {
            if (!adminLoggedIn) {
                jsonObject.put(org.b3log.solo.model.Common.STATUS_CODE, false);
                jsonObject.put(Keys.MSG, "你输入了管理员的昵称，但并没有登录！");

                return;
            } else {
                // 管理员回复不发送邮件提醒
                try {
                    String adminActiveSentToMailbox = Options.get(Option.ID_C_ADMIN_ACTIVE_SENT_TO_MAILBOX);
                    if (adminActiveSentToMailbox.equals("off")) {
                        sendEmailToAdmin = false;
                    }
                } catch (Exception ignored) {
                }
            }
        }

        if (!adminLoggedIn && commentFilterService.blocked(requestJSONObject.getString("commentContent"))) {
            AdminRequests.reject(context, 400, org.b3log.solo.service.CommentFilterService.REJECTION);
            return;
        }

        JSONObject addResult = null;
        try {
            addResult = commentMgmtService.addArticleComment(requestJSONObject);
            commentPersisted = true;
            // The article row is only needed to send notifications and render this response.
            // Never return its source, password or other internal fields to a public caller.
            addResult.remove(Article.ARTICLE);

            // 用户关系表
            String commentId = addResult.optString("oId");
            // Notification email is saved with the comment in the same transaction.

            // 提醒被回复的用户
            String originalCommentId = "";
            String blogSite = Latkes.getServePath();
            String blogTitle = optionQueryService.getPreference().getString(Option.ID_C_BLOG_TITLE);
            try {
                originalCommentId = requestJSONObject.getString("commentOriginalCommentId");
                CommentMailService.remindCommentedGuy(
                        originalCommentId,
                        blogSite,
                        username,
                        blogTitle);
            } catch (JSONException jsonException) {
                LOGGER.log(Level.DEBUG,
                        "No originalCommentId for [from=" + commentId + ", to=" + originalCommentId + "]");
            }

            // 提醒博主
            if (sendEmailToAdmin) {
                // 邮件提醒
                String replyRemindMailBoxAddress = "";
                try {
                    replyRemindMailBoxAddress = optionRepository.get(Option.ID_C_REPLY_REMIND)
                            .optString(Option.OPTION_VALUE);
                } catch (NullPointerException e) {
                }
                String user = requestJSONObject.getString("commentName");
                String comment = requestJSONObject.getString("commentContent");
                try {
                    CommentMailService.remindAdmin(
                            replyRemindMailBoxAddress,
                            blogSite,
                            user,
                            comment,
                            blogTitle);
                } catch (JSONException jsonException) {
                    LOGGER.log(Level.DEBUG, "Send admin mail reminder failed.");
                }
                // Server酱提醒
                ServerJiangService.remindAdmin(
                        blogSite,
                        user,
                        comment,
                        blogTitle);
            }

            final Map<String, Object> dataModel = commentTemplateModel(addResult);

            // 添加评论优化 https://github.com/b3log/solo/issues/12246
            try {
                final String skinDirName = (String) context.attr(Keys.TEMPLATE_DIR_NAME);
                final Template template = Skins.getSkinTemplate(context, "common-comment.ftl");
                final JSONObject preference = optionQueryService.getPreference();
                Skins.fillLangs(preference.optString(Option.ID_C_LOCALE_STRING), skinDirName, dataModel);
                Keys.fillServer(dataModel);
                final StringWriter stringWriter = new StringWriter();
                template.process(dataModel, stringWriter);
                stringWriter.close();
                final String cmtTpl = stringWriter.toString();

                addResult.put("cmtTpl", cmtTpl);
            } catch (final Exception e) {
                LOGGER.log(Level.WARN, "Comment saved, but its skin template could not be rendered.");
            }

            addResult.put(org.b3log.solo.model.Common.STATUS_CODE, true);

            renderer.setJSONObject(addResult);
        } catch (final Exception e) {

            LOGGER.log(Level.ERROR, "Can not add comment on article", e);
            if (commentPersisted && addResult != null) {
                // A notification failure must not invite a second copy of a saved comment.
                addResult.put(org.b3log.solo.model.Common.STATUS_CODE, true);
                renderer.setJSONObject(addResult);
            } else {
                jsonObject.put(org.b3log.solo.model.Common.STATUS_CODE, false);
                jsonObject.put(Keys.MSG, langPropsService.get("addFailLabel"));
            }
        }
        } finally {
            if (!commentPersisted) commentThrottle.failed(admission);
        }
    }

    static boolean checkCommentCaptcha(final RequestContext context, final JSONObject payload, final boolean administrator) {
        if (administrator) return true;
        final var captcha = org.b3log.latke.ioc.BeanManager.getInstance().getReference(org.b3log.solo.service.CaptchaService.class);
        if (!captcha.commentsRequired()) return true;
        if (captcha.consume("comment", LoginThrottle.clientAddress(context.getRequest()), payload.optString("captchaId"), payload.optString("captchaAnswer"))) return true;
        AdminRequests.reject(context, 400, "验证码错误或已过期，请重新确认评论。"); return false;
    }

    static Map<String, Object> commentTemplateModel(final JSONObject comment) {
        final Map<String, Object> model = new HashMap<>();
        // JSONObject.get throws for absent fields, bypassing FreeMarker's ! defaults.
        // A plain map keeps optional thread, deletion and region fields optional.
        model.put(Comment.COMMENT, comment.toMap());
        model.put(Article.ARTICLE, Map.of(Common.COMMENTABLE, comment.optBoolean(Common.COMMENTABLE),
                Common.PERMALINK, comment.optString(Common.PERMALINK)));
        return model;
    }

    /**
     * Fills commenter info if logged in.
     *
     * @param requestJSONObject the specified request json object
     * @param context           the specified HTTP servlet request context
     */
    private void fillCommenter(final JSONObject requestJSONObject, final RequestContext context) {
        final JSONObject currentUser = Solos.getCurrentUser(context.getRequest(), context.getResponse());
        if (null == currentUser) {
            return;
        }

        requestJSONObject.put(Comment.COMMENT_NAME, currentUser.optString(User.USER_NAME));
        requestJSONObject.put(Comment.COMMENT_URL, currentUser.optString(User.USER_URL));
    }

    @RequestProcessing(value = "/article/commentSync/getList", method = HttpMethod.GET)
    public void commentGetArticleList(final RequestContext context) {
        if (!Solos.isAdminLoggedIn(context)) {
            context.sendError(401);

            return;
        }

        final Query query = new Query().setFilter(CompositeFilterOperator.and(
                new PropertyFilter(Article.ARTICLE_STATUS, FilterOperator.EQUAL, Article.ARTICLE_STATUS_C_PUBLISHED),
                new PropertyFilter(Article.ARTICLE_STATUS, FilterOperator.EQUAL, Article.ARTICLE_STATUS_C_PUBLISHED)))
                .setPageCount(1).addSort(Keys.OBJECT_ID, SortDirection.DESCENDING);
        List<JSONObject> articlesList = new ArrayList<>();
        try {
            final List<JSONObject> articles = articleRepository.getList(query);
            for (final JSONObject article : articles) {
                JSONObject articleInfo = new JSONObject();
                String oId = article.optString("oId");
                String title = article.optString(Article.ARTICLE_TITLE);
                articleInfo.put("oId", oId);
                articleInfo.put("title", title);
                articlesList.add(articleInfo);
            }
            org.b3log.solo.web.JsonResponses.json(context).renderData(articlesList);
        } catch (RepositoryException repositoryException) {
            repositoryException.printStackTrace();
            org.b3log.solo.web.JsonResponses.json(context).renderCode(1);
        }
    }

    /**
     * Sync comment from HacPai to article
     *
     * @param context
     */
    @RequestProcessing(value = "/article/commentSync", method = HttpMethod.POST)
    public void commentSync(final RequestContext context) {
        syncComments(context, false);
    }

    /**
     * Sync comment from HacPai to article
     *
     * @param context
     */
    @RequestProcessing(value = "/article/fishpi/commentSync", method = HttpMethod.POST)
    public void commentSyncFromFishPI(final RequestContext context) {
        syncComments(context, true);
    }

    private void syncComments(final RequestContext context, final boolean fishpi) {
        if (!AdminRequests.allowWrite(context)) return;
        final JSONObject payload;
        final long localId;
        final long remoteId;
        final String symphony;
        try {
            payload = context.requestJSON();
            localId = Long.parseLong(payload.optString("localArticleId"));
            remoteId = Long.parseLong(payload.optString("remoteArticleId"));
            symphony = payload.optString("symphony");
            if (localId <= 0 || remoteId <= 0 || (!fishpi && !symphony.matches("[A-Za-z0-9_./+=%-]{1,4096}"))) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            AdminRequests.reject(context, 400, "请输入有效的文章编号和 Cookie。");
            return;
        }
        if (articleQueryService.getArticleById(String.valueOf(localId)) == null) {
            AdminRequests.reject(context, 404, "本地文章不存在，请重新选择。");
            return;
        }
        final Transaction transaction = commentRepository.beginTransaction();
        try {
            final int pageCount = fishpi ? commentMgmtService.syncCommentFromFishPI(localId, remoteId, 1)
                    : syncComment(localId, remoteId, symphony, 1);
            if (pageCount < 0 || pageCount > 1000) {
                AdminRequests.reject(context, 502, "评论同步失败，请检查远端服务和凭据。");
                return;
            }
            for (int page = 2; page <= pageCount; page++) {
                final int result = fishpi ? commentMgmtService.syncCommentFromFishPI(localId, remoteId, page)
                        : syncComment(localId, remoteId, symphony, page);
                if (result < 0) {
                    AdminRequests.reject(context, 502, "评论同步中断，请稍后重试。");
                    return;
                }
            }
            if (fishpi) {
                final String id = "article_" + localId;
                final JSONObject reference = new JSONObject().put(Keys.OBJECT_ID, id)
                        .put(Option.OPTION_CATEGORY, "fishPiArticleRef").put(Option.OPTION_VALUE, String.valueOf(remoteId));
                if (optionRepository.get(id) == null) optionRepository.add(reference);
                else optionRepository.update(id, reference);
            }
            transaction.commit();
            context.renderJSON(new JSONObject().put("sc", true).put("msg", pageCount == 0 ? "远端文章暂无评论。" : "评论同步成功。"));
        } catch (Exception exception) {
            // Do not log upstream errors that may contain credentials or private comment data.
            LOGGER.log(Level.WARN, "Comment sync failed");
            AdminRequests.reject(context, 502, "评论同步失败，请检查远端服务和凭据。");
        } finally {
            if (transaction.isActive()) transaction.rollback();
        }
    }

    private int syncComment(long localaid, long remoteaid, String symphony, int page) throws RepositoryException {
        // 获取本地文章信息
        final JSONObject article = articleQueryService.getArticleById(String.valueOf(localaid));
        String fetchURL = "https://" + Global.HACPAI_DOMAIN + "/api/v2/article/" + remoteaid + "?p=" + page;
        // 从远程拉取评论列表
        final HttpResponse res = HttpRequest.get(fetchURL).connectionTimeout(3000).timeout(7000)
                .header("User-Agent", Solos.USER_AGENT).cookies(new Cookie("symphony=" + symphony)).send();
        if (200 != res.statusCode()) {
            return -1;
        }
        res.charset("UTF-8");
        final JSONObject result = new JSONObject(res.bodyText());
        JSONArray rslt;
        try {
            rslt = result.optJSONObject("data").optJSONObject("article").optJSONArray("articleComments");
        } catch (Exception e) {
            return -1;
        }
        if (rslt == null) return -1;
        if (rslt.isEmpty()) return 0;
        for (Object o : rslt) {
                JSONObject object = (JSONObject) o;
                String link = object.optString("commentArticlePermalink");
                String avatar = object.optString("commentAuthorThumbnailURL");
                String comment = object.optString("commentContent");
                String author = object.optString("commentAuthorName");
                String id = object.optString("oId");
                if (commentRepository.get(id) != null) continue;
                final JSONObject commentObject = new JSONObject();
                commentObject.put(Comment.COMMENT_ORIGINAL_COMMENT_ID, "");
                commentObject.put(Comment.COMMENT_ORIGINAL_COMMENT_NAME, "");
                commentObject.put(Comment.COMMENT_NAME, author);
                commentObject.put(Comment.COMMENT_URL, link);
                commentObject.put(Comment.COMMENT_CONTENT, comment);
                commentObject.put(Comment.COMMENT_CREATED, Long.parseLong(id));
                commentObject.put(Comment.COMMENT_THUMBNAIL_URL, avatar);
                commentObject.put(Comment.COMMENT_ON_ID, localaid);
                commentObject.put(Keys.OBJECT_ID, id);
                final long date = article.optLong(Article.ARTICLE_CREATED);
                commentObject.put(Comment.COMMENT_SHARP_URL, "/articles/" + DateFormatUtils.format(date, "yyyy/MM/dd")
                        + "/" + article.optString(Keys.OBJECT_ID) + ".html#" + Long.parseLong(id));
                commentRepository.add(commentObject);
                articleMgmtService.incArticleCommentCount(article.optString(Keys.OBJECT_ID));
        }
        return Integer
                .parseInt(result.optJSONObject("data").optJSONObject("pagination").optString("paginationPageCount"));
    }

}
