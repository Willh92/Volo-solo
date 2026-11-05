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
package org.b3log.solo.processor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.b3log.latke.http.Request;
import org.b3log.latke.http.Response;

import org.b3log.latke.Keys;
import org.b3log.latke.ioc.Inject;
import org.apache.logging.log4j.Level;
import org.b3log.solo.logging.Logger;
import org.b3log.latke.service.LangPropsService;
import org.b3log.latke.http.HttpMethod;
import org.b3log.latke.http.RequestContext;
import org.b3log.solo.web.annotation.RequestProcessing;
import org.b3log.latke.ioc.Singleton;
import org.b3log.latke.http.renderer.AbstractFreeMarkerRenderer;
import org.b3log.latke.util.Locales;
import org.b3log.solo.model.Option;
import org.b3log.solo.service.DataModelService;
import org.b3log.solo.service.OptionMgmtService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.service.StatisticMgmtService;
import org.b3log.solo.service.UserMgmtService;
import org.b3log.solo.util.Skins;
import org.b3log.solo.util.Solos;
import org.json.JSONObject;

import freemarker.template.Template;

/**
 * User template processor.
 *
 * <p>
 * User can add a template (for example "links.ftl") then visits the page
 * ("links.html").
 * </p>
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @author <a href="https://github.com/adlered">gakkiyomi (Bolo Commiter)</a>
 * @since 0.4.6
 */
@Singleton
public class UserTemplateProcessor {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(ArticleProcessor.class);

    /**
     * DataModelService.
     */
    @Inject
    private DataModelService dataModelService;

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
     * Option query service.
     */
    @Inject
    private OptionQueryService optionQueryService;

    /**
     * User management service.
     */
    @Inject
    private UserMgmtService userMgmtService;

    /**
     * Option management service.
     */
    @Inject
    private OptionMgmtService optionMgmtService;

