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
package org.b3log.solo.security;

import org.b3log.latke.repository.CompositeFilter;
import org.b3log.latke.repository.CompositeFilterOperator;
import org.b3log.latke.repository.FilterOperator;
import org.b3log.latke.repository.PropertyFilter;
import org.b3log.latke.repository.Query;
import org.b3log.solo.model.Common;
import org.b3log.solo.util.AdminListSearch;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.Test;

public class AdminListSearchTest {
    @Test public void keywordUsesBoundValuesAndPreservesExistingRestrictions() {
        final var existing = new PropertyFilter("status", FilterOperator.EQUAL, 1);
        final var query = new Query().setPage(2, 10).setFilter(existing);
        AdminListSearch.apply(query, new JSONObject().put(Common.KEYWORD, "  O'Reilly & 中文  "), "title", "description");
        final var filter = (CompositeFilter) query.getFilter();
        Assert.assertEquals(filter.getOperator(), CompositeFilterOperator.AND);
        Assert.assertSame(filter.getSubFilters().get(0), existing);
        final var keywords = (CompositeFilter) filter.getSubFilters().get(1);
        Assert.assertEquals(keywords.getOperator(), CompositeFilterOperator.OR);
        Assert.assertEquals(keywords.getSubFilters().size(), 2);
        Assert.assertEquals(((PropertyFilter) keywords.getSubFilters().get(0)).getValue(), "%O'Reilly & 中文%");
        Assert.assertEquals(query.getPageSize(), 10);
    }
    @Test public void emptyQueriesDoNotFilterAndLongInputPreservesUnicodeCharacters() {
        final var query = new Query();
        AdminListSearch.apply(query, new JSONObject().put(Common.KEYWORD, " \t "), "title");
        Assert.assertNull(query.getFilter());
        AdminListSearch.apply(query, new JSONObject().put(Common.KEYWORD, "😀".repeat(201)), "title");
        final var filter = (PropertyFilter) ((CompositeFilter) query.getFilter()).getSubFilters().get(0);
        Assert.assertEquals(filter.getValue(), "%" + "😀".repeat(200) + "%");
    }
}
