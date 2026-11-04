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
package org.b3log.solo.processor.console;

import org.apache.commons.text.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.ioc.Singleton;
import org.apache.logging.log4j.Level;
import org.b3log.solo.logging.Logger;
import org.b3log.latke.model.Role;
import org.b3log.latke.model.User;
import org.b3log.latke.repository.RepositoryException;
import org.b3log.latke.repository.Transaction;
import org.b3log.latke.service.LangPropsService;
import org.b3log.latke.service.ServiceException;
import org.b3log.latke.http.RequestContext;
import org.b3log.solo.web.annotation.Before;
import org.b3log.latke.http.renderer.JsonRenderer;
import org.b3log.latke.util.Strings;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Common;
import org.b3log.solo.repository.CategoryTagRepository;
import org.b3log.solo.service.*;
import org.b3log.solo.util.Images;
import org.b3log.solo.util.Markdowns;
import org.b3log.solo.util.Solos;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import org.b3log.latke.http.Request;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Article console request processing.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 0.4.0
 */
@Singleton
@Before(ConsoleAuthAdvice.class)
public class ArticleConsole {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(ArticleConsole.class);

    /**
     * Article management service.
     */
    @Inject
    private ArticleMgmtService articleMgmtService;

    /**
     * Article query service.
     */
    @Inject
    private ArticleQueryService articleQueryService;

    /**
     * User query service.
     */
    @Inject
    private UserQueryService userQueryService;

    /**
     * Language service.
     */
    @Inject
    private LangPropsService langPropsService;

    /**
     * Category tag repository.
     */
    @Inject
    CategoryTagRepository categoryTagRepository;

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

