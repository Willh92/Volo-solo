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

import org.apache.commons.lang3.StringUtils;
import org.b3log.latke.Keys;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.repository.AbstractRepository;
import org.b3log.latke.repository.CompositeFilterOperator;
import org.b3log.latke.repository.FilterOperator;
import org.b3log.latke.repository.PropertyFilter;
import org.b3log.latke.repository.Query;
import org.b3log.latke.repository.RepositoryException;
import org.b3log.latke.repository.SortDirection;
import org.b3log.latke.repository.annotation.Repository;
import org.b3log.solo.cache.ArticleCache;
import org.b3log.solo.cache.StatisticCache;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.model.Article;
import org.b3log.solo.service.ArticleMgmtService;
import org.b3log.solo.service.ArticleQueryService;
import org.b3log.solo.service.HiddenContentService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.util.QueryResults;
import org.b3log.solo.util.Solos;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Article repository.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 0.3.1
 */
@Repository
public class ArticleRepository extends AbstractRepository {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(ArticleRepository.class);

    private static final String RANDOM_SELECT_COLS =
            "oId, articleStatus, articleRandomDouble, articleTitle, articlePermalink, articleCreated, articleUpdated";

    private static final String[] SIDEBAR_PROJECTIONS = {
            Article.ARTICLE_TITLE,
            Article.ARTICLE_PERMALINK,
            Article.ARTICLE_COMMENT_COUNT,
            Article.ARTICLE_VIEW_COUNT,
            Article.ARTICLE_IMG1_URL,
            Article.ARTICLE_STATUS,
            Article.ARTICLE_PUT_TOP,
            Article.ARTICLE_CREATED,
            Article.ARTICLE_UPDATED
    };

    private static final String[] ADJACENT_SUMMARY_PROJECTIONS = {
            Article.ARTICLE_TITLE,
            Article.ARTICLE_ABSTRACT,
            Article.ARTICLE_ABSTRACT_TEXT,
            Article.ARTICLE_PERMALINK,
            Article.ARTICLE_IMG1_URL,
            Article.ARTICLE_VIEW_PWD
    };

    /**
     * Article cache.
     */
    @Inject
    private ArticleCache articleCache;

    @Inject
    private StatisticCache statisticCache;

    @Inject
    private OptionQueryService optionQueryService;

    /**
     * Public constructor.
     */
    public ArticleRepository() {
        super(Article.ARTICLE);
    }

    @Override
    public String add(final JSONObject article) throws RepositoryException {
        if (!article.has(Article.ARTICLE_SCHEDULE_TIME)) {
            article.put(Article.ARTICLE_SCHEDULE_TIME, 0L);
        }
        if (!article.has(Article.ARTICLE_AUDIO_URL)) {
            article.put(Article.ARTICLE_AUDIO_URL, "");
        }
        if (!article.has(Article.ARTICLE_AUDIO_DURATION)) {
            article.put(Article.ARTICLE_AUDIO_DURATION, 0);
        }
        if (!article.has(Article.ARTICLE_AUDIO_SIZE)) {
            article.put(Article.ARTICLE_AUDIO_SIZE, 0L);
        }
        final String id = super.add(article);
        article.put(Keys.OBJECT_ID, id);
        if (null != articleCache) {
            articleCache.putArticle(article);
        }
        if (null != statisticCache) {
            statisticCache.invalidateCounts();
        }
        return id;
    }

    @Override
    public void remove(final String id) throws RepositoryException {
        super.remove(id);

        if (null != articleCache) {
            articleCache.removeArticle(id);
        }
        if (null != statisticCache) {
            statisticCache.invalidateCounts();
        }
    }

    @Override
    public JSONObject get(final String id) throws RepositoryException {
        final JSONObject cached = null == articleCache ? null : articleCache.getArticle(id);
        if (null != cached) {
            return cached;
        }

        final JSONObject raw = super.get(id);
        if (null == raw) {
            return null;
        }

        if (null != articleCache) {
            articleCache.putArticle(raw);
        }

        final JSONObject ret = Solos.clone(raw);
        overlayPendingViewDelta(ret);
        return ret;
    }

