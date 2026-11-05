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
import org.b3log.solo.util.Markdowns;
import org.b3log.solo.util.QueryResults;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.owasp.encoder.Encode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * In-memory zero-leak public article snapshot for {@code Ctrl/Cmd + K} instant search.
 * Strips all {@code :::lock:::} blocks and password-protected/draft articles before indexing.
 *
 * @author <a href="https://github.com/Willh92">Willh92</a>
 * @since 3.0.0
 */
@Service
public class InstantSearchService {

    private static final Logger LOGGER = Logger.getLogger(InstantSearchService.class);
    private static final long SNAPSHOT_TTL_MS = 60_000L;
    private static final int MAX_INDEXED_ARTICLES = 2000;
    private static final int SNIPPET_RADIUS = 56;

    @Inject
    private ArticleRepository articleRepository;

    @Inject
    private CategoryRepository categoryRepository;

    @Inject
    private CategoryTagRepository categoryTagRepository;

    @Inject
    private TagRepository tagRepository;

    private record SearchDoc(
            String id,
            String title,
            String titleLower,
            String permalink,
            List<String> tags,
            List<String> tagsLower,
            String category,
            String categoryLower,
            String plainText,
            String plainLower,
            long updatedAt) {
    }

    private record Snapshot(
            long builtAtMs,
            List<SearchDoc> docs,
            List<String> topTags,
            List<String> categories) {
    }

    private volatile Snapshot cachedSnapshot;

    /**
     * Invalidates the cached snapshot after article mutations.
     */
    public void invalidate() {
        cachedSnapshot = null;
    }

