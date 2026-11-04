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

import org.b3log.latke.Keys;
import org.b3log.latke.ioc.Inject;
import org.apache.logging.log4j.Level;
import org.b3log.solo.logging.Logger;
import org.b3log.latke.service.LangPropsService;
import org.b3log.latke.service.ServiceException;
import org.b3log.latke.http.RequestContext;
import org.b3log.solo.web.annotation.Before;
import org.b3log.latke.ioc.Singleton;
import org.b3log.latke.http.renderer.JsonRenderer;
import org.b3log.solo.model.Option;
import org.b3log.solo.model.Sign;
import org.b3log.solo.service.OptionMgmtService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.service.PreferenceMgmtService;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Preference console request processing.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @author <a href="https://github.com/hzchendou">hzchendou</a>
 * @since 0.4.0
 */
@Singleton
@Before(ConsoleAdminAuthAdvice.class)
public class PreferenceConsole {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(PreferenceConsole.class);

    /**
     * Preference management service.
     */
    @Inject
    private PreferenceMgmtService preferenceMgmtService;

    /**
     * Option management service.
     */
    @Inject
    private OptionMgmtService optionMgmtService;

    /**
     * Option query service.
     */
    @Inject
    private OptionQueryService optionQueryService;

    @Inject
    private org.b3log.solo.service.GitHubRepositoryService gitHubRepositoryService;

    @Inject
    private org.b3log.solo.service.ArticleMgmtService articleMgmtService;

    /**
     * Language service.
     */
    @Inject
    private LangPropsService langPropsService;

