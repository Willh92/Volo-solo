/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.security;

import freemarker.template.Template;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.http.Request;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.http.Response;
import org.b3log.latke.repository.jdbc.AbstractJdbcDatabaseSolution;
import org.b3log.latke.repository.jdbc.JdbcFactory;
import org.b3log.solo.bolo.waf.WAF;
import org.b3log.solo.cache.ArticleCache;
import org.b3log.solo.cache.PublicViewCache;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Option;
import org.b3log.solo.processor.PermalinkHandler;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.service.ArticleMgmtService;
import org.b3log.solo.service.IpBanService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.service.PermalinkQueryService;
import org.b3log.solo.service.SyndicationCacheService;
import org.b3log.solo.service.TotpService;
import org.b3log.solo.upgrade.DatabaseIndexMigration;
import org.b3log.solo.util.S3CompatibleUploader;
import org.b3log.solo.util.Skins;
import org.b3log.solo.web.HttpCompression;
import org.b3log.solo.web.PublicResourceHandler;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

public class FinalRoundRegressionAndPolishTest {

    @BeforeClass
    public void configure() throws Exception {
        LatkeTestSupport.configure();
        final Properties local = new Properties();
        local.setProperty("runtimeDatabase", "NONE");
        local.setProperty("cookieHttpOnly", "true");
        local.setProperty("loginTrustedProxies", "127.0.0.1,::1");
        Latkes.setLocalProps(local);
    }

    @AfterClass
    public void restore() throws Exception {
        WAF.off();
        PublicResourceHandler.clearCache();
        LatkeTestSupport.configure();
    }

    @Test
    public void percentEncodedChinesePermalinksDecodeWhileEncodedProbesAndReservedPathsAreEnforced() {
        final String decodedChinese = PermalinkHandler.normalizeRequestPermalink("/%E4%B8%AD%E6%96%87%E6%A0%87%E9%A2%98");
        Assert.assertEquals(decodedChinese, "/中文标题");
        Assert.assertFalse(PermalinkQueryService.invalidPermalinkFormat(decodedChinese),
                "Decoded Chinese custom permalink must pass format validation");

        final String decodedPlus = PermalinkHandler.normalizeRequestPermalink("/c++%20guide");
        Assert.assertEquals(decodedPlus, "/c++ guide", "Literal '+' in permalinks must not turn into space");

        Assert.assertNull(PermalinkHandler.normalizeRequestPermalink("/broken%ZZslug"),
                "Malformed percent encoding must return null");

        final String encodedDotEnv = PermalinkHandler.normalizeRequestPermalink("/%2eenv");
        Assert.assertEquals(encodedDotEnv, "/.env");
        Assert.assertTrue(PermalinkQueryService.invalidPermalinkFormat(encodedDotEnv),
                "Percent-encoded /.env scanner probe must be rejected after normalization");

        Assert.assertFalse(PermalinkQueryService.invalidPermalinkFormat("/admin-guide"),
                "/admin-guide is a valid user slug and must not be blocked by /admin/ prefix");
        Assert.assertFalse(PermalinkQueryService.invalidPermalinkFormat("/administration"));
        Assert.assertTrue(PermalinkQueryService.invalidPermalinkFormat("/admin/dashboard"));
        Assert.assertTrue(PermalinkQueryService.invalidPermalinkFormat("/admin-index.do"));
        Assert.assertTrue(PermalinkQueryService.invalidPermalinkFormat("/apis/symphony/article"));
        Assert.assertTrue(PermalinkQueryService.invalidArticlePermalinkFormat("/" + "a".repeat(255)),
                "Total length 256 (> VARCHAR(255)) must be rejected before SQL insert");
        Assert.assertFalse(PermalinkQueryService.invalidArticlePermalinkFormat("/" + "a".repeat(254)));
    }

