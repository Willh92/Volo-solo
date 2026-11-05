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

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.event.Event;
import org.b3log.latke.event.EventManager;
import org.b3log.latke.ioc.Inject;
import org.apache.logging.log4j.Level;
import org.b3log.solo.logging.Logger;
import org.b3log.latke.repository.*;
import org.b3log.latke.service.LangPropsService;
import org.b3log.latke.service.ServiceException;
import org.b3log.latke.service.annotation.Service;
import org.b3log.latke.util.Ids;
import org.b3log.solo.cache.ArticleCache;
import org.b3log.solo.cache.PublicViewCache;
import org.b3log.solo.event.B3ArticleSender;
import org.b3log.solo.event.EventTypes;
import org.b3log.solo.event.FishPiArticleSender;
import org.b3log.solo.model.*;
import org.b3log.solo.repository.*;
import org.b3log.solo.util.GitHubs;
import org.b3log.solo.util.GitHubStats;
import org.b3log.solo.util.PluginUtil;
import org.b3log.solo.util.QueryResults;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import static org.b3log.solo.model.Article.*;

/**
 * Article management service.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 0.3.5
 */

@Service
public class ArticleMgmtService {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(ArticleMgmtService.class);

    public static final int MAX_PENDING_VIEW_ARTICLES = 4096;
    private static final int VIEW_FLUSH_BATCH_SIZE = 500;
    private static volatile ArticleMgmtService activeInstance;

    /**
     * Category service.
     */
    @Inject
    private CategoryRepository categoryRepository;

    /**
     * Article query service.
     */
    @Inject
    private ArticleQueryService articleQueryService;

    /**
     * Article repository.
     */
    @Inject
    private ArticleRepository articleRepository;

    /**
     * Page repository.
     */
    @Inject
    private PageRepository pageRepository;

    /**
     * User repository.
     */
    @Inject
    private UserRepository userRepository;

    /**
     * Tag repository.
     */
    @Inject
    private TagRepository tagRepository;

    /**
     * Archive date repository.
     */
    @Inject
    private ArchiveDateRepository archiveDateRepository;

    /**
     * Archive date-Article repository.
     */
    @Inject
    private ArchiveDateArticleRepository archiveDateArticleRepository;

    /**
     * Tag-Article repository.
     */
    @Inject
    private TagArticleRepository tagArticleRepository;

    /**
     * Comment repository.
     */
    @Inject
    private CommentRepository commentRepository;

    /**
     * Category-tag repository.
     */
    @Inject
    private CategoryTagRepository categoryTagRepository;

    /**
     * Permalink query service.
     */
    @Inject
    private PermalinkQueryService permalinkQueryService;

    /**
     * Event manager.
     */
    @Inject
    private EventManager eventManager;

    /**
     * Language service.
     */
    @Inject
    private LangPropsService langPropsService;

    /**
     * Statistic management service.
     */
    @Inject
    private StatisticMgmtService statisticMgmtService;

    /**
     * Statistic query service.
     */
    @Inject
    private StatisticQueryService statisticQueryService;

    /**
     * Init service.
     */
    @Inject
    private InitService initService;

    /**
     * Tag management service.
     */
    @Inject
    private TagMgmtService tagMgmtService;

    /**
     * Option query service.
     */
    @Inject
    private OptionQueryService optionQueryService;

    /**
     * Option management service.
     */
    @Inject
    private OptionMgmtService optionMgmtService;

    @Inject
    private GitHubRepositoryService gitHubRepositoryService;

    /**
     * Category management service.
     */
    @Inject
    private CategoryMgmtService categoryMgmtService;

    /**
     * Category query service.
     */
    @Inject
    private CategoryQueryService categoryQueryService;

    @Inject
    private ArticleRevisionService articleRevisionService;

    @Inject
    private SeriesAndBookshelfService seriesAndBookshelfService;

    @Inject
    private InstantSearchService instantSearchService;

    @Inject
    private SyndicationCacheService syndicationCacheService;

    @Inject
    private BacklinkService backlinkService;

    @Inject
    private IndexNowService indexNowService;

    @Inject
    private ArticleCache articleCache;

    @Inject
    private PublicViewCache publicViewCache;

    private final ConcurrentHashMap<String, LongAdder> pendingArticleViewDeltas = new ConcurrentHashMap<>();
    private final Object viewFlushLock = new Object();

    public ArticleMgmtService() {
        activeInstance = this;
    }

    /**
     * Returns any un-flushed write-behind view delta from the active service instance (0 if none).
     */
    public static long peekPendingViewDelta(final String articleId) {
        final ArticleMgmtService instance = activeInstance;
        return null == instance ? 0L : instance.getPendingViewDelta(articleId);
    }

    private void invalidateContentCaches() {
        if (null != instantSearchService) {
            instantSearchService.invalidate();
        }
        if (null != syndicationCacheService) {
            syndicationCacheService.invalidate();
        }
        if (null != backlinkService) {
            backlinkService.invalidate();
        }
        if (null != publicViewCache) {
            publicViewCache.invalidateAll();
        }
    }

    private void notifyIndexNowIfPublic(final JSONObject article) {
        if (null == indexNowService || null == article) {
            return;
        }
        if (Article.ARTICLE_STATUS_C_PUBLISHED == article.optInt(ARTICLE_STATUS)
                && StringUtils.isBlank(article.optString(ARTICLE_VIEW_PWD))) {
            final String permalink = StringUtils.trimToEmpty(article.optString(ARTICLE_PERMALINK));
            if (!permalink.isEmpty()) {
                indexNowService.notifyUrlsAsync(List.of(permalink, "/"));
            }
        }
    }

    /**
     * Refreshes GitHub repos. 同步拉取 GitHub 仓库 https://github.com/b3log/solo/issues/12514
     */
    public synchronized boolean refreshGitHub(String githubId) {
        if (!initService.isInited()) {
            return false;
        }

        final JSONObject preference = optionQueryService.getPreference();
        if (null == preference) {
            return false;
        }

        JSONObject admin;
        try {
            admin = userRepository.getAdmin();
        } catch (final Exception e) {
            return false;
        }

        if (null == admin) {
            return false;
        }

        final JSONArray gitHubRepos = fetchGitHubRepositories(githubId);
        if (null == gitHubRepos) {
            return false;
        }
        try {
            gitHubRepositoryService.storeSnapshot(githubId, gitHubRepos);
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Updates github repos option failed", e);
            return false;
        }
        return publishGitHub(githubId, admin, gitHubRepositoryService.visibleFor(githubId));
    }

    protected JSONArray fetchGitHubRepositories(final String username) {
        return GitHubs.getGitHubRepos(username);
    }

    /** Rebuild from the complete saved snapshot without contacting GitHub. */
    public synchronized boolean rebuildGitHub(final String username) {
        if (!GitHubStats.validUser(username) || !gitHubRepositoryService.hasSnapshot(username)) return true;
        try {
            final JSONObject admin = userRepository.getAdmin();
            return admin != null && publishGitHub(username, admin, gitHubRepositoryService.visibleFor(username));
        } catch (Exception e) {
            LOGGER.log(Level.ERROR, "Rebuilds github repos page failed", e);
            return false;
        }
    }

