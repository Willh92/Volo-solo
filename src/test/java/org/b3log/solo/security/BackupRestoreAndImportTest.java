/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.security;

import org.b3log.latke.Keys;
import org.b3log.latke.model.Role;
import org.b3log.latke.model.User;
import org.b3log.latke.repository.AbstractRepository;
import org.b3log.latke.repository.Query;
import org.b3log.latke.repository.Transaction;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.ArticleRevision;
import org.b3log.solo.model.Follow;
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
import org.b3log.solo.service.BackupRestoreService;
import org.b3log.solo.service.ExportService;
import org.b3log.solo.service.ImportService;
import org.b3log.solo.service.LocalRollingBackupService;
import org.b3log.solo.service.PermalinkQueryService;
import org.b3log.solo.service.UserQueryService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class BackupRestoreAndImportTest {

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
    public void exportIncludesAll15TablesAndRestorePreservesOrOverwritesAuthCleanly() throws Exception {
        final InMemoryTable options = new InMemoryTable();
        final InMemoryTable users = new InMemoryTable();
        final InMemoryTable articles = new InMemoryTable();
        final InMemoryTable follows = new InMemoryTable();
        final InMemoryTable revisions = new InMemoryTable();
        final InMemoryTable empty = new InMemoryTable();

        users.put(new JSONObject()
                .put(Keys.OBJECT_ID, "admin-live")
                .put(User.USER_NAME, "liveadmin")
                .put(User.USER_EMAIL, "live@example.com")
                .put(User.USER_PASSWORD, "HASH_LIVE_SECRET")
                .put(User.USER_ROLE, Role.ADMIN_ROLE)
                .put(User.USER_URL, "https://blog.example.com"));
        options.put(new JSONObject()
                .put(Keys.OBJECT_ID, Option.ID_C_VOLO_PASSKEY_AUTH)
                .put(Option.OPTION_CATEGORY, "security")
                .put(Option.OPTION_VALUE, "{\"credentials\":[{\"id\":\"pk-live\"}]}"));
        options.put(new JSONObject()
                .put(Keys.OBJECT_ID, Option.ID_C_VOLO_REMOTE_BACKUP)
                .put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE)
                .put(Option.OPTION_VALUE, "{\"protocol\":\"s3\",\"s3SecretKey\":\"TOP_SECRET_S3_KEY\"}"));
        follows.put(new JSONObject()
                .put(Keys.OBJECT_ID, "f-1")
                .put(Follow.FOLLOW_TITLE, "GitHub")
                .put(Follow.FOLLOW_ADDRESS, "https://github.com/Willh92/Volo-solo"));
        revisions.put(new JSONObject()
                .put(Keys.OBJECT_ID, "rev-1")
                .put(ArticleRevision.ARTICLE_OID, "a-1")
                .put(ArticleRevision.REVISION_TITLE, "Snapshot 1")
                .put(ArticleRevision.REVISION_CONTENT, "Body 1")
                .put(ArticleRevision.REVISION_ABSTRACT, "")
                .put(ArticleRevision.REVISION_CREATED, 1_730_000_000_000L));

        final ExportService exportService = new ExportService();
        wireRepositories(exportService, options, users, articles, follows, revisions, empty);

        final JSONObject exported = exportService.getJSONs();
        Assert.assertEquals(exported.getJSONArray(Follow.FOLLOWS).length(), 1, "Export must include follow table");
        Assert.assertEquals(exported.getJSONArray("articleRevisions").length(), 1, "Export must include article_revision table");
        final String exportedStr = exported.toString();
        Assert.assertFalse(exportedStr.contains("pk-live"), "Passkey credentials must be redacted from plain JSON export");
        Assert.assertFalse(exportedStr.contains("TOP_SECRET_S3_KEY"), "Remote backup secret keys must be masked during export");

        // Prepare a backup zip containing different articles, follows, revisions, and a different backup admin password
        final Path tempDir = Files.createTempDirectory("volo-restore-test-");
        try {
            final JSONObject backupData = new JSONObject()
                    .put(User.USERS, new JSONArray().put(new JSONObject()
                            .put(Keys.OBJECT_ID, "admin-from-backup")
                            .put(User.USER_NAME, "backupadmin")
                            .put(User.USER_EMAIL, "backup@example.com")
                            .put(User.USER_PASSWORD, "HASH_BACKUP_OLD")
                            .put(User.USER_ROLE, Role.ADMIN_ROLE)
                            .put(User.USER_URL, "https://blog.example.com")))
                    .put(Article.ARTICLES, new JSONArray().put(new JSONObject()
                            .put(Keys.OBJECT_ID, "art-restored")
                            .put(Article.ARTICLE_TITLE, "Restored Post")
                            .put(Article.ARTICLE_ABSTRACT, "Summary")
                            .put(Article.ARTICLE_CONTENT, "Full restored markdown")
                            .put(Article.ARTICLE_AUTHOR_ID, "admin-from-backup")
                            .put(Article.ARTICLE_PERMALINK, "/articles/restored.html")
                            .put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED)
                            .put(Article.ARTICLE_CREATED, 1_730_100_000_000L)
                            .put(Article.ARTICLE_UPDATED, 1_730_100_000_000L)))
                    .put(Follow.FOLLOWS, new JSONArray().put(new JSONObject()
                            .put(Keys.OBJECT_ID, "f-restored")
                            .put(Follow.FOLLOW_TITLE, "Restored Follow")
                            .put(Follow.FOLLOW_ADDRESS, "https://example.com")))
                    .put("articleRevisions", new JSONArray().put(new JSONObject()
                            .put(Keys.OBJECT_ID, "rev-restored")
                            .put(ArticleRevision.ARTICLE_OID, "art-restored")
                            .put(ArticleRevision.REVISION_TITLE, "Rev Title")
                            .put(ArticleRevision.REVISION_CONTENT, "Rev Body")
                            .put(ArticleRevision.REVISION_CREATED, 1_730_100_000_000L)))
                    .put(Option.OPTIONS, new JSONArray()
                            .put(new JSONObject()
                                    .put(Keys.OBJECT_ID, Option.ID_C_BLOG_TITLE)
                                    .put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE)
                                    .put(Option.OPTION_VALUE, "Restored Blog Title"))
                            .put(new JSONObject()
                                    .put(Keys.OBJECT_ID, Option.ID_C_VOLO_PASSKEY_AUTH)
                                    .put(Option.OPTION_CATEGORY, "security")
                                    .put(Option.OPTION_VALUE, "{\"credentials\":[{\"id\":\"pk-backup\"}]}")));

            final Path zipFile = tempDir.resolve("volo-backup-sample.zip");
            try (OutputStream os = Files.newOutputStream(zipFile);
                 ZipOutputStream zos = new ZipOutputStream(os, StandardCharsets.UTF_8)) {
                zos.putNextEntry(new ZipEntry("data.json"));
                zos.write(backupData.toString().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }

            final AtomicInteger preBackups = new AtomicInteger(0);
            final BackupRestoreService restoreService = new BackupRestoreService();
            wireRepositories(restoreService, options, users, articles, follows, revisions, empty);
            setField(restoreService, "localRollingBackupService", new LocalRollingBackupService() {
                @Override
                public JSONObject runBackup(final boolean manual) {
                    return new JSONObject().put("name", "volo-backup-pre-" + preBackups.incrementAndGet() + ".zip");
                }
            });

            final JSONObject inspection = restoreService.inspectBackupZip(zipFile);
            Assert.assertEquals(inspection.getJSONObject("counts").getInt("articles"), 1);
            Assert.assertEquals(inspection.getJSONObject("counts").getInt("follows"), 1);
            Assert.assertEquals(inspection.getJSONObject("counts").getInt("revisions"), 1);
            Assert.assertTrue(inspection.getBoolean("hasAdminUser"));

            // Mode 1: Safe restore (overwriteAuth = false) -> restores content, preserves live admin & passkey!
            final JSONObject safeRes = restoreService.restoreFromBackupZip(zipFile, false);
            Assert.assertEquals(preBackups.get(), 1, "Restore must create an automatic pre-restore snapshot first");
            Assert.assertFalse(safeRes.optBoolean("reloginRequired"));
            Assert.assertNotNull(articles.rows.get("art-restored"));
            Assert.assertEquals(articles.rows.get("art-restored").getString(Article.ARTICLE_AUTHOR_ID), "admin-live",
                    "Safe restore must remap orphaned backup admin authorId to preserved live admin");
            Assert.assertNotNull(users.rows.get("admin-live"), "Live admin must be preserved when overwriteAuth=false");
            Assert.assertEquals(users.rows.get("admin-live").getString(User.USER_PASSWORD), "HASH_LIVE_SECRET");
            Assert.assertTrue(options.rows.get(Option.ID_C_VOLO_PASSKEY_AUTH).getString(Option.OPTION_VALUE).contains("pk-live"),
                    "Live Passkey credentials must be preserved when overwriteAuth=false");

            // Mode 2: Full overwrite (overwriteAuth = true) -> adopts backup admin & backup auth options
            final JSONObject fullRes = restoreService.restoreFromBackupZip(zipFile, true);
            Assert.assertEquals(preBackups.get(), 2);
            Assert.assertTrue(fullRes.optBoolean("reloginRequired"));
            Assert.assertNull(users.rows.get("admin-live"));
            Assert.assertNotNull(users.rows.get("admin-from-backup"));
            Assert.assertEquals(users.rows.get("admin-from-backup").getString(User.USER_PASSWORD), "HASH_BACKUP_OLD");
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    public void batchMarkdownAndZipImportHandlesDuplicatesAndUniqueIds() throws Exception {
        final Path tempDir = Files.createTempDirectory("volo-batch-import-");
        try {
            final Path md1 = tempDir.resolve("first.md");
            Files.writeString(md1,
                    "---\ntitle: First Batch Post\npermalink: /posts/batch-one.html\ntags:\n  - Volo\n---\nHello world 1",
                    StandardCharsets.UTF_8);

            final Path mdDup = tempDir.resolve("dup.md");
            Files.writeString(mdDup,
                    "---\ntitle: Duplicate Permalink Post\npermalink: /posts/batch-one.html\n---\nHello duplicate",
                    StandardCharsets.UTF_8);

            final Path zipBundle = tempDir.resolve("bundle.zip");
            try (OutputStream os = Files.newOutputStream(zipBundle);
                 ZipOutputStream zos = new ZipOutputStream(os, StandardCharsets.UTF_8)) {
                zos.putNextEntry(new ZipEntry("nested/second.md"));
                zos.write("---\ntitle: Zipped Post\n---\nFrom zip archive".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }

            final Set<String> seenIds = new HashSet<>();
            final Map<String, JSONObject> byPermalink = new LinkedHashMap<>();
            final List<JSONObject> added = new ArrayList<>();

            final ImportService importService = new ImportService();
            setField(importService, "userQueryService", new UserQueryService() {
                @Override
                public JSONObject getAdmin() {
                    return new JSONObject().put(Keys.OBJECT_ID, "admin-1");
                }
            });
            setField(importService, "articleRepository", new ArticleRepository() {
                @Override
                public JSONObject getByPermalink(final String permalink) {
                    return byPermalink.get(permalink);
                }
            });
            setField(importService, "permalinkQueryService", new PermalinkQueryService() {
                @Override
                public boolean exist(final String permalink) {
                    return byPermalink.containsKey(permalink);
                }
            });
            setField(importService, "articleMgmtService", new ArticleMgmtService() {
                @Override
                public String addArticle(final JSONObject request) {
                    final JSONObject a = request.getJSONObject(Article.ARTICLE);
                    final String id = a.getString(Keys.OBJECT_ID);
                    Assert.assertTrue(seenIds.add(id), "Every imported article must get a unique non-colliding oId");
                    final String link = a.optString(Article.ARTICLE_PERMALINK, "/articles/" + id + ".html");
                    byPermalink.put(link, a);
                    added.add(a);
                    return id;
                }
            });

            // 1. skipDuplicatePermalink = true -> imports first.md + nested/second.md, skips dup.md
            final JSONObject summarySkip = importService.importBatchFiles(List.of(md1, mdDup, zipBundle), true, true);
            Assert.assertEquals(summarySkip.getInt("imported"), 2);
            Assert.assertEquals(summarySkip.getInt("skipped"), 1);
            Assert.assertEquals(summarySkip.getInt("failed"), 0);

            // 2. skipDuplicatePermalink = false -> imports dup.md with deduplicated suffix permalink
            final JSONObject summaryCopy = importService.importBatchFiles(List.of(mdDup), false, false);
            Assert.assertEquals(summaryCopy.getInt("imported"), 1);
            final JSONObject copied = added.get(added.size() - 1);
            Assert.assertEquals(added.get(0).optString(Article.ARTICLE_PERMALINK), "/batch-one.html");
            Assert.assertEquals(copied.optString(Article.ARTICLE_PERMALINK), "/batch-one-2.html");
            Assert.assertEquals(copied.getInt(Article.ARTICLE_STATUS), Article.ARTICLE_STATUS_C_DRAFT);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static final class InMemoryTable {
        final Map<String, JSONObject> rows = new LinkedHashMap<>();

        void put(final JSONObject row) {
            rows.put(row.getString(Keys.OBJECT_ID), new JSONObject(row.toString()));
        }

        JSONObject queryAll() {
            final JSONArray arr = new JSONArray();
            for (final JSONObject r : rows.values()) {
                arr.put(new JSONObject(r.toString()));
            }
            return new JSONObject().put(Keys.RESULTS, arr);
        }
    }

    private static void wireRepositories(final Object target,
                                         final InMemoryTable options,
                                         final InMemoryTable users,
                                         final InMemoryTable articles,
                                         final InMemoryTable follows,
                                         final InMemoryTable revisions,
                                         final InMemoryTable empty) throws Exception {
        setField(target, "optionRepository", new OptionRepository() {
            @Override public Transaction beginTransaction() { return noopTx(); }
            @Override public JSONObject get(final String id) { return options.rows.get(id); }
            @Override public JSONObject get(final Query q) { return options.queryAll(); }
            @Override public String add(final JSONObject o) { options.put(o); return o.getString(Keys.OBJECT_ID); }
            @Override public void remove(final String id) { options.rows.remove(id); }
        });
        setField(target, "userRepository", new UserRepository() {
            @Override public JSONObject get(final String id) { return users.rows.get(id); }
            @Override public JSONObject get(final Query q) { return users.queryAll(); }
            @Override public String add(final JSONObject o) { users.put(o); return o.getString(Keys.OBJECT_ID); }
            @Override public void remove(final String id) { users.rows.remove(id); }
        });
        setField(target, "articleRepository", new ArticleRepository() {
            @Override public JSONObject get(final String id) { return articles.rows.get(id); }
            @Override public JSONObject get(final Query q) { return articles.queryAll(); }
            @Override public String add(final JSONObject o) { articles.put(o); return o.getString(Keys.OBJECT_ID); }
            @Override public void remove(final String id) { articles.rows.remove(id); }
        });
        setField(target, "followRepository", new FollowRepository() {
            @Override public JSONObject get(final Query q) { return follows.queryAll(); }
            @Override public String add(final JSONObject o) { follows.put(o); return o.getString(Keys.OBJECT_ID); }
            @Override public void remove(final String id) { follows.rows.remove(id); }
        });
        setField(target, "articleRevisionRepository", new ArticleRevisionRepository() {
            @Override public JSONObject get(final Query q) { return revisions.queryAll(); }
            @Override public String add(final JSONObject o) { revisions.put(o); return o.getString(Keys.OBJECT_ID); }
            @Override public void remove(final String id) { revisions.rows.remove(id); }
        });
        setField(target, "archiveDateRepository", new ArchiveDateRepository() {
            @Override public JSONObject get(final Query q) { return empty.queryAll(); }
            @Override public String add(final JSONObject o) { return o.optString(Keys.OBJECT_ID); }
            @Override public void remove(final String id) { }
        });
        setField(target, "archiveDateArticleRepository", new ArchiveDateArticleRepository() {
            @Override public JSONObject get(final Query q) { return empty.queryAll(); }
            @Override public String add(final JSONObject o) { return o.optString(Keys.OBJECT_ID); }
            @Override public void remove(final String id) { }
        });
        setField(target, "categoryRepository", new CategoryRepository() {
            @Override public JSONObject get(final Query q) { return empty.queryAll(); }
            @Override public String add(final JSONObject o) { return o.optString(Keys.OBJECT_ID); }
            @Override public void remove(final String id) { }
        });
        setField(target, "categoryTagRepository", new CategoryTagRepository() {
            @Override public JSONObject get(final Query q) { return empty.queryAll(); }
            @Override public String add(final JSONObject o) { return o.optString(Keys.OBJECT_ID); }
            @Override public void remove(final String id) { }
        });
        setField(target, "commentRepository", new CommentRepository() {
            @Override public JSONObject get(final Query q) { return empty.queryAll(); }
            @Override public String add(final JSONObject o) { return o.optString(Keys.OBJECT_ID); }
            @Override public void remove(final String id) { }
        });
        setField(target, "linkRepository", new LinkRepository() {
            @Override public JSONObject get(final Query q) { return empty.queryAll(); }
            @Override public String add(final JSONObject o) { return o.optString(Keys.OBJECT_ID); }
            @Override public void remove(final String id) { }
        });
        setField(target, "pageRepository", new PageRepository() {
            @Override public JSONObject get(final Query q) { return empty.queryAll(); }
            @Override public String add(final JSONObject o) { return o.optString(Keys.OBJECT_ID); }
            @Override public void remove(final String id) { }
        });
        setField(target, "pluginRepository", new PluginRepository() {
            @Override public JSONObject get(final Query q) { return empty.queryAll(); }
            @Override public String add(final JSONObject o) { return o.optString(Keys.OBJECT_ID); }
            @Override public void remove(final String id) { }
        });
        setField(target, "tagRepository", new TagRepository() {
            @Override public JSONObject get(final Query q) { return empty.queryAll(); }
            @Override public String add(final JSONObject o) { return o.optString(Keys.OBJECT_ID); }
            @Override public void remove(final String id) { }
        });
        setField(target, "tagArticleRepository", new TagArticleRepository() {
            @Override public JSONObject get(final Query q) { return empty.queryAll(); }
            @Override public String add(final JSONObject o) { return o.optString(Keys.OBJECT_ID); }
            @Override public void remove(final String id) { }
        });
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
