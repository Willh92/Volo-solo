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
import org.apache.commons.lang3.time.DateFormatUtils;
import org.apache.logging.log4j.Level;
import org.b3log.latke.Latkes;
import org.b3log.latke.http.HttpMethod;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.http.Response;
import org.b3log.latke.http.renderer.TextResponseRenderer;
import org.b3log.latke.http.renderer.TextXmlRenderer;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.ioc.Singleton;
import org.b3log.latke.repository.CompositeFilterOperator;
import org.b3log.latke.repository.FilterOperator;
import org.b3log.latke.repository.PropertyFilter;
import org.b3log.latke.repository.Query;
import org.b3log.latke.repository.SortDirection;
import org.b3log.latke.util.URLs;
import org.b3log.latke.util.XMLs;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.model.ArchiveDate;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Page;
import org.b3log.solo.model.Tag;
import org.b3log.solo.model.sitemap.Sitemap;
import org.b3log.solo.model.sitemap.URL;
import org.b3log.solo.repository.ArchiveDateRepository;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.repository.PageRepository;
import org.b3log.solo.repository.TagRepository;
import org.b3log.solo.service.IndexNowService;
import org.b3log.solo.service.SeriesAndBookshelfService;
import org.b3log.solo.service.SyndicationCacheService;
import org.b3log.solo.util.QueryResults;
import org.b3log.solo.web.HttpCompression;
import org.b3log.solo.web.PublicResourceHandler;
import org.b3log.solo.web.annotation.RequestProcessing;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Sitemap and IndexNow verification processor.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 0.3.1
 */
@Singleton
public class SitemapProcessor {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(SitemapProcessor.class);

    private static final String XML_CONTENT_TYPE = "text/xml; charset=UTF-8";
    private static final String PLAIN_TEXT_CONTENT_TYPE = "text/plain; charset=UTF-8";
    private static final byte[] EMPTY_BYTES = new byte[0];

    /**
     * Article repository.
     */
    @Inject
    private ArticleRepository articleRepository;

    /**
     * Page repository.
     */
    @Inject
    private PageRepository pageRepository;

    /**
     * Tag repository.
     */
    @Inject
    private TagRepository tagRepository;

    /**
     * Archive date repository.
     */
    @Inject
    private ArchiveDateRepository archiveDateRepository;

    @Inject
    private org.b3log.solo.repository.TagArticleRepository tagArticleRepository;

    @Inject
    private org.b3log.solo.repository.ArchiveDateArticleRepository archiveDateArticleRepository;

    @Inject
    private SyndicationCacheService syndicationCacheService;

    @Inject
    private SeriesAndBookshelfService seriesAndBookshelfService;

    @Inject
    private IndexNowService indexNowService;

    /**
     * Returns the sitemap with ETag / 304 conditional cache support.
     *
     * @param context the specified context
     */
    @RequestProcessing(value = "/sitemap.xml", method = {HttpMethod.GET, HttpMethod.HEAD})
    public void sitemap(final RequestContext context) {
        try {
            final SyndicationCacheService.Snapshot snapshot = syndicationCacheService != null
                    ? syndicationCacheService.getOrCompute(SyndicationCacheService.KEY_SITEMAP, this::buildSitemapXml)
                    : SyndicationCacheService.Snapshot.of(buildSitemapXml());

            HttpCompression.ensureVaryAcceptEncoding(context.getResponse());
            if (SyndicationCacheService.tryServeNotModified(context, snapshot, XML_CONTENT_TYPE)) {
                return;
            }

            final TextXmlRenderer renderer = new TextXmlRenderer();
            renderer.setContent(snapshot.xml());
            context.setRenderer(renderer);
            if ("HEAD".equalsIgnoreCase(context.method())) {
                context.getResponse().sendBytes(EMPTY_BYTES);
            } else if (PublicResourceHandler.acceptsGzip(context.header("Accept-Encoding"))) {
                HttpCompression.sendText(context, snapshot.xml(), XML_CONTENT_TYPE);
            } else {
                renderer.render(context);
            }
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Generates sitemap failed", e);

            context.sendError(503);
        }
    }

