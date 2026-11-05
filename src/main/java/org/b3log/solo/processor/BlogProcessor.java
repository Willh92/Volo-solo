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

import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.http.HttpMethod;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.http.renderer.JsonRenderer;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.ioc.Singleton;
import org.b3log.latke.model.Pagination;
import org.b3log.latke.model.User;
import org.b3log.solo.SoloApplication;
import org.b3log.solo.bolo.prop.FaviconCache;
import org.b3log.solo.cache.PublicViewCache;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Option;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.service.ArticleQueryService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.service.StatisticQueryService;
import org.b3log.solo.service.TagQueryService;
import org.b3log.solo.service.UserQueryService;
import org.b3log.solo.web.JsonResponses;
import org.b3log.solo.web.annotation.RequestProcessing;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Blog processor.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 0.4.6
 */
@Singleton
public class BlogProcessor {

    @org.b3log.solo.web.annotation.Before(org.b3log.solo.processor.console.ConsoleAuthAdvice.class)
    @RequestProcessing(value = "/console/project-info", method = HttpMethod.GET)
    public void projectInfo(final RequestContext context) {
        context.setHeader("Cache-Control", "no-store");
        context.renderJSON(org.b3log.solo.util.ProjectInfo.snapshot());
    }

    @RequestProcessing(value = "/github-stats/{username}.svg", method = HttpMethod.GET)
    public void githubStats(final RequestContext context) {
        final String username = context.pathVar("username");
        if (!org.b3log.solo.util.GitHubStats.validUser(username)) {
            context.sendError(404);
            return;
        }
        final JSONArray repositories = gitHubRepositoryService.visibleFor(username);
        context.getResponse().setContentType("image/svg+xml; charset=UTF-8");
        context.setHeader("X-Content-Type-Options", "nosniff");
        context.setHeader("Cache-Control", "public, max-age=300");
        context.setHeader("Content-Security-Policy", "default-src 'none'; sandbox");
        context.sendString(org.b3log.solo.util.GitHubStats.svg(username, repositories, "dark".equals(context.getRequest().getParameter("theme"))));
    }

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(BlogProcessor.class);

    private static final int[] STANDARD_FAVICON_SIZES = {16, 32, 48, 64, 72, 96, 128, 144, 180, 192, 256, 512};
    private static final int MAX_FAVICON_CACHE_ENTRIES = 32;

    @Inject
    private org.b3log.solo.service.GitHubRepositoryService gitHubRepositoryService;

    /**
     * Article query service.
     */
    @Inject
    private ArticleQueryService articleQueryService;

    @Inject
    private ArticleRepository articleRepository;

    @Inject
    private PublicViewCache publicViewCache;

    /**
     * Tag query service.
     */
    @Inject
    private TagQueryService tagQueryService;

    /**
     * Statistic query service.
     */
    @Inject
    private StatisticQueryService statisticQueryService;

    /**
     * User query service.
     */
    @Inject
    private UserQueryService userQueryService;

    /**
     * Option query service.
     */
    @Inject
    private OptionQueryService optionQueryService;

    /**
     * PWA manifest JSON template.
     */
    private static String PWA_MANIFESTO_JSON;

