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

import org.b3log.solo.util.AdminPaths;

import org.apache.commons.lang3.StringUtils;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.ioc.BeanManager;
import org.apache.logging.log4j.Level;
import org.b3log.solo.logging.Logger;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.http.function.Handler;
import org.b3log.solo.bolo.waf.WAF;
import org.b3log.solo.service.InitService;
import org.b3log.solo.service.UpgradeService;

import org.b3log.latke.http.Response;

/**
 * Checks initialization handler.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 3.2.0
 */
public class InitCheckHandler implements Handler {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(InitCheckHandler.class);

    /**
     * Whether initialization info reported.
     */
    private static boolean initReported;

    protected boolean blockedAddress(final String address) throws Exception {
        final BeanManager beans = BeanManager.getInstance();
        return beans.getReference(org.b3log.solo.service.GeoAccessService.class).blocked(address);
    }

    protected boolean inspectRisk(final RequestContext context) throws Exception {
        return BeanManager.getInstance().getReference(org.b3log.solo.service.RiskControlService.class).inspect(context);
    }

    @Override
    public void handle(final RequestContext context) {
        final String requestURI = context.requestURI();
        final String localURI = StringUtils.removeStart(requestURI, Latkes.getContextPath());
        try {
            if (inspectRisk(context)) {
                context.setHeader("Cache-Control", "no-store");
                context.sendError(403); context.abort(); return;
            }
        } catch (Exception unavailable) {
            context.sendError(503); context.abort(); return;
        }
        if (AdminPaths.legacyEntry(localURI)) {
            org.b3log.solo.service.RiskControlService.recordFailure(context, 404);
            context.setHeader("Cache-Control", "no-store");
            context.sendError(404);
            context.abort();
            return;
        }
        if (localURI.equals(AdminPaths.login())) {
            context.attr(Keys.HttpRequest.REQUEST_URI, Latkes.getContextPath() + AdminPaths.loginRoute());
        }
        final boolean isSpiderBot = Boolean.TRUE.equals(context.attr(Keys.HttpRequest.IS_SEARCH_ENGINE_BOT));
        LOGGER.log(Level.TRACE, "Request [URI={0}]", requestURI);

        final String requestIP = org.b3log.solo.util.LoginThrottle.clientAddress(context.getRequest());
        try {
            if (blockedAddress(requestIP)) {
                context.setHeader("Cache-Control", "no-store");
                context.sendError(403);
                context.abort();
                return;
            }
        } catch (Exception unavailable) {
            context.setHeader("Cache-Control", "no-store");
            context.sendError(503);
            context.abort();
            return;
        }

        // Bolo WAF
        if (!WAF.in(requestIP, requestURI)) {
            // Rate limits are temporary. Never let browsers cache a blocked response as a gone resource.
            context.setHeader("Cache-Control", "no-store");
            context.sendError(429);
            context.abort();

            return;
        }

        // 站点统计

        // 禁止直接获取 robots.txt https://github.com/b3log/solo/issues/12543
        if (localURI.startsWith("/robots.txt") && !isSpiderBot) {
            context.sendError(403);
            context.abort();

            return;
        }

        if (org.b3log.solo.web.PublicResourceHandler.isPublicAssetUri(requestURI)) {
            context.handle();

            return;
        }

        if (StringUtils.startsWith(requestURI, Latkes.getContextPath() + "/oauth/bolo")) {
            // Do initialization
            context.handle();

            return;
        } else if (UpgradeService.boloFastMigration) {
            context.attr(Keys.HttpRequest.REQUEST_URI, Latkes.getContextPath() + AdminPaths.login());
            context.handle();
            LOGGER.log(Level.DEBUG, "Bolo Fast Migrating is enabled, so redirects to /start");

            return;
        }

        final BeanManager beanManager = BeanManager.getInstance();
        final InitService initService = beanManager.getReference(InitService.class);
        if (initService.isInited()) {
            context.handle();

            return;
        }

        if (!initReported) {
            LOGGER.log(Level.DEBUG, "Bolo has not been initialized, so redirects to /start");
            initReported = true;
        }

        context.attr(Keys.HttpRequest.REQUEST_URI, Latkes.getContextPath() + AdminPaths.login());
        context.handle();
    }
}
