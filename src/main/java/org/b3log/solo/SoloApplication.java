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
package org.b3log.solo;

import java.util.Locale;

import org.b3log.latke.http.Request;

import org.apache.commons.lang3.StringUtils;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.event.EventManager;
import org.b3log.latke.ioc.BeanManager;
import org.apache.logging.log4j.Level;
import org.b3log.solo.logging.Logger;
import org.b3log.latke.plugin.PluginManager;
import org.b3log.latke.plugin.ViewLoadEventHandler;
import org.b3log.latke.repository.Transaction;
import org.b3log.solo.web.Routes;
import org.b3log.latke.util.Locales;
import org.b3log.latke.util.Requests;
import org.b3log.latke.util.Stopwatchs;
import org.b3log.latke.util.Strings;
import org.b3log.solo.bolo.prop.MailService;
import org.b3log.solo.bolo.waf.WAF;
import org.b3log.solo.event.B3ArticleSender;
import org.b3log.solo.event.B3ArticleUpdater;
import org.b3log.solo.event.DeleteArticleListener;
import org.b3log.solo.event.DeleteFollowListener;
import org.b3log.solo.event.FishPiArticleSender;
import org.b3log.solo.event.FishPiArticleUpdater;
import org.b3log.solo.event.FollowArticleRefresher;
import org.b3log.solo.event.PluginRefresher;
import org.b3log.solo.model.Option;
import org.b3log.solo.processor.InitCheckHandler;
import org.b3log.solo.processor.PermalinkHandler;
import org.b3log.solo.processor.console.AdminConsole;
import org.b3log.solo.processor.console.ArticleConsole;
import org.b3log.solo.processor.console.CategoryConsole;
import org.b3log.solo.processor.console.CommentConsole;
import org.b3log.solo.processor.console.FollowConsole;
import org.b3log.solo.processor.console.LinkConsole;
import org.b3log.solo.processor.console.OtherConsole;
import org.b3log.solo.processor.console.PageConsole;
import org.b3log.solo.processor.console.PluginConsole;
import org.b3log.solo.processor.console.PreferenceConsole;
import org.b3log.solo.processor.console.RepairConsole;
import org.b3log.solo.processor.console.SkinConsole;
import org.b3log.solo.processor.console.TagConsole;
import org.b3log.solo.processor.console.UserConsole;
import org.b3log.solo.repository.OptionRepository;
import org.b3log.solo.service.CronMgmtService;
import org.b3log.solo.service.ImportService;
import org.b3log.solo.service.InitService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.service.SkinMgmtService;
import org.b3log.solo.service.StatisticMgmtService;
import org.b3log.solo.service.UpgradeService;
import org.b3log.solo.util.Markdowns;
import org.b3log.solo.util.Skins;
import org.b3log.solo.util.Solos;
import org.json.JSONException;
import org.json.JSONObject;

import eu.bitwalker.useragentutils.BrowserType;
import eu.bitwalker.useragentutils.UserAgent;

/**
 * Solo application lifecycle and native Latke request hooks.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @author <a href="http://vanessa.b3log.org">Vanessa</a>
 * @since 0.3.1
 */
public final class SoloApplication {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(SoloApplication.class);

    /**
     * Volo release version.
     */
    public static final String BOLO_VERSION_SOURCE = "3.0.0";
    public static final String BOLO_VERSION = "v" + BOLO_VERSION_SOURCE;
    public static final String BOLO_VERSION_EN = BOLO_VERSION;
    /**
     * Internal database migration version, independent of the public Volo release.
     */
    public static final String DATABASE_VERSION = "2026.11.05";

    /** Public application version, never used to decide database migrations. */
    public static final String VERSION = BOLO_VERSION_SOURCE;

