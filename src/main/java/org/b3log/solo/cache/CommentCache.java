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
import org.b3log.solo.util.Solos;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
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
    private final Map<String, JSONObject> cache = Collections.synchronizedMap(new LinkedHashMap<>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, JSONObject> eldest) {
            return size() > MAX_ENTRIES;
        }
    });

    /**
     * Bounded LRU {@code commentOnId -> sanitized List<JSONObject>} cache.
     */
    private final Map<String, List<JSONObject>> articleCommentsCache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(final Map.Entry<String, List<JSONObject>> eldest) {
                    return size() > MAX_ARTICLE_LISTS;
                }
            });

    /**
     * Gets a comment by the specified comment id.
     *
     * @param id the specified comment id
     * @return comment, returns {@code null} if not found
     */
    public JSONObject getComment(final String id) {
        if (null == id) {
            return null;
        }
        final JSONObject comment = cache.get(id);
        if (null == comment) {
            return null;
        }

        return Solos.clone(comment);
    }

    /**
     * Adds or updates the specified comment and invalidates its owning article's comment list cache.
     *
     * @param comment the specified comment
     */
    public void putComment(final JSONObject comment) {
        if (null == comment) {
            return;
        }
        final String id = comment.optString(Keys.OBJECT_ID);
        if (!id.isEmpty()) {
            cache.put(id, Solos.clone(comment));
        }
        final String onId = comment.optString(Comment.COMMENT_ON_ID);
        if (!onId.isEmpty()) {
            articleCommentsCache.remove(onId);
        }
    }

    /**
     * Removes a comment by the specified comment id and invalidates its owning article's comment list cache.
     *
     * @param id the specified comment id
     */
    public void removeComment(final String id) {
        if (null == id) {
            return;
        }
        final JSONObject removed = cache.remove(id);
        if (null != removed) {
            final String onId = removed.optString(Comment.COMMENT_ON_ID);
            if (!onId.isEmpty()) {
                articleCommentsCache.remove(onId);
            }
        } else {
            articleCommentsCache.clear();
        }
    }

    /**
     * Gets a defensive copy of the cached sanitized comment list for an article/page.
     *
     * @param articleId {@code commentOnId}
     * @return cloned list of comments, or {@code null} on cache miss
     */
    public List<JSONObject> getCommentsByArticleId(final String articleId) {
        if (null == articleId || articleId.isEmpty()) {
            return null;
        }
        final List<JSONObject> cached = articleCommentsCache.get(articleId);
        if (null == cached) {
            return null;
        }
        final List<JSONObject> copy = new ArrayList<>(cached.size());
        for (final JSONObject item : cached) {
            copy.add(Solos.clone(item));
        }
        return copy;
    }

    /**
     * Caches a defensive copy of the sanitized comment list for an article/page.
     *
     * @param articleId {@code commentOnId}
     * @param comments  sanitized comments
     */
    public void putCommentsByArticleId(final String articleId, final List<JSONObject> comments) {
        if (null == articleId || articleId.isEmpty() || null == comments
                || comments.size() > MAX_CACHED_COMMENTS_PER_ARTICLE) {
            return;
        }
        final List<JSONObject> snapshot = new ArrayList<>(comments.size());
        for (final JSONObject item : comments) {
            snapshot.add(Solos.clone(item));
        }
        articleCommentsCache.put(articleId, Collections.unmodifiableList(snapshot));
    }

    /**
     * Invalidates cached comment list for a specific article/page (or all lists if {@code articleId} is blank).
     *
     * @param articleId {@code commentOnId}
     */
    public void invalidateArticleComments(final String articleId) {
        if (null == articleId || articleId.isEmpty()) {
            articleCommentsCache.clear();
        } else {
            articleCommentsCache.remove(articleId);
        }
    }

    /**
     * Returns current number of cached single comments.
     */
    public int size() {
        return cache.size();
    }

    /**
     * Clears all cached data.
     */
    public void clear() {
        cache.clear();
        articleCommentsCache.clear();
    }
}
