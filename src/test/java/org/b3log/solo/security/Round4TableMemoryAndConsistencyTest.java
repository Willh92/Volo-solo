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
import org.b3log.latke.event.EventManager;
import org.b3log.latke.http.Cookie;
import org.b3log.latke.http.Request;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.http.Response;
import org.b3log.latke.model.Pagination;
import org.b3log.latke.model.Role;
import org.b3log.latke.model.User;
import org.b3log.latke.repository.Query;
import org.b3log.latke.repository.Transaction;
import org.b3log.solo.cache.ArticleCache;
import org.b3log.solo.cache.OptionCache;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.ArticleRevision;
import org.b3log.solo.model.Comment;
import org.b3log.solo.model.Option;
import org.b3log.solo.model.Tag;
import org.b3log.solo.model.UserExt;
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
import org.b3log.solo.service.BackupRestoreService;
import org.b3log.solo.service.DataModelService;
import org.b3log.solo.service.ImportService;
import org.b3log.solo.service.LocalRollingBackupService;
import org.b3log.solo.service.OptionMgmtService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.service.StatisticMgmtService;
import org.b3log.solo.service.StatisticQueryService;
import org.b3log.solo.service.UserQueryService;
import org.b3log.solo.util.AdminListSearch;
import org.b3log.solo.util.S3CompatibleUploader;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class Round4TableMemoryAndConsistencyTest {

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
    public void articleCachePersistedBaselineSingleOverlayWeightCapAndDeleteTombstone() throws Exception {
        final ArticleCache cache = new ArticleCache();
        final ArticleMgmtService mgmt = new ArticleMgmtService();
        setField(mgmt, "articleCache", cache);

        cache.putArticle(new JSONObject()
                .put(Keys.OBJECT_ID, "art-r4-1")
                .put(Article.ARTICLE_PERMALINK, "/r4-post.html")
                .put(Article.ARTICLE_TITLE, "Round 4 Cache Baseline")
                .put(Article.ARTICLE_CONTENT, "Hello world")
                .put("nestedTags", new JSONArray().put("Java").put(new JSONObject().put("k", "v")))
                .put(Article.ARTICLE_VIEW_COUNT, 100));

        mgmt.incViewCount("art-r4-1");
        mgmt.incViewCount("art-r4-1");
        mgmt.incViewCount("art-r4-1");
        mgmt.incViewCount("art-r4-1");
        mgmt.incViewCount("art-r4-1");

        // Dynamic read overlays pending delta (+5) once onto persisted baseline (100) -> 105
        Assert.assertEquals(cache.getArticle("art-r4-1").getInt(Article.ARTICLE_VIEW_COUNT), 105);
        Assert.assertEquals(cache.getArticleByPermalink("/r4-post.html").getInt(Article.ARTICLE_VIEW_COUNT), 105);
        // Persisted snapshot remains strictly at DB baseline (100), preventing double-counting on edits or reloads
        Assert.assertEquals(cache.getPersistedArticle("art-r4-1").getInt(Article.ARTICLE_VIEW_COUNT), 100);

        // Simulate cache eviction + reload from DB baseline (100): still 105, never 110
        cache.removeArticle("art-r4-1");
        cache.putArticle(new JSONObject()
                .put(Keys.OBJECT_ID, "art-r4-1")
                .put(Article.ARTICLE_PERMALINK, "/r4-post.html")
                .put(Article.ARTICLE_VIEW_COUNT, 100));
        Assert.assertEquals(cache.getArticle("art-r4-1").getInt(Article.ARTICLE_VIEW_COUNT), 105);

        // Tombstone prevents view-delta resurrection after article deletion
        @SuppressWarnings("unchecked")
        final Set<String> deletedIds = (Set<String>) getField(mgmt, "deletedArticleIds");
        final long drained = mgmt.drainPendingViewDelta("art-r4-1");
        Assert.assertEquals(drained, 5L);
        deletedIds.add("art-r4-1");
        mgmt.restorePendingViewDelta("art-r4-1", drained);
        mgmt.incViewCount("art-r4-1");
        Assert.assertEquals(mgmt.getPendingViewDelta("art-r4-1"), 0L,
                "Deleted article tombstone must block restorePendingViewDelta and incViewCount resurrection");

        // Weight-bounded eviction: 10 x 600K-char articles (~6M chars > MAX_TOTAL_CHARS=4M) must evict oldest entries
        final ArticleCache heavyCache = new ArticleCache();
        final String bigBody = "x".repeat(600_000);
        for (int i = 0; i < 10; i++) {
            heavyCache.putArticle(new JSONObject()
                    .put(Keys.OBJECT_ID, "heavy-" + i)
                    .put(Article.ARTICLE_PERMALINK, "/heavy-" + i + ".html")
                    .put(Article.ARTICLE_CONTENT, bigBody));
        }
        Assert.assertTrue(heavyCache.size() <= 7,
                "Character-weight cap (4M chars) must evict large entries well before MAX_ENTRIES=256, got " + heavyCache.size());
        Assert.assertTrue(heavyCache.getTotalChars() <= ArticleCache.MAX_TOTAL_CHARS);
        Assert.assertNull(heavyCache.getArticle("heavy-0"), "Oldest heavy entry must be evicted");
        Assert.assertNotNull(heavyCache.getArticle("heavy-9"), "Newest heavy entry must remain cached");
    }

    @Test
    public void optionCacheBypassesDocumentChunksAndStoreDocumentRollbackEvictsChunkKeys() throws Exception {
        final String chunkId = "voloIpBans.0123456789abcdef0123456789abcdef.0";
        Assert.assertTrue(OptionCache.isDocumentChunkId(chunkId));
        Assert.assertFalse(OptionCache.isDocumentChunkId("blogTitle"));
        Assert.assertFalse(OptionCache.isDocumentChunkId("custom.option.1"));

        final OptionCache optionCache = new OptionCache();
        final JSONObject chunkOpt = new JSONObject()
                .put(Keys.OBJECT_ID, chunkId)
                .put(Option.OPTION_CATEGORY, OptionCache.CATEGORY_DOCUMENT_CHUNK)
                .put(Option.OPTION_VALUE, "fragment");
        Assert.assertTrue(OptionCache.isDocumentChunk(chunkOpt));

        optionCache.putOption(chunkOpt);
        optionCache.markMissing(chunkId);
        Assert.assertNull(optionCache.getOption(chunkId), "Document chunks must bypass OptionCache heap storage");
        Assert.assertFalse(optionCache.isMissing(chunkId), "Document chunk IDs must never pollute missingIds");
        Assert.assertNull(optionCache.getCategory(OptionCache.CATEGORY_DOCUMENT_CHUNK));

        // Verify storeDocument rollback evicts every touched chunk key via OptionRepository.evict(chunkId)
        final Set<String> evictedIds = ConcurrentHashMap.newKeySet();
        final Map<String, JSONObject> rows = new HashMap<>();
        final OptionRepository repo = new OptionRepository() {
            @Override
            public Transaction beginTransaction() {
                final Map<String, JSONObject> snapshot = new HashMap<>(rows);
                return new Transaction() {
                    private boolean active = true;
                    @Override public String getId() { return "tx"; }
                    @Override public boolean isActive() { return active; }
                    @Override public void commit() { active = false; }
                    @Override public void rollback() {
                        rows.clear();
                        rows.putAll(snapshot);
                        active = false;
                    }
                };
            }

            @Override
            public JSONObject get(final String id) {
                return rows.get(id);
            }

            @Override
            public String add(final JSONObject option) {
                final String id = option.getString(Keys.OBJECT_ID);
                if (id.endsWith(".1")) {
                    throw new IllegalStateException("Simulated failure on second chunk");
                }
                rows.put(id, new JSONObject(option.toString()));
                return id;
            }

            @Override
            public void evict(final String id) {
                evictedIds.add(id);
            }

            @Override
            public void evictOption(final String id) {
                evictedIds.add(id);
            }
        };

        final OptionMgmtService mgmt = new OptionMgmtService();
        setField(mgmt, "optionRepository", repo);
        final String twoChunkDoc = "a".repeat(12_000);
        Assert.expectThrows(Exception.class, () -> mgmt.storeDocument("voloIpBans", "security", twoChunkDoc));
        Assert.assertTrue(evictedIds.contains("voloIpBans"));
        Assert.assertTrue(evictedIds.stream().anyMatch(k -> k.startsWith("voloIpBans.") && k.endsWith(".0")),
                "Rollback must evict touched generation chunk IDs, got: " + evictedIds);
    }

    @Test
    public void customVarsParsingHandlesEmptyValuesTrailingPipesAndEqualsSignsInValues() throws Exception {
        final DataModelService dms = new DataModelService();
        setField(dms, "userQueryService", new UserQueryService() {
            @Override public String getLoginURL(final String redirectURL) { return "/login"; }
            @Override public String getLogoutURL() { return "/logout"; }
            @Override public String getB3username() { return ""; }
        });
        setField(dms, "userRepository", new UserRepository() {
            @Override public List<JSONObject> getList(final Query query) { return Collections.emptyList(); }
            @Override public JSONObject getAdmin() { return null; }
        });
        setField(dms, "pageRepository", new PageRepository() {
            @Override public List<JSONObject> getPages() { return Collections.emptyList(); }
        });
        setField(dms, "statisticQueryService", new StatisticQueryService() {
            @Override public JSONObject getStatistic() { return new JSONObject(); }
        });
        setField(dms, "optionQueryService", new OptionQueryService() {
            @Override public JSONObject getOptionById(final String id) { return null; }
        });
        setField(dms, "langPropsService", new org.b3log.latke.service.LangPropsService() {
            @Override public String get(final String key) { return key; }
        });
        setField(dms, "tagArticleRepository", new TagArticleRepository() {
            @Override public List<JSONObject> getMostUsedTags(final int num) { return Collections.emptyList(); }
        });
        setField(dms, "archiveDateRepository", new ArchiveDateRepository() {
            @Override public List<JSONObject> getPublishedArchiveDates() { return new ArrayList<>(); }
        });
        setField(dms, "categoryRepository", new CategoryRepository() {
            @Override public List<JSONObject> getMostUsedCategories(final int num) { return Collections.emptyList(); }
        });
        setField(dms, "eventManager", new EventManager());

        final JSONObject preference = new JSONObject()
                .put(Option.ID_C_ARTICLE_LIST_DISPLAY_COUNT, 10)
                .put(Option.ID_C_ARTICLE_LIST_PAGINATION_WINDOW_SIZE, 5)
                .put(Option.ID_C_LOCALE_STRING, "zh_CN")
                .put(Option.ID_C_BLOG_TITLE, "Volo")
                .put(Option.ID_C_BLOG_SUBTITLE, "Sub")
                .put(Option.ID_C_HTML_HEAD, "")
                .put(Option.ID_C_META_KEYWORDS, "")
                .put(Option.ID_C_META_DESCRIPTION, "")
                .put(Option.ID_C_NOTICE_BOARD, "")
                .put(Option.ID_C_MOST_USED_TAG_DISPLAY_CNT, 0)
                .put(Option.ID_C_MOST_VIEW_ARTICLE_DISPLAY_CNT, 0)
                .put(Option.ID_C_MOST_COMMENT_ARTICLE_DISPLAY_CNT, 0)
                .put(Option.ID_C_RECENT_ARTICLE_DISPLAY_CNT, 0)
                .put(Option.ID_C_RECENT_COMMENT_DISPLAY_CNT, 0)
                .put(Option.ID_C_CUSTOM_VARS, "foo=|a=1||=orphan|noequals|url=https://x.test/path?a=1&b=2|");

        final DefaultFullHttpRequest rawReq = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        final DefaultFullHttpResponse rawResp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        try {
            final RequestContext ctx = new RequestContext(new Request(null, rawReq), new Response(null, rawResp));
            ctx.attr(Keys.TEMPLATE_DIR_NAME, "non-existent-test-skin");
            final Map<String, Object> dataModel = new HashMap<>();
            dms.fillCommon(ctx, dataModel, preference);

            @SuppressWarnings("unchecked")
            final Map<String, String> customVars = (Map<String, String>) dataModel.get("customVars");
            Assert.assertNotNull(customVars);
            Assert.assertEquals(customVars.get("foo"), "", "Empty value after '=' must map to empty string without AIOOBE");
            Assert.assertEquals(customVars.get("a"), "1");
            Assert.assertEquals(customVars.get("url"), "https://x.test/path?a=1&b=2",
                    "Values containing '=' query parameters must not be truncated");
            Assert.assertFalse(customVars.containsKey(""));
            Assert.assertFalse(customVars.containsKey("noequals"));
        } finally {
            rawReq.release();
            rawResp.release();
        }
    }

    @Test
    public void h2SqlQueriesExcludePrivateDraftDeletedCommentsLoadRevisionMetadataWithoutClobsAndFindUnusedTags()
            throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:volo_round4_sql;MODE=MYSQL")) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE b3_solo_article ("
                        + "oId VARCHAR(19) PRIMARY KEY, articleTitle VARCHAR(255), "
                        + "articleStatus INT, articleViewPwd VARCHAR(128))");
                st.execute("CREATE TABLE b3_solo_comment ("
                        + "oId VARCHAR(19) PRIMARY KEY, commentOnId VARCHAR(19), commentContent CLOB, "
                        + "commentStatus INT, commentDeleted BOOLEAN, commentCreated BIGINT)");
                st.execute("CREATE TABLE b3_solo_article_revision ("
                        + "oId VARCHAR(19) PRIMARY KEY, articleOId VARCHAR(19), revisionTitle VARCHAR(255), "
                        + "revisionContent CLOB, revisionCreated BIGINT, revisionAuthorName VARCHAR(128), "
                        + "revisionNote VARCHAR(255))");
                st.execute("CREATE TABLE b3_solo_tag (oId VARCHAR(19) PRIMARY KEY, tagTitle VARCHAR(255))");
                st.execute("CREATE TABLE b3_solo_tag_article ("
                        + "oId VARCHAR(19) PRIMARY KEY, tag_oId VARCHAR(19), article_oId VARCHAR(19))");

                // Articles: 1 public published, 1 password-protected published, 1 draft
                st.execute("INSERT INTO b3_solo_article VALUES ('art-pub', 'Public Post', 0, '')");
                st.execute("INSERT INTO b3_solo_article VALUES ('art-nullpwd', 'Legacy Public Post', 0, NULL)");
                st.execute("INSERT INTO b3_solo_article VALUES ('art-pwd', 'Secret Post', 0, 'pass123')");
                st.execute("INSERT INTO b3_solo_article VALUES ('art-draft', 'Draft Post', 1, '')");

                // Comments: only c-pub-ok and c-legacy-null-del should survive getRecentComments
                st.execute("INSERT INTO b3_solo_comment VALUES ('c-pub-ok', 'art-pub', 'Visible 1', 0, FALSE, 5000)");
                st.execute("INSERT INTO b3_solo_comment VALUES ('c-legacy-ok', 'art-nullpwd', 'Visible 2', 0, NULL, 4000)");
                st.execute("INSERT INTO b3_solo_comment VALUES ('c-pwd-leak', 'art-pwd', 'Protected leak', 0, FALSE, 6000)");
                st.execute("INSERT INTO b3_solo_comment VALUES ('c-draft-leak', 'art-draft', 'Draft leak', 0, FALSE, 7000)");
                st.execute("INSERT INTO b3_solo_comment VALUES ('c-pending', 'art-pub', 'Unapproved', 1, FALSE, 8000)");
                st.execute("INSERT INTO b3_solo_comment VALUES ('c-deleted', 'art-pub', 'Soft deleted', 0, TRUE, 9000)");

                // Revisions with large CLOB content
                final String largeClob = "Markdown revision payload ".repeat(500);
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO b3_solo_article_revision VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, "rev-101");
                    ps.setString(2, "art-pub");
                    ps.setString(3, "Rev Title 101");
                    ps.setString(4, largeClob);
                    ps.setLong(5, 1_730_500_000_000L);
                    ps.setString(6, "Willh");
                    ps.setString(7, "Trimmed CLOB test");
                    ps.executeUpdate();
                }

                // Tags: tag-used referenced in tag_article, tag-orphan1 & tag-orphan2 unreferenced
                st.execute("INSERT INTO b3_solo_tag VALUES ('tag-used', 'Java')");
                st.execute("INSERT INTO b3_solo_tag VALUES ('tag-orphan-b', 'ZetaUnused')");
                st.execute("INSERT INTO b3_solo_tag VALUES ('tag-orphan-a', 'AlphaUnused')");
                st.execute("INSERT INTO b3_solo_tag_article VALUES ('rel-1', 'tag-used', 'art-pub')");
            }

            final ArticleRepository articleRepo = new ArticleRepository() {
                @Override public String getName() { return "b3_solo_article"; }
            };
            final CommentRepository commentRepo = new CommentRepository() {
                @Override public String getName() { return "b3_solo_comment"; }
                @Override public List<JSONObject> select(final String sql, final Object... params) {
                    return queryH2(conn, sql, params);
                }
            };
            setField(commentRepo, "articleRepository", articleRepo);

            final ArticleRevisionRepository revisionRepo = new ArticleRevisionRepository() {
                @Override public String getName() { return "b3_solo_article_revision"; }
                @Override public List<JSONObject> select(final String sql, final Object... params) {
                    return queryH2(conn, sql, params);
                }
            };

            final TagArticleRepository tagArticleRepo = new TagArticleRepository() {
                @Override public String getName() { return "b3_solo_tag_article"; }
            };
            final TagRepository tagRepo = new TagRepository() {
                @Override public String getName() { return "b3_solo_tag"; }
                @Override public List<JSONObject> select(final String sql, final Object... params) {
                    return queryH2(conn, sql, params);
                }
            };
            setField(tagRepo, "tagArticleRepository", tagArticleRepo);

            final Properties h2Props = new Properties();
            h2Props.setProperty("runtimeDatabase", "H2");
            h2Props.setProperty("jdbc.tablePrefix", "b3_solo");
            h2Props.setProperty("cookieHttpOnly", "true");
            h2Props.setProperty("loginTrustedProxies", "127.0.0.1,::1");
            Latkes.setLocalProps(h2Props);
            try {
                // 1. Recent comments SQL join filters out draft, password-protected, pending, and deleted comments
                final List<JSONObject> recent = commentRepo.getRecentComments(10);
                Assert.assertEquals(recent.size(), 2, "Only public approved non-deleted comments may be returned: " + recent);
                Assert.assertEquals(optCaseInsensitive(recent.get(0), "oId"), "c-pub-ok");
                Assert.assertEquals(optCaseInsensitive(recent.get(1), "oId"), "c-legacy-ok");

                // 2. Revision metadata computes exact contentLength in SQL without loading revisionContent CLOB
                final List<JSONObject> revMeta = revisionRepo.listMetadataByArticleId("art-pub", 10);
                Assert.assertEquals(revMeta.size(), 1);
                Assert.assertEquals(revMeta.get(0).getString(Keys.OBJECT_ID), "rev-101");
                Assert.assertEquals(revMeta.get(0).getInt("contentLength"), "Markdown revision payload ".length() * 500);
                Assert.assertFalse(revMeta.get(0).has(ArticleRevision.REVISION_CONTENT),
                        "Revision metadata listing must not materialize revisionContent CLOBs in heap");

                // 3. Unused tags NOT EXISTS query returns only unreferenced tags ordered by tagTitle ASC
                final List<JSONObject> unusedTags = tagRepo.getUnusedTags();
                Assert.assertEquals(unusedTags.size(), 2);
                Assert.assertEquals(unusedTags.get(0).getString(Tag.TAG_TITLE), "AlphaUnused");
                Assert.assertEquals(unusedTags.get(1).getString(Tag.TAG_TITLE), "ZetaUnused");
            } finally {
                configure();
            }
        }
    }

    @Test
    public void backupRestoreDefaultsLegacyNullStatusFieldsAndImportPreservesHorizontalRulesAndEmptyFrontMatter()
            throws Exception {
        // Part A: Legacy backup JSON missing commentStatus, commentDeleted, and userStatus
        final Map<String, JSONObject> userRows = new LinkedHashMap<>();
        final Map<String, JSONObject> commentRows = new LinkedHashMap<>();
        final Map<String, JSONObject> optionRows = new LinkedHashMap<>();

        final Path tempDir = Files.createTempDirectory("volo-r4-restore-");
        try {
            final JSONObject legacyBackup = new JSONObject()
                    .put(User.USERS, new JSONArray()
                            .put(new JSONObject()
                                    .put(Keys.OBJECT_ID, "u-admin")
                                    .put(User.USER_NAME, "admin")
                                    .put(User.USER_EMAIL, "admin@example.com")
                                    .put(User.USER_PASSWORD, "HASH")
                                    .put(User.USER_ROLE, Role.ADMIN_ROLE)
                                    .put(UserExt.USER_STATUS, JSONObject.NULL)))
                    .put(Comment.COMMENTS, new JSONArray()
                            .put(new JSONObject()
                                    .put(Keys.OBJECT_ID, "cmt-legacy-1")
                                    .put(Comment.COMMENT_ON_ID, "art-1")
                                    .put(Comment.COMMENT_NAME, "Reader")
                                    .put(Comment.COMMENT_CONTENT, "<p>Legacy comment</p>")
                                    .put(Comment.COMMENT_STATUS, JSONObject.NULL)))
                    .put(Option.OPTIONS, new JSONArray());

            final Path zipFile = tempDir.resolve("legacy-backup.zip");
            try (OutputStream os = Files.newOutputStream(zipFile);
                 ZipOutputStream zos = new ZipOutputStream(os, StandardCharsets.UTF_8)) {
                zos.putNextEntry(new ZipEntry("data.json"));
                zos.write(legacyBackup.toString().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }

            final BackupRestoreService restoreService = new BackupRestoreService();
            wireMinimalRestoreRepositories(restoreService, optionRows, userRows, commentRows);
            setField(restoreService, "localRollingBackupService", new LocalRollingBackupService() {
                @Override public JSONObject runBackup(final boolean manual) {
                    return new JSONObject().put("name", "pre-restore.zip");
                }
            });

            restoreService.restoreFromBackupZip(zipFile, true);
            final JSONObject restoredUser = userRows.get("u-admin");
            Assert.assertNotNull(restoredUser);
            Assert.assertEquals(restoredUser.getInt(UserExt.USER_STATUS), UserExt.USER_STATUS_C_VALID,
                    "Legacy user missing/null userStatus must default to VALID (0)");

            final JSONObject restoredComment = commentRows.get("cmt-legacy-1");
            Assert.assertNotNull(restoredComment);
            Assert.assertEquals(restoredComment.getInt(Comment.COMMENT_STATUS), Comment.COMMENT_STATUS_C_APPROVED,
                    "Legacy comment missing/null commentStatus must default to APPROVED (0)");
            Assert.assertFalse(restoredComment.getBoolean("commentDeleted"),
                    "Legacy comment missing commentDeleted must default to false");
        } finally {
            deleteRecursively(tempDir);
        }

        // Part B: ImportService.parseArticle front-matter boundary & horizontal rule preservation
        final ImportService importService = new ImportService();
        final Method parseArticle = ImportService.class.getDeclaredMethod("parseArticle", String.class, String.class);
        parseArticle.setAccessible(true);

        final String plainWithHr = "Introductory paragraph before thematic break.\n\n---\n\nSecond section after HR.";
        final JSONObject plainParsed = (JSONObject) parseArticle.invoke(importService, "plain-hr.md", plainWithHr);
        Assert.assertEquals(plainParsed.getString(Article.ARTICLE_TITLE), "plain-hr");
        Assert.assertTrue(plainParsed.getString(Article.ARTICLE_CONTENT).contains("Introductory paragraph before thematic break."),
                "Plain Markdown with '---' horizontal rule must not lose its opening section");
        Assert.assertTrue(plainParsed.getString(Article.ARTICLE_CONTENT).contains("Second section after HR."));

        final String emptyFrontMatter = "---\n---\nBody after empty YAML fence.";
        final JSONObject emptyFmParsed = (JSONObject) parseArticle.invoke(importService, "empty-fm.md", emptyFrontMatter);
        Assert.assertEquals(emptyFmParsed.getString(Article.ARTICLE_TITLE), "empty-fm");
        Assert.assertEquals(emptyFmParsed.getString(Article.ARTICLE_CONTENT), "Body after empty YAML fence.");

        final String standardHexo = "---\ntitle: Proper Front Matter\ntags: [Java, Volo]\n---\nBody with later\n\n---\n\nhorizontal rule.";
        final JSONObject hexoParsed = (JSONObject) parseArticle.invoke(importService, "hexo.md", standardHexo);
        Assert.assertEquals(hexoParsed.getString(Article.ARTICLE_TITLE), "Proper Front Matter");
        Assert.assertTrue(hexoParsed.getString(Article.ARTICLE_CONTENT).contains("horizontal rule."));
    }

    @Test
    public void s3VirtualHostedDnsSsrfGuardVisitedCookieCompactHashAndLikeBackslashEscape() throws Exception {
        // 1. S3 target host SSRF validation rejects loopback, unspecified, and cloud metadata IP literals
        for (final String blockedHost : new String[]{"127.0.0.1", "[::1]", "169.254.169.254", "0.0.0.0"}) {
            Assert.expectThrows(IOException.class, () -> S3CompatibleUploader.validateHostAddresses(blockedHost));
        }

        // 2. AdminListSearch.escapeLikeLiteral escapes \, %, and _ cleanly
        Assert.assertEquals(AdminListSearch.escapeLikeLiteral("normalKeyword"), "normalKeyword");
        Assert.assertEquals(AdminListSearch.escapeLikeLiteral("path\\to_100%"), "path\\\\to\\_100\\%");
        Assert.assertTrue(AdminListSearch.containsLikeMeta("trailing\\"));

        // 3. StatisticMgmtService visited cookie uses compact 11-char Base64URL tokens (< 1 KiB for 64 long CJK URLs)
        String cookieVal = null;
        for (int i = 0; i < 64; i++) {
            final String longCjkUri = "/articles/2026/11/06/深入理解高性能Java内存分配与表格排版优化实践指南-" + i + ".html";
            final DefaultFullHttpRequest rawReq = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, longCjkUri);
            final DefaultFullHttpResponse rawResp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            try {
                final String currentCookie = cookieVal;
                final Request req = new Request(null, rawReq) {
                    @Override public String getRequestURI() { return longCjkUri; }
                    @Override public Set<Cookie> getCookies() {
                        if (currentCookie == null) {
                            return Collections.emptySet();
                        }
                        final Cookie c = new Cookie("visited", currentCookie);
                        return Set.of(c);
                    }
                };
                final Cookie[] captured = new Cookie[1];
                final Response resp = new Response(null, rawResp) {
                    @Override public void addCookie(final Cookie cookie) {
                        captured[0] = cookie;
                    }
                };
                final RequestContext ctx = new RequestContext(req, resp);
                Assert.assertFalse(StatisticMgmtService.hasBeenServed(ctx, resp), "First visit must return false");
                Assert.assertNotNull(captured[0]);
                cookieVal = captured[0].getValue();
            } finally {
                rawReq.release();
                rawResp.release();
            }
        }
        Assert.assertNotNull(cookieVal);
        Assert.assertTrue(org.b3log.latke.util.URLs.decode(cookieVal).length() < 950,
                "64 visited CJK URLs must stay < 950 bytes decoded JSON, got " + org.b3log.latke.util.URLs.decode(cookieVal).length());
        Assert.assertTrue(cookieVal.length() < 1500,
                "64 visited CJK URLs must stay well under RFC 6265 4KB cookie limit (< 1.5 KB URL-encoded), got length=" + cookieVal.length());

        // Re-visiting the 63rd URI with the compact cookie must be recognized as already served
        final String revisitUri = "/articles/2026/11/06/深入理解高性能Java内存分配与表格排版优化实践指南-63.html";
        final String savedCookie = cookieVal;
        final DefaultFullHttpRequest revReq = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, revisitUri);
        final DefaultFullHttpResponse revResp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        try {
            final Request req = new Request(null, revReq) {
                @Override public String getRequestURI() { return revisitUri; }
                @Override public Set<Cookie> getCookies() { return Set.of(new Cookie("visited", savedCookie)); }
            };
            final Response resp = new Response(null, revResp);
            Assert.assertTrue(StatisticMgmtService.hasBeenServed(new RequestContext(req, resp), resp),
                    "Re-visiting an already hashed URI must return true");
        } finally {
            revReq.release();
            revResp.release();
        }

        // Legacy raw-URI cookie entries remain backward-compatible
        final DefaultFullHttpRequest legReq = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/legacy-post.html");
        final DefaultFullHttpResponse legResp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        try {
            final Request req = new Request(null, legReq) {
                @Override public String getRequestURI() { return "/legacy-post.html"; }
                @Override public Set<Cookie> getCookies() {
                    return Set.of(new Cookie("visited", "[\"/legacy-post.html\"]"));
                }
            };
            final Response resp = new Response(null, legResp);
            Assert.assertTrue(StatisticMgmtService.hasBeenServed(new RequestContext(req, resp), resp),
                    "Legacy raw-URI visited cookie must still be recognized");
        } finally {
            legReq.release();
            legResp.release();
        }
    }

    private static List<JSONObject> queryH2(final Connection conn, final String sql, final Object... params) {
        final List<JSONObject> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (params != null) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                final ResultSetMetaData md = rs.getMetaData();
                final int cols = md.getColumnCount();
                while (rs.next()) {
                    final JSONObject row = new JSONObject();
                    for (int c = 1; c <= cols; c++) {
                        final String label = md.getColumnLabel(c);
                        final Object val = rs.getObject(c);
                        if (val != null) {
                            row.put(label, val);
                        }
                    }
                    list.add(row);
                }
            }
        } catch (final Exception e) {
            throw new IllegalStateException("H2 test query failed: " + sql, e);
        }
        return list;
    }

    private static String optCaseInsensitive(final JSONObject row, final String key) {
        for (final String k : row.keySet()) {
            if (k.equalsIgnoreCase(key)) {
                return row.optString(k);
            }
        }
        return "";
    }

    private static void wireMinimalRestoreRepositories(final Object target,
                                                       final Map<String, JSONObject> options,
                                                       final Map<String, JSONObject> users,
                                                       final Map<String, JSONObject> comments) throws Exception {
        final JSONObject emptyQueryRes = new JSONObject()
                .put(Pagination.PAGINATION, new JSONObject().put(Pagination.PAGINATION_PAGE_COUNT, 1))
                .put(Keys.RESULTS, new JSONArray());

        setField(target, "optionRepository", new OptionRepository() {
            @Override public Transaction beginTransaction() { return noopTx(); }
            @Override public JSONObject get(final String id) { return options.get(id); }
            @Override public JSONObject get(final Query q) { return toQueryResult(options); }
            @Override public String add(final JSONObject o) {
                final String id = o.getString(Keys.OBJECT_ID);
                options.put(id, new JSONObject(o.toString()));
                return id;
            }
            @Override public void remove(final String id) { options.remove(id); }
        });
        setField(target, "userRepository", new UserRepository() {
            @Override public JSONObject get(final String id) { return users.get(id); }
            @Override public JSONObject get(final Query q) { return toQueryResult(users); }
            @Override public String add(final JSONObject o) {
                final String id = o.getString(Keys.OBJECT_ID);
                users.put(id, new JSONObject(o.toString()));
                return id;
            }
            @Override public void remove(final String id) { users.remove(id); }
        });
        setField(target, "commentRepository", new CommentRepository() {
            @Override public JSONObject get(final Query q) { return toQueryResult(comments); }
            @Override public String add(final JSONObject o) {
                final String id = o.getString(Keys.OBJECT_ID);
                comments.put(id, new JSONObject(o.toString()));
                return id;
            }
            @Override public void remove(final String id) { comments.remove(id); }
        });
        setField(target, "articleRepository", new ArticleRepository() {
            @Override public JSONObject get(final Query q) { return emptyQueryRes; }
        });
        setField(target, "articleRevisionRepository", new ArticleRevisionRepository() {
            @Override public JSONObject get(final Query q) { return emptyQueryRes; }
        });
        setField(target, "archiveDateRepository", new ArchiveDateRepository() {
            @Override public JSONObject get(final Query q) { return emptyQueryRes; }
        });
        setField(target, "archiveDateArticleRepository", new ArchiveDateArticleRepository() {
            @Override public JSONObject get(final Query q) { return emptyQueryRes; }
        });
        setField(target, "categoryRepository", new CategoryRepository() {
            @Override public JSONObject get(final Query q) { return emptyQueryRes; }
        });
        setField(target, "categoryTagRepository", new CategoryTagRepository() {
            @Override public JSONObject get(final Query q) { return emptyQueryRes; }
        });
        setField(target, "followRepository", new FollowRepository() {
            @Override public JSONObject get(final Query q) { return emptyQueryRes; }
        });
        setField(target, "linkRepository", new LinkRepository() {
            @Override public JSONObject get(final Query q) { return emptyQueryRes; }
        });
        setField(target, "pageRepository", new PageRepository() {
            @Override public JSONObject get(final Query q) { return emptyQueryRes; }
        });
        setField(target, "pluginRepository", new PluginRepository() {
            @Override public JSONObject get(final Query q) { return emptyQueryRes; }
        });
        setField(target, "tagRepository", new TagRepository() {
            @Override public JSONObject get(final Query q) { return emptyQueryRes; }
        });
        setField(target, "tagArticleRepository", new TagArticleRepository() {
            @Override public JSONObject get(final Query q) { return emptyQueryRes; }
        });
    }

    private static JSONObject toQueryResult(final Map<String, JSONObject> map) {
        final JSONArray arr = new JSONArray();
        for (final JSONObject v : map.values()) {
            arr.put(new JSONObject(v.toString()));
        }
        return new JSONObject()
                .put(Pagination.PAGINATION, new JSONObject().put(Pagination.PAGINATION_PAGE_COUNT, 1))
                .put(Keys.RESULTS, arr);
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

    private static Object getField(final Object target, final String name) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                final Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (final NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
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