    /**
     * Gets the persisted DB baseline snapshot of an article without overlaying un-flushed write-behind view deltas.
     *
     * @param id article ID
     * @return isolated clone of persisted article, or {@code null} if not found
     * @throws RepositoryException repository exception
     */
    public JSONObject getPersisted(final String id) throws RepositoryException {
        if (null == id || id.isEmpty()) {
            return null;
        }
        if (null != articleCache) {
            final JSONObject cached = articleCache.getPersistedArticle(id);
            if (null != cached) {
                return cached;
            }
        }
        if (getClass() != ArticleRepository.class) {
            return get(id);
        }
        final JSONObject raw = super.get(id);
        if (null == raw) {
            return null;
        }
        if (null != articleCache) {
            articleCache.putArticle(raw);
        }
        return Solos.clone(raw);
    }

    /**
     * Loads narrow article metadata for access, status, commentability, and password checks without loading
     * {@code articleContent} or {@code articleAbstract} CLOBs on cache misses.
     *
     * @param articleId article ID
     * @return narrow article JSON object (or cached snapshot), or {@code null} if not found
     * @throws RepositoryException repository exception
     */
    public JSONObject getBriefForAccessCheck(final String articleId) throws RepositoryException {
        if (null == articleId || articleId.isEmpty()) {
            return null;
        }
        if (null != articleCache) {
            final JSONObject cached = articleCache.getPersistedArticle(articleId);
            if (null != cached) {
                return cached;
            }
        }
        if (getClass() != ArticleRepository.class) {
            return get(articleId);
        }
        return getFirst(new Query()
                .setFilter(new PropertyFilter(Keys.OBJECT_ID, FilterOperator.EQUAL, articleId))
                .select(Keys.OBJECT_ID,
                        Article.ARTICLE_STATUS,
                        Article.ARTICLE_COMMENTABLE,
                        Article.ARTICLE_VIEW_PWD,
                        Article.ARTICLE_TITLE,
                        Article.ARTICLE_PERMALINK,
                        Article.ARTICLE_AUTHOR_ID)
                .setPage(1, 1)
                .setPageCount(1));
    }

    @Override
    public void update(final String id, final JSONObject article, final String... propertyNames) throws RepositoryException {
        super.update(id, article, propertyNames);

        article.put(Keys.OBJECT_ID, id);
        final boolean viewCountOnly = null != propertyNames
                && propertyNames.length == 1
                && Article.ARTICLE_VIEW_COUNT.equals(propertyNames[0]);
        if (null != articleCache) {
            if (viewCountOnly) {
                articleCache.setPersistedViewCount(id, article.optInt(Article.ARTICLE_VIEW_COUNT));
            } else if (null != propertyNames && propertyNames.length > 0 && !article.has(Article.ARTICLE_CONTENT)) {
                final JSONObject cached = articleCache.getPersistedArticle(id);
                if (null != cached) {
                    for (final String prop : propertyNames) {
                        if (article.has(prop)) {
                            cached.put(prop, article.opt(prop));
                        }
                    }
                    articleCache.putArticle(cached);
                } else if (containsPermalinkOrStatusProperty(propertyNames)) {
                    articleCache.removeArticle(id);
                }
            } else {
                articleCache.putArticle(article);
            }
        }
        if (!viewCountOnly && null != statisticCache) {
            statisticCache.invalidateCounts();
        }
    }

