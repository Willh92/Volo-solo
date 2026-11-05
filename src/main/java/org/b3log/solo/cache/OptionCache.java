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

    public boolean isMissing(final String id) {
        return null != id && missingIds.contains(id);
    }

    public void markMissing(final String id) {
        if (null == id || id.isEmpty() || id.length() > 128) {
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
     * Gets merged options as a JSON object for the specified category.
     *
     * @param category the specified category
     * @return merged options
     */
    public JSONObject getCategory(final String category) {
        if (null == category) {
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
        if (null == category || null == mergedOptions) {
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
        if (null == id) {
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
        missingIds.remove(id);
        cache.put(id, Solos.clone(option));

        final String category = option.optString(Option.OPTION_CATEGORY);
        if (!category.isEmpty()) {
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
        if (null == option) {
            return;
        }

        final String category = option.optString(Option.OPTION_CATEGORY);
        if (!category.isEmpty()) {
            categoryCache.computeIfPresent(category, (cat, existing) -> {
                final JSONObject updated = Solos.clone(existing);
                updated.remove(id);
                return updated.isEmpty() ? null : updated;
            });
        }
    }

    /**
     * Evicts a single option ID and clears cached category snapshots without mutating any category snapshot in-place.
     *
     * @param id the specified option id
     */
    public void evictOption(final String id) {
        if (null == id) {
            return;
        }
        missingIds.remove(id);
        cache.remove(id);
        categoryCache.clear();
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
