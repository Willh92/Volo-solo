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
package org.b3log.solo.cache;

import org.b3log.latke.Keys;
import org.b3log.latke.ioc.Singleton;
import org.b3log.solo.model.Article;
import org.b3log.solo.service.ArticleMgmtService;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Article cache.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 2.3.0
 */
@Singleton
public class ArticleCache {

    /**
     * Maximum number of cached articles in memory.
     */
    public static final int MAX_ENTRIES = 256;

    /**
     * Maximum estimated UTF-16 character weight across cached articles (~8 MiB).
     */
    public static final int MAX_TOTAL_CHARS = 4_000_000;

    /**
     * Maximum number of negative-cached missing permalinks.
     */
    public static final int MAX_MISSING_PERMALINKS = 4096;

    private static final int MISSING_PERMALINK_EVICT_BATCH = 64;

    /**
     * Permalink -> article id index (deduplicating cached JSONObjects).
     */
    private final Map<String, String> permalinkIndex = new HashMap<>();

    /**
     * Bounded negative cache for missing permalinks.
     */
    private final Set<String> missingPermalinks = ConcurrentHashMap.newKeySet();

    /**
     * Bounded LRU article id cache storing persisted DB article snapshots.
     */
    private final Map<String, JSONObject> idCache = new LinkedHashMap<>(128, 0.75f, true);

    /**
     * Exact per-entry character weights paired with {@link #idCache}.
     */
    private final Map<String, Integer> idWeights = new HashMap<>();

    /**
     * Current total character weight across {@link #idCache}.
     */
    private long totalChars = 0L;

    /**
     * Checks whether the specified permalink is known to be missing.
     *
     * @param permalink the specified permalink
     * @return {@code true} if negative-cached as missing
     */
    public boolean isMissingPermalink(final String permalink) {
        return null != permalink && !permalink.isEmpty() && missingPermalinks.contains(permalink);
    }

    /**
     * Records a permalink that missed the database lookup.
     *
     * @param permalink the missing permalink
     */
    public void markMissingPermalink(final String permalink) {
        if (null == permalink || permalink.isEmpty() || permalink.length() > 512) {
            return;
        }
        if (missingPermalinks.size() >= MAX_MISSING_PERMALINKS) {
            synchronized (missingPermalinks) {
                if (missingPermalinks.size() >= MAX_MISSING_PERMALINKS) {
                    final Iterator<String> it = missingPermalinks.iterator();
                    int evicted = 0;
                    while (it.hasNext() && evicted < MISSING_PERMALINK_EVICT_BATCH) {
                        it.next();
                        it.remove();
                        evicted++;
                    }
                }
            }
        }
        missingPermalinks.add(permalink);
    }

    /**
     * Gets an article by the specified article id with any pending write-behind view delta overlaid once.
     *
     * @param id the specified article id
     * @return article, returns {@code null} if not found
     */
    public synchronized JSONObject getArticle(final String id) {
        if (id == null) {
            return null;
        }
        final JSONObject article = idCache.get(id);
        if (null == article) {
            return null;
        }
        final JSONObject copy = JsonCloneHelper.deepCloneObject(article);
        overlayPendingViewDelta(id, copy);
        return copy;
    }

    /**
     * Gets the persisted DB baseline snapshot of an article without overlaying un-flushed view deltas.
     *
     * @param id the specified article id
     * @return persisted article clone, or {@code null} if not cached
     */
    public synchronized JSONObject getPersistedArticle(final String id) {
        if (null == id) {
            return null;
        }
        final JSONObject article = idCache.get(id);
        return null == article ? null : JsonCloneHelper.deepCloneObject(article);
    }

    /**
     * Gets an article by the specified article permalink with any pending write-behind view delta overlaid once.
     *
     * @param permalink the specified article permalink
     * @return article, returns {@code null} if not found
     */
    public synchronized JSONObject getArticleByPermalink(final String permalink) {
        if (permalink == null) {
            return null;
        }
        final String id = permalinkIndex.get(permalink);
        if (null == id) {
            return null;
        }
        final JSONObject article = idCache.get(id);
        if (null == article) {
            permalinkIndex.remove(permalink, id);
            return null;
        }
        final JSONObject copy = JsonCloneHelper.deepCloneObject(article);
        overlayPendingViewDelta(id, copy);
        return copy;
    }

    private static void overlayPendingViewDelta(final String id, final JSONObject copy) {
        if (null == copy || null == id || id.isEmpty()) {
            return;
        }
        final long delta = ArticleMgmtService.peekPendingViewDelta(id);
        if (delta > 0L) {
            final int updated = (int) Math.min(
                    Integer.MAX_VALUE,
                    Math.max(0L, copy.optLong(Article.ARTICLE_VIEW_COUNT, 0L) + delta));
            copy.put(Article.ARTICLE_VIEW_COUNT, updated);
        }
    }

