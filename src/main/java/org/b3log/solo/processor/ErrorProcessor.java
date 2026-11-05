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
import org.apache.logging.log4j.Level;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.http.HttpMethod;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.http.renderer.AbstractResponseRenderer;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.ioc.Singleton;
import org.b3log.latke.service.LangPropsService;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.model.Common;
import org.b3log.solo.model.Option;
import org.b3log.solo.service.DataModelService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.service.RiskControlService;
import org.b3log.solo.service.UserQueryService;
import org.b3log.solo.util.SiteAppearance;
import org.b3log.solo.util.Solos;
import org.b3log.solo.web.HttpCompression;
import org.b3log.solo.web.JsonResponses;
import org.b3log.solo.web.annotation.RequestProcessing;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Error processor.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 0.4.5
 */
@Singleton
public class ErrorProcessor {

    /**
     * Request attribute guarding duplicate {@link RiskControlService#recordFailure} invocations within a single request.
     */
    public static final String RISK_RECORDED_ATTR = "bolo.riskRecorded";

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(ArticleProcessor.class);

    private static final Set<String> ALLOWED_STATUS_CODES =
            Set.of("401", "403", "404", "410", "429", "500");
    private static final Set<String> CACHEABLE_ERROR_CODES =
            Set.of("403", "404", "429", "500");
    private static final long ERROR_HTML_TTL_MS = 60_000L;
    private static final int MAX_CACHED_ERROR_PAGES = 32;

    private static final AbstractResponseRenderer NOOP_RENDERER = new AbstractResponseRenderer() {
        @Override
        public void render(final RequestContext ctx) {
        }
    };

