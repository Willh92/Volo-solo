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
package org.b3log.solo.repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.b3log.latke.Keys;
import org.b3log.latke.ioc.BeanManager;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.repository.AbstractRepository;
import org.b3log.latke.repository.FilterOperator;
import org.b3log.latke.repository.PropertyFilter;
import org.b3log.latke.repository.Query;
import org.b3log.latke.repository.RepositoryException;
import org.b3log.latke.repository.SortDirection;
import org.b3log.latke.repository.annotation.Repository;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Category;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Category repository.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 2.0.0
 */
@Repository
public class CategoryRepository extends AbstractRepository {

    @Inject
    private CategoryTagRepository categoryTagRepository;

    @Inject
    private ArticleRepository articleRepository;

    /**
     * Public constructor.
     */
    public CategoryRepository() {
        super(Category.CATEGORY);
    }

    /**
     * Gets a category by the specified category title.
     *
     * @param categoryTitle the specified category title
     * @return a category, {@code null} if not found
     * @throws RepositoryException repository exception
     */
    public JSONObject getByTitle(final String categoryTitle) throws RepositoryException {
        final Query query = new Query()
                .setFilter(new PropertyFilter(Category.CATEGORY_TITLE, FilterOperator.EQUAL, categoryTitle))
                .setPage(1, 1)
                .setPageCount(1);
        final JSONObject result = get(query);
        final JSONArray array = org.b3log.solo.util.QueryResults.array(result);
        if (0 == array.length()) {
            return null;
        }

        return array.optJSONObject(0);
    }

    /**
     * Gets a category by the specified category URI.
     *
     * @param categoryURI the specified category URI
     * @return a category, {@code null} if not found
     * @throws RepositoryException repository exception
     */
    public JSONObject getByURI(final String categoryURI) throws RepositoryException {
        final Query query = new Query()
                .setFilter(new PropertyFilter(Category.CATEGORY_URI, FilterOperator.EQUAL, categoryURI))
                .setPage(1, 1)
                .setPageCount(1);
        final JSONObject result = get(query);
        final JSONArray array = org.b3log.solo.util.QueryResults.array(result);
        if (0 == array.length()) {
            return null;
        }

        return array.optJSONObject(0);
    }

    /**
     * Gets the maximum order.
     *
     * @return order number, returns {@code -1} if not found
     * @throws RepositoryException repository exception
     */
    public int getMaxOrder() throws RepositoryException {
        final Query query = new Query()
                .select(Category.CATEGORY_ORDER)
                .addSort(Category.CATEGORY_ORDER, SortDirection.DESCENDING)
                .setPage(1, 1)
                .setPageCount(1);
        final JSONObject result = get(query);
        final JSONArray array = org.b3log.solo.util.QueryResults.array(result);
        if (0 == array.length()) {
            return -1;
        }

        return array.optJSONObject(0).optInt(Category.CATEGORY_ORDER);
    }

    /**
     * Gets the upper category of the category specified by the given id.
     *
     * @param id the given id
     * @return upper category, returns {@code null} if not found
     * @throws RepositoryException repository exception
     */
    public JSONObject getUpper(final String id) throws RepositoryException {
        final JSONObject category = get(id);
        if (null == category) {
            return null;
        }

        final Query query = new Query()
                .setFilter(new PropertyFilter(Category.CATEGORY_ORDER, FilterOperator.LESS_THAN,
                        category.optInt(Category.CATEGORY_ORDER)))
                .addSort(Category.CATEGORY_ORDER, SortDirection.DESCENDING)
                .setPage(1, 1)
                .setPageCount(1);
        final JSONObject result = get(query);
        final JSONArray array = org.b3log.solo.util.QueryResults.array(result);
        if (1 != array.length()) {
            return null;
        }

        return array.optJSONObject(0);
    }

    /**
     * Gets the under category of the category specified by the given id.
     *
     * @param id the given id
     * @return under category, returns {@code null} if not found
     * @throws RepositoryException repository exception
     */
    public JSONObject getUnder(final String id) throws RepositoryException {
        final JSONObject category = get(id);
        if (null == category) {
            return null;
        }

        final Query query = new Query()
                .setFilter(new PropertyFilter(Category.CATEGORY_ORDER, FilterOperator.GREATER_THAN,
                        category.optInt(Category.CATEGORY_ORDER)))
                .addSort(Category.CATEGORY_ORDER, SortDirection.ASCENDING)
                .setPage(1, 1)
                .setPageCount(1);
        final JSONObject result = get(query);
        final JSONArray array = org.b3log.solo.util.QueryResults.array(result);
        if (1 != array.length()) {
            return null;
        }

        return array.optJSONObject(0);
    }