    /**
     * Serves the IndexNow key verification file {@code /{indexNowKey}.txt} when IndexNow is enabled
     * and the requested key matches the configured key in constant time; returns 404 otherwise.
     *
     * @param context the specified context
     */
    @RequestProcessing(value = "/{indexNowKey}.txt", method = HttpMethod.GET)
    public void indexNowKeyFile(final RequestContext context) {
        final String keyParam = context.pathVar("indexNowKey");
        if (indexNowService == null || !indexNowService.verifyKeyRequest(keyParam)) {
            context.sendError(404);
            return;
        }

        final Response response = context.getResponse();
        response.setContentType(PLAIN_TEXT_CONTENT_TYPE);
        response.setHeader("Cache-Control", SyndicationCacheService.CACHE_CONTROL_VALUE);
        final TextResponseRenderer renderer = new TextResponseRenderer(PLAIN_TEXT_CONTENT_TYPE);
        renderer.setContent(keyParam);
        context.setRenderer(renderer);
        renderer.render(context);
    }

    private String buildSitemapXml() throws Exception {
        final Sitemap sitemap = new Sitemap();
        final Set<String> seenLocs = new LinkedHashSet<>();

        // 1. Homepage first
        addUrlIfAbsent(sitemap, seenLocs, Latkes.getServePath() + "/", null);

        // 2. Public published articles (excluding password-protected & drafts)
        addArticles(sitemap, seenLocs);

        // 3. Local navigation pages (excluding external third-party URLs)
        addNavigations(sitemap, seenLocs);

        // 4. Local bookshelf chapter paths
        addBookshelfChapters(sitemap, seenLocs);

        // 5. Tags & archives
        addTags(sitemap, seenLocs);
        addArchives(sitemap, seenLocs);

        final String formatted = XMLs.format(sitemap.toString());
        LOGGER.log(Level.DEBUG, "Generated sitemap");
        return formatted;
    }

    /**
     * Adds public published articles into the specified sitemap.
     */
    private void addArticles(final Sitemap sitemap, final Set<String> seenLocs) throws Exception {
        if (articleRepository == null) {
            return;
        }
        final Query query = new Query()
                .setPage(1, Integer.MAX_VALUE)
                .setFilter(CompositeFilterOperator.and(
                        new PropertyFilter(Article.ARTICLE_STATUS, FilterOperator.EQUAL, Article.ARTICLE_STATUS_C_PUBLISHED),
                        new PropertyFilter(Article.ARTICLE_VIEW_PWD, FilterOperator.EQUAL, "")))
                .addSort(Article.ARTICLE_CREATED, SortDirection.DESCENDING)
                .select(Article.ARTICLE_PERMALINK, Article.ARTICLE_UPDATED, Article.ARTICLE_STATUS, Article.ARTICLE_VIEW_PWD)
                .setPageCount(1);
        final JSONObject articleResult = articleRepository.get(query);
        final JSONArray articles = QueryResults.array(articleResult);

        final String servePath = Latkes.getServePath();
        for (int i = 0; i < articles.length(); i++) {
            final JSONObject article = articles.optJSONObject(i);
            if (article == null) {
                continue;
            }
            if (article.optInt(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED) != Article.ARTICLE_STATUS_C_PUBLISHED
                    || StringUtils.isNotBlank(article.optString(Article.ARTICLE_VIEW_PWD))) {
                continue;
            }
            final String permalink = StringUtils.trimToEmpty(article.optString(Article.ARTICLE_PERMALINK));
            if (permalink.isEmpty()) {
                continue;
            }
            final String path = permalink.startsWith("/") ? permalink : ("/" + permalink);
            final long updated = article.optLong(Article.ARTICLE_UPDATED, 0L);
            final String lastMod = updated > 0L
                    ? DateFormatUtils.ISO_DATETIME_TIME_ZONE_FORMAT.format(updated)
                    : null;
            addUrlIfAbsent(sitemap, seenLocs, servePath + path, lastMod);
        }
    }

    /**
     * Adds internal navigations into the specified sitemap, skipping external third-party links.
     */
    private void addNavigations(final Sitemap sitemap, final Set<String> seenLocs) throws Exception {
        if (pageRepository == null) {
            return;
        }
        final JSONObject result = pageRepository.get(new Query().select(Page.PAGE_PERMALINK).setPageCount(1));
        final JSONArray pages = QueryResults.array(result);
        final String servePath = Latkes.getServePath();

        for (int i = 0; i < pages.length(); i++) {
            final JSONObject page = pages.optJSONObject(i);
            if (page == null) {
                continue;
            }
            final String rawPermalink = page.optString(Page.PAGE_PERMALINK, null);
            if (rawPermalink == null || rawPermalink.isBlank()) {
                continue;
            }
            final String permalink = rawPermalink.trim();
            final String loc;
            if (permalink.contains("://")) {
                if (permalink.startsWith(servePath + "/")) {
                    loc = permalink;
                } else {
                    continue;
                }
            } else {
                if (permalink.startsWith("//") || permalink.startsWith("#") || permalink.indexOf(':') >= 0) {
                    continue;
                }
                final String path = permalink.startsWith("/") ? permalink : ("/" + permalink);
                loc = servePath + path;
            }
            addUrlIfAbsent(sitemap, seenLocs, loc, null);
        }
    }

