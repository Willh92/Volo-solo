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
import org.b3log.latke.service.annotation.Service;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.model.Article;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.util.QueryResults;
import org.json.JSONArray;
import org.json.JSONObject;
import org.owasp.encoder.Encode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    @Inject
    private ArticleRepository articleRepository;

    private final Object buildLock = new Object();
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
        final Query query = new Query()
                .setPage(1, Integer.MAX_VALUE)
                .setFilter(new CompositeFilter(CompositeFilterOperator.AND, List.of(
                        new PropertyFilter(Article.ARTICLE_STATUS, FilterOperator.EQUAL, Article.ARTICLE_STATUS_C_PUBLISHED),
                        new PropertyFilter(Article.ARTICLE_VIEW_PWD, FilterOperator.EQUAL, "")
                )))
                .select(Keys.OBJECT_ID,
                        Article.ARTICLE_TITLE,
                        Article.ARTICLE_PERMALINK,
                        Article.ARTICLE_CONTENT,
                        Article.ARTICLE_VIEW_PWD,
                        Article.ARTICLE_UPDATED,
                        Article.ARTICLE_STATUS)
                .setPageCount(1);

        final JSONArray rows = QueryResults.array(articleRepository.get(query));
        if (rows.isEmpty()) {
            return Collections.emptyMap();
        }

        final String servePath = stripTrailingSlashes(StringUtils.trimToEmpty(Latkes.getServePath()));
        final String contextPath = stripTrailingSlashes(StringUtils.trimToEmpty(Latkes.getContextPath()));

        record SourceRow(String oId, String title, String permalink, String rawContent, long updated) {
        }

        final List<SourceRow> publicArticles = new ArrayList<>(rows.length());
        final Map<String, String> permalinkToId = new HashMap<>(rows.length() * 2);

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
            final String canonicalPermalink = rawPermalink.startsWith("/") ? rawPermalink : ("/" + rawPermalink);
            final String lookupKey = normalizePermalinkKey(canonicalPermalink);
            if (lookupKey.isEmpty() || "/".equals(lookupKey)) {
                continue;
            }
            final String title = StringUtils.trimToEmpty(row.optString(Article.ARTICLE_TITLE));
            final String content = row.optString(Article.ARTICLE_CONTENT, "");
            final long updated = row.optLong(Article.ARTICLE_UPDATED, 0L);

            permalinkToId.putIfAbsent(lookupKey, oId);
            publicArticles.add(new SourceRow(oId, title, canonicalPermalink, content, updated));
        }

        if (permalinkToId.isEmpty()) {
            return Collections.emptyMap();
        }

        final Map<String, Map<String, BacklinkItem>> incomingByTarget = new LinkedHashMap<>();

        for (final SourceRow src : publicArticles) {
            if (StringUtils.isBlank(src.rawContent())) {
                continue;
            }
            // CRITICAL SECURITY: strip all :::lock ... ::: blocks (failing closed on unclosed locks) first!
            final String publicMarkdown = stripCodeBlocksAndSpans(Article.sanitizePublicMarkdown(src.rawContent()));
            if (publicMarkdown.isEmpty()) {
                continue;
            }
            final Set<String> rawTargets = extractRawLinkTargets(publicMarkdown);
            if (rawTargets.isEmpty()) {
                continue;
            }
            final BacklinkItem backlinkItem = new BacklinkItem(src.oId(), src.title(), src.permalink(), src.updated());
            for (final String rawTarget : rawTargets) {
                final String normalizedTarget = normalizeTargetToPermalinkKey(rawTarget, servePath, contextPath);
                if (normalizedTarget.isEmpty()) {
                    continue;
                }
                final String targetArticleId = permalinkToId.get(normalizedTarget);
                if (targetArticleId != null && !targetArticleId.equals(src.oId())) {
                    incomingByTarget
                            .computeIfAbsent(targetArticleId, k -> new LinkedHashMap<>())
                            .putIfAbsent(src.oId(), backlinkItem);
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
        if (s.isEmpty()) {
            return "";
        }
        if (!s.startsWith("/")) {
            s = "/" + s;
        }
        while (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String stripTrailingSlashes(final String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }
}
