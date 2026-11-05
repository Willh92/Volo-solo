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

import org.apache.commons.lang3.StringUtils;
import org.b3log.latke.Keys;
import org.b3log.latke.ioc.BeanManager;
import org.b3log.latke.ioc.Inject;
import org.apache.logging.log4j.Level;
import org.b3log.solo.logging.Logger;
import org.b3log.latke.model.Role;
import org.b3log.latke.model.User;
import org.b3log.latke.http.HttpMethod;
import org.b3log.latke.http.RequestContext;
import org.b3log.solo.web.annotation.RequestProcessing;
import org.b3log.latke.ioc.Singleton;
import org.b3log.solo.model.*;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.repository.CommentRepository;
import org.b3log.solo.repository.OptionRepository;
import org.b3log.solo.repository.UserRepository;
import org.b3log.solo.service.*;
import org.b3log.solo.util.LoginThrottle;
import org.b3log.solo.util.Markdowns;
import org.b3log.solo.util.PluginUtil;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Receiving articles and comments from B3log community. Visits <a href="https://ld246.com/b3log">B3log 构思</a> for more details.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 0.5.5
 */
@Singleton
public class B3Receiver {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(B3Receiver.class);

    private static final LoginThrottle THROTTLE = new LoginThrottle();

    /**
     * User repository.
     */
    @Inject
    private UserRepository userRepository;

    /**
     * Comment repository.
     */
    @Inject
    private static CommentRepository commentRepository;

    /**
     * Article repository.
     */
    @Inject
    private ArticleRepository articleRepository;

    /**
     * Comment management service.
     */
    @Inject
    private CommentMgmtService commentMgmtService;

    /**
     * Article management service.
     */
    @Inject
    private ArticleMgmtService articleMgmtService;

    /**
     * Article query service.
     */
    @Inject
    private ArticleQueryService articleQueryService;

    /**
     * Option query service.
     */
    @Inject
    private OptionQueryService optionQueryService;

    /**
     * User management service.
     */
    @Inject
    private UserMgmtService userMgmtService;

