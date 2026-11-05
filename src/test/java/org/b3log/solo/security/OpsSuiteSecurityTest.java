/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.security;

import org.b3log.latke.Keys;
import org.b3log.latke.repository.Transaction;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Option;
import org.b3log.solo.model.rss.Item;
import org.b3log.solo.processor.console.OpsConsole;
import org.b3log.solo.repository.OptionRepository;
import org.b3log.solo.service.LocalRollingBackupService;
import org.b3log.solo.service.WebhookNotifyService;
import org.b3log.solo.util.ReadingStatsUtil;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class OpsSuiteSecurityTest {

    @BeforeClass
    public void setup() throws Exception {
        LatkeTestSupport.configure();
        final var local = new java.util.Properties();
        local.setProperty("runtimeDatabase", "NONE");
        local.setProperty("cookieHttpOnly", "true");
        local.setProperty("loginTrustedProxies", "127.0.0.1,::1");
        org.b3log.latke.Latkes.setLocalProps(local);
    }

    @org.testng.annotations.AfterClass
    public void restore() throws Exception {
        LatkeTestSupport.configure();
    }

    @Test
    public void webhookServiceBlocksSsrfTargetsAndMasksSecretsAcrossReadsAndExports() throws Exception {
        for (final String blockedUrl : new String[]{
                "http://127.0.0.1:8080/hook",
                "http://localhost/hook",
                "http://169.254.169.254/latest/meta-data/",
                "http://192.168.1.10/webhook",
                "http://10.0.0.5/webhook",
                "http://[::1]:9000/hook",
                "ftp://example.com/hook"
        }) {
            Assert.expectThrows(IllegalArgumentException.class,
                    () -> WebhookNotifyService.validateWebhookUrl(blockedUrl));
        }

        final Map<String, JSONObject> store = new HashMap<>();
        final WebhookNotifyService svc = new WebhookNotifyService();
        final Field repoField = WebhookNotifyService.class.getDeclaredField("optionRepository");
        repoField.setAccessible(true);
        repoField.set(svc, new OptionRepository() {
            @Override
            public Transaction beginTransaction() {
                return new Transaction() {
                    private boolean active = true;
                    @Override public String getId() { return "tx"; }
                    @Override public void commit() { active = false; }
                    @Override public void rollback() { active = false; }
                    @Override public boolean isActive() { return active; }
                };
            }
            @Override public JSONObject get(final String id) { return store.get(id); }
            @Override public String add(final JSONObject obj) {
                store.put(obj.getString(Keys.OBJECT_ID), new JSONObject(obj.toString()));
                return obj.getString(Keys.OBJECT_ID);
            }
            @Override public void update(final String id, final JSONObject obj, final String... names) {
                store.put(id, new JSONObject(obj.toString()));
            }
        });

        // Verify secret masking helper
        final String rawWithSecret = new JSONArray()
                .put(new JSONObject().put("id", "wh_1").put("name", "Ops").put("secret", "SUPER_SECRET_KEY_777"))
                .toString();
        final String maskedExport = WebhookNotifyService.maskJsonArrayString(rawWithSecret);
        Assert.assertFalse(maskedExport.contains("SUPER_SECRET_KEY_777"), "Exported webhook JSON must strip raw secrets");
        Assert.assertTrue(maskedExport.contains("\"secretSet\":true"));
    }

    @Test
    public void localBackupAndMediaLibraryRejectPathTraversalAndPruneExcessArchives() throws Exception {
        // Backup directory validation blocks traversal and protected project folders
        Assert.expectThrows(IllegalArgumentException.class,
                () -> LocalRollingBackupService.resolveSafeBackupDir("../outside"));
        Assert.expectThrows(IllegalArgumentException.class,
                () -> LocalRollingBackupService.resolveSafeBackupDir("src"));
        Assert.expectThrows(IllegalArgumentException.class,
                () -> LocalRollingBackupService.resolveSafeBackupDir("."));

        final Path tempDir = Files.createTempDirectory("volo-backup-test-");
        try {
            for (int i = 1; i <= 5; i++) {
                Files.writeString(tempDir.resolve(String.format("volo-backup-2026110%d-020000.zip", i)),
                        "zip-" + i, StandardCharsets.UTF_8);
            }
            final int pruned = LocalRollingBackupService.pruneOldBackups(tempDir, 2);
            Assert.assertEquals(pruned, 3, "Pruning with keepCount=2 must remove oldest 3 archives");
            Assert.assertTrue(Files.exists(tempDir.resolve("volo-backup-20261105-020000.zip")));
            Assert.assertTrue(Files.exists(tempDir.resolve("volo-backup-20261104-020000.zip")));
            Assert.assertFalse(Files.exists(tempDir.resolve("volo-backup-20261101-020000.zip")));

            Assert.expectThrows(IllegalArgumentException.class,
                    () -> LocalRollingBackupService.resolveValidatedBackupFileInDir(tempDir.toString(), "../evil.zip"));
            Assert.expectThrows(IllegalArgumentException.class,
                    () -> LocalRollingBackupService.resolveValidatedBackupFileInDir(tempDir.toString(), "not-backup.zip"));
        } finally {
            try (var s = Files.list(tempDir)) {
                for (final Path p : s.toList()) Files.deleteIfExists(p);
            }
            Files.deleteIfExists(tempDir);
        }

        // Media library path traversal & extension guard
        final Path mediaDir = Files.createTempDirectory("volo-media-test-");
        try {
            final Path validImg = mediaDir.resolve("20261105120000.png");
            Files.writeString(validImg, "png", StandardCharsets.UTF_8);
            final Path nonImg = mediaDir.resolve("secret.properties");
            Files.writeString(nonImg, "db=pwd", StandardCharsets.UTF_8);

            Assert.assertEquals(OpsConsole.resolveSafeMediaFile(mediaDir, "20261105120000.png"), validImg.toRealPath());
            Assert.expectThrows(IllegalArgumentException.class,
                    () -> OpsConsole.resolveSafeMediaFile(mediaDir, "../20261105120000.png"));
            Assert.expectThrows(IllegalArgumentException.class,
                    () -> OpsConsole.resolveSafeMediaFile(mediaDir, "secret.properties"));
        } finally {
            try (var s = Files.list(mediaDir)) {
                for (final Path p : s.toList()) Files.deleteIfExists(p);
            }
            Files.deleteIfExists(mediaDir);
        }
    }

    @Test
    public void readingStatsOutdatedBannerAudioPlayerAndPodcastEnclosureRenderProperly() {
        final String md = "这是一篇关于 Java 17 与 Netty 高性能异步网络编程的深度实践文章，包含 English words counting test。";
        final ReadingStatsUtil.Stats stats = ReadingStatsUtil.compute(md);
        Assert.assertTrue(stats.wordCount() >= 25);
        Assert.assertEquals(stats.readingMinutes(), 1);

        final long twoYearsAgo = System.currentTimeMillis() - 735L * 86_400_000L;
        final JSONObject article = new JSONObject()
                .put(Article.ARTICLE_UPDATED, twoYearsAgo)
                .put(Article.ARTICLE_AUDIO_URL, "https://cdn.example.com/podcast/ep01.mp3")
                .put(Article.ARTICLE_AUDIO_DURATION, "12:34");
        final JSONObject pref = new JSONObject()
                .put(Option.ID_C_ENABLE_READING_STATS, true)
                .put(Option.ID_C_ARTICLE_OUTDATED_DAYS, 365);

        final String widgets = ReadingStatsUtil.buildArticleHeaderWidgets(article, md, pref);
        Assert.assertTrue(widgets.contains("volo-reading-meta"), "Reading stats bar must render");
        Assert.assertTrue(widgets.contains("volo-outdated-notice"), "Outdated technical banner must render for 2-year-old post");
        Assert.assertTrue(widgets.contains("volo-audio-player"), "Podcast single-episode audio player must render");

        // RSS 2.0 <enclosure> output
        final Item rssItem = new Item();
        rssItem.setTitle("Ep 01");
        rssItem.setDescription("Show notes");
        rssItem.setLink("https://blog.example.com/ep01.html");
        rssItem.setGUID("https://blog.example.com/ep01.html");
        rssItem.setAuthor("Willh");
        rssItem.setPubDate(new Date());
        rssItem.setEnclosure("https://cdn.example.com/podcast/ep01.mp3", 10485760L,
                ReadingStatsUtil.inferAudioMimeType("https://cdn.example.com/podcast/ep01.mp3"));
        final String xml = rssItem.toString();
        Assert.assertTrue(xml.contains("<enclosure url=\"https://cdn.example.com/podcast/ep01.mp3\" length=\"10485760\" type=\"audio/mpeg\" />"));

        // Protected backup subdirectory guard & URL-encoded Chinese media orphan matching
        Assert.expectThrows(IllegalArgumentException.class,
                () -> LocalRollingBackupService.resolveSafeBackupDir("src/backups"));
        Assert.expectThrows(IllegalArgumentException.class,
                () -> LocalRollingBackupService.resolveSafeBackupDir("target/sub/backups"));
        Assert.assertTrue(OpsConsole.isMediaReferenced(
                "![封面](/image/%E6%9E%B6%E6%9E%84%20%E5%9B%BE.png)", "架构 图.png"),
                "URL-encoded Chinese/space filenames in Markdown must not be flagged as orphan media");
        Assert.assertFalse(OpsConsole.isMediaReferenced(
                "![封面](/image/other.png)", "架构 图.png"));
    }
}
