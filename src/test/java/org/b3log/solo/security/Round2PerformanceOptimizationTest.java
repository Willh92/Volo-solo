/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.security;

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.b3log.latke.Keys;
import org.b3log.latke.http.Cookie;
import org.b3log.latke.http.Request;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.http.Response;
import org.b3log.latke.http.Session;
import org.b3log.latke.http.Sessions;
import org.b3log.latke.http.renderer.JsonRenderer;
import org.b3log.latke.repository.Query;
import org.b3log.solo.SoloApplication;
import org.b3log.solo.cache.ArticleCache;
import org.b3log.solo.cache.PublicViewCache;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Common;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.service.PermalinkQueryService;
import org.b3log.solo.upgrade.DatabaseIndexMigration;
import org.b3log.solo.util.Emotions;
import org.b3log.solo.util.Skins;
import org.b3log.solo.util.Solos;
import org.b3log.solo.web.HttpCompression;
import org.b3log.solo.web.JsonResponses;
import org.b3log.solo.web.PublicResourceHandler;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

public class Round2PerformanceOptimizationTest {

    @BeforeClass
    public void configure() throws Exception {
        LatkeTestSupport.configure();
        final var local = new java.util.Properties();
        local.setProperty("runtimeDatabase", "NONE");
        local.setProperty("cookieHttpOnly", "true");
        local.setProperty("loginTrustedProxies", "127.0.0.1,::1");
        org.b3log.latke.Latkes.setLocalProps(local);
    }

    @AfterClass
    public void restore() throws Exception {
        PublicResourceHandler.clearCache();
        LatkeTestSupport.configure();
    }

