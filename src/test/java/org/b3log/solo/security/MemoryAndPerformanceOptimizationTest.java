/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.security;

import io.netty.buffer.ByteBufUtil;
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
import org.b3log.latke.model.Role;
import org.b3log.latke.model.User;
import org.b3log.latke.repository.Query;
import org.b3log.latke.repository.Transaction;
import org.b3log.solo.cache.ArticleCache;
import org.b3log.solo.cache.CommentCache;
import org.b3log.solo.cache.OptionCache;
import org.b3log.solo.cache.PublicViewCache;
import org.b3log.solo.cache.StatisticCache;
import org.b3log.solo.cache.UserCache;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Comment;
import org.b3log.solo.model.Option;
import org.b3log.solo.repository.ArchiveDateArticleRepository;
import org.b3log.solo.repository.ArchiveDateRepository;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.repository.ArticleRevisionRepository;
import org.b3log.solo.repository.CategoryRepository;
import org.b3log.solo.repository.CategoryTagRepository;
import org.b3log.solo.repository.CommentRepository;
import org.b3log.solo.repository.FollowRepository;
import org.b3log.solo.repository.LinkRepository;
import org.b3log.solo.repository.OptionRepository;
import org.b3log.solo.repository.PageRepository;
import org.b3log.solo.repository.PluginRepository;
import org.b3log.solo.repository.TagArticleRepository;
import org.b3log.solo.repository.TagRepository;
import org.b3log.solo.repository.UserRepository;
import org.b3log.solo.service.ArticleMgmtService;
import org.b3log.solo.service.BacklinkService;
import org.b3log.solo.service.BackupRestoreService;
import org.b3log.solo.service.ExportService;
import org.b3log.solo.service.InstantSearchService;
import org.b3log.solo.service.LocalRollingBackupService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.service.StatisticMgmtService;
import org.b3log.solo.service.StatisticQueryService;
import org.b3log.solo.service.SyndicationCacheService;
import org.b3log.solo.service.UserQueryService;
import org.b3log.solo.util.AttackTracker;
import org.b3log.solo.util.Markdowns;
import org.b3log.solo.web.PublicResourceHandler;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipFile;

