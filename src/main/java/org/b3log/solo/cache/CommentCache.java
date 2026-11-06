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
import org.b3log.solo.model.Comment;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Comment cache.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 2.3.0
 */
@Singleton
public class CommentCache {

    /**
     * Maximum number of cached comments in memory.
     */
    public static final int MAX_ENTRIES = 512;

    /**
     * Maximum estimated UTF-16 character weight across cached single comments (~1 MiB).
     */
    public static final int MAX_SINGLE_CHARS = 500_000;

    /**
     * Maximum estimated UTF-16 character weight across cached per-article comment lists (~1.5 MiB).
     */
    public static final int MAX_LIST_CHARS = 750_000;

    /**
     * Maximum estimated UTF-16 character weight across cached comments and comment lists.
     */
    public static final int MAX_TOTAL_CHARS = MAX_SINGLE_CHARS + MAX_LIST_CHARS;

    /**
     * Maximum number of cached per-article sanitized comment lists in memory.
     */
    public static final int MAX_ARTICLE_LISTS = 128;

    /**
     * Maximum number of comments per article eligible for list caching.
     */
    public static final int MAX_CACHED_COMMENTS_PER_ARTICLE = 500;

    /**
     * Bounded LRU comment cache.
     */
    private final Map<String, JSONObject> cache = new LinkedHashMap<>(128, 0.75f, true);
    private final Map<String, Integer> cacheWeights = new HashMap<>();

    /**
     * Bounded LRU {@code commentOnId -> sanitized List<JSONObject>} cache.
     */
    private final Map<String, List<JSONObject>> articleCommentsCache = new LinkedHashMap<>(64, 0.75f, true);
    private final Map<String, Integer> articleListWeights = new HashMap<>();

    private long singleTotalChars = 0L;
    private long listTotalChars = 0L;

    /**
     * Gets a comment by the specified comment id.
     *
     * @param id the specified comment id
     * @return comment, returns {@code null} if not found
     */
    public synchronized JSONObject getComment(final String id) {
        if (null == id) {
            return null;
        }
        final JSONObject comment = cache.get(id);
        if (null == comment) {
            return null;
        }

        return JsonCloneHelper.deepCloneObject(comment);
    }

    /**
     * Adds or updates the specified comment and invalidates its owning article's comment list cache.
     *
     * @param comment the specified comment
     */
    public synchronized void putComment(final JSONObject comment) {
        if (null == comment) {
            return;
        }
        final String onId = comment.optString(Comment.COMMENT_ON_ID);
        if (!onId.isEmpty()) {
            removeArticleListEntry(onId);
        }
        final String id = comment.optString(Keys.OBJECT_ID);
        if (!id.isEmpty()) {
            final JSONObject cloned = JsonCloneHelper.deepCloneObject(comment);
            final int weight = JsonCloneHelper.estimateChars(cloned);
            if (weight > MAX_SINGLE_CHARS) {
                removeSingleEntry(id);
                return;
            }
            cache.put(id, cloned);
            final Integer prevWeight = cacheWeights.put(id, weight);
            if (null != prevWeight) {
                singleTotalChars = Math.max(0L, singleTotalChars - prevWeight);
            }
            singleTotalChars += weight;
            evictSingleToBounds();
        }
    }

    /**
     * Removes a comment by the specified comment id and invalidates its owning article's comment list cache.
     *
     * @param id the specified comment id
     */
    public synchronized void removeComment(final String id) {
        if (null == id) {
            return;
        }
        final JSONObject removed = removeSingleEntry(id);
        if (null != removed) {
            final String onId = removed.optString(Comment.COMMENT_ON_ID);
            if (!onId.isEmpty()) {
                removeArticleListEntry(onId);
            }
        } else {
            clearArticleLists();
        }
    }