    /**
     * Shows the user template page.
     *
     * @param context the specified context
     */
    @RequestProcessing(value = "/{name}.html", method = HttpMethod.GET)
    public void showPage(final RequestContext context) {
        final String requestURI = context.requestURI();
        final String templateName = context.pathVar("name") + ".ftl";
        LOGGER.log(Level.DEBUG, "Shows page [requestURI={0}, templateName={1}]", requestURI, templateName);

        final Request request = context.getRequest();
        final Response response = context.getResponse();
        final AbstractFreeMarkerRenderer renderer = new SkinRenderer(context, templateName);

        final Map<String, Object> dataModel = renderer.getDataModel();
        final Template template = Skins.getSkinTemplate(context, templateName);
        if (null == template) {
            context.sendError(404);

            return;
        }

        try {
            final Map<String, String> langs = langPropsService.getAll(Locales.getLocale(request));
            dataModel.putAll(langs);
            final JSONObject preference = optionQueryService.getPreference();
            dataModelService.fillCommon(context, dataModel, preference);
            dataModelService.fillFaviconURL(dataModel, preference);
            dataModelService.fillUsite(dataModel);
            dataModelService.fillUserTemplate(context, template, dataModel, preference);
            Skins.fillLangs(preference.optString(Option.ID_C_LOCALE_STRING),
                    (String) context.attr(Keys.TEMPLATE_DIR_NAME), dataModel);
            statisticMgmtService.incBlogViewCount(context, response);
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, e.getMessage(), e);

            context.sendError(404);
        }
    }

    /**
     * Refresh usite from hacpai.
     * 
     * @param context
     */
    @RequestProcessing(value = "/admin/usite/refresh", method = HttpMethod.POST)
    public void refreshUsite(final RequestContext context) {
        if (!org.b3log.solo.util.AdminRequests.allowWrite(context)) return;
        if (!org.b3log.solo.util.PluginUtil.b3logPluginEnabled()) {
            context.sendError(404);
            return;
        }

        userMgmtService.refreshUSite();

        org.b3log.solo.web.JsonResponses.json(context).renderCode(200);
        org.b3log.solo.web.JsonResponses.json(context).renderMsg("OK");
    }

    public static final List<String> USITE_KEYS = List.of(
            "usiteUserId", "usiteResume", "usiteWeiBo", "usiteQQMusic",
            "usiteStackOverflow", "usiteDribbble", "usiteGitHub", "usiteMedium", "usiteTwitter", "usiteQQ",
            "usiteLinkedIn", "usiteSteam", "oId", "usiteInstagram", "usiteCodePen", "usiteWYMusic",
            "usiteWeChat", "usiteZhiHu", "usiteBehance", "usiteTelegram", "usiteFacebook"
    );

    private static final java.util.Set<String> USITE_KEY_SET = java.util.Set.copyOf(USITE_KEYS);

    private static final Map<String, List<String>> USITE_PREFIXES = Map.ofEntries(
            Map.entry("usiteWeiBo", List.of("https://www.weibo.com/", "http://www.weibo.com/", "https://weibo.com/", "http://weibo.com/")),
            Map.entry("usiteQQMusic", List.of("https://y.qq.com/portal/profile.html?uin=", "http://y.qq.com/portal/profile.html?uin=")),
            Map.entry("usiteStackOverflow", List.of("https://stackoverflow.com/users/", "http://stackoverflow.com/users/")),
            Map.entry("usiteDribbble", List.of("https://dribbble.com/", "http://dribbble.com/")),
            Map.entry("usiteGitHub", List.of("https://github.com/", "http://github.com/")),
            Map.entry("usiteMedium", List.of("https://medium.com/@", "http://medium.com/@", "https://medium.com/", "http://medium.com/")),
            Map.entry("usiteTwitter", List.of("https://twitter.com/", "http://twitter.com/", "https://x.com/", "http://x.com/")),
            Map.entry("usiteLinkedIn", List.of("https://www.linkedin.com/in/", "http://www.linkedin.com/in/", "https://linkedin.com/in/", "http://linkedin.com/in/")),
            Map.entry("usiteSteam", List.of("https://steamcommunity.com/id/", "http://steamcommunity.com/id/")),
            Map.entry("usiteInstagram", List.of("https://www.instagram.com/", "http://www.instagram.com/", "https://instagram.com/", "http://instagram.com/")),
            Map.entry("usiteCodePen", List.of("https://codepen.io/", "http://codepen.io/")),
            Map.entry("usiteWYMusic", List.of("https://music.163.com/#/user/home?id=", "http://music.163.com/#/user/home?id=", "https://music.163.com/user/home?id=")),
            Map.entry("usiteZhiHu", List.of("https://www.zhihu.com/people/", "http://www.zhihu.com/people/", "https://zhihu.com/people/", "http://zhihu.com/people/")),
            Map.entry("usiteBehance", List.of("https://www.behance.net/", "http://www.behance.net/", "https://behance.net/", "http://behance.net/")),
            Map.entry("usiteTelegram", List.of("https://telegram.me/", "http://telegram.me/", "https://t.me/", "http://t.me/")),
            Map.entry("usiteFacebook", List.of("https://www.facebook.com/", "http://www.facebook.com/", "https://facebook.com/", "http://facebook.com/"))
    );

    /**
     * Normalizes and validates contact configuration fields.
     * {@code usiteResume} accepts empty, root-relative ({@code /...}), or {@code http(s)://} URLs;
     * all other fields store platform account handles/IDs (with automatic prefix stripping if a full profile URL is pasted).
     */
    public static JSONObject normalizeAndValidateUsite(final JSONObject incoming, final JSONObject stored) {
        if (incoming == null) {
            throw new IllegalArgumentException("联系方式提交内容不能为空。");
        }
        for (final String key : incoming.keySet()) {
            if (!USITE_KEY_SET.contains(key)) {
                throw new IllegalArgumentException("包含未知的联系方式字段。");
            }
        }
        final JSONObject normalized = new JSONObject();
        for (final String key : USITE_KEYS) {
            String val = incoming.has(key)
                    ? incoming.optString(key, "").trim()
                    : (stored != null ? stored.optString(key, "").trim() : "");
            if ("usiteResume".equals(key)) {
                if (!org.b3log.solo.util.OutboundUrls.isOptionalSafeIconOrAvatar(val)) {
                    throw new IllegalArgumentException("个人简历地址需填写以 / 开头的站内路径或 http(s):// 网址。");
                }
                normalized.put(key, val);
                continue;
            }
            if (val.isEmpty()) {
                normalized.put(key, "");
                continue;
            }
            final List<String> prefixes = USITE_PREFIXES.get(key);
            if (prefixes != null) {
                final String lowerVal = val.toLowerCase(java.util.Locale.ROOT);
                for (final String prefix : prefixes) {
                    if (lowerVal.startsWith(prefix)) {
                        val = val.substring(prefix.length()).trim();
                        break;
                    }
                }
                while (val.endsWith("/")) {
                    val = val.substring(0, val.length() - 1).trim();
                }
            }
            if (java.util.Set.of("usiteMedium", "usiteTwitter", "usiteInstagram", "usiteTelegram").contains(key)
                    && val.startsWith("@")) {
                val = val.substring(1).trim();
            }
            if (val.isEmpty()) {
                normalized.put(key, "");
                continue;
            }
            final String lower = val.toLowerCase(java.util.Locale.ROOT);
            if (val.contains("://") || lower.startsWith("javascript:") || lower.startsWith("data:") || lower.startsWith("vbscript:")) {
                throw new IllegalArgumentException("请直接填写对应平台的账号标识（无需包含其他网址协议），或清空不填。");
            }
            final boolean allowSpace = "usiteWeChat".equals(key);
            if (val.length() > 200 || val.chars().anyMatch(ch ->
                    Character.isISOControl(ch)
                            || (!allowSpace && Character.isWhitespace(ch))
                            || ch == '<' || ch == '>' || ch == '"' || ch == '\'' || ch == '\\' || ch == '`')) {
                throw new IllegalArgumentException("社交账号格式不合法，请填写对应平台的账号标识（勿包含引号、尖括号或空白字符）。");
            }
            normalized.put(key, val);
        }
        return normalized;
    }

    @RequestProcessing(value = "/admin/usite/set", method = HttpMethod.POST)
    public void setUsite(final RequestContext context) {
        if (!org.b3log.solo.util.AdminRequests.allowWrite(context)) return;

        JSONObject usiteOpt = optionQueryService.getOptionById(Option.ID_C_USITE);
        JSONObject storedObj = null;
        if (null == usiteOpt) {
            usiteOpt = new JSONObject();
            usiteOpt.put(Keys.OBJECT_ID, Option.ID_C_USITE);
            usiteOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_HACPAI);
        } else {
            final String rawStored = usiteOpt.optString(Option.OPTION_VALUE);
            if (rawStored != null && !rawStored.isBlank()) {
                try {
                    storedObj = new JSONObject(rawStored);
                } catch (final Exception ignored) {
                    storedObj = null;
                }
            }
        }

        final JSONObject normalized;
        try {
            normalized = normalizeAndValidateUsite(context.requestJSON(), storedObj);
        } catch (final IllegalArgumentException invalid) {
            org.b3log.solo.web.JsonResponses.json(context).renderCode(400).renderMsg(invalid.getMessage());
            return;
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Updates usite option failed", e);
            org.b3log.solo.web.JsonResponses.json(context).renderCode(400).renderMsg("联系方式提交格式不正确。");
            return;
        }

        usiteOpt.put(Option.OPTION_VALUE, normalized.toString());
        try {
            optionMgmtService.addOrUpdateOption(usiteOpt);
            LOGGER.log(Level.INFO, "Contact settings updated");
            context.renderJSON(new JSONObject()
                    .put(org.b3log.solo.model.Common.STATUS_CODE, true)
                    .put("code", 200)
                    .put("msg", "联系方式已保存。")
                    .put("data", normalized));
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Updates usite option failed", e);
            org.b3log.solo.web.JsonResponses.json(context).renderCode(500).renderMsg("保存联系方式失败，请稍后重试。");
        }
    }

    @RequestProcessing(value = "/admin/usite/get", method = HttpMethod.GET)
    public void getUsite(final RequestContext context) {
        context.setHeader("Cache-Control", "no-store");
        if (!Solos.isAdminLoggedIn(context)) {
            context.sendError(401);

            return;
        }

        try {
            JSONObject usiteOpt = optionQueryService.getOptionById(Option.ID_C_USITE);
            String usite = usiteOpt == null ? "" : usiteOpt.optString(Option.OPTION_VALUE);
            org.b3log.solo.web.JsonResponses.json(context).renderCode(200);
            org.b3log.solo.web.JsonResponses.json(context).renderMsg(usite);
        } catch (Exception e) {
            org.b3log.solo.web.JsonResponses.json(context).renderCode(500);
            org.b3log.solo.web.JsonResponses.json(context).renderMsg("");
        }
    }
}
