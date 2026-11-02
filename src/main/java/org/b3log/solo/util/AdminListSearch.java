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
    private AdminListSearch() { }

    public static void apply(final Query query, final JSONObject request, final String... fields) {
        String keyword = request.optString(Common.KEYWORD).strip();
        if (keyword.isEmpty()) return;
        if (keyword.codePointCount(0, keyword.length()) > 200) keyword = keyword.substring(0, keyword.offsetByCodePoints(0, 200));
        final ArrayList<Filter> filters = new ArrayList<>();
        for (final String field : fields) filters.add(new PropertyFilter(field, FilterOperator.LIKE, "%" + keyword + "%"));
        final Filter search = new CompositeFilter(CompositeFilterOperator.OR, filters);
        final Filter existing = query.getFilter();
        query.setFilter(existing == null ? search : CompositeFilterOperator.and(existing, search));
    }
}
