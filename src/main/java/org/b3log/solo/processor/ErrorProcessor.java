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

import org.apache.commons.lang3.StringUtils;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
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
import org.b3log.solo.model.Common;
import org.b3log.solo.service.DataModelService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.service.UserQueryService;
import org.b3log.solo.util.Solos;
import org.json.JSONObject;

import org.b3log.latke.http.Request;
import org.b3log.latke.http.Response;
import java.util.Map;

/**
 * Error processor.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 0.4.5
 */
@Singleton
public class ErrorProcessor {

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
     * Option query service.
     */
    @Inject
    private OptionQueryService optionQueryService;

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
     * Handles the error.
     *
     * @param context the specified context
     * @throws Exception exception
     */
    @RequestProcessing(value = "/error/{statusCode}", method = {HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE})
    public void showErrorPage(final RequestContext context) {
        final String requestedStatus = context.pathVar("statusCode");
        if (requestedStatus != null && requestedStatus.matches("[0-9]{3}")) org.b3log.solo.service.RiskControlService.recordFailure(context, Integer.parseInt(requestedStatus));
        final String statusCode = requestedStatus != null && java.util.Set.of("401", "403", "404", "410", "429", "500").contains(requestedStatus)
                ? requestedStatus : "404";
        org.b3log.solo.service.RiskControlService.recordFailure(context, Integer.parseInt(statusCode));
        context.setStatus(Integer.parseInt(statusCode));
        context.setHeader("Cache-Control", "no-store");
        if (StringUtils.equals("GET", context.method())) {
            final String requestURI = context.requestURI();
            final String templateName = statusCode + ".ftl";
            LOGGER.log(Level.TRACE, "Shows error page [requestURI={0}, templateName={1}]", requestURI, templateName);

            final AbstractFreeMarkerRenderer renderer = new SkinRenderer(context, "error/" + templateName);
            final Map<String, Object> dataModel = renderer.getDataModel();
            try {
                final Map<String, String> langs = langPropsService.getAll(Latkes.getLocale());
                dataModel.putAll(langs);
                final JSONObject preference = optionQueryService.getPreference();
                dataModelService.fillCommon(context, dataModel, preference);
                dataModelService.fillFaviconURL(dataModel, preference);
                dataModelService.fillUsite(dataModel);
                final String msg = (String) context.attr(Keys.MSG);
                dataModel.put(Keys.MSG, msg);
                dataModel.put(Common.LOGIN_URL, userQueryService.getLoginURL(Common.ADMIN_INDEX_URI));
            } catch (final Exception e) {
                LOGGER.log(Level.ERROR, "Shows error page failed", e);

                context.setRenderer(null);
                org.b3log.solo.service.RiskControlService.recordFailure(context, Integer.parseInt(statusCode));
        context.setStatus(Integer.parseInt(statusCode));
                context.getResponse().setContentType("text/plain; charset=UTF-8");
                context.sendBytes(("HTTP " + statusCode).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                context.abort();
            }

            Solos.addGoogleNoIndex(context);
        } else {
            org.b3log.solo.web.JsonResponses.json(context).renderMsg(statusCode);
        }
    }
}
