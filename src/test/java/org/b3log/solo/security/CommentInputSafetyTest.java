/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.security;

import java.lang.reflect.Field;
import java.util.List;
import org.b3log.latke.service.LangPropsService;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Comment;
import org.b3log.solo.model.Common;
import org.b3log.solo.model.Option;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.repository.CommentRepository;
import org.b3log.solo.service.CommentMgmtService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.util.CommentNames;
import org.b3log.solo.util.CommentThreads;
import org.b3log.solo.util.Markdowns;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.Test;

public class CommentInputSafetyTest {
    private static final String PROBE = "<svg/onload=document.body.dataset.p='50d235'>";

    @Test public void aForgedReplyToANonexistentCommentIsAnOrdinaryComment() {
        final java.util.Date date = new java.util.Date();
        final JSONObject stored = comment("one", "a", "283526085278", "reader").put(Comment.COMMENT_ORIGINAL_COMMENT_NAME, PROBE)
                .put(Comment.COMMENT_T_DATE, date);
        final JSONObject shown = CommentThreads.forDisplay(List.of(stored)).get(0);
        Assert.assertFalse(shown.getBoolean(Common.IS_REPLY));
        Assert.assertEquals(shown.getString(Comment.COMMENT_ORIGINAL_COMMENT_NAME), "");
        Assert.assertEquals(shown.getString(Comment.COMMENT_ORIGINAL_COMMENT_ID), "");
        Assert.assertEquals(shown.getInt("commentDepth"), 0);
        Assert.assertSame(shown.get(Comment.COMMENT_T_DATE), date, "Templates must receive their original Date value");
        Assert.assertEquals(stored.getString(Comment.COMMENT_ORIGINAL_COMMENT_NAME), PROBE, "Reading must not modify historical data");
    }

    @Test public void replyNamesComeFromTheActualParentAndNeverFromTheCachedTarget() {
        final JSONObject parent = comment("root", "a", "", "真实读者");
        final JSONObject reply = comment("child", "a", "root", "another").put(Comment.COMMENT_ORIGINAL_COMMENT_NAME, PROBE);
        final var shown = CommentThreads.forDisplay(List.of(parent, reply));
        Assert.assertTrue(shown.get(1).getBoolean(Common.IS_REPLY));
        Assert.assertEquals(shown.get(1).getString(Comment.COMMENT_ORIGINAL_COMMENT_NAME), "真实读者");
        Assert.assertFalse(shown.toString().contains(PROBE));
        parent.put("commentDeleted", true);
        Assert.assertTrue(CommentThreads.forDisplay(List.of(parent, reply)).get(1).getBoolean(Common.IS_REPLY), "A soft-deleted parent keeps its discussion");
    }

    @Test public void crossArticleSelfReferencesAndCyclesNeverForgeRootReplyLabels() {
        final var shown = CommentThreads.forDisplay(List.of(comment("a", "a", "a", "self"),
                comment("b", "a", "c", "cross"), comment("c", "b", "", "other"),
                comment("d", "a", "e", "cycle1"), comment("e", "a", "d", "cycle2")));
        Assert.assertEquals(shown.size(), 5);
        for (final JSONObject comment : shown) {
            Assert.assertTrue(comment.getInt("commentDepth") <= 1);
            if (comment.getInt("commentDepth") == 0) {
                Assert.assertFalse(comment.getBoolean(Common.IS_REPLY));
                Assert.assertEquals(comment.getString(Comment.COMMENT_ORIGINAL_COMMENT_NAME), "");
            }
        }
    }

    @Test public void nicknameMarkupAndEntityEncodedMarkupCannotBecomeNewIdentities() {
        for (final String value : new String[]{PROBE, "&lt;svg/onload=alert(1)&gt;", "&amp;lt;img src=x onerror=alert(1)&amp;gt;", "reader<img src=x>"}) {
            Assert.assertTrue(CommentNames.hasMarkup(value), value);
            Assert.assertFalse(CommentNames.display(value).contains("<"));
        }
        Assert.assertFalse(CommentNames.hasMarkup("张三 & Alice"));
        Assert.assertEquals(CommentNames.display("<b>历史昵称</b>"), "历史昵称");
        Assert.assertEquals(CommentNames.display("😀".repeat(30)).codePointCount(0, CommentNames.display("😀".repeat(30)).length()), 20);
    }

    @Test public void parentExistenceAndArticleBoundaryAreEnforcedBeforeWriting() throws Exception {
        LatkeTestSupport.configure();
        final var local = new java.util.Properties();
        local.setProperty("runtimeDatabase", "NONE");
        org.b3log.latke.Latkes.setLocalProps(local);
        final CommentMgmtService service = new CommentMgmtService();
        inject(service, "optionQueryService", new OptionQueryService() {
            @Override public JSONObject getPreference() { return new JSONObject().put(Option.ID_C_COMMENTABLE, true); }
        });
        inject(service, "articleRepository", new ArticleRepository() {
            @Override public JSONObject get(final String id) { return new JSONObject().put(Article.ARTICLE_COMMENTABLE, true).put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED); }
        });
        inject(service, "commentRepository", new CommentRepository() {
            @Override public JSONObject get(final String id) { return id.equals("missing") ? null : comment(id, id.equals("other") ? "b" : "a", "", "parent"); }
        });
        inject(service, "langPropsService", new LangPropsService() {
            @Override public String get(final String key) { return key; }
        });
        final JSONObject request = new JSONObject().put("oId", "a").put(Common.TYPE, Article.ARTICLE)
                .put(Comment.COMMENT_NAME, "reader").put(Comment.COMMENT_CONTENT, "Safe comment.").put(Comment.COMMENT_URL, "https://example.test/");
        for (final String target : new String[]{"missing", "other", "x".repeat(65), PROBE}) {
            Assert.assertFalse(service.checkAddCommentRequest(new JSONObject(request.toString()).put(Comment.COMMENT_ORIGINAL_COMMENT_ID, target)).getBoolean("sc"));
        }
        Assert.assertTrue(service.checkAddCommentRequest(new JSONObject(request.toString()).put(Comment.COMMENT_ORIGINAL_COMMENT_ID, "real")).getBoolean("sc"));
        Assert.assertFalse(service.checkAddCommentRequest(new JSONObject(request.toString()).put(Comment.COMMENT_NAME, PROBE)).getBoolean("sc"));
    }

    @Test public void commentHtmlCleaningStripsExecutableMarkupAndUnsafeProtocols() {
        final String clean = Markdowns.clean("<svg onload='alert(1)'></svg><img src=x onerror='alert(1)'><a href='javascript:alert(1)'>link</a>");
        Assert.assertFalse(clean.contains("onerror")); Assert.assertFalse(clean.contains("onload"));
        Assert.assertFalse(clean.contains("javascript:")); Assert.assertFalse(clean.contains("<svg"));
        Assert.assertEquals(CommentNames.homepage("javascript:alert(1)"), "");
        Assert.assertEquals(CommentNames.homepage("data:text/html,test"), "");
        Assert.assertEquals(CommentNames.homepage("https://example.test/?q=hello&lang=zh"), "https://example.test/?q=hello&lang=zh");
    }

    private static JSONObject comment(final String id, final String article, final String parent, final String name) {
        return new JSONObject().put("oId", id).put(Comment.COMMENT_ON_ID, article).put(Comment.COMMENT_NAME, name).put(Comment.COMMENT_ORIGINAL_COMMENT_ID, parent);
    }
    private static void inject(final Object target, final String name, final Object value) throws Exception {
        final Field field = CommentMgmtService.class.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
}