    @RequestProcessing(value = "/apis/symphony/article", method = {HttpMethod.POST, HttpMethod.PUT})
    public void postArticle(final RequestContext context) {
        context.setHeader("Cache-Control", "no-store");
        if (!PluginUtil.b3logPluginEnabled()) {
            context.sendError(404);
            return;
        }
        final JSONObject ret = new JSONObject().put(Keys.CODE, 0);
        context.renderJSON(ret);

        final String remoteAddr = LoginThrottle.clientAddress(context.getRequest());
        final LoginThrottle.Attempt attempt = THROTTLE.acquire(remoteAddr, "symphony-api");
        if (attempt.retryAfter() > 0) {
            context.setStatus(429);
            context.setHeader("Retry-After", Integer.toString(attempt.retryAfter()));
            ret.put(Keys.CODE, 1).put(Keys.MSG, "Too many requests");
            return;
        }

        final BeanManager beanManager = BeanManager.getInstance();
        final UserQueryService uqs = beanManager == null ? null : beanManager.getReference(UserQueryService.class);
        final String configuredKey = uqs == null ? "" : uqs.getConfiguredB3Key();
        if (configuredKey.length() < 8) {
            context.setStatus(403);
            ret.put(Keys.CODE, 1).put(Keys.MSG, "Community sync key is not configured");
            return;
        }

        final JSONObject requestJSONObject = context.requestJSON();
        LOGGER.log(Level.DEBUG, "Received an article from community");

        try {
            final JSONObject client = requestJSONObject == null ? null : requestJSONObject.optJSONObject("client");
            if (client == null) {
                context.setStatus(400);
                ret.put(Keys.CODE, 1).put(Keys.MSG, "Invalid request");
                return;
            }
            final String b3Key = client.optString(UserExt.USER_B3_KEY, "");
            if (b3Key.isEmpty() || !MessageDigest.isEqual(
                    configuredKey.getBytes(StandardCharsets.UTF_8),
                    b3Key.getBytes(StandardCharsets.UTF_8))) {
                RiskControlService.recordFailure(context, 403);
                context.setStatus(403);
                ret.put(Keys.CODE, 1).put(Keys.MSG, "Forbidden");
                LOGGER.log(Level.WARN, "Rejected community article sync with invalid key");
                return;
            }

            final String articleAuthorName = client.optString(User.USER_NAME);
            final JSONObject articleAuthor = userRepository.getByUserName(articleAuthorName);
            if (null == articleAuthor || Role.VISITOR_ROLE.equals(articleAuthor.optString(User.USER_ROLE))
                    || articleAuthor.optInt(UserExt.USER_STATUS, UserExt.USER_STATUS_C_VALID) != UserExt.USER_STATUS_C_VALID) {
                context.setStatus(403);
                ret.put(Keys.CODE, 1).put(Keys.MSG, "Forbidden");
                LOGGER.log(Level.WARN, "Not found authorized user [" + articleAuthorName + "]");
                return;
            }
            THROTTLE.succeeded(attempt);

            final JSONObject symArticle = requestJSONObject.optJSONObject(Article.ARTICLE);
            if (symArticle == null) {
                context.setStatus(400);
                ret.put(Keys.CODE, 1).put(Keys.MSG, "Invalid article payload");
                return;
            }
            final String title = symArticle.optString("title");
            final String articleId = symArticle.optString("id");
            final String sanitizedContent = symArticle.optString("content");
            final JSONObject oldArticle = StringUtils.isBlank(articleId) ? null : articleQueryService.getArticleById(articleId);
            if (null == oldArticle) {
                final JSONObject article = new JSONObject()
                        .put(Keys.OBJECT_ID, articleId)
                        .put(Article.ARTICLE_TITLE, title)
                        .put(Article.ARTICLE_CONTENT, sanitizedContent)
                        .put(Article.ARTICLE_TAGS_REF, symArticle.optString("tags"));
                article.put(Article.ARTICLE_AUTHOR_ID, articleAuthor.getString(Keys.OBJECT_ID));
                article.put(Article.ARTICLE_ABSTRACT, Article.getAbstractText(sanitizedContent));
                article.put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED);
                article.put(Common.POST_TO_COMMUNITY, false);
                article.put(Article.ARTICLE_COMMENTABLE, true);
                article.put(Article.ARTICLE_VIEW_PWD, "");
                final JSONObject addRequest = new JSONObject().put(Article.ARTICLE, article);
                articleMgmtService.addArticle(addRequest);
                LOGGER.log(Level.INFO, "Added an article [" + title + "] via Sym");
                return;
            }

            if (!Role.ADMIN_ROLE.equals(articleAuthor.optString(User.USER_ROLE))
                    && !articleAuthor.optString(Keys.OBJECT_ID).equals(oldArticle.optString(Article.ARTICLE_AUTHOR_ID))) {
                context.setStatus(403);
                ret.put(Keys.CODE, 1).put(Keys.MSG, "Forbidden");
                return;
            }

            oldArticle.put(Article.ARTICLE_ABSTRACT, Article.getAbstractText(sanitizedContent));
            oldArticle.put(Article.ARTICLE_CONTENT, sanitizedContent);
            oldArticle.put(Article.ARTICLE_TITLE, title);
            oldArticle.put(Article.ARTICLE_TAGS_REF, symArticle.optString("tags"));
            oldArticle.put(Common.POST_TO_COMMUNITY, false);
            final JSONObject updateRequest = new JSONObject().put(Article.ARTICLE, oldArticle);
            articleMgmtService.updateArticle(updateRequest);
            LOGGER.log(Level.INFO, "Updated an article [" + title + "] via Sym");
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, e.getMessage(), e);
            context.setStatus(500);
            ret.put(Keys.CODE, 1).put(Keys.MSG, "Failed to process article");
        }
    }
}
