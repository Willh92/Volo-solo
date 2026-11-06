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
import org.b3log.latke.repository.CompositeFilter;
import org.b3log.latke.repository.CompositeFilterOperator;
import org.b3log.latke.repository.FilterOperator;
import org.b3log.latke.repository.PropertyFilter;
import org.b3log.latke.repository.Query;
import org.b3log.latke.repository.SortDirection;
import org.b3log.latke.service.annotation.Service;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Category;
import org.b3log.solo.model.Tag;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.repository.CategoryRepository;
import org.b3log.solo.repository.CategoryTagRepository;
import org.b3log.solo.repository.TagRepository;
import org.b3log.solo.util.QueryResults;
import org.json.JSONArray;
import org.json.JSONObject;
import org.owasp.encoder.Encode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * In-memory zero-leak public article snapshot for {@code Ctrl/Cmd + K} instant search.
 * Strips all {@code :::lock:::} blocks and password-protected/draft articles before indexing,
 * reuses per-article plain-text extractions across edits via a two-phase incremental build,
 * and stores compact case-preserving {@code SearchDoc} entries matched case-insensitively.
 *
 * @author <a href="https://github.com/Willh92">Willh92</a>
 * @since 3.0.0
 */
@Service
public class InstantSearchService {

    private static final Logger LOGGER = Logger.getLogger(InstantSearchService.class);
    private static final int MAX_INDEXED_ARTICLES = 2000;
    private static final int MAX_PLAIN_TEXT_CACHE_ENTRIES = 2500;
    private static final int MAX_PLAIN_CACHE_CHARS = 8_000_000;
    private static final int MAX_PLAIN_TEXT_CHARS = 16_384;
    private static final int CONTENT_FETCH_BATCH_SIZE = 50;
    private static final int SNIPPET_RADIUS = 56;

    @Inject
    private ArticleRepository articleRepository;

    @Inject
    private CategoryRepository categoryRepository;

    @Inject
    private CategoryTagRepository categoryTagRepository;

    @Inject
    private TagRepository tagRepository;

    private record CachedPlainText(long updated, String text) {
    }

    private record SearchDoc(
            String id,
            String title,
            String permalink,
            List<String> tags,
            String category,
            String plainText,
            long updatedAt) {
    }

    private record PendingDoc(
            String id,
            String title,
            String permalink,
            List<String> tags,
            String category,
            long updatedAt,
            String inlinePlainText) {
    }

    private record Snapshot(
            long builtAtMs,
            List<SearchDoc> docs,
            List<String> topTags,
            List<String> categories) {
    }

    private final Object buildLock = new Object();
    private final Map<String, CachedPlainText> plainTextCache = new LinkedHashMap<>(256, 0.75f, true);
    private long plainTextCacheChars = 0L;

    private volatile Snapshot cachedSnapshot;
    private volatile Snapshot lastGoodSnapshot;

    /**
     * Invalidates the cached snapshot after article/category/tag mutations.
     * Per-article plain-text extractions remain cached by {@code (articleId, articleUpdated)}
     * so editing one article only re-extracts plain text for that changed article.
     */
    public void invalidate() {
        cachedSnapshot = null;
    }

    /**
     * Completely resets all cached search snapshots and per-article plain-text cache entries
     * (used after full backup restore or bulk database replacement).
     */
    public void invalidateAll() {
        cachedSnapshot = null;
        lastGoodSnapshot = null;
        synchronized (buildLock) {
            plainTextCache.clear();
            plainTextCacheChars = 0L;
            cachedSnapshot = null;
            lastGoodSnapshot = null;
        }
    }

    private void putPlainTextCacheLocked(final String id, final CachedPlainText entry) {
        final CachedPlainText prev = plainTextCache.put(id, entry);
        if (prev != null) {
            plainTextCacheChars -= prev.text().length();
        }
        plainTextCacheChars += entry.text().length();
        final Iterator<Map.Entry<String, CachedPlainText>> it = plainTextCache.entrySet().iterator();
        while ((plainTextCache.size() > MAX_PLAIN_TEXT_CACHE_ENTRIES || plainTextCacheChars > MAX_PLAIN_CACHE_CHARS)
                && it.hasNext()) {
            final Map.Entry<String, CachedPlainText> eldest = it.next();
            plainTextCacheChars -= eldest.getValue().text().length();
            it.remove();
        }
        if (plainTextCacheChars < 0L || plainTextCache.isEmpty()) {
            plainTextCacheChars = 0L;
        }
    }

    private void removePlainTextCacheLocked(final String id) {
        final CachedPlainText removed = plainTextCache.remove(id);
        if (removed != null) {
            plainTextCacheChars = Math.max(0L, plainTextCacheChars - removed.text().length());
        }
    }

