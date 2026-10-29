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