    /**
     * Evicts all cached single comments and comment list belonging to {@code articleId}.
     *
     * @param articleId owning {@code commentOnId}
     */
    public synchronized void removeCommentsByArticleId(final String articleId) {
        if (null == articleId || articleId.isEmpty()) {
            clear();
            return;
        }
        removeArticleListEntry(articleId);
        final Iterator<Map.Entry<String, JSONObject>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<String, JSONObject> entry = it.next();
            if (articleId.equals(entry.getValue().optString(Comment.COMMENT_ON_ID))) {
                final Integer w = cacheWeights.remove(entry.getKey());
                if (null != w) {
                    singleTotalChars = Math.max(0L, singleTotalChars - w);
                }
                it.remove();
            }
        }
    }

    /**
     * Gets a defensive copy of the cached sanitized comment list for an article/page.
     *
     * @param articleId {@code commentOnId}
     * @return cloned list of comments, or {@code null} on cache miss
     */
    public synchronized List<JSONObject> getCommentsByArticleId(final String articleId) {
        if (null == articleId || articleId.isEmpty()) {
            return null;
        }
        final List<JSONObject> cached = articleCommentsCache.get(articleId);
        if (null == cached) {
            return null;
        }
        final List<JSONObject> copy = new ArrayList<>(cached.size());
        for (final JSONObject item : cached) {
            copy.add(JsonCloneHelper.deepCloneObject(item));
        }
        return copy;
    }

    /**
     * Caches a defensive copy of the sanitized comment list for an article/page.
     *
     * @param articleId {@code commentOnId}
     * @param comments  sanitized comments
     */
    public synchronized void putCommentsByArticleId(final String articleId, final List<JSONObject> comments) {
        if (null == articleId || articleId.isEmpty() || null == comments
                || comments.size() > MAX_CACHED_COMMENTS_PER_ARTICLE) {
            return;
        }
        final List<JSONObject> snapshot = new ArrayList<>(comments.size());
        for (final JSONObject item : comments) {
            if (null != item) {
                snapshot.add(JsonCloneHelper.deepCloneObject(item));
            }
        }
        final int weight = JsonCloneHelper.estimateListChars(snapshot);
        if (weight > MAX_LIST_CHARS) {
            removeArticleListEntry(articleId);
            return;
        }
        articleCommentsCache.put(articleId, Collections.unmodifiableList(snapshot));
        final Integer prevWeight = articleListWeights.put(articleId, weight);
        if (null != prevWeight) {
            listTotalChars = Math.max(0L, listTotalChars - prevWeight);
        }
        listTotalChars += weight;
        evictListsToBounds();
    }

    /**
     * Invalidates cached comment list for a specific article/page (or all lists if {@code articleId} is blank).
     *
     * @param articleId {@code commentOnId}
     */
    public synchronized void invalidateArticleComments(final String articleId) {
        if (null == articleId || articleId.isEmpty()) {
            clearArticleLists();
        } else {
            removeArticleListEntry(articleId);
        }
    }

    /**
     * Returns current number of cached single comments.
     */
    public synchronized int size() {
        return cache.size();
    }

    /**
     * Returns current estimated UTF-16 character weight across comment caches.
     */
    public synchronized long getTotalChars() {
        return singleTotalChars + listTotalChars;
    }

    /**
     * Clears all cached data.
     */
    public synchronized void clear() {
        cache.clear();
        cacheWeights.clear();
        articleCommentsCache.clear();
        articleListWeights.clear();
        singleTotalChars = 0L;
        listTotalChars = 0L;
    }

    private JSONObject removeSingleEntry(final String id) {
        final JSONObject removed = cache.remove(id);
        final Integer w = cacheWeights.remove(id);
        if (null != w) {
            singleTotalChars = Math.max(0L, singleTotalChars - w);
        }
        return removed;
    }

    private void removeArticleListEntry(final String articleId) {
        articleCommentsCache.remove(articleId);
        final Integer w = articleListWeights.remove(articleId);
        if (null != w) {
            listTotalChars = Math.max(0L, listTotalChars - w);
        }
    }

    private void clearArticleLists() {
        articleCommentsCache.clear();
        articleListWeights.clear();
        listTotalChars = 0L;
    }

    private void evictSingleToBounds() {
        final Iterator<Map.Entry<String, JSONObject>> singleIt = cache.entrySet().iterator();
        while (singleIt.hasNext() && cache.size() > MAX_ENTRIES) {
            final Map.Entry<String, JSONObject> eldest = singleIt.next();
            final Integer w = cacheWeights.remove(eldest.getKey());
            if (null != w) {
                singleTotalChars = Math.max(0L, singleTotalChars - w);
            }
            singleIt.remove();
        }
        final Iterator<Map.Entry<String, JSONObject>> singleWeightIt = cache.entrySet().iterator();
        while (singleTotalChars > MAX_SINGLE_CHARS && singleWeightIt.hasNext() && cache.size() > 1) {
            final Map.Entry<String, JSONObject> eldest = singleWeightIt.next();
            final Integer w = cacheWeights.remove(eldest.getKey());
            if (null != w) {
                singleTotalChars = Math.max(0L, singleTotalChars - w);
            }
            singleWeightIt.remove();
        }
    }

    private void evictListsToBounds() {
        final Iterator<Map.Entry<String, List<JSONObject>>> listIt = articleCommentsCache.entrySet().iterator();
        while (listIt.hasNext() && articleCommentsCache.size() > MAX_ARTICLE_LISTS) {
            final Map.Entry<String, List<JSONObject>> eldest = listIt.next();
            final Integer w = articleListWeights.remove(eldest.getKey());
            if (null != w) {
                listTotalChars = Math.max(0L, listTotalChars - w);
            }
            listIt.remove();
        }
        final Iterator<Map.Entry<String, List<JSONObject>>> listWeightIt = articleCommentsCache.entrySet().iterator();
        while (listTotalChars > MAX_LIST_CHARS && listWeightIt.hasNext() && articleCommentsCache.size() > 1) {
            final Map.Entry<String, List<JSONObject>> eldest = listWeightIt.next();
            final Integer w = articleListWeights.remove(eldest.getKey());
            if (null != w) {
                listTotalChars = Math.max(0L, listTotalChars - w);
            }
            listWeightIt.remove();
        }
    }
}
