/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.cache;

import org.b3log.latke.ioc.Singleton;
import org.b3log.solo.util.Solos;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded, mutation-invalidated read cache for public header/sidebar widgets and rendered article views.
 * Eliminates repetitive uncached SQL queries and redundant full-DOM Jsoup parses on public page requests.
 *
 * @author <a href="https://github.com/Willh92">Willh92</a>
 * @since 3.0.0
 */
@Singleton
public class PublicViewCache {

    public static final int MAX_WIDGET_KEYS = 128;
    public static final int MAX_RENDERED_ARTICLES = 192;
    public static final long MAX_RENDERED_CHARS = 8L * 1024L * 1024L; // ~16 MiB UTF-16 ceiling

    public static long currentEpochDay() {
        return System.currentTimeMillis() / 86_400_000L;
    }

    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    public record RenderedArticleView(
            long articleUpdated,
            boolean tocEnabled,
            boolean showCodeBlockLn,
            String bodyHtml,
            String plainAbstract,
            List<JSONObject> tocItems,
            JSONObject categoryObj,
            String articleCategoryTitle,
            String categoryUri,
            JSONObject nextArticleSummary,
            JSONObject previousArticleSummary,
            long renderedEpochDay) {

        public RenderedArticleView {
            tocItems = null == tocItems || tocItems.isEmpty() ? Collections.emptyList() : freezeList(tocItems);
            categoryObj = Solos.clone(categoryObj);
            articleCategoryTitle = null == articleCategoryTitle ? "" : articleCategoryTitle;
            categoryUri = null == categoryUri ? "" : categoryUri;
            nextArticleSummary = Solos.clone(nextArticleSummary);
            previousArticleSummary = Solos.clone(previousArticleSummary);
        }

        public RenderedArticleView(final long articleUpdated,
                                   final boolean tocEnabled,
                                   final boolean showCodeBlockLn,
                                   final String bodyHtml,
                                   final String plainAbstract,
                                   final List<JSONObject> tocItems,
                                   final JSONObject categoryObj,
                                   final String articleCategoryTitle,
                                   final String categoryUri,
                                   final JSONObject nextArticleSummary,
                                   final JSONObject previousArticleSummary) {
            this(articleUpdated, tocEnabled, showCodeBlockLn, bodyHtml, plainAbstract,
                    tocItems, categoryObj, articleCategoryTitle, categoryUri,
                    nextArticleSummary, previousArticleSummary, currentEpochDay());
        }

        public RenderedArticleView(final long articleUpdated,
                                   final boolean tocEnabled,
                                   final boolean showCodeBlockLn,
                                   final String bodyHtml,
                                   final String tocHtml,
                                   final String plainAbstract) {
            this(articleUpdated, tocEnabled, showCodeBlockLn, bodyHtml, plainAbstract,
                    Collections.emptyList(), null, "", "", null, null, currentEpochDay());
        }

        public static long currentEpochDay() {
            return PublicViewCache.currentEpochDay();
        }

        public List<JSONObject> clonedTocItems() {
            if (tocItems.isEmpty()) {
                return new ArrayList<>(0);
            }
            return cloneList(tocItems);
        }

        public JSONObject clonedCategory() {
            return Solos.clone(categoryObj);
        }

        public JSONObject clonedNextArticle() {
            return Solos.clone(nextArticleSummary);
        }

        public JSONObject clonedPreviousArticle() {
            return Solos.clone(previousArticleSummary);
        }

        int charWeight() {
            return (bodyHtml == null ? 0 : bodyHtml.length())
                    + (plainAbstract == null ? 0 : plainAbstract.length())
                    + (articleCategoryTitle.length() + categoryUri.length())
                    + (tocItems.size() * 96)
                    + (categoryObj == null ? 0 : 128)
                    + (nextArticleSummary == null ? 0 : 256)
                    + (previousArticleSummary == null ? 0 : 256);
        }
    }

    private record ListSlot(List<JSONObject> items, long expiresAtMillis) {
    }

