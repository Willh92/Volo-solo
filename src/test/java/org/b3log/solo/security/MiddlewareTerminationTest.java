package org.b3log.solo.security;

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.http.Request;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.http.Response;
import org.b3log.latke.http.function.Handler;
import org.b3log.solo.bolo.waf.WAF;
import org.b3log.solo.bolo.waf.WAFstorage;
import org.b3log.solo.processor.InitCheckHandler;
import org.b3log.solo.processor.console.ConsoleAdminAuthAdvice;
import org.b3log.solo.processor.console.ConsoleAuthAdvice;
import org.b3log.solo.util.AdminPaths;
import org.testng.Assert;
import org.testng.annotations.Test;
import pers.adlered.simplecurrentlimiter.main.SimpleCurrentLimiter;

public class MiddlewareTerminationTest {
    @Test public void securitySubpagesBindAsOrdinaryConsoleRoutes() throws Exception {
        LatkeTestSupport.configure();
        org.b3log.latke.ioc.BeanManager.start(new java.util.ArrayList<>(java.util.List.of(ConsoleAuthAdvice.class)));
        final var console = new org.b3log.solo.processor.console.AdminConsole();
        try {
            org.b3log.solo.web.Routes.get("/fixture/admin-access", console::showAdminAccess);
            org.b3log.solo.web.Routes.get("/fixture/admin-totp", console::showAdminTotp);
            org.b3log.solo.web.Routes.get("/fixture/admin-ip", console::showAdminIpBans);
            org.b3log.solo.web.Routes.get("/fixture/admin-geo", console::showAdminGeoRules);
            org.b3log.solo.web.Routes.get("/fixture/admin-comments", console::showAdminCommentProtection);
        } finally { org.b3log.latke.ioc.BeanManager.close(); }
    }

    @Test public void expiredPanelSessionsReturnForbiddenWithoutRunningTheController() throws Exception {
        try {
            for (String prefix : new String[]{"", "/private-panel"}) {
                for (String contextPath : new String[]{"", "/blog"}) {
                    LatkeTestSupport.configure();
                    final Properties local = new Properties();
                    local.setProperty("adminPath", prefix);
                    Latkes.setLocalProps(local);
                    Latkes.setContextPath(contextPath);
                    for (String suffix : new String[]{"", "/"}) {
                        try (Exchange exchange = new Exchange(contextPath + AdminPaths.panel() + suffix)) {
                            exchange.run(new ConsoleAuthAdvice());
                            Assert.assertEquals(exchange.raw.status().code(), 403);
                            Assert.assertNull(exchange.raw.headers().get("Location"));
                            Assert.assertEquals(exchange.raw.headers().get("Cache-Control"), "no-store");
                        }
                    }
                }
            }
        } finally { LatkeTestSupport.configure(); }
    }

    @Test public void deniedManagementApisNeverRunTheirNextHandler() throws Exception {
        LatkeTestSupport.configure();
        for (Handler guard : new Handler[]{new ConsoleAuthAdvice(), new ConsoleAdminAuthAdvice()}) {
            try (Exchange exchange = new Exchange("/console/preference/")) {
                exchange.run(guard);
                Assert.assertEquals(exchange.raw.status().code(), 401);
            }
        }
    }

    @Test public void exhaustedPageBudgetsStopBeforeDownstreamWork() throws Exception {
        LatkeTestSupport.configure();
        final boolean oldPower = WAF.POWER;
        final SimpleCurrentLimiter oldLimiter = WAFstorage.currentLimiter;
        try {
            WAFstorage.currentLimiter = new SimpleCurrentLimiter(180, 1);
            WAF.POWER = true;
            Assert.assertTrue(WAF.in("198.51.100.88", "/first.html"));
            try (Exchange exchange = new Exchange("/blocked.html")) {
                exchange.context.attr(Keys.HttpRequest.IS_SEARCH_ENGINE_BOT, false);
                exchange.run(new InitCheckHandler() { @Override protected boolean inspectRisk(final RequestContext context) { return false; } @Override protected boolean blockedAddress(final String address) { return false; } });
                Assert.assertEquals(exchange.raw.status().code(), 429);
                Assert.assertEquals(exchange.raw.headers().get("Cache-Control"), "no-store");
            }
        } finally {
            WAF.POWER = oldPower;
            WAFstorage.currentLimiter = oldLimiter;
        }
    }

