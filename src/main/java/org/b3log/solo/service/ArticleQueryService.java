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
package org.b3log.solo.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.b3log.latke.Keys;
import org.b3log.latke.ioc.Inject;
import org.apache.logging.log4j.Level;
import org.b3log.solo.logging.Logger;
import org.b3log.latke.model.Pagination;
import org.b3log.latke.model.Role;
import org.b3log.latke.model.User;
import org.b3log.latke.repository.CompositeFilter;
import org.b3log.latke.repository.CompositeFilterOperator;
import org.b3log.latke.repository.Filter;
import org.b3log.latke.repository.FilterOperator;
import org.b3log.latke.repository.PropertyFilter;
import org.b3log.latke.repository.Query;
import org.b3log.latke.repository.RepositoryException;
import org.b3log.latke.repository.SortDirection;
import org.b3log.latke.service.LangPropsService;
import org.b3log.latke.service.ServiceException;
import org.b3log.latke.service.annotation.Service;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.util.CollectionUtils;
import org.b3log.latke.util.Paginator;
import org.b3log.latke.util.Stopwatchs;
import org.b3log.solo.cache.JsonCloneHelper;
import org.b3log.solo.cache.UserCache;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Category;
import org.b3log.solo.model.Common;
import org.b3log.solo.model.Option;
import org.b3log.solo.model.Sign;
import org.b3log.solo.model.Tag;
import org.b3log.solo.repository.ArchiveDateArticleRepository;
import org.b3log.solo.repository.ArchiveDateRepository;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.repository.CategoryRepository;
import org.b3log.solo.repository.CategoryTagRepository;
import org.b3log.solo.repository.TagArticleRepository;
import org.b3log.solo.repository.TagRepository;
import org.b3log.solo.repository.UserRepository;
import org.b3log.solo.util.AdminListSearch;
import org.b3log.solo.util.Markdowns;
import org.b3log.solo.util.Solos;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Article query service.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @author <a href="https://ld246.com/member/armstrong">ArmstrongCN</a>
 * @author <a href="http://zephyr.b3log.org">Zephyr</a>
 * @author <a href="http://vanessa.b3log.org">Liyuan Li</a>
 * @since 0.3.5
 */
@Service
public class ArticleQueryService {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(ArticleQueryService.class);

    /**
     * Request flag indicating the caller will run {@link DataModelService#setArticlesExProperties}
     * and therefore {@link #getArticles(JSONObject)} must not pre-overlay write-behind view deltas.
     */
    public static final String SKIP_VIEW_OVERLAY = "_skipViewOverlay";

    private record CachedSigns(String raw, List<JSONObject> parsed) {
    }

    private volatile CachedSigns cachedSigns;

    /**
     * All persisted columns of the {@code article} table defined in {@code repository.json}.
     */
    private static final List<String> ALL_ARTICLE_DB_COLUMNS = List.of(
            Keys.OBJECT_ID,
            Article.ARTICLE_TITLE,
            Article.ARTICLE_ABSTRACT,
            Article.ARTICLE_ABSTRACT_TEXT,
            Article.ARTICLE_TAGS_REF,
            Article.ARTICLE_AUTHOR_ID,
            Article.ARTICLE_COMMENT_COUNT,
            Article.ARTICLE_VIEW_COUNT,
            Article.ARTICLE_CONTENT,
            Article.ARTICLE_PERMALINK,
            Article.ARTICLE_PUT_TOP,
            Article.ARTICLE_CREATED,
            Article.ARTICLE_UPDATED,
            Article.ARTICLE_RANDOM_DOUBLE,
            Article.ARTICLE_SIGN_ID,
            Article.ARTICLE_COMMENTABLE,
            Article.ARTICLE_VIEW_PWD,
            Article.ARTICLE_IMG1_URL,
            Article.ARTICLE_STATUS,
            Article.ARTICLE_SCHEDULE_TIME,
            Article.ARTICLE_AUDIO_URL,
            Article.ARTICLE_AUDIO_DURATION,
            Article.ARTICLE_AUDIO_SIZE
    );

    private static final String[] ARTICLE_COLUMNS_WITHOUT_CONTENT = ALL_ARTICLE_DB_COLUMNS.stream()
            .filter(col -> !Article.ARTICLE_CONTENT.equals(col))
            .toArray(String[]::new);

    public static String[] articleColumnsWithoutContent() {
        return ARTICLE_COLUMNS_WITHOUT_CONTENT.clone();
    }

    public static Query selectWithoutContent(final Query query) {
        if (null != query && ARTICLE_COLUMNS_WITHOUT_CONTENT.length > 0) {
            query.select(ARTICLE_COLUMNS_WITHOUT_CONTENT[0],
                    java.util.Arrays.copyOfRange(ARTICLE_COLUMNS_WITHOUT_CONTENT, 1, ARTICLE_COLUMNS_WITHOUT_CONTENT.length));
        }
        return query;
    }

    public static boolean isFullContentListStyle(final JSONObject preference) {
        if (null == preference) {
            return false;
        }
        final String style = preference.optString(Option.ID_C_ARTICLE_LIST_STYLE);
        return "titleAndContent".equals(style) || "2".equals(style);
    }

    /**
     * User repository.
     */
    @Inject
    private UserRepository userRepository;

    /**
     * Article repository.
     */
    @Inject
    private ArticleRepository articleRepository;

    /**
     * Category-Tag repository.
     */
    @Inject
    private CategoryTagRepository categoryTagRepository;

    @Inject
    private CategoryRepository categoryRepository;

    /**
     * User service.
     */
    @Inject
    private UserQueryService userQueryService;

    /**
     * Option query service.
     */
    @Inject
    private OptionQueryService optionQueryService;

    /**
     * Tag repository.
     */
    @Inject
    private TagRepository tagRepository;

    /**
     * Tag-Article repository.
     */
    @Inject
    private TagArticleRepository tagArticleRepository;

    /**
     * Archive date-Article repository.
     */
    @Inject
    private ArchiveDateArticleRepository archiveDateArticleRepository;

    @Inject
    private ArchiveDateRepository archiveDateRepository;

    /**
     * Statistic query service.
     */
    @Inject
    private StatisticQueryService statisticQueryService;

    /**
     * Language service.
     */
    @Inject
    private LangPropsService langPropsService;

    /**
     * Category query service.
     */
    @Inject
    private CategoryQueryService categoryQueryService;

    @Inject
    private HiddenContentService hiddenContentService;

    @Inject
    private SeriesAndBookshelfService seriesAndBookshelfService;

    @Inject
    private ShareTokenService shareTokenService;

    @Inject
    private UserCache userCache;

    @Inject
    private InstantSearchService instantSearchService;

