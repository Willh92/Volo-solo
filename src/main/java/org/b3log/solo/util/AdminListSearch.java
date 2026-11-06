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
/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.util;

import java.util.ArrayList;
import org.b3log.latke.repository.CompositeFilter;
import org.b3log.latke.repository.CompositeFilterOperator;
import org.b3log.latke.repository.Filter;
import org.b3log.latke.repository.FilterOperator;
import org.b3log.latke.repository.PropertyFilter;
import org.b3log.latke.repository.Query;
import org.b3log.solo.model.Common;
import org.json.JSONObject;

/** Add a bounded, parameterized keyword filter before counting and paging a console list. */
public final class AdminListSearch {

    public static final int MAX_KEYWORD_CODE_POINTS = 200;

    private AdminListSearch() {
    }

    public static void apply(final Query query, final JSONObject request, final String... fields) {
        if (null == query || null == request || null == fields || 0 == fields.length) {
            return;
        }
        final String keyword = normalizeKeyword(request.optString(Common.KEYWORD));
        if (keyword.isEmpty()) {
            return;
        }
        final Filter search = likeAny(keyword, fields);
        if (null == search) {
            return;
        }
        final Filter existing = query.getFilter();
        query.setFilter(existing == null ? search : CompositeFilterOperator.and(existing, search));
    }

    /**
     * Strips surrounding whitespace and caps keyword length at {@value #MAX_KEYWORD_CODE_POINTS} Unicode code points.
     */
    public static String normalizeKeyword(final String raw) {
        if (null == raw) {
            return "";
        }
        String keyword = raw.strip();
        if (keyword.isEmpty()) {
            return "";
        }
        if (keyword.codePointCount(0, keyword.length()) > MAX_KEYWORD_CODE_POINTS) {
            keyword = keyword.substring(0, keyword.offsetByCodePoints(0, MAX_KEYWORD_CODE_POINTS));
        }
        return keyword;
    }

    /**
     * Builds a parameterized {@code OR} {@code LIKE} filter across {@code fields} with escaped wildcards.
     */
    public static Filter likeAny(final String normalizedKeyword, final String... fields) {
        if (null == normalizedKeyword || normalizedKeyword.isEmpty() || null == fields || 0 == fields.length) {
            return null;
        }
        final String pattern = "%" + escapeLikeLiteral(normalizedKeyword) + "%";
        final ArrayList<Filter> filters = new ArrayList<>(fields.length);
        for (final String field : fields) {
            filters.add(new PropertyFilter(field, FilterOperator.LIKE, pattern));
        }
        return new CompositeFilter(CompositeFilterOperator.OR, filters);
    }

    /**
     * Returns {@code true} if {@code raw} contains SQL {@code LIKE} metacharacters ({@code %}, {@code _}, or {@code \}).
     */
    public static boolean containsLikeMeta(final String raw) {
        return null != raw && (raw.indexOf('%') >= 0 || raw.indexOf('_') >= 0 || raw.indexOf('\\') >= 0);
    }

    /**
     * Escapes SQL {@code LIKE} wildcard characters ({@code %}, {@code _}, and {@code \}) using backslash escaping,
     * leaving strings without wildcards or backslashes untouched.
     */
    public static String escapeLikeLiteral(final String raw) {
        if (null == raw || (raw.indexOf('%') < 0 && raw.indexOf('_') < 0 && raw.indexOf('\\') < 0)) {
            return raw;
        }
        final StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            if ('\\' == c || '%' == c || '_' == c) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
