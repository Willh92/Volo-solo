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
import java.util.LinkedHashMap;
import java.util.Map;

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
     * Permalink -> article id index (deduplicating cached JSONObjects).
     */
    private final Map<String, String> permalinkIndex = new HashMap<>();

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
            }
        }
        if (!permalink.isEmpty()) {
            permalinkIndex.put(permalink, id);
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
            return;
        }
        final String permalink = article.optString(Article.ARTICLE_PERMALINK);
        permalinkIndex.remove(permalink, id);
    }

    /**
     * Returns current number of cached articles.
     */
    public synchronized int size() {
        return idCache.size();
    }

    /**
     * Clears all cached data.
     */
    public synchronized void clear() {
        idCache.clear();
        permalinkIndex.clear();
    }
}