    @Test
    public void writeBehindDrainPreventsDoubleCountAndPartialUpdatePreservesNegativePermalinkCache() throws Exception {
        final ArticleCache articleCache = new ArticleCache();
        articleCache.markMissingPermalink("/non-existent-slug.html");
        Assert.assertTrue(articleCache.isMissingPermalink("/non-existent-slug.html"));

        articleCache.putArticle(new JSONObject()
                .put(Keys.OBJECT_ID, "art-1")
                .put(Article.ARTICLE_PERMALINK, "/cached-post.html")
                .put(Article.ARTICLE_TITLE, "Cached Post")
                .put(Article.ARTICLE_VIEW_COUNT, 10));

        final ArticleMgmtService articleMgmtService = new ArticleMgmtService();
        setField(articleMgmtService, "articleCache", articleCache);

        articleMgmtService.incViewCount("art-1");
        articleMgmtService.incViewCount("art-1");
        articleMgmtService.incViewCount("art-1");
        Assert.assertEquals(articleMgmtService.getPendingViewDelta("art-1"), 3L);
        Assert.assertEquals(articleCache.getArticle("art-1").optInt(Article.ARTICLE_VIEW_COUNT), 13);

        // Drain aligns cached article back to persisted base (10) and returns 3 so caller persists 10 + 3 = 13 once
        final long drained = articleMgmtService.drainPendingViewDelta("art-1");
        Assert.assertEquals(drained, 3L);
        Assert.assertEquals(articleMgmtService.getPendingViewDelta("art-1"), 0L);
        Assert.assertEquals(articleCache.getArticle("art-1").optInt(Article.ARTICLE_VIEW_COUNT), 10);

        // Rollback restoration restores both pending delta and live cache view count
        articleMgmtService.restorePendingViewDelta("art-1", drained);
        Assert.assertEquals(articleMgmtService.getPendingViewDelta("art-1"), 3L);
        Assert.assertEquals(articleCache.getArticle("art-1").optInt(Article.ARTICLE_VIEW_COUNT), 13);

        articleMgmtService.resetPendingViewDeltas();
        Assert.assertEquals(articleMgmtService.getPendingViewDelta("art-1"), 0L);

        // Partial view-count SQL update on REAL ArticleRepository (with runtimeDatabase=NONE)
        // for an article NOT in idCache must NOT clear missingPermalinks!
        final ArticleRepository realRepo = new ArticleRepository();
        final org.b3log.latke.repository.Repository noopUnderlying =
                (org.b3log.latke.repository.Repository) Proxy.newProxyInstance(
                        org.b3log.latke.repository.Repository.class.getClassLoader(),
                        new Class<?>[]{org.b3log.latke.repository.Repository.class},
                        (proxy, method, args) -> "getName".equals(method.getName()) ? "article"
                                : (method.getReturnType() == boolean.class ? Boolean.TRUE : null));
        setField(realRepo, "repository", noopUnderlying);
        setField(realRepo, "articleCache", articleCache);
        realRepo.update("uncached-art-999",
                new JSONObject().put(Article.ARTICLE_VIEW_COUNT, 42),
                Article.ARTICLE_VIEW_COUNT);
        Assert.assertTrue(articleCache.isMissingPermalink("/non-existent-slug.html"),
                "60s view-count flush on evicted articles must preserve negative permalink cache");

        // By contrast, updating ARTICLE_PERMALINK for an uncached article MUST clear missingPermalinks
        realRepo.update("uncached-art-999",
                new JSONObject().put(Article.ARTICLE_PERMALINK, "/non-existent-slug.html"),
                Article.ARTICLE_PERMALINK);
        Assert.assertFalse(articleCache.isMissingPermalink("/non-existent-slug.html"),
                "Permalink update on uncached article must clear negative permalink cache");
    }

