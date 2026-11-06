/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.service;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.apache.logging.log4j.Level;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.repository.CompositeFilter;
import org.b3log.latke.repository.CompositeFilterOperator;
import org.b3log.latke.repository.FilterOperator;
import org.b3log.latke.repository.PropertyFilter;
import org.b3log.latke.repository.Query;
import org.b3log.latke.repository.SortDirection;
import org.b3log.latke.service.annotation.Service;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.model.Article;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.util.QueryResults;
import org.json.JSONArray;
import org.json.JSONObject;
import org.owasp.encoder.Encode;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single-flight bidirectional internal link (backlinks) index for public articles.
 * <p>
 * Security invariant: password-protected articles ({@code articleViewPwd != ""}), draft articles,
 * and locked blocks ({@code :::lock ... :::}) stripped via {@link Article#sanitizePublicMarkdown(String)}
 * NEVER emit or receive public backlinks.
 * </p>
 *
 * @author <a href="https://github.com/Willh92">Willh92</a>
 * @since 3.0.0
 */
@Service
public class BacklinkService {

    private static final Logger LOGGER = Logger.getLogger(BacklinkService.class);

    private static final int MAX_BACKLINKS_PER_ARTICLE = 10;
    private static final int SCAN_PAGE_SIZE = 200;
    private static final int CONTENT_FETCH_BATCH_SIZE = 50;
    private static final Pattern CODE_FENCE_PATTERN = Pattern.compile("^\\s*(`{3,}|~{3,})");
    private static final Pattern INLINE_CODE_PATTERN = Pattern.compile("`+[^`\\r\\n]+`+");
    private static final Pattern MD_INLINE_LINK_PATTERN = Pattern.compile("(?<!!)\\[[^\\]\\r\\n]*\\]\\(\\s*([^)\\r\\n]+?)\\s*\\)");
    private static final Pattern MD_REF_DEF_PATTERN = Pattern.compile("(?m)^\\s{0,3}\\[[^\\]\\r\\n]+\\]:\\s*<?([^\\s>\"'\\r\\n]+)>?");
    private static final Pattern HTML_HREF_PATTERN = Pattern.compile("(?i)\\bhref\\s*=\\s*(?:\"([^\"]+)\"|'([^']+)'|([^\\s>\"']+))");

    /**
     * Public backlink summary item linking a referring public article to the target article.
     */
    public record BacklinkItem(String oId, String title, String permalink, long updated) {
    }

    /**
     * Incrementally cached outgoing link targets for a public article keyed by {@code articleUpdated}.
     */
    private record CachedOutgoing(long updated, String title, String permalink, Set<String> refs) {
    }

    private record MetaRow(String oId, String title, String permalink, long updated) {
    }

    @Inject
    private ArticleRepository articleRepository;

    private final Object buildLock = new Object();
    private final ConcurrentHashMap<String, CachedOutgoing> outgoingCache = new ConcurrentHashMap<>();
    private volatile Map<String, List<BacklinkItem>> snapshot;
    private volatile Map<String, List<BacklinkItem>> lastGoodSnapshot = Collections.emptyMap();
    private final AtomicLong generation = new AtomicLong(0L);

    void setArticleRepository(final ArticleRepository articleRepository) {
        this.articleRepository = articleRepository;
    }

    /**
     * Invalidates the in-memory backlink graph snapshot after article mutations.
     */
    public void invalidate() {
        generation.incrementAndGet();
        snapshot = null;
    }

    /**
     * Completely resets all backlink caches and snapshots (used after full backup restore or bulk database replacement).
     */
    public void invalidateAll() {
        generation.incrementAndGet();
        snapshot = null;
        synchronized (buildLock) {
            generation.incrementAndGet();
            outgoingCache.clear();
            snapshot = null;
            lastGoodSnapshot = Collections.emptyMap();
        }
    }

    /**
     * Returns up to {@value #MAX_BACKLINKS_PER_ARTICLE} public articles that link to {@code article},
     * sorted by {@code updated} descending.
     */
    public List<BacklinkItem> getBacklinksForArticle(final JSONObject article) {
        if (article == null) {
            return List.of();
        }
        if (article.optInt(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED) != Article.ARTICLE_STATUS_C_PUBLISHED
                || StringUtils.isNotBlank(article.optString(Article.ARTICLE_VIEW_PWD))) {
            return List.of();
        }
        final String articleId = StringUtils.trimToEmpty(article.optString(Keys.OBJECT_ID));
        if (articleId.isEmpty()) {
            return List.of();
        }
        final Map<String, List<BacklinkItem>> current = getOrBuildSnapshot();
        return current.getOrDefault(articleId, List.of());
    }

    /**
     * Renders semantic backlinks HTML ({@code aside.volo-backlinks-box}) for {@code article},
     * or returns {@code ""} when there are no incoming public backlinks.
     */
    public String renderBacklinksHtml(final JSONObject article) {
        final List<BacklinkItem> items = getBacklinksForArticle(article);
        if (items.isEmpty()) {
            return "";
        }
        final String servePath = StringUtils.trimToEmpty(Latkes.getServePath());
        final StringBuilder sb = new StringBuilder(256 + items.size() * 160);
        sb.append("<aside class=\"volo-backlinks-box\" aria-label=\"提及本文的文章\">\n")
                .append("  <div class=\"volo-backlinks-box__header\">\n")
                .append("    <span class=\"volo-backlinks-box__title\">提及本文的文章</span>\n")
                .append("    <span class=\"volo-backlinks-box__count\">").append(items.size()).append(" 篇关联引用</span>\n")
                .append("  </div>\n")
                .append("  <ul class=\"volo-backlinks-box__list\">\n");
        for (final BacklinkItem item : items) {
            final String permalink = item.permalink().startsWith("/") ? item.permalink() : ("/" + item.permalink());
            final String href = servePath + permalink;
            final String dateText = item.updated() > 0L
                    ? DateFormatUtils.format(item.updated(), "yyyy-MM-dd")
                    : "";
            sb.append("    <li class=\"volo-backlinks-box__item\">\n")
                    .append("      <a class=\"volo-backlinks-box__link\" href=\"")
                    .append(Encode.forHtmlAttribute(href)).append("\">")
                    .append(Encode.forHtml(item.title())).append("</a>\n")
                    .append("      <time class=\"volo-backlinks-box__date\">")
                    .append(Encode.forHtml(dateText)).append("</time>\n")
                    .append("    </li>\n");
        }
        sb.append("  </ul>\n")
                .append("</aside>");
        return sb.toString();
    }

    private Map<String, List<BacklinkItem>> getOrBuildSnapshot() {
        final Map<String, List<BacklinkItem>> current = snapshot;
        if (current != null) {
            return current;
        }
        if (articleRepository == null) {
            return Collections.emptyMap();
        }
        synchronized (buildLock) {
            final Map<String, List<BacklinkItem>> recheck = snapshot;
            if (recheck != null) {
                return recheck;
            }
            final long genBefore = generation.get();
            try {
                final Map<String, List<BacklinkItem>> built = buildSnapshot();
                lastGoodSnapshot = built;
                if (generation.get() == genBefore) {
                    snapshot = built;
                    if (generation.get() != genBefore) {
                        snapshot = null;
                    }
                }
                return built;
            } catch (final Exception e) {
                LOGGER.log(Level.WARN, "Builds article backlink snapshot failed", e);
                return lastGoodSnapshot;
            }
        }
    }

    private Map<String, List<BacklinkItem>> buildSnapshot() throws Exception {
        final Set<String> seenPublicIds = new LinkedHashSet<>();
        final Map<String, String> permalinkToId = new HashMap<>();
        final Map<String, MetaRow> missingContentMeta = new LinkedHashMap<>();

        int page = 1;
        while (true) {
            final Query metaQuery = new Query()
                    .setPage(page, SCAN_PAGE_SIZE)
                    .setPageCount(1)
                    .addSort(Keys.OBJECT_ID, SortDirection.ASCENDING)
                    .setFilter(new CompositeFilter(CompositeFilterOperator.AND, List.of(
                            new PropertyFilter(Article.ARTICLE_STATUS, FilterOperator.EQUAL, Article.ARTICLE_STATUS_C_PUBLISHED),
                            new PropertyFilter(Article.ARTICLE_VIEW_PWD, FilterOperator.EQUAL, "")
                    )))
                    .select(Keys.OBJECT_ID,
                            Article.ARTICLE_TITLE,
                            Article.ARTICLE_PERMALINK,
                            Article.ARTICLE_VIEW_PWD,
                            Article.ARTICLE_UPDATED,
                            Article.ARTICLE_STATUS);

            final JSONArray rows = QueryResults.array(articleRepository.get(metaQuery));
            if (rows.isEmpty()) {
                break;
            }

            int newlySeen = 0;
            for (int i = 0; i < rows.length(); i++) {
                final JSONObject row = rows.optJSONObject(i);
                if (row == null) {
                    continue;
                }
                if (row.optInt(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED) != Article.ARTICLE_STATUS_C_PUBLISHED
                        || StringUtils.isNotBlank(row.optString(Article.ARTICLE_VIEW_PWD))) {
                    continue;
                }
                final String oId = StringUtils.trimToEmpty(row.optString(Keys.OBJECT_ID));
                final String rawPermalink = StringUtils.trimToEmpty(row.optString(Article.ARTICLE_PERMALINK));
                if (oId.isEmpty() || rawPermalink.isEmpty()) {
                    continue;
                }
                if (!seenPublicIds.add(oId)) {
                    continue;
                }
                newlySeen++;
                final String canonicalPermalink = rawPermalink.startsWith("/") ? rawPermalink : ("/" + rawPermalink);
                final String lookupKey = normalizePermalinkKey(canonicalPermalink);
                if (lookupKey.isEmpty() || "/".equals(lookupKey)) {
                    seenPublicIds.remove(oId);
                    continue;
                }
                final String title = StringUtils.trimToEmpty(row.optString(Article.ARTICLE_TITLE));
                final long updated = row.optLong(Article.ARTICLE_UPDATED, 0L);

                permalinkToId.putIfAbsent(lookupKey, oId);

                final CachedOutgoing cached = outgoingCache.get(oId);
                if (cached != null && cached.updated() == updated) {
                    if (!title.equals(cached.title()) || !canonicalPermalink.equals(cached.permalink())) {
                        outgoingCache.put(oId, new CachedOutgoing(updated, title, canonicalPermalink, cached.refs()));
                    }
                } else if (row.has(Article.ARTICLE_CONTENT)) {
                    final Set<String> refs = extractPublicRawRefs(row.optString(Article.ARTICLE_CONTENT, ""));
                    outgoingCache.put(oId, new CachedOutgoing(updated, title, canonicalPermalink, refs));
                } else {
                    missingContentMeta.put(oId, new MetaRow(oId, title, canonicalPermalink, updated));
                }
            }

            if (rows.length() < SCAN_PAGE_SIZE || newlySeen == 0) {
                break;
            }
            page++;
        }

        // Prune deleted, unpublished, or newly password-protected articles from the incremental cache.
        outgoingCache.keySet().retainAll(seenPublicIds);

        if (seenPublicIds.isEmpty() || permalinkToId.isEmpty()) {
            return Collections.emptyMap();
        }

        if (!missingContentMeta.isEmpty()) {
            final List<String> missingIds = new ArrayList<>(missingContentMeta.keySet());
            final Set<String> invalidatedPublicIds = new HashSet<>();
            for (int start = 0; start < missingIds.size(); start += CONTENT_FETCH_BATCH_SIZE) {
                final int end = Math.min(start + CONTENT_FETCH_BATCH_SIZE, missingIds.size());
                final List<String> batchIds = missingIds.subList(start, end);
                final Query contentQuery = new Query()
                        .setFilter(new PropertyFilter(Keys.OBJECT_ID, FilterOperator.IN, batchIds))
                        .select(Keys.OBJECT_ID,
                                Article.ARTICLE_CONTENT,
                                Article.ARTICLE_VIEW_PWD,
                                Article.ARTICLE_STATUS)
                        .setPage(1, batchIds.size())
                        .setPageCount(1);
                final JSONArray contentRows = QueryResults.array(articleRepository.get(contentQuery));
                final Set<String> fetchedInBatch = new HashSet<>(batchIds.size() * 2);
                for (int i = 0; i < contentRows.length(); i++) {
                    final JSONObject row = contentRows.optJSONObject(i);
                    if (row == null) {
                        continue;
                    }
                    final String oId = StringUtils.trimToEmpty(row.optString(Keys.OBJECT_ID));
                    final MetaRow meta = missingContentMeta.get(oId);
                    if (meta == null) {
                        continue;
                    }
                    fetchedInBatch.add(oId);
                    if (row.optInt(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED) != Article.ARTICLE_STATUS_C_PUBLISHED
                            || StringUtils.isNotBlank(row.optString(Article.ARTICLE_VIEW_PWD))) {
                        invalidatedPublicIds.add(oId);
                        outgoingCache.remove(oId);
                        continue;
                    }
                    final Set<String> refs = extractPublicRawRefs(row.optString(Article.ARTICLE_CONTENT, ""));
                    outgoingCache.put(oId, new CachedOutgoing(meta.updated(), meta.title(), meta.permalink(), refs));
                }
                for (final String expectedId : batchIds) {
                    if (!fetchedInBatch.contains(expectedId)) {
                        invalidatedPublicIds.add(expectedId);
                        outgoingCache.remove(expectedId);
                    }
                }
            }
            if (!invalidatedPublicIds.isEmpty()) {
                seenPublicIds.removeAll(invalidatedPublicIds);
                permalinkToId.values().removeAll(invalidatedPublicIds);
            }
        }

        if (seenPublicIds.isEmpty() || permalinkToId.isEmpty()) {
            return Collections.emptyMap();
        }

        final String servePath = stripTrailingSlashes(StringUtils.trimToEmpty(Latkes.getServePath()));
        final String contextPath = stripTrailingSlashes(StringUtils.trimToEmpty(Latkes.getContextPath()));
        final Map<String, Map<String, BacklinkItem>> incomingByTarget = new LinkedHashMap<>();

        for (final String sourceId : seenPublicIds) {
            final CachedOutgoing src = outgoingCache.get(sourceId);
            if (src == null || src.refs().isEmpty()) {
                continue;
            }
            final BacklinkItem backlinkItem = new BacklinkItem(sourceId, src.title(), src.permalink(), src.updated());
            for (final String rawTarget : src.refs()) {
                final String normalizedTarget = normalizeTargetToPermalinkKey(rawTarget, servePath, contextPath);
                if (normalizedTarget.isEmpty()) {
                    continue;
                }
                final String targetArticleId = permalinkToId.get(normalizedTarget);
                if (targetArticleId != null && !targetArticleId.equals(sourceId)) {
                    incomingByTarget
                            .computeIfAbsent(targetArticleId, k -> new LinkedHashMap<>())
                            .putIfAbsent(sourceId, backlinkItem);
                }
            }
        }

        if (incomingByTarget.isEmpty()) {
            return Collections.emptyMap();
        }

        final Map<String, List<BacklinkItem>> result = new HashMap<>(incomingByTarget.size() * 2);
        final Comparator<BacklinkItem> byUpdatedDesc = Comparator.comparingLong(BacklinkItem::updated).reversed()
                .thenComparing(BacklinkItem::oId);

        for (final Map.Entry<String, Map<String, BacklinkItem>> entry : incomingByTarget.entrySet()) {
            final List<BacklinkItem> sorted = new ArrayList<>(entry.getValue().values());
            sorted.sort(byUpdatedDesc);
            final List<BacklinkItem> capped = sorted.size() > MAX_BACKLINKS_PER_ARTICLE
                    ? List.copyOf(sorted.subList(0, MAX_BACKLINKS_PER_ARTICLE))
                    : List.copyOf(sorted);
            result.put(entry.getKey(), capped);
        }

        return Collections.unmodifiableMap(result);
    }

    private static Set<String> extractPublicRawRefs(final String rawContent) {
        if (StringUtils.isBlank(rawContent)) {
            return Collections.emptySet();
        }
        // CRITICAL SECURITY: strip all :::lock ... ::: blocks (failing closed on unclosed locks) first!
        final String publicMarkdown = stripCodeBlocksAndSpans(Article.sanitizePublicMarkdown(rawContent));
        if (publicMarkdown.isEmpty()) {
            return Collections.emptySet();
        }
        final Set<String> rawTargets = extractRawLinkTargets(publicMarkdown);
        return rawTargets.isEmpty() ? Collections.emptySet() : Set.copyOf(rawTargets);
    }

    static String stripCodeBlocksAndSpans(final String markdown) {
        if (StringUtils.isBlank(markdown)) {
            return "";
        }
        final String[] lines = markdown.split("\r?\n", -1);
        final StringBuilder sb = new StringBuilder(markdown.length());
        boolean inFence = false;
        char fenceChar = 0;
        int fenceLen = 0;

        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i];
            final Matcher m = CODE_FENCE_PATTERN.matcher(line);
            if (m.find()) {
                final String ticks = m.group(1);
                if (!inFence) {
                    inFence = true;
                    fenceChar = ticks.charAt(0);
                    fenceLen = ticks.length();
                    continue;
                } else if (ticks.charAt(0) == fenceChar && ticks.length() >= fenceLen && line.trim().equals(ticks)) {
                    inFence = false;
                    continue;
                }
            }
            if (!inFence) {
                sb.append(INLINE_CODE_PATTERN.matcher(line).replaceAll(" "));
                if (i < lines.length - 1) {
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }

    static Set<String> extractRawLinkTargets(final String content) {
        final Set<String> targets = new LinkedHashSet<>();

        final Matcher mdInline = MD_INLINE_LINK_PATTERN.matcher(content);
        while (mdInline.find()) {
            final String cleaned = cleanMarkdownTarget(mdInline.group(1));
            if (!cleaned.isEmpty()) {
                targets.add(cleaned);
            }
        }

        final Matcher mdRef = MD_REF_DEF_PATTERN.matcher(content);
        while (mdRef.find()) {
            final String refTarget = StringUtils.trimToEmpty(mdRef.group(1));
            if (!refTarget.isEmpty()) {
                targets.add(refTarget);
            }
        }

        final Matcher htmlHref = HTML_HREF_PATTERN.matcher(content);
        while (htmlHref.find()) {
            String href = htmlHref.group(1);
            if (href == null) {
                href = htmlHref.group(2);
            }
            if (href == null) {
                href = htmlHref.group(3);
            }
            final String trimmed = StringUtils.trimToEmpty(href);
            if (!trimmed.isEmpty()) {
                targets.add(trimmed);
            }
        }

        return targets;
    }

    private static String cleanMarkdownTarget(final String rawInsideParens) {
        if (rawInsideParens == null) {
            return "";
        }
        final String trimmed = rawInsideParens.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.startsWith("<")) {
            final int gt = trimmed.indexOf('>');
            return gt > 1 ? trimmed.substring(1, gt).trim() : "";
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isWhitespace(trimmed.charAt(i))) {
                return trimmed.substring(0, i);
            }
        }
        return trimmed;
    }

    static String normalizeTargetToPermalinkKey(final String rawTarget,
                                                final String servePath,
                                                final String contextPath) {
        if (StringUtils.isBlank(rawTarget)) {
            return "";
        }
        String s = rawTarget.trim()
                .replace("&amp;", "&");
        if (s.startsWith("#") || s.startsWith("?")) {
            return "";
        }

        final int hashIdx = s.indexOf('#');
        if (hashIdx >= 0) {
            s = s.substring(0, hashIdx);
        }
        final int queryIdx = s.indexOf('?');
        if (queryIdx >= 0) {
            s = s.substring(0, queryIdx);
        }
        s = s.trim();
        if (s.isEmpty()) {
            return "";
        }

        if (!servePath.isEmpty()) {
            if (s.equalsIgnoreCase(servePath)) {
                return "";
            }
            if (s.length() > servePath.length()
                    && s.regionMatches(true, 0, servePath, 0, servePath.length())
                    && s.charAt(servePath.length()) == '/') {
                s = s.substring(servePath.length());
            }
        }

        if (s.contains("://") || s.startsWith("//") || s.indexOf(':') >= 0) {
            return "";
        }

        if (!contextPath.isEmpty() && s.startsWith(contextPath + "/")) {
            s = s.substring(contextPath.length());
        }

        if (!s.startsWith("/")) {
            return "";
        }

        return normalizePermalinkKey(s);
    }

    static String normalizePermalinkKey(final String permalink) {
        if (StringUtils.isBlank(permalink)) {
            return "";
        }
        String s = permalink.trim();
        final int hashIdx = s.indexOf('#');
        if (hashIdx >= 0) {
            s = s.substring(0, hashIdx);
        }
        final int queryIdx = s.indexOf('?');
        if (queryIdx >= 0) {
            s = s.substring(0, queryIdx);
        }
        if (s.isEmpty() || s.length() > 4096) {
            return "";
        }
        if (s.indexOf('%') >= 0) {
            try {
                final String preservedPlus = s.indexOf('+') >= 0
                        ? s.replace("+", "%2B")
                        : s;
                s = URLDecoder.decode(preservedPlus, StandardCharsets.UTF_8);
            } catch (final IllegalArgumentException e) {
                return "";
            }
        }
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c <= 0x1F || c == 0x7F || c == '\\' || c == '\uFFFD') {
                return "";
            }
        }
        if (!s.startsWith("/")) {
            s = "/" + s;
        }
        if (hasDotDotSegment(s)) {
            return "";
        }
        while (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static boolean hasDotDotSegment(final String path) {
        int segStart = 0;
        final int len = path.length();
        for (int i = 0; i <= len; i++) {
            if (i == len || path.charAt(i) == '/') {
                if (i - segStart == 2
                        && path.charAt(segStart) == '.'
                        && path.charAt(segStart + 1) == '.') {
                    return true;
                }
                segStart = i + 1;
            }
        }
        return false;
    }

    private static String stripTrailingSlashes(final String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }
}