    @Test
    public void databaseIndexMigrationCreatesAll30CoreIndexesIdempotently() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:volo_idx_test;DB_CLOSE_DELAY=-1")) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE b3_article (oId VARCHAR(19) PRIMARY KEY, articlePermalink VARCHAR(255), "
                        + "articleStatus INT, articlePutTop BOOLEAN, articleUpdated BIGINT, articleCreated BIGINT, "
                        + "articleViewCount INT, articleCommentCount INT, articleRandomDouble DOUBLE, "
                        + "articleAuthorId VARCHAR(19), articleScheduleTime BIGINT)");
                st.execute("CREATE TABLE b3_comment (oId VARCHAR(19) PRIMARY KEY, commentOnId VARCHAR(19), "
                        + "commentStatus INT, commentCreated BIGINT, commentUserId VARCHAR(19))");
                st.execute("CREATE TABLE b3_option (oId VARCHAR(64) PRIMARY KEY, optionCategory VARCHAR(64))");
                st.execute("CREATE TABLE b3_tag (oId VARCHAR(19) PRIMARY KEY, tagTitle VARCHAR(255))");
                st.execute("CREATE TABLE b3_tag_article (oId VARCHAR(19) PRIMARY KEY, tag_oId VARCHAR(19), article_oId VARCHAR(19))");
                st.execute("CREATE TABLE b3_archivedate (oId VARCHAR(19) PRIMARY KEY, archiveTime BIGINT)");
                st.execute("CREATE TABLE b3_archivedate_article (oId VARCHAR(19) PRIMARY KEY, archiveDate_oId VARCHAR(19), article_oId VARCHAR(19))");
                st.execute("CREATE TABLE b3_category (oId VARCHAR(19) PRIMARY KEY, categoryURI VARCHAR(255), categoryOrder INT)");
                st.execute("CREATE TABLE b3_category_tag (oId VARCHAR(19) PRIMARY KEY, tag_oId VARCHAR(19), category_oId VARCHAR(19))");
                st.execute("CREATE TABLE b3_page (oId VARCHAR(19) PRIMARY KEY, pagePermalink VARCHAR(255), pageOrder INT)");
                st.execute("CREATE TABLE b3_user (oId VARCHAR(19) PRIMARY KEY, userName VARCHAR(255), userEmail VARCHAR(254), userRole VARCHAR(64))");
                st.execute("CREATE TABLE b3_article_revision (oId VARCHAR(19) PRIMARY KEY, articleOId VARCHAR(19), revisionCreated BIGINT)");
            }

            // First execution creates all 30 secondary indexes
            DatabaseIndexMigration.ensureCoreIndexes(conn, "b3_");
            // Second execution must be 100% idempotent without throwing
            DatabaseIndexMigration.ensureCoreIndexes(conn, "b3_");

            final Set<String> createdIndexes = new HashSet<>();
            try (ResultSet tables = conn.getMetaData().getTables(
                    conn.getCatalog(), conn.getSchema(), null, new String[]{"TABLE", "BASE TABLE"})) {
                while (tables.next()) {
                    final String tableName = tables.getString("TABLE_NAME");
                    if (tableName == null) {
                        continue;
                    }
                    try (ResultSet rs = conn.getMetaData().getIndexInfo(
                            conn.getCatalog(), conn.getSchema(), tableName, false, true)) {
                        while (rs.next()) {
                            final String name = rs.getString("INDEX_NAME");
                            if (name != null && name.toLowerCase(Locale.ROOT).startsWith("idx_b3_")) {
                                createdIndexes.add(name.toLowerCase(Locale.ROOT));
                            }
                        }
                    }
                }
            }
            Assert.assertEquals(createdIndexes.size(), 30, "Expected all 30 core secondary indexes to exist: " + createdIndexes);
            Assert.assertTrue(createdIndexes.contains("idx_b3_article_permalink"));
            Assert.assertTrue(createdIndexes.contains("idx_b3_article_st_top_upd"));
            Assert.assertTrue(createdIndexes.contains("idx_b3_article_st_sched"));
            Assert.assertTrue(createdIndexes.contains("idx_b3_comment_on_st_id"));
            Assert.assertTrue(createdIndexes.contains("idx_b3_option_cat"));
        }
    }

    @Test
    public void transientAnonymousSessionsAreEvictedEvenWhenLatkeMutatesRequestCookies() {
        final Session minted = Sessions.add();
        final String mintedId = minted.getId();
        Assert.assertTrue(Sessions.CACHE.contains(mintedId));

        final DefaultFullHttpRequest rawAnon = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        try {
            final Request anonReq = new Request(null, rawAnon);
            // Simulate Latke ServerHandler.createSessionCookie mutating req.addCookie before startRequestHandler runs
            anonReq.addCookie(new Cookie(Session.LATKE_SESSION_ID, mintedId));
            anonReq.setSession(minted);

            final boolean incoming = SoloApplication.hasIncomingSessionCookie(anonReq.getHeader("Cookie"), mintedId);
            Assert.assertFalse(incoming, "Minted cookie without incoming Cookie HTTP header must NOT count as incoming session");
            anonReq.setAttribute("bolo.hadIncomingSession", incoming);
            SoloApplication.evictTransientSession(anonReq);
            Assert.assertFalse(Sessions.CACHE.contains(mintedId),
                    "Anonymous request without ARTICLES_VIEW_PWD must be immediately evicted from Sessions.CACHE");
        } finally {
            Sessions.CACHE.remove(mintedId);
            rawAnon.release();
        }

        // Verify anonymous visitor who unlocked a password-protected article retains their session
        final Session pwdSession = Sessions.add();
        final String pwdSessionId = pwdSession.getId();
        pwdSession.setAttribute(Common.ARTICLES_VIEW_PWD, "{\"art-1\":\"secret\"}");
        final DefaultFullHttpRequest rawPwd = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/console/article-pwd");
        try {
            final Request pwdReq = new Request(null, rawPwd);
            pwdReq.setSession(pwdSession);
            pwdReq.setAttribute("bolo.hadIncomingSession", false);
            SoloApplication.evictTransientSession(pwdReq);
            Assert.assertTrue(Sessions.CACHE.contains(pwdSessionId),
                    "Session holding unlocked ARTICLES_VIEW_PWD must survive transient eviction");
        } finally {
            Sessions.CACHE.remove(pwdSessionId);
            rawPwd.release();
        }

        // Verify genuine incoming Cookie HTTP header matches accurately with token boundaries
        Assert.assertTrue(SoloApplication.hasIncomingSessionCookie(
                "theme=dark; " + Session.LATKE_SESSION_ID + "=abc123XYZ; visited=[]", "abc123XYZ"));
        Assert.assertFalse(SoloApplication.hasIncomingSessionCookie(
                "OTHER_" + Session.LATKE_SESSION_ID + "=abc123XYZ", "abc123XYZ"));
        Assert.assertFalse(SoloApplication.hasIncomingSessionCookie(
                Session.LATKE_SESSION_ID + "=differentId", "abc123XYZ"));
    }

    @Test
    public void solosCloneDeepCopiesNestedObjectsArraysAndDatesWithoutSerialization() {
        final Date originalDate = new Date(1_700_000_000_000L);
        final JSONObject nestedObj = new JSONObject().put("title", "Original Nested");
        final JSONArray nestedArr = new JSONArray().put(new JSONObject().put("tag", "java")).put("plain");
        final JSONObject source = new JSONObject();
        for (int i = 0; i < 80; i++) {
            source.put("prefKey_" + i, "val_" + i);
        }
        source.put("createdDate", originalDate);
        source.put("nestedObj", nestedObj);
        source.put("nestedArr", nestedArr);

        final JSONObject cloned = Solos.clone(source);
        Assert.assertNotNull(cloned);
        Assert.assertNotSame(cloned, source);
        Assert.assertEquals(cloned.length(), source.length());

        // Mutate nested structures in clone and verify source remains 100% isolated
        cloned.getJSONObject("nestedObj").put("title", "Mutated");
        cloned.getJSONArray("nestedArr").getJSONObject(0).put("tag", "mutated-tag");
        ((Date) cloned.get("createdDate")).setTime(0L);

        Assert.assertEquals(source.getJSONObject("nestedObj").getString("title"), "Original Nested");
        Assert.assertEquals(source.getJSONArray("nestedArr").getJSONObject(0).getString("tag"), "java");
        Assert.assertEquals(((Date) source.get("createdDate")).getTime(), 1_700_000_000_000L);
    }

    @Test
    public void permalinkFastRejectAndNegativeCacheEliminateMissingSlugSqlQueries() throws Exception {
        for (final String nonPermalink : new String[]{
                "/search", "/manifest.json", "/opensearch.xml", "/categories.html",
                "/archives.html", "/links.html", "/dynamic.html", "/wp-login.php", "/.env", "/probe.jsp"
        }) {
            Assert.assertTrue(PermalinkQueryService.invalidPermalinkFormat(nonPermalink),
                    "Route/probe must be rejected before ArticleRepository lookup: " + nonPermalink);
        }
        Assert.assertFalse(PermalinkQueryService.invalidPermalinkFormat("/hello-bolo"));
        Assert.assertFalse(PermalinkQueryService.invalidArticlePermalinkFormat("/articles/2026/11/06/1700000000000.html"));

        final AtomicInteger sqlQueries = new AtomicInteger(0);
        final ArticleCache articleCache = new ArticleCache();
        final ArticleRepository repo = new ArticleRepository() {
            @Override
            public JSONObject get(final Query query) {
                sqlQueries.incrementAndGet();
                return new JSONObject().put(Keys.RESULTS, new JSONArray());
            }
        };
        final Field cacheField = ArticleRepository.class.getDeclaredField("articleCache");
        cacheField.setAccessible(true);
        cacheField.set(repo, articleCache);

        Assert.assertNull(repo.getByPermalink("/missing-post.html"));
        Assert.assertEquals(sqlQueries.get(), 1, "First miss checks SQL once");
        Assert.assertTrue(articleCache.isMissingPermalink("/missing-post.html"));

        // Repeat 20 lookups for the same missing permalink -> 0 SQL queries!
        for (int i = 0; i < 20; i++) {
            Assert.assertNull(repo.getByPermalink("/missing-post.html"));
        }
        Assert.assertEquals(sqlQueries.get(), 1, "Negative permalink cache must serve repeated misses with 0 SQL");

        // Publishing an article at that permalink must immediately clear the negative cache entry
        articleCache.putArticle(new JSONObject()
                .put(Keys.OBJECT_ID, "art-new")
                .put(Article.ARTICLE_PERMALINK, "/missing-post.html")
                .put(Article.ARTICLE_TITLE, "Now Published"));
        Assert.assertFalse(articleCache.isMissingPermalink("/missing-post.html"));
        Assert.assertNotNull(repo.getByPermalink("/missing-post.html"));
        Assert.assertEquals(sqlQueries.get(), 1, "Positive cache hit must also execute 0 SQL");
    }

    @Test
    public void renderedArticleViewZeroSqlMetadataAndLruWidgetEvictionStayIsolated() throws Exception {
        final PublicViewCache viewCache = new PublicViewCache();
        final List<JSONObject> toc = List.of(new JSONObject().put("id", "h-1").put("text", "Intro"));
        final JSONObject category = new JSONObject().put(Keys.OBJECT_ID, "cat-1").put("categoryTitle", "Backend");
        final JSONObject next = new JSONObject().put("articleTitle", "Next").put("articlePermalink", "/next");
        final JSONObject prev = new JSONObject().put("articleTitle", "Prev").put("articlePermalink", "/prev");

        viewCache.putRenderedArticle("art-100", new PublicViewCache.RenderedArticleView(
                12345L, true, false, "<p>Body</p>", "Plain",
                toc, category, "Backend", "backend", next, prev));

        final PublicViewCache.RenderedArticleView hit = viewCache.getRenderedArticle("art-100", 12345L, true, false);
        Assert.assertNotNull(hit);
        final List<JSONObject> clonedToc = hit.clonedTocItems();
        clonedToc.get(0).put("text", "Tampered");
        hit.clonedCategory().put("categoryTitle", "Tampered");
        hit.clonedNextArticle().put("articleTitle", "Tampered");

        final PublicViewCache.RenderedArticleView secondHit = viewCache.getRenderedArticle("art-100", 12345L, true, false);
        Assert.assertEquals(secondHit.clonedTocItems().get(0).getString("text"), "Intro");
        Assert.assertEquals(secondHit.clonedCategory().getString("categoryTitle"), "Backend");
        Assert.assertEquals(secondHit.clonedNextArticle().getString("articleTitle"), "Next");
        Assert.assertEquals(secondHit.clonedPreviousArticle().getString("articleTitle"), "Prev");

        // Exceed MAX_WIDGET_KEYS by 1 and verify recent keys survive (no full-map clear cliff!)
        for (int i = 0; i <= PublicViewCache.MAX_WIDGET_KEYS; i++) {
            final int idx = i;
            viewCache.getOrComputeList("widget-" + i, 0L, () -> List.of(new JSONObject().put("idx", idx)));
        }
        final AtomicInteger reloads = new AtomicInteger(0);
        viewCache.getOrComputeList("widget-" + PublicViewCache.MAX_WIDGET_KEYS, 0L, () -> {
            reloads.incrementAndGet();
            return List.of();
        });
        Assert.assertEquals(reloads.get(), 0, "Overflowing widgetLists by 1 must evict LRU only, not clear all widgets");
    }

    @Test
    public void staticResourcesEmitImmutableOnVersionedUrlsAndSkinsNegativeCacheAvoidsMisses() throws Exception {
        PublicResourceHandler.clearCache();
        final PublicResourceHandler handler = new PublicResourceHandler();

        // Unversioned static asset -> max-age=3600
        final DefaultFullHttpRequest unversionedReq = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/js/common.min.js");
        final DefaultFullHttpResponse unversionedResp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        try {
            final RequestContext ctx1 = new RequestContext(
                    new Request(null, unversionedReq), new Response(null, unversionedResp));
            handler.handle(ctx1);
            Assert.assertEquals(ctx1.getResponse().getHeader("Cache-Control"), "public, max-age=3600");
        } finally {
            unversionedReq.release();
            unversionedResp.release();
        }

        // Versioned static asset (?v=...) -> max-age=31536000, immutable
        final DefaultFullHttpRequest versionedReq = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/js/common.min.js?20261106");
        final DefaultFullHttpResponse versionedResp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        try {
            final RequestContext ctx2 = new RequestContext(
                    new Request(null, versionedReq), new Response(null, versionedResp));
            handler.handle(ctx2);
            Assert.assertEquals(ctx2.getResponse().getHeader("Cache-Control"), "public, max-age=31536000, immutable");
        } finally {
            versionedReq.release();
            versionedResp.release();
        }

        // Service worker stays no-cache even with query string
        final DefaultFullHttpRequest swReq = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/sw.js?20261106");
        final DefaultFullHttpResponse swResp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        try {
            final RequestContext ctxSw = new RequestContext(
                    new Request(null, swReq), new Response(null, swResp));
            handler.handle(ctxSw);
            Assert.assertEquals(ctxSw.getResponse().getHeader("Cache-Control"), "no-cache");
        } finally {
            swReq.release();
            swResp.release();
        }

        // Skins.fillLangs negative cache for default skin volo-studio (no /lang/ directory)
        org.b3log.latke.Latkes.setLocale(Locale.SIMPLIFIED_CHINESE);
        boolean startedBeanManager = false;
        try {
            org.b3log.latke.ioc.BeanManager.getInstance().getReference(org.b3log.latke.service.LangPropsService.class);
        } catch (final Exception ignored) {
            org.b3log.latke.ioc.BeanManager.start(new java.util.ArrayList<>(List.of(org.b3log.latke.service.LangPropsService.class)));
            startedBeanManager = true;
        }
        try {
            final Field langMapField = Skins.class.getDeclaredField("LANG_MAP");
            langMapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            final Map<String, Map<String, String>> langMap = (Map<String, Map<String, String>>) langMapField.get(null);
            langMap.remove("volo-studio.zh_CN");
            final Map<String, Object> dataModel = new HashMap<>();
            Skins.fillLangs("zh_CN", "volo-studio", dataModel);
            Assert.assertNotNull(langMap.get("volo-studio.zh_CN"),
                    "Skins without /lang/ directory must store emptyMap in LANG_MAP negative cache");
            Assert.assertTrue(langMap.get("volo-studio.zh_CN").isEmpty());
        } finally {
            if (startedBeanManager) {
                org.b3log.latke.ioc.BeanManager.close();
            }
        }
    }

    @Test
    public void dynamicHttpGzipCompressesEligibleHtmlAndJsonWhilePreservingRawFallback() throws Exception {
        Assert.assertTrue(PublicResourceHandler.acceptsGzip("br, gzip;q=0.8, deflate"));
        Assert.assertFalse(PublicResourceHandler.acceptsGzip("gzip;q=0, deflate"));
        Assert.assertFalse(PublicResourceHandler.acceptsGzip("br"));

        final String largeJsonValue = "Volo high-performance dynamic payload compression verification. ".repeat(40);

        // 1. Without Accept-Encoding: gzip -> remains genuine JsonRenderer / uncompressed bytes
        final DefaultFullHttpRequest plainHttp = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/search/instant");
        final DefaultFullHttpResponse plainRaw = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        try {
            final RequestContext plainCtx = new RequestContext(new Request(null, plainHttp), new Response(null, plainRaw));
            JsonResponses.json(plainCtx).renderData(largeJsonValue);
            Assert.assertTrue(plainCtx.getRenderer() instanceof JsonRenderer);
            JsonResponses.render(plainCtx);
            Assert.assertNull(plainCtx.getResponse().getHeader("Content-Encoding"));
        } finally {
            plainHttp.release();
            plainRaw.release();
        }

        // 2. With Accept-Encoding: gzip -> GZIP-compressed with Content-Encoding: gzip & Vary: Accept-Encoding
        final DefaultFullHttpRequest gzHttp = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/search/instant");
        gzHttp.headers().set(HttpHeaderNames.ACCEPT_ENCODING, "gzip, deflate, br");
        final DefaultFullHttpResponse gzRaw = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        try {
            final RequestContext gzCtx = new RequestContext(new Request(null, gzHttp), new Response(null, gzRaw));
            JsonResponses.json(gzCtx).renderData(largeJsonValue);
            JsonResponses.render(gzCtx);
            Assert.assertEquals(gzCtx.getResponse().getHeader("Content-Encoding"), "gzip");
            Assert.assertEquals(gzCtx.getResponse().getHeader("Vary"), "Accept-Encoding");
            final byte[] compressed = gzCtx.getResponse().getBytes();
            Assert.assertTrue(compressed.length < largeJsonValue.length() / 2,
                    "Expected significant GZIP size reduction, got " + compressed.length + " bytes");
            try (GZIPInputStream gzis = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                final JSONObject decoded = new JSONObject(new String(gzis.readAllBytes(), StandardCharsets.UTF_8));
                Assert.assertEquals(decoded.getString("data"), largeJsonValue);
            }
        } finally {
            gzHttp.release();
            gzRaw.release();
        }

        // 3. Direct HttpCompression.sendText HTML verification
        final String html = "<!DOCTYPE html><html><body>" + "<p>Hello Volo GZIP!</p>".repeat(80) + "</body></html>";
        final DefaultFullHttpRequest htmlHttp = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        htmlHttp.headers().set(HttpHeaderNames.ACCEPT_ENCODING, "gzip");
        final DefaultFullHttpResponse htmlRaw = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        try {
            final RequestContext htmlCtx = new RequestContext(new Request(null, htmlHttp), new Response(null, htmlRaw));
            HttpCompression.sendText(htmlCtx, html, "text/html; charset=UTF-8");
            Assert.assertEquals(htmlCtx.getResponse().getHeader("Content-Encoding"), "gzip");
            try (GZIPInputStream gzis = new GZIPInputStream(new ByteArrayInputStream(htmlCtx.getResponse().getBytes()))) {
                Assert.assertEquals(new String(gzis.readAllBytes(), StandardCharsets.UTF_8), html);
            }
        } finally {
            htmlHttp.release();
            htmlRaw.release();
        }
    }

    @Test
    public void emotionsShortCircuitSkipsUrlColonsWhileConvertingValidShortcodes() {
        final String markdownWithUrls = "Check https://github.com/Willh92/Volo-solo at 10:30:45 and http://localhost:8080/test";
        Assert.assertSame(Emotions.convert(markdownWithUrls), markdownWithUrls,
                "Markdown with URL/timestamp colons but no :shortcode: must return the exact input String without EmojiParser pass");
        final String converted = Emotions.convert("Great work :smile: ❤");
        Assert.assertFalse(converted.contains(":smile:"), "Valid :smile: shortcode must still be converted to Unicode emoji");
    }
}