    @Test
    public void renderedArticleViewRollsOverAcrossEpochDaysAndSelectiveSidebarInvalidationKeepsOtherWidgets() throws Exception {
        final PublicViewCache viewCache = new PublicViewCache();
        final long today = PublicViewCache.currentEpochDay();
        viewCache.putRenderedArticle("art-rollover", new PublicViewCache.RenderedArticleView(
                999L, true, false, "<p>Outdated 30 days</p>", "Plain",
                List.of(), null, "", "", null, null, today - 1L));
        Assert.assertNull(viewCache.getRenderedArticle("art-rollover", 999L, true, false),
                "Stale epochDay must miss so outdated-article day counter refreshes across midnight");

        viewCache.putRenderedArticle("art-today", new PublicViewCache.RenderedArticleView(
                999L, true, false, "<p>Fresh</p>", "Plain",
                List.of(), null, "", "", null, null, today));
        Assert.assertNotNull(viewCache.getRenderedArticle("art-today", 999L, true, false));

        viewCache.getOrComputeList("mostViewCountArticles:5", 0L, () -> List.of(new JSONObject().put("n", 1)));
        viewCache.getOrComputeList("recentArticles:5", 0L, () -> List.of(new JSONObject().put("n", 1)));
        viewCache.getOrComputeList("links", 0L, () -> List.of(new JSONObject().put("keep", true)));

        viewCache.invalidateSidebarViewWidgets();

        final List<JSONObject> reloadedMostViewed = viewCache.getOrComputeList(
                "mostViewCountArticles:5", 0L, () -> List.of(new JSONObject().put("n", 2)));
        Assert.assertEquals(reloadedMostViewed.get(0).getInt("n"), 2, "Sidebar view widget must reload after flush");

        final List<JSONObject> preservedLinks = viewCache.getOrComputeList(
                "links", 0L, () -> List.of(new JSONObject().put("keep", false)));
        Assert.assertTrue(preservedLinks.get(0).getBoolean("keep"), "Unrelated widgets must remain cached");
    }

    @Test
    public void totpAndIpBanServicesReloadFromStorageImmediatelyWhenInvokedByBackupRestore() throws Exception {
        final AtomicReference<String> storedBansJson = new AtomicReference<>(
                "[{\"range\":\"203.0.113.9\",\"reason\":\"pre-restore\",\"created\":1000,\"expiresAt\":0}]");
        final OptionQueryService mockOptionQuery = new OptionQueryService() {
            @Override
            public JSONObject getOptionById(final String optionId) {
                if ("voloIpBans".equals(optionId)) {
                    final String val = storedBansJson.get();
                    return val == null ? null : new JSONObject().put(Option.OPTION_VALUE, val);
                }
                return null;
            }
        };

        final IpBanService ipBanService = new IpBanService();
        setField(ipBanService, "optionQueryService", mockOptionQuery);
        Assert.assertTrue(ipBanService.blocked("203.0.113.9"));

        // Simulate backup restore replacing underlying DB row with empty list
        storedBansJson.set("[]");
        Assert.assertTrue(ipBanService.blocked("203.0.113.9"), "Snapshot stays cached prior to reloadFromStorage");

        ipBanService.reloadFromStorage();
        Assert.assertFalse(ipBanService.blocked("203.0.113.9"),
                "reloadFromStorage() must immediately drop stale pre-restore IP ban snapshot");

        final TotpService totpService = new TotpService();
        totpService.reloadFromStorage();
    }

    @Test
    public void errorPageTemplateNeverLeaksVisitorRequestUriOrQueryStringIntoCachedHtml() throws Exception {
        final Template template = Skins.getTemplate("error/404.ftl");
        Assert.assertNotNull(template, "error/404.ftl must load from webapp root");

        final Map<String, Object> model = new HashMap<>();
        model.put("servePath", "https://blog.example.com");
        model.put("staticServePath", "https://blog.example.com");
        model.put("staticResourceVersion", "20261106");
        model.put("blogTitle", "Volo Studio");
        model.put("blogSubtitle", "Lightweight Blog");
        model.put("metaKeywords", "volo");
        model.put("metaDescription", "desc");
        model.put("langLabel", "zh_CN");
        model.put("boloVersion", "3.0.0");
        model.put("faviconURL", "https://blog.example.com/favicon.ico");
        model.put("appearanceAccent", "emerald");
        model.put("request", Map.of(
                "requestURI", "/private-preview/secret-slug",
                "queryString", "shareToken=SUPER_SECRET_TOKEN_999"
        ));

        final StringWriter out = new StringWriter();
        template.process(model, out);
        final String html = out.toString();

        Assert.assertFalse(html.contains("SUPER_SECRET_TOKEN_999"),
                "Error page HTML must NEVER embed visitor queryString");
        Assert.assertFalse(html.contains("/private-preview/secret-slug"),
                "Error page HTML must NEVER embed visitor requestURI");
        Assert.assertTrue(html.contains("property=\"og:url\"") && html.contains("content=\"https://blog.example.com/\""),
                "Error page og:url must point to site root");
        Assert.assertFalse(html.contains("rel=\"canonical\""),
                "Error page must omit self-referential canonical link");
    }

