/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.processor;

import freemarker.template.Configuration;
import freemarker.template.TemplateExceptionHandler;
import freemarker.template.Template;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.Map;
import java.util.List;
import java.util.Properties;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Comment;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.testng.Assert;
import org.testng.annotations.Test;

public class CommentTemplateTest {
    @Test public void nativeManagementSlotsHideGuestsByDefaultAndKeepAdministratorControlsIndependent() throws Exception {
        final Configuration configuration = new Configuration(Configuration.VERSION_2_3_35);
        configuration.setDirectoryForTemplateLoading(Path.of("src/main/webapp").toFile());
        final Template template = new Template("management", new StringReader("<#include 'common-template/macro-management.ftl'><ul><@managementLinks itemTag='li' panelIcon='settings' exitIcon='logout' loginIcon='login'/></ul>"), configuration);
        for (final boolean administrator : new boolean[]{false, true}) for (final boolean showLogin : new boolean[]{false, true}) {
            final Map<String, Object> model = new java.util.HashMap<>();
            model.put("isAdminLoggedIn", administrator); model.put("showLoginEntry", showLogin);
            if (administrator) { model.put("adminPanelURL", "/custom-panel"); model.put("logoutURL", "/logout"); }
            if (showLogin) model.put("adminLoginURL", "/custom-panel/login");
            final StringWriter output = new StringWriter(); template.process(model, output);
            final var html = Jsoup.parseBodyFragment(output.toString());
            Assert.assertEquals(html.select("a[data-management-link=panel]").size(), administrator ? 1 : 0);
            Assert.assertEquals(html.select("a[data-management-link=logout]").size(), administrator ? 1 : 0);
            Assert.assertEquals(html.select("a[data-management-link=login]").size(), !administrator && showLogin ? 1 : 0);
            Assert.assertEquals(html.select("li:empty").size(), 0, "Hidden controls must leave no empty menu entries");
        }
    }
    @Test public void studioAdminControlsAndRecentCommentLinksAreNotRenderedForGuests() throws Exception {
        final Configuration configuration = new Configuration(Configuration.VERSION_2_3_35);
        configuration.setDirectoryForTemplateLoading(Path.of("src/main/webapp").toFile());
        final Map<String, Object> model = new java.util.HashMap<>();
        model.put("servePath", "http://localhost"); model.put("blogTitle", "博客"); model.put("pageNavigations", List.of());
        model.put("interactive", "true"); model.put("mostUsedTags", List.of()); model.put("studioIndex", true);
        model.put("recentComments", List.of(Map.of("commentName", "昵称<img>", "commentContentText", "正文<script>", "commentSharpURL", "/post.html#123")));
        for (final boolean administrator : new boolean[]{false, true}) {
            model.put("isAdminLoggedIn", administrator);
            if (administrator) { model.put("adminPanelURL", "/private-panel"); model.put("logoutURL", "/logout"); }
            model.put("pageNavigations", administrator ? List.of(
                    Map.of("pageTitle", "自定义链接", "pagePermalink", "/custom", "pageOpenTarget", "_self", "pageIcon", ""),
                    Map.of("pageTitle", "后台管理", "pagePermalink", "/private-panel", "pageOpenTarget", "_self", "pageIcon", ""),
                    Map.of("pageTitle", "退出登录", "pagePermalink", "/logout", "pageOpenTarget", "_self", "pageIcon", "")) : List.of());
            final StringWriter header = new StringWriter(), side = new StringWriter();
            configuration.getTemplate("skins/volo-studio/header.ftl").process(model, header);
            configuration.getTemplate("skins/volo-studio/side.ftl").process(model, side);
            Assert.assertEquals(header.toString().contains("href=\"/logout\""), administrator);
            Assert.assertEquals(header.toString().contains("href=\"/private-panel\""), administrator);
            final var headerDocument = Jsoup.parse(header.toString());
            Assert.assertEquals(headerDocument.select(".studio-management-mobile a").size(), administrator ? 2 : 0);
            Assert.assertTrue(headerDocument.select(".studio-nav-panel a[href='/logout'],.studio-nav-panel a[href='/private-panel']").isEmpty(), "Mobile administrator actions must remain outside More");
            Assert.assertEquals(side.toString().contains("studio-recent-comments"), administrator);
            if (administrator) {
                Assert.assertTrue(side.toString().contains("/post.html#123"));
                Assert.assertTrue(side.toString().contains("昵称&lt;img&gt;"));
                Assert.assertFalse(side.toString().contains("<script>"));
            }
        }
    }
    @Test public void newCommentsAndRepliesRenderInEverySkinWithoutOptionalStoredFields() throws Exception {
        final Configuration configuration = new Configuration(Configuration.VERSION_2_3_35);
        configuration.setDirectoryForTemplateLoading(Path.of("src/main/webapp").toFile());
        configuration.setLogTemplateExceptions(false);
        configuration.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        final Properties language = new Properties();
        try (var input = Files.newInputStream(Path.of("src/main/resources/lang_zh_CN.properties"))) { language.load(input); }
        try (var skins = Files.list(Path.of("src/main/webapp/skins"))) {
            for (final Path skin : skins.filter(path -> Files.isRegularFile(path.resolve("skin.properties"))).toList()) {
                for (final boolean reply : new boolean[]{false, true}) {
                    final JSONObject comment = new JSONObject().put("oId", "new-comment").put("isReply", reply)
                            .put("commentName", "访客'<>&").put("commentURL", "https://example.test/?a=1&b=2")
                            .put("commentDate2", new Date()).put("commentContent", "<p>新评论正文</p>")
                            .put("commentThumbnailURL", "").put("commentable", true).put("permalink", "/article.html");
                    if (reply) comment.put("commentOriginalCommentId", "parent").put("commentOriginalCommentName", "被回复者'<>&");
                    final Map<String, Object> model = CommentProcessor.commentTemplateModel(comment);
                    language.forEach((key, value) -> model.put(key.toString(), value));
                    final Path localized = skin.resolve("lang/lang_zh_CN.properties");
                    if (Files.isRegularFile(localized)) {
                        final Properties translated = new Properties();
                        try (var input = Files.newInputStream(localized)) { translated.load(input); }
                        translated.forEach((key, value) -> model.put(key.toString(), value));
                    }
                    model.put("servePath", "http://localhost");
                    model.put("staticServePath", "http://localhost");
                    final StringWriter output = new StringWriter();
                    configuration.getTemplate("skins/" + skin.getFileName() + "/common-comment.ftl").process(model, output);
                    final String html = output.toString();
                    Assert.assertTrue(html.contains("data-comment-id=\"new-comment\""), skin.toString());
                    Assert.assertTrue(html.contains("新评论正文"), skin.toString());
                    Assert.assertTrue(html.contains("访客&#39;&lt;&gt;&amp;"), skin.toString());
                    Assert.assertFalse(html.contains("undefined"), skin.toString());
                }
            }
        }
    }