    /**
     * Bean manager.
     */
    private BeanManager beanManager;
    private static final int MAX_SESSIONS = 4096;
    private static final java.util.concurrent.ConcurrentLinkedQueue<String> SESSION_ORDER =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.Set<String> SESSION_TRACKED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private record UaFlags(boolean searchEngineBot, boolean mobileBot) { }
    private static final java.util.Map<String, UaFlags> UA_CACHE =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, UaFlags>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(final java.util.Map.Entry<String, UaFlags> eldest) {
                    return size() > 512;
                }
            });
    private static final org.b3log.solo.web.WebResources WEB_RESOURCES = new org.b3log.solo.web.WebResources(
            java.nio.file.Path.of(System.getProperty("bolo.webRoot", java.nio.file.Files.isDirectory(java.nio.file.Path.of("src/main/webapp")) ? "src/main/webapp" : ".")));
    public static org.b3log.solo.web.WebResources getWebResources() { return WEB_RESOURCES; }

    public void initialize() {
        Latkes.setScanPath("org.b3log.solo");
        Latkes.init();
        org.b3log.latke.http.Dispatcher.startRequestHandler = context -> {
            final Request req = context.getRequest();
            final org.b3log.latke.http.Session session = req.getSession();
            final boolean hadIncomingSession = session != null
                    && hasIncomingSessionCookie(req.getHeader("Cookie"), session.getId());
            req.setAttribute("bolo.hadIncomingSession", hadIncomingSession);
            // Incoming browser cookies have no Path/expiry/SameSite/Secure attributes. Never echo them back;
            // handlers that mint or update cookies attach explicit properly attributed Cookie instances.
            context.getResponse().getCookies().clear();
            requestInitialized(req);
        };
        org.b3log.latke.http.Dispatcher.endRequestHandler = context -> {
            final Request req = context.getRequest();
            evictTransientSession(req);
            requestDestroyed(req);
        };
        Routes.HANDLERS.removeIf(handler -> handler instanceof org.b3log.latke.http.handler.StaticResourceHandler);
        Routes.HANDLERS.add(0, new PermalinkHandler());
        Routes.HANDLERS.add(0, new org.b3log.solo.web.PublicResourceHandler());
        Routes.HANDLERS.add(0, new InitCheckHandler());

        beanManager = BeanManager.getInstance();
        routeConsoleProcessors();
        Routes.mapping();
        org.b3log.latke.http.Dispatcher.error("/error/{statusCode}", beanManager.getReference(org.b3log.solo.processor.ErrorProcessor.class)::showErrorPage);
        Stopwatchs.start("Context Initialized");

        validateSkin();

        final InitService initService = beanManager.getReference(InitService.class);
        initService.initTables();

        if (initService.isInited()) {
            // Upgrade check https://github.com/b3log/solo/issues/12040
            final UpgradeService upgradeService = beanManager.getReference(UpgradeService.class);
            upgradeService.upgrade();

            // Import check https://github.com/b3log/solo/issues/12293
            final ImportService importService = beanManager.getReference(ImportService.class);
            importService.importMarkdowns();

            final OptionRepository optionRepository = beanManager.getReference(OptionRepository.class);
            final Transaction transaction = optionRepository.beginTransaction();
            try {
                loadPreference();
                final JSONObject loginPath = beanManager.getReference(OptionQueryService.class).getOptionById("adminLoginPath");
                org.b3log.solo.util.AdminPaths.configureLogin(loginPath == null ? null : loginPath.optString(Option.OPTION_VALUE));

                if (transaction.isActive()) {
                    transaction.commit();
                }
            } catch (final Exception e) {
                if (transaction.isActive()) {
                    transaction.rollback();
                }
            }
        }

        registerEventHandlers();

        final PluginManager pluginManager = beanManager.getReference(PluginManager.class);
        pluginManager.load();

        String header = "Volo " + BOLO_VERSION_EN + " | Latke 3 / Netty\n" +
                "Project: https://github.com/Willh92/Volo-solo\n" +
                "Forked from Bolo: https://github.com/bolo-blog/bolo-solo (based on Solo)\n" +
                "Upstream contributors: adlered, expoli, zeekling, csfwff, teahouse, Gakkiyomi\n" +
                "Public URL: " + Latkes.getServePath() + "\n" +
                "JVM Memory: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024
                + "MB / " + Runtime.getRuntime().maxMemory() / 1024 / 1024 + "MB";
        System.out.println("");
        String[] lines = header.split("\n");
        for (String line : lines) {
            System.out.println(line);
        }
        System.out.println("");

        Stopwatchs.end();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.log(Level.DEBUG, "Stopwatch: {0}{1}", Strings.LINE_SEPARATOR, Stopwatchs.getTimingStat());
        }

        final CronMgmtService cronMgmtService = beanManager.getReference(CronMgmtService.class);
        cronMgmtService.start();

        MailService.loadMailSettings();
        WAF.set();
        if (initService.isInited()) beanManager.getReference(org.b3log.solo.service.CommentFilterService.class).reload();

        try {
            final OptionQueryService optionQueryService = beanManager.getReference(OptionQueryService.class);
            final JSONObject preference = optionQueryService.getPreference();
            final String localeString = preference.getString(Option.ID_C_LOCALE_STRING);
            Latkes.setLocale(new Locale(Locales.getLanguage(localeString), Locales.getCountry(localeString)));
        } catch (JSONException ignored) {
        }
    }
    public void shutdown() {
        final CronMgmtService cronMgmtService = beanManager.getReference(CronMgmtService.class);
        cronMgmtService.stop();
        try {
            if (null != beanManager) {
                try {
                    final var pk = beanManager.getReference(org.b3log.solo.service.PasskeyService.class);
                    if (null != pk) {
                        pk.flushIfDirty();
                    }
                } catch (final Throwable ignored) {
                }
                final org.b3log.solo.service.AnalyticsTrendService analyticsTrendService =
                        beanManager.getReference(org.b3log.solo.service.AnalyticsTrendService.class);
                if (null != analyticsTrendService) {
                    analyticsTrendService.flushIfDirty();
                }
                final StatisticMgmtService statisticMgmtService =
                        beanManager.getReference(StatisticMgmtService.class);
                if (null != statisticMgmtService) {
                    statisticMgmtService.flushPendingViewCounts();
                }
                final org.b3log.solo.service.ArticleMgmtService articleMgmtService =
                        beanManager.getReference(org.b3log.solo.service.ArticleMgmtService.class);
                if (null != articleMgmtService) {
                    articleMgmtService.flushPendingViewCounts();
                }
            }
        } catch (final Exception e) {
            LOGGER.log(Level.WARN, "Flushes analytics trends on shutdown failed", e);
        } finally {
            try {
                org.b3log.latke.repository.jdbc.JdbcRepository.dispose();
            } catch (final Exception ignored) {
            }
        }
        org.b3log.solo.service.ImportService.shutdown();
        org.b3log.solo.service.RemoteBackupSyncService.shutdown();
        org.b3log.solo.service.IndexNowService.shutdown();
        org.b3log.solo.service.WebhookNotifyService.shutdown();
        org.b3log.solo.service.LinkHealthService.shutdown();
        org.b3log.solo.bolo.prop.MailProcessor.shutdown();
        Markdowns.shutdown();
        org.b3log.solo.event.BackgroundEvents.shutdown();
        org.b3log.solo.improve.SharedExecutorPool.shutdown();
        org.b3log.solo.util.IpGeo.close();
        org.b3log.solo.log4j.RamAppender.close();
        Latkes.shutdown();

        LOGGER.info("Destroyed the context");
    }
    public static boolean hasIncomingSessionCookie(final String rawCookieHeader, final String sessionId) {
        if (rawCookieHeader == null || rawCookieHeader.isEmpty() || sessionId == null || sessionId.isEmpty()) {
            return false;
        }
        final String targetName = org.b3log.latke.http.Session.LATKE_SESSION_ID;
        final int targetNameLen = targetName.length();
        final int sessionIdLen = sessionId.length();
        final int headerLen = rawCookieHeader.length();
        int idx = 0;
        while (idx < headerLen) {
            int semi = rawCookieHeader.indexOf(';', idx);
            if (semi < 0) {
                semi = headerLen;
            }
            int start = idx;
            int end = semi;
            while (start < end && rawCookieHeader.charAt(start) <= ' ') {
                start++;
            }
            while (end > start && rawCookieHeader.charAt(end - 1) <= ' ') {
                end--;
            }
            if (end > start) {
                final int eq = rawCookieHeader.indexOf('=', start);
                if (eq > start && eq < end) {
                    int nameEnd = eq;
                    while (nameEnd > start && rawCookieHeader.charAt(nameEnd - 1) <= ' ') {
                        nameEnd--;
                    }
                    if (nameEnd - start == targetNameLen
                            && rawCookieHeader.regionMatches(start, targetName, 0, targetNameLen)) {
                        int valStart = eq + 1;
                        int valEnd = end;
                        while (valStart < valEnd && rawCookieHeader.charAt(valStart) <= ' ') {
                            valStart++;
                        }
                        if (valEnd - valStart >= 2
                                && rawCookieHeader.charAt(valStart) == '"'
                                && rawCookieHeader.charAt(valEnd - 1) == '"') {
                            valStart++;
                            valEnd--;
                        }
                        if (valEnd - valStart == sessionIdLen
                                && rawCookieHeader.regionMatches(valStart, sessionId, 0, sessionIdLen)) {
                            return true;
                        }
                    }
                }
            }
            idx = semi + 1;
        }
        return false;
    }

    public static boolean hasSessionAttributes(final String sessionId) {
        return hasSessionAttributes(sessionId, null);
    }

    private static boolean hasSessionAttributes(final String sessionId, final org.b3log.latke.http.Session session) {
        if (sessionId != null && !sessionId.isEmpty()) {
            final JSONObject data = org.b3log.latke.http.Sessions.CACHE.get(sessionId);
            if (data != null && data.length() > 1) {
                for (final String key : data.keySet()) {
                    if ("id".equals(key) || data.isNull(key)) {
                        continue;
                    }
                    final Object rawVal = data.opt(key);
                    final String strVal = rawVal == null ? "" : String.valueOf(rawVal);
                    if (!strVal.isBlank() && !"{}".equals(strVal)) {
                        return true;
                    }
                }
            }
        }
        if (session != null) {
            final String viewPwds = session.getAttribute(org.b3log.solo.model.Common.ARTICLES_VIEW_PWD);
            return viewPwds != null && !viewPwds.isBlank() && !"{}".equals(viewPwds);
        }
        return false;
    }

    public static void evictTransientSession(final Request request) {
        if (request == null) return;
        final org.b3log.latke.http.Session session = request.getSession();
        if (session != null) {
            final String sessionId = session.getId();
            final boolean hadIncoming = Boolean.TRUE.equals(request.getAttribute("bolo.hadIncomingSession"));
            if (!hadIncoming) {
                if (!hasSessionAttributes(sessionId, session)) {
                    org.b3log.latke.http.Sessions.CACHE.remove(sessionId);
                    SESSION_TRACKED.remove(sessionId);
                } else if (org.b3log.latke.http.Sessions.CACHE.contains(sessionId)) {
                    if (SESSION_TRACKED.add(sessionId)) {
                        SESSION_ORDER.offer(sessionId);
                    }
                } else {
                    SESSION_TRACKED.remove(sessionId);
                }
            } else if (org.b3log.latke.http.Sessions.CACHE.contains(sessionId)) {
                if (SESSION_TRACKED.add(sessionId)) {
                    SESSION_ORDER.offer(sessionId);
                }
            } else {
                SESSION_TRACKED.remove(sessionId);
            }
        }
        while (org.b3log.latke.http.Sessions.CACHE.size() > MAX_SESSIONS) {
            final String oldest = SESSION_ORDER.poll();
            if (oldest != null) {
                SESSION_TRACKED.remove(oldest);
                org.b3log.latke.http.Sessions.CACHE.remove(oldest);
            } else {
                SESSION_TRACKED.clear();
                org.b3log.latke.http.Sessions.CACHE.clear();
                break;
            }
        }
    }
    public void requestInitialized(final Request httpServletRequest) {
        httpServletRequest.setAttribute(Keys.HttpRequest.START_TIME_MILLIS, System.currentTimeMillis());
        if (LOGGER.isDebugEnabled()) {
            Requests.log(httpServletRequest, Level.DEBUG, LOGGER.unwrap());
        }

        final String requestURI = httpServletRequest.getRequestURI();
        Stopwatchs.start("Request Initialized [requestURI=" + requestURI + "]");
        if (org.b3log.solo.web.PublicResourceHandler.isPublicAssetUri(requestURI)) {
            return;
        }
        fillBotAttrs(httpServletRequest);
        if (!Solos.isBot(httpServletRequest)) {
            final StatisticMgmtService statisticMgmtService = beanManager.getReference(StatisticMgmtService.class);
            statisticMgmtService.onlineVisitorCount(httpServletRequest);
        }

        resolveSkinDir(httpServletRequest);
    }
    public void requestDestroyed(final Request request) {
        Stopwatchs.end();

        if (LOGGER.isDebugEnabled()) {
            LOGGER.log(Level.DEBUG, "Stopwatch: {0}{1}", Strings.LINE_SEPARATOR, Stopwatchs.getTimingStat());
        }
        Stopwatchs.release();
    }

    /**
     * Loads skin.
     * <p>
     * Loads skin from repository, loads skins from skin directory then sets it into
     * preference if the skins changed.
     * </p>
     */
    private void loadPreference() {
        Stopwatchs.start("Load Preference");

        LOGGER.debug("Loading preference....");

        final OptionQueryService optionQueryService = beanManager.getReference(OptionQueryService.class);
        JSONObject skin;
        try {
            skin = optionQueryService.getSkin();
            if (null == skin) {
                return;
            }

            final SkinMgmtService skinMgmtService = beanManager.getReference(SkinMgmtService.class);
            skinMgmtService.loadSkins(skin);

            final JSONObject preference = optionQueryService.getPreference();
            if (null == preference) {
                return;
            }

            final String showClodeBlockLn = preference.optString(org.b3log.solo.model.Option.ID_C_SHOW_CODE_BLOCK_LN);
            Markdowns.SHOW_CODE_BLOCK_LN = StringUtils.equalsIgnoreCase(showClodeBlockLn, "true");
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, e.getMessage(), e);

            System.exit(-1);
        }

        Stopwatchs.end();
    }

    /**
     * Register event handlers.
     */
    private void registerEventHandlers() {
        Stopwatchs.start("Register Event Handlers");
        LOGGER.debug("Registering event handlers....");

        try {
            final EventManager eventManager = beanManager.getReference(EventManager.class);
            final PluginRefresher pluginRefresher = beanManager.getReference(PluginRefresher.class);
            eventManager.registerListener(pluginRefresher);
            eventManager.registerListener(new ViewLoadEventHandler());
            final B3ArticleSender articleSender = beanManager.getReference(B3ArticleSender.class);
            eventManager.registerListener(articleSender);
            final B3ArticleUpdater articleUpdater = beanManager.getReference(B3ArticleUpdater.class);
            eventManager.registerListener(articleUpdater);
            final FishPiArticleSender fishPiArticleSender = beanManager.getReference(FishPiArticleSender.class);
            eventManager.registerListener(fishPiArticleSender);
            final FishPiArticleUpdater fishPiArticleUpdater = beanManager.getReference(FishPiArticleUpdater.class);
            eventManager.registerListener(fishPiArticleUpdater);
            final DeleteArticleListener deleteArticleListener = beanManager.getReference(DeleteArticleListener.class);
            eventManager.registerListener(deleteArticleListener);
            final FollowArticleRefresher followArticleRefresher = beanManager
                    .getReference(FollowArticleRefresher.class);
            eventManager.registerListener(followArticleRefresher);
            final DeleteFollowListener deleteFollowListener = beanManager
                    .getReference(DeleteFollowListener.class);
            eventManager.registerListener(deleteFollowListener);
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Register event handlers failed", e);

            System.exit(-1);
        }

        LOGGER.debug("Registered event handlers");
        Stopwatchs.end();
    }

    /**
     * Resolve skin (template) for the specified HTTP servlet request.
     * 前台皮肤切换 https://github.com/b3log/solo/issues/12060
     *
     * @param httpServletRequest the specified HTTP servlet request
     */
    private void resolveSkinDir(final Request httpServletRequest) {
        String skin = Skins.getSkinDirNameFromQuery(httpServletRequest);
        if (StringUtils.isBlank(skin)) {
            final OptionQueryService optionQueryService = beanManager.getReference(OptionQueryService.class);
            final JSONObject skinOpt = optionQueryService.getSkin();
            if (Solos.isMobile(httpServletRequest)) {
                if (null != skinOpt) {
                    skin = skinOpt.optString(Option.ID_C_MOBILE_SKIN_DIR_NAME);
                } else {
                    skin = Option.DefaultPreference.DEFAULT_MOBILE_SKIN_DIR_NAME;
                }
            } else {
                if (null != skinOpt) {
                    skin = skinOpt.optString(Option.ID_C_SKIN_DIR_NAME);
                } else {
                    skin = Option.DefaultPreference.DEFAULT_SKIN_DIR_NAME;
                }
            }
        }

        httpServletRequest.setAttribute(Keys.TEMPLATE_DIR_NAME, skin);
    }

    private static void fillBotAttrs(final Request request) {
        final String userAgentStr = request.getHeader("User-Agent");
        final String key = userAgentStr == null ? "" : userAgentStr;
        UaFlags flags = key.length() <= 512 ? UA_CACHE.get(key) : null;
        if (flags == null) {
            final UserAgent userAgent = UserAgent.parseUserAgentString(userAgentStr);
            BrowserType browserType = userAgent.getBrowser().getBrowserType();

            if (StringUtils.containsIgnoreCase(userAgentStr, "mobile")
                    || StringUtils.containsIgnoreCase(userAgentStr, "MQQBrowser")
                    || StringUtils.containsIgnoreCase(userAgentStr, "iphone")
                    || StringUtils.containsIgnoreCase(userAgentStr, "MicroMessenger")
                    || StringUtils.containsIgnoreCase(userAgentStr, "CFNetwork")
                    || StringUtils.containsIgnoreCase(userAgentStr, "Android")) {
                browserType = BrowserType.MOBILE_BROWSER;
            } else if (StringUtils.containsIgnoreCase(userAgentStr, "Iframely")
                    || StringUtils.containsIgnoreCase(userAgentStr, "Google")
                    || StringUtils.containsIgnoreCase(userAgentStr, "BUbiNG")
                    || StringUtils.containsIgnoreCase(userAgentStr, "ltx71")
                    || StringUtils.containsIgnoreCase(userAgentStr, "py")) {
                browserType = BrowserType.ROBOT;
            }
            flags = new UaFlags(BrowserType.ROBOT == browserType, BrowserType.MOBILE_BROWSER == browserType);
            if (key.length() <= 512) {
                UA_CACHE.put(key, flags);
            }
        }

        request.setAttribute(Keys.HttpRequest.IS_SEARCH_ENGINE_BOT, flags.searchEngineBot());
        request.setAttribute(Keys.HttpRequest.IS_MOBILE_BOT, flags.mobileBot());
    }

    /**
     * Validates the default skin.
     *
     * <p>
     * 改进皮肤加载校验 https://github.com/b3log/solo/issues/12548
     * </p>
     */
    private static void validateSkin() {
        final String skinDirName = Option.DefaultPreference.DEFAULT_SKIN_DIR_NAME;
        final String skinName = Latkes.getSkinName(skinDirName);
        if (StringUtils.isBlank(skinName)) {
            LOGGER.log(Level.ERROR, "Can't load the default skins, please make sure skin [" + skinDirName
                    + "] is under skins directory and structure correctly");

            System.exit(-1);
        }
    }

    /**
     * 后台控制器使用函数式路由. https://github.com/b3log/solo/issues/12580
     */
    public static void routeConsoleProcessors() {
        final BeanManager beanManager = BeanManager.getInstance();
        final AdminConsole adminConsole = beanManager.getReference(AdminConsole.class);
        Routes.get("/admin-index.do", adminConsole::showAdminIndex);
        Routes.get("/admin-preference.do", adminConsole::showAdminPreferenceFunction);
        Routes.get("/admin-security.do", adminConsole::showAdminAccess);
        Routes.get("/admin-access.do", adminConsole::showAdminAccess);
        Routes.get("/admin-totp.do", adminConsole::showAdminTotp);
        Routes.get("/admin-ip.do", adminConsole::showAdminIpBans);
        Routes.get("/admin-geo.do", adminConsole::showAdminGeoRules);
        Routes.get("/admin-comments.do", adminConsole::showAdminCommentProtection);
        Routes.get(new String[] { "/admin-article.do",
                "/admin-article-list.do",
                "/admin-comment-list.do",
                "/admin-link-list.do",
                "/admin-follow-list.do",
                "/admin-page-list.do",
                "/admin-others.do",
                "/admin-draft-list.do",
                "/admin-user-list.do",
                "/admin-category-list.do",
                "/admin-theme-list.do",
                "/admin-plugin-list.do",
                "/admin-main.do",
                "/admin-about.do",
                "/admin-tool-box.do",
                "/admin-book-list.do",
                "/admin-usite.do" }, adminConsole::showAdminFunctions);
        Routes.get("/console/export/sql", adminConsole::exportSQL);
        Routes.get("/console/export/json", adminConsole::exportJSON);
        Routes.get("/console/export/hexo", adminConsole::exportHexo);

        final ArticleConsole articleConsole = beanManager.getReference(ArticleConsole.class);
        Routes.post("/console/article/push2rhy", articleConsole::pushArticleToCommunity);
        Routes.get("/console/thumbs", articleConsole::getArticleThumbs);
        Routes.get("/console/article/{id}", articleConsole::getArticle);
        Routes.get("/console/articles/status/{status}/{page}/{pageSize}/{windowSize}",
                articleConsole::getArticles);
        Routes.delete("/console/article/{id}", articleConsole::removeArticle);
        Routes.put("/console/article/unpublish/{id}", articleConsole::cancelPublishArticle);
        Routes.put("/console/article/canceltop/{id}", articleConsole::cancelTopArticle);
        Routes.put("/console/article/puttop/{id}", articleConsole::putTopArticle);
        Routes.post("/console/article/{id}/share-token", articleConsole::createShareToken);
        Routes.get("/console/article/{id}/revisions", articleConsole::listArticleRevisions);
        Routes.get("/console/article/{id}/revisions/{revId}", articleConsole::getArticleRevision);
        Routes.post("/console/article/{id}/revisions/{revId}/restore", articleConsole::restoreArticleRevision);
        Routes.put("/console/article/", articleConsole::updateArticle);
        Routes.post("/console/article/", articleConsole::addArticle);

        final CategoryConsole categoryConsole = beanManager.getReference(CategoryConsole.class);
        Routes.put("/console/category/order/", categoryConsole::changeOrder);
        Routes.get("/console/category/{id}", categoryConsole::getCategory);
        Routes.delete("/console/category/{id}", categoryConsole::removeCategory);
        Routes.put("/console/category/", categoryConsole::updateCategory);
        Routes.post("/console/category/", categoryConsole::addCategory);
        Routes.get("/console/categories/{page}/{pageSize}/{windowSize}", categoryConsole::getCategories);

        final CommentConsole commentConsole = beanManager.getReference(CommentConsole.class);
        Routes.delete("/console/article/comment/{id}", commentConsole::removeArticleComment);
        Routes.put("/console/comment/{id}/moderate", commentConsole::moderateComment);
        Routes.get("/console/comments/{page}/{pageSize}/{windowSize}", commentConsole::getComments);
        Routes.get("/console/comments/article/{id}", commentConsole::getArticleComments);

        final LinkConsole linkConsole = beanManager.getReference(LinkConsole.class);
        Routes.delete("/console/link/{id}", linkConsole::removeLink);
        Routes.put("/console/link/", linkConsole::updateLink);
        Routes.put("/console/link/order/", linkConsole::changeOrder);
        Routes.post("/console/link/", linkConsole::addLink);
        Routes.post("/console/links/check-health", linkConsole::checkLinksHealth);
        Routes.get("/console/links/{page}/{pageSize}/{windowSize}", linkConsole::getLinks);
        Routes.get("/console/link/{id}", linkConsole::getLink);

        final FollowConsole followConsole = beanManager.getReference(FollowConsole.class);
        Routes.delete("/console/follow/{id}", followConsole::removeFollow);
        Routes.put("/console/follow/", followConsole::updateFollow);
        Routes.put("/console/follow/order/", followConsole::changeOrder);
        Routes.post("/console/follow/", followConsole::addFollow);
        Routes.get("/console/follows/{page}/{pageSize}/{windowSize}", followConsole::getFollows);
        Routes.get("/console/follow/{id}", followConsole::getFollow);

        final PageConsole pageConsole = beanManager.getReference(PageConsole.class);
        Routes.put("/console/page/", pageConsole::updatePage);
        Routes.delete("/console/page/{id}", pageConsole::removePage);
        Routes.post("/console/page/", pageConsole::addPage);
        Routes.put("/console/page/order/", pageConsole::changeOrder);
        Routes.get("/console/page/{id}", pageConsole::getPage);
        Routes.get("/console/pages/{page}/{pageSize}/{windowSize}", pageConsole::getPages);

        final PluginConsole pluginConsole = beanManager.getReference(PluginConsole.class);
        Routes.put("/console/plugin/status/", pluginConsole::setPluginStatus);
        Routes.get("/console/plugins/{page}/{pageSize}/{windowSize}", pluginConsole::getPlugins);
        Routes.post("/console/plugin/toSetting", pluginConsole::toSetting);
        Routes.post("/console/plugin/updateSetting", pluginConsole::updateSetting);

        final PreferenceConsole preferenceConsole = beanManager.getReference(PreferenceConsole.class);
        Routes.get("/console/signs/", preferenceConsole::getSigns);
        Routes.get("/console/preference/", preferenceConsole::getPreference);
        Routes.put("/console/preference/", preferenceConsole::updatePreference);

        final SkinConsole skinConsole = beanManager.getReference(SkinConsole.class);
        Routes.get("/console/skin", skinConsole::getSkin);
        Routes.put("/console/skin", skinConsole::updateSkin);

        final RepairConsole repairConsole = beanManager.getReference(RepairConsole.class);
        Routes.post("/fix/restore-signs", repairConsole::restoreSigns);

        final TagConsole tagConsole = beanManager.getReference(TagConsole.class);
        Routes.get("/console/tags", tagConsole::getTags);
        Routes.get("/console/tag/unused", tagConsole::getUnusedTags);

        final OtherConsole otherConsole = beanManager.getReference(OtherConsole.class);
        Routes.delete("/console/archive/unused", otherConsole::removeUnusedArchives);
        Routes.delete("/console/tag/unused", otherConsole::removeUnusedTags);

        final UserConsole userConsole = beanManager.getReference(UserConsole.class);
        Routes.put("/console/user/", userConsole::updateUser);
        Routes.delete("/console/user/{id}", userConsole::removeUser);
        Routes.get("/console/users/{page}/{pageSize}/{windowSize}", userConsole::getUsers);
        Routes.get("/console/user/{id}", userConsole::getUser);
        Routes.post("/console/changeRole/{id}", userConsole::changeUserRole);
        Routes.put("/console/user/{id}/status", userConsole::updateUserStatus);

    }
}