    @Test
    public void syndicationGzipEtagDifferentiationAndStreamingThreadLocalGzipRoundTrip() throws Exception {
        final String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss><channel>"
                + "<item><title>中文与 Emoji 🚀 订阅压缩校验</title></item>".repeat(35)
                + "</channel></rss>";
        final SyndicationCacheService.Snapshot snapshot = SyndicationCacheService.Snapshot.of(xml);

        // 1. Plain client gets base ETag "<hex>" and Vary: Accept-Encoding
        final DefaultFullHttpRequest plainReq = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/rss.xml");
        final DefaultFullHttpResponse plainResp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        try {
            final RequestContext plainCtx = new RequestContext(new Request(null, plainReq), new Response(null, plainResp));
            Assert.assertFalse(SyndicationCacheService.tryServeNotModified(plainCtx, snapshot, "application/xml; charset=UTF-8"));
            Assert.assertEquals(plainCtx.getResponse().getHeader("ETag"), snapshot.etag());
            Assert.assertEquals(plainCtx.getResponse().getHeader("Vary"), "Accept-Encoding");
        } finally {
            plainReq.release();
            plainResp.release();
        }

        // 2. GZIP client gets distinct "<hex>-gzip" ETag per RFC 7232
        final String expectedGzipEtag = SyndicationCacheService.withEncodingSuffix(snapshot.etag(), "-gzip");
        final DefaultFullHttpRequest gzReq = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/rss.xml");
        gzReq.headers().set(HttpHeaderNames.ACCEPT_ENCODING, "gzip, br");
        final DefaultFullHttpResponse gzResp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        try {
            final RequestContext gzCtx = new RequestContext(new Request(null, gzReq), new Response(null, gzResp));
            Assert.assertFalse(SyndicationCacheService.tryServeNotModified(gzCtx, snapshot, "application/xml; charset=UTF-8"));
            Assert.assertEquals(gzCtx.getResponse().getHeader("ETag"), expectedGzipEtag);
            Assert.assertEquals(gzCtx.getResponse().getHeader("Vary"), "Accept-Encoding");

            // Exercise reusable ThreadLocal<Deflater> multiple times on same thread
            HttpCompression.sendText(gzCtx, xml, "application/xml; charset=UTF-8");
            Assert.assertEquals(gzCtx.getResponse().getHeader("Content-Encoding"), "gzip");
            try (GZIPInputStream gzis = new GZIPInputStream(new ByteArrayInputStream(gzCtx.getResponse().getBytes()))) {
                Assert.assertEquals(new String(gzis.readAllBytes(), StandardCharsets.UTF_8), xml);
            }
        } finally {
            gzReq.release();
            gzResp.release();
        }

        // 3. Revalidating with "<hex>-gzip" returns 304 Not Modified
        final DefaultFullHttpRequest condReq = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/rss.xml");
        condReq.headers().set(HttpHeaderNames.ACCEPT_ENCODING, "gzip");
        condReq.headers().set(HttpHeaderNames.IF_NONE_MATCH, expectedGzipEtag);
        final DefaultFullHttpResponse condResp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        try {
            final RequestContext condCtx = new RequestContext(new Request(null, condReq), new Response(null, condResp));
            Assert.assertTrue(SyndicationCacheService.tryServeNotModified(condCtx, snapshot, "application/xml; charset=UTF-8"));
            Assert.assertEquals(condResp.status().code(), 304);
        } finally {
            condReq.release();
            condResp.release();
        }

        // 4. HEAD request on HttpCompression skips body & GZIP deflation
        final DefaultFullHttpRequest headReq = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.HEAD, "/rss.xml");
        headReq.headers().set(HttpHeaderNames.ACCEPT_ENCODING, "gzip");
        final DefaultFullHttpResponse headResp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        try {
            final RequestContext headCtx = new RequestContext(new Request(null, headReq), new Response(null, headResp));
            HttpCompression.sendText(headCtx, xml, "application/xml; charset=UTF-8");
            Assert.assertEquals(headCtx.getResponse().getBytes().length, 0);
            Assert.assertNull(headCtx.getResponse().getHeader("Content-Encoding"));
        } finally {
            headReq.release();
            headResp.release();
        }
    }

    @Test
    public void wafExemptsLocalImagesAndPublicResourceHandlerServesScopedImmutableAndInMemoryRanges() throws Exception {
        WAF.on();
        org.b3log.solo.bolo.waf.WAFstorage.currentLimiter =
                new pers.adlered.simplecurrentlimiter.main.SimpleCurrentLimiter(60, 2);
        try {
            // 30 consecutive local uploaded images on an article page must NEVER trip WAF rate limit
            for (int i = 0; i < 30; i++) {
                Assert.assertTrue(WAF.in("198.51.100.77", "/image/20261106_" + i + ".webp"),
                        "Local /image/*.webp must be exempted from per-request WAF quota");
            }
            // Meanwhile non-exempted paths enforce the 2-request limit for the exact same IP
            Assert.assertTrue(WAF.in("198.51.100.77", "/non-exempt-1.html"));
            Assert.assertTrue(WAF.in("198.51.100.77", "/non-exempt-2.html"));
            Assert.assertFalse(WAF.in("198.51.100.77", "/non-exempt-3.html"),
                    "Non-exempt paths must still be rate-limited by WAF");
        } finally {
            WAF.off();
        }

        PublicResourceHandler.clearCache();
        final PublicResourceHandler handler = new PublicResourceHandler();

        // /images/404.gif?v=1 must get max-age=3600 (NOT immutable)
        final DefaultFullHttpRequest favReq = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/images/404.gif?v=1");
        final DefaultFullHttpResponse favResp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        try {
            final RequestContext favCtx = new RequestContext(new Request(null, favReq), new Response(null, favResp));
            handler.handle(favCtx);
            Assert.assertEquals(favCtx.getResponse().getHeader("Cache-Control"), "public, max-age=3600");
        } finally {
            favReq.release();
            favResp.release();
        }

        // Warm /js/common.min.js into hotCache, then request a 16-byte Range slice from RAM
        final byte[] fullBytes;
        final DefaultFullHttpRequest warmReq = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/js/common.min.js");
        final DefaultFullHttpResponse warmResp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        try {
            final RequestContext warmCtx = new RequestContext(new Request(null, warmReq), new Response(null, warmResp));
            handler.handle(warmCtx);
            fullBytes = warmCtx.getResponse().getBytes();
            Assert.assertTrue(fullBytes.length > 32);
        } finally {
            warmReq.release();
            warmResp.release();
        }

        final DefaultFullHttpRequest rangeReq = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/js/common.min.js");
        rangeReq.headers().set(HttpHeaderNames.RANGE, "bytes=0-15");
        final DefaultFullHttpResponse rangeResp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        try {
            final RequestContext rangeCtx = new RequestContext(new Request(null, rangeReq), new Response(null, rangeResp));
            handler.handle(rangeCtx);
            Assert.assertEquals(rangeResp.status().code(), 206);
            final byte[] slice = rangeCtx.getResponse().getBytes();
            Assert.assertEquals(slice.length, 16);
            for (int i = 0; i < 16; i++) {
                Assert.assertEquals(slice[i], fullBytes[i]);
            }
        } finally {
            rangeReq.release();
            rangeResp.release();
        }
    }

    @Test
    public void s3CjkMixedPercentDecodeAndMysqlError1071Prefix191FallbackSucceed() throws Exception {
        // Mixed raw CJK characters + %20 escape previously threw BufferOverflowException in rawPercentDecode
        final URI mixedCjkUri = new URI("https://s3.example.com/media/图片%20封面_1.webp");
        final S3CompatibleUploader.SignedRequest signed = S3CompatibleUploader.signRequest(
                "PUT",
                mixedCjkUri,
                "auto",
                "AKIAEXAMPLE",
                "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                S3CompatibleUploader.EMPTY_PAYLOAD_SHA256,
                "20261106T000000Z"
        );
        Assert.assertTrue(signed.canonicalRequest().contains("/media/%E5%9B%BE%E7%89%87%20%E5%B0%81%E9%9D%A2_1.webp"),
                "Mixed CJK + %20 object key must decode and re-encode canonically without BufferOverflowException");

        // Simulate legacy MySQL 5.7 COMPACT table throwing Error 1071 on full VARCHAR(255) utf8mb4 index
        final List<String> executedSql = new ArrayList<>();
        final Statement mockStmt = (Statement) Proxy.newProxyInstance(
                Statement.class.getClassLoader(),
                new Class<?>[]{Statement.class},
                (proxy, method, args) -> {
                    if ("executeUpdate".equals(method.getName())) {
                        final String sql = (String) args[0];
                        executedSql.add(sql);
                        if (!sql.contains("(191)")) {
                            throw new SQLException("Specified key was too long; max key length is 767 bytes", "HY000", 1071);
                        }
                        return 0;
                    }
                    return null;
                });

        final boolean created = DatabaseIndexMigration.executeCreateIndexWithFallback(
                mockStmt, "idx_b3_article_permalink", "`b3_article`", List.of("`articlePermalink`"));
        Assert.assertTrue(created, "Single-column VARCHAR index must succeed via (191) prefix fallback on Error 1071");
        Assert.assertEquals(executedSql.size(), 2);
        Assert.assertEquals(executedSql.get(1),
                "CREATE INDEX idx_b3_article_permalink ON `b3_article` (`articlePermalink`(191))");
    }

    @Test
    public void latkeBeanManagerInstantiatesAllDiscoveredBeansAndRoutesWithoutCircularDependency() throws Exception {
        final Properties localProps = new Properties();
        localProps.setProperty("runtimeDatabase", "NONE");
        localProps.setProperty("jdbc.tablePrefix", "b3_solo");
        Latkes.setLocalProps(localProps);
        final Properties latkeProps = new Properties();
        latkeProps.setProperty("serverScheme", "https");
        latkeProps.setProperty("serverHost", "example.com");
        latkeProps.setProperty("serverPort", "");
        latkeProps.setProperty("staticServerScheme", "https");
        latkeProps.setProperty("staticServerHost", "example.com");
        latkeProps.setProperty("staticServerPort", "");
        latkeProps.setProperty("contextPath", "");
        latkeProps.setProperty("staticPath", "");
        latkeProps.setProperty("runtimeMode", "PRODUCTION");
        Latkes.setLatkeProps(latkeProps);

        Latkes.setScanPath("org.b3log.solo");
        final java.util.Collection<Class<?>> discovered = org.b3log.latke.ioc.Discoverer.discover("org.b3log.solo");
        Assert.assertFalse(discovered.isEmpty(), "Discoverer must find org.b3log.solo IoC beans");

        org.b3log.latke.ioc.BeanManager.start(discovered);
        try {
            final org.b3log.latke.ioc.BeanManager beanManager = org.b3log.latke.ioc.BeanManager.getInstance();
            int instantiatedCount = 0;
            for (final Class<?> clazz : discovered) {
                final org.b3log.latke.ioc.Bean<?> bean = beanManager.getBean(clazz);
                if (null != bean) {
                    Assert.assertNotNull(beanManager.getReference(bean),
                            "Failed to instantiate IoC bean: " + clazz.getName());
                    instantiatedCount++;
                }
            }
            Assert.assertTrue(instantiatedCount >= 50,
                    "Expected at least 50 Solo IoC beans to instantiate cleanly, got " + instantiatedCount);
            org.b3log.solo.SoloApplication.routeConsoleProcessors();
            org.b3log.solo.web.Routes.mapping();
        } finally {
            org.b3log.latke.ioc.BeanManager.close();
            configure();
        }
    }

    private static void setField(final Object target, final String name, final Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                final Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (final NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
