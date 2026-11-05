/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.security;

import org.b3log.latke.Keys;
import org.b3log.solo.model.Article;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.repository.CategoryRepository;
import org.b3log.solo.repository.CategoryTagRepository;
import org.b3log.solo.repository.TagRepository;
import org.b3log.solo.service.HiddenContentService;
import org.b3log.solo.service.InstantSearchService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.lang.reflect.Field;

public class HiddenContentZeroLeakTest {

    private static final String SECRET_USER_TEXT = "TOP_SECRET_READER_ONLY_TOKEN_991";
    private static final String SECRET_PWD_TEXT = "TOP_SECRET_PASSWORD_LOCKED_TOKEN_882";
    private static final String SECRET_COVER_IMG = "https://cdn.example.com/secret-hidden-diagram.png";
    private static final String PUBLIC_COVER_IMG = "https://cdn.example.com/public-architecture.png";

    private static final String SAMPLE_MARKDOWN = """
            公开导读段落：欢迎阅读 Volo 架构设计解析。
            ![公开架构图](%s)

            ```markdown
            :::lock type="user"
            代码块里的示例语法不应被解析为真实锁块
            :::
            ```

            :::lock type="user" hint="注册登录后免费查看核心配置"
            %s
            ![隐藏图](%s)
            :::

            :::lock type="pwd" pwd="Volo#Pass2026" hint="关注公众号回复暗号获取口令"
            %s
            :::
            """.formatted(PUBLIC_COVER_IMG, SECRET_USER_TEXT, SECRET_COVER_IMG, SECRET_PWD_TEXT);

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
    public void anonymousHtmlExcerptCoverFeedAndSearchNeverLeakLockedContent() throws Exception {
        final HiddenContentService svc = new HiddenContentService();
        final String anonHtml = svc.renderMarkdown("art-1", SAMPLE_MARKDOWN, true, null, false);
        Assert.assertFalse(anonHtml.contains(SECRET_USER_TEXT), "Anonymous HTML must not contain user-locked text");
        Assert.assertFalse(anonHtml.contains(SECRET_PWD_TEXT), "Anonymous HTML must not contain password-locked text");
        Assert.assertFalse(anonHtml.contains(SECRET_COVER_IMG), "Anonymous HTML must not contain locked images");
        Assert.assertFalse(anonHtml.contains("Volo#Pass2026"), "Lock password attribute must never appear in HTML");
        Assert.assertTrue(anonHtml.contains("volo-lock-card--user"));
        Assert.assertTrue(anonHtml.contains("volo-lock-card--pwd"));
        Assert.assertTrue(anonHtml.contains("代码块里的示例语法不应被解析为真实锁块"), "Fenced code examples remain intact");

        // Abstract & first-image extraction zero leak
        final String abstractText = Article.getAbstractText(SAMPLE_MARKDOWN);
        Assert.assertFalse(abstractText.contains(SECRET_USER_TEXT));
        Assert.assertFalse(abstractText.contains(SECRET_PWD_TEXT));

        final JSONObject lockFirstArticle = new JSONObject()
                .put(Article.ARTICLE_CONTENT, ":::lock type=\"user\"\n![secret](" + SECRET_COVER_IMG + ")\n:::\n\n![pub](" + PUBLIC_COVER_IMG + ")");
        final String extractedCover = Article.getArticleImg1URL(lockFirstArticle);
        Assert.assertFalse(extractedCover.contains(SECRET_COVER_IMG),
                "Cover image extractor must never return locked images");
        Assert.assertTrue(extractedCover.startsWith(PUBLIC_COVER_IMG),
                "Cover image extractor must skip images inside locked blocks and pick the public image");

        // RSS/Atom feed stripping
        final String feedMd = HiddenContentService.stripForFeed(SAMPLE_MARKDOWN);
        Assert.assertFalse(feedMd.contains(SECRET_USER_TEXT));
        Assert.assertFalse(feedMd.contains(SECRET_PWD_TEXT));
        Assert.assertTrue(feedMd.contains("受保护的隐藏内容"));

        // Public search oracle prevention
        final JSONObject article = new JSONObject()
                .put(Keys.OBJECT_ID, "art-1")
                .put(Article.ARTICLE_TITLE, "Volo 架构设计")
                .put(Article.ARTICLE_TAGS_REF, "Java,Netty")
                .put(Article.ARTICLE_PERMALINK, "/articles/volo-arch.html")
                .put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED)
                .put(Article.ARTICLE_VIEW_PWD, "")
                .put(Article.ARTICLE_ABSTRACT, SAMPLE_MARKDOWN)
                .put(Article.ARTICLE_CONTENT, SAMPLE_MARKDOWN)
                .put(Article.ARTICLE_UPDATED, 1_760_000_000_000L);
        Assert.assertTrue(HiddenContentService.matchesPublicSearch(article, "架构设计"));
        Assert.assertFalse(HiddenContentService.matchesPublicSearch(article, SECRET_USER_TEXT));
        Assert.assertFalse(HiddenContentService.matchesPublicSearch(article, SECRET_PWD_TEXT));

