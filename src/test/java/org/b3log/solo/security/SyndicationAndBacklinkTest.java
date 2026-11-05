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
import org.b3log.latke.http.Request;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.http.Response;
import org.b3log.latke.repository.Query;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Option;
import org.b3log.solo.model.Page;
import org.b3log.solo.processor.SitemapProcessor;
import org.b3log.solo.repository.ArchiveDateRepository;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.repository.PageRepository;
import org.b3log.solo.repository.TagRepository;
import org.b3log.solo.service.BacklinkService;
import org.b3log.solo.service.IndexNowService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.service.SeriesAndBookshelfService;
import org.b3log.solo.service.SyndicationCacheService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class SyndicationAndBacklinkTest {

    @BeforeClass
    public void configure() throws Exception {
        LatkeTestSupport.configure();
        final var local = new java.util.Properties();
        local.setProperty("runtimeDatabase", "NONE");
        org.b3log.latke.Latkes.setLocalProps(local);
    }

    @AfterClass
    public void restore() throws Exception {
        LatkeTestSupport.configure();
    }

    @Test
    public void syndicationCacheServes304OnMatchingEtagAndInvalidatesCleanly() throws Exception {
        final SyndicationCacheService cache = new SyndicationCacheService();
        final AtomicInteger builds = new AtomicInteger(0);

        final SyndicationCacheService.Snapshot s1 = cache.getOrCompute(
                SyndicationCacheService.KEY_SITEMAP,
                () -> "<urlset><n>" + builds.incrementAndGet() + "</n></urlset>");
        final SyndicationCacheService.Snapshot s2 = cache.getOrCompute(
                SyndicationCacheService.KEY_SITEMAP,
                () -> "<urlset><n>" + builds.incrementAndGet() + "</n></urlset>");
        Assert.assertEquals(builds.get(), 1, "Second read before invalidation must hit cached snapshot");
        Assert.assertEquals(s1.etag(), s2.etag());

        final DefaultFullHttpRequest rawReq = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/sitemap.xml");
        rawReq.headers().set(HttpHeaderNames.IF_NONE_MATCH, "W/" + s1.etag());
        final DefaultFullHttpResponse rawResp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        final RequestContext ctx = new RequestContext(new Request(null, rawReq), new Response(null, rawResp));

        Assert.assertTrue(SyndicationCacheService.tryServeNotModified(ctx, s1, "text/xml; charset=UTF-8"));
        Assert.assertEquals(rawResp.status().code(), 304);
        Assert.assertEquals(rawResp.headers().get(HttpHeaderNames.ETAG), s1.etag());

        cache.invalidate();
        final SyndicationCacheService.Snapshot s3 = cache.getOrCompute(
                SyndicationCacheService.KEY_SITEMAP,
                () -> "<urlset><n>" + builds.incrementAndGet() + "</n></urlset>");
        Assert.assertEquals(builds.get(), 2, "Read after invalidate() must recompute fresh XML");
        Assert.assertNotEquals(s1.etag(), s3.etag());
    }

    @Test
    public void sitemapIncludesHomeAndBooksWhileExcludingExternalNavAndProtectedPosts() throws Exception {
        final SitemapProcessor processor = new SitemapProcessor();
        setField(processor, "articleRepository", new ArticleRepository() {
            @Override
            public JSONObject get(final Query query) {
                return new JSONObject().put(Keys.RESULTS, new JSONArray()
                        .put(new JSONObject()
                                .put(Article.ARTICLE_PERMALINK, "/articles/public-post.html")
                                .put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED)
                                .put(Article.ARTICLE_VIEW_PWD, "")
                                .put(Article.ARTICLE_UPDATED, 1_730_000_000_000L))
                        .put(new JSONObject()
                                .put(Article.ARTICLE_PERMALINK, "/articles/secret-post.html")
                                .put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED)
                                .put(Article.ARTICLE_VIEW_PWD, "pwd123")
                                .put(Article.ARTICLE_UPDATED, 1_730_000_000_000L))
                        .put(new JSONObject()
                                .put(Article.ARTICLE_PERMALINK, "/articles/draft-post.html")
                                .put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_DRAFT)
                                .put(Article.ARTICLE_VIEW_PWD, "")));
            }
        });
        setField(processor, "pageRepository", new PageRepository() {
            @Override
            public JSONObject get(final Query query) {
                return new JSONObject().put(Keys.RESULTS, new JSONArray()
                        .put(new JSONObject().put(Page.PAGE_PERMALINK, "/about.html"))
                        .put(new JSONObject().put(Page.PAGE_PERMALINK, "https://github.com/Willh92/Volo-solo"))
                        .put(new JSONObject().put(Page.PAGE_PERMALINK, "javascript:alert(1)")));
            }
        });
        setField(processor, "seriesAndBookshelfService", new SeriesAndBookshelfService() {
            @Override
            public JSONArray getAllBooks() {
                return new JSONArray().put(new JSONObject().put("id", "java-guide"));
            }
            @Override
            public JSONArray getBookToc(final String bookId) {
                return new JSONArray()
                        .put(new JSONObject().put("url", "/books/java-guide/ch1.html"))
                        .put(new JSONObject().put("url", "https://external.example.com/ch2.html"));
            }
        });
        setField(processor, "tagRepository", new TagRepository() {
            @Override
            public JSONObject get(final Query query) {
                return new JSONObject().put(Keys.RESULTS, new JSONArray());
            }
        });
        setField(processor, "archiveDateRepository", new ArchiveDateRepository() {
            @Override
            public JSONObject get(final Query query) {
                return new JSONObject().put(Keys.RESULTS, new JSONArray());
            }
        });

        final Method buildMethod = SitemapProcessor.class.getDeclaredMethod("buildSitemapXml");
        buildMethod.setAccessible(true);
        final String xml = (String) buildMethod.invoke(processor);

        Assert.assertTrue(xml.contains("/</loc>"), "Sitemap must include blog homepage");
        Assert.assertTrue(xml.contains("/articles/public-post.html</loc>"));
        Assert.assertTrue(xml.contains("/about.html</loc>"));
        Assert.assertTrue(xml.contains("/books/java-guide/ch1.html</loc>"), "Sitemap must include local book chapter URLs");
        Assert.assertFalse(xml.contains("secret-post.html"), "Password-protected articles must never appear in sitemap");
        Assert.assertFalse(xml.contains("draft-post.html"), "Draft articles must never appear in sitemap");
        Assert.assertFalse(xml.contains("github.com"), "External navigation links must never appear in sitemap");
        Assert.assertFalse(xml.contains("external.example.com"), "External book URLs must never appear in sitemap");
    }

    @Test
    public void indexNowKeyVerificationAndBacklinkZeroLeakage() throws Exception {
        // 1. IndexNow verification checks format, enabled flag, and constant-time key equality
        final IndexNowService indexNow = new IndexNowService();
        final JSONObject pref = new JSONObject()
                .put(Option.ID_C_INDEXNOW_ENABLED, "true")
                .put(Option.ID_C_INDEXNOW_KEY, "a1b2c3d4e5f60718293a4b5c6d7e8f90");
        setField(indexNow, "optionQueryService", new OptionQueryService() {
            @Override
            public JSONObject getPreference() {
                return pref;
            }
        });
        Assert.assertTrue(indexNow.verifyKeyRequest("a1b2c3d4e5f60718293a4b5c6d7e8f90"));
        Assert.assertFalse(indexNow.verifyKeyRequest("wrong-key-value-12345678"));
        Assert.assertFalse(indexNow.verifyKeyRequest("short"));
        pref.put(Option.ID_C_INDEXNOW_ENABLED, "false");
        Assert.assertFalse(indexNow.verifyKeyRequest("a1b2c3d4e5f60718293a4b5c6d7e8f90"));

        // 2. BacklinkService indexes real internal links while stripping :::lock, code blocks, drafts, and pwd posts
        final JSONObject target = new JSONObject()
                .put(Keys.OBJECT_ID, "art-target")
                .put(Article.ARTICLE_TITLE, "Target Architecture")
                .put(Article.ARTICLE_PERMALINK, "/articles/target.html")
                .put(Article.ARTICLE_CONTENT, "Base article body.")
                .put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED)
                .put(Article.ARTICLE_VIEW_PWD, "")
                .put(Article.ARTICLE_UPDATED, 1_730_000_000_000L);

        final JSONObject validSource = new JSONObject()
                .put(Keys.OBJECT_ID, "art-source-ok")
                .put(Article.ARTICLE_TITLE, "Referring Note <script>alert(1)</script>")
                .put(Article.ARTICLE_PERMALINK, "/articles/source-ok.html")
                .put(Article.ARTICLE_CONTENT, "See [Target Architecture](/articles/target.html#sec1) for details.")
                .put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED)
                .put(Article.ARTICLE_VIEW_PWD, "")
                .put(Article.ARTICLE_UPDATED, 1_730_500_000_000L);

        final JSONObject lockedAndCodeOnlySource = new JSONObject()
                .put(Keys.OBJECT_ID, "art-source-hidden")
                .put(Article.ARTICLE_TITLE, "Hidden Reference")
                .put(Article.ARTICLE_PERMALINK, "/articles/source-hidden.html")
                .put(Article.ARTICLE_CONTENT,
                        ":::lock type=\"pwd\" pwd=\"9999\"\nSecret link [Target](/articles/target.html)\n:::\n"
                                + "```markdown\n[CodeFence](/articles/target.html)\n```\n"
                                + "Inline `[Inline](/articles/target.html)` only.")
                .put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED)
                .put(Article.ARTICLE_VIEW_PWD, "")
                .put(Article.ARTICLE_UPDATED, 1_730_600_000_000L);

        final JSONObject pwdProtectedSource = new JSONObject()
                .put(Keys.OBJECT_ID, "art-source-pwd")
                .put(Article.ARTICLE_TITLE, "Private Diary")
                .put(Article.ARTICLE_PERMALINK, "/articles/private.html")
                .put(Article.ARTICLE_CONTENT, "Private citation [Target](/articles/target.html)")
                .put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED)
                .put(Article.ARTICLE_VIEW_PWD, "secret")
                .put(Article.ARTICLE_UPDATED, 1_730_700_000_000L);

        final BacklinkService backlinkService = new BacklinkService();
        setField(backlinkService, "articleRepository", new ArticleRepository() {
            @Override
            public JSONObject get(final Query query) {
                return new JSONObject().put(Keys.RESULTS, new JSONArray()
                        .put(target)
                        .put(validSource)
                        .put(lockedAndCodeOnlySource)
                        .put(pwdProtectedSource));
            }
        });

        final List<BacklinkService.BacklinkItem> backlinks = backlinkService.getBacklinksForArticle(target);
        Assert.assertEquals(backlinks.size(), 1, "Only public non-locked non-code links may produce backlinks");
        Assert.assertEquals(backlinks.get(0).oId(), "art-source-ok");

        final String html = backlinkService.renderBacklinksHtml(target);
        Assert.assertTrue(html.contains("class=\"volo-backlinks-box\""));
        Assert.assertFalse(html.contains("<script>"), "Backlink titles must be HTML-escaped");
        Assert.assertTrue(html.contains("&lt;script&gt;"));
        Assert.assertEquals(backlinkService.getBacklinksForArticle(pwdProtectedSource).size(), 0,
                "Password-protected target articles must never expose backlinks");
    }

    private static void setField(final Object target, final String name, final Object value) throws Exception {
        final Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