    private static boolean containsPermalinkOrStatusProperty(final String[] propertyNames) {
        if (null == propertyNames) {
            return false;
        }
        for (final String prop : propertyNames) {
            if (Article.ARTICLE_PERMALINK.equals(prop) || Article.ARTICLE_STATUS.equals(prop)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<JSONObject> getRandomly(final int fetchSize) throws RepositoryException {
        final List<JSONObject> ret = new ArrayList<>();
        if (fetchSize <= 0) {
            return ret;
        }
        final double pivot = Math.random();
        final String query = "SELECT " + RANDOM_SELECT_COLS + " FROM " + getName()
                + " WHERE articleStatus = ? AND articleRandomDouble >= ?"
                + " ORDER BY articleRandomDouble, oId LIMIT ?";
        ret.addAll(select(query, Article.ARTICLE_STATUS_C_PUBLISHED, pivot, fetchSize));
        if (ret.size() < fetchSize) {
            final String wrapQuery = "SELECT " + RANDOM_SELECT_COLS + " FROM " + getName()
                    + " WHERE articleStatus = ? AND (articleRandomDouble < ? OR articleRandomDouble IS NULL)"
                    + " ORDER BY articleRandomDouble, oId LIMIT ?";
            ret.addAll(select(wrapQuery, Article.ARTICLE_STATUS_C_PUBLISHED, pivot, fetchSize - ret.size()));
        }
        overlayPendingViewDeltas(ret);
        return ret;
    }

    /**
     * Gets published articles by the specified author id, current page number and page size.
     *
     * @param authorId       the specified author id
     * @param currentPageNum the specified current page number, MUST greater then {@code 0}
     * @param pageSize       the specified page size(count of a page contains objects), MUST greater then {@code 0}
     * @return paginated articles
     * @throws RepositoryException repository exception
     */
    public JSONObject getByAuthorId(final String authorId, final int currentPageNum, final int pageSize) throws RepositoryException {
        final Query query = new Query()
                .setFilter(CompositeFilterOperator.and(
                        new PropertyFilter(Article.ARTICLE_AUTHOR_ID, FilterOperator.EQUAL, authorId),
                        new PropertyFilter(Article.ARTICLE_STATUS, FilterOperator.EQUAL, Article.ARTICLE_STATUS_C_PUBLISHED)))
                .addSort(Article.ARTICLE_PUT_TOP, SortDirection.DESCENDING)
                .addSort(Article.ARTICLE_UPDATED, SortDirection.DESCENDING)
                .setPage(currentPageNum, pageSize);

        if (null != optionQueryService) {
            final JSONObject preference = optionQueryService.getPreference();
            if (null != preference && !ArticleQueryService.isFullContentListStyle(preference)) {
                ArticleQueryService.selectWithoutContent(query);
            }
        }

        return get(query);
    }

    /**
     * Gets an article by the specified permalink.
     *
     * @param permalink the specified permalink
     * @return an article, returns {@code null} if not found
     * @throws RepositoryException repository exception
     */
    public JSONObject getByPermalink(final String permalink) throws RepositoryException {
        final JSONObject cached = null == articleCache ? null : articleCache.getArticleByPermalink(permalink);
        if (null != cached) {
            return cached;
        }
        if (null != articleCache && articleCache.isMissingPermalink(permalink)) {
            return null;
        }

        final Query query = new Query()
                .setFilter(new PropertyFilter(Article.ARTICLE_PERMALINK, FilterOperator.EQUAL, permalink))
                .setPage(1, 1)
                .setPageCount(1);

        final JSONObject result = get(query);
        final JSONArray array = QueryResults.array(result);
        if (0 == array.length()) {
            if (null != articleCache) {
                articleCache.markMissingPermalink(permalink);
            }
            return null;
        }

        final JSONObject raw = array.optJSONObject(0);
        if (null == raw) {
            return null;
        }
        if (null != articleCache) {
            articleCache.putArticle(raw);
        }

        final JSONObject ret = Solos.clone(raw);
        overlayPendingViewDelta(ret);
        return ret;
    }

    /**
     * Gets post articles recently with the specified fetch size without write-behind view overlay.
     *
     * @param fetchSize the specified fetch size
     * @return a list of recent articles with persisted view counts
     * @throws RepositoryException repository exception
     */
    public List<JSONObject> getRecentArticlesRaw(final int fetchSize) throws RepositoryException {
        final Query query = new Query()
                .setFilter(new PropertyFilter(Article.ARTICLE_STATUS, FilterOperator.EQUAL, Article.ARTICLE_STATUS_C_PUBLISHED))
                .addSort(Article.ARTICLE_UPDATED, SortDirection.DESCENDING)
                .setPage(1, fetchSize).setPageCount(1)
                .select(Keys.OBJECT_ID, SIDEBAR_PROJECTIONS);

        return getList(query);
    }

    /**
     * Gets post articles recently with the specified fetch size.
     *
     * @param fetchSize the specified fetch size
     * @return a list of articles recently, returns an empty list if not found
     * @throws RepositoryException repository exception
     */
    public List<JSONObject> getRecentArticles(final int fetchSize) throws RepositoryException {
        final List<JSONObject> ret = getRecentArticlesRaw(fetchSize);
        overlayPendingViewDeltas(ret);
        return ret;
    }

    /**
     * Gets most commented and published articles with the specified number without write-behind view overlay.
     *
     * @param num the specified number
     * @return a list of most comment articles with persisted view counts
     * @throws RepositoryException repository exception
     */
    public List<JSONObject> getMostCommentArticlesRaw(final int num) throws RepositoryException {
        final Query query = new Query()
                .addSort(Article.ARTICLE_COMMENT_COUNT, SortDirection.DESCENDING)
                .addSort(Article.ARTICLE_UPDATED, SortDirection.DESCENDING)
                .setFilter(new PropertyFilter(Article.ARTICLE_STATUS, FilterOperator.EQUAL, Article.ARTICLE_STATUS_C_PUBLISHED))
                .setPage(1, num).setPageCount(1)
                .select(Keys.OBJECT_ID, SIDEBAR_PROJECTIONS);

        return getList(query);
    }

    /**
     * Gets most commented and published articles with the specified number.
     *
     * @param num the specified number
     * @return a list of most comment articles, returns an empty list if not found
     * @throws RepositoryException repository exception
     */
    public List<JSONObject> getMostCommentArticles(final int num) throws RepositoryException {
        final List<JSONObject> ret = getMostCommentArticlesRaw(num);
        overlayPendingViewDeltas(ret);
        return ret;
    }

    /**
     * Gets most view count and published articles with the specified number without write-behind view overlay.
     *
     * @param num the specified number
     * @return a list of most view count articles with persisted view counts
     * @throws RepositoryException repository exception
     */
    public List<JSONObject> getMostViewCountArticlesRaw(final int num) throws RepositoryException {
        final Query query = new Query()
                .addSort(Article.ARTICLE_VIEW_COUNT, SortDirection.DESCENDING)
                .addSort(Article.ARTICLE_UPDATED, SortDirection.DESCENDING)
                .setFilter(new PropertyFilter(Article.ARTICLE_STATUS, FilterOperator.EQUAL, Article.ARTICLE_STATUS_C_PUBLISHED))
                .setPage(1, num).setPageCount(1)
                .select(Keys.OBJECT_ID, SIDEBAR_PROJECTIONS);

        return getList(query);
    }

    /**
     * Gets most view count and published articles with the specified number.
     *
     * @param num the specified number
     * @return a list of most view count articles, returns an empty list if not found
     * @throws RepositoryException repository exception
     */
    public List<JSONObject> getMostViewCountArticles(final int num) throws RepositoryException {
        final List<JSONObject> ret = getMostViewCountArticlesRaw(num);
        overlayPendingViewDeltas(ret);
        return ret;
    }

    /**
     * Gets the previous article(by create date) by the specified article id.
     *
     * @param articleId the specified article id
     * @return the previous article summary, returns {@code null} if not found
     * @throws RepositoryException repository exception
     */
    public JSONObject getPreviousArticle(final String articleId) throws RepositoryException {
        final Long created = resolveArticleCreated(articleId);
        if (null == created) {
            return null;
        }

        return getPreviousArticle(articleId, created);
    }

    /**
     * Gets the previous article summary by create timestamp without re-loading the current article.
     *
     * @param articleId the specified article id
     * @param created   the current article creation timestamp
     * @return the previous article summary, returns {@code null} if not found
     * @throws RepositoryException repository exception
     */
    public JSONObject getPreviousArticle(final String articleId, final long created) throws RepositoryException {
        final Query query = new Query()
                .setFilter(CompositeFilterOperator.and(
                        new PropertyFilter(Article.ARTICLE_CREATED, FilterOperator.LESS_THAN, created),
                        new PropertyFilter(Article.ARTICLE_STATUS, FilterOperator.EQUAL, Article.ARTICLE_STATUS_C_PUBLISHED)))
                .addSort(Article.ARTICLE_CREATED, SortDirection.DESCENDING)
                .setPage(1, 1).setPageCount(1)
                .select(Keys.OBJECT_ID, ADJACENT_SUMMARY_PROJECTIONS);

        final JSONObject result = get(query);
        final JSONArray array = QueryResults.array(result);
        if (1 != array.length()) {
            return null;
        }

        return toAdjacentSummary(array.optJSONObject(0));
    }

    /**
     * Gets the next article(by create date, oId) by the specified article id.
     *
     * @param articleId the specified article id
     * @return the next article summary, returns {@code null} if not found
     * @throws RepositoryException repository exception
     */
    public JSONObject getNextArticle(final String articleId) throws RepositoryException {
        final Long created = resolveArticleCreated(articleId);
        if (null == created) {
            return null;
        }

        return getNextArticle(articleId, created);
    }

    /**
     * Gets the next article summary by create timestamp without re-loading the current article.
     *
     * @param articleId the specified article id
     * @param created   the current article creation timestamp
     * @return the next article summary, returns {@code null} if not found
     * @throws RepositoryException repository exception
     */
    public JSONObject getNextArticle(final String articleId, final long created) throws RepositoryException {
        final Query query = new Query()
                .setFilter(CompositeFilterOperator.and(
                        new PropertyFilter(Article.ARTICLE_CREATED, FilterOperator.GREATER_THAN, created),
                        new PropertyFilter(Article.ARTICLE_STATUS, FilterOperator.EQUAL, Article.ARTICLE_STATUS_C_PUBLISHED)))
                .addSort(Article.ARTICLE_CREATED, SortDirection.ASCENDING)
                .setPage(1, 1).setPageCount(1)
                .select(Keys.OBJECT_ID, ADJACENT_SUMMARY_PROJECTIONS);

        final JSONObject result = get(query);
        final JSONArray array = QueryResults.array(result);
        if (1 != array.length()) {
            return null;
        }

        return toAdjacentSummary(array.optJSONObject(0));
    }

    private Long resolveArticleCreated(final String articleId) throws RepositoryException {
        if (null == articleId || articleId.isEmpty()) {
            return null;
        }
        if (null != articleCache) {
            final JSONObject cached = articleCache.getPersistedArticle(articleId);
            if (null != cached && cached.has(Article.ARTICLE_CREATED)) {
                return cached.optLong(Article.ARTICLE_CREATED);
            }
        }
        if (getClass() != ArticleRepository.class) {
            final JSONObject current = get(articleId);
            return null == current ? null : current.optLong(Article.ARTICLE_CREATED);
        }
        final JSONObject row = getFirst(new Query()
                .setFilter(new PropertyFilter(Keys.OBJECT_ID, FilterOperator.EQUAL, articleId))
                .select(Keys.OBJECT_ID, Article.ARTICLE_CREATED)
                .setPage(1, 1)
                .setPageCount(1));
        return null == row ? null : row.optLong(Article.ARTICLE_CREATED);
    }

    private static JSONObject toAdjacentSummary(final JSONObject article) throws RepositoryException {
        if (null == article) {
            return null;
        }
        final JSONObject ret = new JSONObject();
        try {
            ret.put(Keys.OBJECT_ID, article.optString(Keys.OBJECT_ID));
            ret.put(Article.ARTICLE_TITLE, article.getString(Article.ARTICLE_TITLE));
            ret.put(Article.ARTICLE_PERMALINK, article.getString(Article.ARTICLE_PERMALINK));
            if (StringUtils.isNotBlank(article.optString(Article.ARTICLE_VIEW_PWD))) {
                ret.put(Article.ARTICLE_ABSTRACT, "");
                ret.put(Article.ARTICLE_ABSTRACT_TEXT, "");
                ret.put(Article.ARTICLE_IMG1_URL, "");
            } else {
                final String rawAbstractText = article.optString(Article.ARTICLE_ABSTRACT_TEXT);
                final String safeAbstractText = HiddenContentService.containsLockSyntax(rawAbstractText)
                        ? HiddenContentService.stripForExcerptOrImage(rawAbstractText)
                        : rawAbstractText;
                ret.put(Article.ARTICLE_ABSTRACT_TEXT, safeAbstractText);
                final String fallbackAbstract = HiddenContentService.stripForExcerptOrImage(
                        article.optString(Article.ARTICLE_ABSTRACT));
                ret.put(Article.ARTICLE_ABSTRACT,
                        !safeAbstractText.isEmpty() ? safeAbstractText : fallbackAbstract);
                ret.put(Article.ARTICLE_IMG1_URL, article.optString(Article.ARTICLE_IMG1_URL));
            }
        } catch (final JSONException e) {
            throw new RepositoryException(e);
        }
        return ret;
    }

    /**
     * Determines an article specified by the given article id is published.
     *
     * @param articleId the given article id
     * @return {@code true} if it is published, {@code false} otherwise
     * @throws RepositoryException repository exception
     */
    public boolean isPublished(final String articleId) throws RepositoryException {
        if (null == articleId || articleId.isEmpty()) {
            return false;
        }
        if (null != articleCache) {
            final JSONObject cached = articleCache.getPersistedArticle(articleId);
            if (null != cached) {
                return Article.ARTICLE_STATUS_C_PUBLISHED == cached.optInt(Article.ARTICLE_STATUS);
            }
        }
        if (getClass() != ArticleRepository.class) {
            final JSONObject article = get(articleId);
            return null != article && Article.ARTICLE_STATUS_C_PUBLISHED == article.optInt(Article.ARTICLE_STATUS);
        }
        final JSONObject row = getFirst(new Query()
                .setFilter(new PropertyFilter(Keys.OBJECT_ID, FilterOperator.EQUAL, articleId))
                .select(Keys.OBJECT_ID, Article.ARTICLE_STATUS)
                .setPage(1, 1)
                .setPageCount(1));
        return null != row && Article.ARTICLE_STATUS_C_PUBLISHED == row.optInt(Article.ARTICLE_STATUS);
    }

    /**
     * Resolves the subset of {@code articleIds} corresponding to published articles, consulting
     * {@link ArticleCache} first and executing a single batched {@code SELECT oId} query for uncached IDs.
     *
     * @param articleIds candidate article IDs
     * @return set of published article IDs
     * @throws RepositoryException repository exception
     */
    public Set<String> filterPublishedArticleIds(final Collection<String> articleIds)
            throws RepositoryException {
        if (null == articleIds || articleIds.isEmpty()) {
            return Collections.emptySet();
        }
        final Set<String> publishedIds = new HashSet<>();
        final List<String> uncachedIds = new ArrayList<>();
        for (final String id : articleIds) {
            if (null == id || id.isBlank()) {
                continue;
            }
            final JSONObject cached = null == articleCache ? null : articleCache.getPersistedArticle(id);
            if (null != cached) {
                if (Article.ARTICLE_STATUS_C_PUBLISHED == cached.optInt(Article.ARTICLE_STATUS)) {
                    publishedIds.add(id);
                }
            } else {
                uncachedIds.add(id);
            }
        }
        if (!uncachedIds.isEmpty()) {
            final String placeholders = String.join(",", Collections.nCopies(uncachedIds.size(), "?"));
            final Object[] params = new Object[uncachedIds.size() + 1];
            int idx = 0;
            for (final String id : uncachedIds) {
                params[idx++] = id;
            }
            params[idx] = Article.ARTICLE_STATUS_C_PUBLISHED;
            final String sql = "SELECT oId FROM " + getName()
                    + " WHERE oId IN (" + placeholders + ") AND articleStatus = ?";
            final List<JSONObject> rows = select(sql, params);
            if (null != rows) {
                for (final JSONObject row : rows) {
                    if (null == row) {
                        continue;
                    }
                    String oid = row.optString(Keys.OBJECT_ID);
                    if (oid.isEmpty()) {
                        oid = row.optString("OID");
                    }
                    if (oid.isEmpty()) {
                        oid = row.optString("oid");
                    }
                    if (!oid.isEmpty()) {
                        publishedIds.add(oid);
                    }
                }
            }
        }
        return publishedIds;
    }

    private static void overlayPendingViewDelta(final JSONObject article) {
        if (null == article || !article.has(Article.ARTICLE_VIEW_COUNT)) {
            return;
        }
        final long delta = ArticleMgmtService.peekPendingViewDelta(article.optString(Keys.OBJECT_ID));
        if (delta > 0L) {
            article.put(Article.ARTICLE_VIEW_COUNT,
                    (int) Math.min(Integer.MAX_VALUE, Math.max(0L, article.optLong(Article.ARTICLE_VIEW_COUNT) + delta)));
        }
    }

    private static void overlayPendingViewDeltas(final List<JSONObject> articles) {
        if (null == articles || articles.isEmpty()) {
            return;
        }
        for (final JSONObject article : articles) {
            overlayPendingViewDelta(article);
        }
    }
}