    /**
     * Adds local site-relative bookshelf chapter URLs into the specified sitemap.
     */
    private void addBookshelfChapters(final Sitemap sitemap, final Set<String> seenLocs) {
        if (seriesAndBookshelfService == null) {
            return;
        }
        try {
            final JSONArray books = seriesAndBookshelfService.getAllBooks();
            if (books == null || books.isEmpty()) {
                return;
            }
            final String servePath = Latkes.getServePath();
            for (int i = 0; i < books.length(); i++) {
                final JSONObject book = books.optJSONObject(i);
                if (book == null) {
                    continue;
                }
                final String bookId = StringUtils.trimToEmpty(book.optString("id"));
                if (bookId.isEmpty()) {
                    continue;
                }
                final JSONArray toc = seriesAndBookshelfService.getBookToc(bookId);
                collectBookshelfTocNodes(toc, servePath, sitemap, seenLocs);
            }
        } catch (final Exception e) {
            LOGGER.log(Level.WARN, "Appends bookshelf chapters to sitemap failed", e);
        }
    }

    private static void collectBookshelfTocNodes(final JSONArray nodes,
                                                 final String servePath,
                                                 final Sitemap sitemap,
                                                 final Set<String> seenLocs) {
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        for (int i = 0; i < nodes.length(); i++) {
            final JSONObject node = nodes.optJSONObject(i);
            if (node == null) {
                continue;
            }
            String chapterUrl = StringUtils.trimToEmpty(node.optString("url"));
            if (!chapterUrl.isEmpty()) {
                final int hashIdx = chapterUrl.indexOf('#');
                if (hashIdx >= 0) {
                    chapterUrl = chapterUrl.substring(0, hashIdx).trim();
                }
                if (chapterUrl.startsWith("/") && !chapterUrl.startsWith("//") && !chapterUrl.contains("..")) {
                    addUrlIfAbsent(sitemap, seenLocs, servePath + chapterUrl, null);
                } else if (!servePath.isEmpty() && chapterUrl.startsWith(servePath + "/")) {
                    addUrlIfAbsent(sitemap, seenLocs, chapterUrl, null);
                }
            }
            final JSONArray children = node.optJSONArray("children");
            if (children != null && !children.isEmpty()) {
                collectBookshelfTocNodes(children, servePath, sitemap, seenLocs);
            }
        }
    }

    /**
     * Adds tags (tag-articles) and tags wall (/tags.html) into the specified sitemap.
     */
    private void addTags(final Sitemap sitemap, final Set<String> seenLocs) throws Exception {
        if (tagRepository == null) {
            return;
        }
        final String servePath = Latkes.getServePath();

        if (articleRepository != null && tagArticleRepository != null
                && Latkes.RuntimeDatabase.NONE != Latkes.getRuntimeDatabase()) {
            final String sql = "SELECT t." + Tag.TAG_TITLE + " FROM " + tagRepository.getName() + " AS t"
                    + " WHERE EXISTS (SELECT 1 FROM " + tagArticleRepository.getName() + " AS rel"
                    + " INNER JOIN " + articleRepository.getName() + " AS a ON rel.article_oId = a.oId"
                    + " WHERE rel.tag_oId = t.oId AND a.articleStatus = ?)";
            final java.util.List<JSONObject> rows = tagRepository.select(sql, Article.ARTICLE_STATUS_C_PUBLISHED);
            if (rows != null) {
                for (final JSONObject tag : rows) {
                    if (tag == null) {
                        continue;
                    }
                    final String title = optColumnString(tag, Tag.TAG_TITLE);
                    if (StringUtils.isBlank(title)) {
                        continue;
                    }
                    addUrlIfAbsent(sitemap, seenLocs, servePath + "/tags/" + URLs.encode(title), null);
                }
            }
        } else {
            final JSONObject result = tagRepository.get(new Query().select(Tag.TAG_TITLE).setPageCount(1));
            final JSONArray tags = QueryResults.array(result);
            for (int i = 0; i < tags.length(); i++) {
                final JSONObject tag = tags.optJSONObject(i);
                if (tag == null) {
                    continue;
                }
                if (tag.has(Tag.TAG_T_PUBLISHED_REFERENCE_COUNT)
                        && tag.optInt(Tag.TAG_T_PUBLISHED_REFERENCE_COUNT) <= 0) {
                    continue;
                }
                final String title = tag.optString(Tag.TAG_TITLE);
                if (StringUtils.isBlank(title)) {
                    continue;
                }
                addUrlIfAbsent(sitemap, seenLocs, servePath + "/tags/" + URLs.encode(title), null);
            }
        }

        // Tags wall
        addUrlIfAbsent(sitemap, seenLocs, servePath + "/tags.html", null);
    }

