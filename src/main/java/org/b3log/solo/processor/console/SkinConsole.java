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
import org.b3log.latke.Latkes;
import org.b3log.latke.ioc.Inject;
import org.apache.logging.log4j.Level;
import org.b3log.solo.logging.Logger;
import org.b3log.latke.service.LangPropsService;
import org.b3log.latke.service.ServiceException;
import org.b3log.latke.http.RequestContext;
import org.b3log.solo.web.annotation.Before;
import org.b3log.latke.ioc.Singleton;
import org.b3log.latke.http.renderer.JsonRenderer;
import org.b3log.solo.model.Common;
import org.b3log.solo.model.Option;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.service.SkinMgmtService;
import org.b3log.solo.util.Skins;
import org.json.JSONArray;
import org.json.JSONObject;

import org.b3log.latke.http.Cookie;
import org.b3log.latke.http.Response;
import java.util.Set;

/**
 * Skin console request processing.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 3.5.0
 */
@Singleton
@Before(ConsoleAdminAuthAdvice.class)
public class SkinConsole {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(SkinConsole.class);

    /**
     * Skin management service.
     */
    @Inject
    private SkinMgmtService skinMgmtService;

    @Inject
    private org.b3log.solo.service.OptionMgmtService optionMgmtService;

    @org.b3log.solo.web.annotation.RequestProcessing(value = "/console/appearance", method = org.b3log.latke.http.HttpMethod.PUT)
    public void updateAppearance(final RequestContext context) {
        context.setHeader("Cache-Control", "no-store");
        final String origin = context.header("Origin");
        final java.net.URI publicUrl = java.net.URI.create(Latkes.getServePath());
        final String expectedOrigin = publicUrl.getScheme() + "://" + publicUrl.getRawAuthority();
        if (!"XMLHttpRequest".equals(context.header("X-Requested-With"))
                || (origin != null && !expectedOrigin.equals(origin)) || "cross-site".equalsIgnoreCase(context.header("Sec-Fetch-Site"))) {
            context.setStatus(403);
            context.renderJSON(new JSONObject().put("sc", false).put("msg", "请求来源不正确。"));
            return;
        }
        final JSONObject payload = context.requestJSON();
        final String accent = payload == null ? "" : payload.optString("accent");
        if (!org.b3log.solo.util.SiteAppearance.valid(accent)) {
            context.setStatus(400);
            context.renderJSON(new JSONObject().put("sc", false).put("msg", "请选择有效的强调色。"));
            return;
        }
        try {
            optionMgmtService.addOrUpdateOption(new JSONObject().put(org.b3log.latke.Keys.OBJECT_ID, org.b3log.solo.util.SiteAppearance.OPTION)
                    .put(Option.OPTION_CATEGORY, "appearance").put(Option.OPTION_VALUE, accent));
            context.renderJSON(new JSONObject().put("sc", true).put("accent", accent).put("msg", "强调色已保存，前后台同步生效。"));
        } catch (ServiceException exception) {
            LOGGER.log(Level.ERROR, "Cannot save appearance", exception);
            context.setStatus(500);
            context.renderJSON(new JSONObject().put("sc", false).put("msg", "保存失败，请稍后再试。"));
        }
    }

    /**
     * Option query service.
     */
    @Inject
    private OptionQueryService optionQueryService;

    /**
     * Language service.
     */
    @Inject
    private LangPropsService langPropsService;

    /**
     * Gets skin.
     * <p>
     * Renders the response with a json object, for example,
     * <pre>
     * {
     *     "sc": boolean,
     *     "skin": {
     *         "skinDirName": "",
     *         "mobileSkinDirName": "",
     *         "skins": "[{
     *             "skinDirName": ""
     *         }, ....]"
     *     }
     * }
     * </pre>
     * </p>
     *
     * @param context the specified request context
     */
    public void getSkin(final RequestContext context) {
        final JsonRenderer renderer = new JsonRenderer();
        context.setRenderer(renderer);

        try {
            final JSONObject skin = optionQueryService.getSkin();
            if (null == skin) {
                renderer.setJSONObject(new JSONObject().put(org.b3log.solo.model.Common.STATUS_CODE, false));

                return;
            }

            final Set<String> skinDirNames = Skins.getSkinDirNames();
            final JSONArray skinArray = new JSONArray();
            for (final String dirName : skinDirNames) {
                final JSONObject s = new JSONObject();
                final String name = Latkes.getSkinName(dirName);
                if (null == name) {
                    LOGGER.log(Level.WARN, "The directory [{0}] does not contain any skin, ignored it", dirName);

                    continue;
                }

                s.put(Option.ID_C_SKIN_DIR_NAME, dirName);
                // 显示皮肤简介
                s.put("skinName", name);
                skinArray.put(s);
            }
            skin.put("skins", skinArray.toString());

            final JSONObject ret = new JSONObject();
            renderer.setJSONObject(ret);
            ret.put(Option.CATEGORY_C_SKIN, skin);
            ret.put(org.b3log.solo.model.Common.STATUS_CODE, true);
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, e.getMessage(), e);

            final JSONObject jsonObject = new JSONObject().put(org.b3log.solo.model.Common.STATUS_CODE, false);
            renderer.setJSONObject(jsonObject);
            jsonObject.put(Keys.MSG, langPropsService.get("getFailLabel"));
        }
    }

    /**
     * Updates the skin by the specified request.
     * <p>
     * Request json:
     * <pre>
     * {
     *     "skin": {
     *         "skinDirName": "",
     *         "mobileSkinDirName": "",
     *     }
     * }
     * </pre>
     * </p>
     *
     * @param context the specified request context
     */
    public void updateSkin(final RequestContext context) {
        final JsonRenderer renderer = new JsonRenderer();
        context.setRenderer(renderer);

        try {
            final JSONObject requestJSONObject = context.requestJSON();
            final JSONObject skin = requestJSONObject.getJSONObject(Option.CATEGORY_C_SKIN);
            final JSONObject ret = new JSONObject();
            renderer.setJSONObject(ret);

            skinMgmtService.updateSkin(skin);

            final Response response = context.getResponse();
            final Cookie skinDirNameCookie = new Cookie(Common.COOKIE_NAME_SKIN, "");
            skinDirNameCookie.setMaxAge(0); // Discard preview cookies created by older releases.
            skinDirNameCookie.setPath("/");
            response.addCookie(skinDirNameCookie);
            final Cookie mobileSkinDirNameCookie = new Cookie(Common.COOKIE_NAME_MOBILE_SKIN, "");
            mobileSkinDirNameCookie.setMaxAge(0); // Discard preview cookies created by older releases.
            mobileSkinDirNameCookie.setPath("/");
            response.addCookie(mobileSkinDirNameCookie);

            ret.put(org.b3log.solo.model.Common.STATUS_CODE, true);
            ret.put(Keys.MSG, langPropsService.get("updateSuccLabel"));
        } catch (final ServiceException e) {
            LOGGER.log(Level.ERROR, e.getMessage(), e);

            final JSONObject jsonObject = new JSONObject().put(org.b3log.solo.model.Common.STATUS_CODE, false);
            renderer.setJSONObject(jsonObject);
            jsonObject.put(Keys.MSG, langPropsService.get("updateFailLabel"));
        }
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