    /**
     * Searches articles with the specified keyword.
     *
     * @param keyword        the specified keyword
     * @param currentPageNum the specified current page number
     * @param pageSize       the specified page size
     * @return result
     */
    public JSONObject searchKeyword(final String keyword, final int currentPageNum, final int pageSize) {
        final JSONObject ret = new JSONObject();
        ret.put(Article.ARTICLES, (Object) Collections.emptyList());

        final JSONObject pagination = new JSONObject();
        ret.put(Pagination.PAGINATION, pagination);
        pagination.put(Pagination.PAGINATION_PAGE_COUNT, 0);
        pagination.put(Pagination.PAGINATION_PAGE_NUMS, (Object) Collections.emptyList());

        final String normalized = AdminListSearch.normalizeKeyword(keyword);
        if (normalized.isEmpty()) {
            return ret;
        }

        try {
            final JSONObject preference = null == optionQueryService ? null : optionQueryService.getPreference();
            final boolean fullContentList = isFullContentListStyle(preference);
            final String likePattern = buildSearchLikePattern(normalized);
            final Query query = new Query().setFilter(
                    CompositeFilterOperator.and(
                            new PropertyFilter(Article.ARTICLE_STATUS, FilterOperator.EQUAL,
                                    Article.ARTICLE_STATUS_C_PUBLISHED),
                            CompositeFilterOperator.or(
                                    new PropertyFilter(Article.ARTICLE_TITLE, FilterOperator.LIKE, likePattern),
                                    new PropertyFilter(Article.ARTICLE_TAGS_REF, FilterOperator.LIKE, likePattern),
                                    CompositeFilterOperator.and(
                                            new PropertyFilter(Article.ARTICLE_VIEW_PWD, FilterOperator.EQUAL, ""),
                                            new PropertyFilter(Article.ARTICLE_CONTENT, FilterOperator.LIKE, likePattern)))))
                    .addSort(Article.ARTICLE_UPDATED, SortDirection.DESCENDING)
                    .setPage(currentPageNum, pageSize);
            if (!fullContentList) {
                selectWithoutContent(query);
            }

            final JSONObject result = articleRepository.get(query);

            final int pageCount = null == result.optJSONObject(Pagination.PAGINATION)
                    ? 0
                    : result.optJSONObject(Pagination.PAGINATION).optInt(Pagination.PAGINATION_PAGE_COUNT);
            final int windowSize = null == preference
                    ? 10
                    : preference.optInt(Option.ID_C_ARTICLE_LIST_PAGINATION_WINDOW_SIZE, 10);
            final List<Integer> pageNums = Paginator.paginate(currentPageNum, pageSize, pageCount, windowSize);
            pagination.put(Pagination.PAGINATION_PAGE_COUNT, pageCount);
            pagination.put(Pagination.PAGINATION_PAGE_NUMS, (Object) pageNums);

            final List<JSONObject> articles = CollectionUtils.jsonArrayToList(org.b3log.solo.util.QueryResults.array(result));
            filterVerifiedPublicSearchMatches(articles, normalized);
            if (1 == currentPageNum && articles.isEmpty()) {
                pagination.put(Pagination.PAGINATION_PAGE_COUNT, 0);
                pagination.put(Pagination.PAGINATION_PAGE_NUMS, (Object) Collections.emptyList());
            }
            ret.put(Article.ARTICLES, (Object) articles);
        } catch (final RepositoryException e) {
            LOGGER.log(Level.ERROR, "Searches articles error", e);
        }

        return ret;
    }