    /**
     * Bounded LRU cache of rendered favicons restricted to standard icon dimensions.
     */
    HashMap<String, FaviconCache> faviconCache = new LinkedHashMap<>(48, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, FaviconCache> eldest) {
            return size() > MAX_FAVICON_CACHE_ENTRIES;
        }
    };

    static {
        try (final InputStream tplStream = BlogProcessor.class.getResourceAsStream("/manifest.json.tpl")) {
            PWA_MANIFESTO_JSON = IOUtils.toString(tplStream, "UTF-8");
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Loads PWA manifest.json template failed", e);
        }
    }

    /**
     * Gets PWA manifest.json.
     *
     * @param context the specified context
     */
    @RequestProcessing(value = "/manifest.json", method = {HttpMethod.GET, HttpMethod.HEAD})
    public void getPWAManifestJSON(final RequestContext context) {
        final JsonRenderer renderer = new JsonRenderer();
        renderer.setPretty(true);
        context.setRenderer(renderer);
        final JSONObject preference = optionQueryService.getPreference();
        if (null == preference) {
            if ("HEAD".equalsIgnoreCase(context.method()) && context.getResponse() != null) {
                context.getResponse().setContentType("application/json; charset=UTF-8");
                context.sendBytes(new byte[0]);
            }
            return;
        }
        final String name = preference.optString(Option.ID_C_BLOG_TITLE);
        final String description = preference.optString(Option.ID_C_BLOG_SUBTITLE);
        final JSONObject jsonObject = new JSONObject(PWA_MANIFESTO_JSON);
        jsonObject.put("name", name);
        jsonObject.put("short_name", name);
        jsonObject.put("description", description);
        jsonObject.put("theme_color", org.b3log.solo.util.SiteAppearance.color());
        jsonObject.put("background_color", "#f6f7f3");
        renderer.setJSONObject(jsonObject);
        if ("HEAD".equalsIgnoreCase(context.method()) && context.getResponse() != null) {
            JsonResponses.applyCompression(context);
            context.getRenderer().render(context);
        }
    }

    /**
     * Get an edited image by the website favicon.
     *
     * @param context the specified context
     */
    @RequestProcessing(value = "/favicon/{width}/{height}", method = HttpMethod.GET)
    public void getFavicon(final RequestContext context) {
        final int rawWidth, rawHeight;
        try {
            rawWidth = Integer.parseInt(context.pathVar("width"));
            rawHeight = Integer.parseInt(context.pathVar("height"));
            if (rawWidth < 16 || rawHeight < 16 || rawWidth > 512 || rawHeight > 512) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            context.setStatus(400);
            context.renderJSON(new JSONObject().put("sc", false).put("msg", "图标尺寸应介于 16 和 512 像素。"));
            return;
        }
        final int width = snapFaviconDimension(rawWidth);
        final int height = snapFaviconDimension(rawHeight);
        final JSONObject preference = optionQueryService.getPreference();
        final String source = preference == null ? "" : preference.optString(Option.ID_C_FAVICON_URL);
        final String key = source + ":" + width + ":" + height;
        try {
            final FaviconCache icon;
            synchronized (faviconCache) {
                FaviconCache found = faviconCache.get(key);
                if (found == null) {
                    found = new FaviconCache("image/png", org.b3log.solo.util.Favicons.render(source, width, height, SoloApplication.getWebResources()));
                    faviconCache.put(key, found);
                }
                icon = found;
            }
            context.setHeader("Cache-Control", "public, max-age=300");
            context.setHeader("X-Content-Type-Options", "nosniff");
            context.getResponse().setContentType(icon.getMediaFileType());
            context.sendBytes(icon.getData());
        } catch (Exception exception) {
            LOGGER.log(Level.ERROR, "Cannot render favicon", exception);
            context.sendError(500);
        }
    }

    static int snapFaviconDimension(final int requested) {
        int best = STANDARD_FAVICON_SIZES[0];
        int bestDiff = Math.abs(requested - best);
        for (int i = 1; i < STANDARD_FAVICON_SIZES.length; i++) {
            final int candidate = STANDARD_FAVICON_SIZES[i];
            final int diff = Math.abs(requested - candidate);
            if (diff <= bestDiff) {
                best = candidate;
                bestDiff = diff;
            }
        }
        return best;
    }

    /**
     * Gets blog information.
     * <ul>
     * <li>Time of the recent updated article</li>
     * <li>Article count</li>
     * <li>Comment count</li>
     * <li>Tag count</li>
     * <li>Serve path</li>
     * <li>Static serve path</li>
     * <li>Solo version</li>
     * <li>Runtime mode</li>
     * <li>Runtime database</li>
     * <li>Locale</li>
     * <li>Admin username</li>
     * </ul>
     *
     * @param context the specified context
     */
    @RequestProcessing(value = "/blog/info", method = HttpMethod.GET)
    public void getBlogInfo(final RequestContext context) {
        final JsonRenderer renderer = new JsonRenderer();
        context.setRenderer(renderer);
        final JSONObject jsonObject = new JSONObject();
        renderer.setJSONObject(jsonObject);

        jsonObject.put("recentArticleTime", articleQueryService.getRecentArticleTime());
        final JSONObject statistic = statisticQueryService.getStatistic();
        jsonObject.put("articleCount", statistic.getLong(Option.ID_T_STATISTIC_PUBLISHED_ARTICLE_COUNT));
        jsonObject.put("commentCount", statistic.getLong(Option.ID_T_STATISTIC_PUBLISHED_BLOG_COMMENT_COUNT));
        jsonObject.put("tagCount", tagQueryService.getTagCount());
        jsonObject.put("servePath", Latkes.getServePath());
        jsonObject.put("staticServePath", Latkes.getStaticServePath());
        jsonObject.put("version", SoloApplication.VERSION);
        jsonObject.put("runtimeMode", Latkes.getRuntimeMode());
        jsonObject.put("runtimeDatabase", Latkes.getRuntimeDatabase());
        jsonObject.put("locale", Latkes.getLocale());
        final String userName = userQueryService.getAdmin().optString(User.USER_NAME);
        jsonObject.put("userName", userName);
    }

    /**
     * Gets tags of all articles.
     * <p>
     * 
     * <pre>
     * {
     *     "data": [
     *         ["tag1", "tag2", ....], // tags of one article
     *         ["tagX", "tagY", ....], // tags of another article
     *         ....
     *     ]
     * }
     * </pre>
     * </p>
     *
     * @param context the specified context
     */
    @RequestProcessing(value = "/blog/articles-tags", method = HttpMethod.GET)
    public void getArticlesTags(final RequestContext context) {
        final JsonRenderer renderer = new JsonRenderer();
        context.setRenderer(renderer);
        final JSONObject ret = new JSONObject();
        renderer.setJSONObject(ret);

        final JSONArray data = new JSONArray();
        ret.put("data", data);
        ret.put(Keys.MSG, "SC_OK");

        try {
            List<JSONObject> articles = null == publicViewCache
                    ? null
                    : publicViewCache.getWidgetList("blogArticlesTags");
            if (null == articles) {
                articles = articleRepository.select(
                        "SELECT articleTags FROM " + articleRepository.getName() + " WHERE articleStatus = ?",
                        Article.ARTICLE_STATUS_C_PUBLISHED);
                if (null == articles) {
                    articles = Collections.emptyList();
                }
                if (null != publicViewCache) {
                    publicViewCache.putWidgetList("blogArticlesTags", articles);
                }
            }

            for (final JSONObject article : articles) {
                final String tagString = article.optString(Article.ARTICLE_TAGS_REF);
                final JSONArray tagArray = new JSONArray();
                data.put(tagArray);

                final String[] tags = tagString.split(",");
                for (final String tag : tags) {
                    final String trim = tag.trim();
                    if (StringUtils.isNotBlank(trim)) {
                        tagArray.put(tag);
                    }
                }
            }
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Gets articles tags failed", e);
        }
    }
}