    private void retainPlainTextCacheIdsLocked(final Set<String> seenPublicIds) {
        final Iterator<Map.Entry<String, CachedPlainText>> it = plainTextCache.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<String, CachedPlainText> entry = it.next();
            if (!seenPublicIds.contains(entry.getKey())) {
                plainTextCacheChars -= entry.getValue().text().length();
                it.remove();
            }
        }
        if (plainTextCacheChars < 0L || plainTextCache.isEmpty()) {
            plainTextCacheChars = 0L;
        }
    }

    private static boolean containsTagIgnoreCase(final List<String> tags, final String tagFilter) {
        for (final String tag : tags) {
            if (StringUtils.equalsIgnoreCase(tag, tagFilter)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Searches public articles with optional tag/category filters and returns highlighted snippets.
     */
    public JSONObject search(final String rawQuery, final String rawTag, final String rawCategory, final int maxResults) {
        final Snapshot snapshot = getOrBuildSnapshot();
        final int limit = Math.max(1, Math.min(30, maxResults));
        final String query = StringUtils.left(StringUtils.trimToEmpty(rawQuery), 100);
        final String tagFilter = StringUtils.trimToEmpty(rawTag);
        final String catFilter = StringUtils.trimToEmpty(rawCategory);

        final List<String> terms = query.isEmpty()
                ? Collections.emptyList()
                : Arrays.stream(query.toLowerCase(Locale.ROOT).split("\\s+"))
                        .filter(s -> !s.isEmpty())
                        .limit(6)
                        .toList();

        record ScoredDoc(SearchDoc doc, int score) { }
        final List<ScoredDoc> matches = new ArrayList<>();

        for (final SearchDoc doc : snapshot.docs()) {
            if (!tagFilter.isEmpty() && !containsTagIgnoreCase(doc.tags(), tagFilter)) {
                continue;
            }
            if (!catFilter.isEmpty() && !StringUtils.equalsIgnoreCase(catFilter, doc.category())) {
                continue;
            }
            if (terms.isEmpty()) {
                matches.add(new ScoredDoc(doc, 1));
                if (matches.size() >= limit) {
                    break;
                }
                continue;
            }
            int score = 0;
            boolean allMatched = true;
            for (final String term : terms) {
                int termScore = 0;
                if (StringUtils.containsIgnoreCase(doc.title(), term)) {
                    termScore += StringUtils.startsWithIgnoreCase(doc.title(), term) ? 16 : 10;
                }
                for (final String tag : doc.tags()) {
                    if (StringUtils.containsIgnoreCase(tag, term)) {
                        termScore += 6;
                        break;
                    }
                }
                if (!doc.category().isEmpty() && StringUtils.containsIgnoreCase(doc.category(), term)) {
                    termScore += 5;
                }
                if (StringUtils.indexOfIgnoreCase(doc.plainText(), term) >= 0) {
                    termScore += 3;
                }
                if (termScore == 0) {
                    allMatched = false;
                    break;
                }
                score += termScore;
            }
            if (allMatched) {
                matches.add(new ScoredDoc(doc, score));
            }
        }

        if (!terms.isEmpty()) {
            matches.sort(Comparator.comparingInt(ScoredDoc::score).reversed()
                    .thenComparing(Comparator.comparingLong((ScoredDoc s) -> s.doc().updatedAt()).reversed()));
        }

        final JSONArray items = new JSONArray();
        final int count = Math.min(limit, matches.size());
        for (int i = 0; i < count; i++) {
            final SearchDoc doc = matches.get(i).doc();
            final String url = Latkes.getServePath() + doc.permalink();
            items.put(new JSONObject()
                    .put("id", doc.id())
                    .put("title", doc.title())
                    .put("titleHtml", highlightSafeHtml(doc.title(), terms))
                    .put("snippetHtml", buildHighlightedSnippet(doc.plainText(), terms))
                    .put("url", url)
                    .put("tags", new JSONArray(doc.tags()))
                    .put("category", doc.category())
                    .put("updated", doc.updatedAt()));
        }

        return new JSONObject()
                .put("sc", true)
                .put("enabled", true)
                .put("total", matches.size())
                .put("items", items)
                .put("tags", new JSONArray(snapshot.topTags()))
                .put("categories", new JSONArray(snapshot.categories()));
    }

    private Snapshot getOrBuildSnapshot() {
        final Snapshot current = cachedSnapshot;
        if (current != null) {
            return current;
        }
        if (articleRepository == null) {
            return new Snapshot(System.currentTimeMillis(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }
        synchronized (buildLock) {
            final Snapshot recheck = cachedSnapshot;
            if (recheck != null) {
                return recheck;
            }
            final long now = System.currentTimeMillis();
            final Snapshot fresh = buildSnapshot(now);
            if (fresh != null) {
                cachedSnapshot = fresh;
                lastGoodSnapshot = fresh;
                return fresh;
            }
            final Snapshot fallback = lastGoodSnapshot;
            return fallback != null
                    ? fallback
                    : new Snapshot(now, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }
    }

    private Snapshot buildSnapshot(final long now) {
        try {
            // Phase 1: lightweight metadata projection ONLY (no articleContent CLOBs).
            final Query query = new Query()
                    .setPageCount(1)
                    .setPage(1, MAX_INDEXED_ARTICLES)
                    .setFilter(new CompositeFilter(CompositeFilterOperator.AND, List.of(
                            new PropertyFilter(Article.ARTICLE_STATUS, FilterOperator.EQUAL, Article.ARTICLE_STATUS_C_PUBLISHED),
                            new PropertyFilter(Article.ARTICLE_VIEW_PWD, FilterOperator.EQUAL, "")
                    )))
                    .select(Keys.OBJECT_ID,
                            Article.ARTICLE_TITLE,
                            Article.ARTICLE_PERMALINK,
                            Article.ARTICLE_TAGS_REF,
                            Article.ARTICLE_VIEW_PWD,
                            Article.ARTICLE_STATUS,
                            Article.ARTICLE_UPDATED,
                            Article.ARTICLE_CREATED)
                    .addSort(Article.ARTICLE_UPDATED, SortDirection.DESCENDING);

            final JSONArray rows = QueryResults.array(articleRepository.get(query));
            final Map<String, String> tagToCategoryTitle = loadTagCategoryMap();
            final Map<String, String> stringPool = new HashMap<>();
            final Set<String> seenPublicIds = new LinkedHashSet<>();
            final List<String> missingContentIds = new ArrayList<>();
            final List<PendingDoc> pendingDocs = new ArrayList<>(rows.length());

            for (int i = 0; i < rows.length(); i++) {
                final JSONObject row = rows.optJSONObject(i);
                if (row == null
                        || row.optInt(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED) != Article.ARTICLE_STATUS_C_PUBLISHED
                        || StringUtils.isNotBlank(row.optString(Article.ARTICLE_VIEW_PWD))) {
                    continue;
                }
                final String id = row.optString(Keys.OBJECT_ID);
                if (!id.isEmpty()) {
                    seenPublicIds.add(id);
                }
                final String title = StringUtils.trimToEmpty(row.optString(Article.ARTICLE_TITLE));
                final String permalink = row.optString(Article.ARTICLE_PERMALINK);
                final long updatedAt = row.optLong(Article.ARTICLE_UPDATED, row.optLong(Article.ARTICLE_CREATED, 0L));

                String inlinePlain = null;
                final CachedPlainText cachedPlain = (!id.isEmpty() && updatedAt > 0L) ? plainTextCache.get(id) : null;
                if (cachedPlain != null && cachedPlain.updated() == updatedAt) {
                    inlinePlain = cachedPlain.text();
                } else if (row.has(Article.ARTICLE_CONTENT)) {
                    final String rawMd = Article.sanitizePublicMarkdown(row.optString(Article.ARTICLE_CONTENT));
                    inlinePlain = extractPlainText(rawMd);
                    if (!id.isEmpty() && updatedAt > 0L) {
                        putPlainTextCacheLocked(id, new CachedPlainText(updatedAt, inlinePlain));
                    }
                } else if (!id.isEmpty()) {
                    missingContentIds.add(id);
                } else {
                    inlinePlain = "";
                }

                final List<String> tags = new ArrayList<>();
                String category = tagToCategoryTitle.getOrDefault(id, "");

                final String tagsRef = row.optString(Article.ARTICLE_TAGS_REF);
                if (StringUtils.isNotBlank(tagsRef)) {
                    for (final String t : tagsRef.split(",")) {
                        final String cleanTag = t.trim();
                        if (!cleanTag.isEmpty()) {
                            final String internedTag = stringPool.computeIfAbsent(cleanTag, k -> k);
                            tags.add(internedTag);
                            if (category.isEmpty()) {
                                category = tagToCategoryTitle.getOrDefault("tag:" + internedTag, "");
                            }
                        }
                    }
                }
                final String internedCategory = category.isEmpty()
                        ? ""
                        : stringPool.computeIfAbsent(category, k -> k);

                pendingDocs.add(new PendingDoc(
                        id,
                        title,
                        permalink,
                        tags.isEmpty() ? Collections.emptyList() : List.copyOf(tags),
                        internedCategory,
                        updatedAt,
                        inlinePlain
                ));
            }

            // Phase 2: fetch missing articleContent CLOBs in IN batches of 50.
            final Map<String, String> fetchedPlainById = new HashMap<>();
            final Set<String> invalidatedIds = new HashSet<>();
            if (!missingContentIds.isEmpty()) {
                final List<String> uniqueMissingIds = new ArrayList<>(new LinkedHashSet<>(missingContentIds));
                for (int start = 0; start < uniqueMissingIds.size(); start += CONTENT_FETCH_BATCH_SIZE) {
                    final int end = Math.min(start + CONTENT_FETCH_BATCH_SIZE, uniqueMissingIds.size());
                    final List<String> batchIds = uniqueMissingIds.subList(start, end);
                    final Query contentQuery = new Query()
                            .setPageCount(1)
                            .setPage(1, batchIds.size())
                            .setFilter(new PropertyFilter(Keys.OBJECT_ID, FilterOperator.IN, batchIds))
                            .select(Keys.OBJECT_ID,
                                    Article.ARTICLE_CONTENT,
                                    Article.ARTICLE_STATUS,
                                    Article.ARTICLE_VIEW_PWD);
                    final JSONArray contentRows = QueryResults.array(articleRepository.get(contentQuery));
                    final Set<String> fetchedInBatch = new HashSet<>(batchIds.size() * 2);
                    for (int i = 0; i < contentRows.length(); i++) {
                        final JSONObject crow = contentRows.optJSONObject(i);
                        if (crow == null) {
                            continue;
                        }
                        final String cid = crow.optString(Keys.OBJECT_ID);
                        if (cid.isEmpty()) {
                            continue;
                        }
                        fetchedInBatch.add(cid);
                        if (crow.optInt(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED) != Article.ARTICLE_STATUS_C_PUBLISHED
                                || StringUtils.isNotBlank(crow.optString(Article.ARTICLE_VIEW_PWD))) {
                            invalidatedIds.add(cid);
                            removePlainTextCacheLocked(cid);
                            continue;
                        }
                        final String rawMd = Article.sanitizePublicMarkdown(crow.optString(Article.ARTICLE_CONTENT));
                        fetchedPlainById.put(cid, extractPlainText(rawMd));
                    }
                    for (final String expectedId : batchIds) {
                        if (!fetchedInBatch.contains(expectedId)) {
                            invalidatedIds.add(expectedId);
                            removePlainTextCacheLocked(expectedId);
                        }
                    }
                }
                if (!invalidatedIds.isEmpty()) {
                    seenPublicIds.removeAll(invalidatedIds);
                }
            }

            final Map<String, Integer> tagFreq = new HashMap<>();
            final Set<String> categorySet = new LinkedHashSet<>();
            final List<SearchDoc> docs = new ArrayList<>(pendingDocs.size());

            for (final PendingDoc pending : pendingDocs) {
                if (!pending.id().isEmpty() && invalidatedIds.contains(pending.id())) {
                    continue;
                }
                final String plain;
                if (pending.inlinePlainText() != null) {
                    plain = pending.inlinePlainText();
                } else {
                    plain = fetchedPlainById.getOrDefault(pending.id(), "");
                    if (!pending.id().isEmpty() && pending.updatedAt() > 0L) {
                        putPlainTextCacheLocked(pending.id(), new CachedPlainText(pending.updatedAt(), plain));
                    }
                }
                for (final String tag : pending.tags()) {
                    tagFreq.merge(tag, 1, Integer::sum);
                }
                if (!pending.category().isEmpty()) {
                    categorySet.add(pending.category());
                }
                docs.add(new SearchDoc(
                        pending.id(),
                        pending.title(),
                        pending.permalink(),
                        pending.tags(),
                        pending.category(),
                        plain,
                        pending.updatedAt()
                ));
            }

            retainPlainTextCacheIdsLocked(seenPublicIds);

            final List<String> topTags = tagFreq.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(16)
                    .map(Map.Entry::getKey)
                    .toList();

            return new Snapshot(now, docs, topTags, new ArrayList<>(categorySet));
        } catch (final Exception e) {
            LOGGER.log(Level.WARN, "Builds instant search snapshot failed", e);
            return null;
        }
    }

    private Map<String, String> loadTagCategoryMap() {
        final Map<String, String> map = new LinkedHashMap<>();
        if (categoryRepository == null || categoryTagRepository == null || tagRepository == null) {
            return map;
        }
        try {
            final Map<String, String> catTitles = new HashMap<>();
            final JSONArray cats = QueryResults.array(
                    categoryRepository.get(new Query().select(Keys.OBJECT_ID, Category.CATEGORY_TITLE).setPageCount(1)));
            for (int i = 0; i < cats.length(); i++) {
                final JSONObject c = cats.optJSONObject(i);
                if (c != null) {
                    catTitles.put(c.optString(Keys.OBJECT_ID), c.optString(Category.CATEGORY_TITLE));
                }
            }
            if (catTitles.isEmpty()) {
                return map;
            }
            final Map<String, String> tagIdToTitle = new HashMap<>();
            final JSONArray tags = QueryResults.array(
                    tagRepository.get(new Query().select(Keys.OBJECT_ID, Tag.TAG_TITLE).setPageCount(1)));
            for (int i = 0; i < tags.length(); i++) {
                final JSONObject t = tags.optJSONObject(i);
                if (t != null) {
                    tagIdToTitle.put(t.optString(Keys.OBJECT_ID), t.optString(Tag.TAG_TITLE));
                }
            }
            final JSONArray rels = QueryResults.array(
                    categoryTagRepository.get(new Query()
                            .select(Category.CATEGORY + "_" + Keys.OBJECT_ID, Tag.TAG + "_" + Keys.OBJECT_ID)
                            .setPageCount(1)));
            for (int i = 0; i < rels.length(); i++) {
                final JSONObject r = rels.optJSONObject(i);
                if (r == null) {
                    continue;
                }
                final String catTitle = catTitles.get(r.optString(Category.CATEGORY + "_" + Keys.OBJECT_ID));
                final String refId = r.optString(Tag.TAG + "_" + Keys.OBJECT_ID);
                if (StringUtils.isBlank(catTitle) || StringUtils.isBlank(refId)) {
                    continue;
                }
                map.put(refId, catTitle);
                final String tagTitle = tagIdToTitle.get(refId);
                if (StringUtils.isNotBlank(tagTitle)) {
                    map.putIfAbsent("tag:" + tagTitle, catTitle);
                }
            }
        } catch (final Exception ignored) {
        }
        return map;
    }

    /**
     * Single-pass Markdown-to-plain-text extractor capped at {@value #MAX_PLAIN_TEXT_CHARS} characters.
     * Strips code fence lines, inline code backticks, HTML comments/scripts/styles/tags, link/image URLs,
     * and block/emphasis markers without allocating a CommonMark AST or Jsoup DOM.
     */
    static String extractPlainText(final String sanitizedMarkdown) {
        if (StringUtils.isBlank(sanitizedMarkdown)) {
            return "";
        }
        final int len = sanitizedMarkdown.length();
        final StringBuilder out = new StringBuilder(Math.min(len, MAX_PLAIN_TEXT_CHARS + 64));

        boolean inFence = false;
        char fenceChar = 0;
        int fenceLen = 0;

        int cursor = 0;
        while (cursor < len && out.length() < MAX_PLAIN_TEXT_CHARS) {
            int lineEnd = sanitizedMarkdown.indexOf('\n', cursor);
            if (lineEnd < 0) {
                lineEnd = len;
            }
            final int nextCursor = lineEnd < len ? lineEnd + 1 : len;
            if (lineEnd > cursor && sanitizedMarkdown.charAt(lineEnd - 1) == '\r') {
                lineEnd--;
            }

            int p = cursor;
            while (p < lineEnd && (sanitizedMarkdown.charAt(p) == ' ' || sanitizedMarkdown.charAt(p) == '\t')) {
                p++;
            }

            // Check fenced code delimiter (``` or ~~~)
            if (p + 3 <= lineEnd) {
                final char fc = sanitizedMarkdown.charAt(p);
                if (fc == '`' || fc == '~') {
                    int ticks = 0;
                    while (p + ticks < lineEnd && sanitizedMarkdown.charAt(p + ticks) == fc) {
                        ticks++;
                    }
                    if (ticks >= 3) {
                        if (!inFence) {
                            inFence = true;
                            fenceChar = fc;
                            fenceLen = ticks;
                            cursor = nextCursor;
                            continue;
                        } else if (fc == fenceChar && ticks >= fenceLen && isOnlySpaces(sanitizedMarkdown, p + ticks, lineEnd)) {
                            inFence = false;
                            appendSpaceIfNeeded(out);
                            cursor = nextCursor;
                            continue;
                        }
                    }
                }
            }

            if (inFence) {
                appendCollapsedRange(out, sanitizedMarkdown, p, lineEnd);
                appendSpaceIfNeeded(out);
                cursor = nextCursor;
                continue;
            }

            // Skip empty lines and horizontal rules (---, ***, ___)
            if (p >= lineEnd || isHorizontalRule(sanitizedMarkdown, p, lineEnd) || isReferenceLinkDef(sanitizedMarkdown, p, lineEnd)) {
                appendSpaceIfNeeded(out);
                cursor = nextCursor;
                continue;
            }

            // Strip leading blockquotes (> > ...)
            while (p < lineEnd && sanitizedMarkdown.charAt(p) == '>') {
                p++;
                while (p < lineEnd && (sanitizedMarkdown.charAt(p) == ' ' || sanitizedMarkdown.charAt(p) == '\t')) {
                    p++;
                }
            }

            // Strip ATX heading prefix (#{1,6} )
            if (p < lineEnd && sanitizedMarkdown.charAt(p) == '#') {
                int hashes = 0;
                while (p + hashes < lineEnd && hashes < 6 && sanitizedMarkdown.charAt(p + hashes) == '#') {
                    hashes++;
                }
                if (p + hashes == lineEnd || Character.isWhitespace(sanitizedMarkdown.charAt(p + hashes))) {
                    p += hashes;
                    while (p < lineEnd && Character.isWhitespace(sanitizedMarkdown.charAt(p))) {
                        p++;
                    }
                    // Trim optional closing ATX hashes
                    while (lineEnd > p && Character.isWhitespace(sanitizedMarkdown.charAt(lineEnd - 1))) {
                        lineEnd--;
                    }
                    int tail = lineEnd;
                    while (tail > p && sanitizedMarkdown.charAt(tail - 1) == '#') {
                        tail--;
                    }
                    if (tail == p || Character.isWhitespace(sanitizedMarkdown.charAt(tail - 1))) {
                        lineEnd = tail;
                    }
                }
            } else {
                // Strip unordered/ordered/task list prefixes
                p = skipListMarker(sanitizedMarkdown, p, lineEnd);
            }

            // Scan inline tokens on the line (handling multi-line HTML comments/script/style if encountered)
            int advancedNext = scanInlineLine(out, sanitizedMarkdown, p, lineEnd, len);
            appendSpaceIfNeeded(out);
            cursor = Math.max(nextCursor, advancedNext);
        }

        int finalLen = out.length();
        while (finalLen > 0 && out.charAt(finalLen - 1) == ' ') {
            finalLen--;
        }
        if (finalLen > MAX_PLAIN_TEXT_CHARS) {
            finalLen = MAX_PLAIN_TEXT_CHARS;
            while (finalLen > 0 && out.charAt(finalLen - 1) == ' ') {
                finalLen--;
            }
        }
        return finalLen == 0 ? "" : out.substring(0, finalLen);
    }

    private static int scanInlineLine(final StringBuilder out,
                                      final String md,
                                      final int start,
                                      final int lineEnd,
                                      final int totalLen) {
        int i = start;
        while (i < lineEnd && out.length() < MAX_PLAIN_TEXT_CHARS) {
            final char c = md.charAt(i);

            // Backslash escape
            if (c == '\\' && i + 1 < lineEnd && isAsciiPunctuation(md.charAt(i + 1))) {
                appendChar(out, md.charAt(i + 1));
                i += 2;
                continue;
            }

            // Inline code span `...`
            if (c == '`') {
                int ticks = 1;
                while (i + ticks < lineEnd && md.charAt(i + ticks) == '`') {
                    ticks++;
                }
                final int contentStart = i + ticks;
                final int closeIdx = findBacktickRun(md, contentStart, lineEnd, ticks);
                if (closeIdx >= 0) {
                    appendCollapsedRange(out, md, contentStart, closeIdx);
                    i = closeIdx + ticks;
                } else {
                    i = contentStart;
                }
                continue;
            }

            // HTML comment / script / style / tag
            if (c == '<' && i + 1 < lineEnd) {
                if (md.startsWith("<!--", i)) {
                    final int endComment = md.indexOf("-->", i + 4);
                    appendSpaceIfNeeded(out);
                    return endComment < 0 ? totalLen : endComment + 3;
                }
                if (md.regionMatches(true, i, "<script", 0, 7)
                        && (i + 7 >= totalLen || !Character.isLetterOrDigit(md.charAt(i + 7)))) {
                    final int endScript = StringUtils.indexOfIgnoreCase(md, "</script>", i + 7);
                    appendSpaceIfNeeded(out);
                    if (endScript < 0) {
                        return totalLen;
                    }
                    final int afterScript = endScript + 9;
                    if (afterScript < lineEnd) {
                        i = afterScript;
                        continue;
                    }
                    return afterScript;
                }
                if (md.regionMatches(true, i, "<style", 0, 6)
                        && (i + 6 >= totalLen || !Character.isLetterOrDigit(md.charAt(i + 6)))) {
                    final int endStyle = StringUtils.indexOfIgnoreCase(md, "</style>", i + 6);
                    appendSpaceIfNeeded(out);
                    if (endStyle < 0) {
                        return totalLen;
                    }
                    final int afterStyle = endStyle + 8;
                    if (afterStyle < lineEnd) {
                        i = afterStyle;
                        continue;
                    }
                    return afterStyle;
                }
                final char next = md.charAt(i + 1);
                if (Character.isLetter(next) || next == '/' || next == '!') {
                    final int gt = md.indexOf('>', i + 2);
                    if (gt > i && gt < lineEnd) {
                        appendSpaceIfNeeded(out);
                        i = gt + 1;
                        continue;
                    }
                }
            }

            // Markdown image ![alt](url) or link [label](url) / [label][ref]
            if ((c == '!' && i + 1 < lineEnd && md.charAt(i + 1) == '[') || c == '[') {
                final int openBracket = c == '!' ? i + 1 : i;
                final int closeBracket = findMatchingBracket(md, openBracket, lineEnd);
                if (closeBracket > openBracket) {
                    final int afterBracket = closeBracket + 1;
                    if (afterBracket < lineEnd && md.charAt(afterBracket) == '(') {
                        final int closeParen = findMatchingParen(md, afterBracket, lineEnd);
                        if (closeParen > afterBracket) {
                            scanInlineLine(out, md, openBracket + 1, closeBracket, closeBracket);
                            i = closeParen + 1;
                            continue;
                        }
                    } else if (afterBracket < lineEnd && md.charAt(afterBracket) == '[') {
                        final int closeRef = findMatchingBracket(md, afterBracket, lineEnd);
                        if (closeRef >= afterBracket) {
                            scanInlineLine(out, md, openBracket + 1, closeBracket, closeBracket);
                            i = closeRef + 1;
                            continue;
                        }
                    }
                }
            }

            // Common HTML entities
            if (c == '&') {
                final int semi = md.indexOf(';', i + 1);
                if (semi > i + 1 && semi - i <= 10 && semi < lineEnd) {
                    final char decoded = decodeCommonEntity(md, i, semi + 1);
                    if (decoded != 0) {
                        appendChar(out, decoded);
                        i = semi + 1;
                        continue;
                    }
                }
            }

            // Markdown emphasis / table pipe delimiters
            if (c == '*' || c == '~') {
                while (i < lineEnd && md.charAt(i) == c) {
                    i++;
                }
                continue;
            }
            if (c == '|') {
                appendSpaceIfNeeded(out);
                i++;
                continue;
            }
            if (c == '_') {
                final boolean wordBefore = i > start && Character.isLetterOrDigit(md.charAt(i - 1));
                int runEnd = i + 1;
                while (runEnd < lineEnd && md.charAt(runEnd) == '_') {
                    runEnd++;
                }
                final boolean wordAfter = runEnd < lineEnd && Character.isLetterOrDigit(md.charAt(runEnd));
                if (!wordBefore || !wordAfter) {
                    i = runEnd;
                    continue;
                }
            }

            appendChar(out, c);
            i++;
        }
        return lineEnd;
    }

    private static void appendCollapsedRange(final StringBuilder out, final String s, final int from, final int to) {
        for (int k = from; k < to && out.length() < MAX_PLAIN_TEXT_CHARS; k++) {
            appendChar(out, s.charAt(k));
        }
    }

    private static void appendChar(final StringBuilder out, final char c) {
        if (Character.isWhitespace(c)) {
            appendSpaceIfNeeded(out);
        } else if (out.length() < MAX_PLAIN_TEXT_CHARS) {
            out.append(c);
        }
    }

    private static void appendSpaceIfNeeded(final StringBuilder out) {
        final int n = out.length();
        if (n > 0 && n < MAX_PLAIN_TEXT_CHARS && out.charAt(n - 1) != ' ') {
            out.append(' ');
        }
    }

    private static boolean isOnlySpaces(final String s, final int from, final int to) {
        for (int i = from; i < to; i++) {
            if (!Character.isWhitespace(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHorizontalRule(final String s, final int from, final int to) {
        final char first = s.charAt(from);
        if (first != '-' && first != '*' && first != '_') {
            return false;
        }
        int count = 0;
        for (int i = from; i < to; i++) {
            final char c = s.charAt(i);
            if (c == first) {
                count++;
            } else if (c != ' ' && c != '\t') {
                return false;
            }
        }
        return count >= 3;
    }

    private static boolean isReferenceLinkDef(final String s, final int from, final int to) {
        if (s.charAt(from) != '[') {
            return false;
        }
        final int close = findMatchingBracket(s, from, to);
        return close > from + 1 && close + 2 < to && s.charAt(close + 1) == ':' && Character.isWhitespace(s.charAt(close + 2));
    }

    private static int skipListMarker(final String s, final int from, final int to) {
        int p = from;
        if (p + 1 < to) {
            final char c = s.charAt(p);
            if ((c == '-' || c == '*' || c == '+') && Character.isWhitespace(s.charAt(p + 1))) {
                p += 2;
                while (p < to && Character.isWhitespace(s.charAt(p))) {
                    p++;
                }
            } else if (Character.isDigit(c)) {
                int d = p;
                while (d < to && d - p < 9 && Character.isDigit(s.charAt(d))) {
                    d++;
                }
                if (d + 1 < to && (s.charAt(d) == '.' || s.charAt(d) == ')') && Character.isWhitespace(s.charAt(d + 1))) {
                    p = d + 2;
                    while (p < to && Character.isWhitespace(s.charAt(p))) {
                        p++;
                    }
                }
            }
        }
        if (p + 3 < to && s.charAt(p) == '['
                && (s.charAt(p + 1) == ' ' || s.charAt(p + 1) == 'x' || s.charAt(p + 1) == 'X')
                && s.charAt(p + 2) == ']' && Character.isWhitespace(s.charAt(p + 3))) {
            p += 4;
            while (p < to && Character.isWhitespace(s.charAt(p))) {
                p++;
            }
        }
        return p;
    }

    private static int findBacktickRun(final String s, final int from, final int to, final int targetTicks) {
        int i = from;
        while (i < to) {
            if (s.charAt(i) == '`') {
                int run = 1;
                while (i + run < to && s.charAt(i + run) == '`') {
                    run++;
                }
                if (run == targetTicks) {
                    return i;
                }
                i += run;
            } else {
                i++;
            }
        }
        return -1;
    }

    private static int findMatchingBracket(final String s, final int openPos, final int to) {
        int depth = 0;
        for (int i = openPos; i < to; i++) {
            final char c = s.charAt(i);
            if (c == '\\' && i + 1 < to) {
                i++;
                continue;
            }
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int findMatchingParen(final String s, final int openPos, final int to) {
        int depth = 0;
        for (int i = openPos; i < to; i++) {
            final char c = s.charAt(i);
            if (c == '\\' && i + 1 < to) {
                i++;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean isAsciiPunctuation(final char c) {
        return (c >= '!' && c <= '/') || (c >= ':' && c <= '@') || (c >= '[' && c <= '`') || (c >= '{' && c <= '~');
    }

    private static char decodeCommonEntity(final String s, final int from, final int to) {
        if (s.regionMatches(true, from, "&nbsp;", 0, to - from)) {
            return ' ';
        }
        if (s.regionMatches(true, from, "&amp;", 0, to - from)) {
            return '&';
        }
        if (s.regionMatches(true, from, "&lt;", 0, to - from)) {
            return '<';
        }
        if (s.regionMatches(true, from, "&gt;", 0, to - from)) {
            return '>';
        }
        if (s.regionMatches(true, from, "&quot;", 0, to - from)) {
            return '"';
        }
        if (s.regionMatches(true, from, "&#39;", 0, to - from) || s.regionMatches(true, from, "&apos;", 0, to - from)) {
            return '\'';
        }
        return 0;
    }

    static String buildHighlightedSnippet(final String plainText, final List<String> terms) {
        if (StringUtils.isBlank(plainText)) {
            return "";
        }
        if (terms == null || terms.isEmpty()) {
            final String sub = StringUtils.left(plainText, SNIPPET_RADIUS * 2);
            return Encode.forHtml(sub) + (plainText.length() > sub.length() ? "…" : "");
        }
        int firstPos = -1;
        for (final String term : terms) {
            final int idx = StringUtils.indexOfIgnoreCase(plainText, term);
            if (idx >= 0 && (firstPos < 0 || idx < firstPos)) {
                firstPos = idx;
            }
        }
        final int start = firstPos <= 0 ? 0 : Math.max(0, firstPos - SNIPPET_RADIUS);
        final int end = Math.min(plainText.length(), start + SNIPPET_RADIUS * 2 + 24);
        final String window = plainText.substring(start, end);
        final String prefix = start > 0 ? "…" : "";
        final String suffix = end < plainText.length() ? "…" : "";
        return prefix + highlightSafeHtml(window, terms) + suffix;
    }

    static String buildHighlightedSnippet(final String plainText, final String ignoredPlainLower, final List<String> terms) {
        return buildHighlightedSnippet(plainText, terms);
    }

    static String highlightSafeHtml(final String raw, final List<String> terms) {
        if (StringUtils.isEmpty(raw)) {
            return "";
        }
        if (terms == null || terms.isEmpty()) {
            return Encode.forHtml(raw);
        }
        final List<String> sorted = terms.stream()
                .filter(s -> !s.isEmpty())
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote)
                .toList();
        if (sorted.isEmpty()) {
            return Encode.forHtml(raw);
        }
        final Pattern pattern = Pattern.compile("(" + String.join("|", sorted) + ")", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        final var matcher = pattern.matcher(raw);
        final StringBuilder sb = new StringBuilder(raw.length() + 32);
        int last = 0;
        while (matcher.find()) {
            sb.append(Encode.forHtml(raw.substring(last, matcher.start())));
            sb.append("<mark>").append(Encode.forHtml(matcher.group(1))).append("</mark>");
            last = matcher.end();
        }
        sb.append(Encode.forHtml(raw.substring(last)));
        return sb.toString();
    }
}
