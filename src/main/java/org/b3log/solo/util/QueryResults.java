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
import java.util.Collection;

/** Latke 3 keeps query rows as a List inside JSONObject; Bolo's views still use JSONArray. */
public final class QueryResults {
    private QueryResults() { }
    public static JSONArray array(final JSONObject result) {
        final Object rows = result.get(Keys.RESULTS);
        if (rows instanceof JSONArray) return (JSONArray) rows;
        if (rows instanceof Collection<?>) return new JSONArray((Collection<?>) rows);
        throw new IllegalArgumentException("Invalid query rows: " + rows.getClass().getName());
    }
}