    /**
     * Pushes an article to community.
     *
     * @param context the specified request context
     */
    public void pushArticleToCommunity(final RequestContext context) {
        final String articleId = context.param("id");
        final JSONObject currentUser = Solos.getCurrentUser(context.getRequest(), context.getResponse());
        try {
            if (StringUtils.isBlank(articleId) || !articleQueryService.canAccessArticle(articleId, currentUser)) {
                context.renderJSON(new JSONObject().put(Keys.CODE, -1).put(Keys.MSG, langPropsService.get("forbiddenLabel")));
                return;
            }
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, e.getMessage(), e);
            context.renderJSON(new JSONObject().put(Keys.CODE, -1).put(Keys.MSG, langPropsService.get("getFailLabel")));
            return;
        }
        final JSONObject result = new JSONObject().put(Keys.CODE, 0);
        context.renderJSON(result);
        articleMgmtService.pushArticleToCommunity(articleId);
    }

    /**
     * Gets article thumbs.
     *
     * @param context the specified request context
     */
    public void getArticleThumbs(final RequestContext context) {
        final JsonRenderer renderer = new JsonRenderer();
        context.setRenderer(renderer);
        final JSONObject result = new JSONObject();
        renderer.setJSONObject(result);
        result.put(org.b3log.solo.model.Common.STATUS_CODE, true);
        final Request request = context.getRequest();
        String strN = context.param("n");
        if (!Strings.isNumeric(strN)) {
            strN = "6";
        }

        final int n = Math.min(20, Math.max(1, Integer.valueOf(strN)));
        final List<String> urls = Images.randomImages(n);

        final String wStr = context.param("w");
        int w = Article.ARTICLE_THUMB_IMG_WIDTH;
        if (Strings.isNumeric(wStr)) {
            w = Integer.valueOf(wStr);
        }
        final int width = w;
        final String hStr = context.param("h");
        int h = Article.ARTICLE_THUMB_IMG_HEIGHT;
        if (Strings.isNumeric(hStr)) {
            h = Integer.valueOf(hStr);
        }
        final int height = h;

        result.put("data", urls.stream().map(url -> Images.imageSize(url, width, height)).collect(Collectors.toList()));
    }

    /**
     * Gets an article by the specified request json object.
     *
     * @param context the specified request context
     */
    public void getArticle(final RequestContext context) {
        final JsonRenderer renderer = new JsonRenderer();
        context.setRenderer(renderer);
        try {
            final String articleId = context.pathVar("id");
            final JSONObject currentUser = Solos.getCurrentUser(context.getRequest(), context.getResponse());
            if (!articleQueryService.canAccessArticle(articleId, currentUser)) {
                final JSONObject forbidden = new JSONObject().put(org.b3log.solo.model.Common.STATUS_CODE, false);
                forbidden.put(Keys.MSG, langPropsService.get("forbiddenLabel"));
                renderer.setJSONObject(forbidden);
                return;
            }
            final JSONObject result = articleQueryService.getArticle(articleId);
            if (null == result) {
                final JSONObject missing = new JSONObject().put(org.b3log.solo.model.Common.STATUS_CODE, false);
                missing.put(Keys.MSG, langPropsService.get("getFailLabel"));
                renderer.setJSONObject(missing);
                return;
            }
            result.put(org.b3log.solo.model.Common.STATUS_CODE, true);

            try {
                final JSONObject cate = categoryTagRepository.getByTagId(articleId, 1, Integer.MAX_VALUE);
                final int size = org.b3log.solo.util.QueryResults.array(cate).length();
                final JSONObject cateS = (JSONObject) org.b3log.solo.util.QueryResults.array(cate).get(size - 1);
                result.put("category", cateS.optString("category_oId"));
            } catch (final JSONException | NullPointerException e) {
                result.put("category", "");
            }

            renderer.setJSONObject(result);
        } catch (final ServiceException e) {
            LOGGER.log(Level.ERROR, e.getMessage(), e);

            final JSONObject jsonObject = new JSONObject().put(org.b3log.solo.model.Common.STATUS_CODE, false);
            renderer.setJSONObject(jsonObject);
            jsonObject.put(Keys.MSG, langPropsService.get("getFailLabel"));
        } catch (final RepositoryException e) {
            LOGGER.log(Level.ERROR, e.getMessage(), e);

            final JSONObject jsonObject = new JSONObject().put(org.b3log.solo.model.Common.STATUS_CODE, false);
            renderer.setJSONObject(jsonObject);
            jsonObject.put(Keys.MSG, langPropsService.get("getFailLabel"));
        }
    }

    /**
     * Gets articles(by create date descending) by the specified request json object.
     *
     * @param context the specified request context
     */
    public void getArticles(final RequestContext context) {
        final JsonRenderer renderer = new JsonRenderer();
        context.setRenderer(renderer);
        try {
            String path = context.requestURI().substring((Latkes.getContextPath() + "/console/articles/status/").length());
            final String status = StringUtils.substringBefore(path, "/");

            path = path.substring((status + "/").length());
            final JSONObject requestJSONObject = Solos.buildPaginationRequest(path);
            requestJSONObject.put(Article.ARTICLE_STATUS, "published".equals(status) ? Article.ARTICLE_STATUS_C_PUBLISHED : Article.ARTICLE_STATUS_C_DRAFT);

            final JSONObject currentUser = Solos.getCurrentUser(context.getRequest(), context.getResponse());
            if (!Solos.isAdminLoggedIn(context) && null != currentUser) {
                requestJSONObject.put(Article.ARTICLE_AUTHOR_ID, currentUser.optString(Keys.OBJECT_ID));
            }

            final JSONArray excludes = new JSONArray();
            excludes.put(Article.ARTICLE_CONTENT);
            excludes.put(Article.ARTICLE_UPDATED);
            excludes.put(Article.ARTICLE_CREATED);
            excludes.put(Article.ARTICLE_AUTHOR_ID);
            excludes.put(Article.ARTICLE_RANDOM_DOUBLE);
            requestJSONObject.put(Keys.EXCLUDES, excludes);

            final String keyword = StringUtils.trim(context.param("k"));
            if (StringUtils.isNotBlank(keyword)) {
                requestJSONObject.put(Common.KEYWORD, keyword);
            }

            final JSONObject result = articleQueryService.getArticles(requestJSONObject);
            result.put(org.b3log.solo.model.Common.STATUS_CODE, true);
            renderer.setJSONObject(result);

            final JSONArray articles = result.optJSONArray(Article.ARTICLES);
            for (int i = 0; i < articles.length(); i++) {
                final JSONObject article = articles.optJSONObject(i);
                String title = article.optString(Article.ARTICLE_TITLE);
                title = StringEscapeUtils.escapeXml11(title);
                article.put(Article.ARTICLE_TITLE, title);
            }
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, e.getMessage(), e);

            final JSONObject jsonObject = new JSONObject().put(org.b3log.solo.model.Common.STATUS_CODE, false);
            renderer.setJSONObject(jsonObject);
            jsonObject.put(Keys.MSG, langPropsService.get("getFailLabel"));
        }
    }

    /**
     * Removes an article by the specified request.
     *
     * @param context the specified request context
     */
    public void removeArticle(final RequestContext context) {
        final JsonRenderer renderer = new JsonRenderer();
        context.setRenderer(renderer);
        final JSONObject ret = new JSONObject();
        renderer.setJSONObject(ret);
        final String articleId = context.pathVar("id");
        final JSONObject currentUser = Solos.getCurrentUser(context.getRequest(), context.getResponse());

        try {
            if (!articleQueryService.canAccessArticle(articleId, currentUser)) {
                ret.put(org.b3log.solo.model.Common.STATUS_CODE, false);
                ret.put(Keys.MSG, langPropsService.get("forbiddenLabel"));

                return;
            }

            final Transaction transaction = categoryTagRepository.beginTransaction();
            categoryTagRepository.removeByTagId(articleId);
            transaction.commit();

            articleMgmtService.removeArticle(articleId);

            ret.put(org.b3log.solo.model.Common.STATUS_CODE, true);
            ret.put(Keys.MSG, langPropsService.get("removeSuccLabel"));
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, e.getMessage(), e);

            final JSONObject jsonObject = new JSONObject();
            renderer.setJSONObject(jsonObject);
            jsonObject.put(org.b3log.solo.model.Common.STATUS_CODE, false);
            jsonObject.put(Keys.MSG, langPropsService.get("removeFailLabel"));
        }
    }

    /**
     * Cancels publish an article by the specified request.
     *
     * @param context the specified request context
     */
    public void cancelPublishArticle(final RequestContext context) {
        final JsonRenderer renderer = new JsonRenderer();
        context.setRenderer(renderer);
        final JSONObject ret = new JSONObject();
        renderer.setJSONObject(ret);

        try {
            final String articleId = context.pathVar("id");
            final JSONObject currentUser = Solos.getCurrentUser(context.getRequest(), context.getResponse());
            if (!articleQueryService.canAccessArticle(articleId, currentUser)) {
                ret.put(org.b3log.solo.model.Common.STATUS_CODE, false);
                ret.put(Keys.MSG, langPropsService.get("forbiddenLabel"));

                return;
            }

            articleMgmtService.cancelPublishArticle(articleId);

            ret.put(org.b3log.solo.model.Common.STATUS_CODE, true);
            ret.put(Keys.MSG, langPropsService.get("unPulbishSuccLabel"));
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, e.getMessage(), e);

            final JSONObject jsonObject = new JSONObject();
            renderer.setJSONObject(jsonObject);
            jsonObject.put(org.b3log.solo.model.Common.STATUS_CODE, false);
            jsonObject.put(Keys.MSG, langPropsService.get("unPulbishFailLabel"));
        }
    }

    /**
     * Cancels a top article by the specified request.
     *
     * @param context the specified request context
     */
    public void cancelTopArticle(final RequestContext context) {
        final JsonRenderer renderer = new JsonRenderer();
        context.setRenderer(renderer);
        final JSONObject ret = new JSONObject();
        renderer.setJSONObject(ret);
        if (!Solos.isAdminLoggedIn(context)) {
            ret.put(Keys.MSG, langPropsService.get("forbiddenLabel"));
            ret.put(org.b3log.solo.model.Common.STATUS_CODE, false);

            return;
        }

        try {
            final String articleId = context.pathVar("id");
            articleMgmtService.topArticle(articleId, false);
            ret.put(org.b3log.solo.model.Common.STATUS_CODE, true);
            ret.put(Keys.MSG, langPropsService.get("cancelTopSuccLabel"));
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, e.getMessage(), e);

            final JSONObject jsonObject = new JSONObject();
            renderer.setJSONObject(jsonObject);
            jsonObject.put(org.b3log.solo.model.Common.STATUS_CODE, false);
            jsonObject.put(Keys.MSG, langPropsService.get("cancelTopFailLabel"));
        }
    }

    /**
     * Puts an article to top by the specified request.
     *
     * @param context the specified request context
     */
    public void putTopArticle(final RequestContext context) {
        final JsonRenderer renderer = new JsonRenderer();
        context.setRenderer(renderer);
        final JSONObject ret = new JSONObject();
        renderer.setJSONObject(ret);
        if (!Solos.isAdminLoggedIn(context)) {
            ret.put(Keys.MSG, langPropsService.get("forbiddenLabel"));
            ret.put(org.b3log.solo.model.Common.STATUS_CODE, false);

            return;
        }

        try {
            final String articleId = context.pathVar("id");
            articleMgmtService.topArticle(articleId, true);
            ret.put(org.b3log.solo.model.Common.STATUS_CODE, true);
            ret.put(Keys.MSG, langPropsService.get("putTopSuccLabel"));
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, e.getMessage(), e);

            final JSONObject jsonObject = new JSONObject();
            renderer.setJSONObject(jsonObject);
            jsonObject.put(org.b3log.solo.model.Common.STATUS_CODE, false);
            jsonObject.put(Keys.MSG, langPropsService.get("putTopFailLabel"));
        }
    }

    /**
     * Updates an article by the specified request json object.
     *
     * @param context the specified request context
     */
    public void updateArticle(final RequestContext context) {
        final JsonRenderer renderer = new JsonRenderer();
        context.setRenderer(renderer);
        final JSONObject ret = new JSONObject();
        try {
            final JSONObject requestJSONObject = context.requestJSON();
            final JSONObject article = requestJSONObject.getJSONObject(Article.ARTICLE);
            final String articleId = article.getString(Keys.OBJECT_ID);
            renderer.setJSONObject(ret);

            final JSONObject currentUser = Solos.getCurrentUser(context.getRequest(), context.getResponse());
            if (!articleQueryService.canAccessArticle(articleId, currentUser)) {
                ret.put(Keys.MSG, langPropsService.get("forbiddenLabel"));
                ret.put(org.b3log.solo.model.Common.STATUS_CODE, false);

                return;
            }
            articleMgmtService.updateArticle(requestJSONObject);
            LOGGER.log(Level.INFO, "Article updated [id={0}]", requestJSONObject.getJSONObject(Article.ARTICLE).optString(Keys.OBJECT_ID));

            ret.put(Keys.MSG, langPropsService.get("updateSuccLabel"));
            ret.put(org.b3log.solo.model.Common.STATUS_CODE, true);
        } catch (final ServiceException e) {
            final JSONObject jsonObject = new JSONObject().put(org.b3log.solo.model.Common.STATUS_CODE, false);
            renderer.setJSONObject(jsonObject);
            jsonObject.put(Keys.MSG, e.getMessage());
        }
    }

    /**
     * Adds an article with the specified request.
     *
     * @param context the specified request context
     */
    public void addArticle(final RequestContext context) {
        final JsonRenderer renderer = new JsonRenderer();
        context.setRenderer(renderer);
        final JSONObject ret = new JSONObject();
        try {
            final JSONObject requestJSONObject = context.requestJSON();
            final JSONObject currentUser = Solos.getCurrentUser(context.getRequest(), context.getResponse());
            final JSONObject article = requestJSONObject.getJSONObject(Article.ARTICLE);
            article.put(Article.ARTICLE_AUTHOR_ID, currentUser.getString(Keys.OBJECT_ID));

            final String articleId = articleMgmtService.addArticle(requestJSONObject);
            LOGGER.log(Level.INFO, "Article created [id={0}]", articleId);
            ret.put(Keys.OBJECT_ID, articleId);
            ret.put(Keys.MSG, langPropsService.get("addSuccLabel"));
            ret.put(org.b3log.solo.model.Common.STATUS_CODE, true);

            renderer.setJSONObject(ret);
        } catch (final ServiceException e) {
            final JSONObject jsonObject = new JSONObject().put(org.b3log.solo.model.Common.STATUS_CODE, false);
            renderer.setJSONObject(jsonObject);
            jsonObject.put(Keys.MSG, e.getMessage());
        }
    }
}
