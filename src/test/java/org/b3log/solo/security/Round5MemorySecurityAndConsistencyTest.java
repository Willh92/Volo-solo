/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.security;

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.http.Request;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.http.Response;
import org.b3log.latke.http.renderer.JsonRenderer;
import org.b3log.latke.model.Pagination;
import org.b3log.latke.repository.Filter;
import org.b3log.latke.repository.Projection;
import org.b3log.latke.repository.PropertyFilter;
import org.b3log.latke.repository.Query;
import org.b3log.latke.repository.RepositoryException;
import org.b3log.latke.repository.Transaction;
import org.b3log.latke.service.LangPropsService;
import org.b3log.latke.service.ServiceException;
import org.b3log.solo.cache.CommentCache;
import org.b3log.solo.cache.OptionCache;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Comment;
import org.b3log.solo.model.Common;
import org.b3log.solo.model.Option;
import org.b3log.solo.model.Page;
import org.b3log.solo.processor.CommentProcessor;
import org.b3log.solo.processor.console.PreferenceConsole;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.repository.CommentRepository;
import org.b3log.solo.repository.OptionRepository;
import org.b3log.solo.repository.PageRepository;
import org.b3log.solo.service.BacklinkService;
import org.b3log.solo.service.BackupRestoreService;
import org.b3log.solo.service.CommentQueryService;
import org.b3log.solo.service.InstantSearchService;
import org.b3log.solo.service.OptionMgmtService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.service.PageMgmtService;
import org.b3log.solo.service.RemoteBackupSyncService;
import org.b3log.solo.util.MonotonicIds;
import org.b3log.solo.web.NettyFileSender;
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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class Round5MemorySecurityAndConsistencyTest {

    @BeforeClass
    public void configure() throws Exception {
        LatkeTestSupport.configure();
        Latkes.setRuntimeMode(Latkes.RuntimeMode.PRODUCTION);
        final Properties local = new Properties();
        local.setProperty("runtimeDatabase", "NONE");
        local.setProperty("cookieHttpOnly", "true");
        local.setProperty("loginTrustedProxies", "127.0.0.1,::1");
        Latkes.setLocalProps(local);
    }

    @AfterClass
    public void restore() throws Exception {
        LatkeTestSupport.configure();
    }

    @Test
    public void nettyFileSenderStreamsExactByteSliceAndInvokesOnCompleteEvenOnFailure() throws Exception {
        final Path tempFile = Files.createTempFile("volo-r5-slice-", ".bin");
        try {
            final byte[] raw = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.US_ASCII);
            Files.write(tempFile, raw);

            final DefaultFullHttpRequest nettyReq = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/download");
            final DefaultFullHttpResponse nettyResp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.PARTIAL_CONTENT);
            final AtomicReference<byte[]> sentBytes = new AtomicReference<>();
            final AtomicInteger completedCount = new AtomicInteger();
            try {
                final Request req = new Request(null, nettyReq);
                final Response resp = new Response(null, nettyResp) {
                    @Override
                    public void sendBytes(final byte[] bytes) {
                        sentBytes.set(bytes);
                    }
                };
                final RequestContext ctx = new RequestContext(req, resp);
                NettyFileSender.sendFile(ctx, tempFile, 10L, 26L, completedCount::incrementAndGet);

                Assert.assertEquals(completedCount.get(), 1, "onComplete must run exactly once after slice send");
                Assert.assertNotNull(sentBytes.get());
                Assert.assertEquals(new String(sentBytes.get(), StandardCharsets.US_ASCII), "ABCDEFGHIJKLMNOPQRSTUVWXYZ");
                Assert.assertEquals(nettyResp.headers().get("Content-Length"), "26");
            } finally {
                nettyReq.release();
                nettyResp.release();
            }

            // Out-of-bounds slice must throw IOException AND still run onComplete cleanup
            final DefaultFullHttpRequest errReq = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/download");
            final DefaultFullHttpResponse errResp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            final AtomicBoolean cleanupCalled = new AtomicBoolean(false);
            try {
                final RequestContext errCtx = new RequestContext(new Request(null, errReq), new Response(null, errResp));
                Assert.expectThrows(IOException.class, () ->
                        NettyFileSender.sendFile(errCtx, tempFile, 10L, 10_000L, () -> cleanupCalled.set(true)));
                Assert.assertTrue(cleanupCalled.get(), "onComplete cleanup callback must run even when slice bounds fail");
            } finally {
                errReq.release();
                errResp.release();
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void commentCacheSeparatesSingleAndListWeightBudgetsWithoutSelfEviction() {
        final CommentCache cache = new CommentCache();
        final String largeBody = "c".repeat(45_000);

        // Fill single-comment cache close to MAX_SINGLE_CHARS (500K)
        for (int i = 0; i < 11; i++) {
            cache.putComment(new JSONObject()
                    .put(Keys.OBJECT_ID, "single-" + i)
                    .put(Comment.COMMENT_ON_ID, "other-art-" + i)
                    .put(Comment.COMMENT_CONTENT, largeBody));
        }
        Assert.assertTrue(cache.size() >= 8);

        // Insert multiple per-article comment lists: independent listTotalChars budget (750K) must retain all of them!
        for (int a = 0; a < 5; a++) {
            final List<JSONObject> list = new ArrayList<>();
            for (int c = 0; c < 3; c++) {
                list.add(new JSONObject()
                        .put(Keys.OBJECT_ID, "art-" + a + "-c-" + c)
                        .put(Comment.COMMENT_ON_ID, "target-art-" + a)
                        .put(Comment.COMMENT_CONTENT, "x".repeat(20_000)));
            }
            cache.putCommentsByArticleId("target-art-" + a, list);
        }

        for (int a = 0; a < 5; a++) {
            final List<JSONObject> hit = cache.getCommentsByArticleId("target-art-" + a);
            Assert.assertNotNull(hit, "Article comment list target-art-" + a + " must not be self-evicted by single-comment weight");
            Assert.assertEquals(hit.size(), 3);
        }
        Assert.assertTrue(cache.getTotalChars() <= CommentCache.MAX_TOTAL_CHARS);
    }

    @Test
    public void optionCacheCategoryMigrationRemoveScanAndOrphanDocumentChunkCleanup() throws Exception {
        final OptionCache optionCache = new OptionCache();
        optionCache.putCategory(Option.CATEGORY_C_PREFERENCE, new JSONObject()
                .put("blogTitle", "Volo")
                .put(Option.ID_C_VOLO_REMOTE_BACKUP, "{\"webdavPassword\":\"legacy-secret\"}"));
        optionCache.putOption(new JSONObject()
                .put(Keys.OBJECT_ID, Option.ID_C_VOLO_REMOTE_BACKUP)
                .put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE)
                .put(Option.OPTION_VALUE, "{\"webdavPassword\":\"legacy-secret\"}"));

        // 1. Migrating voloRemoteBackup from "preference" -> "remoteBackup" must immediately purge it from "preference" snapshot
        optionCache.putOption(new JSONObject()
                .put(Keys.OBJECT_ID, Option.ID_C_VOLO_REMOTE_BACKUP)
                .put(Option.OPTION_CATEGORY, Option.CATEGORY_C_REMOTE_BACKUP)
                .put(Option.OPTION_VALUE, "{\"webdavPassword\":\"migrated-secret\"}"));

        final JSONObject prefAfterMigration = optionCache.getCategory(Option.CATEGORY_C_PREFERENCE);
        Assert.assertNotNull(prefAfterMigration);
        Assert.assertFalse(prefAfterMigration.has(Option.ID_C_VOLO_REMOTE_BACKUP),
                "Category migration must evict option key from old category snapshot");

        // 2. removeOption must remove key from cached category snapshot even when single-ID cache never loaded that option
        final OptionCache categoryOnlyCache = new OptionCache();
        categoryOnlyCache.putCategory(Option.CATEGORY_C_PREFERENCE, new JSONObject()
                .put("blogTitle", "Volo")
                .put("orphanPrefKey", "staleValue"));
        categoryOnlyCache.removeOption("orphanPrefKey");
        Assert.assertFalse(categoryOnlyCache.getCategory(Option.CATEGORY_C_PREFERENCE).has("orphanPrefKey"),
                "removeOption must purge key from categoryCache even without a single-option cache hit");

        // 3. OptionMgmtService.removeOption cleans up <id>.<generation>.0..count-1 chunk rows atomically
        final Map<String, JSONObject> dbRows = new LinkedHashMap<>();
        final String gen = "0123456789abcdef0123456789abcdef";
        final String rootId = "voloWebhooks";
        dbRows.put(rootId, new JSONObject()
                .put(Keys.OBJECT_ID, rootId)
                .put(Option.OPTION_CATEGORY, "webhook")
                .put(Option.OPTION_VALUE, "{\"storage\":\"chunks\",\"generation\":\"" + gen + "\",\"count\":3}"));
        for (int i = 0; i < 3; i++) {
            final String chunkId = rootId + "." + gen + "." + i;
            dbRows.put(chunkId, new JSONObject()
                    .put(Keys.OBJECT_ID, chunkId)
                    .put(Option.OPTION_CATEGORY, OptionCache.CATEGORY_DOCUMENT_CHUNK)
                    .put(Option.OPTION_VALUE, "part-" + i));
        }

        final OptionMgmtService optionMgmt = new OptionMgmtService();
        setField(optionMgmt, "optionRepository", new OptionRepository() {
            @Override public Transaction beginTransaction() { return noopTx(); }
            @Override public JSONObject get(final String id) { return dbRows.get(id); }
            @Override public void remove(final String id) { dbRows.remove(id); }
        });
        optionMgmt.removeOption(rootId);
        Assert.assertTrue(dbRows.isEmpty(),
                "Removing chunked option root must also remove all voloDocumentChunk rows, remaining: " + dbRows.keySet());
    }

    @Test
    public void preferenceConsoleStripsRemoteBackupSecretsAndBackupProtectsWebhooks() throws Exception {
        // 1. BackupRestoreService.isProtectedOptionId protects voloWebhooks & chunked voloWebhooks.*
        Assert.assertTrue(BackupRestoreService.isProtectedOptionId(Option.ID_C_VOLO_WEBHOOKS));
        Assert.assertTrue(BackupRestoreService.isProtectedOptionId(Option.ID_C_VOLO_WEBHOOKS + ".0123456789abcdef0123456789abcdef.0"));
        Assert.assertTrue(BackupRestoreService.isProtectedOptionId(Option.ID_C_VOLO_REMOTE_BACKUP));
        Assert.assertFalse(BackupRestoreService.isProtectedOptionId(Option.ID_C_BLOG_TITLE));

        // 2. RemoteBackupSyncService stores config in dedicated Option.CATEGORY_C_REMOTE_BACKUP ("remoteBackup")
        final AtomicReference<String> savedCategory = new AtomicReference<>();
        final RemoteBackupSyncService remoteBackupService = new RemoteBackupSyncService();
        setField(remoteBackupService, "optionMgmtService", new OptionMgmtService() {
            @Override
            public synchronized void storeDocument(final String id, final String category, final String value) {
                savedCategory.set(category);
            }
        });
        remoteBackupService.updateConfig(new JSONObject()
                .put("enabled", false)
                .put("protocol", "webdav")
                .put("webdavUrl", "https://dav.example.com/backups")
                .put("webdavUser", "backup-bot")
                .put("webdavPassword", "super-secret-dav-pwd"));
        Assert.assertEquals(savedCategory.get(), Option.CATEGORY_C_REMOTE_BACKUP);

        // 3. PreferenceConsole.getPreference strips legacy voloRemoteBackup & voloRemoteBackup.* even if still present in preference snapshot
        final PreferenceConsole prefConsole = new PreferenceConsole();
        setField(prefConsole, "optionQueryService", new OptionQueryService() {
            @Override
            public JSONObject getPreference() {
                return new JSONObject()
                        .put(Option.ID_C_BLOG_TITLE, "Volo Secure Blog")
                        .put(Option.ID_C_VOLO_REMOTE_BACKUP, "{\"webdavPassword\":\"TOP_SECRET_DAV\",\"s3SecretKey\":\"TOP_SECRET_S3\"}")
                        .put(Option.ID_C_VOLO_REMOTE_BACKUP + ".legacy", "LEAK");
            }

            @Override
            public JSONObject getOptionById(final String optionId) {
                return null;
            }
        });

        final DefaultFullHttpRequest rawReq = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/console/preference/");
        final DefaultFullHttpResponse rawResp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        try {
            final RequestContext ctx = new RequestContext(new Request(null, rawReq), new Response(null, rawResp));
            prefConsole.getPreference(ctx);
            final JsonRenderer renderer = (JsonRenderer) ctx.getRenderer();
            final JSONObject payload = renderer.getJSONObject();
            Assert.assertTrue(payload.getBoolean(Common.STATUS_CODE));
            final JSONObject pref = payload.getJSONObject(Option.CATEGORY_C_PREFERENCE);
            Assert.assertFalse(pref.has(Option.ID_C_VOLO_REMOTE_BACKUP),
                    "GET /console/preference/ must never expose voloRemoteBackup secrets");
            Assert.assertFalse(pref.toString().contains("TOP_SECRET_DAV"));
            Assert.assertFalse(pref.toString().contains("TOP_SECRET_S3"));
        } finally {
            rawReq.release();
            rawResp.release();
        }
    }

    @Test
    public void instantSearchTwoPhaseIncrementalBuildAndBacklinkPercentDecodedPermalink() throws Exception {
        // Part A: InstantSearchService two-phase build fetches articleContent ONLY for cache-missed/updated articles
        final Map<String, JSONObject> articles = new LinkedHashMap<>();
        articles.put("a-1", new JSONObject()
                .put(Keys.OBJECT_ID, "a-1")
                .put(Article.ARTICLE_TITLE, "First Alpha Guide")
                .put(Article.ARTICLE_PERMALINK, "/alpha.html")
                .put(Article.ARTICLE_TAGS_REF, "Java,Netty")
                .put(Article.ARTICLE_VIEW_PWD, "")
                .put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED)
                .put(Article.ARTICLE_UPDATED, 1000L)
                .put(Article.ARTICLE_CONTENT, "Alpha unique searchable body content."));
        articles.put("a-2", new JSONObject()
                .put(Keys.OBJECT_ID, "a-2")
                .put(Article.ARTICLE_TITLE, "Second Beta Guide")
                .put(Article.ARTICLE_PERMALINK, "/beta.html")
                .put(Article.ARTICLE_TAGS_REF, "Performance")
                .put(Article.ARTICLE_VIEW_PWD, "")
                .put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED)
                .put(Article.ARTICLE_UPDATED, 2000L)
                .put(Article.ARTICLE_CONTENT, "Beta initial searchable body content."));

        final List<Set<String>> contentBatchRequests = new ArrayList<>();
        final AtomicBoolean sawMetadataOnlyPhase = new AtomicBoolean(false);

        final InstantSearchService searchService = new InstantSearchService();
        setField(searchService, "articleRepository", new ArticleRepository() {
            @Override
            public JSONObject get(final Query query) {
                final Set<String> projections = projectionKeys(query);
                final JSONArray arr = new JSONArray();
                if (!projections.contains(Article.ARTICLE_CONTENT)) {
                    sawMetadataOnlyPhase.set(true);
                    for (final JSONObject src : articles.values()) {
                        final JSONObject meta = new JSONObject();
                        for (final String col : projections) {
                            if (src.has(col)) {
                                meta.put(col, src.get(col));
                            }
                        }
                        arr.put(meta);
                    }
                } else {
                    final Filter f = query.getFilter();
                    Assert.assertTrue(f instanceof PropertyFilter, "Phase 2 content fetch must filter by oId IN (...)");
                    @SuppressWarnings("unchecked")
                    final List<String> ids = (List<String>) ((PropertyFilter) f).getValue();
                    contentBatchRequests.add(new LinkedHashSet<>(ids));
                    for (final String id : ids) {
                        final JSONObject src = articles.get(id);
                        if (src != null) {
                            arr.put(new JSONObject(src.toString()));
                        }
                    }
                }
                return new JSONObject()
                        .put(Pagination.PAGINATION, new JSONObject().put(Pagination.PAGINATION_PAGE_COUNT, 1))
                        .put(Keys.RESULTS, arr);
            }
        });

        final JSONObject firstRes = searchService.search("Alpha", "", "", 10);
        Assert.assertTrue(sawMetadataOnlyPhase.get(), "Phase 1 must project metadata without articleContent CLOBs");
        Assert.assertEquals(contentBatchRequests.size(), 1);
        Assert.assertEquals(contentBatchRequests.get(0), Set.of("a-1", "a-2"));
        Assert.assertEquals(firstRes.getJSONArray("items").length(), 1);

        // Update ONLY article a-2 and invalidate snapshot: rebuild must fetch articleContent ONLY for a-2!
        contentBatchRequests.clear();
        articles.get("a-2")
                .put(Article.ARTICLE_UPDATED, 3000L)
                .put(Article.ARTICLE_CONTENT, "Beta updated quantum body token.");
        searchService.invalidate();

        final JSONObject secondRes = searchService.search("quantum", "", "", 10);
        Assert.assertEquals(contentBatchRequests.size(), 1, "Incremental rebuild must issue 1 Phase-2 batch for changed article");
        Assert.assertEquals(contentBatchRequests.get(0), Set.of("a-2"),
                "Unchanged article a-1 must reuse plainTextCache without re-loading CLOB");
        Assert.assertEquals(secondRes.getJSONArray("items").length(), 1);
        Assert.assertEquals(secondRes.getJSONArray("items").getJSONObject(0).getString("id"), "a-2");

        // invalidateAll() clears plainTextCache too (used after backup restore)
        contentBatchRequests.clear();
        searchService.invalidateAll();
        searchService.search("Alpha", "", "", 10);
        Assert.assertEquals(contentBatchRequests.get(0), Set.of("a-1", "a-2"),
                "invalidateAll() must reset plainTextCache so restored DB rows are freshly extracted");

        // Part B: BacklinkService resolves percent-encoded UTF-8 CJK permalinks to raw CJK permalinks
        final BacklinkService backlinkService = new BacklinkService();
        setField(backlinkService, "articleRepository", new ArticleRepository() {
            @Override
            public JSONObject get(final Query query) {
                final Set<String> projections = projectionKeys(query);
                final JSONArray arr = new JSONArray();
                final JSONObject target = new JSONObject()
                        .put(Keys.OBJECT_ID, "cjk-target")
                        .put(Article.ARTICLE_TITLE, "中文目标文章")
                        .put(Article.ARTICLE_PERMALINK, "/articles/中文路径.html")
                        .put(Article.ARTICLE_VIEW_PWD, "")
                        .put(Article.ARTICLE_UPDATED, 1000L)
                        .put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED);
                final JSONObject source = new JSONObject()
                        .put(Keys.OBJECT_ID, "cjk-source")
                        .put(Article.ARTICLE_TITLE, "引用方文章")
                        .put(Article.ARTICLE_PERMALINK, "/articles/source.html")
                        .put(Article.ARTICLE_VIEW_PWD, "")
                        .put(Article.ARTICLE_UPDATED, 2000L)
                        .put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED);
                if (projections.contains(Article.ARTICLE_CONTENT)) {
                    target.put(Article.ARTICLE_CONTENT, "目标正文");
                    source.put(Article.ARTICLE_CONTENT, "请参考 [中文目标](/articles/%E4%B8%AD%E6%96%87%E8%B7%AF%E5%BE%84.html?from=ref#top)");
                }
                // Only return once for page 1
                if (query.getCurrentPageNum() == 1) {
                    arr.put(target).put(source);
                }
                return new JSONObject()
                        .put(Pagination.PAGINATION, new JSONObject().put(Pagination.PAGINATION_PAGE_COUNT, 1))
                        .put(Keys.RESULTS, arr);
            }
        });

        final List<BacklinkService.BacklinkItem> backlinks = backlinkService.getBacklinksForArticle(new JSONObject()
                .put(Keys.OBJECT_ID, "cjk-target")
                .put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED)
                .put(Article.ARTICLE_VIEW_PWD, ""));
        Assert.assertEquals(backlinks.size(), 1, "Percent-encoded UTF-8 link must match raw CJK articlePermalink");
        Assert.assertEquals(backlinks.get(0).oId(), "cjk-source");
    }

    @Test
    public void articleViewPwdFullSurfaceIsolationAndLockedCommentRejection() throws Exception {
        final JSONObject lockedArticle = new JSONObject()
                .put(Keys.OBJECT_ID, "locked-1")
                .put(Article.ARTICLE_TITLE, "Protected Post")
                .put(Article.ARTICLE_PERMALINK, "/protected.html")
                .put(Article.ARTICLE_VIEW_PWD, "secret123")
                .put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED)
                .put(Article.ARTICLE_ABSTRACT, "Custom secret summary ![cover](https://cdn.example.com/private-cover.png)")
                .put(Article.ARTICLE_ABSTRACT_TEXT, "Custom secret summary")
                .put(Article.ARTICLE_IMG1_URL, "https://cdn.example.com/private-cover.png")
                .put(Article.ARTICLE_CONTENT, "Top secret body ![bodyimg](https://cdn.example.com/private-body.png)");

        // 1. Article.getAbstractText(JSONObject) and getArticleImg1URL* never leak custom abstract or private image URLs
        Assert.assertEquals(Article.getAbstractText(lockedArticle), "",
                "Password-protected article must return empty abstract text even when custom articleAbstract is set");
        Assert.assertFalse(Article.getArticleImg1URL(lockedArticle).contains("private-"),
                "Password-protected article cover must not leak private image URL");
        Assert.assertFalse(Article.getArticleImg1URLWithoutSetSize(lockedArticle).contains("private-"),
                "Password-protected article raw image1 URL must not leak private image URL");

        // 2. ArticleRepository.getNextArticle / getPreviousArticle masks abstract & img1URL on password-protected neighbors
        final ArticleRepository adjacentRepo = new ArticleRepository() {
            @Override
            public JSONObject get(final Query query) {
                return new JSONObject()
                        .put(Pagination.PAGINATION, new JSONObject().put(Pagination.PAGINATION_PAGE_COUNT, 1))
                        .put(Keys.RESULTS, new JSONArray().put(new JSONObject(lockedArticle.toString())));
            }
        };
        final JSONObject nextSummary = adjacentRepo.getNextArticle("current-id", 100L);
        Assert.assertNotNull(nextSummary);
        Assert.assertEquals(nextSummary.getString(Article.ARTICLE_TITLE), "Protected Post");
        Assert.assertEquals(nextSummary.getString(Article.ARTICLE_ABSTRACT), "");
        Assert.assertEquals(nextSummary.getString(Article.ARTICLE_ABSTRACT_TEXT), "");
        Assert.assertEquals(nextSummary.getString(Article.ARTICLE_IMG1_URL), "");

        // 3. CommentProcessor.addArticleComment rejects unauthenticated comments on password-protected articles with HTTP 403
        final CommentProcessor commentProcessor = new CommentProcessor();
        setField(commentProcessor, "optionQueryService", new OptionQueryService() {
            @Override
            public JSONObject getPreference() {
                return new JSONObject().put(Option.ID_C_COMMENTABLE, true);
            }
        });
        setField(commentProcessor, "articleRepository", new ArticleRepository() {
            @Override
            public JSONObject get(final String id) {
                return "locked-1".equals(id) ? new JSONObject(lockedArticle.toString()) : null;
            }
        });

        final String origin = Latkes.getServePath();
        final JSONObject bodyObj = new JSONObject()
                .put(Keys.OBJECT_ID, "locked-1")
                .put("boloUser", "Visitor")
                .put("commentContent", "Attempting to comment without unlocking");
        final DefaultFullHttpRequest nettyReq = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/article/comments");
        nettyReq.headers().set("Content-Type", "application/json");
        nettyReq.headers().set("Origin", origin);
        final DefaultFullHttpResponse nettyResp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        try {
            final AtomicInteger statusHolder = new AtomicInteger(200);
            final Request req = new Request(null, nettyReq);
            req.setJSON(bodyObj);
            final Response resp = new Response(null, nettyResp) {
                @Override
                public void setStatus(final int sc) {
                    statusHolder.set(sc);
                    super.setStatus(sc);
                }
            };
            final RequestContext ctx = new RequestContext(req, resp);
            commentProcessor.addArticleComment(ctx);
            Assert.assertEquals(statusHolder.get(), 403,
                    "Unauthenticated comment submission on password-protected article must be rejected with 403");
        } finally {
            nettyReq.release();
            nettyResp.release();
        }
    }

    @Test
    public void commentQueryNonDestructiveReadCaseInsensitiveRowKeysAndAuthorScoping() throws Exception {
        final AtomicBoolean removeCalledOnRead = new AtomicBoolean(false);
        final CommentQueryService service = new CommentQueryService();

        // Simulate H2 lowercase column keys ("oid", "articletitle", "articleauthorid", "articlepermalink")
        final Map<String, JSONObject> lowercaseArticleRows = new LinkedHashMap<>();
        lowercaseArticleRows.put("art-owned", new JSONObject()
                .put("oid", "art-owned")
                .put("articletitle", "H2 Lowercase Article Title")
                .put("articlepermalink", "/h2-owned.html")
                .put("articleauthorid", "author-1"));
        lowercaseArticleRows.put("art-other", new JSONObject()
                .put("oid", "art-other")
                .put("articletitle", "Other Author Article")
                .put("articlepermalink", "/h2-other.html")
                .put("articleauthorid", "author-2"));

        setField(service, "articleRepository", new ArticleRepository() {
            @Override
            public List<JSONObject> getList(final Query query) {
                final Filter filter = query.getFilter();
                if (filter instanceof PropertyFilter pf && Article.ARTICLE_AUTHOR_ID.equals(pf.getKey())) {
                    final String wantedAuthor = String.valueOf(pf.getValue());
                    final List<JSONObject> list = new ArrayList<>();
                    for (final JSONObject row : lowercaseArticleRows.values()) {
                        if (wantedAuthor.equals(row.optString("articleauthorid"))) {
                            list.add(new JSONObject().put("oid", row.getString("oid")));
                        }
                    }
                    return list;
                }
                return new ArrayList<>(lowercaseArticleRows.values());
            }

            @Override
            public JSONObject get(final String id) {
                return lowercaseArticleRows.get(id);
            }
        });

        setField(service, "commentRepository", new CommentRepository() {
            @Override
            public void remove(final String id) {
                removeCalledOnRead.set(true);
            }

            @Override
            public long countPendingComments() {
                return 2L;
            }

            @Override
            public long countPendingCommentsByAuthor(final String authorId, final Collection<String> fallbackArticleIds) {
                return "author-1".equals(authorId) ? 1L : 0L;
            }

            @Override
            public JSONObject get(final Query query) {
                final JSONArray rows = new JSONArray();
                rows.put(new JSONObject()
                        .put(Keys.OBJECT_ID, "c-owned")
                        .put(Comment.COMMENT_ON_ID, "art-owned")
                        .put(Comment.COMMENT_NAME, "ReaderA")
                        .put(Comment.COMMENT_CONTENT, "Nice post!")
                        .put(Comment.COMMENT_CREATED, 1700000000000L));
                // Include an orphan comment (missing article) to prove GET never deletes DB rows
                // and a foreign author's comment to verify defense-in-depth author filtering
                rows.put(new JSONObject()
                        .put(Keys.OBJECT_ID, "c-orphan")
                        .put(Comment.COMMENT_ON_ID, "art-deleted")
                        .put(Comment.COMMENT_NAME, "Ghost")
                        .put(Comment.COMMENT_CONTENT, "Orphan comment")
                        .put(Comment.COMMENT_CREATED, 1700000001000L));
                rows.put(new JSONObject()
                        .put(Keys.OBJECT_ID, "c-other")
                        .put(Comment.COMMENT_ON_ID, "art-other")
                        .put(Comment.COMMENT_NAME, "ReaderB")
                        .put(Comment.COMMENT_CONTENT, "Private to author-2")
                        .put(Comment.COMMENT_CREATED, 1700000002000L));
                return new JSONObject()
                        .put(Pagination.PAGINATION, new JSONObject().put(Pagination.PAGINATION_PAGE_COUNT, 1))
                        .put(Keys.RESULTS, rows);
            }
        });

        final JSONObject req = new JSONObject()
                .put(Pagination.PAGINATION_CURRENT_PAGE_NUM, 1)
                .put(Pagination.PAGINATION_PAGE_SIZE, 20)
                .put(Pagination.PAGINATION_WINDOW_SIZE, 10);

        // 1. Admin sees all comments, orphan renders as "[已删除文章]" without deleting DB row, H2 lowercase title resolves
        final JSONObject adminRes = service.getComments(req, true, "admin-1");
        Assert.assertFalse(removeCalledOnRead.get(), "Read query getComments() must never delete comment rows");
        final JSONArray adminComments = adminRes.getJSONArray(Comment.COMMENTS);
        Assert.assertEquals(adminComments.length(), 3);
        Assert.assertEquals(adminComments.getJSONObject(0).getString(Common.COMMENT_TITLE), "H2 Lowercase Article Title");
        Assert.assertEquals(adminComments.getJSONObject(1).getString(Common.COMMENT_TITLE), "[已删除文章]");

        // 2. Non-admin author-1 strictly sees only comments on author-1's articles and author-scoped pendingCount
        final JSONObject authorRes = service.getComments(req, false, "author-1");
        final JSONArray authorComments = authorRes.getJSONArray(Comment.COMMENTS);
        Assert.assertEquals(authorComments.length(), 1, "Non-admin author must only see comments on their own articles");
        Assert.assertEquals(authorComments.getJSONObject(0).getString(Keys.OBJECT_ID), "c-owned");
        Assert.assertEquals(authorRes.getLong("pendingCount"), 1L);
    }

    @Test
    public void monotonicIdsZeroSleepThroughputAndPageMgmtTransactionRollbackOnFailure() throws Exception {
        // 1. MonotonicIds generates 500 strictly monotonic numeric IDs in well under 200ms (vs 25,000ms with 50ms sleep)
        final long startNanos = System.nanoTime();
        long prev = Long.parseLong(MonotonicIds.nextMillisId());
        for (int i = 0; i < 500; i++) {
            final long next = Long.parseLong(MonotonicIds.nextMillisId());
            Assert.assertTrue(next > prev, "MonotonicIds must be strictly increasing: " + next + " <= " + prev);
            prev = next;
        }
        final long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        Assert.assertTrue(elapsedMs < 500L,
                "500 MonotonicIds calls must complete without sleeping (< 500ms), took " + elapsedMs + "ms");

        // 2. PageMgmtService blocks dangerous URI schemes & path traversal, and rolls back active TX on DB failure
        Assert.assertFalse(PageMgmtService.isSafePagePermalink("javascript:alert(1)"));
        Assert.assertFalse(PageMgmtService.isSafePagePermalink("data:text/html,<script>alert(1)</script>"));
        Assert.assertFalse(PageMgmtService.isSafePagePermalink("//evil.example.com/phish"));
        Assert.assertFalse(PageMgmtService.isSafePagePermalink("/about/../admin"));
        Assert.assertTrue(PageMgmtService.isSafePagePermalink("/about.html"));
        Assert.assertTrue(PageMgmtService.isSafePagePermalink("https://github.com/Willh92/Volo-solo"));

        final AtomicInteger rollbackCount = new AtomicInteger();
        final PageMgmtService pageMgmt = new PageMgmtService();
        setField(pageMgmt, "langPropsService", new LangPropsService() {
            @Override
            public String get(final String key) {
                return key;
            }
        });
        setField(pageMgmt, "pageRepository", new PageRepository() {
            @Override
            public Transaction beginTransaction() {
                return new Transaction() {
                    private boolean active = true;
                    @Override public String getId() { return "page-tx"; }
                    @Override public void commit() { active = false; }
                    @Override public void rollback() {
                        active = false;
                        rollbackCount.incrementAndGet();
                    }
                    @Override public boolean isActive() { return active; }
                };
            }

            @Override
            public int getMaxOrder() {
                return 3;
            }

            @Override
            public String add(final JSONObject page) throws RepositoryException {
                throw new RepositoryException("Simulated DB constraint violation");
            }
        });

        // Pre-TX validation rejects javascript: permalink before even opening a transaction
        Assert.expectThrows(ServiceException.class, () -> pageMgmt.addPage(new JSONObject()
                .put(Page.PAGE, new JSONObject()
                        .put(Page.PAGE_TITLE, "XSS Link")
                        .put(Page.PAGE_PERMALINK, "javascript:alert(1)")
                        .put(Page.PAGE_OPEN_TARGET, "_self"))));
        Assert.assertEquals(rollbackCount.get(), 0);

        // Runtime/DB failure inside TX must always roll back the active transaction
        Assert.expectThrows(ServiceException.class, () -> pageMgmt.addPage(new JSONObject()
                .put(Page.PAGE, new JSONObject()
                        .put(Page.PAGE_TITLE, "About")
                        .put(Page.PAGE_PERMALINK, "/about.html")
                        .put(Page.PAGE_ICON, "")
                        .put(Page.PAGE_OPEN_TARGET, "_self"))));
        Assert.assertEquals(rollbackCount.get(), 1, "Failed addPage TX must roll back active transaction");
    }

    private static Set<String> projectionKeys(final Query query) {
        final Set<String> keys = new LinkedHashSet<>();
        for (final Projection p : query.getProjections()) {
            keys.add(p.getKey());
        }
        return keys;
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
}