    /**
     * Searches public articles with optional tag/category filters and returns highlighted snippets.
     */
    public JSONObject search(final String rawQuery, final String rawTag, final String rawCategory, final int maxResults) {
        final Snapshot snapshot = getOrBuildSnapshot();
        final int limit = Math.max(1, Math.min(30, maxResults));
        final String query = StringUtils.left(StringUtils.trimToEmpty(rawQuery), 100);
        final String tagFilter = StringUtils.trimToEmpty(rawTag).toLowerCase(Locale.ROOT);
        final String catFilter = StringUtils.trimToEmpty(rawCategory).toLowerCase(Locale.ROOT);

        final List<String> terms = query.isEmpty()
                ? Collections.emptyList()
                : Arrays.stream(query.toLowerCase(Locale.ROOT).split("\\s+"))
                        .filter(s -> !s.isEmpty())
                        .limit(6)
                        .toList();

        record ScoredDoc(SearchDoc doc, int score) { }
        final List<ScoredDoc> matches = new ArrayList<>();

        for (final SearchDoc doc : snapshot.docs()) {
            if (!tagFilter.isEmpty() && !doc.tagsLower().contains(tagFilter)) {
                continue;
            }
            if (!catFilter.isEmpty() && !catFilter.equals(doc.categoryLower())) {
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
                if (doc.titleLower().contains(term)) {
                    termScore += doc.titleLower().startsWith(term) ? 16 : 10;
                }
                for (final String tl : doc.tagsLower()) {
                    if (tl.contains(term)) {
                        termScore += 6;
                        break;
                    }
                }
                if (!doc.categoryLower().isEmpty() && doc.categoryLower().contains(term)) {
                    termScore += 5;
                }
                if (doc.plainLower().contains(term)) {
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
                    .put("snippetHtml", buildHighlightedSnippet(doc.plainText(), doc.plainLower(), terms))
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
        final long now = System.currentTimeMillis();
        final Snapshot current = cachedSnapshot;
        if (current != null && (now - current.builtAtMs()) < SNAPSHOT_TTL_MS) {
            return current;
        }
        synchronized (this) {
            final Snapshot recheck = cachedSnapshot;
            if (recheck != null && (now - recheck.builtAtMs()) < SNAPSHOT_TTL_MS) {
                return recheck;
            }
            final Snapshot fresh = buildSnapshot(now);
            cachedSnapshot = fresh;
            return fresh;
        }
    }

    private Snapshot buildSnapshot(final long now) {
        try {
            final Query query = new Query()
                    .setPageCount(1)
                    .setPage(1, MAX_INDEXED_ARTICLES)
                    .setFilter(new CompositeFilter(CompositeFilterOperator.AND, List.of(
                            new PropertyFilter(Article.ARTICLE_STATUS, FilterOperator.EQUAL, Article.ARTICLE_STATUS_C_PUBLISHED),
                            new PropertyFilter(Article.ARTICLE_VIEW_PWD, FilterOperator.EQUAL, "")
                    )))
                    .addSort(Article.ARTICLE_UPDATED, SortDirection.DESCENDING);

            final JSONArray rows = QueryResults.array(articleRepository.get(query));
            final Map<String, String> tagToCategoryTitle = loadTagCategoryMap();
            final Map<String, Integer> tagFreq = new HashMap<>();
            final Set<String> categorySet = new LinkedHashSet<>();
            final List<SearchDoc> docs = new ArrayList<>(rows.length());

            for (int i = 0; i < rows.length(); i++) {
                final JSONObject row = rows.optJSONObject(i);
                if (row == null || StringUtils.isNotBlank(row.optString(Article.ARTICLE_VIEW_PWD))) {
                    continue;
                }
                final String id = row.optString(Keys.OBJECT_ID);
                final String title = StringUtils.trimToEmpty(row.optString(Article.ARTICLE_TITLE));
                final String permalink = row.optString(Article.ARTICLE_PERMALINK);
                final String rawMd = Article.sanitizePublicMarkdown(row.optString(Article.ARTICLE_CONTENT));
                final String plain = extractPlainText(rawMd);

                final List<String> tags = new ArrayList<>();
                final List<String> tagsLower = new ArrayList<>();
                String category = tagToCategoryTitle.getOrDefault(id, "");

                final String tagsRef = row.optString(Article.ARTICLE_TAGS_REF);
                if (StringUtils.isNotBlank(tagsRef)) {
                    for (final String t : tagsRef.split(",")) {
                        final String cleanTag = t.trim();
                        if (!cleanTag.isEmpty()) {
                            tags.add(cleanTag);
                            tagsLower.add(cleanTag.toLowerCase(Locale.ROOT));
                            tagFreq.merge(cleanTag, 1, Integer::sum);
                            if (category.isEmpty()) {
                                category = tagToCategoryTitle.getOrDefault("tag:" + cleanTag, "");
                            }
                        }
                    }
                }
                if (!category.isEmpty()) {
                    categorySet.add(category);
                }

                docs.add(new SearchDoc(
                        id,
                        title,
                        title.toLowerCase(Locale.ROOT),
                        permalink,
                        tags,
                        tagsLower,
                        category,
                        category.toLowerCase(Locale.ROOT),
                        plain,
                        plain.toLowerCase(Locale.ROOT),
                        row.optLong(Article.ARTICLE_UPDATED, row.optLong(Article.ARTICLE_CREATED, 0L))
                ));
            }

            final List<String> topTags = tagFreq.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(16)
                    .map(Map.Entry::getKey)
                    .toList();

            return new Snapshot(now, docs, topTags, new ArrayList<>(categorySet));
        } catch (final Exception e) {
            LOGGER.log(Level.WARN, "Builds instant search snapshot failed", e);
            return new Snapshot(now, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }
    }

    private Map<String, String> loadTagCategoryMap() {
        final Map<String, String> map = new LinkedHashMap<>();
        if (categoryRepository == null || categoryTagRepository == null || tagRepository == null) {
            return map;
        }
        try {
            final Map<String, String> catTitles = new HashMap<>();
            final JSONArray cats = QueryResults.array(categoryRepository.get(new Query().setPageCount(1)));
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
            final JSONArray tags = QueryResults.array(tagRepository.get(new Query().setPageCount(1)));
            for (int i = 0; i < tags.length(); i++) {
                final JSONObject t = tags.optJSONObject(i);
                if (t != null) {
                    tagIdToTitle.put(t.optString(Keys.OBJECT_ID), t.optString(Tag.TAG_TITLE));
                }
            }
            final JSONArray rels = QueryResults.array(categoryTagRepository.get(new Query().setPageCount(1)));
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

    private static String extractPlainText(final String sanitizedMarkdown) {
        if (StringUtils.isBlank(sanitizedMarkdown)) {
            return "";
        }
        try {
            final String html = Markdowns.toHTMLLocally(sanitizedMarkdown);
            return Jsoup.parse(html).text().replaceAll("\\s+", " ").trim();
        } catch (final Exception e) {
            return sanitizedMarkdown.replaceAll("[#*`>\\[\\]()!_~-]", " ").replaceAll("\\s+", " ").trim();
        }
    }

    static String buildHighlightedSnippet(final String plainText, final String plainLower, final List<String> terms) {
        if (StringUtils.isBlank(plainText)) {
            return "";
        }
        if (terms == null || terms.isEmpty()) {
            final String sub = StringUtils.left(plainText, SNIPPET_RADIUS * 2);
            return Encode.forHtml(sub) + (plainText.length() > sub.length() ? "…" : "");
        }
        int firstPos = -1;
        for (final String term : terms) {
            final int idx = plainLower.indexOf(term);
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