    /**
     * Gets a category by the specified order.
     *
     * @param order the specified order
     * @return category, returns {@code null} if not found
     * @throws RepositoryException repository exception
     */
    public JSONObject getByOrder(final int order) throws RepositoryException {
        final Query query = new Query()
                .setFilter(new PropertyFilter(Category.CATEGORY_ORDER, FilterOperator.EQUAL, order))
                .setPage(1, 1)
                .setPageCount(1);
        final JSONObject result = get(query);
        final JSONArray array = org.b3log.solo.util.QueryResults.array(result);
        if (0 == array.length()) {
            return null;
        }

        return array.optJSONObject(0);
    }

    /**
     * Gets categories ordered by {@code categoryOrder} with accurate published article counts joined against
     * {@code b3_solo_article.articleStatus = PUBLISHED}.
     *
     * @param num the specified number
     * @return a list of categories, returns an empty list if not found
     * @throws RepositoryException repository exception
     */
    public List<JSONObject> getMostUsedCategories(final int num) throws RepositoryException {
        if (num <= 0) {
            return new ArrayList<>();
        }
        final Query query = new Query().addSort(Category.CATEGORY_ORDER, SortDirection.ASCENDING).setPage(1, num)
                .setPageCount(1);
        final List<JSONObject> ret = getList(query);
        if (ret.isEmpty()) {
            return ret;
        }

        final BeanManager beanManager = BeanManager.getInstance();
        final CategoryTagRepository ctRepo = null != categoryTagRepository
                ? categoryTagRepository
                : (null == beanManager ? null : beanManager.getReference(CategoryTagRepository.class));
        final ArticleRepository artRepo = null != articleRepository
                ? articleRepository
                : (null == beanManager ? null : beanManager.getReference(ArticleRepository.class));
        if (null == ctRepo) {
            for (final JSONObject category : ret) {
                category.put(Category.CATEGORY_T_PUBLISHED_ARTICLE_COUNT, 0);
            }
            return ret;
        }

        final Object[] categoryIds = new Object[ret.size()];
        for (int i = 0; i < ret.size(); i++) {
            categoryIds[i] = ret.get(i).optString(Keys.OBJECT_ID);
        }
        final Map<String, Integer> publishedCounts = new HashMap<>();
        final String inPlaceholders = String.join(",", Collections.nCopies(categoryIds.length, "?"));
        final List<JSONObject> countRows;
        if (null != artRepo) {
            final Object[] params = new Object[categoryIds.length + 1];
            params[0] = Article.ARTICLE_STATUS_C_PUBLISHED;
            System.arraycopy(categoryIds, 0, params, 1, categoryIds.length);
            countRows = ctRepo.select(
                    "SELECT ct.category_oId, COUNT(DISTINCT a.oId) AS C FROM " + ctRepo.getName() + " ct "
                            + "INNER JOIN " + artRepo.getName() + " a ON ct.tag_oId = a.oId AND a.articleStatus = ? "
                            + "WHERE ct.category_oId IN (" + inPlaceholders + ") GROUP BY ct.category_oId",
                    params);
        } else {
            countRows = ctRepo.select(
                    "SELECT category_oId, COUNT(*) AS C FROM " + ctRepo.getName()
                            + " WHERE category_oId IN (" + inPlaceholders + ") GROUP BY category_oId",
                    categoryIds);
        }
        if (countRows != null) {
            for (final JSONObject row : countRows) {
                if (null == row) {
                    continue;
                }
                String catId = row.optString(Category.CATEGORY + "_" + Keys.OBJECT_ID);
                if (catId.isEmpty()) {
                    catId = row.optString("CATEGORY_OID", row.optString("category_oid"));
                }
                if (!catId.isEmpty()) {
                    final int cnt = row.has("C") ? row.optInt("C") : row.optInt("c");
                    publishedCounts.put(catId, cnt);
                }
            }
        }

        for (final JSONObject category : ret) {
            final String categoryId = category.optString(Keys.OBJECT_ID);
            category.put(Category.CATEGORY_T_PUBLISHED_ARTICLE_COUNT, publishedCounts.getOrDefault(categoryId, 0));
        }

        return ret;
    }
}
