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
import org.b3log.latke.Latkes;
import org.b3log.latke.ioc.BeanManager;
import org.apache.logging.log4j.Level;
import org.b3log.solo.logging.Logger;
import org.b3log.latke.repository.RepositoryException;
import org.b3log.solo.web.Routes;
import org.b3log.latke.http.HttpMethod;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.http.function.Handler;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Option;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.service.InitService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.service.PermalinkQueryService;
import org.b3log.solo.util.Solos;
import org.json.JSONObject;

import org.b3log.latke.http.Response;

/**
 * Article permalink  handler.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 3.2.0
 */
public class PermalinkHandler implements Handler {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(PermalinkHandler.class);

    /**
     * Whether initialization info reported.
     */
    private static boolean initReported;

    @Override
    public void handle(final RequestContext context) {
        final BeanManager beanManager = BeanManager.getInstance();

        JSONObject article;
        try {
            final InitService initService = beanManager.getReference(InitService.class);
            if (!initService.isInited()) {
                context.handle();

                return;
            }

            final String requestURI = context.requestURI();
            final String contextPath = Latkes.getContextPath();
            final String permalink = StringUtils.substringAfter(requestURI, contextPath);
            if (PermalinkQueryService.invalidPermalinkFormat(permalink)) {
                LOGGER.log(Level.DEBUG, "Skip permalink handling request [URI={0}]", permalink);
                context.handle();

                return;
            }

            final ArticleRepository articleRepository = beanManager.getReference(ArticleRepository.class);
            article = articleRepository.getByPermalink(permalink);
            if (null == article) {
                LOGGER.log(Level.DEBUG, "Not found article with permalink [{0}]", permalink);
                context.handle();

                return;
            }
        } catch (final RepositoryException e) {
            LOGGER.log(Level.ERROR, "Processes article permalink handler failed", e);
            context.sendError(404);
            context.abort();

            return;
        }

        final org.b3log.solo.service.ShareTokenService shareTokenService =
                beanManager.getReference(org.b3log.solo.service.ShareTokenService.class);
        final boolean validPreviewToken = null != shareTokenService && shareTokenService.hasValidPreviewToken(context, article);

        // If requests an article and the article need view password, sends redirect to the password form
        if (!validPreviewToken && Solos.needViewPwd(context, article)) {
            try {
                context.sendRedirect(Latkes.getServePath() + "/console/article-pwd?articleId=" + article.optString(Keys.OBJECT_ID));
                context.abort();

                return;
            } catch (final Exception e) {
                context.sendError(404);
                context.abort();

                return;
            }
        }

        final OptionQueryService optionQueryService = beanManager.getReference(OptionQueryService.class);
        final JSONObject preference = optionQueryService.getPreference();
        final boolean allowVisitDraftViaPermalink = preference.getBoolean(Option.ID_C_ALLOW_VISIT_DRAFT_VIA_PERMALINK);
        if (Article.ARTICLE_STATUS_C_PUBLISHED != article.optInt(Article.ARTICLE_STATUS)
                && !allowVisitDraftViaPermalink
                && !validPreviewToken
                && !Solos.isAdminOrAuthorLoggedIn(context)) {
            context.sendError(404);
            context.abort();

            return;
        }

        dispatchToArticleProcessor(context, article);
        context.handle();
    }

    /**
     * Dispatches the specified request to the specified article processor with the specified response.
     *
     * @param context the specified request context
     * @param article the specified article
     * @see Routes#result(RequestContext)
     */
    private void dispatchToArticleProcessor(final RequestContext context, final JSONObject article) {
        context.attr(Article.ARTICLE, article);
        context.attr(Keys.HttpRequest.REQUEST_URI, Latkes.getContextPath() + "/article");
        context.attr(Keys.HttpRequest.REQUEST_METHOD, HttpMethod.GET.name());
    }
}