    /**
     * Gets signs.
     * <p>
     * Renders the response with a json object, for example,
     * <pre>
     * {
     *     "sc": boolean,
     *     "signs": [{
     *         "oId": "",
     *         "signHTML": ""
     *      }, ...]
     * }
     * </pre>
     * </p>
     *
     * @param context the specified request context
     */
    public void getSigns(final RequestContext context) {
        final JsonRenderer renderer = new JsonRenderer();
        context.setRenderer(renderer);

        try {
            final JSONObject preference = optionQueryService.getPreference();
            final JSONArray signs = new JSONArray();
            final JSONArray allSigns = // includes the empty sign(id=0)
                    new JSONArray(preference.getString(Option.ID_C_SIGNS));

            for (int i = 1; i < allSigns.length(); i++) { // excludes the empty sign
                signs.put(allSigns.getJSONObject(i));
            }

            final JSONObject ret = new JSONObject();
            renderer.setJSONObject(ret);
            ret.put(Sign.SIGNS, signs);
            ret.put(org.b3log.solo.model.Common.STATUS_CODE, true);
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, e.getMessage(), e);

            final JSONObject jsonObject = new JSONObject().put(org.b3log.solo.model.Common.STATUS_CODE, false);
            renderer.setJSONObject(jsonObject);
            jsonObject.put(Keys.MSG, langPropsService.get("getFailLabel"));
        }
    }

    /**
     * Gets preference.
     * <p>
     * Renders the response with a json object, for example,
     * <pre>
     * {
     *     "sc": boolean,
     *     "preference": {
     *         "mostViewArticleDisplayCount": int,
     *         "recentCommentDisplayCount": int,
     *         "mostUsedTagDisplayCount": int,
     *         "articleListDisplayCount": int,
     *         "articleListPaginationWindowSize": int,
     *         "mostCommentArticleDisplayCount": int,
     *         "relevantArticlesDisplayCount": int,
     *         "randomArticlesDisplayCount": int,
     *         "blogTitle": "",
     *         "blogSubtitle": "",
     *         "localeString": "",
     *         "timeZoneId": "",
     *         "skinDirName": "",
     *         "skins": "[{
     *             "skinDirName": ""
     *         }, ....]",
     *         "noticeBoard": "",
     *         "footerContent": "",
     *         "htmlHead": "",
     *         "metaKeywords": "",
     *         "metaDescription": "",
     *         "enableArticleUpdateHint": boolean,
     *         "signs": "[{
     *             "oId": "",
     *             "signHTML": ""
     *         }, ...]",
     *         "allowVisitDraftViaPermalink": boolean,
     *         "version": "",
     *         "articleListStyle": "", // Optional values: "titleOnly"/"titleAndContent"/"titleAndAbstract"
     *         "commentable": boolean,
     *         "feedOutputMode: "" // Optional values: "abstract"/"full"
     *         "feedOutputCnt": int,
     *         "faviconURL": "",
     *         "syncGitHub": boolean,
     *         "pullGitHub": boolean,
     *         "customVars" "", // 支持配置自定义参数 https://github.com/b3log/solo/issues/12535
     *     }
     * }
     * </pre>
     * </p>
     *
     * @param context the specified request context
     */
    public void getPreference(final RequestContext context) {
        final JsonRenderer renderer = new JsonRenderer();
        context.setRenderer(renderer);

        try {
            final JSONObject storedPreference = optionQueryService.getPreference();
            if (null == storedPreference) {
                renderer.setJSONObject(new JSONObject().put(org.b3log.solo.model.Common.STATUS_CODE, false));

                return;
            }
            final JSONObject preference = new JSONObject(storedPreference.toString());

            String footerContent = "";
            final JSONObject opt = optionQueryService.getOptionById(Option.ID_C_FOOTER_CONTENT);
            if (null != opt) {
                footerContent = opt.optString(Option.OPTION_VALUE);
            }
            preference.put(Option.ID_C_FOOTER_CONTENT, footerContent);

            final JSONObject ret = new JSONObject();
            renderer.setJSONObject(ret);
            try { org.b3log.solo.bolo.prop.SmtpSettings.read(preference).fill(preference); }
            catch (IllegalArgumentException invalidStoredSetting) { /* Keep incomplete legacy settings editable. */ }
            preference.put("smtpPasswordSet", !preference.optString(Option.ID_C_MAIL_PASSWORD).isEmpty());
            preference.put(Option.ID_C_MAIL_PASSWORD, "");
            preference.put("githubPATSet", !preference.optString(Option.ID_C_GITHUB_PAT).isEmpty());
            preference.put(Option.ID_C_GITHUB_PAT, "");
            final String storedB3Key = preference.optString(Option.ID_C_B3LOG_KEY);
            preference.put("b3logKeySet", !storedB3Key.isEmpty() && !"123456".equals(storedB3Key));
            preference.put(Option.ID_C_B3LOG_KEY, "");
            preference.put("fishKeySet", !preference.optString(Option.ID_C_FISH_KEY).isEmpty());
            preference.put(Option.ID_C_FISH_KEY, "");
            preference.put("sendKeySet", !preference.optString(Option.ID_C_SEND_KEY).isEmpty());
            preference.put(Option.ID_C_SEND_KEY, "");
            final String rawTuChuang = preference.optString(Option.ID_C_TUCHUANG_CONFIG);
            preference.put("tuChuangSecretSet", PreferenceMgmtService.hasTuChuangSecret(rawTuChuang));
            preference.put(Option.ID_C_TUCHUANG_CONFIG, PreferenceMgmtService.redactTuChuangConfig(rawTuChuang));
            ret.put(Option.CATEGORY_C_PREFERENCE, preference);
            ret.put(org.b3log.solo.model.Common.STATUS_CODE, true);
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, e.getMessage(), e);

            final JSONObject jsonObject = new JSONObject().put(org.b3log.solo.model.Common.STATUS_CODE, false);
            renderer.setJSONObject(jsonObject);
            jsonObject.put(Keys.MSG, langPropsService.get("getFailLabel"));
        }
    }

    /**
     * Updates the preference by the specified request.
     * <p>
     * Request json:
     * <pre>
     * {
     *     "preference": {
     *         "mostViewArticleDisplayCount": int,
     *         "recentCommentDisplayCount": int,
     *         "mostUsedTagDisplayCount": int,
     *         "articleListDisplayCount": int,
     *         "articleListPaginationWindowSize": int,
     *         "mostCommentArticleDisplayCount": int,
     *         "relevantArticlesDisplayCount": int,
     *         "randomArticlesDisplayCount": int,
     *         "blogTitle": "",
     *         "blogSubtitle": "",
     *         "localeString": "",
     *         "timeZoneId": "",
     *         "noticeBoard": "",
     *         "footerContent": "",
     *         "htmlHead": "",
     *         "metaKeywords": "",
     *         "metaDescription": "",
     *         "enableArticleUpdateHint": boolean,
     *         "signs": [{
     *             "oId": "",
     *             "signHTML": ""
     *             }, ...],
     *         "allowVisitDraftViaPermalink": boolean,
     *         "articleListStyle": "",
     *         "commentable": boolean,
     *         "feedOutputMode: "",
     *         "feedOutputCnt": int,
     *         "faviconURL": "",
     *         "syncGitHub": boolean,
     *         "pullGitHub": boolean,
     *         "customVars" "", // 支持配置自定义参数 https://github.com/b3log/solo/issues/12535
     *         "showCodeBlockLn": boolean, // 支持代码块行号显示 https://github.com/88250/solo/issues/4
     *         "footnotes": boolean, // Markdown 支持改进 https://github.com/88250/solo/issues/54
     *         "showToC": boolean, // Markdown 支持改进 https://github.com/88250/solo/issues/54
     *         "autoSpace": boolean, // Markdown 支持改进 https://github.com/88250/solo/issues/54
     *         "fixTermTypo": boolean, // Markdown 支持改进 https://github.com/88250/solo/issues/54
     *         "chinesePunct": boolean, // Markdown 支持改进 https://github.com/88250/solo/issues/54
     *         "inlineMathAllowDigitAfterOpenMarker": boolean, // Markdown 支持改进 https://github.com/88250/solo/issues/54
     *         "editorMode": "", // 支持配置编辑器模式 https://github.com/88250/solo/issues/95
     *     }
     * }
     * </pre>
     * </p>
     *
     * @param context the specified request context
     */
    public void updatePreference(final RequestContext context) {
        final JsonRenderer renderer = new JsonRenderer();
        context.setRenderer(renderer);

        try {
            final JSONObject requestJSONObject = context.requestJSON();
            final JSONObject preference = requestJSONObject.getJSONObject(Option.CATEGORY_C_PREFERENCE);
            final JSONObject ret = new JSONObject();
            renderer.setJSONObject(ret);
            if (isInvalid(preference, ret)) {
                return;
            }

            org.b3log.solo.service.CommentFilterService.validate(preference);
            JSONObject githubDisplay = null;
            if (requestJSONObject.has("githubDisplay")) {
                if (!(requestJSONObject.opt("githubDisplay") instanceof JSONObject)) throw new IllegalArgumentException("仓库显示设置不正确。");
                githubDisplay = gitHubRepositoryService.prepareDisplay(preference.optString(Option.ID_C_MY_GITHUB_ID).strip(),
                        requestJSONObject.getJSONObject("githubDisplay"));
            }
            preferenceMgmtService.updatePreference(preference);
            if (githubDisplay != null) {
                gitHubRepositoryService.saveDisplay(githubDisplay);
                if (!articleMgmtService.rebuildGitHub(preference.optString(Option.ID_C_MY_GITHUB_ID).strip())) {
                    ret.put("sc", false).put(Keys.MSG, "设置已保存，但项目文章生成失败，请在我的 GitHub 中重新同步。");
                    return;
                }
            }

            ret.put(org.b3log.solo.model.Common.STATUS_CODE, true);
            ret.put(Keys.MSG, langPropsService.get("updateSuccLabel"));
        } catch (final IllegalArgumentException invalid) {
            renderer.setJSONObject(new JSONObject().put(org.b3log.solo.model.Common.STATUS_CODE, false)
                    .put(Keys.MSG, invalid.getMessage()));
        } catch (final ServiceException e) {
            LOGGER.log(Level.ERROR, e.getMessage(), e);

            final JSONObject jsonObject = new JSONObject().put(org.b3log.solo.model.Common.STATUS_CODE, false);
            renderer.setJSONObject(jsonObject);
            jsonObject.put(Keys.MSG, langPropsService.get("updateFailLabel"));
        }
    }

    /**
     * Checks whether the specified preference is invalid and sets the specified response object.
     *
     * @param preference     the specified preference
     * @param responseObject the specified response object
     * @return {@code true} if the specified preference is invalid, returns {@code false} otherwise
     */
    private boolean isInvalid(final JSONObject preference, final JSONObject responseObject) {
        responseObject.put(org.b3log.solo.model.Common.STATUS_CODE, false);

        final StringBuilder errMsgBuilder = new StringBuilder('[' + langPropsService.get("paramSettingsLabel"));
        errMsgBuilder.append(" - ");

        String input = preference.optString(Option.ID_C_RELEVANT_ARTICLES_DISPLAY_CNT);
        if (!isNonNegativeInteger(input)) {
            errMsgBuilder.append(langPropsService.get("relevantArticlesDisplayCntLabel")).append("]  ")
                    .append(langPropsService.get("nonNegativeIntegerOnlyLabel"));
            responseObject.put(Keys.MSG, errMsgBuilder.toString());
            return true;
        }

        input = preference.optString(Option.ID_C_RANDOM_ARTICLES_DISPLAY_CNT);
        if (!isNonNegativeInteger(input)) {
            errMsgBuilder.append(langPropsService.get("randomArticlesDisplayCntLabel")).append("]  ")
                    .append(langPropsService.get("nonNegativeIntegerOnlyLabel"));
            responseObject.put(Keys.MSG, errMsgBuilder.toString());
            return true;
        }

        input = preference.optString(Option.ID_C_MOST_COMMENT_ARTICLE_DISPLAY_CNT);
        if (!isNonNegativeInteger(input)) {
            errMsgBuilder.append(langPropsService.get("indexMostCommentArticleDisplayCntLabel")).append("]  ")
                    .append(langPropsService.get("nonNegativeIntegerOnlyLabel"));
            responseObject.put(Keys.MSG, errMsgBuilder.toString());
            return true;
        }

        input = preference.optString(Option.ID_C_MOST_VIEW_ARTICLE_DISPLAY_CNT);
        if (!isNonNegativeInteger(input)) {
            errMsgBuilder.append(langPropsService.get("indexMostViewArticleDisplayCntLabel")).append("]  ")
                    .append(langPropsService.get("nonNegativeIntegerOnlyLabel"));
            responseObject.put(Keys.MSG, errMsgBuilder.toString());
            return true;
        }

        input = preference.optString(Option.ID_C_RECENT_COMMENT_DISPLAY_CNT);
        if (!isNonNegativeInteger(input)) {
            errMsgBuilder.append(langPropsService.get("indexRecentCommentDisplayCntLabel")).append("]  ")
                    .append(langPropsService.get("nonNegativeIntegerOnlyLabel"));
            responseObject.put(Keys.MSG, errMsgBuilder.toString());
            return true;
        }

        input = preference.optString(Option.ID_C_MOST_USED_TAG_DISPLAY_CNT);
        if (!isNonNegativeInteger(input)) {
            errMsgBuilder.append(langPropsService.get("indexTagDisplayCntLabel")).append("]  ")
                    .append(langPropsService.get("nonNegativeIntegerOnlyLabel"));
            responseObject.put(Keys.MSG, errMsgBuilder.toString());
            return true;
        }

        input = preference.optString(Option.ID_C_ARTICLE_LIST_DISPLAY_COUNT);
        if (!isNonNegativeInteger(input)) {
            errMsgBuilder.append(langPropsService.get("pageSizeLabel")).append("]  ")
                    .append(langPropsService.get("nonNegativeIntegerOnlyLabel"));
            responseObject.put(Keys.MSG, errMsgBuilder.toString());
            return true;
        }

        input = preference.optString(Option.ID_C_ARTICLE_LIST_PAGINATION_WINDOW_SIZE);
        if (!isNonNegativeInteger(input)) {
            errMsgBuilder.append(langPropsService.get("windowSizeLabel")).append("]  ")
                    .append(langPropsService.get("nonNegativeIntegerOnlyLabel"));
            responseObject.put(Keys.MSG, errMsgBuilder.toString());
            return true;
        }

        input = preference.optString(Option.ID_C_FEED_OUTPUT_CNT);
        if (!isNonNegativeInteger(input)) {
            errMsgBuilder.append(langPropsService.get("feedOutputCntLabel")).append("]  ")
                    .append(langPropsService.get("nonNegativeIntegerOnlyLabel"));
            responseObject.put(Keys.MSG, errMsgBuilder.toString());
            return true;
        }

        final String faviconURL = preference.optString(Option.ID_C_FAVICON_URL);
        if (!org.b3log.solo.util.OutboundUrls.isOptionalSafeIconOrAvatar(faviconURL)) {
            responseObject.put(Keys.MSG, "网站图标地址仅支持站内路径或 http/https 地址。");
            return true;
        }

        return false;
    }

    /**
     * Checks whether the specified input is a non-negative integer.
     *
     * @param input the specified input
     * @return {@code true} if it is, returns {@code false} otherwise
     */
    private boolean isNonNegativeInteger(final String input) {
        try {
            return 0 <= Integer.valueOf(input);
        } catch (final Exception e) {
            return false;
        }
    }
}
