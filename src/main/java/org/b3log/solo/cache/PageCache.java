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
import org.b3log.solo.model.Page;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
     * Page cache sharing immutable internal {@link JSONObject} instances across ID, permalink, and ordered indexes.
     */
    private final Map<String, JSONObject> cache = new ConcurrentHashMap<>();
    private final Map<String, JSONObject> permalinkCache = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();
    private volatile List<JSONObject> orderedPages;

    /**
     * Gets a page by the specified page id.
     *
     * @param id the specified page id
     * @return page, returns {@code null} if not found
     */
    public JSONObject getPage(final String id) {
        if (null == id) {
            return null;
        }
        final JSONObject page = cache.get(id);
        if (null == page) {
            return null;
        }

        return JsonCloneHelper.deepCloneObject(page);
    }

    public JSONObject getPageByPermalink(final String permalink) {
        if (null == permalink) {
            return null;
        }
        final JSONObject page = permalinkCache.get(permalink);
        return null == page ? null : JsonCloneHelper.deepCloneObject(page);
    }

    public List<JSONObject> getPages() {
        final List<JSONObject> snapshot = orderedPages;
        if (null == snapshot) {
            return null;
        }
        final List<JSONObject> copy = new ArrayList<>(snapshot.size());
        for (final JSONObject page : snapshot) {
            copy.add(JsonCloneHelper.deepCloneObject(page));
        }
        return copy;
    }

    public void putPages(final List<JSONObject> pages) {
        synchronized (writeLock) {
            if (null == pages) {
                orderedPages = null;
                return;
            }
            final List<JSONObject> stored = new ArrayList<>(pages.size());
            cache.clear();
            permalinkCache.clear();
            for (final JSONObject page : pages) {
                if (null == page) {
                    continue;
                }
                final JSONObject shared = JsonCloneHelper.deepCloneObject(page);
                stored.add(shared);
                final String id = shared.optString(Keys.OBJECT_ID);
                if (!id.isEmpty()) {
                    cache.put(id, shared);
                }
                final String permalink = shared.optString(Page.PAGE_PERMALINK);
                if (!permalink.isEmpty()) {
                    permalinkCache.put(permalink, shared);
                }
            }
            orderedPages = Collections.unmodifiableList(stored);
        }
    }

    /**
     * Adds or updates the specified page.
     *
     * @param page the specified page
     */
    public void putPage(final JSONObject page) {
        if (null == page) {
            return;
        }
        synchronized (writeLock) {
            final String pageId = page.optString(Keys.OBJECT_ID);
            final JSONObject shared = JsonCloneHelper.deepCloneObject(page);
            final JSONObject previous = pageId.isEmpty() ? null : cache.put(pageId, shared);
            if (null != previous) {
                final String oldPermalink = previous.optString(Page.PAGE_PERMALINK);
                if (!oldPermalink.isEmpty()) {
                    permalinkCache.remove(oldPermalink, previous);
                }
            }
            final String permalink = shared.optString(Page.PAGE_PERMALINK);
            if (!permalink.isEmpty()) {
                permalinkCache.put(permalink, shared);
            }
            orderedPages = null;
        }
    }

    /**
     * Removes a page by the specified page id.
     *
     * @param id the specified page id
     */
    public void removePage(final String id) {
        if (null == id) {
            return;
        }
        synchronized (writeLock) {
            final JSONObject removed = cache.remove(id);
            if (null != removed) {
                final String permalink = removed.optString(Page.PAGE_PERMALINK);
                if (!permalink.isEmpty()) {
                    permalinkCache.remove(permalink, removed);
                }
            }
            orderedPages = null;
        }
    }

    /**
     * Clears all cached data.
     */
    public void clear() {
        synchronized (writeLock) {
            cache.clear();
            permalinkCache.clear();
            orderedPages = null;
        }
    }
}