public class MemoryAndPerformanceOptimizationTest {

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
        LatkeTestSupport.configure();
    }

    @Test
    public void writeBehindViewCountersOverlayRealTimeAndPreserveStatisticCategoryCache() throws Exception {
        Assert.assertNotEquals(Option.CATEGORY_C_VOLO_ANALYTICS, Option.CATEGORY_C_STATISTIC,
                "Analytics trend option must use an isolated category outside statistic");

        final OptionCache optionCache = new OptionCache();
        optionCache.putCategory(Option.CATEGORY_C_STATISTIC, new JSONObject()
                .put(Option.ID_C_STATISTIC_BLOG_VIEW_COUNT, "100"));
        optionCache.putOption(new JSONObject()
                .put(Keys.OBJECT_ID, Option.ID_C_STATISTIC_BLOG_VIEW_COUNT)
                .put(Option.OPTION_CATEGORY, Option.CATEGORY_C_STATISTIC)
                .put(Option.OPTION_VALUE, "105"));
        final JSONObject cachedStatCat = optionCache.getCategory(Option.CATEGORY_C_STATISTIC);
        Assert.assertNotNull(cachedStatCat, "putOption must update categoryCache in-place instead of evicting it");
        Assert.assertEquals(cachedStatCat.optString(Option.ID_C_STATISTIC_BLOG_VIEW_COUNT), "105");

        final AtomicInteger optionDbWrites = new AtomicInteger(0);
        final Map<String, JSONObject> optionRows = new LinkedHashMap<>();
        optionRows.put(Option.ID_C_STATISTIC_BLOG_VIEW_COUNT, new JSONObject()
                .put(Keys.OBJECT_ID, Option.ID_C_STATISTIC_BLOG_VIEW_COUNT)
                .put(Option.OPTION_CATEGORY, Option.CATEGORY_C_STATISTIC)
                .put(Option.OPTION_VALUE, "100"));

        final OptionRepository optionRepo = new OptionRepository() {
            @Override
            public Transaction beginTransaction() {
                return noopTx();
            }

            @Override
            public JSONObject get(final String id) {
                final JSONObject v = optionRows.get(id);
                return v == null ? null : new JSONObject(v.toString());
            }

            @Override
            public void update(final String id, final JSONObject option, final String... propertyNames) {
                optionDbWrites.incrementAndGet();
                optionRows.put(id, new JSONObject(option.toString()));
            }
        };

        final StatisticCache statisticCache = new StatisticCache();
        statisticCache.putCounts(12L, 34L);
        final StatisticMgmtService statMgmt = new StatisticMgmtService();
        setField(statMgmt, "optionRepository", optionRepo);
        setField(statMgmt, "statisticCache", statisticCache);

        final OptionQueryService optQuery = new OptionQueryService() {
            @Override
            public JSONObject getOptionById(final String optionId) {
                final JSONObject v = optionRows.get(optionId);
                return v == null ? null : new JSONObject(v.toString());
            }

            @Override
            public JSONObject getOptions(final String category) {
                if (Option.CATEGORY_C_STATISTIC.equals(category)) {
                    final JSONObject row = optionRows.get(Option.ID_C_STATISTIC_BLOG_VIEW_COUNT);
                    return new JSONObject().put(Option.ID_C_STATISTIC_BLOG_VIEW_COUNT,
                            row == null ? "0" : row.optString(Option.OPTION_VALUE, "0"));
                }
                return null;
            }
        };

        final StatisticQueryService statQuery = new StatisticQueryService();
        setField(statQuery, "optionQueryService", optQuery);
        setField(statQuery, "statisticCache", statisticCache);
        setField(statQuery, "statisticMgmtService", statMgmt);

        for (int i = 0; i < 5; i++) {
            final DefaultFullHttpRequest rawReq = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.GET, "/articles/post-" + i + ".html");
            rawReq.headers().set(HttpHeaderNames.USER_AGENT, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/130.0");
            final DefaultFullHttpResponse rawResp = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            final Response resp = new Response(null, rawResp);
            final RequestContext ctx = new RequestContext(new Request(null, rawReq), resp);
            statMgmt.incBlogViewCount(ctx, resp);
        }

        Assert.assertEquals(optionDbWrites.get(), 0, "incBlogViewCount must not write to DB synchronously");
        Assert.assertEquals(statQuery.getBlogViewCount(), 105L, "getBlogViewCount must overlay pending delta immediately");
        Assert.assertEquals(statQuery.getStatistic().optLong(Option.ID_C_STATISTIC_BLOG_VIEW_COUNT), 105L,
                "getStatistic must overlay pending delta immediately");

        statMgmt.flushPendingViewCounts();
        Assert.assertEquals(optionDbWrites.get(), 1, "flushPendingViewCounts must batch all deltas into a single DB write");
        Assert.assertEquals(optionRepo.get(Option.ID_C_STATISTIC_BLOG_VIEW_COUNT).optString(Option.OPTION_VALUE), "105");
        Assert.assertEquals(statMgmt.getPendingBlogViewDelta(), 0L);
        Assert.assertEquals(statQuery.getBlogViewCount(), 105L);

        // Verify ArticleMgmtService write-behind + ArticleCache in-place increment
        final AtomicInteger articleDbWrites = new AtomicInteger(0);
        final Map<String, JSONObject> articleRows = new LinkedHashMap<>();
        articleRows.put("art-1", new JSONObject()
                .put(Keys.OBJECT_ID, "art-1")
                .put(Article.ARTICLE_TITLE, "Perf Post")
                .put(Article.ARTICLE_PERMALINK, "/perf-post.html")
                .put(Article.ARTICLE_VIEW_COUNT, 40)
                .put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED));

        final ArticleCache articleCache = new ArticleCache();
        articleCache.putArticle(new JSONObject(articleRows.get("art-1").toString()));

        final ArticleRepository articleRepo = new ArticleRepository() {
            @Override
            public Transaction beginTransaction() {
                return noopTx();
            }

            @Override
            public JSONObject get(final String id) {
                final JSONObject row = articleRows.get(id);
                return row == null ? null : new JSONObject(row.toString());
            }

            @Override
            public JSONObject get(final Query query) {
                final JSONArray arr = new JSONArray();
                for (final JSONObject r : articleRows.values()) {
                    arr.put(new JSONObject(r.toString()));
                }
                return new JSONObject().put(Keys.RESULTS, arr);
            }

            @Override
            public void update(final String id, final JSONObject article, final String... propertyNames) {
                articleDbWrites.incrementAndGet();
                final JSONObject existing = articleRows.get(id);
                if (existing != null) {
                    existing.put(Article.ARTICLE_VIEW_COUNT, article.optInt(Article.ARTICLE_VIEW_COUNT));
                }
            }
        };

        final ArticleMgmtService articleMgmt = new ArticleMgmtService();
        setField(articleMgmt, "articleRepository", articleRepo);
        setField(articleMgmt, "articleCache", articleCache);

        articleMgmt.incViewCount("art-1");
        articleMgmt.incViewCount("art-1");
        articleMgmt.incViewCount("art-1");

        Assert.assertEquals(articleDbWrites.get(), 0, "incViewCount must not execute synchronous DB UPDATEs");
        Assert.assertEquals(articleCache.getArticle("art-1").optInt(Article.ARTICLE_VIEW_COUNT), 43,
                "ArticleCache must reflect incremented views in-place");

        articleMgmt.flushPendingViewCounts();
        Assert.assertEquals(articleDbWrites.get(), 1, "flushPendingViewCounts must coalesce 3 increments into 1 UPDATE");
        Assert.assertEquals(articleRows.get("art-1").optInt(Article.ARTICLE_VIEW_COUNT), 43);
        Assert.assertEquals(articleMgmt.getPendingViewDelta("art-1"), 0L);
    }

    @Test
    public void attackTrackerStores64BitFingerprintsWithoutRetainingRawUrlStrings() throws Exception {
        final AtomicLong now = new AtomicLong(1_000_000L);
        final AttackTracker tracker = new AttackTracker(now::get);

        // Verify Window class holds zero String or Collection fields (only primitive counters & long[] fingerprints)
        Class<?> windowClass = null;
        for (final Class<?> nested : AttackTracker.class.getDeclaredClasses()) {
            if ("Window".equals(nested.getSimpleName())) {
                windowClass = nested;
                break;
            }
        }
        Assert.assertNotNull(windowClass);
        for (final Field f : windowClass.getDeclaredFields()) {
            Assert.assertNotEquals(f.getType(), String.class, "Window must not retain raw String references");
            Assert.assertFalse(Iterable.class.isAssignableFrom(f.getType()),
                    "Window must use primitive fingerprint arrays instead of boxed Set<String>");
        }

        final String padding = "x".repeat(3000);
        AttackTracker.Trigger triggered = null;
        for (int i = 0; i < 40; i++) {
            final AttackTracker.Trigger t = tracker.record(
                    "203.0.113.77", "/missing-" + i + "-" + padding, 404, false);
            if (t != null) {
                triggered = t;
                break;
            }
        }
        Assert.assertNotNull(triggered, "Distinct missing 3KB URLs must still trigger 404 scan ban");
        Assert.assertTrue(triggered.code().contains("missing") || triggered.code().contains("404") || !triggered.code().isEmpty());

        AttackTracker.Trigger probeTrigger = null;
        for (int i = 0; i < 12; i++) {
            final AttackTracker.Trigger t = tracker.record(
                    "198.51.100.88", "/probe-" + i + ".php?" + padding, 0, false);
            if (t != null) {
                probeTrigger = t;
                break;
            }
        }
        Assert.assertNotNull(probeTrigger, "Distinct .php probe URLs must trigger probe ban");
    }

    @Test
    public void boundedCachesAndMarkdownLazyImageRenderingRespectLimits() throws Exception {
        Markdowns.clearCache();
        final String mdWithImgs = "![cover](https://cdn.example.com/a.png)\n\n"
                + "<img src=\"https://cdn.example.com/hero.jpg\" loading=\"eager\" alt=\"hero\"/>";
        final String html = Markdowns.toHTML(mdWithImgs);
        Assert.assertTrue(html.contains("loading=\"lazy\""), "Default Markdown images must receive loading=\"lazy\"");
        Assert.assertTrue(html.contains("decoding=\"async\""), "Markdown images must receive decoding=\"async\"");
        Assert.assertTrue(html.contains("loading=\"eager\""), "Explicit loading=\"eager\" must be preserved");

        // Verify >64 KB Markdown (e.g. 90 KB) is now cached and hits cleanly
        final String largeMd = "## Heading\n\n" + ("Paragraph content for large article. ".repeat(2500));
        Assert.assertTrue(largeMd.length() > 65_536 && largeMd.length() < 262_144);
        final String firstRender = Markdowns.toHTML(largeMd);
        final String secondRender = Markdowns.toHTML(largeMd);
        Assert.assertSame(firstRender, secondRender, "90 KB Markdown render must hit bounded MD_CACHE");

        // Verify UserCache LRU cap (512)
        final UserCache userCache = new UserCache();
        for (int i = 0; i < 600; i++) {
            userCache.putUser(new JSONObject()
                    .put(Keys.OBJECT_ID, "u-" + i)
                    .put(User.USER_EMAIL, "u" + i + "@example.com")
                    .put(User.USER_ROLE, Role.DEFAULT_ROLE));
        }
        Assert.assertTrue(userCache.size() <= UserCache.MAX_ENTRIES, "UserCache must enforce MAX_ENTRIES=512");
        Assert.assertNull(userCache.getUser("u-0"), "Oldest UserCache entry must be evicted");
        Assert.assertNotNull(userCache.getUser("u-599"), "Newest UserCache entry must remain present");

        // Verify CommentCache per-article list cache + automatic invalidation on comment write
        final CommentCache commentCache = new CommentCache();
        commentCache.putCommentsByArticleId("art-9", List.of(new JSONObject().put(Keys.OBJECT_ID, "c-1")));
        Assert.assertNotNull(commentCache.getCommentsByArticleId("art-9"));
        commentCache.putComment(new JSONObject()
                .put(Keys.OBJECT_ID, "c-2")
                .put(Comment.COMMENT_ON_ID, "art-9"));
        Assert.assertNull(commentCache.getCommentsByArticleId("art-9"),
                "Mutating a comment must invalidate cached article comment list");

        // Verify PublicViewCache single-flight & defensive cloning
        final PublicViewCache viewCache = new PublicViewCache();
        final AtomicInteger widgetLoads = new AtomicInteger(0);
        final List<JSONObject> w1 = viewCache.getOrComputeList("recentArticles", 0L, () -> {
            widgetLoads.incrementAndGet();
            return List.of(new JSONObject().put(Keys.OBJECT_ID, "a-1").put(Article.ARTICLE_TITLE, "Original"));
        });
        w1.get(0).put(Article.ARTICLE_TITLE, "MutatedByCaller");
        final List<JSONObject> w2 = viewCache.getOrComputeList("recentArticles", 0L, () -> {
            widgetLoads.incrementAndGet();
            return List.of();
        });
        Assert.assertEquals(widgetLoads.get(), 1);
        Assert.assertEquals(w2.get(0).optString(Article.ARTICLE_TITLE), "Original",
                "PublicViewCache must isolate callers from mutating cached widget JSONObjects");
    }

    @Test
    public void singleFlightLocksDeduplicateConcurrentBuildsAndInstantSearchReusesPlainText() throws Exception {
        final SyndicationCacheService syndicationCache = new SyndicationCacheService();
        final AtomicInteger xmlBuilds = new AtomicInteger(0);
        final int threads = 12;
        final CyclicBarrier barrier = new CyclicBarrier(threads);
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            final List<Future<SyndicationCacheService.Snapshot>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit((Callable<SyndicationCacheService.Snapshot>) () -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return syndicationCache.getOrCompute(SyndicationCacheService.KEY_ATOM, () -> {
                        Thread.sleep(40L);
                        return "<feed><count>" + xmlBuilds.incrementAndGet() + "</count></feed>";
                    });
                }));
            }
            for (final Future<SyndicationCacheService.Snapshot> f : futures) {
                Assert.assertEquals(f.get(5, TimeUnit.SECONDS).xml(), "<feed><count>1</count></feed>");
            }
            Assert.assertEquals(xmlBuilds.get(), 1, "12 concurrent cold misses must execute builder exactly once");

            // Stale fallback when rebuild fails after invalidation
            syndicationCache.invalidate();
            final SyndicationCacheService.Snapshot fallback = syndicationCache.getOrCompute(
                    SyndicationCacheService.KEY_ATOM,
                    () -> {
                        throw new IllegalStateException("Simulated transient DB error");
                    });
            Assert.assertEquals(fallback.xml(), "<feed><count>1</count></feed>",
                    "Transient rebuild failure must serve last good snapshot instead of 500");

            // BacklinkService concurrent single-flight test
            final AtomicInteger backlinkRepoScans = new AtomicInteger(0);
            final BacklinkService backlinkService = new BacklinkService();
            final JSONObject targetArticle = new JSONObject()
                    .put(Keys.OBJECT_ID, "target-1")
                    .put(Article.ARTICLE_TITLE, "Target")
                    .put(Article.ARTICLE_PERMALINK, "/target.html")
                    .put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED)
                    .put(Article.ARTICLE_UPDATED, 1_730_000_001_000L)
                    .put(Article.ARTICLE_CONTENT, "Hello");
            final JSONObject sourceArticle = new JSONObject()
                    .put(Keys.OBJECT_ID, "source-1")
                    .put(Article.ARTICLE_TITLE, "Source")
                    .put(Article.ARTICLE_PERMALINK, "/source.html")
                    .put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED)
                    .put(Article.ARTICLE_UPDATED, 1_730_000_002_000L)
                    .put(Article.ARTICLE_CONTENT, "See [Target](/target.html)");
            setField(backlinkService, "articleRepository", new ArticleRepository() {
                @Override
                public JSONObject get(final Query query) {
                    backlinkRepoScans.incrementAndGet();
                    try {
                        Thread.sleep(35L);
                    } catch (final InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    return new JSONObject().put(Keys.RESULTS,
                            new JSONArray().put(targetArticle).put(sourceArticle));
                }
            });

            final CyclicBarrier blBarrier = new CyclicBarrier(threads);
            final List<Future<List<BacklinkService.BacklinkItem>>> blFutures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                blFutures.add(pool.submit(() -> {
                    blBarrier.await(5, TimeUnit.SECONDS);
                    return backlinkService.getBacklinksForArticle(targetArticle);
                }));
            }
            for (final Future<List<BacklinkService.BacklinkItem>> f : blFutures) {
                final List<BacklinkService.BacklinkItem> items = f.get(5, TimeUnit.SECONDS);
                Assert.assertEquals(items.size(), 1);
                Assert.assertEquals(items.get(0).oId(), "source-1");
            }
            Assert.assertEquals(backlinkRepoScans.get(), 1,
                    "Concurrent BacklinkService cold misses must execute only 1 repository scan");
        } finally {
            pool.shutdownNow();
        }

        // InstantSearchService incremental plain-text reuse & zero :::lock leak
        final List<JSONObject> searchArticles = new ArrayList<>();
        searchArticles.add(new JSONObject()
                .put(Keys.OBJECT_ID, "s-1")
                .put(Article.ARTICLE_TITLE, "Java Memory Tuning")
                .put(Article.ARTICLE_PERMALINK, "/java-memory.html")
                .put(Article.ARTICLE_TAGS_REF, "JVM,Performance")
                .put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED)
                .put(Article.ARTICLE_UPDATED, 1000L)
                .put(Article.ARTICLE_CONTENT, "Use G1GC StringDeduplication.\n:::lock\nTOP_SECRET_LOCK_TOKEN\n:::"));
        searchArticles.add(new JSONObject()
                .put(Keys.OBJECT_ID, "s-2")
                .put(Article.ARTICLE_TITLE, "Netty Zero Copy")
                .put(Article.ARTICLE_PERMALINK, "/netty.html")
                .put(Article.ARTICLE_TAGS_REF, "Netty")
                .put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED)
                .put(Article.ARTICLE_UPDATED, 2000L)
                .put(Article.ARTICLE_CONTENT, "ByteRegion and HTTP 206 Partial Content."));

        final InstantSearchService searchService = new InstantSearchService();
        setField(searchService, "articleRepository", new ArticleRepository() {
            @Override
            public JSONObject get(final Query query) {
                final JSONArray arr = new JSONArray();
                for (final JSONObject a : searchArticles) {
                    arr.put(new JSONObject(a.toString()));
                }
                return new JSONObject().put(Keys.RESULTS, arr);
            }
        });
        setField(searchService, "categoryRepository", new CategoryRepository() {
            @Override public JSONObject get(final Query query) { return new JSONObject().put(Keys.RESULTS, new JSONArray()); }
        });
        setField(searchService, "categoryTagRepository", new CategoryTagRepository() {
            @Override public JSONObject get(final Query query) { return new JSONObject().put(Keys.RESULTS, new JSONArray()); }
        });
        setField(searchService, "tagRepository", new TagRepository() {
            @Override public JSONObject get(final Query query) { return new JSONObject().put(Keys.RESULTS, new JSONArray()); }
        });

        final JSONObject hit1 = searchService.search("StringDeduplication", "", "", 10);
        Assert.assertEquals(hit1.getJSONArray("items").length(), 1);
        final JSONObject lockProbe = searchService.search("TOP_SECRET_LOCK_TOKEN", "", "", 10);
        Assert.assertEquals(lockProbe.getJSONArray("items").length(), 0,
                "InstantSearchService must never index :::lock blocks");

        final Field ptCacheField = InstantSearchService.class.getDeclaredField("plainTextCache");
        ptCacheField.setAccessible(true);
        final Map<?, ?> ptCache = (Map<?, ?>) ptCacheField.get(searchService);
        final Object cachedEntryS2Before = ptCache.get("s-2");
        Assert.assertNotNull(cachedEntryS2Before);

        // Update only s-1 and invalidate; s-2's CachedPlainText instance must be reused identically
        searchArticles.get(0).put(Article.ARTICLE_UPDATED, 3000L)
                .put(Article.ARTICLE_CONTENT, "Updated JVM G1GC tuning guide.");
        searchService.invalidate();
        final JSONObject hitAfterUpdate = searchService.search("guide", "", "", 10);
        Assert.assertEquals(hitAfterUpdate.getJSONArray("items").length(), 1);
        Assert.assertSame(ptCache.get("s-2"), cachedEntryS2Before,
                "Unchanged articles must reuse cached plain-text extraction across snapshot rebuilds");
    }

    @Test
    public void streamingBackupRestoreRoundtripAndHttpByteRange206WorkCleanly() throws Exception {
        final Path tempDir = Files.createTempDirectory("volo-perf-stream-test-");
        try {
            // 1. Verify HTTP 206 Partial Content & 416 Range Not Satisfiable on PublicResourceHandler
            final Path mediaFile = tempDir.resolve("sample.mp3");
            final byte[] payload = new byte[256];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (i & 0x7f);
            }
            Files.write(mediaFile, payload);

            final DefaultFullHttpRequest rangeReq = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.GET, "/images/local/sample.mp3");
            rangeReq.headers().set(HttpHeaderNames.RANGE, "bytes=10-73");
            final DefaultFullHttpResponse rangeResp = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            final RequestContext rangeCtx = new RequestContext(new Request(null, rangeReq), new Response(null, rangeResp));

            final boolean handled206 = PublicResourceHandler.serveByteRangeIfPresent(
                    rangeCtx, mediaFile, payload.length, "W/\"1-100\"", "Wed, 05 Nov 2026 12:00:00 GMT",
                    1_730_000_000_000L, false);
            Assert.assertTrue(handled206);
            Assert.assertEquals(rangeResp.status().code(), 206);
            Assert.assertEquals(rangeResp.headers().get(HttpHeaderNames.CONTENT_RANGE), "bytes 10-73/256");
            Assert.assertEquals(rangeResp.headers().get(HttpHeaderNames.CONTENT_LENGTH), "64");
            Assert.assertEquals(rangeCtx.getResponse().getBytes(), Arrays.copyOfRange(payload, 10, 74));

            final DefaultFullHttpRequest badRangeReq = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.GET, "/images/local/sample.mp3");
            badRangeReq.headers().set(HttpHeaderNames.RANGE, "bytes=500-600");
            final DefaultFullHttpResponse badRangeResp = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            final RequestContext badRangeCtx = new RequestContext(
                    new Request(null, badRangeReq), new Response(null, badRangeResp));
            Assert.assertTrue(PublicResourceHandler.serveByteRangeIfPresent(
                    badRangeCtx, mediaFile, payload.length, "W/\"1-100\"", "Wed, 05 Nov 2026 12:00:00 GMT",
                    1_730_000_000_000L, false));
            Assert.assertEquals(badRangeResp.status().code(), 416);
            Assert.assertEquals(badRangeResp.headers().get(HttpHeaderNames.CONTENT_RANGE), "bytes */256");

            // 2. Verify streaming LocalRollingBackupService -> BackupRestoreService roundtrip
            final Path backupDir = tempDir.resolve("backups");
            Files.createDirectories(backupDir);
            try {
                final Map<String, JSONObject> options = new LinkedHashMap<>();
                options.put(Option.ID_C_LOCAL_BACKUP_DIR, new JSONObject()
                        .put(Keys.OBJECT_ID, Option.ID_C_LOCAL_BACKUP_DIR)
                        .put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE)
                        .put(Option.OPTION_VALUE, backupDir.toString()));
                options.put(Option.ID_C_LOCAL_BACKUP_KEEP_COUNT, new JSONObject()
                        .put(Keys.OBJECT_ID, Option.ID_C_LOCAL_BACKUP_KEEP_COUNT)
                        .put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE)
                        .put(Option.OPTION_VALUE, "5"));

                final Map<String, JSONObject> users = new LinkedHashMap<>();
                users.put("u-admin", new JSONObject()
                        .put(Keys.OBJECT_ID, "u-admin")
                        .put(User.USER_NAME, "admin")
                        .put(User.USER_EMAIL, "admin@example.com")
                        .put(User.USER_PASSWORD, "LIVE_HASH")
                        .put(User.USER_ROLE, Role.ADMIN_ROLE)
                        .put(User.USER_URL, "https://example.com"));

                final Map<String, JSONObject> articles = new LinkedHashMap<>();
                articles.put("art-stream-1", new JSONObject()
                        .put(Keys.OBJECT_ID, "art-stream-1")
                        .put(Article.ARTICLE_TITLE, "Streaming Backup Post")
                        .put(Article.ARTICLE_ABSTRACT, "Short abstract")
                        .put(Article.ARTICLE_CONTENT, "Body streamed without 4x heap copies.")
                        .put(Article.ARTICLE_AUTHOR_ID, "u-admin")
                        .put(Article.ARTICLE_TAGS_REF, "Java")
                        .put(Article.ARTICLE_PERMALINK, "/streaming-backup.html")
                        .put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED)
                        .put(Article.ARTICLE_CREATED, 1_730_000_000_000L)
                        .put(Article.ARTICLE_UPDATED, 1_730_000_000_000L));

                final ExportService exportService = new ExportService();
                wireMinimalRepos(exportService, options, users, articles);

                final LocalRollingBackupService rollingBackup = new LocalRollingBackupService();
                setField(rollingBackup, "exportService", exportService);
                setField(rollingBackup, "optionQueryService", new OptionQueryService() {
                    @Override
                    public JSONObject getPreference() {
                        return new JSONObject()
                                .put(Option.ID_C_LOCAL_BACKUP_DIR, backupDir.toString())
                                .put(Option.ID_C_LOCAL_BACKUP_KEEP_COUNT, 5);
                    }
                });

                final JSONObject backupMeta = rollingBackup.runBackup(false);
                final Path archivePath = backupDir.resolve(backupMeta.getString("name"));
                Assert.assertTrue(Files.isRegularFile(archivePath), "Streaming backup archive must be created on disk");

                try (ZipFile zf = new ZipFile(archivePath.toFile(), StandardCharsets.UTF_8)) {
                    Assert.assertNotNull(zf.getEntry("data.json"), "Backup ZIP must contain streamed data.json");
                    final boolean hasPostMd = zf.stream().anyMatch(e -> e.getName().startsWith("posts/") && e.getName().endsWith(".md"));
                    Assert.assertTrue(hasPostMd, "Backup ZIP must contain streamed Hexo Markdown entry");
                }

                // Clear articles and restore from the streamed backup archive
                articles.clear();
                final BackupRestoreService restoreService = new BackupRestoreService();
                wireMinimalRepos(restoreService, options, users, articles);
                setField(restoreService, "localRollingBackupService", rollingBackup);
                final JSONObject restoreResult = restoreService.restoreFromBackupZip(archivePath, false);
                Assert.assertTrue(restoreResult.optString("preRestoreBackup").contains("volo-backup-"),
                        "Restore must create pre-restore snapshot before mutating tables");
                Assert.assertTrue(articles.containsKey("art-stream-1"),
                        "Streamed data.json must restore all articles cleanly");
                Assert.assertEquals(articles.get("art-stream-1").optString(Article.ARTICLE_TITLE), "Streaming Backup Post");
            } finally {
                deleteRecursively(backupDir);
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void wireMinimalRepos(final Object target,
                                         final Map<String, JSONObject> options,
                                         final Map<String, JSONObject> users,
                                         final Map<String, JSONObject> articles) throws Exception {
        final JSONObject emptyResult = new JSONObject().put(Keys.RESULTS, new JSONArray());
        setField(target, "optionRepository", new OptionRepository() {
            @Override public Transaction beginTransaction() { return noopTx(); }
            @Override public JSONObject get(final String id) { return options.get(id); }
            @Override public JSONObject get(final Query q) { return toResults(options); }
            @Override public String add(final JSONObject o) { options.put(o.getString(Keys.OBJECT_ID), new JSONObject(o.toString())); return o.getString(Keys.OBJECT_ID); }
            @Override public void remove(final String id) { options.remove(id); }
        });
        setField(target, "userRepository", new UserRepository() {
            @Override public JSONObject get(final String id) { return users.get(id); }
            @Override public JSONObject get(final Query q) { return toResults(users); }
            @Override public String add(final JSONObject o) { users.put(o.getString(Keys.OBJECT_ID), new JSONObject(o.toString())); return o.getString(Keys.OBJECT_ID); }
            @Override public void remove(final String id) { users.remove(id); }
        });
        setField(target, "articleRepository", new ArticleRepository() {
            @Override public JSONObject get(final String id) { return articles.get(id); }
            @Override public JSONObject get(final Query q) { return toResults(articles); }
            @Override public String add(final JSONObject o) { articles.put(o.getString(Keys.OBJECT_ID), new JSONObject(o.toString())); return o.getString(Keys.OBJECT_ID); }
            @Override public void remove(final String id) { articles.remove(id); }
        });
        setField(target, "followRepository", new FollowRepository() {
            @Override public JSONObject get(final Query q) { return emptyResult; }
            @Override public String add(final JSONObject o) { return o.optString(Keys.OBJECT_ID); }
            @Override public void remove(final String id) { }
        });
        setField(target, "articleRevisionRepository", new ArticleRevisionRepository() {
            @Override public JSONObject get(final Query q) { return emptyResult; }
            @Override public String add(final JSONObject o) { return o.optString(Keys.OBJECT_ID); }
            @Override public void remove(final String id) { }
        });
        setField(target, "archiveDateRepository", new ArchiveDateRepository() {
            @Override public JSONObject get(final Query q) { return emptyResult; }
            @Override public String add(final JSONObject o) { return o.optString(Keys.OBJECT_ID); }
            @Override public void remove(final String id) { }
        });
        setField(target, "archiveDateArticleRepository", new ArchiveDateArticleRepository() {
            @Override public JSONObject get(final Query q) { return emptyResult; }
            @Override public String add(final JSONObject o) { return o.optString(Keys.OBJECT_ID); }
            @Override public void remove(final String id) { }
        });
        setField(target, "categoryRepository", new CategoryRepository() {
            @Override public JSONObject get(final Query q) { return emptyResult; }
            @Override public String add(final JSONObject o) { return o.optString(Keys.OBJECT_ID); }
            @Override public void remove(final String id) { }
        });
        setField(target, "categoryTagRepository", new CategoryTagRepository() {
            @Override public JSONObject get(final Query q) { return emptyResult; }
            @Override public String add(final JSONObject o) { return o.optString(Keys.OBJECT_ID); }
            @Override public void remove(final String id) { }
        });
        setField(target, "commentRepository", new CommentRepository() {
            @Override public JSONObject get(final Query q) { return emptyResult; }
            @Override public String add(final JSONObject o) { return o.optString(Keys.OBJECT_ID); }
            @Override public void remove(final String id) { }
        });
        setField(target, "linkRepository", new LinkRepository() {
            @Override public JSONObject get(final Query q) { return emptyResult; }
            @Override public String add(final JSONObject o) { return o.optString(Keys.OBJECT_ID); }
            @Override public void remove(final String id) { }
        });
        setField(target, "pageRepository", new PageRepository() {
            @Override public JSONObject get(final Query q) { return emptyResult; }
            @Override public String add(final JSONObject o) { return o.optString(Keys.OBJECT_ID); }
            @Override public void remove(final String id) { }
        });
        setField(target, "pluginRepository", new PluginRepository() {
            @Override public JSONObject get(final Query q) { return emptyResult; }
            @Override public String add(final JSONObject o) { return o.optString(Keys.OBJECT_ID); }
            @Override public void remove(final String id) { }
        });
        setField(target, "tagRepository", new TagRepository() {
            @Override public JSONObject get(final Query q) { return emptyResult; }
            @Override public String add(final JSONObject o) { return o.optString(Keys.OBJECT_ID); }
            @Override public void remove(final String id) { }
        });
        setField(target, "tagArticleRepository", new TagArticleRepository() {
            @Override public JSONObject get(final Query q) { return emptyResult; }
            @Override public String add(final JSONObject o) { return o.optString(Keys.OBJECT_ID); }
            @Override public void remove(final String id) { }
        });
    }

    private static JSONObject toResults(final Map<String, JSONObject> map) {
        final JSONArray arr = new JSONArray();
        for (final JSONObject row : map.values()) {
            arr.put(new JSONObject(row.toString()));
        }
        return new JSONObject().put(Keys.RESULTS, arr);
    }

    private static Transaction noopTx() {
        return new Transaction() {
            private boolean active = true;
            @Override public String getId() { return "tx"; }
            @Override public void commit() { active = false; }
            @Override public void rollback() { active = false; }
            @Override public boolean isActive() { return active; }
        };
    }

    private static void setField(final Object target, final String name, final Object value) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                final Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (final NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void deleteRecursively(final Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            for (final Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
