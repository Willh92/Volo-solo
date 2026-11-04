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
import org.b3log.solo.util.Solos;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Page cache.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 2.3.0
 */
@Singleton
public class PageCache {

    /**
     * Page cache.
     */
    private final Map<String, JSONObject> cache = new ConcurrentHashMap<>();
    private final Map<String, JSONObject> permalinkCache = new ConcurrentHashMap<>();
    private volatile java.util.List<JSONObject> orderedPages;

    /**
     * Gets a page by the specified page id.
     *
     * @param id the specified page id
     * @return page, returns {@code null} if not found
     */
    public JSONObject getPage(final String id) {
        final JSONObject page = cache.get(id);
        if (null == page) {
            return null;
        }

        return Solos.clone(page);
    }

    public JSONObject getPageByPermalink(final String permalink) {
        if (null == permalink) {
            return null;
        }
        final JSONObject page = permalinkCache.get(permalink);
        return null == page ? null : Solos.clone(page);
    }

    public java.util.List<JSONObject> getPages() {
        final java.util.List<JSONObject> snapshot = orderedPages;
        if (null == snapshot) {
            return null;
        }
        final java.util.List<JSONObject> copy = new java.util.ArrayList<>(snapshot.size());
        for (final JSONObject page : snapshot) {
            copy.add(Solos.clone(page));
        }
        return copy;
    }

    public void putPages(final java.util.List<JSONObject> pages) {
        if (null == pages) {
            orderedPages = null;
            return;
        }
        final java.util.List<JSONObject> stored = new java.util.ArrayList<>(pages.size());
        permalinkCache.clear();
        for (final JSONObject page : pages) {
            final JSONObject copy = Solos.clone(page);
            stored.add(copy);
            final String id = copy.optString(Keys.OBJECT_ID);
            if (!id.isEmpty()) {
                cache.put(id, copy);
            }
            final String permalink = copy.optString(org.b3log.solo.model.Page.PAGE_PERMALINK);
            if (!permalink.isEmpty()) {
                permalinkCache.put(permalink, copy);
            }
        }
        orderedPages = java.util.Collections.unmodifiableList(stored);
    }

    /**
     * Adds or updates the specified page.
     *
     * @param page the specified page
     */
    public void putPage(final JSONObject page) {
        final String pageId = page.optString(Keys.OBJECT_ID);
        final JSONObject copy = Solos.clone(page);
        final JSONObject previous = cache.put(pageId, copy);
        if (null != previous) {
            final String oldPermalink = previous.optString(org.b3log.solo.model.Page.PAGE_PERMALINK);
            if (!oldPermalink.isEmpty()) {
                permalinkCache.remove(oldPermalink);
            }
        }
        final String permalink = copy.optString(org.b3log.solo.model.Page.PAGE_PERMALINK);
        if (!permalink.isEmpty()) {
            permalinkCache.put(permalink, copy);
        }
        orderedPages = null;
    }

    /**
     * Removes an page by the specified page id.
     *
     * @param id the specified page id
     */
    public void removePage(final String id) {
        final JSONObject removed = cache.remove(id);
        if (null != removed) {
            final String permalink = removed.optString(org.b3log.solo.model.Page.PAGE_PERMALINK);
            if (!permalink.isEmpty()) {
                permalinkCache.remove(permalink);
            }
        }
        orderedPages = null;
    }

    /**
     * Clears all cached data.
     */
    public void clear() {
        cache.clear();
        permalinkCache.clear();
        orderedPages = null;
    }
}