    @Test public void commentTemplateOnlyReceivesThePublicArticleFields() {
        final Map<String, Object> model = CommentProcessor.commentTemplateModel(new JSONObject()
                .put("commentable", true).put("permalink", "/article.html"));
        Assert.assertEquals(model.get(Article.ARTICLE), Map.of("commentable", true, "permalink", "/article.html"));
        Assert.assertTrue(model.get(Comment.COMMENT) instanceof Map);
    }

    @Test public void groupedDiscussionHasARealListContainerForInitialAndAjaxComments() throws Exception {
        final Configuration configuration = new Configuration(Configuration.VERSION_2_3_35);
        configuration.setDirectoryForTemplateLoading(Path.of("src/main/webapp").toFile());
        final Map<String, Object> comment = Map.of("oId", "parent", "commentThreadId", "parent", "commentDepth", 0,
                "isReply", false, "commentName", "访客", "commentURL", "", "commentDate2", new Date(), "commentContent", "<p>正文</p>");
        final Map<String, Object> model = Map.of("commentList", List.of(comment),
                "article", Map.of("commentable", false, "permalink", "/article.html"), "replyLabel", "回复", "commentLabel", "评论");
        for (final String skin : List.of("bolo-sakura", "bolo-xups", "bolo-yilia")) {
            final String source = "<#import 'skins/" + skin + "/macro-comments.ftl' as skin>"
                    + "<@skin.comments commentList=commentList article=article" + (skin.equals("bolo-sakura") ? " count=1" : "") + "/>";
            final StringWriter output = new StringWriter();
            new Template("discussion.ftl", new StringReader(source), configuration).process(model, output);
            final var document = Jsoup.parse(output.toString());
            final var list = document.getElementById("comments");
            Assert.assertNotNull(list, skin);
            Assert.assertEquals(list.tagName(), "ul", skin + " must anchor insertions on the real list");
            Assert.assertEquals(list.childrenSize(), 1, skin);
            Assert.assertTrue(list.child(0).hasAttr("data-comment-depth"), skin);
            Assert.assertEquals(list.select(".thread-comment-header .comment-author").size(), 1, skin);
        }
    }
}