    private boolean publishGitHub(final String githubId, final JSONObject admin, final JSONArray gitHubRepos) {
        final StringBuilder contentBuilder = new StringBuilder();
        final String profile = "https://github.com/" + githubId;
        String stats = "\n[![GitHub Stats](" + Latkes.getContextPath() + "/github-stats/"
                + java.net.URLEncoder.encode(githubId, java.nio.charset.StandardCharsets.UTF_8) + ".svg?v=" + System.currentTimeMillis() + ")](" + profile + ")\n\n";
        contentBuilder.append("[![GitHub Repo](").append(Latkes.getContextPath()).append("/images/github_repo.jpg)](").append(profile).append(")\n\n");
        contentBuilder.append("## [GitHub Stats](").append(profile).append(")\n").append(stats);
        contentBuilder.append("## 开源项目\n");
        if (gitHubRepos.isEmpty()) contentBuilder.append("暂未选择展示仓库。\n");
        else {
            contentBuilder.append("| 仓库 | 项目简介 | Stars | Forks | 编程语言 |\n");
            contentBuilder.append("| ---- | ---- | ---- | ---- | ---- |\n");
        }
        for (int i = 0; i < gitHubRepos.length(); i++) {
            final JSONObject repo = gitHubRepos.optJSONObject(i);
            final String url = repo.optString("githubrepoHTMLURL");
            final String desc = repo.optString("githubrepoDescription");
            final String name = repo.optString("githubrepoName");
            final String stars = repo.optString("githubrepoStargazersCount");
            final String forks = repo.optString("githubrepoForksCount");
            final String lang = repo.optString("githubrepoLanguage");
            contentBuilder.append("| [").append(gitHubTableText(name)).append("](").append(url).append(")")
                .append(repo.optBoolean("githubrepoFork") ? " · Fork" : "").append(" | ")
                .append(gitHubTableText(desc)).append(" | ")
                .append(stars).append(" | ")
                .append(forks).append(" | ")
                .append(gitHubTableText(lang)).append("|\n");
        }
        final String content = contentBuilder.toString();

        try {
            final String permalink = "/github";
            JSONObject article = articleRepository.getByPermalink(permalink);
            if (null == article) {
                try {
                    // 新建文章时，删除有可能存在的旧 permalink （用户手动删除文章后残留）
                    JSONObject page = pageRepository.getByPermalink(permalink);
                    final Transaction transaction = pageRepository.beginTransaction();
                    try {
                        pageRepository.remove(page.optString("oId"));
                        transaction.commit();
                    } catch (final Exception ex) {
                        if (transaction.isActive()) {
                            transaction.rollback();
                        }
                        throw ex;
                    }
                } catch (NullPointerException ignored) {
                }
                article = new JSONObject();
                article.put(Article.ARTICLE_AUTHOR_ID, admin.optString(Keys.OBJECT_ID));
                article.put(Article.ARTICLE_TITLE, "我在 GitHub 上的开源项目");
                article.put(Article.ARTICLE_ABSTRACT, Article.getAbstractText(content));
                article.put(Article.ARTICLE_TAGS_REF, "开源,GitHub");
                article.put(Article.ARTICLE_PERMALINK, permalink);
                article.put(Article.ARTICLE_CONTENT, content);
                article.put(Article.ARTICLE_VIEW_PWD, "");
                article.put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED);
                article.put(Common.POST_TO_COMMUNITY, false);
                article.put(Article.ARTICLE_COMMENTABLE, true);

                final JSONObject addArticleReq = new JSONObject();
                addArticleReq.put(Article.ARTICLE, article);
                addArticle(addArticleReq);
            } else {
                final String articleId = article.optString(Keys.OBJECT_ID);
                final long drainedDelta = drainPendingViewDelta(articleId);
                final Transaction transaction = articleRepository.beginTransaction();
                try {
                    final JSONObject baseArticle = articleRepository.get(articleId);
                    if (null != baseArticle) {
                        article = baseArticle;
                    }
                    article.put(Article.ARTICLE_VIEW_COUNT, (int) Math.min(
                            Integer.MAX_VALUE,
                            Math.max(0L, article.optLong(Article.ARTICLE_VIEW_COUNT, 0L) + drainedDelta)));
                    article.put(Article.ARTICLE_CONTENT, content);
                    article.put(Article.ARTICLE_ABSTRACT, Article.getAbstractText(content));
                    article.put(Article.ARTICLE_ABSTRACT_TEXT, Article.getAbstractText(content));
                    article.put(Article.ARTICLE_UPDATED, System.currentTimeMillis());

                    articleRepository.update(articleId, article);
                    transaction.commit();
                } catch (final Exception ex) {
                    if (transaction.isActive()) {
                        transaction.rollback();
                    }
                    if (null != articleCache) {
                        articleCache.clear();
                    }
                    restorePendingViewDelta(articleId, drainedDelta);
                    throw ex;
                }
            }

            final Transaction transaction = pageRepository.beginTransaction();
            try {
                JSONObject page = pageRepository.getByPermalink(permalink);
                if (null == page) {
                    page = new JSONObject();
                    final int maxOrder = pageRepository.getMaxOrder();
                    page.put(Page.PAGE_ORDER, maxOrder + 1);
                    page.put(Page.PAGE_TITLE, "我的开源");
                    page.put(Page.PAGE_OPEN_TARGET, "_self");
                    page.put(Page.PAGE_PERMALINK, permalink);
                    page.put(Page.PAGE_ICON, Latkes.getContextPath() + "/images/github-icon.png");
                    pageRepository.add(page);
                } else {
                    page.put(Page.PAGE_OPEN_TARGET, "_self");
                    page.put(Page.PAGE_ICON, Latkes.getContextPath() + "/images/github-icon.png");
                    pageRepository.update(page.optString(Keys.OBJECT_ID), page);
                }
                transaction.commit();
            } catch (final Exception ex) {
                if (transaction.isActive()) {
                    transaction.rollback();
                }
                throw ex;
            }
            invalidateContentCaches();
            LOGGER.log(Level.INFO, "My github repos page has been generated.");
            return true;
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Updates github repos page failed", e);
            return false;
        }
    }

    private static String gitHubTableText(final String text) {
        return text.replace("\\", "\\\\").replace("|", "\\|").replace("[", "\\[").replace("]", "\\]")
                .replace("<", "&lt;").replace(">", "&gt;").replace('\r', ' ').replace('\n', ' ');
    }

    /**
     * Pushes an article specified by the given article id to community.
     *
     * @param articleId the given article id
     */
    public void pushArticleToCommunity(final String articleId) {
        try {
            final JSONObject article = articleRepository.get(articleId);
            if (null == article) {
                return;
            }

            article.put(Common.POST_TO_COMMUNITY, true);

            final JSONObject data = new JSONObject().put(ARTICLE, article);
            org.b3log.solo.event.BackgroundEvents.community(data, snapshot -> {
                if (PluginUtil.b3logPluginEnabled()) {
                    B3ArticleSender.pushArticleToRhy(snapshot);
                }
                if (PluginUtil.fishpiPluginEnabled()) {
                    FishPiArticleSender.pushArticleToFishPi(snapshot);
                }
            });

        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Pushes an article [id=" + articleId + "] to community failed", e);
        }
    }

    /**
     * Article comment count +1 for an article specified by the given article id.
     *
     * @param articleId the given article id
     * @throws JSONException       json exception
     * @throws RepositoryException repository exception
     */
    public void incArticleCommentCount(final String articleId) throws JSONException, RepositoryException {
        final JSONObject article = articleRepository.get(articleId);
        final JSONObject newArticle = new JSONObject(article, JSONObject.getNames(article));
        final int commentCnt = article.getInt(Article.ARTICLE_COMMENT_COUNT);
        newArticle.put(Article.ARTICLE_COMMENT_COUNT, commentCnt + 1);
        articleRepository.update(articleId, newArticle, ARTICLE_COMMENT_COUNT);
        if (null != publicViewCache) {
            publicViewCache.invalidateCommon();
        }
    }

    /**
     * Cancels publish an article by the specified article id.
     *
     * @param articleId the specified article id
     * @throws ServiceException service exception
     */
    public void cancelPublishArticle(final String articleId) throws ServiceException {
        final Transaction transaction = articleRepository.beginTransaction();

        try {
            final JSONObject article = articleRepository.get(articleId);
            article.put(ARTICLE_STATUS, ARTICLE_STATUS_C_DRAFT);
            articleRepository.update(articleId, article, ARTICLE_STATUS);

            transaction.commit();
            invalidateContentCaches();
        } catch (final Exception e) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
            if (null != articleCache) {
                articleCache.removeArticle(articleId);
            }

            LOGGER.log(Level.ERROR, "Cancels publish article failed", e);

            throw new ServiceException(e);
        }
    }

    /**
     * Puts an article specified by the given article id to top or cancel top.
     *
     * @param articleId the given article id
     * @param top       the specified flag, {@code true} to top, {@code false} to
     *                  cancel top
     * @throws ServiceException service exception
     */
    public void topArticle(final String articleId, final boolean top) throws ServiceException {
        final Transaction transaction = articleRepository.beginTransaction();

        try {
            final JSONObject topArticle = articleRepository.get(articleId);
            topArticle.put(ARTICLE_PUT_TOP, top);
            articleRepository.update(articleId, topArticle, ARTICLE_PUT_TOP);

            transaction.commit();
            invalidateContentCaches();
        } catch (final Exception e) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
            if (null != articleCache) {
                articleCache.removeArticle(articleId);
            }

            LOGGER.log(Level.ERROR, "Can't put the article[oId{0}] to top", articleId);
            throw new ServiceException(e);
        }
    }

    /**
     * Updates an article by the specified request json object.
     *
     * @param requestJSONObject the specified request json object, for example,
     *                          {
     *                          "article": {
     *                          "oId": "",
     *                          "articleTitle": "",
     *                          "articleAbstract": "",
     *                          "articleContent": "",
     *                          "articleTags": "tag1,tag2,tag3", // optional, default set "待分类"
     *                          "categories": "cat1,cat2,cat3",
     *                          "articlePermalink": "", // optional
     *                          "articleStatus": int, // 0: published, 1: draft
     *                          "articleSignId": "", // optional
     *                          "articleCommentable": boolean,
     *                          "articleViewPwd": ""
     *                          }
     *                          }
     * @throws ServiceException service exception
     */
    public void updateArticle(final JSONObject requestJSONObject) throws ServiceException {
        final Transaction transaction = articleRepository.beginTransaction();
        String articleId = null;
        long drainedDelta = 0L;

        try {
            final JSONObject article = requestJSONObject.getJSONObject(ARTICLE);
            String tagsString = article.optString(Article.ARTICLE_TAGS_REF);
            tagsString = Tag.formatTags(tagsString, 4);
            if (StringUtils.isBlank(tagsString)) {
                tagsString = "待分类";
            }
            article.put(Article.ARTICLE_TAGS_REF, tagsString);

            articleId = article.getString(Keys.OBJECT_ID);
            drainedDelta = drainPendingViewDelta(articleId);
            // Set permalink
            final JSONObject oldArticle = articleRepository.get(articleId);
            final String permalink = getPermalinkForUpdateArticle(oldArticle, article, oldArticle.optLong(ARTICLE_CREATED));
            article.put(ARTICLE_PERMALINK, permalink);

            processTagsForArticleUpdate(oldArticle, article);

            // Normalize the date before deriving its archive month.
            fillAutoProperties(oldArticle, article);
            if (drainedDelta > 0L) {
                article.put(ARTICLE_VIEW_COUNT, (int) Math.min(
                        Integer.MAX_VALUE,
                        Math.max(0L, article.optLong(ARTICLE_VIEW_COUNT, 0L) + drainedDelta)));
            }
            archiveDate(article);

            if (!oldArticle.getString(Article.ARTICLE_PERMALINK).equals(permalink)) { // The permalink has been updated
                // Updates related comments' links
                processCommentsForArticleUpdate(article);
            }

            // Fill auto properties
            // Set date
            article.put(ARTICLE_UPDATED, oldArticle.getLong(ARTICLE_UPDATED));
            final long now = System.currentTimeMillis();

            // The article to update has no sign
            if (!article.has(Article.ARTICLE_SIGN_ID)) {
                article.put(Article.ARTICLE_SIGN_ID, "0");
            }

            article.put(ARTICLE_UPDATED, now);

            final String articleImg1URL = getArticleImg1URL(article);
            article.put(ARTICLE_IMG1_URL, articleImg1URL);

            final String articleAbstractText = Article.getAbstractText(article);
            article.put(ARTICLE_ABSTRACT_TEXT, articleAbstractText);

            final boolean hasSeriesField = article.has("seriesId");
            final String seriesId = article.optString("seriesId", "");
            article.remove("seriesId");
            final String operatorName = article.optString("_operatorName", "");
            final String revisionNote = article.optString("_revisionNote", "");
            article.remove("_operatorName");
            article.remove("_revisionNote");

            normalizeVoloArticleFields(oldArticle, article, now);
            archiveDate(article);

            final boolean postToCommunity = article.optBoolean(Common.POST_TO_COMMUNITY);
            article.remove(Common.POST_TO_COMMUNITY);

            // Bolo category
            try {
                String category = String.valueOf(article.get(CATEGORY_REF));
                final JSONObject categoryTag = new JSONObject();
                categoryTag.put(Category.CATEGORY + "_" + Keys.OBJECT_ID, category);
                categoryTag.put(Tag.TAG + "_" + Keys.OBJECT_ID, article.optString(Keys.OBJECT_ID));
                try {
                    categoryTagRepository.removeByTagId(article.optString(Keys.OBJECT_ID));
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                categoryMgmtService.addCategoryTag(categoryTag);
                article.remove(CATEGORY_REF);
            } catch (Exception ignored) {
            }

            articleRepository.update(articleId, article);
            article.put(Common.POST_TO_COMMUNITY, postToCommunity);

            final boolean publishNewArticle = Article.ARTICLE_STATUS_C_DRAFT == oldArticle.optInt(ARTICLE_STATUS) && Article.ARTICLE_STATUS_C_PUBLISHED == article.optInt(ARTICLE_STATUS);
            final JSONObject eventData = new JSONObject();
            eventData.put(ARTICLE, article);
            if (publishNewArticle) {
                eventManager.fireEventAsynchronously(new Event<>(EventTypes.ADD_ARTICLE, eventData));
            } else {
                eventManager.fireEventAsynchronously(new Event<>(EventTypes.UPDATE_ARTICLE, eventData));
            }

            transaction.commit();

            if (null != articleRevisionService) {
                articleRevisionService.recordSnapshot(oldArticle, article, operatorName, revisionNote);
            }
            if (hasSeriesField && null != seriesAndBookshelfService) {
                seriesAndBookshelfService.assignArticleSeries(articleId, seriesId);
            }
            invalidateContentCaches();
            notifyIndexNowIfPublic(article);
        } catch (final ServiceException e) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
            if (null != articleCache) {
                articleCache.clear();
            }
            restorePendingViewDelta(articleId, drainedDelta);

            LOGGER.log(Level.ERROR, "Updates an article failed", e);

            throw e;
        } catch (final Exception e) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
            if (null != articleCache) {
                articleCache.clear();
            }
            restorePendingViewDelta(articleId, drainedDelta);

            LOGGER.log(Level.ERROR, "Updates an article failed", e);

            throw new ServiceException(e.getMessage());
        }
    }

    /**
     * Adds an article from the specified request json object.
     *
     * @param requestJSONObject the specified request json object, for example,
     *                          {
     *                          "article": {
     *                          "articleAuthorId": "",
     *                          "articleTitle": "",
     *                          "articleAbstract": "",
     *                          "articleContent": "",
     *                          "articleTags": "tag1,tag2,tag3",
     *                          "articleStatus": int, // 0: published, 1: draft
     *                          "articlePermalink": "", // optional
     *                          "postToCommunity": boolean, // optional
     *                          "articleSignId": "" // optional, default is "0",
     *                          "articleCommentable": boolean,
     *                          "articleCommentCount": int, // optional, default is 0
     *                          "articleViewPwd": "",
     *                          "oId": "" // optional, generate it if not exists this key
     *                          }
     *                          }
     * @return generated article id
     * @throws ServiceException service exception
     */
    public String addArticle(final JSONObject requestJSONObject) throws ServiceException {
        final Transaction transaction = articleRepository.beginTransaction();

        try {
            final JSONObject article = requestJSONObject.getJSONObject(Article.ARTICLE);
            String ret = article.optString(Keys.OBJECT_ID);
            if (StringUtils.isBlank(ret)) {
                ret = Ids.genTimeMillisId();
                article.put(Keys.OBJECT_ID, ret);
            }

            String tagsString = article.optString(Article.ARTICLE_TAGS_REF);
            tagsString = Tag.formatTags(tagsString, 4);
            if (StringUtils.isBlank(tagsString)) {
                tagsString = "待分类";
            }
            article.put(Article.ARTICLE_TAGS_REF, tagsString);
            final String[] tagTitles = tagsString.split(",");
            final JSONArray tags = tag(tagTitles, article);

            article.put(Article.ARTICLE_COMMENT_COUNT, article.optInt(Article.ARTICLE_COMMENT_COUNT));
            article.put(Article.ARTICLE_VIEW_COUNT, 0);
            if (!article.has(Article.ARTICLE_CREATED)) {
                article.put(Article.ARTICLE_CREATED, System.currentTimeMillis());
            }
            article.put(Article.ARTICLE_UPDATED, article.optLong(Article.ARTICLE_CREATED));
            article.put(Article.ARTICLE_PUT_TOP, false);

            addTagArticleRelation(tags, article);

            archiveDate(article);

            final String permalink = getPermalinkForAddArticle(article);
            article.put(Article.ARTICLE_PERMALINK, permalink);

            final String signId = article.optString(Article.ARTICLE_SIGN_ID, "1");
            article.put(Article.ARTICLE_SIGN_ID, signId);

            article.put(Article.ARTICLE_RANDOM_DOUBLE, Math.random());

            final String articleImg1URL = getArticleImg1URL(article);
            article.put(ARTICLE_IMG1_URL, articleImg1URL);

            final String articleAbstractText = Article.getAbstractText(article);
            article.put(ARTICLE_ABSTRACT_TEXT, articleAbstractText);

            final boolean hasSeriesField = article.has("seriesId");
            final String seriesId = article.optString("seriesId", "");
            article.remove("seriesId");
            final String operatorName = article.optString("_operatorName", "");
            final String revisionNote = article.optString("_revisionNote", "");
            article.remove("_operatorName");
            article.remove("_revisionNote");

            normalizeVoloArticleFields(null, article, System.currentTimeMillis());
            if (Article.ARTICLE_STATUS_C_PUBLISHED != article.optInt(ARTICLE_STATUS)) {
                unArchiveDate(ret);
            } else {
                archiveDate(article);
            }

            final boolean postToCommunity = article.optBoolean(Common.POST_TO_COMMUNITY);
            article.remove(Common.POST_TO_COMMUNITY);

            // Bolo category
            try {
                String category = String.valueOf(article.get(CATEGORY_REF));
                final JSONObject categoryTag = new JSONObject();
                categoryTag.put(Category.CATEGORY + "_" + Keys.OBJECT_ID, category);
                categoryTag.put(Tag.TAG + "_" + Keys.OBJECT_ID, article.optString(Keys.OBJECT_ID));
                categoryMgmtService.addCategoryTag(categoryTag);
                article.remove(CATEGORY_REF);
            } catch (Exception ignored) {
            }

            articleRepository.add(article);
            transaction.commit();

            if (null != articleRevisionService) {
                articleRevisionService.recordSnapshot(null, article, operatorName, revisionNote);
            }
            if (hasSeriesField && null != seriesAndBookshelfService) {
                seriesAndBookshelfService.assignArticleSeries(ret, seriesId);
            }
            invalidateContentCaches();

            article.put(Common.POST_TO_COMMUNITY, postToCommunity);
            notifyIndexNowIfPublic(article);
            if (Article.ARTICLE_STATUS_C_PUBLISHED == article.optInt(ARTICLE_STATUS)) {
                final JSONObject eventData = new JSONObject();
                eventData.put(Article.ARTICLE, article);
                eventManager.fireEventAsynchronously(new Event<>(EventTypes.ADD_ARTICLE, eventData));
            }

            return ret;
        } catch (final Exception e) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
            if (null != articleCache) {
                articleCache.clear();
            }

            throw new ServiceException(e.getMessage());
        }
    }

    /**
     * Removes the article specified by the given id.
     *
     * @param articleId the given id
     * @throws ServiceException service exception
     */
    public void removeArticle(final String articleId) throws ServiceException {
        final Transaction transaction = articleRepository.beginTransaction();
        try {
            unArchiveDate(articleId);
            removeTagArticleRelations(articleId);
            articleRepository.remove(articleId);
            commentRepository.removeComments(articleId);
            transaction.commit();
            if (null != articleId) {
                pendingArticleViewDeltas.remove(articleId);
            }
            if (null != articleRevisionService) {
                articleRevisionService.removeRevisions(articleId);
            }
            if (null != seriesAndBookshelfService) {
                seriesAndBookshelfService.removeArticleFromAllSeries(articleId);
            }
            invalidateContentCaches();
            eventManager.fireEventAsynchronously(new Event<>(EventTypes.DELETE_ARTICLE, articleId));
        } catch (final Exception e) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
            if (null != articleCache) {
                articleCache.clear();
            }

            LOGGER.log(Level.ERROR, "Removes an article[id=" + articleId + "] failed", e);
            throw new ServiceException(e);
        }
    }

    /**
     * Updates the random values of articles fetched with the specified update
     * count.
     *
     * @param updateCnt the specified update count
     * @throws ServiceException service exception
     */
    public void updateArticlesRandomValue(final int updateCnt) throws ServiceException {
        final Transaction transaction = articleRepository.beginTransaction();
        List<JSONObject> randomArticles = null;

        try {
            randomArticles = articleRepository.getRandomly(updateCnt);

            for (final JSONObject article : randomArticles) {
                article.put(Article.ARTICLE_RANDOM_DOUBLE, Math.random());

                articleRepository.update(article.getString(Keys.OBJECT_ID), article, ARTICLE_RANDOM_DOUBLE);
            }

            transaction.commit();
        } catch (final Exception e) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
            if (null != articleCache && null != randomArticles) {
                for (final JSONObject article : randomArticles) {
                    if (null != article && null != articleCache.getArticle(article.optString(Keys.OBJECT_ID))) {
                        articleCache.removeArticle(article.optString(Keys.OBJECT_ID));
                    }
                }
            }

            LOGGER.log(Level.WARN, "Updates article random value failed");

            throw new ServiceException(e);
        }
    }

    /**
     * Increments the view count of the article specified by the given article id (lock-free write-behind).
     *
     * @param articleId the given article id
     * @throws ServiceException service exception
     */
    public void incViewCount(final String articleId) throws ServiceException {
        if (StringUtils.isBlank(articleId)) {
            return;
        }
        if (!pendingArticleViewDeltas.containsKey(articleId)
                && pendingArticleViewDeltas.size() >= MAX_PENDING_VIEW_ARTICLES) {
            flushPendingViewCounts();
        }
        pendingArticleViewDeltas.computeIfAbsent(articleId, k -> new LongAdder()).increment();
        if (null != articleCache) {
            articleCache.incrementViewCountInPlace(articleId, 1L);
        }
    }

    /**
     * Returns the un-flushed write-behind view delta for an article.
     *
     * @param articleId article object ID
     * @return non-negative pending view delta
     */
    public long getPendingViewDelta(final String articleId) {
        if (StringUtils.isBlank(articleId)) {
            return 0L;
        }
        final LongAdder adder = pendingArticleViewDeltas.get(articleId);
        return null == adder ? 0L : Math.max(0L, adder.sum());
    }

    /**
     * Atomically drains any un-flushed write-behind view delta for {@code articleId} under {@code viewFlushLock}
     * and aligns any cached {@link ArticleCache} entry back to its persisted base count so callers can add
     * the returned delta once without double-counting on either {@code idCache} hits or misses.
     *
     * @param articleId article object ID
     * @return non-negative drained view delta
     */
    public long drainPendingViewDelta(final String articleId) {
        if (StringUtils.isBlank(articleId)) {
            return 0L;
        }
        synchronized (viewFlushLock) {
            final LongAdder adder = pendingArticleViewDeltas.remove(articleId);
            if (null == adder) {
                return 0L;
            }
            final long delta = Math.max(0L, adder.sum());
            if (delta > 0L && null != articleCache) {
                final JSONObject cached = articleCache.getArticle(articleId);
                if (null != cached) {
                    final long baseCount = Math.max(0L, cached.optLong(ARTICLE_VIEW_COUNT, 0L) - delta);
                    cached.put(ARTICLE_VIEW_COUNT, (int) Math.min(Integer.MAX_VALUE, baseCount));
                    articleCache.putArticle(cached);
                }
            }
            return delta;
        }
    }

    /**
     * Restores a previously drained write-behind view delta if a full-article update transaction rolls back.
     *
     * @param articleId article object ID
     * @param delta     positive delta to restore
     */
    public void restorePendingViewDelta(final String articleId, final long delta) {
        if (StringUtils.isBlank(articleId) || delta <= 0L) {
            return;
        }
        synchronized (viewFlushLock) {
            pendingArticleViewDeltas.computeIfAbsent(articleId, k -> new LongAdder()).add(delta);
            if (null != articleCache) {
                final JSONObject cached = articleCache.getArticle(articleId);
                if (null != cached) {
                    final long restored = Math.max(0L, cached.optLong(ARTICLE_VIEW_COUNT, 0L) + delta);
                    cached.put(ARTICLE_VIEW_COUNT, (int) Math.min(Integer.MAX_VALUE, restored));
                    articleCache.putArticle(cached);
                }
            }
        }
    }

    /**
     * Clears all un-flushed write-behind article view deltas (used when restoring a backup).
     */
    public void resetPendingViewDeltas() {
        synchronized (viewFlushLock) {
            pendingArticleViewDeltas.clear();
        }
    }

    /**
     * Atomically drains accumulated write-behind article view deltas and batch-updates
     * {@code b3_solo_article} in a transaction, restoring drained deltas if rollback occurs.
     */
    public void flushPendingViewCounts() {
        if (null == articleRepository || pendingArticleViewDeltas.isEmpty()) {
            return;
        }
        synchronized (viewFlushLock) {
            final Map<String, Long> drained = new LinkedHashMap<>();
            for (final Map.Entry<String, LongAdder> entry : pendingArticleViewDeltas.entrySet()) {
                final long delta = entry.getValue().sumThenReset();
                if (delta > 0L) {
                    drained.put(entry.getKey(), delta);
                } else {
                    pendingArticleViewDeltas.remove(entry.getKey(), entry.getValue());
                }
            }
            if (drained.isEmpty()) {
                return;
            }
            final Transaction transaction = articleRepository.beginTransaction();
            final List<String> ids = new ArrayList<>(drained.keySet());
            int updated = 0;
            try {
                for (int start = 0; start < ids.size(); start += VIEW_FLUSH_BATCH_SIZE) {
                    final List<String> chunk = ids.subList(start, Math.min(ids.size(), start + VIEW_FLUSH_BATCH_SIZE));
                    final Query query = new Query()
                            .setFilter(new PropertyFilter(Keys.OBJECT_ID, FilterOperator.IN, chunk))
                            .select(Keys.OBJECT_ID, ARTICLE_VIEW_COUNT)
                            .setPageCount(1);
                    final JSONArray rows = QueryResults.array(articleRepository.get(query));
                    for (int i = 0; i < rows.length(); i++) {
                        final JSONObject row = rows.optJSONObject(i);
                        if (null == row) {
                            continue;
                        }
                        final String id = row.optString(Keys.OBJECT_ID);
                        final Long delta = drained.get(id);
                        if (null == delta || delta <= 0L) {
                            continue;
                        }
                        final int updatedCount = (int) Math.min(
                                Integer.MAX_VALUE,
                                Math.max(0L, row.optLong(ARTICLE_VIEW_COUNT, 0L) + delta));
                        final JSONObject patch = new JSONObject();
                        patch.put(Keys.OBJECT_ID, id);
                        patch.put(ARTICLE_VIEW_COUNT, updatedCount);
                        articleRepository.update(id, patch, ARTICLE_VIEW_COUNT);
                        updated++;
                    }
                }
                transaction.commit();
                for (final String id : ids) {
                    final LongAdder adder = pendingArticleViewDeltas.get(id);
                    if (null != adder && adder.sum() <= 0L) {
                        pendingArticleViewDeltas.remove(id, adder);
                    }
                }
                if (updated > 0 && null != publicViewCache) {
                    publicViewCache.invalidateSidebarViewWidgets();
                }
            } catch (final Exception e) {
                if (null != transaction && transaction.isActive()) {
                    transaction.rollback();
                }
                if (null != articleCache) {
                    for (final String id : ids) {
                        if (null != articleCache.getArticle(id)) {
                            articleCache.removeArticle(id);
                        }
                    }
                }
                for (final Map.Entry<String, Long> entry : drained.entrySet()) {
                    pendingArticleViewDeltas.computeIfAbsent(entry.getKey(), k -> new LongAdder()).add(entry.getValue());
                }
                LOGGER.log(Level.WARN, "Flushes pending article view counts failed", e);
            }
        }
    }

    /**
     * Un-archive an article specified by the given specified article id.
     *
     * @param articleId the given article id
     * @throws ServiceException service exception
     */
    private void unArchiveDate(final String articleId) throws ServiceException {
        try {
            final JSONObject archiveDateArticleRelation = archiveDateArticleRepository.getByArticleId(articleId);
            if (null == archiveDateArticleRelation) {
                // 草稿不生成存档，所以需要判空
                return;
            }

            final String archiveDateId = archiveDateArticleRelation.getString(ArchiveDate.ARCHIVE_DATE + "_" + Keys.OBJECT_ID);
            final int publishedArticleCount = archiveDateArticleRepository.getPublishedArticleCount(archiveDateId);
            if (1 > publishedArticleCount) {
                archiveDateRepository.remove(archiveDateId);
            }

            archiveDateArticleRepository.remove(archiveDateArticleRelation.getString(Keys.OBJECT_ID));
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Unarchive date for article[id=" + articleId + "] failed", e);

            throw new ServiceException(e);
        }
    }

    /**
     * Processes comments for article update.
     *
     * @param article the specified article to update
     * @throws Exception exception
     */
    private void processCommentsForArticleUpdate(final JSONObject article) throws Exception {
        final String articleId = article.getString(Keys.OBJECT_ID);

        final List<JSONObject> comments = commentRepository.getComments(articleId, 1, Integer.MAX_VALUE);
        for (final JSONObject comment : comments) {
            final String commentId = comment.getString(Keys.OBJECT_ID);
            final String sharpURL = Comment.getCommentSharpURLForArticle(article, commentId);
            comment.put(Comment.COMMENT_SHARP_URL, sharpURL);
            if (StringUtils.isBlank(comment.optString(Comment.COMMENT_ORIGINAL_COMMENT_ID))) {
                comment.put(Comment.COMMENT_ORIGINAL_COMMENT_ID, "");
            }
            if (StringUtils.isBlank(comment.optString(Comment.COMMENT_ORIGINAL_COMMENT_NAME))) {
                comment.put(Comment.COMMENT_ORIGINAL_COMMENT_NAME, "");
            }

            commentRepository.update(commentId, comment);
        }
    }

    /**
     * Processes tags for article update.
     * <p>
     * <ul>
     * <li>Un-tags old article, decrements tag reference count</li>
     * <li>Removes old article-tag relations</li>
     * <li>Saves new article-tag relations with tag reference count</li>
     * </ul>
     *
     * @param oldArticle the specified old article
     * @param newArticle the specified new article
     * @throws Exception exception
     */
    private void processTagsForArticleUpdate(final JSONObject oldArticle, final JSONObject newArticle) throws Exception {
        final String oldArticleId = oldArticle.getString(Keys.OBJECT_ID);
        final List<JSONObject> oldTags = tagRepository.getByArticleId(oldArticleId);
        final String tagsString = newArticle.getString(Article.ARTICLE_TAGS_REF);
        String[] tagStrings = tagsString.split(",");
        final List<JSONObject> newTags = new ArrayList<>();

        for (int i = 0; i < tagStrings.length; i++) {
            final String tagTitle = tagStrings[i].trim();
            JSONObject newTag = tagRepository.getByTitle(tagTitle);

            if (null == newTag) {
                newTag = new JSONObject();
                newTag.put(Tag.TAG_TITLE, tagTitle);
            }
            newTags.add(newTag);
        }

        final List<JSONObject> tagsDropped = new ArrayList<>();
        final List<JSONObject> tagsNeedToAdd = new ArrayList<>();
        final List<JSONObject> tagsUnchanged = new ArrayList<>();

        for (final JSONObject newTag : newTags) {
            final String newTagTitle = newTag.getString(Tag.TAG_TITLE);

            if (!tagExists(newTagTitle, oldTags)) {
                LOGGER.log(Level.DEBUG, "Tag need to add[title={0}]", newTagTitle);
                tagsNeedToAdd.add(newTag);
            } else {
                tagsUnchanged.add(newTag);
            }
        }
        for (final JSONObject oldTag : oldTags) {
            final String oldTagTitle = oldTag.getString(Tag.TAG_TITLE);

            if (!tagExists(oldTagTitle, newTags)) {
                LOGGER.log(Level.DEBUG, "Tag dropped[title={0}]", oldTag);
                tagsDropped.add(oldTag);
            } else {
                tagsUnchanged.remove(oldTag);
            }
        }

        LOGGER.log(Level.DEBUG, "Tags unchanged [{0}]", tagsUnchanged);

        final String[] tagIdsDropped = new String[tagsDropped.size()];
        for (int i = 0; i < tagIdsDropped.length; i++) {
            final JSONObject tag = tagsDropped.get(i);
            final String id = tag.getString(Keys.OBJECT_ID);
            tagIdsDropped[i] = id;
        }

        removeTagArticleRelations(oldArticleId, 0 == tagIdsDropped.length ? new String[]{"l0y0l"} : tagIdsDropped);

        tagStrings = new String[tagsNeedToAdd.size()];
        for (int i = 0; i < tagStrings.length; i++) {
            final JSONObject tag = tagsNeedToAdd.get(i);
            final String tagTitle = tag.getString(Tag.TAG_TITLE);
            tagStrings[i] = tagTitle;
        }
        final JSONArray tags = tag(tagStrings, newArticle);

        addTagArticleRelation(tags, newArticle);
    }

    /**
     * Removes tag-article relations by the specified article id and tag ids of the relations to be removed.
     * <p>
     * Removes all relations if not specified the tag ids.
     * </p>
     *
     * @param articleId the specified article id
     * @param tagIds    the specified tag ids of the relations to be removed
     * @throws JSONException       json exception
     * @throws RepositoryException repository exception
     */
    private void removeTagArticleRelations(final String articleId, final String... tagIds) throws JSONException, RepositoryException {
        final List<String> tagIdList = Arrays.asList(tagIds);
        final List<JSONObject> tagArticleRelations = tagArticleRepository.getByArticleId(articleId);

        for (int i = 0; i < tagArticleRelations.size(); i++) {
            final JSONObject tagArticleRelation = tagArticleRelations.get(i);
            String relationId;
            if (tagIdList.isEmpty()) { // Removes all if un-specified
                relationId = tagArticleRelation.getString(Keys.OBJECT_ID);
                tagArticleRepository.remove(relationId);
            } else {
                if (tagIdList.contains(tagArticleRelation.getString(Tag.TAG + "_" + Keys.OBJECT_ID))) {
                    relationId = tagArticleRelation.getString(Keys.OBJECT_ID);
                    tagArticleRepository.remove(relationId);
                }
            }

            final String tagId = tagArticleRelation.optString(Tag.TAG + "_" + Keys.OBJECT_ID);
            final int articleCount = tagArticleRepository.getArticleCount(tagId);
            if (1 > articleCount) {
                // Bolo
                // categoryTagRepository.removeByTagId(tagId);
                tagRepository.remove(tagId);
            }
        }
    }

    /**
     * Adds relation of the specified tags and article.
     *
     * @param tags    the specified tags
     * @param article the specified article
     * @throws RepositoryException repository exception
     */
    private void addTagArticleRelation(final JSONArray tags, final JSONObject article) throws RepositoryException {
        for (int i = 0; i < tags.length(); i++) {
            final JSONObject tag = tags.optJSONObject(i);
            final JSONObject tagArticleRelation = new JSONObject();

            tagArticleRelation.put(Tag.TAG + "_" + Keys.OBJECT_ID, tag.optString(Keys.OBJECT_ID));
            tagArticleRelation.put(Article.ARTICLE + "_" + Keys.OBJECT_ID, article.optString(Keys.OBJECT_ID));

            tagArticleRepository.add(tagArticleRelation);
        }
    }

    /**
     * Tags the specified article with the specified tag titles.
     *
     * @param tagTitles the specified tag titles
     * @param article   the specified article
     * @return an array of tags
     * @throws RepositoryException repository exception
     */
    private JSONArray tag(final String[] tagTitles, final JSONObject article) throws RepositoryException {
        final JSONArray ret = new JSONArray();

        for (int i = 0; i < tagTitles.length; i++) {
            final String tagTitle = tagTitles[i].trim();
            JSONObject tag = tagRepository.getByTitle(tagTitle);
            String tagId;

            if (null == tag) {
                LOGGER.log(Level.TRACE, "Found a new tag[title={0}] in article[title={1}]",
                        tagTitle, article.optString(Article.ARTICLE_TITLE));
                tag = new JSONObject();
                tag.put(Tag.TAG_TITLE, tagTitle);
                tagId = tagRepository.add(tag);
                tag.put(Keys.OBJECT_ID, tagId);
            } else {
                tagId = tag.optString(Keys.OBJECT_ID);
                LOGGER.log(Level.TRACE, "Found a existing tag[title={0}, id={1}] in article[title={2}]",
                        tag.optString(Tag.TAG_TITLE), tag.optString(Keys.OBJECT_ID), article.optString(Article.ARTICLE_TITLE));
                final JSONObject tagTmp = new JSONObject();
                tagTmp.put(Keys.OBJECT_ID, tagId);
                tagTmp.put(Tag.TAG_TITLE, tagTitle);
                tagRepository.update(tagId, tagTmp);
            }

            ret.put(tag);
        }

        return ret;
    }

    /**
     * Archive the create date with the specified article.
     *
     * @param article the specified article, for example,
     *                {
     *                "oId": "",
     *                ....
     *                }
     * @throws RepositoryException repository exception
     */
    private void archiveDate(final JSONObject article) throws RepositoryException {
        if (Article.ARTICLE_STATUS_C_PUBLISHED != article.optInt(ARTICLE_STATUS)) {
            // 草稿不生成存档
            return;
        }

        final long created = article.optLong(ARTICLE_CREATED);
        final String createDateString = DateFormatUtils.format(created, "yyyy/MM");
        JSONObject archiveDate = archiveDateRepository.getByArchiveDate(createDateString);
        if (null == archiveDate) {
            archiveDate = new JSONObject();
            try {
                archiveDate.put(ArchiveDate.ARCHIVE_TIME, DateUtils.parseDate(createDateString, new String[]{"yyyy/MM"}).getTime());
                archiveDateRepository.add(archiveDate);
            } catch (final ParseException e) {
                LOGGER.log(Level.ERROR, e.getMessage(), e);
                throw new RepositoryException(e);
            }
        }

        final String articleId = article.optString(Keys.OBJECT_ID);
        final JSONObject existingRelation = archiveDateArticleRepository.getByArticleId(articleId);
        if (null == existingRelation) {
            final JSONObject archiveDateArticleRelation = new JSONObject();
            archiveDateArticleRelation.put(ArchiveDate.ARCHIVE_DATE + "_" + Keys.OBJECT_ID, archiveDate.optString(Keys.OBJECT_ID));
            archiveDateArticleRelation.put(Article.ARTICLE + "_" + Keys.OBJECT_ID, articleId);
            archiveDateArticleRepository.add(archiveDateArticleRelation);
        } else if (!archiveDate.getString(Keys.OBJECT_ID).equals(existingRelation.optString(ArchiveDate.ARCHIVE_DATE + "_" + Keys.OBJECT_ID))) {
            existingRelation.put(ArchiveDate.ARCHIVE_DATE + "_" + Keys.OBJECT_ID, archiveDate.getString(Keys.OBJECT_ID));
            archiveDateArticleRepository.update(existingRelation.getString(Keys.OBJECT_ID), existingRelation);
        }
    }

    /**
     * Fills 'auto' properties for the specified article and old article.
     * <p>
     * Some properties of an article are not been changed while article
     * updating, these properties are called 'auto' properties.
     * </p>
     * <p>
     * The property(named {@value org.b3log.solo.model.Article#ARTICLE_RANDOM_DOUBLE}) of the specified
     * article will be regenerated.
     * </p>
     *
     * @param oldArticle the specified old article
     * @param article    the specified article
     * @throws JSONException json exception
     */
    private void fillAutoProperties(final JSONObject oldArticle, final JSONObject article) throws JSONException {
        final long created = oldArticle.getLong(ARTICLE_CREATED);
        if (!article.has(ARTICLE_CREATED) || article.isNull(ARTICLE_CREATED) || article.optString(ARTICLE_CREATED).isBlank()) {
            article.put(ARTICLE_CREATED, created);
        } else {
            try {
                article.put(ARTICLE_CREATED, article.getLong(ARTICLE_CREATED));
            } catch (JSONException invalidDate) {
                LOGGER.log(Level.WARN, "Invalid article creation date; retaining its previous value");
                article.put(ARTICLE_CREATED, created);
            }
        }
        article.put(ARTICLE_COMMENT_COUNT, oldArticle.getInt(ARTICLE_COMMENT_COUNT));
        article.put(ARTICLE_VIEW_COUNT, oldArticle.getInt(ARTICLE_VIEW_COUNT));
        article.put(ARTICLE_PUT_TOP, oldArticle.getBoolean(ARTICLE_PUT_TOP));
        article.put(ARTICLE_AUTHOR_ID, oldArticle.getString(ARTICLE_AUTHOR_ID));
        article.put(ARTICLE_RANDOM_DOUBLE, Math.random());
    }

    /**
     * Gets article permalink for adding article with the specified article.
     *
     * @param article the specified article
     * @return permalink
     * @throws ServiceException if invalid permalink occurs
     */
    private String getPermalinkForAddArticle(final JSONObject article) throws ServiceException {
        final long date = article.optLong(Article.ARTICLE_CREATED);
        String ret = article.optString(Article.ARTICLE_PERMALINK);
        if (StringUtils.isBlank(ret)) {
            ret = "/articles/" + DateFormatUtils.format(date, "yyyy/MM/dd") + "/" + article.optString(Keys.OBJECT_ID) + ".html";
        }

        if (!ret.startsWith("/")) {
            ret = "/" + ret;
        }

        if (PermalinkQueryService.invalidArticlePermalinkFormat(ret)) {
            throw new ServiceException(langPropsService.get("invalidPermalinkFormatLabel"));
        }

        if (permalinkQueryService.exist(ret)) {
            throw new ServiceException(langPropsService.get("duplicatedPermalinkLabel"));
        }

        return ret.replaceAll(" ", "-");
    }

    /**
     * Gets article permalink for updating article with the specified old article, article, created at.
     *
     * @param oldArticle the specified old article
     * @param article    the specified article
     * @param created    the specified created
     * @return permalink
     * @throws ServiceException if invalid permalink occurs
     * @throws JSONException    json exception
     */
    private String getPermalinkForUpdateArticle(final JSONObject oldArticle, final JSONObject article, final long created)
            throws ServiceException, JSONException {
        final String articleId = article.getString(Keys.OBJECT_ID);
        String ret = article.optString(ARTICLE_PERMALINK).trim();
        final String oldPermalink = oldArticle.getString(ARTICLE_PERMALINK);

        if (!oldPermalink.equals(ret)) {
            if (StringUtils.isBlank(ret)) {
                ret = "/articles/" + DateFormatUtils.format(created, "yyyy/MM/dd") + "/" + articleId + ".html";
            }

            if (!ret.startsWith("/")) {
                ret = "/" + ret;
            }

            if (PermalinkQueryService.invalidArticlePermalinkFormat(ret)) {
                throw new ServiceException(langPropsService.get("invalidPermalinkFormatLabel"));
            }

            if (!oldPermalink.equals(ret) && permalinkQueryService.exist(ret)) {
                throw new ServiceException(langPropsService.get("duplicatedPermalinkLabel"));
            }
        }

        return ret.replaceAll(" ", "-");
    }

    /**
     * Determines whether the specified tag title exists in the specified tags.
     *
     * @param tagTitle the specified tag title
     * @param tags     the specified tags
     * @return {@code true} if it exists, {@code false} otherwise
     * @throws JSONException json exception
     */
    private void normalizeVoloArticleFields(final JSONObject oldArticle, final JSONObject article, final long now) throws ServiceException {
        long scheduleTime = article.has(ARTICLE_SCHEDULE_TIME)
                ? Math.max(0L, article.optLong(ARTICLE_SCHEDULE_TIME, 0L))
                : (oldArticle == null ? 0L : Math.max(0L, oldArticle.optLong(ARTICLE_SCHEDULE_TIME, 0L)));
        if (scheduleTime > now) {
            article.put(ARTICLE_STATUS, Article.ARTICLE_STATUS_C_DRAFT);
            article.put(ARTICLE_SCHEDULE_TIME, scheduleTime);
        } else {
            article.put(ARTICLE_SCHEDULE_TIME, 0L);
        }

        String audioUrl = article.has(ARTICLE_AUDIO_URL)
                ? StringUtils.trimToEmpty(article.optString(ARTICLE_AUDIO_URL))
                : (oldArticle == null ? "" : StringUtils.trimToEmpty(oldArticle.optString(ARTICLE_AUDIO_URL)));
        if (!audioUrl.isEmpty() && !org.b3log.solo.util.OutboundUrls.isOptionalSafeIconOrAvatar(audioUrl)) {
            throw new ServiceException("音频地址必须是合法的 http(s) URL 或站内以 / 开头的安全路径。");
        }
        if (audioUrl.length() > 1024) {
            throw new ServiceException("音频地址长度不能超过 1024 个字符。");
        }
        article.put(ARTICLE_AUDIO_URL, audioUrl);

        final int audioDuration = article.has(ARTICLE_AUDIO_DURATION)
                ? Math.max(0, article.optInt(ARTICLE_AUDIO_DURATION, 0))
                : (oldArticle == null ? 0 : Math.max(0, oldArticle.optInt(ARTICLE_AUDIO_DURATION, 0)));
        article.put(ARTICLE_AUDIO_DURATION, audioDuration);

        final long audioSize = article.has(ARTICLE_AUDIO_SIZE)
                ? Math.max(0L, article.optLong(ARTICLE_AUDIO_SIZE, 0L))
                : (oldArticle == null ? 0L : Math.max(0L, oldArticle.optLong(ARTICLE_AUDIO_SIZE, 0L)));
        article.put(ARTICLE_AUDIO_SIZE, audioSize);
    }

    /**
     * Publishes draft articles whose {@code articleScheduleTime} has arrived.
     *
     * @return number of articles auto-published in this run
     */
    public int publishDueScheduledArticles() {
        final long now = System.currentTimeMillis();
        List<JSONObject> dueArticles;
        try {
            final Query query = new Query()
                    .setFilter(CompositeFilterOperator.and(
                            new PropertyFilter(ARTICLE_STATUS, FilterOperator.EQUAL, Article.ARTICLE_STATUS_C_DRAFT),
                            new PropertyFilter(ARTICLE_SCHEDULE_TIME, FilterOperator.GREATER_THAN, 0L),
                            new PropertyFilter(ARTICLE_SCHEDULE_TIME, FilterOperator.LESS_THAN_OR_EQUAL, now)))
                    .setPage(1, 50)
                    .setPageCount(1);
            dueArticles = articleRepository.getList(query);
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Queries due scheduled articles failed", e);
            return 0;
        }
        if (null == dueArticles || dueArticles.isEmpty()) {
            return 0;
        }

        int publishedCount = 0;
        for (final JSONObject draft : dueArticles) {
            final String articleId = draft.optString(Keys.OBJECT_ID);
            final long drainedDelta = drainPendingViewDelta(articleId);
            final Transaction tx = articleRepository.beginTransaction();
            try {
                final JSONObject current = articleRepository.get(articleId);
                if (null == current || Article.ARTICLE_STATUS_C_DRAFT != current.optInt(ARTICLE_STATUS)) {
                    tx.commit();
                    restorePendingViewDelta(articleId, drainedDelta);
                    continue;
                }
                final long scheduledAt = current.optLong(ARTICLE_SCHEDULE_TIME, 0L);
                if (scheduledAt <= 0L || scheduledAt > now) {
                    tx.commit();
                    restorePendingViewDelta(articleId, drainedDelta);
                    continue;
                }
                current.put(ARTICLE_VIEW_COUNT, (int) Math.min(
                        Integer.MAX_VALUE,
                        Math.max(0L, current.optLong(ARTICLE_VIEW_COUNT, 0L) + drainedDelta)));
                current.put(ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED);
                current.put(ARTICLE_SCHEDULE_TIME, 0L);
                current.put(ARTICLE_UPDATED, now);
                archiveDate(current);
                articleRepository.update(articleId, current);
                tx.commit();
                publishedCount++;
                notifyIndexNowIfPublic(current);

                final JSONObject eventData = new JSONObject();
                eventData.put(ARTICLE, current);
                eventManager.fireEventAsynchronously(new Event<>(EventTypes.ADD_ARTICLE, eventData));
                LOGGER.log(Level.INFO, "Scheduled article auto-published [id={0}, title={1}]",
                        articleId, current.optString(ARTICLE_TITLE));
            } catch (final Exception e) {
                if (tx.isActive()) {
                    tx.rollback();
                }
                if (null != articleCache) {
                    articleCache.removeArticle(articleId);
                }
                restorePendingViewDelta(articleId, drainedDelta);
                LOGGER.log(Level.ERROR, "Auto-publishes scheduled article failed [id=" + articleId + "]", e);
            }
        }
        if (publishedCount > 0) {
            invalidateContentCaches();
        }
        return publishedCount;
    }

    private static boolean tagExists(final String tagTitle, final List<JSONObject> tags) throws JSONException {
        for (final JSONObject tag : tags) {
            if (tag.getString(Tag.TAG_TITLE).equals(tagTitle)) {
                return true;
            }
        }

        return false;
    }
}
