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
import org.b3log.latke.model.Role;
import org.b3log.latke.model.User;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Option;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.service.ArticleQueryService;
import org.b3log.solo.service.InitService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.service.PermalinkQueryService;
import org.b3log.solo.util.Solos;
import org.json.JSONObject;

/**
 * Article permalink handler.
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

    private record CachedRef<T>(BeanManager owner, T instance) {
    }

    private static volatile CachedRef<InitService> cachedInitService;
    private static volatile CachedRef<ArticleRepository> cachedArticleRepository;
    private static volatile CachedRef<org.b3log.solo.service.ShareTokenService> cachedShareTokenService;
    private static volatile CachedRef<OptionQueryService> cachedOptionQueryService;
    private static volatile CachedRef<ArticleQueryService> cachedArticleQueryService;

    private static InitService resolveInitService(final BeanManager beanManager) {
        final CachedRef<InitService> cached = cachedInitService;
        if (null != cached && cached.owner == beanManager) {
            return cached.instance;
        }
        final InitService ref = beanManager.getReference(InitService.class);
        if (null != ref) {
            cachedInitService = new CachedRef<>(beanManager, ref);
        }
        return ref;
    }

    private static ArticleRepository resolveArticleRepository(final BeanManager beanManager) {
        final CachedRef<ArticleRepository> cached = cachedArticleRepository;
        if (null != cached && cached.owner == beanManager) {
            return cached.instance;
        }
        final ArticleRepository ref = beanManager.getReference(ArticleRepository.class);
        if (null != ref) {
            cachedArticleRepository = new CachedRef<>(beanManager, ref);
        }
        return ref;
    }

    private static org.b3log.solo.service.ShareTokenService resolveShareTokenService(final BeanManager beanManager) {
        final CachedRef<org.b3log.solo.service.ShareTokenService> cached = cachedShareTokenService;
        if (null != cached && cached.owner == beanManager) {
            return cached.instance;
        }
        final org.b3log.solo.service.ShareTokenService ref =
                beanManager.getReference(org.b3log.solo.service.ShareTokenService.class);
        if (null != ref) {
            cachedShareTokenService = new CachedRef<>(beanManager, ref);
        }
        return ref;
    }

    private static OptionQueryService resolveOptionQueryService(final BeanManager beanManager) {
        final CachedRef<OptionQueryService> cached = cachedOptionQueryService;
        if (null != cached && cached.owner == beanManager) {
            return cached.instance;
        }
        final OptionQueryService ref = beanManager.getReference(OptionQueryService.class);
        if (null != ref) {
            cachedOptionQueryService = new CachedRef<>(beanManager, ref);
        }
        return ref;
    }

    private static ArticleQueryService resolveArticleQueryService(final BeanManager beanManager) {
        final CachedRef<ArticleQueryService> cached = cachedArticleQueryService;
        if (null != cached && cached.owner == beanManager) {
            return cached.instance;
        }
        try {
            final ArticleQueryService ref = beanManager.getReference(ArticleQueryService.class);
            if (null != ref) {
                cachedArticleQueryService = new CachedRef<>(beanManager, ref);
            }
            return ref;
        } catch (final Exception ignored) {
            return null;
        }
    }

    private static boolean canAccessDraftArticle(final BeanManager beanManager,
                                                 final RequestContext context,
                                                 final JSONObject article) {
        final JSONObject currentUser = Solos.getCurrentUser(context.getRequest(), context.getResponse());
        if (null == currentUser) {
            return false;
        }
        final ArticleQueryService articleQueryService = resolveArticleQueryService(beanManager);
        if (null != articleQueryService) {
            try {
                return articleQueryService.canAccessArticle(article.optString(Keys.OBJECT_ID), currentUser);
            } catch (final Exception e) {
                LOGGER.log(Level.WARN, "Checks draft article access failed", e);
                return false;
            }
        }
        if (Role.ADMIN_ROLE.equals(currentUser.optString(User.USER_ROLE))) {
            return true;
        }
        final String currentUserId = currentUser.optString(Keys.OBJECT_ID);
        return !currentUserId.isEmpty() && currentUserId.equals(article.optString(Article.ARTICLE_AUTHOR_ID));
    }

    /**
     * Decodes percent-encoded UTF-8 octets in a request permalink without converting literal {@code '+'} into spaces.
     *
     * @param rawPermalink raw request path after context path
     * @return decoded permalink, or {@code null} if percent-encoding is malformed or excessively long
     */
    public static String normalizeRequestPermalink(final String rawPermalink) {
        if (null == rawPermalink || rawPermalink.isEmpty() || rawPermalink.indexOf('%') < 0) {
            return rawPermalink;
        }
        if (rawPermalink.length() > 4096) {
            return null;
        }
        try {
            final String preservedPlus = rawPermalink.indexOf('+') >= 0
                    ? rawPermalink.replace("+", "%2B")
                    : rawPermalink;
            return java.net.URLDecoder.decode(preservedPlus, java.nio.charset.StandardCharsets.UTF_8);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public void handle(final RequestContext context) {
        final String method = context.method();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            context.handle();

            return;
        }
        final BeanManager beanManager = BeanManager.getInstance();

        JSONObject article;
        try {
            final InitService initService = resolveInitService(beanManager);
            if (!initService.isInited()) {
                context.handle();

                return;
            }

            final String requestURI = context.requestURI();
            final String contextPath = Latkes.getContextPath();
            final String permalink = StringUtils.substringAfter(requestURI, contextPath);
            final String decoded = normalizeRequestPermalink(permalink);
            if (null == decoded || PermalinkQueryService.invalidPermalinkFormat(decoded)) {
                LOGGER.log(Level.DEBUG, "Skip permalink handling request [URI={0}]", permalink);
                context.handle();

                return;
            }

            final ArticleRepository articleRepository = resolveArticleRepository(beanManager);
            article = articleRepository.getByPermalink(decoded);
            if (null == article && !decoded.equals(permalink) && !PermalinkQueryService.invalidPermalinkFormat(permalink)) {
                article = articleRepository.getByPermalink(permalink);
            }
            if (null == article) {
                LOGGER.log(Level.DEBUG, "Not found article with permalink [{0}]", decoded);
                context.handle();

                return;
            }
        } catch (final RepositoryException e) {
            LOGGER.log(Level.ERROR, "Processes article permalink handler failed", e);
            context.sendError(404);
            context.abort();

            return;
        }

        final org.b3log.solo.service.ShareTokenService shareTokenService = resolveShareTokenService(beanManager);
        final boolean validPreviewToken = null != shareTokenService && shareTokenService.hasValidPreviewToken(context, article);

        if (Article.ARTICLE_STATUS_C_PUBLISHED != article.optInt(Article.ARTICLE_STATUS)
                && !validPreviewToken) {
            final OptionQueryService optionQueryService = resolveOptionQueryService(beanManager);
            final boolean allowVisitDraftViaPermalink = null != optionQueryService
                    && Boolean.parseBoolean(optionQueryService.getPreferenceString(Option.ID_C_ALLOW_VISIT_DRAFT_VIA_PERMALINK, "false"));
            if (!allowVisitDraftViaPermalink && !canAccessDraftArticle(beanManager, context, article)) {
                context.sendError(404);
                context.abort();

                return;
            }
        }

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
        context.attr("bolo.originalHttpMethod", context.method());
        context.attr(Article.ARTICLE, article);
        context.attr(Keys.HttpRequest.REQUEST_URI, Latkes.getContextPath() + "/article");
        context.attr(Keys.HttpRequest.REQUEST_METHOD, HttpMethod.GET.name());
    }
}