    private final Object widgetMapLock = new Object();
    private final Map<String, ListSlot> widgetLists = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, ListSlot> eldest) {
            return size() > MAX_WIDGET_KEYS;
        }
    };
    private final Object[] widgetLocks = createLocks(16);

    private final LinkedHashMap<String, RenderedArticleView> renderedArticles = new LinkedHashMap<>(64, 0.75f, true);
    private long renderedChars = 0L;

    private static Object[] createLocks(final int count) {
        final Object[] arr = new Object[count];
        for (int i = 0; i < count; i++) {
            arr[i] = new Object();
        }
        return arr;
    }

    private ListSlot getValidSlot(final String key, final long now) {
        if (null == key) {
            return null;
        }
        synchronized (widgetMapLock) {
            final ListSlot slot = widgetLists.get(key);
            if (null != slot && (slot.expiresAtMillis <= 0L || now < slot.expiresAtMillis)) {
                return slot;
            }
            return null;
        }
    }

    private ListSlot peekAnySlot(final String key) {
        if (null == key) {
            return null;
        }
        synchronized (widgetMapLock) {
            return widgetLists.get(key);
        }
    }

    /**
     * Returns a defensive copy of a cached widget list if present and unexpired, or {@code null} on miss.
     */
    public List<JSONObject> getWidgetList(final String key) {
        final ListSlot slot = getValidSlot(key, System.currentTimeMillis());
        return null == slot ? null : cloneList(slot.items);
    }

    /**
     * Stores a defensive copy of a widget list in the bounded LRU widget cache with no soft TTL.
     */
    public void putWidgetList(final String key, final List<JSONObject> items) {
        putWidgetList(key, items, 0L);
    }

    /**
     * Stores a defensive copy of a widget list in the bounded LRU widget cache.
     */
    public void putWidgetList(final String key, final List<JSONObject> items, final long ttlMillis) {
        if (null == key || null == items) {
            return;
        }
        final List<JSONObject> frozen = freezeList(items);
        final long expiresAt = ttlMillis > 0L ? (System.currentTimeMillis() + ttlMillis) : 0L;
        synchronized (widgetMapLock) {
            widgetLists.put(key, new ListSlot(frozen, expiresAt));
        }
    }

    /**
     * Returns a defensive copy of a cached {@code List<JSONObject>} widget result, computing it under a striped
     * single-flight lock on miss or soft-TTL expiry.
     *
     * @param key       widget cache key
     * @param ttlMillis soft TTL in milliseconds ({@code <= 0} means live until explicit invalidation)
     * @param loader    loader invoked on miss
     * @return cloned list of {@link JSONObject}s
     * @throws Exception if {@code loader} fails and no stale entry exists
     */
    public List<JSONObject> getOrComputeList(final String key,
                                             final long ttlMillis,
                                             final CheckedSupplier<List<JSONObject>> loader) throws Exception {
        final ListSlot fast = getValidSlot(key, System.currentTimeMillis());
        if (fast != null) {
            return cloneList(fast.items);
        }

        final Object lock = widgetLocks[(key.hashCode() & 0x7fffffff) % widgetLocks.length];
        synchronized (lock) {
            final long nowLocked = System.currentTimeMillis();
            final ListSlot recheck = getValidSlot(key, nowLocked);
            if (recheck != null) {
                return cloneList(recheck.items);
            }
            try {
                final List<JSONObject> loaded = loader.get();
                if (loaded == null) {
                    return Collections.emptyList();
                }
                final List<JSONObject> frozen = freezeList(loaded);
                final long expiresAt = ttlMillis > 0L ? (nowLocked + ttlMillis) : 0L;
                synchronized (widgetMapLock) {
                    widgetLists.put(key, new ListSlot(frozen, expiresAt));
                }
                return cloneList(frozen);
            } catch (final Exception e) {
                final ListSlot stale = peekAnySlot(key);
                if (stale != null) {
                    return cloneList(stale.items);
                }
                throw e;
            }
        }
    }

    /**
     * Looks up a cached rendered public article view matching the exact {@code articleUpdated}, ToC state,
     * and code-line-number flag.
     */
    public synchronized RenderedArticleView getRenderedArticle(final String articleId,
                                                               final long articleUpdated,
                                                               final boolean tocEnabled,
                                                               final boolean showCodeBlockLn) {
        if (articleId == null || articleId.isEmpty()) {
            return null;
        }
        final RenderedArticleView cached = renderedArticles.get(articleId);
        if (cached == null) {
            return null;
        }
        if (cached.articleUpdated != articleUpdated
                || cached.tocEnabled != tocEnabled
                || cached.showCodeBlockLn != showCodeBlockLn
                || cached.renderedEpochDay != currentEpochDay()) {
            renderedChars = Math.max(0L, renderedChars - cached.charWeight());
            renderedArticles.remove(articleId);
            return null;
        }
        return cached;
    }

    /**
     * Caches a rendered public article view within both entry-count and character-weight bounds.
     */
    public synchronized void putRenderedArticle(final String articleId, final RenderedArticleView view) {
        if (articleId == null || articleId.isEmpty() || view == null) {
            return;
        }
        final int weight = view.charWeight();
        if (weight > MAX_RENDERED_CHARS / 2) {
            return;
        }
        final RenderedArticleView prev = renderedArticles.put(articleId, view);
        if (prev != null) {
            renderedChars = Math.max(0L, renderedChars - prev.charWeight());
        }
        renderedChars += weight;
        final Iterator<Map.Entry<String, RenderedArticleView>> it = renderedArticles.entrySet().iterator();
        while (it.hasNext() && (renderedArticles.size() > MAX_RENDERED_ARTICLES || renderedChars > MAX_RENDERED_CHARS)) {
            final Map.Entry<String, RenderedArticleView> eldest = it.next();
            renderedChars = Math.max(0L, renderedChars - eldest.getValue().charWeight());
            it.remove();
        }
    }

    public synchronized void invalidateArticle(final String articleId) {
        if (articleId != null && !articleId.isEmpty()) {
            final RenderedArticleView removed = renderedArticles.remove(articleId);
            if (removed != null) {
                renderedChars = Math.max(0L, renderedChars - removed.charWeight());
            }
        }
        invalidateCommon();
    }

    public void invalidateKeys(final String... prefixes) {
        if (null == prefixes || prefixes.length == 0) {
            return;
        }
        synchronized (widgetMapLock) {
            final Iterator<String> it = widgetLists.keySet().iterator();
            while (it.hasNext()) {
                final String key = it.next();
                if (null == key) {
                    continue;
                }
                for (final String prefix : prefixes) {
                    if (null != prefix && !prefix.isEmpty() && key.startsWith(prefix)) {
                        it.remove();
                        break;
                    }
                }
            }
        }
    }

    public void invalidateSidebarViewWidgets() {
        invalidateKeys(
                "recentArticles:", "recentArticles|",
                "mostCommentArticles:", "mostCommentArticles|",
                "mostViewCountArticles:", "mostViewCountArticles|"
        );
    }

    public void invalidateCommon() {
        synchronized (widgetMapLock) {
            widgetLists.clear();
        }
    }

    public synchronized void invalidateAll() {
        invalidateCommon();
        renderedArticles.clear();
        renderedChars = 0L;
    }

    private static List<JSONObject> freezeList(final List<JSONObject> raw) {
        final List<JSONObject> copy = new ArrayList<>(raw.size());
        for (final JSONObject item : raw) {
            if (item != null) {
                copy.add(Solos.clone(item));
            }
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<JSONObject> cloneList(final List<JSONObject> frozen) {
        final List<JSONObject> copy = new ArrayList<>(frozen.size());
        for (final JSONObject item : frozen) {
            copy.add(Solos.clone(item));
        }
        return copy;
    }
}
