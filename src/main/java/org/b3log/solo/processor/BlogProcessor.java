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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.b3log.solo.web.WebResources;
import org.b3log.latke.http.Response;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.ioc.Inject;
import org.apache.logging.log4j.Level;
import org.b3log.solo.logging.Logger;
import org.b3log.latke.model.Pagination;
import org.b3log.latke.model.User;
import org.b3log.latke.http.HttpMethod;
import org.b3log.latke.http.RequestContext;
import org.b3log.solo.web.annotation.RequestProcessing;
import org.b3log.latke.ioc.Singleton;
import org.b3log.latke.http.renderer.JsonRenderer;
import org.b3log.solo.SoloApplication;
import org.b3log.solo.bolo.prop.FaviconCache;
import org.b3log.solo.bolo.tool.EditIMG;
import org.b3log.solo.bolo.tool.MediaFileUtil;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Option;
import org.b3log.solo.service.ArticleQueryService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.service.StatisticQueryService;
import org.b3log.solo.service.TagQueryService;
import org.b3log.solo.service.UserQueryService;
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
        JSONArray repositories = new JSONArray();
        final JSONObject option = optionQueryService.getOptionById(Option.ID_C_GITHUB_REPOS);
        if (option != null) {
            try { repositories = new JSONArray(option.optString(Option.OPTION_VALUE, "[]")); }
            catch (org.json.JSONException ignored) { }
        }
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

    /**
     * Article query service.
     */
    @Inject
    private ArticleQueryService articleQueryService;

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
     * The cache of favicon.
     */
    HashMap<String, FaviconCache> faviconCache = new HashMap<>();

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
    @RequestProcessing(value = "/manifest.json", method = HttpMethod.GET)
    public void getPWAManifestJSON(final RequestContext context) {
        final JsonRenderer renderer = new JsonRenderer();
        renderer.setPretty(true);
        context.setRenderer(renderer);
        final JSONObject preference = optionQueryService.getPreference();
        if (null == preference) {
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
    }

    /**
     * Get an edited image by the website favicon.
     *
     * @param context the specified context
     */
    @RequestProcessing(value = "/favicon/{width}/{height}", method = HttpMethod.GET)
    public void getFavicon(final RequestContext context) {
        final int width, height;
        try {
            width = Integer.parseInt(context.pathVar("width"));
            height = Integer.parseInt(context.pathVar("height"));
            if (width < 16 || height < 16 || width > 512 || height > 512) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            context.setStatus(400);
            context.renderJSON(new JSONObject().put("sc", false).put("msg", "图标尺寸应介于 16 和 512 像素。"));
            return;
        }
        final JSONObject preference = optionQueryService.getPreference();
        final String source = preference == null ? "" : preference.optString(Option.ID_C_FAVICON_URL);
        final String key = source + ":" + width + ":" + height;
        try {
            final FaviconCache icon;
            synchronized (faviconCache) {
                FaviconCache found = faviconCache.get(key);
                if (found == null) {
                    found = new FaviconCache("image/png", org.b3log.solo.util.Favicons.render(source, width, height, SoloApplication.getWebResources()));
                    if (faviconCache.size() >= 64) faviconCache.clear();
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
        final JSONObject requestJSONObject = new JSONObject();
        requestJSONObject.put(Pagination.PAGINATION_CURRENT_PAGE_NUM, 1);
        requestJSONObject.put(Pagination.PAGINATION_PAGE_SIZE, Integer.MAX_VALUE);
        requestJSONObject.put(Pagination.PAGINATION_WINDOW_SIZE, Integer.MAX_VALUE);
        requestJSONObject.put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED);

        final JSONArray excludes = new JSONArray();

        excludes.put(Article.ARTICLE_CONTENT);
        excludes.put(Article.ARTICLE_UPDATED);
        excludes.put(Article.ARTICLE_CREATED);
        excludes.put(Article.ARTICLE_AUTHOR_ID);
        excludes.put(Article.ARTICLE_RANDOM_DOUBLE);

        requestJSONObject.put(Keys.EXCLUDES, excludes);

        final JSONObject result = articleQueryService.getArticles(requestJSONObject);
        final JSONArray articles = result.optJSONArray(Article.ARTICLES);

        final JsonRenderer renderer = new JsonRenderer();
        context.setRenderer(renderer);
        final JSONObject ret = new JSONObject();
        renderer.setJSONObject(ret);

        final JSONArray data = new JSONArray();
        ret.put("data", data);

        for (int i = 0; i < articles.length(); i++) {
            final JSONObject article = articles.optJSONObject(i);
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
    }
}
