/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.service;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.repository.CompositeFilterOperator;
import org.b3log.latke.repository.FilterOperator;
import org.b3log.latke.repository.PropertyFilter;
import org.b3log.latke.repository.Query;
import org.b3log.latke.service.ServiceException;
import org.b3log.latke.service.annotation.Service;
import org.b3log.solo.cache.PublicViewCache;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Option;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.util.OptionDocuments;
import org.b3log.solo.util.OutboundUrls;
import org.b3log.solo.util.QueryResults;
import org.b3log.solo.util.Solos;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.owasp.encoder.Encode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages ordered article series ({@code voloSeries}) and the embedded multi-book catalog &amp; reader
 * ({@code voloBooks} + {@code voloBookToc.<bookId>}).
 * Supports SSRF-safe TOC extraction from GitBook 3 / HonKit, VuePress 1.x / 2.x / VitePress, MdBook,
 * Docusaurus, plain index pages, or internal Volo article series.
 *
 * @author <a href="https://github.com/Willh92">Willh92</a>
 * @since 3.0.0
 */
@Service
public class SeriesAndBookshelfService {

    private static final Logger LOGGER = Logger.getLogger(SeriesAndBookshelfService.class);

    private static final String CATEGORY_SERIES = "voloSeries";
    private static final String CATEGORY_BOOKS = "voloBooks";
    private static final int MAX_TOC_NODES = 600;
    private static final int MAX_TOC_DEPTH = 4;
    private static final int FETCH_MAX_BYTES = 2 * 1024 * 1024;