    private static String buildSearchLikePattern(final String normalized) {
        if (!AdminListSearch.containsLikeMeta(normalized)) {
            return "%" + normalized + "%";
        }
        if (org.b3log.latke.Latkes.RuntimeDatabase.H2 == org.b3log.latke.Latkes.getRuntimeDatabase()) {
            // H2's default LIKE without ESCAPE treats '\' literally; pre-filter by longest literal token and post-verify.
            String bestToken = "";
            final StringBuilder cur = new StringBuilder();
            for (int i = 0; i < normalized.length(); i++) {
                final char c = normalized.charAt(i);
                if ('%' == c || '_' == c) {
                    if (cur.length() > bestToken.length()) {
                        bestToken = cur.toString();
                    }
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
            if (cur.length() > bestToken.length()) {
                bestToken = cur.toString();
            }
            return "%" + bestToken + "%";
        }
        return "%" + AdminListSearch.escapeLikeLiteral(normalized) + "%";
    }

    private void filterVerifiedPublicSearchMatches(final List<JSONObject> articles, final String normalized)
            throws RepositoryException {
        if (null == articles || articles.isEmpty()) {
            return;
        }
        final String needle = normalized.toLowerCase(java.util.Locale.ROOT);
        final List<String> needContentCheckIds = new ArrayList<>();
        for (final JSONObject article : articles) {
            if (null == article) {
                continue;
            }
            final String title = article.optString(Article.ARTICLE_TITLE, "");
            final String tags = article.optString(Article.ARTICLE_TAGS_REF, "");
            if (StringUtils.containsIgnoreCase(title, needle) || StringUtils.containsIgnoreCase(tags, needle)) {
                continue;
            }
            if (StringUtils.isNotBlank(article.optString(Article.ARTICLE_VIEW_PWD))) {
                continue;
            }
            if (article.has(Article.ARTICLE_CONTENT)) {
                continue;
            }
            final String absText = article.optString(Article.ARTICLE_ABSTRACT_TEXT, "");
            final String rawAbs = article.optString(Article.ARTICLE_ABSTRACT, "");
            if (!HiddenContentService.containsLockSyntax(absText)
                    && !HiddenContentService.containsLockSyntax(rawAbs)
                    && StringUtils.containsIgnoreCase(absText, needle)) {
                continue;
            }
            final String id = article.optString(Keys.OBJECT_ID);
            if (!id.isEmpty()) {
                needContentCheckIds.add(id);
            }
        }
        final Map<String, JSONObject> loadedBodies = new HashMap<>();
        if (!needContentCheckIds.isEmpty()) {
            final List<String> uniqueIds = new ArrayList<>(new LinkedHashSet<>(needContentCheckIds));
            for (int start = 0; start < uniqueIds.size(); start += 50) {
                final List<String> batchIds = uniqueIds.subList(start, Math.min(start + 50, uniqueIds.size()));
                final Query bodyQuery = new Query()
                        .setFilter(new PropertyFilter(Keys.OBJECT_ID, FilterOperator.IN, batchIds))
                        .select(Keys.OBJECT_ID, Article.ARTICLE_TITLE, Article.ARTICLE_TAGS_REF,
                                Article.ARTICLE_VIEW_PWD, Article.ARTICLE_ABSTRACT, Article.ARTICLE_CONTENT)
                        .setPage(1, batchIds.size())
                        .setPageCount(1);
                for (final JSONObject row : articleRepository.getList(bodyQuery)) {
                    if (null != row) {
                        loadedBodies.put(row.optString(Keys.OBJECT_ID), row);
                    }
                }
            }
        }
        articles.removeIf(article -> !matchesVerifiedPublicSearch(article, loadedBodies, needle));
    }

    private static boolean matchesVerifiedPublicSearch(final JSONObject article,
                                                       final Map<String, JSONObject> loadedBodies,
                                                       final String needle) {
        if (null == article) {
            return false;
        }
        final String title = article.optString(Article.ARTICLE_TITLE, "");
        final String tags = article.optString(Article.ARTICLE_TAGS_REF, "");
        if (StringUtils.containsIgnoreCase(title, needle) || StringUtils.containsIgnoreCase(tags, needle)) {
            return true;
        }
        if (StringUtils.isNotBlank(article.optString(Article.ARTICLE_VIEW_PWD))) {
            return false;
        }
        if (article.has(Article.ARTICLE_CONTENT)) {
            final String publicAbstract = Article.sanitizePublicMarkdown(article.optString(Article.ARTICLE_ABSTRACT, ""));
            if (StringUtils.containsIgnoreCase(publicAbstract, needle)) {
                return true;
            }
            final String publicContent = Article.sanitizePublicMarkdown(article.optString(Article.ARTICLE_CONTENT, ""));
            return StringUtils.containsIgnoreCase(publicContent, needle);
        }
        final String absText = article.optString(Article.ARTICLE_ABSTRACT_TEXT, "");
        final String rawAbs = article.optString(Article.ARTICLE_ABSTRACT, "");
        if (!HiddenContentService.containsLockSyntax(absText)
                && !HiddenContentService.containsLockSyntax(rawAbs)
                && StringUtils.containsIgnoreCase(absText, needle)) {
            return true;
        }
        final JSONObject full = loadedBodies.get(article.optString(Keys.OBJECT_ID));
        if (null == full || StringUtils.isNotBlank(full.optString(Article.ARTICLE_VIEW_PWD))) {
            return false;
        }
        if (StringUtils.containsIgnoreCase(full.optString(Article.ARTICLE_TITLE, ""), needle)
                || StringUtils.containsIgnoreCase(full.optString(Article.ARTICLE_TAGS_REF, ""), needle)) {
            return true;
        }
        final String fullPublicAbs = Article.sanitizePublicMarkdown(full.optString(Article.ARTICLE_ABSTRACT, ""));
        if (StringUtils.containsIgnoreCase(fullPublicAbs, needle)) {
            return true;
        }
        final String fullPublicBody = Article.sanitizePublicMarkdown(full.optString(Article.ARTICLE_CONTENT, ""));
        return StringUtils.containsIgnoreCase(fullPublicBody, needle);
    }

    /**
     * Gets category articles.
     *
     * @param categoryId     the specified category id
     * @param currentPageNum the specified current page number
     * @param pageSize       the specified page size
     * @return result
     * @throws ServiceException service exception
     */
    public JSONObject getCategoryArticles(final String categoryId, final int currentPageNum, final int pageSize)
            throws ServiceException {
        final JSONObject ret = new JSONObject();
        ret.put(Keys.RESULTS, (Object) Collections.emptyList());

        final JSONObject pagination = new JSONObject();
        ret.put(Pagination.PAGINATION, pagination);
        pagination.put(Pagination.PAGINATION_PAGE_COUNT, 0);
        pagination.put(Pagination.PAGINATION_PAGE_NUMS, (Object) Collections.emptyList());

        try {
            final JSONArray categoryTags = org.b3log.solo.util.QueryResults.array(
                    categoryTagRepository.getByCategoryId(categoryId, 1, Integer.MAX_VALUE));
            if (categoryTags.length() <= 0) {
                return ret;
            }

            final Set<String> candidateIds = new LinkedHashSet<>();
            for (int i = 0; i < categoryTags.length(); i++) {
                final JSONObject row = categoryTags.optJSONObject(i);
                if (null != row) {
                    final String id = row.optString(Tag.TAG + "_" + Keys.OBJECT_ID);
                    if (StringUtils.isNotBlank(id)) {
                        candidateIds.add(id);
                    }
                }
            }
            if (candidateIds.isEmpty()) {
                return ret;
            }

            final JSONObject preference = null != optionQueryService ? optionQueryService.getPreference() : null;
            final List<Filter> filters = new ArrayList<>(2);
            filters.add(new PropertyFilter(Keys.OBJECT_ID, FilterOperator.IN, candidateIds));
            filters.add(new PropertyFilter(Article.ARTICLE_STATUS, FilterOperator.EQUAL, Article.ARTICLE_STATUS_C_PUBLISHED));
            final Query query = new Query()
                    .setFilter(new CompositeFilter(CompositeFilterOperator.AND, filters))
                    .setPage(currentPageNum, pageSize)
                    .addSort(Keys.OBJECT_ID, SortDirection.DESCENDING);
            if (!isFullContentListStyle(preference)) {
                selectWithoutContent(query);
            }

            final JSONObject queryResult = articleRepository.get(query);
            final JSONObject repoPagination = queryResult.optJSONObject(Pagination.PAGINATION);
            final int pageCount = null == repoPagination ? 0 : repoPagination.optInt(Pagination.PAGINATION_PAGE_COUNT);
            final int recordCount = null == repoPagination ? 0 : repoPagination.optInt(Pagination.PAGINATION_RECORD_COUNT);
            final int windowSize = null == preference
                    ? Option.DefaultPreference.DEFAULT_ARTICLE_LIST_PAGINATION_WINDOW_SIZE
                    : preference.optInt(Option.ID_C_ARTICLE_LIST_PAGINATION_WINDOW_SIZE);
            final List<Integer> pageNums = Paginator.paginate(currentPageNum, pageSize, pageCount, windowSize);
            pagination.put(Pagination.PAGINATION_PAGE_COUNT, pageCount);
            pagination.put(Pagination.PAGINATION_PAGE_NUMS, (Object) pageNums);
            pagination.put(Pagination.PAGINATION_RECORD_COUNT, recordCount);

            final List<JSONObject> articles = new ArrayList<>();
            final JSONArray articleArray = org.b3log.solo.util.QueryResults.array(queryResult);
            for (int i = 0; i < articleArray.length(); i++) {
                final JSONObject article = articleArray.optJSONObject(i);
                article.put(Article.ARTICLE_CREATE_TIME, article.optLong(Article.ARTICLE_CREATED));
                article.put(Article.ARTICLE_T_CREATE_DATE, new Date(article.optLong(Article.ARTICLE_CREATED)));
                article.put(Article.ARTICLE_T_UPDATE_DATE, new Date(article.optLong(Article.ARTICLE_UPDATED)));
                articles.add(article);
            }
            ret.put(Keys.RESULTS, (Object) articles);

            return ret;
        } catch (final RepositoryException e) {
            LOGGER.log(Level.ERROR, "Gets category articles error", e);

            throw new ServiceException(e);
        }
    }

    /**
     * Can the specified user access an article specified by the given article id?
     *
     * @param articleId the given article id
     * @param user      the specified user
     * @return {@code true} if the current user can access the article,
     *         {@code false} otherwise
     * @throws ServiceException service exception
     */
    public boolean canAccessArticle(final String articleId, final JSONObject user) throws ServiceException {
        if (StringUtils.isBlank(articleId)) {
            return false;
        }

        if (null == user) {
            return false;
        }

        if (Role.ADMIN_ROLE.equals(user.optString(User.USER_ROLE))) {
            return true;
        }

        try {
            final JSONObject article = articleRepository.getBriefForAccessCheck(articleId);
            if (null == article) {
                return false;
            }
            final String currentUserId = user.getString(Keys.OBJECT_ID);

            return currentUserId.equals(article.optString(Article.ARTICLE_AUTHOR_ID));
        } catch (final Exception e) {
            throw new ServiceException(e);
        }
    }

    /**
     * Gets time of the recent updated article.
     *
     * @return time of the recent updated article, returns {@code 0} if not found
     */
    public long getRecentArticleTime() {
        try {
            final List<JSONObject> recentArticles = articleRepository.getRecentArticles(1);
            if (recentArticles.isEmpty()) {
                return 0;
            }

            final JSONObject recentArticle = recentArticles.get(0);

            return recentArticle.getLong(Article.ARTICLE_UPDATED);
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Gets recent article time failed", e);

            return 0;
        }
    }

    /**
     * Gets the specified article's author.
     * <p>
     * The specified article has a property {@value Article#ARTICLE_AUTHOR_ID}, this
     * method will use this property to
     * get a user from users.
     * </p>
     * <p>
     * If can't find the specified article's author (i.e. the author has been
     * removed by administrator), returns
     * administrator.
     * </p>
     *
     * @param article the specified article
     * @return user, {@code null} if not found
     * @throws ServiceException service exception
     */
    public JSONObject getAuthor(final JSONObject article) throws ServiceException {
        try {
            final String userId = article.optString(Article.ARTICLE_AUTHOR_ID);
            JSONObject ret = StringUtils.isBlank(userId) ? null : userRepository.get(userId);
            if (null == ret) {
                // This author may be deleted by admin; fall back to admin (missing userId is negative-cached).
                ret = userRepository.getAdmin();
            }

            return ret;
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Gets author of article [id={0}] failed", article.optString(Keys.OBJECT_ID));

            throw new ServiceException(e);
        }
    }

    /**
     * Batch-preloads uncached authors for a list of articles in a single {@code IN (...)} query,
     * populating {@link UserCache} and its negative cache so subsequent {@link #getAuthor(JSONObject)}
     * calls execute zero SQL.
     */
    public Map<String, JSONObject> preloadAuthors(final List<JSONObject> articles) {
        if (null == articles || articles.isEmpty() || null == userRepository) {
            return Collections.emptyMap();
        }
        final Set<String> authorIds = new LinkedHashSet<>();
        for (final JSONObject article : articles) {
            if (null != article) {
                final String id = article.optString(Article.ARTICLE_AUTHOR_ID);
                if (StringUtils.isNotBlank(id)) {
                    authorIds.add(id);
                }
            }
        }
        return preloadAuthorsByIds(authorIds);
    }

    private Map<String, JSONObject> preloadAuthorsFromArray(final JSONArray articles) {
        if (null == articles || 0 == articles.length() || null == userRepository) {
            return Collections.emptyMap();
        }
        final Set<String> authorIds = new LinkedHashSet<>();
        for (int i = 0; i < articles.length(); i++) {
            final JSONObject article = articles.optJSONObject(i);
            if (null != article) {
                final String id = article.optString(Article.ARTICLE_AUTHOR_ID);
                if (StringUtils.isNotBlank(id)) {
                    authorIds.add(id);
                }
            }
        }
        return preloadAuthorsByIds(authorIds);
    }

    private Map<String, JSONObject> preloadAuthorsByIds(final Set<String> authorIds) {
        if (null == authorIds || authorIds.isEmpty() || null == userRepository) {
            return Collections.emptyMap();
        }
        final Map<String, JSONObject> resolved = new HashMap<>(authorIds.size());
        final List<String> uncached = new ArrayList<>();
        for (final String id : authorIds) {
            if (null != userCache) {
                final JSONObject cached = userCache.getUser(id);
                if (null != cached) {
                    resolved.put(id, cached);
                    continue;
                }
                if (userCache.isMissingUser(id)) {
                    continue;
                }
            }
            uncached.add(id);
        }
        if (!uncached.isEmpty()) {
            try {
                final Query q = new Query()
                        .setFilter(new PropertyFilter(Keys.OBJECT_ID, FilterOperator.IN, uncached))
                        .setPage(1, uncached.size())
                        .setPageCount(1);
                final List<JSONObject> rows = userRepository.getList(q);
                final Set<String> foundIds = new HashSet<>();
                if (null != rows) {
                    for (final JSONObject user : rows) {
                        if (null == user) {
                            continue;
                        }
                        final String id = user.optString(Keys.OBJECT_ID);
                        if (!id.isEmpty()) {
                            foundIds.add(id);
                            resolved.put(id, user);
                            if (null != userCache) {
                                userCache.putUser(user);
                            }
                        }
                    }
                }
                if (null != userCache && userRepository.getClass() == UserRepository.class) {
                    for (final String id : uncached) {
                        if (!foundIds.contains(id)) {
                            userCache.markMissingUser(id);
                        }
                    }
                }
            } catch (final Exception e) {
                LOGGER.log(Level.DEBUG, "Batch author preload fell back to per-article lookup", e);
            }
        }
        return resolved;
    }

    private List<JSONObject> parseSignsCached(final String rawSigns) throws JSONException {
        if (StringUtils.isBlank(rawSigns)) {
            return Collections.emptyList();
        }
        final CachedSigns snapshot = cachedSigns;
        if (null != snapshot && rawSigns.equals(snapshot.raw())) {
            return snapshot.parsed();
        }
        final JSONArray arr = new JSONArray(rawSigns);
        final List<JSONObject> list = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject item = arr.optJSONObject(i);
            if (null != item) {
                list.add(JsonCloneHelper.deepCloneObject(item));
            }
        }
        final List<JSONObject> frozen = Collections.unmodifiableList(list);
        cachedSigns = new CachedSigns(rawSigns, frozen);
        return frozen;
    }

    /**
     * Gets the sign of an article specified by the sign id.
     *
     * @param signId     the specified article id
     * @param preference the specified preference
     * @return article sign, returns the default sign (which oId is "1") if not
     *         found
     * @throws JSONException json exception
     */
    public JSONObject getSign(final String signId, final JSONObject preference) throws JSONException {
        final List<JSONObject> signs = parseSignsCached(preference.getString(Option.ID_C_SIGNS));
        JSONObject defaultSign = null;
        for (final JSONObject ret : signs) {
            if (signId.equals(ret.optString(Keys.OBJECT_ID))) {
                return Solos.clone(ret);
            }

            if ("1".equals(ret.optString(Keys.OBJECT_ID))) {
                defaultSign = ret;
            }
        }

        LOGGER.log(Level.WARN, "Can not find the sign [id={0}], returns a default sign [id=1]", signId);

        return Solos.clone(defaultSign);
    }

    /**
     * Determines the specified article has updated.
     *
     * @param article the specified article
     * @return {@code true} if it has updated, {@code false} otherwise
     * @throws JSONException json exception
     */
    public boolean hasUpdated(final JSONObject article) throws JSONException {
        final long updateDate = article.getLong(Article.ARTICLE_UPDATED);
        final long createDate = article.getLong(Article.ARTICLE_CREATED);

        return createDate != updateDate;
    }

    /**
     * Gets the recent articles with the specified fetch size.
     *
     * @param fetchSize the specified fetch size
     * @return a list of json object, its size less or equal to the specified fetch
     *         size
     */
    public List<JSONObject> getRecentArticles(final int fetchSize) {
        try {
            return articleRepository.getRecentArticles(fetchSize);
        } catch (final RepositoryException e) {
            LOGGER.log(Level.ERROR, "Gets recent articles failed", e);

            return Collections.emptyList();
        }
    }

    /**
     * Gets an article by the specified article id.
     * <p>
     * <b>Note</b>: The article content and abstract is raw (no editor type
     * processing).
     * </p>
     *
     * @param articleId the specified article id
     * @return article wrapper JSON, returns {@code null} if not found
     * @throws ServiceException service exception
     */
    public JSONObject getArticle(final String articleId) throws ServiceException {
        try {
            final JSONObject ret = new JSONObject();
            final JSONObject article = articleRepository.get(articleId);
            if (null == article) {
                return null;
            }

            ret.put(Article.ARTICLE, article);

            // Tags
            final JSONArray tags = new JSONArray();
            for (final JSONObject tag : tagRepository.getByArticleId(articleId)) {
                tags.put(tag);
            }
            article.put(Article.ARTICLE_TAGS_REF, tags);

            // Signs
            final JSONObject preference = optionQueryService.getPreference();
            final List<JSONObject> parsedSigns = parseSignsCached(preference.getString(Option.ID_C_SIGNS));
            final JSONArray signsArray = new JSONArray();
            for (final JSONObject sign : parsedSigns) {
                signsArray.put(Solos.clone(sign));
            }
            article.put(Sign.SIGNS, signsArray);
            // Remove unused properties
            article.remove(Article.ARTICLE_AUTHOR_ID);
            article.remove(Article.ARTICLE_COMMENT_COUNT);
            article.remove(Article.ARTICLE_PUT_TOP);
            article.remove(Article.ARTICLE_UPDATED);
            article.remove(Article.ARTICLE_VIEW_COUNT);
            article.remove(Article.ARTICLE_RANDOM_DOUBLE);
            if (null != seriesAndBookshelfService) {
                article.put("seriesId", seriesAndBookshelfService.getSeriesIdByArticleId(articleId));
            }

            LOGGER.log(Level.DEBUG, "Got an article [id={0}]", articleId);

            return ret;
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Gets an article failed", e);
            throw new ServiceException(e);
        }
    }

    /**
     * Gets articles(by crate date descending) by the specified request json object.
     * <p>
     * Specified the "excludes" for results properties exclusion.
     * </p>
     *
     * @param requestJSONObject the specified request json object
     * @return articles result JSON
     * @see Pagination
     */
    public JSONObject getArticles(final JSONObject requestJSONObject) {
        final JSONObject ret = new JSONObject();

        try {
            final int currentPageNum = requestJSONObject.getInt(Pagination.PAGINATION_CURRENT_PAGE_NUM);
            final int pageSize = requestJSONObject.getInt(Pagination.PAGINATION_PAGE_SIZE);
            final int windowSize = requestJSONObject.getInt(Pagination.PAGINATION_WINDOW_SIZE);
            final int articleStatus = requestJSONObject.optInt(Article.ARTICLE_STATUS,
                    Article.ARTICLE_STATUS_C_PUBLISHED);

            final Query query = new Query().setPage(currentPageNum, pageSize).addSort(Article.ARTICLE_PUT_TOP,
                    SortDirection.DESCENDING);
            if (requestJSONObject.optBoolean(Option.ID_C_ENABLE_ARTICLE_UPDATE_HINT)) {
                query.addSort(Article.ARTICLE_UPDATED, SortDirection.DESCENDING);
            } else {
                query.addSort(Article.ARTICLE_CREATED, SortDirection.DESCENDING);
            }
            final boolean skipViewOverlay = requestJSONObject.optBoolean(SKIP_VIEW_OVERLAY);
            final String keyword = AdminListSearch.normalizeKeyword(requestJSONObject.optString(Common.KEYWORD));
            final String filterAuthorId = requestJSONObject.optString(Article.ARTICLE_AUTHOR_ID);
            Filter filter = new PropertyFilter(Article.ARTICLE_STATUS, FilterOperator.EQUAL, articleStatus);
            if (StringUtils.isNotBlank(filterAuthorId)) {
                filter = CompositeFilterOperator.and(filter,
                        new PropertyFilter(Article.ARTICLE_AUTHOR_ID, FilterOperator.EQUAL, filterAuthorId));
            }
            if (!keyword.isEmpty()) {
                filter = CompositeFilterOperator.and(filter,
                        AdminListSearch.likeAny(keyword,
                                Article.ARTICLE_TITLE, Article.ARTICLE_TAGS_REF, Article.ARTICLE_CONTENT));
            }
            query.setFilter(filter);

            JSONArray excludes = requestJSONObject.optJSONArray(Keys.EXCLUDES);
            excludes = null == excludes ? new JSONArray() : excludes;
            final Set<String> excludedSet = new HashSet<>();
            if (excludes.length() > 0) {
                for (int j = 0; j < excludes.length(); j++) {
                    final String ex = excludes.optString(j);
                    if (StringUtils.isNotBlank(ex)) {
                        excludedSet.add(ex);
                    }
                }
                final List<String> selectedCols = new ArrayList<>(ALL_ARTICLE_DB_COLUMNS.size());
                for (final String col : ALL_ARTICLE_DB_COLUMNS) {
                    if (!excludedSet.contains(col)
                            || Keys.OBJECT_ID.equals(col)
                            || Article.ARTICLE_AUTHOR_ID.equals(col)
                            || Article.ARTICLE_CREATED.equals(col)
                            || Article.ARTICLE_UPDATED.equals(col)) {
                        selectedCols.add(col);
                    }
                }
                if (!selectedCols.isEmpty() && selectedCols.size() < ALL_ARTICLE_DB_COLUMNS.size()) {
                    query.select(selectedCols.get(0), selectedCols.subList(1, selectedCols.size()).toArray(new String[0]));
                }
            }

            final JSONObject result = articleRepository.get(query);
            final int pageCount = result.optJSONObject(Pagination.PAGINATION).optInt(Pagination.PAGINATION_PAGE_COUNT);
            final JSONObject pagination = new JSONObject();
            ret.put(Pagination.PAGINATION, pagination);
            final List<Integer> pageNums = Paginator.paginate(currentPageNum, pageSize, pageCount, windowSize);
            pagination.put(Pagination.PAGINATION_PAGE_COUNT, pageCount);
            pagination.put(Pagination.PAGINATION_PAGE_NUMS, pageNums);

            final JSONArray articles = org.b3log.solo.util.QueryResults.array(result);
            final boolean needCategory = Article.ARTICLE_STATUS_C_PUBLISHED == articleStatus
                    && !excludedSet.contains("articleCategory");
            final Map<String, Object> batchCategoryTitles = needCategory
                    ? preloadDirectCategoryTitles(articles)
                    : Collections.emptyMap();
            final Map<String, JSONObject> batchAuthors = preloadAuthorsFromArray(articles);
            JSONObject fallbackAdmin = null;
            boolean adminResolved = false;

            for (int i = 0; i < articles.length(); i++) {
                final JSONObject article = articles.getJSONObject(i);
                final String articleId = article.optString(Keys.OBJECT_ID);
                if (!skipViewOverlay && article.has(Article.ARTICLE_VIEW_COUNT)) {
                    final long pendingDelta = ArticleMgmtService.peekPendingViewDelta(articleId);
                    if (pendingDelta > 0L) {
                        article.put(Article.ARTICLE_VIEW_COUNT,
                                (int) Math.min(Integer.MAX_VALUE, Math.max(0L, article.optLong(Article.ARTICLE_VIEW_COUNT) + pendingDelta)));
                    }
                }
                final String authorId = article.optString(Article.ARTICLE_AUTHOR_ID);
                JSONObject author = batchAuthors.get(authorId);
                if (null == author) {
                    if (!adminResolved) {
                        fallbackAdmin = null == userRepository ? null : userRepository.getAdmin();
                        adminResolved = true;
                    }
                    author = fallbackAdmin;
                }
                if (null == author) {
                    author = getAuthor(article);
                }
                if (null != author) {
                    article.put(Common.AUTHOR_NAME, author.optString(User.USER_NAME));
                }
                article.put(Article.ARTICLE_CREATE_TIME, article.getLong(Article.ARTICLE_CREATED));
                article.put(Article.ARTICLE_UPDATE_TIME, article.getLong(Article.ARTICLE_UPDATED));
                if (needCategory) {
                    if (null != batchCategoryTitles) {
                        final Object catTitle = batchCategoryTitles.get(articleId);
                        if (null != catTitle) {
                            article.put("articleCategory", catTitle);
                        }
                    } else {
                        try {
                            JSONObject cate = categoryTagRepository.getByTagId(articleId, 1, Integer.MAX_VALUE);
                            int size = org.b3log.solo.util.QueryResults.array(cate).length();
                            JSONObject cateS = (JSONObject) org.b3log.solo.util.QueryResults.array(cate).get(size - 1);
                            String categoryOId = cateS.optString("category_oId");
                            cateS = categoryQueryService.getCategory(categoryOId);
                            article.put("articleCategory", cateS.opt("categoryTitle"));
                        } catch (JSONException | NullPointerException | IndexOutOfBoundsException e) {
                        }
                    }
                }
                // Remove unused properties
                for (int j = 0; j < excludes.length(); j++) {
                    article.remove(excludes.optString(j));
                }
            }

            ret.put(Article.ARTICLES, articles);

            return ret;
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Gets articles failed", e);

            return null;
        }
    }

    private Map<String, Object> preloadDirectCategoryTitles(final JSONArray articles) {
        if (null == articles || 0 == articles.length()) {
            return Collections.emptyMap();
        }
        if (null == categoryTagRepository || null == categoryRepository) {
            return null;
        }
        try {
            final Set<String> articleIds = new LinkedHashSet<>(articles.length());
            for (int i = 0; i < articles.length(); i++) {
                final JSONObject article = articles.optJSONObject(i);
                if (null != article) {
                    final String id = article.optString(Keys.OBJECT_ID);
                    if (!id.isEmpty()) {
                        articleIds.add(id);
                    }
                }
            }
            if (articleIds.isEmpty()) {
                return Collections.emptyMap();
            }
            final String idPlaceholders = String.join(",", Collections.nCopies(articleIds.size(), "?"));
            final List<JSONObject> relRows = categoryTagRepository.select(
                    "SELECT category_oId, tag_oId FROM " + categoryTagRepository.getName()
                            + " WHERE tag_oId IN (" + idPlaceholders + ")",
                    articleIds.toArray());
            if (null == relRows || relRows.isEmpty()) {
                return Collections.emptyMap();
            }
            final Map<String, String> lastCatIdByArticleId = new HashMap<>(relRows.size());
            final Set<String> categoryIds = new LinkedHashSet<>();
            for (final JSONObject row : relRows) {
                final String refId = row.optString(Tag.TAG + "_" + Keys.OBJECT_ID);
                final String catId = row.optString(Category.CATEGORY + "_" + Keys.OBJECT_ID);
                if (!refId.isEmpty() && !catId.isEmpty()) {
                    lastCatIdByArticleId.put(refId, catId);
                    categoryIds.add(catId);
                }
            }
            if (categoryIds.isEmpty()) {
                return Collections.emptyMap();
            }
            final String catPlaceholders = String.join(",", Collections.nCopies(categoryIds.size(), "?"));
            final List<JSONObject> catRows = categoryRepository.select(
                    "SELECT oId, categoryTitle FROM " + categoryRepository.getName()
                            + " WHERE oId IN (" + catPlaceholders + ")",
                    categoryIds.toArray());
            final Map<String, Object> titleByCatId = new HashMap<>();
            if (null != catRows) {
                for (final JSONObject cat : catRows) {
                    final String id = cat.optString(Keys.OBJECT_ID);
                    final Object title = cat.opt(Category.CATEGORY_TITLE);
                    if (!id.isEmpty() && null != title) {
                        titleByCatId.put(id, title);
                    }
                }
            }
            final Map<String, Object> ret = new HashMap<>(lastCatIdByArticleId.size());
            for (final Map.Entry<String, String> entry : lastCatIdByArticleId.entrySet()) {
                final Object title = titleByCatId.get(entry.getValue());
                if (null != title) {
                    ret.put(entry.getKey(), title);
                }
            }
            return ret;
        } catch (final Exception e) {
            LOGGER.log(Level.DEBUG, "Batch direct category preload fell back to per-article lookup", e);
            return null;
        }
    }

    /**
     * Gets a list of published articles with the specified tag id, current page
     * number and page size.
     *
     * @param tagId          the specified tag id
     * @param currentPageNum the specified current page number
     * @param pageSize       the specified page size
     * @return result, returns {@code null} if not found
     * @throws ServiceException service exception
     */
    public JSONObject getArticlesByTag(final String tagId, final int currentPageNum, final int pageSize)
            throws ServiceException {
        return getArticlesByTag(tagId, -1, currentPageNum, pageSize);
    }

    /**
     * Gets published articles for a resolved {@code tag} JSON, reusing {@code tagPublishedRefCount}
     * when already populated to avoid a duplicate {@code COUNT(*)} query.
     */
    public JSONObject getArticlesByTag(final JSONObject tag, final int currentPageNum, final int pageSize)
            throws ServiceException {
        if (null == tag) {
            throw new ServiceException("Tag must not be null");
        }
        return getArticlesByTag(
                tag.optString(Keys.OBJECT_ID),
                tag.optInt(Tag.TAG_T_PUBLISHED_REFERENCE_COUNT, -1),
                currentPageNum,
                pageSize);
    }

    public JSONObject getArticlesByTag(final String tagId,
                                       final int knownPublishedCount,
                                       final int currentPageNum,
                                       final int pageSize) throws ServiceException {
        try {
            if (currentPageNum < 1 || pageSize < 1) throw new IllegalArgumentException("Invalid pagination");
            final int count = knownPublishedCount >= 0
                    ? knownPublishedCount
                    : tagArticleRepository.getPublishedArticleCount(tagId);
            if (count < 0) throw new RepositoryException("Could not count published tag articles");
            final JSONObject preference = null != optionQueryService ? optionQueryService.getPreference() : null;
            final boolean includeContent = null == preference || isFullContentListStyle(preference);
            final List<JSONObject> articles = tagArticleRepository.getPublishedArticles(
                    tagId, currentPageNum, pageSize, includeContent);
            for (final JSONObject article : articles) {
                article.put(Article.ARTICLE_CREATE_TIME, article.getLong(Article.ARTICLE_CREATED));
                article.put(Article.ARTICLE_T_CREATE_DATE, new Date(article.getLong(Article.ARTICLE_CREATED)));
                article.put(Article.ARTICLE_T_UPDATE_DATE, new Date(article.optLong(Article.ARTICLE_UPDATED)));
            }
            final JSONObject pagination = new JSONObject();
            pagination.put(Pagination.PAGINATION_PAGE_COUNT, (int) ((count + (long) pageSize - 1) / pageSize));
            final JSONObject ret = new JSONObject();
            ret.put(Pagination.PAGINATION, pagination);
            ret.put(Keys.RESULTS, (Object) articles);
            return ret;
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Gets articles by tag [id=" + tagId + "] failed", e);
            throw new ServiceException(e);
        }
    }

    /**
     * Gets a list of published articles with the specified archive date id, current
     * page number and page size.
     *
     * @param archiveDateId  the specified archive date id
     * @param currentPageNum the specified current page number
     * @param pageSize       the specified page size
     * @return a list of articles, returns an empty list if not found
     * @throws ServiceException service exception
     */
    public List<JSONObject> getArticlesByArchiveDate(final String archiveDateId, final int currentPageNum,
            final int pageSize) throws ServiceException {
        try {
            if (currentPageNum < 1 || pageSize < 1) throw new IllegalArgumentException("Invalid pagination");
            final List<String> archiveIds = archiveDateRepository.getMonthArchiveIds(archiveDateId);
            return getArticlesByArchiveIds(archiveDateId, archiveIds, currentPageNum, pageSize);
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Gets articles by archive date [id=" + archiveDateId + "] failed", e);
            throw new ServiceException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public List<JSONObject> getArticlesByArchiveDate(final JSONObject archiveQueryResult,
                                                     final int currentPageNum,
                                                     final int pageSize) throws ServiceException {
        if (null == archiveQueryResult) {
            return Collections.emptyList();
        }
        final Object rawIds = archiveQueryResult.opt(ArchiveDateQueryService.MONTH_ARCHIVE_IDS);
        final JSONObject archiveDate = archiveQueryResult.optJSONObject( org.b3log.solo.model.ArchiveDate.ARCHIVE_DATE );
        final String archiveDateId = null == archiveDate ? "" : archiveDate.optString(Keys.OBJECT_ID);
        if (rawIds instanceof List<?> list) {
            try {
                if (currentPageNum < 1 || pageSize < 1) throw new IllegalArgumentException("Invalid pagination");
                return getArticlesByArchiveIds(archiveDateId, (List<String>) list, currentPageNum, pageSize);
            } catch (final Exception e) {
                LOGGER.log(Level.ERROR, "Gets articles by archive date [id=" + archiveDateId + "] failed", e);
                throw new ServiceException(e);
            }
        }
        return getArticlesByArchiveDate(archiveDateId, currentPageNum, pageSize);
    }

    private List<JSONObject> getArticlesByArchiveIds(final String archiveDateId,
                                                     final List<String> archiveIds,
                                                     final int currentPageNum,
                                                     final int pageSize) throws RepositoryException {
        if (null == archiveIds || archiveIds.isEmpty()) return Collections.emptyList();
        final List<Object> params = new ArrayList<>();
        params.add(Article.ARTICLE_STATUS_C_PUBLISHED);
        params.addAll(archiveIds);
        params.add(pageSize);
        params.add((long) (currentPageNum - 1) * pageSize);
        final JSONObject preference = null != optionQueryService ? optionQueryService.getPreference() : null;
        final String selectClause;
        if (null == preference || isFullContentListStyle(preference)) {
            selectClause = "a.*";
        } else {
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ARTICLE_COLUMNS_WITHOUT_CONTENT.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append("a.").append(ARTICLE_COLUMNS_WITHOUT_CONTENT[i]);
            }
            selectClause = sb.toString();
        }
        final String query = "SELECT " + selectClause + " FROM " + articleRepository.getName()
                + " AS a WHERE a.articleStatus = ? AND EXISTS (SELECT 1 FROM " + archiveDateArticleRepository.getName()
                + " AS r WHERE r.article_oId = a.oId AND r.archiveDate_oId IN ("
                + String.join(",", Collections.nCopies(archiveIds.size(), "?")) + "))"
                + " ORDER BY a.oId DESC LIMIT ? OFFSET ?";
        final List<JSONObject> articles = articleRepository.select(query, params.toArray());
        for (final JSONObject article : articles) {
            article.put(Article.ARTICLE_CREATE_TIME, article.getLong(Article.ARTICLE_CREATED));
            article.put(Article.ARTICLE_T_CREATE_DATE, new Date(article.getLong(Article.ARTICLE_CREATED)));
            article.put(Article.ARTICLE_T_UPDATE_DATE, new Date(article.optLong(Article.ARTICLE_UPDATED)));
        }
        return articles;
    }

    /**
     * Gets a list of articles randomly with the specified fetch size.
     * <p>
     * <b>Note</b>: The article content and abstract is raw (no editor type
     * processing).
     * </p>
     *
     * @param fetchSize the specified fetch size
     * @return a list of json objects, its size less or equal to the specified fetch
     *         size
     * @throws ServiceException service exception
     */
    public List<JSONObject> getArticlesRandomly(final int fetchSize) throws ServiceException {
        try {
            final List<JSONObject> ret = articleRepository.getRandomly(fetchSize);
            removeUnusedProperties(ret);

            return ret;
        } catch (final RepositoryException e) {
            LOGGER.log(Level.ERROR, "Gets articles randomly failed[fetchSize=" + fetchSize + "]", e);
            throw new ServiceException(e);
        }
    }

    /**
     * Gets the relevant published articles of the specified article.
     * <p>
     * <b>Note</b>: The article content and abstract is raw (no editor type
     * processing).
     * </p>
     *
     * @param article    the specified article
     * @param preference the specified preference
     * @return a list of articles, returns an empty list if not found
     */
    public List<JSONObject> getRelevantArticles(final JSONObject article, final JSONObject preference) {
        try {
            final int displayCnt = preference.getInt(Option.ID_C_RELEVANT_ARTICLES_DISPLAY_CNT);
            if (displayCnt <= 0) return Collections.emptyList();
            final List<String> tagTitles = new ArrayList<>();
            for (final String value : article.optString(Article.ARTICLE_TAGS_REF).split(",")) {
                final String title = value.trim();
                if (!title.isEmpty() && !tagTitles.contains(title)) tagTitles.add(title);
                if (tagTitles.size() >= displayCnt) break;
            }
            if (tagTitles.isEmpty()) return Collections.emptyList();
            final List<Object> params = new ArrayList<>();
            params.add(Article.ARTICLE_STATUS_C_PUBLISHED);
            params.add(article.getString(Keys.OBJECT_ID));
            params.addAll(tagTitles);
            params.add((int) Math.min(Integer.MAX_VALUE, (long) displayCnt * tagTitles.size()));
            final String query = "SELECT a.articleTitle, a.articlePermalink FROM " + articleRepository.getName()
                    + " AS a WHERE a.articleStatus = ? AND a.oId <> ? AND EXISTS (SELECT 1 FROM "
                    + tagArticleRepository.getName() + " AS r JOIN " + tagRepository.getName()
                    + " AS t ON t.oId = r.tag_oId WHERE r.article_oId = a.oId AND t.tagTitle IN ("
                    + String.join(",", Collections.nCopies(tagTitles.size(), "?")) + "))"
                    + " ORDER BY a.oId DESC LIMIT ?";
            final List<JSONObject> articles = articleRepository.select(query, params.toArray());
            Collections.shuffle(articles);
            return new ArrayList<>(articles.subList(0, Math.min(displayCnt, articles.size())));
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Gets relevant articles failed", e);

            return Collections.emptyList();
        }
    }

    /**
     * Gets the next article(by create date) by the specified article id.
     *
     * @param articleId the specified article id
     * @return the next article, returns {@code null} if not found
     * @throws ServiceException service exception
     */
    public JSONObject getNextArticle(final String articleId) throws ServiceException {
        try {
            return articleRepository.getNextArticle(articleId);
        } catch (final RepositoryException e) {
            LOGGER.log(Level.ERROR, "Gets the next article failed[articleId=" + articleId + "]", e);
            throw new ServiceException(e);
        }
    }

    public JSONObject getNextArticle(final String articleId, final long created) throws ServiceException {
        try {
            return articleRepository.getNextArticle(articleId, created);
        } catch (final RepositoryException e) {
            LOGGER.log(Level.ERROR, "Gets the next article failed[articleId=" + articleId + "]", e);
            throw new ServiceException(e);
        }
    }

    /**
     * Gets the previous article(by create date) by the specified article id.
     *
     * @param articleId the specified article id
     * @return the previous article, returns {@code null} if not found
     * @throws ServiceException service exception
     */
    public JSONObject getPreviousArticle(final String articleId) throws ServiceException {
        try {
            return articleRepository.getPreviousArticle(articleId);
        } catch (final RepositoryException e) {
            LOGGER.log(Level.ERROR, "Gets the previous article failed[articleId=" + articleId + "]", e);
            throw new ServiceException(e);
        }
    }

    public JSONObject getPreviousArticle(final String articleId, final long created) throws ServiceException {
        try {
            return articleRepository.getPreviousArticle(articleId, created);
        } catch (final RepositoryException e) {
            LOGGER.log(Level.ERROR, "Gets the previous article failed[articleId=" + articleId + "]", e);
            throw new ServiceException(e);
        }
    }

    /**
     * Gets an article by the specified article id.
     *
     * @param articleId the specified article id
     * @return an article, returns {@code null} if not found
     */
    public JSONObject getArticleById(final String articleId) {
        try {
            return articleRepository.get(articleId);
        } catch (final RepositoryException e) {
            LOGGER.log(Level.ERROR, "Gets an article [id=" + articleId + "] failed", e);

            return null;
        }
    }

    /**
     * Gets <em>published</em> articles by the specified author id, current page
     * number and page size.
     *
     * @param authorId       the specified author id
     * @param currentPageNum the specified current page number
     * @param pageSize       the specified page size
     * @return result
     * @throws ServiceException service exception
     */
    public JSONObject getArticlesByAuthorId(final String authorId, final int currentPageNum, final int pageSize)
            throws ServiceException {
        try {
            final JSONObject ret = articleRepository.getByAuthorId(authorId, currentPageNum, pageSize);
            final JSONArray articles = org.b3log.solo.util.QueryResults.array(ret);
            for (int i = 0; i < articles.length(); i++) {
                final JSONObject article = articles.getJSONObject(i);
                article.put(Article.ARTICLE_CREATE_TIME, article.getLong(Article.ARTICLE_CREATED));
                article.put(Article.ARTICLE_T_CREATE_DATE, new Date(article.optLong(Article.ARTICLE_CREATED)));
                article.put(Article.ARTICLE_T_UPDATE_DATE, new Date(article.optLong(Article.ARTICLE_UPDATED)));
            }

            return ret;
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Gets articles by author id failed [authorId=" + authorId + ", currentPageNum="
                    + currentPageNum + ", pageSize=" + pageSize + "]", e);

            throw new ServiceException(e);
        }
    }

    /**
     * Gets article contents with the specified article id.
     * <p>
     * Invoking this method dose not effect on article view count.
     * </p>
     *
     * @param context   the specified HTTP servlet request context
     * @param articleId the specified article id
     * @return article contents, returns {@code null} if not found
     * @throws ServiceException service exception
     */
    public String getArticleContent(final RequestContext context, final String articleId) throws ServiceException {
        if (StringUtils.isBlank(articleId)) {
            return null;
        }

        try {
            final JSONObject article = articleRepository.get(articleId);
            if (null == article) {
                return null;
            }

            final boolean validPreview = null != context && null != shareTokenService && shareTokenService.hasValidPreviewToken(context, article);
            if (Article.ARTICLE_STATUS_C_PUBLISHED != article.optInt(Article.ARTICLE_STATUS)) {
                final JSONObject preference = optionQueryService.getPreference();
                final boolean allowDraft = null != preference && preference.optBoolean(Option.ID_C_ALLOW_VISIT_DRAFT_VIA_PERMALINK);
                final boolean authorized = null != context && canAccessArticle(articleId, Solos.getCurrentUser(context.getRequest(), context.getResponse()));
                if (!allowDraft && !authorized && !validPreview) {
                    return null;
                }
            }

            if (null != context && !validPreview && Solos.needViewPwd(context, article)) {
                final String content = langPropsService.get("articleContentPwd");
                article.put(Article.ARTICLE_CONTENT, content);
            } else {
                Stopwatchs.start("Get Article Content [Markdown]");
                final String content = renderRichMarkdown(articleId, article.optString(Article.ARTICLE_CONTENT), isAdminAuthor(article), context, false);
                article.put(Article.ARTICLE_CONTENT, content);
                Stopwatchs.end();
            }

            return article.getString(Article.ARTICLE_CONTENT);
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Gets article content failed[articleId=" + articleId + "]", e);

            throw new ServiceException(e);
        }
    }

    /**
     * Converts the content and abstract for each of the specified articles to HTML
     * if that is saved by Markdown editor.
     *
     * @param articles the specified articles
     */
    public void markdowns(final List<JSONObject> articles) {
        for (final JSONObject article : articles) {
            markdown(article);
        }
    }

    private boolean isAdminAuthor(final JSONObject article) {
        if (null == article) {
            return false;
        }
        final String resolvedRole = article.optString(Common.AUTHOR_ROLE);
        if (!resolvedRole.isEmpty()) {
            return Role.ADMIN_ROLE.equals(resolvedRole);
        }
        final String authorId = article.optString(Article.ARTICLE_AUTHOR_ID);
        if (StringUtils.isBlank(authorId) || null == userRepository) {
            return true;
        }
        try {
            final JSONObject author = userRepository.get(authorId);
            return null != author && Role.ADMIN_ROLE.equals(author.optString(User.USER_ROLE));
        } catch (final RepositoryException e) {
            LOGGER.log(Level.WARN, "Checks article author role failed [authorId=" + authorId + "]", e);
            return false;
        }
    }

    public String renderRichMarkdown(final String articleId, final String rawMarkdown, final boolean adminAuthor,
                                     final RequestContext context, final boolean editorPreview) {
        if (StringUtils.isEmpty(rawMarkdown)) {
            return "";
        }
        SeriesAndBookshelfService.ShortcodePreprocess prep = null;
        String working = rawMarkdown;
        if (null != seriesAndBookshelfService && SeriesAndBookshelfService.containsShortcode(working)) {
            prep = seriesAndBookshelfService.preprocessShortcodes(working);
            working = prep.markdownWithTokens();
        }
        String html;
        if (null != hiddenContentService) {
            html = hiddenContentService.renderMarkdown(articleId, working, adminAuthor, context, editorPreview);
        } else {
            html = Markdowns.toHTML(working);
            if (!adminAuthor) {
                html = Markdowns.clean(html);
            }
        }
        if (null != prep && null != seriesAndBookshelfService) {
            html = seriesAndBookshelfService.applyShortcodeSlots(html, prep);
        }
        return html;
    }

    public void markdown(final JSONObject article) {
        markdown(article, null);
    }

    public void markdown(final JSONObject article, final RequestContext context) {
        final String articleId = article.optString(Keys.OBJECT_ID);
        Stopwatchs.start("Markdown Article [id=" + articleId + "]");
        final boolean adminAuthor = isAdminAuthor(article);

        final String rawContent = article.optString(Article.ARTICLE_CONTENT);
        final String content = renderRichMarkdown(articleId, rawContent, adminAuthor, context, false);
        article.put(Article.ARTICLE_CONTENT, content);

        String abstractContent = article.optString(Article.ARTICLE_ABSTRACT);
        if (StringUtils.isNotBlank(abstractContent)) {
            Stopwatchs.start("Abstract");
            abstractContent = renderRichMarkdown(articleId, abstractContent, adminAuthor, context, false);
            article.put(Article.ARTICLE_ABSTRACT, abstractContent);
            Stopwatchs.end();
        }

        // Compute cover strictly from public unlocked HTML so locked images never leak on list cards
        final String publicHtmlForCover = HiddenContentService.containsLockSyntax(rawContent)
                ? Markdowns.toHTML(HiddenContentService.stripForExcerptOrImage(article.optString(Article.ARTICLE_ABSTRACT) + "\n" + rawContent))
                : (abstractContent + "\n" + content);
        article.put(Article.ARTICLE_COVER_URL, StringUtils.isBlank(article.optString(Article.ARTICLE_VIEW_PWD))
                ? Article.getArticleCoverURL(publicHtmlForCover) : "");
        Stopwatchs.end();
    }

    /**
     * Lightweight list-mode Markdown renderer for {@code titleOnly} / {@code titleAndAbstract} styles:
     * renders only {@code articleAbstract}, reuses {@code articleImg1URL} (falling back to unlocked abstract HTML
     * only when blank), and clears {@code articleContent} without parsing full article bodies.
     */
    public void markdownAbstractOnly(final JSONObject article, final RequestContext context) {
        final String articleId = article.optString(Keys.OBJECT_ID);
        Stopwatchs.start("Markdown Abstract [id=" + articleId + "]");
        final boolean adminAuthor = isAdminAuthor(article);

        final String rawAbstract = article.optString(Article.ARTICLE_ABSTRACT);
        String abstractHtml = "";
        if (StringUtils.isNotBlank(rawAbstract)) {
            abstractHtml = renderRichMarkdown(articleId, rawAbstract, adminAuthor, context, false);
            article.put(Article.ARTICLE_ABSTRACT, abstractHtml);
        } else {
            article.put(Article.ARTICLE_ABSTRACT, "");
        }
        article.put(Article.ARTICLE_CONTENT, "");

        if (StringUtils.isNotBlank(article.optString(Article.ARTICLE_VIEW_PWD))) {
            article.put(Article.ARTICLE_COVER_URL, "");
        } else {
            final String img1Url = StringUtils.trimToEmpty(article.optString(Article.ARTICLE_IMG1_URL));
            if (!img1Url.isEmpty()) {
                article.put(Article.ARTICLE_COVER_URL, img1Url);
            } else if (StringUtils.isNotBlank(rawAbstract)) {
                final String publicAbstractHtml = HiddenContentService.containsLockSyntax(rawAbstract)
                        ? Markdowns.toHTML(HiddenContentService.stripForExcerptOrImage(rawAbstract))
                        : abstractHtml;
                article.put(Article.ARTICLE_COVER_URL, Article.getArticleCoverURL(publicAbstractHtml));
            } else {
                article.put(Article.ARTICLE_COVER_URL, "");
            }
        }
        Stopwatchs.end();
    }

    /**
     * Removes unused properties of each article in the specified articles.
     *
     * @param articles the specified articles
     */
    private void removeUnusedProperties(final List<JSONObject> articles) {
        for (final JSONObject article : articles) {
            removeUnusedProperties(article);
        }
    }

    /**
     * Removes unused properties of the specified article.
     *
     * @param article the specified article
     */
    private void removeUnusedProperties(final JSONObject article) {
        article.remove(Keys.OBJECT_ID);
        article.remove(Article.ARTICLE_AUTHOR_ID);
        article.remove(Article.ARTICLE_ABSTRACT);
        article.remove(Article.ARTICLE_COMMENT_COUNT);
        article.remove(Article.ARTICLE_CONTENT);
        article.remove(Article.ARTICLE_CREATED);
        article.remove(Article.ARTICLE_TAGS_REF);
        article.remove(Article.ARTICLE_UPDATED);
        article.remove(Article.ARTICLE_VIEW_COUNT);
        article.remove(Article.ARTICLE_RANDOM_DOUBLE);
        article.remove(Article.ARTICLE_PUT_TOP);
        article.remove(Article.ARTICLE_VIEW_PWD);
        article.remove(Article.ARTICLE_SIGN_ID);
        article.remove(Article.ARTICLE_COMMENTABLE);
    }
}