    /**
     * Adds archives (archive-articles) into the specified sitemap.
     */
    private void addArchives(final Sitemap sitemap, final Set<String> seenLocs) throws Exception {
        if (archiveDateRepository == null) {
            return;
        }
        final String servePath = Latkes.getServePath();

        if (articleRepository != null && archiveDateArticleRepository != null
                && Latkes.RuntimeDatabase.NONE != Latkes.getRuntimeDatabase()) {
            final String sql = "SELECT d." + ArchiveDate.ARCHIVE_TIME + " FROM "
                    + archiveDateRepository.getName() + " AS d"
                    + " WHERE EXISTS (SELECT 1 FROM " + archiveDateArticleRepository.getName() + " AS rel"
                    + " INNER JOIN " + articleRepository.getName() + " AS a ON rel.article_oId = a.oId"
                    + " WHERE rel.archiveDate_oId = d.oId AND a.articleStatus = ?)";
            final java.util.List<JSONObject> rows = archiveDateRepository.select(sql, Article.ARTICLE_STATUS_C_PUBLISHED);
            if (rows != null) {
                for (final JSONObject archiveDate : rows) {
                    if (archiveDate == null) {
                        continue;
                    }
                    final long time = optColumnLong(archiveDate, ArchiveDate.ARCHIVE_TIME);
                    if (time <= 0L) {
                        continue;
                    }
                    final String dateString = DateFormatUtils.format(time, "yyyy/MM");
                    addUrlIfAbsent(sitemap, seenLocs, servePath + "/archives/" + dateString, null);
                }
            }
        } else {
            final JSONObject result = archiveDateRepository.get(new Query().select(ArchiveDate.ARCHIVE_TIME).setPageCount(1));
            final JSONArray archiveDates = QueryResults.array(result);
            for (int i = 0; i < archiveDates.length(); i++) {
                final JSONObject archiveDate = archiveDates.optJSONObject(i);
                if (archiveDate == null) {
                    continue;
                }
                if (archiveDate.has(ArchiveDate.ARCHIVE_DATE_T_PUBLISHED_ARTICLE_COUNT)
                        && archiveDate.optInt(ArchiveDate.ARCHIVE_DATE_T_PUBLISHED_ARTICLE_COUNT) <= 0) {
                    continue;
                }
                final long time = archiveDate.optLong(ArchiveDate.ARCHIVE_TIME, 0L);
                if (time <= 0L) {
                    continue;
                }
                final String dateString = DateFormatUtils.format(time, "yyyy/MM");
                addUrlIfAbsent(sitemap, seenLocs, servePath + "/archives/" + dateString, null);
            }
        }
    }

    private static String optColumnString(final JSONObject row, final String columnName) {
        if (row.has(columnName)) {
            return row.optString(columnName);
        }
        for (final String key : row.keySet()) {
            if (columnName.equalsIgnoreCase(key)) {
                return row.optString(key);
            }
        }
        return "";
    }

    private static long optColumnLong(final JSONObject row, final String columnName) {
        if (row.has(columnName)) {
            return row.optLong(columnName, 0L);
        }
        for (final String key : row.keySet()) {
            if (columnName.equalsIgnoreCase(key)) {
                return row.optLong(key, 0L);
            }
        }
        return 0L;
    }

    /**
     * Deduplicates all {@code <loc>} entries across the entire sitemap; {@link URL#toString()}
     * escapes {@code loc} via {@code StringEscapeUtils.escapeXml11}.
     */
    private static void addUrlIfAbsent(final Sitemap sitemap,
                                       final Set<String> seenLocs,
                                       final String rawLoc,
                                       final String lastMod) {
        if (StringUtils.isBlank(rawLoc)) {
            return;
        }
        final String loc = rawLoc.trim();
        if (!seenLocs.add(loc)) {
            return;
        }
        final URL url = new URL();
        url.setLoc(loc);
        if (StringUtils.isNotBlank(lastMod)) {
            url.setLastMod(lastMod);
        }
        sitemap.addURL(url);
    }
}