    private static final LinkedHashMap<String, CachedErrorPage> ERROR_HTML_CACHE =
            new LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(final Map.Entry<String, CachedErrorPage> eldest) {
                    return size() > MAX_CACHED_ERROR_PAGES;
                }
            };

    private record CachedErrorPage(String html, long expiresAtMillis) {
    }

    private static final class CachedErrorHtmlRenderer extends AbstractResponseRenderer {
        private final String html;

        CachedErrorHtmlRenderer(final String html) {
            this.html = html;
        }

        @Override
        public void render(final RequestContext ctx) {
            HttpCompression.sendText(ctx, html, "text/html; charset=UTF-8");
        }
    }

    /**
     * Records a risk-control failure at most once per HTTP request context.
     *
     * @param context    HTTP request context
     * @param statusCode HTTP status code
     */
    public static void recordFailureOnce(final RequestContext context, final int statusCode) {
        if (context == null) {
            return;
        }
        if (statusCode != 400 && statusCode != 401 && statusCode != 403
                && statusCode != 404 && statusCode != 405 && statusCode != 429) {
            return;
        }
        if (Boolean.TRUE.equals(context.attr(RISK_RECORDED_ATTR))) {
            return;
        }
        context.attr(RISK_RECORDED_ATTR, Boolean.TRUE);
        RiskControlService.recordFailure(context, statusCode);
    }

    /**
     * Clears the 60-second anonymous rendered error page cache.
     */
    public static void clearCache() {
        synchronized (ERROR_HTML_CACHE) {
            ERROR_HTML_CACHE.clear();
        }
    }

    /**
     * DataModelService.
     */
    @Inject
    private DataModelService dataModelService;

    /**
     * Option query service.
     */
    @Inject
    private OptionQueryService optionQueryService;

    /**
     * User query service.
     */
    @Inject
    private UserQueryService userQueryService;

    /**
     * Language service.
     */
    @Inject
    private LangPropsService langPropsService;

    /**
     * Handles the error.
     *
     * @param context the specified context
     */
    @RequestProcessing(value = "/error/{statusCode}", method = {HttpMethod.GET, HttpMethod.HEAD, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE})
    public void showErrorPage(final RequestContext context) {
        final String requestedStatus = context.pathVar("statusCode");
        if (isThreeAsciiDigits(requestedStatus)) {
            recordFailureOnce(context, parseThreeDigits(requestedStatus));
        }
        final String statusCode = requestedStatus != null && ALLOWED_STATUS_CODES.contains(requestedStatus)
                ? requestedStatus : "404";
        final int numericStatus = parseThreeDigits(statusCode);
        recordFailureOnce(context, numericStatus);
        context.setStatus(numericStatus);
        context.setHeader("Cache-Control", "no-store");

        if ("HEAD".equalsIgnoreCase(context.method())) {
            Solos.addGoogleNoIndex(context);
            context.setRenderer(NOOP_RENDERER);
            context.getResponse().setContentType("text/html; charset=UTF-8");
            context.sendBytes(new byte[0]);
            return;
        }

        if (StringUtils.equals("GET", context.method())) {
            final String requestURI = context.requestURI();
            final String templateName = statusCode + ".ftl";
            LOGGER.log(Level.TRACE, "Shows error page [requestURI={0}, templateName={1}]", requestURI, templateName);

            final String msg = (String) context.attr(Keys.MSG);
            final boolean cacheEligible = CACHEABLE_ERROR_CODES.contains(statusCode)
                    && StringUtils.isBlank(msg)
                    && !Boolean.parseBoolean(context.header("X-PJAX"))
                    && !Solos.isLoggedIn(context);

            try {
                final JSONObject preference = optionQueryService.getPreference();
                String skinDirName = (String) context.attr(Keys.TEMPLATE_DIR_NAME);
                if (StringUtils.isBlank(skinDirName)) {
                    skinDirName = preference != null
                            ? preference.optString(Option.ID_C_SKIN_DIR_NAME, Option.DefaultPreference.DEFAULT_SKIN_DIR_NAME)
                            : Option.DefaultPreference.DEFAULT_SKIN_DIR_NAME;
                    if (StringUtils.isNotBlank(skinDirName)) {
                        context.attr(Keys.TEMPLATE_DIR_NAME, skinDirName);
                    }
                }

                final String cacheKey;
                if (cacheEligible) {
                    final String blogTitle = preference != null
                            ? preference.optString(Option.ID_C_BLOG_TITLE, "")
                            : "";
                    cacheKey = skinDirName + "|" + Latkes.getLocale() + "|" + SiteAppearance.current()
                            + "|" + Latkes.getStaticResourceVersion() + "|" + blogTitle + "|" + statusCode;
                    final String cachedHtml = lookupCachedErrorHtml(cacheKey);
                    if (cachedHtml != null) {
                        Solos.addGoogleNoIndex(context);
                        context.setRenderer(new CachedErrorHtmlRenderer(cachedHtml));
                        return;
                    }
                } else {
                    cacheKey = null;
                }

                final SkinRenderer renderer = new SkinRenderer(context, "error/" + templateName);
                if (cacheKey != null) {
                    renderer.setHtmlCapture(rendered -> putCachedErrorHtml(cacheKey, rendered));
                }
                final Map<String, Object> dataModel = renderer.getDataModel();
                final Map<String, String> langs = langPropsService.getAll(Latkes.getLocale());
                dataModel.putAll(langs);
                dataModelService.fillCommon(context, dataModel, preference);
                dataModelService.fillFaviconURL(dataModel, preference);
                dataModelService.fillUsite(dataModel);
                dataModel.put(Keys.MSG, msg);
                dataModel.put(Common.LOGIN_URL, userQueryService.getLoginURL(Common.ADMIN_INDEX_URI));
            } catch (final Exception e) {
                LOGGER.log(Level.ERROR, "Shows error page failed", e);

                context.setRenderer(NOOP_RENDERER);
                recordFailureOnce(context, numericStatus);
                context.setStatus(numericStatus);
                context.getResponse().setContentType("text/plain; charset=UTF-8");
                context.sendBytes(("HTTP " + statusCode).getBytes(StandardCharsets.UTF_8));
                context.abort();
                return;
            }

            Solos.addGoogleNoIndex(context);
        } else {
            JsonResponses.json(context).renderMsg(statusCode);
        }
    }

    private static String lookupCachedErrorHtml(final String cacheKey) {
        final long now = System.currentTimeMillis();
        synchronized (ERROR_HTML_CACHE) {
            final CachedErrorPage entry = ERROR_HTML_CACHE.get(cacheKey);
            if (entry == null) {
                return null;
            }
            if (now < entry.expiresAtMillis()) {
                return entry.html();
            }
            ERROR_HTML_CACHE.remove(cacheKey);
            return null;
        }
    }

    private static void putCachedErrorHtml(final String cacheKey, final String html) {
        if (html == null || html.isEmpty()) {
            return;
        }
        final long expiresAt = System.currentTimeMillis() + ERROR_HTML_TTL_MS;
        synchronized (ERROR_HTML_CACHE) {
            ERROR_HTML_CACHE.put(cacheKey, new CachedErrorPage(html, expiresAt));
        }
    }

    private static boolean isThreeAsciiDigits(final String s) {
        return s != null
                && s.length() == 3
                && s.charAt(0) >= '0' && s.charAt(0) <= '9'
                && s.charAt(1) >= '0' && s.charAt(1) <= '9'
                && s.charAt(2) >= '0' && s.charAt(2) <= '9';
    }

    private static int parseThreeDigits(final String s) {
        return (s.charAt(0) - '0') * 100 + (s.charAt(1) - '0') * 10 + (s.charAt(2) - '0');
    }
}
