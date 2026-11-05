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
import org.b3log.solo.model.ArticleRevision;
import org.b3log.solo.model.Option;
import org.b3log.solo.repository.ArticleRevisionRepository;
import org.b3log.solo.service.ArticleRevisionService;
import org.b3log.solo.service.OptionMgmtService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.service.SeriesAndBookshelfService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ArticleRevisionAndBookshelfTest {

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
    public void revisionHistoryRetainsLatestTenSnapshotsAndEnforcesArticleOwnership() throws Exception {
        final List<JSONObject> rows = new ArrayList<>();
        final ArticleRevisionService svc = new ArticleRevisionService();
        final Field repoField = ArticleRevisionService.class.getDeclaredField("articleRevisionRepository");
        repoField.setAccessible(true);
        repoField.set(svc, new ArticleRevisionRepository() {
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

            @Override
            public String add(final JSONObject jsonObject) {
                rows.add(new JSONObject(jsonObject.toString()));
                return jsonObject.optString(Keys.OBJECT_ID);
            }

            @Override
            public JSONObject get(final String id) {
                return rows.stream().filter(r -> id.equals(r.optString(Keys.OBJECT_ID))).findFirst().orElse(null);
            }

            @Override
            public List<JSONObject> listByArticleId(final String articleId, final int limit) {
                return rows.stream()
                        .filter(r -> articleId.equals(r.optString(ArticleRevision.ARTICLE_OID)))
                        .sorted(Comparator.comparingLong((JSONObject r) -> r.optLong(ArticleRevision.REVISION_CREATED)).reversed())
                        .limit(limit)
                        .toList();
            }

            @Override
            public void pruneExcess(final String articleId, final int keepCount) {
                final List<JSONObject> ordered = rows.stream()
                        .filter(r -> articleId.equals(r.optString(ArticleRevision.ARTICLE_OID)))
                        .sorted(Comparator.comparingLong((JSONObject r) -> r.optLong(ArticleRevision.REVISION_CREATED)).reversed())
                        .toList();
                for (int i = keepCount; i < ordered.size(); i++) {
                    final String dropId = ordered.get(i).optString(Keys.OBJECT_ID);
                    rows.removeIf(r -> dropId.equals(r.optString(Keys.OBJECT_ID)));
                }
            }
        });

        for (int i = 1; i <= 14; i++) {
            final JSONObject cur = new JSONObject()
                    .put(Keys.OBJECT_ID, "art-100")
                    .put(Article.ARTICLE_TITLE, "Version " + i)
                    .put(Article.ARTICLE_ABSTRACT, "Abs " + i)
                    .put(Article.ARTICLE_CONTENT, "Body content #" + i);
            svc.recordSnapshot(null, cur, "Willh", "Edit " + i);
            Thread.sleep(2L);
        }

        final List<JSONObject> listed = svc.listRevisions("art-100");
        Assert.assertEquals(listed.size(), ArticleRevision.MAX_REVISIONS_PER_ARTICLE,
                "Revision history must cap at 10 versions per article");
        Assert.assertEquals(listed.get(0).getString(ArticleRevision.REVISION_TITLE), "Version 14");

        final String revId = listed.get(0).getString(Keys.OBJECT_ID);
        Assert.assertNotNull(svc.getRevision("art-100", revId));
        Assert.assertNull(svc.getRevision("art-other", revId),
                "Cross-article revision lookup must return null");
    }

    @Test
    public void bookshelfParsesGitBookAndVuePressChapterTreesAndRendersEmbeddedShortcode() throws Exception {
        // 1. GitBook 3 / HonKit HTML structure (matching https://docs.example.com/go/)
        final String gitbookHtml = """
                <html><body>
                  <div class="book-summary">
                    <nav role="navigation">
                      <ul class="summary">
                        <li class="chapter active" data-level="1.1" data-path="./">
                          <a href="./">前言</a>
                        </li>
                        <li class="chapter" data-level="1.2" data-path="ch1/goroutine.html">
                          <a href="ch1/goroutine.html">1. 并发与 Goroutine</a>
                          <ul class="articles">
                            <li class="chapter" data-level="1.2.1" data-path="ch1/channel.html">
                              <a href="ch1/channel.html">1.1 Channel 深入解析</a>
                            </li>
                          </ul>
                        </li>
                        <li><a href="https://www.gitbook.com" class="gitbook-link">本书使用 GitBook 发布</a></li>
                      </ul>
                    </nav>
                  </div>
                </body></html>
                """;
        final JSONArray gbToc = SeriesAndBookshelfService.parseHtmlToToc(
                gitbookHtml, "https://docs.example.com/go/", "gitbook");
        Assert.assertEquals(gbToc.length(), 2, "GitBook branding link must be stripped");
        Assert.assertEquals(gbToc.getJSONObject(1).getString("title"), "1. 并发与 Goroutine");
        Assert.assertEquals(gbToc.getJSONObject(1).getString("url"), "https://docs.example.com/go/ch1/goroutine.html");
        Assert.assertEquals(gbToc.getJSONObject(1).getJSONArray("children").length(), 1);
        Assert.assertEquals(gbToc.getJSONObject(1).getJSONArray("children").getJSONObject(0).getString("url"),
                "https://docs.example.com/go/ch1/channel.html");

        // 2. VuePress 1.8 sidebar structure (matching https://docs.example.com/)
        final String vuepressHtml = """
                <html><body>
                  <aside class="sidebar">
                    <ul class="sidebar-links">
                      <li>
                        <section class="sidebar-group depth-0">
                          <p class="sidebar-heading"><span>Go 工程实践</span></p>
                          <ul class="sidebar-links sidebar-group-items">
                            <li><a href="/guide/intro.html" class="sidebar-link">导读</a></li>
                            <li><a href="/guide/perf.html" class="sidebar-link">性能调优</a></li>
                          </ul>
                        </section>
                      </li>
                    </ul>
                  </aside>
                </body></html>
                """;
        final JSONArray vpToc = SeriesAndBookshelfService.parseHtmlToToc(
                vuepressHtml, "https://docs.example.com/guide/intro.html", "vuepress");
        Assert.assertEquals(vpToc.length(), 1);
        Assert.assertEquals(vpToc.getJSONObject(0).getString("title"), "Go 工程实践");
        Assert.assertEquals(vpToc.getJSONObject(0).getJSONArray("children").length(), 2);

        // 3. Shortcode rendering (:::bookshelf:::)
        final Map<String, JSONObject> optionStore = new HashMap<>();
        final SeriesAndBookshelfService svc = new SeriesAndBookshelfService();
        setField(svc, "optionQueryService", new OptionQueryService() {
            @Override
            public JSONObject getOptionById(final String id) {
                return optionStore.get(id);
            }
        });
        setField(svc, "optionMgmtService", new OptionMgmtService() {
            @Override
            public void storeDocument(final String id, final String category, final String value) {
                optionStore.put(id, new JSONObject()
                        .put(Keys.OBJECT_ID, id)
                        .put(Option.OPTION_CATEGORY, category)
                        .put(Option.OPTION_VALUE, value));
            }
        });

        final JSONObject savedBook = svc.saveBook(new JSONObject()
                .put("id", "gobook")
                .put("title", "Go 语言高级编程")
                .put("author", "Willh")
                .put("sourceType", "manual")
                .put("sourceUrl", "https://docs.example.com/go/")
                .put("embedMode", "iframe"));
        Assert.assertEquals(savedBook.getString("id"), "gobook");

        final var prep = svc.preprocessShortcodes("欢迎访问我的在线书架：\n\n:::bookshelf:::\n");
        final var prepRepeat = svc.preprocessShortcodes("欢迎访问我的在线书架：\n\n:::bookshelf:::\n");
        Assert.assertEquals(prep.markdownWithTokens(), prepRepeat.markdownWithTokens(),
                "Bookshelf shortcode tokens must be deterministic to preserve Markdown cache hits");
        final String rendered = svc.applyShortcodeSlots("<h2>推荐专栏</h2><p>" + prep.markdownWithTokens().trim() + "</p>", prep);
        Assert.assertTrue(rendered.contains("volo-bookshelf"));
        Assert.assertTrue(rendered.contains("Go 语言高级编程"));

        // 4. ToCPlugin must ignore headings inside .volo-bookshelf while indexing real article headings
        final JSONObject tocArticle = new JSONObject().put(Article.ARTICLE_CONTENT, rendered);
        org.b3log.solo.plugin.ToCPlugin.processArticleToc(tocArticle);
        @SuppressWarnings("unchecked")
        final List<JSONObject> tocItems = (List<JSONObject>) tocArticle.get(Article.ARTICLE_T_TOC);
        Assert.assertEquals(tocItems.size(), 1, "Book card <h3> titles must not pollute articleToC");
        Assert.assertEquals(tocItems.get(0).getString("text"), "推荐专栏");
    }

    private static void setField(final Object target, final String name, final Object value) throws Exception {
        final Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