    private static final Pattern CODE_FENCE_PATTERN = Pattern.compile("^\\s*(`{3,}|~{3,})");
    private static final Pattern BOOKSHELF_SHORTCODE = Pattern.compile("^\\s*:::bookshelf(?:\\s*:::)?\\s*$");
    private static final Pattern BOOK_SHORTCODE = Pattern.compile("^\\s*:::book\\s+([^\\r\\n]*?)(?:\\s*:::)?\\s*$");
    private static final Pattern CLOSE_FENCE = Pattern.compile("^\\s*:::\\s*$");
    private static final Pattern ATTR_PATTERN = Pattern.compile("([a-zA-Z]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s:]+))");
    private static final Pattern SAFE_ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9_-]{1,15}$");

    @Inject
    private OptionQueryService optionQueryService;

    @Inject
    private OptionMgmtService optionMgmtService;

    @Inject
    private ArticleRepository articleRepository;

    @Inject
    private SyndicationCacheService syndicationCacheService;

    @Inject
    private PublicViewCache publicViewCache;

    private void invalidateSyndicationCache() {
        if (null != syndicationCacheService) {
            syndicationCacheService.invalidate();
        }
        if (null != publicViewCache) {
            publicViewCache.invalidateAll();
        }
    }

    private volatile JSONArray cachedSeries;
    private volatile JSONArray cachedBooks;
    private final ConcurrentHashMap<String, JSONArray> cachedBookTocs = new ConcurrentHashMap<>();

    /**
     * Clears cached series, books, and chapter trees so subsequent reads reload fresh documents from storage.
     */
    public synchronized void reloadFromStorage() {
        cachedSeries = null;
        cachedBooks = null;
        cachedBookTocs.clear();
        invalidateSyndicationCache();
    }

    public static boolean containsShortcode(final String markdown) {
        return markdown != null && (markdown.contains(":::bookshelf") || markdown.contains(":::book"));
    }

    /**
     * Strips {@code :::bookshelf:::} and {@code :::book ... :::} shortcodes for plain-text excerpts and search indexing.
     */
    public static String stripForExcerpt(final String rawMarkdown) {
        return replaceShortcodesStatic(rawMarkdown, "");
    }

    /**
     * Replaces {@code :::bookshelf:::} and {@code :::book ... :::} shortcodes with a clean notice for RSS/Atom feeds.
     */
    public static String stripForFeed(final String rawMarkdown) {
        return replaceShortcodesStatic(rawMarkdown, "\n\n> 在线书架阅读组件，请前往原文浏览目录与章节。\n\n");
    }

    private static String replaceShortcodesStatic(final String rawMarkdown, final String replacement) {
        if (!containsShortcode(rawMarkdown)) {
            return rawMarkdown == null ? "" : rawMarkdown;
        }
        final String[] lines = rawMarkdown.split("\r?\n", -1);
        final StringBuilder out = new StringBuilder(rawMarkdown.length());
        boolean inCodeFence = false;
        char fenceChar = 0;
        int fenceLen = 0;
        boolean skipUntilClosingColons = false;
        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i];
            final Matcher fenceMatcher = CODE_FENCE_PATTERN.matcher(line);
            if (fenceMatcher.find()) {
                final String ticks = fenceMatcher.group(1);
                if (!inCodeFence) {
                    inCodeFence = true;
                    fenceChar = ticks.charAt(0);
                    fenceLen = ticks.length();
                } else if (ticks.charAt(0) == fenceChar && ticks.length() >= fenceLen && line.trim().equals(ticks)) {
                    inCodeFence = false;
                }
            }
            if (!inCodeFence) {
                if (skipUntilClosingColons) {
                    if (CLOSE_FENCE.matcher(line).matches()) {
                        skipUntilClosingColons = false;
                    }
                    continue;
                }
                if (BOOKSHELF_SHORTCODE.matcher(line).matches()) {
                    out.append(replacement);
                    if ((!line.trim().endsWith(":::") || line.trim().equals(":::bookshelf"))
                            && i + 1 < lines.length && CLOSE_FENCE.matcher(lines[i + 1]).matches()) {
                        skipUntilClosingColons = true;
                    }
                    continue;
                }
                if (BOOK_SHORTCODE.matcher(line).matches()) {
                    out.append(replacement);
                    if (i + 1 < lines.length && CLOSE_FENCE.matcher(lines[i + 1]).matches()) {
                        skipUntilClosingColons = true;
                    }
                    continue;
                }
            }
            out.append(line);
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    // ========================== Ordered Article Series (#3) ==========================

    public JSONArray getAllSeries() {
        final JSONArray local = cachedSeries;
        if (local != null) {
            return cloneJsonArray(local);
        }
        try {
            final String raw = OptionDocuments.read(optionQueryService, Option.ID_C_VOLO_SERIES, "[]");
            final JSONArray parsed = new JSONArray(raw);
            cachedSeries = parsed;
            return cloneJsonArray(parsed);
        } catch (final Exception e) {
            LOGGER.log(Level.WARN, "Reads voloSeries failed", e);
            return new JSONArray();
        }
    }

    public synchronized JSONObject saveSeries(final JSONObject input) throws ServiceException {
        final String title = StringUtils.trimToEmpty(input.optString("title"));
        if (title.isEmpty() || title.length() > 120) {
            throw new ServiceException("系列标题不能为空且长度不超过 120 字符。");
        }
        final String description = StringUtils.left(StringUtils.trimToEmpty(input.optString("description")), 500);
        String id = normalizeShortId(input.optString("id"));
        if (id.isEmpty()) {
            id = generateShortId();
        }
        final JSONArray rawArticleIds = input.optJSONArray("articleIds");
        final List<String> dedupedIds = new ArrayList<>();
        if (rawArticleIds != null) {
            for (int i = 0; i < rawArticleIds.length(); i++) {
                final String aid = StringUtils.trimToEmpty(rawArticleIds.optString(i));
                if (!aid.isEmpty() && aid.length() <= 64 && !dedupedIds.contains(aid)) {
                    dedupedIds.add(aid);
                }
            }
        }

        final JSONArray all = getAllSeries();
        final JSONObject target = new JSONObject()
                .put("id", id)
                .put("title", title)
                .put("description", description)
                .put("articleIds", new JSONArray(dedupedIds));
        boolean replaced = false;
        for (int i = 0; i < all.length(); i++) {
            if (id.equals(all.optJSONObject(i).optString("id"))) {
                all.put(i, target);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            all.put(target);
        }
        persistSeries(all);
        return target;
    }

    public synchronized void deleteSeries(final String seriesId) throws ServiceException {
        if (StringUtils.isBlank(seriesId)) {
            return;
        }
        final JSONArray all = getAllSeries();
        final JSONArray next = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            final JSONObject item = all.optJSONObject(i);
            if (item != null && !seriesId.equals(item.optString("id"))) {
                next.put(item);
            }
        }
        persistSeries(next);
    }

    public String getSeriesIdByArticleId(final String articleId) {
        if (StringUtils.isBlank(articleId)) {
            return "";
        }
        final JSONArray local = cachedSeries;
        final JSONArray all = local != null ? local : getAllSeries();
        for (int i = 0; i < all.length(); i++) {
            final JSONObject s = all.optJSONObject(i);
            final JSONArray ids = s == null ? null : s.optJSONArray("articleIds");
            if (ids == null) {
                continue;
            }
            for (int j = 0; j < ids.length(); j++) {
                if (articleId.equals(ids.optString(j))) {
                    return s.optString("id");
                }
            }
        }
        return "";
    }

    /**
     * Binds an article to a target series (or removes it from all series when {@code seriesId} is blank).
     */
    public synchronized void assignArticleSeries(final String articleId, final String seriesId) {
        if (StringUtils.isBlank(articleId)) {
            return;
        }
        final String targetId = StringUtils.trimToEmpty(seriesId);
        final JSONArray all = getAllSeries();
        boolean changed = false;
        for (int i = 0; i < all.length(); i++) {
            final JSONObject s = all.optJSONObject(i);
            if (s == null) {
                continue;
            }
            final String sid = s.optString("id");
            final JSONArray ids = s.optJSONArray("articleIds");
            final List<String> list = new ArrayList<>();
            if (ids != null) {
                for (int j = 0; j < ids.length(); j++) {
                    list.add(ids.optString(j));
                }
            }
            if (sid.equals(targetId)) {
                if (!list.contains(articleId)) {
                    list.add(articleId);
                    s.put("articleIds", new JSONArray(list));
                    changed = true;
                }
            } else if (list.remove(articleId)) {
                s.put("articleIds", new JSONArray(list));
                changed = true;
            }
        }
        if (changed) {
            try {
                persistSeries(all);
            } catch (final Exception e) {
                LOGGER.log(Level.WARN, "Updates article series membership failed [articleId=" + articleId + "]", e);
            }
        }
    }

    public void removeArticleFromAllSeries(final String articleId) {
        assignArticleSeries(articleId, "");
    }

    /**
     * Renders the `.volo-series-box` navigation widget for a published article if it belongs to a series.
     */
    public String renderSeriesBoxHtml(final String articleId) {
        if (StringUtils.isBlank(articleId) || articleRepository == null) {
            return "";
        }
        final JSONArray local = cachedSeries;
        final JSONArray all = local != null ? local : getAllSeries();
        for (int i = 0; i < all.length(); i++) {
            final JSONObject s = all.optJSONObject(i);
            if (s == null) {
                continue;
            }
            final JSONArray ids = s.optJSONArray("articleIds");
            if (ids == null || ids.isEmpty()) {
                continue;
            }
            boolean containsTarget = false;
            for (int j = 0; j < ids.length(); j++) {
                if (articleId.equals(ids.optString(j))) {
                    containsTarget = true;
                    break;
                }
            }
            if (!containsTarget) {
                continue;
            }
            final List<JSONObject> publishedItems = loadPublishedSeriesArticlesInOrder(ids);
            int currentIdx = -1;
            for (int k = 0; k < publishedItems.size(); k++) {
                if (articleId.equals(publishedItems.get(k).optString(Keys.OBJECT_ID))) {
                    currentIdx = k;
                    break;
                }
            }
            if (currentIdx < 0 || publishedItems.isEmpty()) {
                return "";
            }
            final String title = s.optString("title", "专题系列");
            final String desc = s.optString("description", "");
            final StringBuilder sb = new StringBuilder(512);
            sb.append("<aside class=\"volo-series-box\" aria-label=\"系列文章目录\">")
                    .append("<div class=\"volo-series-box__head\">")
                    .append("<div class=\"volo-series-box__title-wrap\">")
                    .append("<span class=\"volo-series-box__badge\">专题系列</span>")
                    .append("<strong class=\"volo-series-box__title\">").append(Encode.forHtml(title)).append("</strong>")
                    .append("</div>")
                    .append("<span class=\"volo-series-box__progress\">第 ").append(currentIdx + 1)
                    .append(" / ").append(publishedItems.size()).append(" 篇</span>")
                    .append("</div>");
            if (StringUtils.isNotBlank(desc)) {
                sb.append("<p class=\"volo-series-box__desc\">").append(Encode.forHtml(desc)).append("</p>");
            }
            sb.append("<details class=\"volo-series-box__toc\" open>")
                    .append("<summary>展开 / 收起系列目录（共 ").append(publishedItems.size()).append(" 篇）</summary>")
                    .append("<ol class=\"volo-series-box__list\">");
            for (int k = 0; k < publishedItems.size(); k++) {
                final JSONObject item = publishedItems.get(k);
                final String href = Latkes.getServePath() + item.optString(Article.ARTICLE_PERMALINK);
                final String itemTitle = item.optString(Article.ARTICLE_TITLE);
                if (k == currentIdx) {
                    sb.append("<li class=\"is-active\" aria-current=\"page\"><span>")
                            .append(Encode.forHtml(itemTitle)).append("（当前阅读）</span></li>");
                } else {
                    sb.append("<li><a href=\"").append(Encode.forHtmlAttribute(href)).append("\">")
                            .append(Encode.forHtml(itemTitle)).append("</a></li>");
                }
            }
            sb.append("</ol></details>");
            if (publishedItems.size() > 1) {
                sb.append("<div class=\"volo-series-box__nav\">");
                if (currentIdx > 0) {
                    final JSONObject prev = publishedItems.get(currentIdx - 1);
                    sb.append("<a class=\"volo-series-box__nav-link\" href=\"")
                            .append(Encode.forHtmlAttribute(Latkes.getServePath() + prev.optString(Article.ARTICLE_PERMALINK)))
                            .append("\">&larr; 上一篇：").append(Encode.forHtml(prev.optString(Article.ARTICLE_TITLE))).append("</a>");
                } else {
                    sb.append("<span></span>");
                }
                if (currentIdx + 1 < publishedItems.size()) {
                    final JSONObject next = publishedItems.get(currentIdx + 1);
                    sb.append("<a class=\"volo-series-box__nav-link\" href=\"")
                            .append(Encode.forHtmlAttribute(Latkes.getServePath() + next.optString(Article.ARTICLE_PERMALINK)))
                            .append("\">下一篇：").append(Encode.forHtml(next.optString(Article.ARTICLE_TITLE))).append(" &rarr;</a>");
                }
                sb.append("</div>");
            }
            sb.append("</aside>");
            return sb.toString();
        }
        return "";
    }

    private void persistSeries(final JSONArray series) throws ServiceException {
        optionMgmtService.storeDocument(Option.ID_C_VOLO_SERIES, CATEGORY_SERIES, series.toString());
        cachedSeries = cloneJsonArray(series);
        invalidateSyndicationCache();
    }

    // ========================== Embedded Bookshelf & Reader (#16) ==========================

    public JSONArray listBooks() {
        return getAllBooks();
    }

    public JSONArray getAllBooks() {
        final JSONArray local = cachedBooks;
        if (local != null) {
            return cloneJsonArray(local);
        }
        try {
            final String raw = OptionDocuments.read(optionQueryService, Option.ID_C_VOLO_BOOKS, "[]");
            final JSONArray parsed = new JSONArray(raw);
            cachedBooks = parsed;
            return cloneJsonArray(parsed);
        } catch (final Exception e) {
            LOGGER.log(Level.WARN, "Reads voloBooks failed", e);
            return new JSONArray();
        }
    }

    public JSONArray getBookToc(final String bookId) {
        final String cleanId = normalizeShortId(bookId);
        if (cleanId.isEmpty()) {
            return new JSONArray();
        }
        final JSONArray cached = cachedBookTocs.get(cleanId);
        if (cached != null) {
            return cloneJsonArray(cached);
        }
        try {
            final String raw = OptionDocuments.read(optionQueryService, Option.ID_C_VOLO_BOOK_TOC_PREFIX + cleanId, "[]");
            final JSONArray parsed = new JSONArray(raw);
            cachedBookTocs.put(cleanId, parsed);
            return cloneJsonArray(parsed);
        } catch (final Exception e) {
            LOGGER.log(Level.WARN, "Reads voloBookToc failed [bookId=" + cleanId + "]", e);
            return new JSONArray();
        }
    }

    public synchronized JSONObject saveBook(final JSONObject input) throws ServiceException {
        final String title = StringUtils.trimToEmpty(input.optString("title"));
        if (title.isEmpty() || title.length() > 120) {
            throw new ServiceException("书籍标题不能为空且长度不超过 120 字符。");
        }
        String id = normalizeShortId(input.optString("id"));
        if (id.isEmpty()) {
            id = generateShortId();
        }
        final String description = StringUtils.left(StringUtils.trimToEmpty(input.optString("description")), 500);
        final String coverUrl = StringUtils.trimToEmpty(input.optString("coverUrl"));
        if (!coverUrl.isEmpty() && !OutboundUrls.isOptionalSafeIconOrAvatar(coverUrl)) {
            throw new ServiceException("封面地址必须为安全的 http(s) 链接或站内相对路径。");
        }
        final String sourceType = normalizeSourceType(input.optString("sourceType", "auto"));
        final String sourceUrl = StringUtils.trimToEmpty(input.optString("sourceUrl"));
        final String seriesId = StringUtils.trimToEmpty(input.optString("seriesId"));
        final String embedMode = "link".equalsIgnoreCase(input.optString("embedMode")) ? "link" : "iframe";

        if (!"series".equals(sourceType) && !"manual".equals(sourceType)) {
            if (!OutboundUrls.isHttpOrHttps(sourceUrl)) {
                throw new ServiceException("外部书籍源地址必须是合法的 http:// 或 https:// URL。");
            }
        } else if ("series".equals(sourceType) && seriesId.isEmpty()) {
            throw new ServiceException("绑定站内系列时请选择对应系列。");
        }

        final JSONArray all = getAllBooks();
        JSONObject existing = null;
        int existingIndex = -1;
        for (int i = 0; i < all.length(); i++) {
            final JSONObject b = all.optJSONObject(i);
            if (b != null && id.equals(b.optString("id"))) {
                existing = b;
                existingIndex = i;
                break;
            }
        }

        int chapterCount = existing == null ? 0 : existing.optInt("chapterCount", 0);
        long syncedAt = existing == null ? 0L : existing.optLong("syncedAt", 0L);

        if (input.has("toc")) {
            final JSONArray sanitizedToc = sanitizeTocTree(input.optJSONArray("toc"), new int[]{0}, 0);
            saveBookToc(id, sanitizedToc);
            chapterCount = countTocChapters(sanitizedToc);
            syncedAt = System.currentTimeMillis();
        } else if ("series".equals(sourceType)) {
            final JSONArray seriesToc = buildTocFromSeries(seriesId);
            saveBookToc(id, seriesToc);
            chapterCount = countTocChapters(seriesToc);
            syncedAt = System.currentTimeMillis();
        }

        final JSONObject saved = new JSONObject()
                .put("id", id)
                .put("title", title)
                .put("description", description)
                .put("coverUrl", coverUrl)
                .put("sourceType", sourceType)
                .put("sourceUrl", sourceUrl)
                .put("seriesId", seriesId)
                .put("embedMode", embedMode)
                .put("chapterCount", chapterCount)
                .put("syncedAt", syncedAt);

        if (existingIndex >= 0) {
            all.put(existingIndex, saved);
        } else {
            all.put(saved);
        }
        persistBooks(all);
        return saved;
    }

    public synchronized void deleteBook(final String bookId) throws ServiceException {
        final String cleanId = normalizeShortId(bookId);
        if (cleanId.isEmpty()) {
            return;
        }
        final JSONArray all = getAllBooks();
        final JSONArray next = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            final JSONObject b = all.optJSONObject(i);
            if (b != null && !cleanId.equals(b.optString("id"))) {
                next.put(b);
            }
        }
        persistBooks(next);
        cachedBookTocs.remove(cleanId);
        try {
            optionMgmtService.removeOption(Option.ID_C_VOLO_BOOK_TOC_PREFIX + cleanId);
        } catch (final Exception ignored) {
        }
        invalidateSyndicationCache();
    }

    /**
     * Fetches and parses the remote chapter tree for a book using SSRF-safe {@link OutboundUrls}.
     */
    public synchronized JSONObject syncBookToc(final String bookId, final boolean allowLoopback) throws ServiceException {
        final String cleanId = normalizeShortId(bookId);
        if (cleanId.isEmpty()) {
            throw new ServiceException("无效的书籍 ID。");
        }
        final JSONArray all = getAllBooks();
        JSONObject book = null;
        int bookIdx = -1;
        for (int i = 0; i < all.length(); i++) {
            final JSONObject candidate = all.optJSONObject(i);
            if (candidate != null && cleanId.equals(candidate.optString("id"))) {
                book = candidate;
                bookIdx = i;
                break;
            }
        }
        if (book == null) {
            throw new ServiceException("未找到指定书籍。");
        }

        final String sourceType = book.optString("sourceType", "auto");
        JSONArray toc;
        if ("series".equals(sourceType)) {
            toc = buildTocFromSeries(book.optString("seriesId"));
        } else if ("manual".equals(sourceType)) {
            toc = getBookToc(cleanId);
        } else {
            final String sourceUrl = book.optString("sourceUrl");
            try {
                final byte[] bytes = OutboundUrls.fetchBytes(
                        sourceUrl, 5000, 12000, FETCH_MAX_BYTES,
                        "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8",
                        Solos.USER_AGENT, allowLoopback);
                final String html = new String(bytes, StandardCharsets.UTF_8);
                toc = parseHtmlDocumentToToc(html, sourceUrl, sourceType, allowLoopback);
            } catch (final Exception e) {
                LOGGER.log(Level.WARN, "Syncs remote book TOC failed [bookId=" + cleanId + ", url=" + sourceUrl + "]", e);
                throw new ServiceException("同步章节目录失败：" + e.getMessage());
            }
        }

        toc = sanitizeTocTree(toc, new int[]{0}, 0);
        saveBookToc(cleanId, toc);
        final int count = countTocChapters(toc);
        book.put("chapterCount", count);
        book.put("syncedAt", System.currentTimeMillis());
        all.put(bookIdx, book);
        persistBooks(all);
        return new JSONObject().put("book", book).put("toc", toc).put("chapterCount", count);
    }

    /**
     * Parses GitBook 3 / HonKit, VuePress 1.x / 2.x / VitePress, MdBook, Docusaurus, or generic HTML index into a nested chapter tree.
     */
    public static JSONArray parseHtmlToToc(final String html, final String baseUrl, final String sourceType) {
        return parseHtmlDocumentToToc(html, baseUrl, sourceType, false);
    }

    private static JSONArray parseHtmlDocumentToToc(final String html, final String baseUrl,
                                                    final String sourceType, final boolean allowLoopback) {
        final Document doc = Jsoup.parse(html == null ? "" : html, baseUrl);
        final URI baseUri = URI.create(baseUrl);
        final Set<String> seenUrls = new LinkedHashSet<>();

        // 1. GitBook 3 / HonKit: .book-summary ul.summary
        if ("auto".equals(sourceType) || "gitbook".equals(sourceType)) {
            final Element summary = doc.selectFirst(".book-summary ul.summary, nav[role=navigation] ul.summary");
            if (summary != null) {
                final JSONArray parsed = parseNestedList(summary, baseUri, seenUrls, 0);
                if (!parsed.isEmpty()) {
                    return parsed;
                }
            }
        }

        // 2. MdBook: nav#sidebar ol.chapter
        if ("auto".equals(sourceType) || "mdbook".equals(sourceType)) {
            final Element mdChapter = doc.selectFirst("nav#sidebar ol.chapter, .sidebar-scrollbox ol.chapter");
            if (mdChapter != null) {
                final JSONArray parsed = parseNestedList(mdChapter, baseUri, seenUrls, 0);
                if (!parsed.isEmpty()) {
                    return parsed;
                }
            }
        }

        // 3. VuePress 1.x / 2.x / VitePress / Docusaurus sidebar
        if ("auto".equals(sourceType) || "vuepress".equals(sourceType)) {
            final Element vpSidebar = doc.selectFirst(
                    "aside.sidebar ul.sidebar-links, aside.vp-sidebar, .VPNavScreenMenu, .theme-doc-sidebar-menu");
            if (vpSidebar != null) {
                final JSONArray parsed = parseVuePressSidebar(vpSidebar, baseUri, seenUrls, 0);
                if (!parsed.isEmpty()) {
                    return parsed;
                }
            }
            // VuePress hero homepage fallback: follow action button or extract navbar dropdowns + content links
            final Element actionBtn = doc.selectFirst("a.action-button[href], .hero a.nav-link[href], .VPButton.brand[href]");
            if (actionBtn != null) {
                final String entryHref = resolveSafeChapterUrl(actionBtn.absUrl("href"), baseUri);
                if (!entryHref.isEmpty() && isSameHost(entryHref, baseUri) && !entryHref.equals(baseUrl)) {
                    try {
                        final byte[] subBytes = OutboundUrls.fetchBytes(
                                entryHref, 4000, 8000, FETCH_MAX_BYTES,
                                "text/html,application/xhtml+xml", Solos.USER_AGENT, allowLoopback);
                        final Document subDoc = Jsoup.parse(new String(subBytes, StandardCharsets.UTF_8), entryHref);
                        final Element subSidebar = subDoc.selectFirst("aside.sidebar ul.sidebar-links, aside.vp-sidebar, .theme-doc-sidebar-menu");
                        if (subSidebar != null) {
                            final JSONArray subParsed = parseVuePressSidebar(subSidebar, URI.create(entryHref), seenUrls, 0);
                            if (!subParsed.isEmpty()) {
                                return subParsed;
                            }
                        }
                    } catch (final Exception ignored) {
                    }
                }
            }
            final Element navLinks = doc.selectFirst("header.navbar nav.nav-links, .VPNavBarMenu");
            if (navLinks != null) {
                final JSONArray navParsed = parseVuePressNavbar(navLinks, baseUri, seenUrls);
                if (!navParsed.isEmpty()) {
                    return navParsed;
                }
            }
        }

        // 4. Generic fallback: structured lists or links in main content
        final Element contentRoot = doc.selectFirst(".theme-default-content, .markdown-section, main, article, body");
        if (contentRoot != null) {
            final JSONArray generic = new JSONArray();
            for (final Element link : contentRoot.select("a[href]")) {
                if (generic.length() >= MAX_TOC_NODES) {
                    break;
                }
                final String title = cleanChapterTitle(link.text());
                final String url = resolveSafeChapterUrl(link.absUrl("href"), baseUri);
                if (title.isEmpty() || url.isEmpty() || isIgnoredBrandingLink(url, title) || !isSameHost(url, baseUri)) {
                    continue;
                }
                if (seenUrls.add(url)) {
                    generic.put(new JSONObject().put("title", title).put("url", url).put("children", new JSONArray()));
                }
            }
            return generic;
        }
        return new JSONArray();
    }

    private static JSONArray parseNestedList(final Element listEl, final URI baseUri,
                                             final Set<String> seenUrls, final int depth) {
        final JSONArray result = new JSONArray();
        if (listEl == null || depth > MAX_TOC_DEPTH) {
            return result;
        }
        for (final Element li : listEl.children()) {
            if (!"li".equalsIgnoreCase(li.tagName()) || li.hasClass("divider")) {
                continue;
            }
            final Element directLink = li.selectFirst("> a[href]");
            final Element directSpan = directLink == null ? li.selectFirst("> span, > p, > strong") : null;
            String title = directLink != null ? cleanChapterTitle(directLink.text())
                    : (directSpan != null ? cleanChapterTitle(directSpan.text()) : "");
            String url = directLink != null ? resolveSafeChapterUrl(directLink.absUrl("href"), baseUri) : "";
            if (isIgnoredBrandingLink(url, title)) {
                continue;
            }
            if (!url.isEmpty()) {
                seenUrls.add(url);
            }
            final Element childList = li.selectFirst("> ul, > ol");
            final JSONArray children = childList != null
                    ? parseNestedList(childList, baseUri, seenUrls, depth + 1)
                    : new JSONArray();
            if (title.isEmpty() && children.isEmpty()) {
                continue;
            }
            if (title.isEmpty()) {
                title = "章节分组";
            }
            result.put(new JSONObject()
                    .put("title", title)
                    .put("url", url)
                    .put("children", children));
        }
        return result;
    }

    private static JSONArray parseVuePressSidebar(final Element container, final URI baseUri,
                                                  final Set<String> seenUrls, final int depth) {
        final JSONArray result = new JSONArray();
        if (container == null || depth > MAX_TOC_DEPTH) {
            return result;
        }
        final Elements items = "ul".equalsIgnoreCase(container.tagName())
                ? container.children()
                : container.select("> ul > li, > section, > .sidebar-group");
        for (final Element item : items) {
            final Element groupHeading = item.selectFirst("> .sidebar-group > .sidebar-heading, > section > .sidebar-heading, > .sidebar-heading");
            final Element groupList = item.selectFirst("> .sidebar-group ul.sidebar-group-items, > section ul.sidebar-group-items, > ul.sidebar-links, > ul.sidebar-sub-headers, > ul");
            final Element directLink = item.selectFirst("> a.sidebar-link[href], > a[href]");
            if (groupHeading != null) {
                final String groupTitle = cleanChapterTitle(groupHeading.text());
                final Element groupAnchor = "a".equalsIgnoreCase(groupHeading.tagName()) ? groupHeading : groupHeading.selectFirst("a[href]");
                final String groupUrl = groupAnchor != null ? resolveSafeChapterUrl(groupAnchor.absUrl("href"), baseUri) : "";
                final JSONArray children = groupList != null
                        ? parseVuePressSidebar(groupList, baseUri, seenUrls, depth + 1)
                        : new JSONArray();
                if (!groupTitle.isEmpty() || !children.isEmpty()) {
                    result.put(new JSONObject()
                            .put("title", groupTitle.isEmpty() ? "章节分组" : groupTitle)
                            .put("url", groupUrl)
                            .put("children", children));
                }
            } else if (directLink != null) {
                final String title = cleanChapterTitle(directLink.text());
                final String url = resolveSafeChapterUrl(directLink.absUrl("href"), baseUri);
                if (!title.isEmpty() && !url.isEmpty() && !isIgnoredBrandingLink(url, title)) {
                    seenUrls.add(url);
                    final JSONArray sub = groupList != null
                            ? parseVuePressSidebar(groupList, baseUri, seenUrls, depth + 1)
                            : new JSONArray();
                    result.put(new JSONObject().put("title", title).put("url", url).put("children", sub));
                }
            }
        }
        return result;
    }

    private static JSONArray parseVuePressNavbar(final Element navLinks, final URI baseUri, final Set<String> seenUrls) {
        final JSONArray result = new JSONArray();
        for (final Element item : navLinks.select("> .nav-item")) {
            final Element dropdownTitle = item.selectFirst(".dropdown-title .title, .dropdown-title");
            final Elements dropdownLinks = item.select(".nav-dropdown a.nav-link[href], .nav-dropdown a[href]");
            if (dropdownTitle != null && !dropdownLinks.isEmpty()) {
                final JSONArray children = new JSONArray();
                for (final Element a : dropdownLinks) {
                    final String t = cleanChapterTitle(a.text());
                    final String u = resolveSafeChapterUrl(a.absUrl("href"), baseUri);
                    if (!t.isEmpty() && !u.isEmpty() && isSameHost(u, baseUri) && seenUrls.add(u)) {
                        children.put(new JSONObject().put("title", t).put("url", u).put("children", new JSONArray()));
                    }
                }
                if (!children.isEmpty()) {
                    result.put(new JSONObject()
                            .put("title", cleanChapterTitle(dropdownTitle.text()))
                            .put("url", "")
                            .put("children", children));
                }
            } else {
                final Element a = item.selectFirst("a.nav-link[href], a[href]");
                if (a != null) {
                    final String t = cleanChapterTitle(a.text());
                    final String u = resolveSafeChapterUrl(a.absUrl("href"), baseUri);
                    if (!t.isEmpty() && !u.isEmpty() && isSameHost(u, baseUri) && seenUrls.add(u)) {
                        result.put(new JSONObject().put("title", t).put("url", u).put("children", new JSONArray()));
                    }
                }
            }
        }
        return result;
    }

    /**
     * Replaces {@code :::bookshelf:::} and {@code :::book id="...":::} shortcodes BEFORE Markdown rendering
     * using safe tokens, then restores interactive HTML after Markdown rendering.
     */
    public record ShortcodeSlot(int index, String bookId) {
    }

    public record ShortcodePreprocess(String markdownWithTokens, String tokenPrefix, List<ShortcodeSlot> slots) {
    }

    public ShortcodePreprocess preprocessShortcodes(final String rawMarkdown) {
        if (!containsShortcode(rawMarkdown)) {
            return new ShortcodePreprocess(rawMarkdown == null ? "" : rawMarkdown, "", List.of());
        }
        final String tokenPrefix = deterministicTokenPrefix(rawMarkdown);
        final List<ShortcodeSlot> slots = new ArrayList<>();
        final StringBuilder out = new StringBuilder(rawMarkdown.length());
        final String[] lines = rawMarkdown.split("\r?\n", -1);

        boolean inCodeFence = false;
        char fenceChar = 0;
        int fenceLen = 0;
        boolean skipUntilClosingColons = false;

        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i];
            final Matcher fenceMatcher = CODE_FENCE_PATTERN.matcher(line);
            if (fenceMatcher.find()) {
                final String ticks = fenceMatcher.group(1);
                if (!inCodeFence) {
                    inCodeFence = true;
                    fenceChar = ticks.charAt(0);
                    fenceLen = ticks.length();
                } else if (ticks.charAt(0) == fenceChar && ticks.length() >= fenceLen && line.trim().equals(ticks)) {
                    inCodeFence = false;
                }
            }

            if (!inCodeFence) {
                if (skipUntilClosingColons) {
                    if (CLOSE_FENCE.matcher(line).matches()) {
                        skipUntilClosingColons = false;
                    }
                    continue;
                }
                if (BOOKSHELF_SHORTCODE.matcher(line).matches()) {
                    final int idx = slots.size();
                    slots.add(new ShortcodeSlot(idx, ""));
                    out.append("\n\n").append(shortcodeToken(tokenPrefix, idx)).append("\n\n");
                    if (!line.trim().endsWith(":::") || line.trim().equals(":::bookshelf")) {
                        // Support both single-line :::bookshelf::: and block :::bookshelf \n :::
                        if (i + 1 < lines.length && CLOSE_FENCE.matcher(lines[i + 1]).matches()) {
                            skipUntilClosingColons = true;
                        }
                    }
                    continue;
                }
                final Matcher bookMatcher = BOOK_SHORTCODE.matcher(line);
                if (bookMatcher.matches()) {
                    final String attrs = bookMatcher.group(1);
                    String bookId = "";
                    final Matcher attrMatcher = ATTR_PATTERN.matcher(attrs);
                    while (attrMatcher.find()) {
                        final String k = attrMatcher.group(1).toLowerCase(Locale.ROOT);
                        if ("id".equals(k) || "book".equals(k)) {
                            bookId = firstNonNull(attrMatcher.group(2), attrMatcher.group(3), attrMatcher.group(4));
                        }
                    }
                    final int idx = slots.size();
                    slots.add(new ShortcodeSlot(idx, normalizeShortId(bookId)));
                    out.append("\n\n").append(shortcodeToken(tokenPrefix, idx)).append("\n\n");
                    if (i + 1 < lines.length && CLOSE_FENCE.matcher(lines[i + 1]).matches()) {
                        skipUntilClosingColons = true;
                    }
                    continue;
                }
            }

            out.append(line);
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return new ShortcodePreprocess(out.toString(), tokenPrefix, slots);
    }

    public String applyShortcodeSlots(final String renderedHtml, final ShortcodePreprocess prep) {
        if (prep == null || prep.slots().isEmpty() || StringUtils.isEmpty(renderedHtml)) {
            return renderedHtml;
        }
        String html = renderedHtml;
        for (final ShortcodeSlot slot : prep.slots()) {
            final String token = shortcodeToken(prep.tokenPrefix(), slot.index());
            final String widgetHtml = renderBookshelfHtml(slot.bookId());
            final String pWrapped = "<p>" + token + "</p>";
            if (html.contains(pWrapped)) {
                html = html.replace(pWrapped, widgetHtml);
            } else {
                html = html.replace(token, widgetHtml);
            }
        }
        return html;
    }

    public String renderBookshelfHtml(final String filterBookId) {
        final JSONArray localBooks = cachedBooks;
        final JSONArray allBooks = localBooks != null ? localBooks : getAllBooks();
        final List<JSONObject> selected = new ArrayList<>();
        for (int i = 0; i < allBooks.length(); i++) {
            final JSONObject b = allBooks.optJSONObject(i);
            if (b == null) {
                continue;
            }
            if (StringUtils.isBlank(filterBookId) || filterBookId.equals(b.optString("id"))) {
                selected.add(b);
            }
        }
        if (selected.isEmpty()) {
            return "<div class=\"volo-bookshelf volo-bookshelf--empty\">"
                    + "<p>暂未配置电子书目录，站长可在「后台 &rarr; 工具 &rarr; 在线书架与系列」中添加并同步书籍章节。</p>"
                    + "</div>";
        }

        final StringBuilder sb = new StringBuilder(2048);
        sb.append("<section class=\"volo-bookshelf\" data-volo-bookshelf=\"true\">")
                .append("<div class=\"volo-bookshelf__toolbar\">")
                .append("<div class=\"volo-bookshelf__heading\">")
                .append("<span class=\"volo-bookshelf__badge\">在线书架</span>")
                .append("<strong>共 ").append(selected.size()).append(" 本专栏与书籍</strong>")
                .append("</div>")
                .append("<input type=\"search\" class=\"volo-bookshelf__filter\" placeholder=\"搜索书籍或章节标题...\" aria-label=\"搜索书籍或章节标题\" />")
                .append("</div>")
                .append("<div class=\"volo-bookshelf__grid\">");

        for (final JSONObject book : selected) {
            final String id = book.optString("id");
            final String title = book.optString("title");
            final String desc = book.optString("description");
            final String coverUrl = book.optString("coverUrl");
            final String sourceUrl = book.optString("sourceUrl");
            final String embedMode = book.optString("embedMode", "iframe");
            final String sourceType = book.optString("sourceType", "auto");
            final String seriesId = book.optString("seriesId");
            final JSONArray toc = ("series".equalsIgnoreCase(sourceType) && StringUtils.isNotBlank(seriesId) && articleRepository != null)
                    ? buildTocFromSeries(seriesId)
                    : getBookToc(id);
            final int count = toc.isEmpty() ? book.optInt("chapterCount", 0) : countTocChapters(toc);

            sb.append("<article class=\"volo-book-card\" data-book-id=\"").append(Encode.forHtmlAttribute(id))
                    .append("\" data-book-title=\"").append(Encode.forHtmlAttribute(title))
                    .append("\" data-embed-mode=\"").append(Encode.forHtmlAttribute(embedMode)).append("\">")
                    .append("<div class=\"volo-book-card__head\">");
            if (StringUtils.isNotBlank(coverUrl) && OutboundUrls.isOptionalSafeIconOrAvatar(coverUrl)) {
                sb.append("<img class=\"volo-book-card__cover\" src=\"").append(Encode.forHtmlAttribute(coverUrl))
                        .append("\" alt=\"\" loading=\"lazy\" />");
            }
            sb.append("<div class=\"volo-book-card__meta\">")
                    .append("<h3 class=\"volo-book-card__title\">").append(Encode.forHtml(title)).append("</h3>");
            if (StringUtils.isNotBlank(desc)) {
                sb.append("<p class=\"volo-book-card__desc\">").append(Encode.forHtml(desc)).append("</p>");
            }
            sb.append("<div class=\"volo-book-card__stats\">")
                    .append("<span>").append(count).append(" 个章节</span>");
            if (StringUtils.isNotBlank(sourceUrl) && OutboundUrls.isHttpOrHttps(sourceUrl)) {
                sb.append("<a href=\"").append(Encode.forHtmlAttribute(sourceUrl))
                        .append("\" target=\"_blank\" rel=\"noopener noreferrer\" class=\"volo-book-card__ext\">源站主页 &nearr;</a>");
            }
            sb.append("<button type=\"button\" class=\"volo-book-card__resume\" data-volo-book-resume=\"")
                    .append(Encode.forHtmlAttribute(id)).append("\" hidden>继续上次阅读</button>")
                    .append("</div></div></div>");

            if (!toc.isEmpty()) {
                sb.append("<details class=\"volo-book-card__catalog\" ").append(selected.size() == 1 ? "open" : "").append(">")
                        .append("<summary>查看章节目录（点击章节即可站内沉浸阅读）</summary>")
                        .append(renderTocTreeHtml(id, title, embedMode, toc))
                        .append("</details>");
            } else if (StringUtils.isNotBlank(sourceUrl) && OutboundUrls.isHttpOrHttps(sourceUrl)) {
                sb.append("<div class=\"volo-book-card__direct\">")
                        .append("<button type=\"button\" class=\"volo-lock-btn\" data-volo-chapter-url=\"")
                        .append(Encode.forHtmlAttribute(sourceUrl)).append("\" data-volo-book-id=\"")
                        .append(Encode.forHtmlAttribute(id)).append("\" data-volo-chapter-title=\"")
                        .append(Encode.forHtmlAttribute(title)).append("\">站内打开阅读器</button>")
                        .append("</div>");
            }
            sb.append("</article>");
        }
        sb.append("</div>");

        // Embedded split-screen / fullscreen reader stage
        sb.append("<div class=\"volo-book-reader\" data-volo-book-reader=\"true\" hidden>")
                .append("<div class=\"volo-book-reader__bar\">")
                .append("<div class=\"volo-book-reader__crumb\">")
                .append("<button type=\"button\" class=\"volo-book-reader__btn\" data-volo-reader-toggle-toc=\"true\">目录栏</button>")
                .append("<strong class=\"volo-book-reader__title\" data-volo-reader-title=\"true\">章节阅读</strong>")
                .append("</div>")
                .append("<div class=\"volo-book-reader__actions\">")
                .append("<button type=\"button\" class=\"volo-book-reader__btn\" data-volo-reader-prev=\"true\">&larr; 上一节</button>")
                .append("<button type=\"button\" class=\"volo-book-reader__btn\" data-volo-reader-next=\"true\">下一节 &rarr;</button>")
                .append("<button type=\"button\" class=\"volo-book-reader__btn\" data-volo-reader-fullscreen=\"true\">全屏阅读</button>")
                .append("<a class=\"volo-book-reader__btn\" data-volo-reader-external=\"true\" href=\"#\" target=\"_blank\" rel=\"noopener noreferrer\">新窗口打开 &nearr;</a>")
                .append("<button type=\"button\" class=\"volo-book-reader__btn volo-book-reader__btn--close\" data-volo-reader-close=\"true\">收起阅读器 &times;</button>")
                .append("</div></div>")
                .append("<div class=\"volo-book-reader__body\">")
                .append("<nav class=\"volo-book-reader__sidebar\" data-volo-reader-sidebar=\"true\" aria-label=\"当前书籍目录\"></nav>")
                .append("<div class=\"volo-book-reader__stage\">")
                .append("<iframe class=\"volo-book-reader__iframe\" data-volo-reader-iframe=\"true\" title=\"在线书籍章节阅读器\" loading=\"lazy\" referrerpolicy=\"strict-origin-when-cross-origin\"></iframe>")
                .append("</div></div></div>")
                .append("</section>");
        return sb.toString();
    }

    private static String renderTocTreeHtml(final String bookId, final String bookTitle,
                                            final String embedMode, final JSONArray nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        sb.append("<ul class=\"volo-book-toc\">");
        for (int i = 0; i < nodes.length(); i++) {
            final JSONObject node = nodes.optJSONObject(i);
            if (node == null) {
                continue;
            }
            final String title = node.optString("title");
            final String url = node.optString("url");
            final JSONArray children = node.optJSONArray("children");
            sb.append("<li class=\"volo-book-toc__item\" data-toc-title=\"")
                    .append(Encode.forHtmlAttribute(title.toLowerCase(Locale.ROOT))).append("\">");
            if (StringUtils.isNotBlank(url)) {
                sb.append("<a href=\"").append(Encode.forHtmlAttribute(url)).append("\" class=\"volo-book-toc__link\"")
                        .append(" data-volo-book-id=\"").append(Encode.forHtmlAttribute(bookId)).append("\"")
                        .append(" data-volo-book-name=\"").append(Encode.forHtmlAttribute(bookTitle)).append("\"")
                        .append(" data-volo-embed-mode=\"").append(Encode.forHtmlAttribute(embedMode)).append("\"")
                        .append(" data-volo-chapter-url=\"").append(Encode.forHtmlAttribute(url)).append("\"")
                        .append(" data-volo-chapter-title=\"").append(Encode.forHtmlAttribute(title)).append("\">")
                        .append(Encode.forHtml(title)).append("</a>");
            } else {
                sb.append("<span class=\"volo-book-toc__group\">").append(Encode.forHtml(title)).append("</span>");
            }
            if (children != null && !children.isEmpty()) {
                sb.append(renderTocTreeHtml(bookId, bookTitle, embedMode, children));
            }
            sb.append("</li>");
        }
        sb.append("</ul>");
        return sb.toString();
    }

    private JSONArray buildTocFromSeries(final String seriesId) {
        final JSONArray result = new JSONArray();
        if (StringUtils.isBlank(seriesId) || articleRepository == null) {
            return result;
        }
        final JSONArray local = cachedSeries;
        final JSONArray allSeries = local != null ? local : getAllSeries();
        for (int i = 0; i < allSeries.length(); i++) {
            final JSONObject s = allSeries.optJSONObject(i);
            if (s != null && seriesId.equals(s.optString("id"))) {
                final JSONArray ids = s.optJSONArray("articleIds");
                if (ids == null) {
                    break;
                }
                final List<JSONObject> publishedItems = loadPublishedSeriesArticlesInOrder(ids);
                for (final JSONObject art : publishedItems) {
                    result.put(new JSONObject()
                            .put("title", art.optString(Article.ARTICLE_TITLE))
                            .put("url", Latkes.getServePath() + art.optString(Article.ARTICLE_PERMALINK))
                            .put("children", new JSONArray()));
                }
                break;
            }
        }
        return result;
    }

    private List<JSONObject> loadPublishedSeriesArticlesInOrder(final JSONArray ids) {
        if (ids == null || ids.isEmpty() || articleRepository == null) {
            return Collections.emptyList();
        }
        final LinkedHashSet<String> orderedIds = new LinkedHashSet<>(ids.length() * 2);
        for (int j = 0; j < ids.length(); j++) {
            final String aid = StringUtils.trimToEmpty(ids.optString(j));
            if (!aid.isEmpty()) {
                orderedIds.add(aid);
            }
        }
        if (orderedIds.isEmpty()) {
            return Collections.emptyList();
        }
        final Map<String, JSONObject> byId = new HashMap<>(orderedIds.size() * 2);
        try {
            final Query query = new Query()
                    .setFilter(CompositeFilterOperator.and(
                            new PropertyFilter(Keys.OBJECT_ID, FilterOperator.IN, orderedIds),
                            new PropertyFilter(Article.ARTICLE_STATUS, FilterOperator.EQUAL, Article.ARTICLE_STATUS_C_PUBLISHED)))
                    .select(Keys.OBJECT_ID, Article.ARTICLE_TITLE, Article.ARTICLE_PERMALINK, Article.ARTICLE_STATUS)
                    .setPageCount(1);
            final JSONObject queryResult = articleRepository.get(query);
            if (queryResult != null) {
                final JSONArray rows = QueryResults.array(queryResult);
                for (int k = 0; k < rows.length(); k++) {
                    final JSONObject art = rows.optJSONObject(k);
                    if (art != null && art.optInt(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED) == Article.ARTICLE_STATUS_C_PUBLISHED) {
                        final String oid = art.optString(Keys.OBJECT_ID);
                        if (!oid.isEmpty()) {
                            byId.put(oid, art);
                        }
                    }
                }
            } else {
                for (final String aid : orderedIds) {
                    try {
                        final JSONObject art = articleRepository.get(aid);
                        if (art != null && art.optInt(Article.ARTICLE_STATUS) == Article.ARTICLE_STATUS_C_PUBLISHED) {
                            byId.put(aid, art);
                        }
                    } catch (final Exception ignored) {
                    }
                }
            }
        } catch (final Exception e) {
            LOGGER.log(Level.DEBUG, "Batch loads series articles failed", e);
        }
        final List<JSONObject> publishedItems = new ArrayList<>(byId.size());
        for (final String aid : orderedIds) {
            final JSONObject art = byId.get(aid);
            if (art != null) {
                publishedItems.add(art);
            }
        }
        return publishedItems;
    }

    private void persistBooks(final JSONArray books) throws ServiceException {
        optionMgmtService.storeDocument(Option.ID_C_VOLO_BOOKS, CATEGORY_BOOKS, books.toString());
        cachedBooks = cloneJsonArray(books);
        invalidateSyndicationCache();
    }

    private void saveBookToc(final String bookId, final JSONArray toc) throws ServiceException {
        optionMgmtService.storeDocument(Option.ID_C_VOLO_BOOK_TOC_PREFIX + bookId, CATEGORY_BOOKS, toc.toString());
        cachedBookTocs.put(bookId, cloneJsonArray(toc));
        invalidateSyndicationCache();
    }

    private static JSONArray cloneJsonArray(final JSONArray src) {
        return src == null || src.isEmpty() ? new JSONArray() : Solos.cloneArray(src);
    }

    private static JSONObject cloneJsonObjectDeep(final JSONObject src) {
        return Solos.clone(src);
    }

    private static JSONArray sanitizeTocTree(final JSONArray raw, final int[] counter, final int depth) {
        final JSONArray clean = new JSONArray();
        if (raw == null || depth > MAX_TOC_DEPTH || counter[0] >= MAX_TOC_NODES) {
            return clean;
        }
        for (int i = 0; i < raw.length() && counter[0] < MAX_TOC_NODES; i++) {
            final JSONObject item = raw.optJSONObject(i);
            if (item == null) {
                continue;
            }
            final String title = cleanChapterTitle(item.optString("title"));
            String url = StringUtils.trimToEmpty(item.optString("url"));
            if (!url.isEmpty() && !isSafeChapterTarget(url)) {
                url = "";
            }
            final JSONArray children = sanitizeTocTree(item.optJSONArray("children"), counter, depth + 1);
            if (title.isEmpty() && children.isEmpty()) {
                continue;
            }
            counter[0]++;
            clean.put(new JSONObject()
                    .put("title", title.isEmpty() ? "未命名章节" : title)
                    .put("url", url)
                    .put("children", children));
        }
        return clean;
    }

    private static String deterministicTokenPrefix(final String rawMarkdown) {
        try {
            final byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((rawMarkdown == null ? "" : rawMarkdown).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest).substring(0, 24).toUpperCase(Locale.ROOT);
        } catch (final Exception e) {
            return UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        }
    }

    private static int countTocChapters(final JSONArray toc) {
        if (toc == null) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < toc.length(); i++) {
            final JSONObject item = toc.optJSONObject(i);
            if (item == null) {
                continue;
            }
            if (StringUtils.isNotBlank(item.optString("url"))) {
                total++;
            }
            total += countTocChapters(item.optJSONArray("children"));
        }
        return total;
    }

    private static boolean isSafeChapterTarget(final String url) {
        if (url.startsWith("/") && !url.startsWith("//") && !url.contains("..")) {
            return true;
        }
        return OutboundUrls.isHttpOrHttps(url);
    }

    private static String resolveSafeChapterUrl(final String absUrl, final URI baseUri) {
        if (StringUtils.isBlank(absUrl)) {
            return "";
        }
        final String trimmed = absUrl.trim();
        if (OutboundUrls.isHttpOrHttps(trimmed)) {
            return trimmed;
        }
        try {
            final URI resolved = baseUri.resolve(trimmed);
            final String str = resolved.toString();
            return OutboundUrls.isHttpOrHttps(str) ? str : "";
        } catch (final Exception e) {
            return "";
        }
    }

    private static boolean isSameHost(final String url, final URI baseUri) {
        try {
            final URI u = URI.create(url);
            return baseUri.getHost() != null && baseUri.getHost().equalsIgnoreCase(u.getHost());
        } catch (final Exception e) {
            return false;
        }
    }

    private static boolean isIgnoredBrandingLink(final String url, final String title) {
        final String lowerUrl = url == null ? "" : url.toLowerCase(Locale.ROOT);
        final String lowerTitle = title == null ? "" : title.toLowerCase(Locale.ROOT);
        return lowerUrl.contains("gitbook.com")
                || lowerTitle.contains("published with gitbook")
                || lowerTitle.contains("本书使用 gitbook");
    }

    private static String cleanChapterTitle(final String raw) {
        if (raw == null) {
            return "";
        }
        return StringUtils.left(raw.replaceAll("\\s+", " ").trim(), 160);
    }

    private static String normalizeShortId(final String raw) {
        if (raw == null) {
            return "";
        }
        final String cleaned = raw.trim().toLowerCase(Locale.ROOT);
        return SAFE_ID_PATTERN.matcher(cleaned).matches() ? cleaned : "";
    }

    private static String generateShortId() {
        return "b" + UUID.randomUUID().toString().replace("-", "").substring(0, 7);
    }

    private static String normalizeSourceType(final String raw) {
        if (raw == null) {
            return "auto";
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "gitbook", "vuepress", "mdbook", "series", "manual" -> raw.trim().toLowerCase(Locale.ROOT);
            default -> "auto";
        };
    }

    private static String shortcodeToken(final String prefix, final int index) {
        return "VOLOBOOKSLOT" + prefix + "IDX" + index + "END";
    }

    private static String firstNonNull(final String a, final String b, final String c) {
        if (a != null) {
            return a;
        }
        if (b != null) {
            return b;
        }
        return c == null ? "" : c;
    }
}
