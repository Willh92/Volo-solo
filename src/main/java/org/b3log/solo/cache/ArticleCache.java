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
import org.b3log.solo.util.Solos;
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
     * Bounded LRU article id cache.
     */
    private final Map<String, JSONObject> idCache = new LinkedHashMap<>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, JSONObject> eldest) {
            if (size() <= MAX_ENTRIES) {
                return false;
            }
            final String evictedPermalink = eldest.getValue().optString(Article.ARTICLE_PERMALINK);
            permalinkIndex.remove(evictedPermalink, eldest.getKey());
            return true;
        }
    };

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
     * Gets an article by the specified article id.
     *
     * @param id the specified article id
     * @return article, returns {@code null} if not found
     */
    public synchronized JSONObject getArticle(final String id) {
        if (id == null) {
            return null;
        }
        final JSONObject article = idCache.get(id);
        return null == article ? null : Solos.clone(article);
    }

    /**
     * Gets an article by the specified article permalink.
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
        return Solos.clone(article);
    }

    /**
     * Adds or updates the specified article.
     *
     * @param article the specified article
     */
    public synchronized void putArticle(final JSONObject article) {
        if (null == article) {
            return;
        }
        final String id = article.optString(Keys.OBJECT_ID);
        final String permalink = article.optString(Article.ARTICLE_PERMALINK);
        final JSONObject previous = idCache.put(id, Solos.clone(article));
        if (null != previous) {
            final String oldPermalink = previous.optString(Article.ARTICLE_PERMALINK);
            if (!oldPermalink.equals(permalink)) {
                permalinkIndex.remove(oldPermalink, id);
                missingPermalinks.remove(oldPermalink);
            }
        }
        if (!permalink.isEmpty()) {
            permalinkIndex.put(permalink, id);
            missingPermalinks.remove(permalink);
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
     * Increments cached article view count in place when present.
     */
    public synchronized void incrementViewCountInPlace(final String id, final long delta) {
        if (null == id || 0L == delta) {
            return;
        }
        final JSONObject cached = idCache.get(id);
        if (null != cached) {
            final int updated = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, cached.optLong(Article.ARTICLE_VIEW_COUNT) + delta));
            cached.put(Article.ARTICLE_VIEW_COUNT, updated);
        }
    }

    /**
     * Clears all cached data.
     */
    public synchronized void clear() {
        idCache.clear();
        permalinkIndex.clear();
        missingPermalinks.clear();
    }
}