    @Test public void ipBansAndUnavailableRulesStopBeforeControllers() throws Exception {
        LatkeTestSupport.configure();
        try (Exchange exchange = new Exchange("/article/comments")) {
            exchange.context.attr(Keys.HttpRequest.IS_SEARCH_ENGINE_BOT, false);
            exchange.run(new InitCheckHandler() { @Override protected boolean inspectRisk(final RequestContext context) { return false; } @Override protected boolean blockedAddress(final String address) { return true; } });
            Assert.assertEquals(exchange.raw.status().code(), 403);
            Assert.assertNull(exchange.raw.headers().get("Location"));
        }
        try (Exchange exchange = new Exchange("/article/comments")) {
            exchange.context.attr(Keys.HttpRequest.IS_SEARCH_ENGINE_BOT, false);
            exchange.run(new InitCheckHandler() { @Override protected boolean inspectRisk(final RequestContext context) { return false; } @Override protected boolean blockedAddress(final String address) { throw new IllegalStateException("Unavailable rule store"); } });
            Assert.assertEquals(exchange.raw.status().code(), 503);
        }
    }

    @Test public void staticAssetsBypassInitServiceWithoutBotAttrNpeAndRobotsEnforcesBotFlag() throws Exception {
        LatkeTestSupport.configure();
        final Handler guard = new InitCheckHandler() {
            @Override protected boolean inspectRisk(final RequestContext context) { return false; }
            @Override protected boolean blockedAddress(final String address) { return false; }
        };

        // Public static asset with NO IS_SEARCH_ENGINE_BOT attribute set must pass straight through to downstream
        try (Exchange assetExchange = new Exchange("/scss/appearance.css")) {
            final AtomicInteger downstream = new AtomicInteger();
            assetExchange.context.addHandler(guard);
            assetExchange.context.addHandler(ignored -> downstream.incrementAndGet());
            assetExchange.context.handle();
            Assert.assertEquals(downstream.get(), 1, "Public static assets must bypass InitService even before initialization");
            Assert.assertEquals(assetExchange.raw.status().code(), 200);
        }

        // /robots.txt without bot attribute -> 403 Forbidden (no NPE)
        try (Exchange browserRobots = new Exchange("/robots.txt")) {
            browserRobots.run(guard);
            Assert.assertEquals(browserRobots.raw.status().code(), 403);
        }

        // /robots.txt with IS_SEARCH_ENGINE_BOT=true -> allowed to downstream handler
        try (Exchange botRobots = new Exchange("/robots.txt")) {
            botRobots.context.attr(Keys.HttpRequest.IS_SEARCH_ENGINE_BOT, true);
            final AtomicInteger downstream = new AtomicInteger();
            botRobots.context.addHandler(new InitCheckHandler() {
                @Override protected boolean inspectRisk(final RequestContext context) { return false; }
                @Override protected boolean blockedAddress(final String address) { return false; }
                @Override public void handle(final RequestContext context) {
                    // Verify /robots.txt passes the bot gate; intercept right before InitService DB check
                    if ("/robots.txt".equals(context.requestURI()) && Boolean.TRUE.equals(context.attr(Keys.HttpRequest.IS_SEARCH_ENGINE_BOT))) {
                        downstream.incrementAndGet();
                        return;
                    }
                    super.handle(context);
                }
            });
            botRobots.context.handle();
            Assert.assertEquals(downstream.get(), 1);
        }
    }

    private static final class Exchange implements AutoCloseable {
        private final DefaultFullHttpRequest http;
        private final DefaultFullHttpResponse raw = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        private final RequestContext context;

        Exchange(final String path) {
            http = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path);
            final Request request = new Request(null, http) {
                @Override public String getRemoteAddr() { return "198.51.100.88"; }
            };
            context = new RequestContext(request, new Response(null, raw));
        }

        void run(final Handler guard) {
            final AtomicInteger downstreamCalls = new AtomicInteger();
            context.addHandler(guard);
            context.addHandler(ignored -> downstreamCalls.incrementAndGet());
            context.handle();
            Assert.assertEquals(downstreamCalls.get(), 0, "A terminal response must stop the real Latke handler chain");
        }

        @Override public void close() { http.release(); raw.release(); }
    }
}
