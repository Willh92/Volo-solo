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
import org.b3log.solo.model.Option;
import org.b3log.solo.util.Solos;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Option cache.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 2.3.0
 */
@Singleton
public class OptionCache {

    /**
     * Option category dedicated to chunked large option documents.
     */
    public static final String CATEGORY_DOCUMENT_CHUNK = "voloDocumentChunk";

    /**
     * Option cache.
     */
    private final Map<String, JSONObject> cache = new ConcurrentHashMap<>();

    /**
     * Category option caches (immutable copy-on-write JSONObject snapshots).
     */
    private final Map<String, JSONObject> categoryCache = new ConcurrentHashMap<>();

    private static final int MAX_MISSING_IDS = 1024;
    private static final int MISSING_EVICT_BATCH = 64;
    private final java.util.Set<String> missingIds = ConcurrentHashMap.newKeySet();

    /**
     * Checks whether the given option ID matches the ephemeral document-chunk key pattern
     * ({@code <rootId>.<32-hex-generation>.<chunkIndex>}).
     *
     * @param id option ID
     * @return {@code true} if {@code id} is a document chunk ID
     */
    public static boolean isDocumentChunkId(final String id) {
        if (null == id || id.length() < 36) {
            return false;
        }
        final int lastDot = id.lastIndexOf('.');
        if (lastDot <= 33 || lastDot >= id.length() - 1) {
            return false;
        }
        final int indexLen = id.length() - (lastDot + 1);
        if (indexLen > 4) {
            return false;
        }
        for (int i = lastDot + 1; i < id.length(); i++) {
            final char c = id.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        final int prevDot = id.lastIndexOf('.', lastDot - 1);
        if (prevDot <= 0 || (lastDot - prevDot - 1) != 32) {
            return false;
        }
        for (int i = prevDot + 1; i < lastDot; i++) {
            final char c = id.charAt(i);
            final boolean isHex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!isHex) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks whether the specified option represents a chunked document fragment that must bypass caching.
     *
     * @param option option JSON object
     * @return {@code true} if the option is a document chunk
     */
    public static boolean isDocumentChunk(final JSONObject option) {
        if (null == option) {
            return false;
        }
        return CATEGORY_DOCUMENT_CHUNK.equals(option.optString(Option.OPTION_CATEGORY))
                || isDocumentChunkId(option.optString(Keys.OBJECT_ID));
    }

    public boolean isMissing(final String id) {
        return null != id && !isDocumentChunkId(id) && missingIds.contains(id);
    }

    public void markMissing(final String id) {
        if (null == id || id.isEmpty() || id.length() > 128 || isDocumentChunkId(id)) {
            return;
        }
        if (missingIds.size() >= MAX_MISSING_IDS) {
            final var it = missingIds.iterator();
            int evicted = 0;
            while (evicted < MISSING_EVICT_BATCH && it.hasNext()) {
                it.next();
                it.remove();
                evicted++;
            }
        }
        missingIds.add(id);
    }

    /**
     * Removes a category cache specified by the given category.
     *
     * @param category the given category
     */
    public void removeCategory(final String category) {
        if (null != category && !category.isEmpty()) {
            categoryCache.remove(category);
        }
    }

    /**
     * Checks whether a category snapshot is currently present in cache.
     *
     * @param category option category
     * @return {@code true} if cached
     */
    public boolean hasCategory(final String category) {
        return null != category && categoryCache.containsKey(category);
    }

    /**
     * Reads a scalar string property directly from a cached category snapshot without cloning the full object.
     *
     * @param category     option category
     * @param key          option key
     * @param defaultValue fallback value when absent or uncached
     * @return option string value or {@code defaultValue}
     */
    public String getCategoryString(final String category, final String key, final String defaultValue) {
        if (null == category || null == key) {
            return defaultValue;
        }
        final JSONObject snapshot = categoryCache.get(category);
        if (null == snapshot) {
            return defaultValue;
        }
        return snapshot.optString(key, defaultValue);
    }

    /**
     * Reads a scalar preference string directly from the cached preference snapshot without cloning.
     *
     * @param key          preference key
     * @param defaultValue fallback value when absent or uncached
     * @return preference string value or {@code defaultValue}
     */
    public String getPreferenceString(final String key, final String defaultValue) {
        return getCategoryString(Option.CATEGORY_C_PREFERENCE, key, defaultValue);
    }

    /**
     * Gets merged options as a JSON object for the specified category.
     *
     * @param category the specified category
     * @return merged options
     */
    public JSONObject getCategory(final String category) {
        if (null == category || CATEGORY_DOCUMENT_CHUNK.equals(category)) {
            return null;
        }
        final JSONObject ret = categoryCache.get(category);
        if (null == ret) {
            return null;
        }

        return Solos.clone(ret);
    }

    /**
     * Puts the specified merged options with the specified category.
     *
     * @param category      the specified category
     * @param mergedOptions the specified merged options
     */
    public void putCategory(final String category, final JSONObject mergedOptions) {
        if (null == category || null == mergedOptions || CATEGORY_DOCUMENT_CHUNK.equals(category)) {
            return;
        }
        categoryCache.put(category, Solos.clone(mergedOptions));
    }

    /**
     * Gets an option by the specified option id.
     *
     * @param id the specified option id
     * @return option, returns {@code null} if not found
     */
    public JSONObject getOption(final String id) {
        if (null == id || isDocumentChunkId(id)) {
            return null;
        }
        final JSONObject option = cache.get(id);
        if (null == option) {
            return null;
        }

        return Solos.clone(option);
    }

    /**
     * Adds or updates the specified option and updates any cached category snapshot in-place via copy-on-write.
     *
     * @param option the specified option
     */
    public void putOption(final JSONObject option) {
        if (null == option) {
            return;
        }
        final String id = option.optString(Keys.OBJECT_ID);
        if (id.isEmpty()) {
            return;
        }
        if (isDocumentChunk(option)) {
            missingIds.remove(id);
            cache.remove(id);
            return;
        }
        missingIds.remove(id);
        final JSONObject prev = cache.put(id, Solos.clone(option));

        final String category = option.optString(Option.OPTION_CATEGORY);
        if (null != prev) {
            final String oldCategory = prev.optString(Option.OPTION_CATEGORY);
            if (!oldCategory.isEmpty() && !oldCategory.equals(category)
                    && !CATEGORY_DOCUMENT_CHUNK.equals(oldCategory)) {
                categoryCache.computeIfPresent(oldCategory, (cat, existing) -> {
                    if (!existing.has(id)) {
                        return existing;
                    }
                    final JSONObject updated = Solos.clone(existing);
                    updated.remove(id);
                    return updated.isEmpty() ? null : updated;
                });
            }
        }
        if (!category.isEmpty() && !CATEGORY_DOCUMENT_CHUNK.equals(category)) {
            final Object val = option.opt(Option.OPTION_VALUE);
            categoryCache.computeIfPresent(category, (cat, existing) -> {
                final JSONObject updated = Solos.clone(existing);
                updated.put(id, val);
                return updated;
            });
        }
    }

    /**
     * Removes an option by the specified option id and updates any cached category snapshot in-place.
     *
     * @param id the specified option id
     */
    public void removeOption(final String id) {
        if (null == id) {
            return;
        }
        missingIds.remove(id);
        final JSONObject option = cache.remove(id);
        if (null != option) {
            final String category = option.optString(Option.OPTION_CATEGORY);
            if (!category.isEmpty() && !CATEGORY_DOCUMENT_CHUNK.equals(category)) {
                categoryCache.computeIfPresent(category, (cat, existing) -> {
                    if (!existing.has(id)) {
                        return existing;
                    }
                    final JSONObject updated = Solos.clone(existing);
                    updated.remove(id);
                    return updated.isEmpty() ? null : updated;
                });
            }
        }
        for (final String catKey : categoryCache.keySet()) {
            categoryCache.computeIfPresent(catKey, (cat, existing) -> {
                if (!existing.has(id)) {
                    return existing;
                }
                final JSONObject updated = Solos.clone(existing);
                updated.remove(id);
                return updated.isEmpty() ? null : updated;
            });
        }
    }

    /**
     * Evicts a single option ID from option and negative caches without polluting {@code missingIds}
     * and without clearing unrelated category snapshots.
     *
     * @param id the specified option id
     */
    public void evict(final String id) {
        if (null == id) {
            return;
        }
        missingIds.remove(id);
        final JSONObject removed = cache.remove(id);
        if (isDocumentChunkId(id)) {
            return;
        }
        if (null != removed) {
            final String category = removed.optString(Option.OPTION_CATEGORY);
            if (!category.isEmpty()) {
                categoryCache.remove(category);
                return;
            }
        }
        for (final Map.Entry<String, JSONObject> entry : categoryCache.entrySet()) {
            if (entry.getValue().has(id)) {
                categoryCache.remove(entry.getKey());
            }
        }
    }

    /**
     * Evicts a single option ID and invalidates any affected cached category snapshot.
     *
     * @param id the specified option id
     */
    public void evictOption(final String id) {
        evict(id);
    }

    /**
     * Clears all cached data.
     */
    public void clear() {
        cache.clear();
        categoryCache.clear();
        missingIds.clear();
    }
}