    /**
     * Adds or updates the specified persisted article snapshot.
     *
     * @param article the specified article (containing persisted {@code articleViewCount})
     */
    public synchronized void putArticle(final JSONObject article) {
        if (null == article) {
            return;
        }
        final String id = article.optString(Keys.OBJECT_ID);
        if (id.isEmpty()) {
            return;
        }
        final String permalink = article.optString(Article.ARTICLE_PERMALINK);
        if (!permalink.isEmpty()) {
            missingPermalinks.remove(permalink);
        }
        final JSONObject cloned = JsonCloneHelper.deepCloneObject(article);
        final int weight = JsonCloneHelper.estimateChars(cloned);
        if (weight > MAX_TOTAL_CHARS) {
            evictSingleById(id);
            return;
        }
        final JSONObject previous = idCache.put(id, cloned);
        final Integer prevWeight = idWeights.put(id, weight);
        if (null != prevWeight) {
            totalChars = Math.max(0L, totalChars - prevWeight);
        }
        totalChars += weight;
        if (null != previous) {
            final String oldPermalink = previous.optString(Article.ARTICLE_PERMALINK);
            if (!oldPermalink.equals(permalink)) {
                permalinkIndex.remove(oldPermalink, id);
                missingPermalinks.remove(oldPermalink);
            }
        }
        if (!permalink.isEmpty()) {
            permalinkIndex.put(permalink, id);
        }
        evictToBounds();
    }

    private void evictSingleById(final String id) {
        final JSONObject removed = idCache.remove(id);
        final Integer removedWeight = idWeights.remove(id);
        if (null != removedWeight) {
            totalChars = Math.max(0L, totalChars - removedWeight);
        }
        if (null != removed) {
            final String oldPermalink = removed.optString(Article.ARTICLE_PERMALINK);
            permalinkIndex.remove(oldPermalink, id);
            if (!oldPermalink.isEmpty()) {
                missingPermalinks.remove(oldPermalink);
            }
        }
    }

    private void evictToBounds() {
        final Iterator<Map.Entry<String, JSONObject>> it = idCache.entrySet().iterator();
        while (it.hasNext()
                && (idCache.size() > MAX_ENTRIES || (totalChars > MAX_TOTAL_CHARS && idCache.size() > 1))) {
            final Map.Entry<String, JSONObject> eldest = it.next();
            final String evictedId = eldest.getKey();
            final String evictedPermalink = eldest.getValue().optString(Article.ARTICLE_PERMALINK);
            permalinkIndex.remove(evictedPermalink, evictedId);
            final Integer w = idWeights.remove(evictedId);
            if (null != w) {
                totalChars = Math.max(0L, totalChars - w);
            }
            it.remove();
        }
    }

    /**
     * Removes an article by the specified article id.
     *
     * @param id the specified article id
     */
    public synchronized void removeArticle(final String id) {
        if (null == id) {
            return;
        }
        final JSONObject article = idCache.remove(id);
        final Integer removedWeight = idWeights.remove(id);
        if (null != removedWeight) {
            totalChars = Math.max(0L, totalChars - removedWeight);
        }
        if (null == article) {
            missingPermalinks.clear();
            return;
        }
        final String permalink = article.optString(Article.ARTICLE_PERMALINK);
        permalinkIndex.remove(permalink, id);
        if (!permalink.isEmpty()) {
            missingPermalinks.remove(permalink);
        }
    }

    /**
     * Returns current number of cached articles.
     */
    public synchronized int size() {
        return idCache.size();
    }

    /**
     * Returns current estimated total UTF-16 character weight of cached articles.
     */
    public synchronized long getTotalChars() {
        return totalChars;
    }

    /**
     * Advances the persisted {@code articleViewCount} baseline in place when write-behind deltas flush to DB.
     */
    public synchronized void advancePersistedViewCount(final String id, final long delta) {
        if (null == id || 0L == delta) {
            return;
        }
        final JSONObject cached = idCache.get(id);
        if (null != cached) {
            final int updated = (int) Math.min(
                    Integer.MAX_VALUE,
                    Math.max(0L, cached.optLong(Article.ARTICLE_VIEW_COUNT, 0L) + delta));
            cached.put(Article.ARTICLE_VIEW_COUNT, updated);
        }
    }

    /**
     * Sets the exact persisted {@code articleViewCount} baseline on a cached entry when present.
     */
    public synchronized void setPersistedViewCount(final String id, final int persistedCount) {
        if (null == id) {
            return;
        }
        final JSONObject cached = idCache.get(id);
        if (null != cached) {
            cached.put(Article.ARTICLE_VIEW_COUNT, Math.max(0, persistedCount));
        }
    }

    /**
     * Increments cached persisted article view count in place when present.
     */
    public synchronized void incrementViewCountInPlace(final String id, final long delta) {
        advancePersistedViewCount(id, delta);
    }

    /**
     * Clears all cached data.
     */
    public synchronized void clear() {
        idCache.clear();
        idWeights.clear();
        totalChars = 0L;
        permalinkIndex.clear();
        missingPermalinks.clear();
    }
}
