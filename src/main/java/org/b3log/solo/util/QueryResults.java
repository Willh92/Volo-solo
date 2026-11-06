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
package org.b3log.solo.util;

import org.b3log.latke.Keys;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** Latke 3 keeps query rows as a List inside JSONObject; Bolo's views still use JSONArray. */
public final class QueryResults {
    private QueryResults() { }

    public static JSONArray array(final JSONObject result) {
        final Object rows = result.get(Keys.RESULTS);
        if (rows instanceof JSONArray) return (JSONArray) rows;
        if (rows instanceof Collection<?>) return new JSONArray((Collection<?>) rows);
        throw new IllegalArgumentException("Invalid query rows: " + rows.getClass().getName());
    }

    private static String findKeyIgnoreCase(final JSONObject row, final String key) {
        if (null == row || null == key || key.isEmpty()) {
            return null;
        }
        if (row.has(key)) {
            return key;
        }
        final String upper = key.toUpperCase(Locale.ROOT);
        if (row.has(upper)) {
            return upper;
        }
        final String lower = key.toLowerCase(Locale.ROOT);
        if (row.has(lower)) {
            return lower;
        }
        for (final String candidate : row.keySet()) {
            if (key.equalsIgnoreCase(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    public static String optStringIgnoreCase(final JSONObject row, final String key) {
        return optStringIgnoreCase(row, key, "");
    }

    public static String optStringIgnoreCase(final JSONObject row, final String key, final String defaultValue) {
        final String matched = findKeyIgnoreCase(row, key);
        if (null == matched || row.isNull(matched)) {
            return defaultValue;
        }
        return row.optString(matched, defaultValue);
    }

    public static int optIntIgnoreCase(final JSONObject row, final String key) {
        return optIntIgnoreCase(row, key, 0);
    }

    public static int optIntIgnoreCase(final JSONObject row, final String key, final int defaultValue) {
        final String matched = findKeyIgnoreCase(row, key);
        if (null == matched || row.isNull(matched)) {
            return defaultValue;
        }
        final Object val = row.opt(matched);
        if (val instanceof Number n) {
            return n.intValue();
        }
        return row.optInt(matched, defaultValue);
    }

    public static long optLongIgnoreCase(final JSONObject row, final String key) {
        return optLongIgnoreCase(row, key, 0L);
    }

    public static long optLongIgnoreCase(final JSONObject row, final String key, final long defaultValue) {
        final String matched = findKeyIgnoreCase(row, key);
        if (null == matched || row.isNull(matched)) {
            return defaultValue;
        }
        final Object val = row.opt(matched);
        if (val instanceof Number n) {
            return n.longValue();
        }
        return row.optLong(matched, defaultValue);
    }

    public static void removeIgnoreCase(final JSONObject row, final String key) {
        if (null == row || null == key || key.isEmpty()) {
            return;
        }
        row.remove(key);
        row.remove(key.toUpperCase(Locale.ROOT));
        row.remove(key.toLowerCase(Locale.ROOT));
        List<String> extra = null;
        for (final String candidate : row.keySet()) {
            if (key.equalsIgnoreCase(candidate)) {
                if (null == extra) {
                    extra = new ArrayList<>();
                }
                extra.add(candidate);
            }
        }
        if (null != extra) {
            for (final String candidate : extra) {
                row.remove(candidate);
            }
        }
    }
}
