/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.processor;

import org.apache.commons.lang3.StringUtils;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.http.HttpMethod;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.http.function.Handler;
import org.b3log.latke.ioc.BeanManager;
import org.b3log.solo.model.Article;

/**
 * Pre-route handler for IndexNow root key verification files ({@code /{indexNowKey}.txt}).
 *
 * <p>Latke's {@code UriTemplates.resolve} strips segment suffixes without verifying that the request
 * segment actually carries that suffix, so registering {@code /{indexNowKey}.txt} inside
 * {@code RouteHandler.ONE_SEG_GET_VAR_CTX_HANDLER_METAS} collides with {@code /{name}.html}
 * ({@link UserTemplateProcessor#showPage}) and causes public {@code /*.html} pages such as
 * {@code /archives.html} and {@code /categories.html} to return HTTP 404. Handling single-segment
 * {@code .txt} requests after {@link PermalinkHandler} and before {@code RouteHandler} eliminates
 * the route collision while preserving user-defined {@code .txt} custom pages.</p>
 *
 * @since 3.0.0
 */
public final class IndexNowKeyHandler implements Handler {

    private static final String TXT_SUFFIX = ".txt";

    @Override
    public void handle(final RequestContext context) {
        if (!HttpMethod.GET.name().equalsIgnoreCase(context.method())
                || context.attr(Article.ARTICLE) != null) {
            context.handle();
            return;
        }
        final Object rewrittenUri = context.attr(Keys.HttpRequest.REQUEST_URI);
        final String requestURI = rewrittenUri instanceof String s && !s.isEmpty() ? s : context.requestURI();
        final String localURI = StringUtils.removeStart(requestURI, Latkes.getContextPath());
        if (localURI == null
                || localURI.length() <= TXT_SUFFIX.length() + 1
                || localURI.charAt(0) != '/'
                || localURI.indexOf('/', 1) >= 0
                || !localURI.endsWith(TXT_SUFFIX)
                || "/robots.txt".equals(localURI)) {
            context.handle();
            return;
        }

        final String candidateKey = localURI.substring(1, localURI.length() - TXT_SUFFIX.length());
        SitemapProcessor sitemapProcessor = null;
        final BeanManager beanManager = BeanManager.getInstance();
        if (beanManager != null) {
            try {
                sitemapProcessor = beanManager.getReference(SitemapProcessor.class);
            } catch (final RuntimeException ignored) {
            }
        }
        if (sitemapProcessor != null && sitemapProcessor.tryServeIndexNowKey(context, candidateKey)) {
            context.abort();
            return;
        }

        context.sendError(404);
        context.abort();
    }
}