        // InstantSearchService snapshot zero leak
        final InstantSearchService instant = new InstantSearchService();
        setField(instant, "articleRepository", new ArticleRepository() {
            @Override
            public JSONObject get(final org.b3log.latke.repository.Query query) {
                return new JSONObject().put(Keys.RESULTS, new JSONArray().put(article));
            }
        });
        setField(instant, "categoryRepository", new CategoryRepository() {
            @Override
            public JSONObject get(final org.b3log.latke.repository.Query query) {
                return new JSONObject().put(Keys.RESULTS, new JSONArray());
            }
        });
        setField(instant, "categoryTagRepository", new CategoryTagRepository() {
            @Override
            public JSONObject get(final org.b3log.latke.repository.Query query) {
                return new JSONObject().put(Keys.RESULTS, new JSONArray());
            }
        });
        setField(instant, "tagRepository", new TagRepository() {
            @Override
            public JSONObject get(final org.b3log.latke.repository.Query query) {
                return new JSONObject().put(Keys.RESULTS, new JSONArray());
            }
        });

        Assert.assertEquals(instant.search(SECRET_USER_TEXT, "", "", 10).getJSONArray("items").length(), 0);
        Assert.assertEquals(instant.search(SECRET_PWD_TEXT, "", "", 10).getJSONArray("items").length(), 0);
        final JSONArray pubHits = instant.search("架构设计", "", "", 10).getJSONArray("items");
        Assert.assertEquals(pubHits.length(), 1);
        final JSONObject hit = pubHits.getJSONObject(0);
        Assert.assertTrue(hit.getString("titleHtml").contains("<mark>"));
        Assert.assertTrue(hit.getString("snippetHtml").contains("<mark>"));
        Assert.assertFalse(hit.getString("snippetHtml").contains(SECRET_USER_TEXT));
        Assert.assertFalse(hit.getString("snippetHtml").contains(SECRET_PWD_TEXT));
    }

    @Test
    public void passwordUnlockVerifiesInConstantTimeAndRendersTargetBlockOnly() {
        final HiddenContentService svc = new HiddenContentService();
        final HiddenContentService.UnlockAttemptResult wrong =
                svc.verifyAndRenderPwdBlock(SAMPLE_MARKDOWN, 1, "wrong-pwd", true);
        Assert.assertTrue(wrong.found());
        Assert.assertFalse(wrong.matched());
        Assert.assertEquals(wrong.html(), "");

        final HiddenContentService.UnlockAttemptResult right =
                svc.verifyAndRenderPwdBlock(SAMPLE_MARKDOWN, 1, "Volo#Pass2026", true);
        Assert.assertTrue(right.found());
        Assert.assertTrue(right.matched());
        Assert.assertTrue(right.html().contains(SECRET_PWD_TEXT));
        Assert.assertFalse(right.html().contains(SECRET_USER_TEXT), "Unlocking block #1 must not reveal block #0");
    }

    @Test
    public void deterministicPlaceholderTokensReuseCacheAndPublicHexoExportStripsLockedBlocks() throws Exception {
        final HiddenContentService svc = new HiddenContentService();
        final String firstHtml = svc.renderMarkdown("art-1", SAMPLE_MARKDOWN, true, null, false);
        final String secondHtml = svc.renderMarkdown("art-1", SAMPLE_MARKDOWN, true, null, false);
        Assert.assertEquals(firstHtml, secondHtml, "Rendered locked HTML must be deterministic for identical input");
        Assert.assertFalse(firstHtml.contains(SECRET_USER_TEXT));
        Assert.assertFalse(firstHtml.contains(SECRET_PWD_TEXT));

        final org.b3log.solo.service.ExportService exportSvc = new org.b3log.solo.service.ExportService();
        setField(exportSvc, "articleRepository", new ArticleRepository() {
            @Override
            public JSONObject get(final org.b3log.latke.repository.Query query) {
                return new JSONObject().put(Keys.RESULTS, new JSONArray().put(new JSONObject()
                        .put(Keys.OBJECT_ID, "art-exp-1")
                        .put(Article.ARTICLE_TITLE, "Export Lock Test")
                        .put(Article.ARTICLE_CONTENT, SAMPLE_MARKDOWN)
                        .put(Article.ARTICLE_TAGS_REF, "架构")
                        .put(Article.ARTICLE_PERMALINK, "/articles/export-lock.html")
                        .put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED)
                        .put(Article.ARTICLE_CREATED, 1730000000000L)
                        .put(Article.ARTICLE_UPDATED, 1730000000000L)));
            }
        });

        @SuppressWarnings("unchecked")
        final java.util.List<JSONObject> publicPosts =
                (java.util.List<JSONObject>) exportSvc.exportHexoMDs(true).get("posts");
        Assert.assertEquals(publicPosts.size(), 1);
        final String strippedContent = publicPosts.get(0).getString("content");
        Assert.assertFalse(strippedContent.contains(SECRET_USER_TEXT), "Public export must strip reader-locked content");
        Assert.assertFalse(strippedContent.contains(SECRET_PWD_TEXT), "Public export must strip password-locked content");
        Assert.assertFalse(strippedContent.contains("Volo#Pass2026"), "Public export must strip inline lock passwords");

        @SuppressWarnings("unchecked")
        final java.util.List<JSONObject> fullBackupPosts =
                (java.util.List<JSONObject>) exportSvc.exportHexoMDs(false).get("posts");
        Assert.assertTrue(fullBackupPosts.get(0).getString("content").contains("Volo#Pass2026"),
                "Private admin archive backup must preserve raw :::lock::: source");
    }

    private static void setField(final Object target, final String name, final Object value) throws Exception {
        final Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
