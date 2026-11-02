/* SPDX-License-Identifier: AGPL-3.0-or-later */
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
import org.b3log.latke.http.Session;
import org.b3log.solo.model.Article;
import org.b3log.solo.processor.ArticleProcessor;
import org.b3log.solo.service.ArticleQueryService;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class ArticlePasswordProtectionTest {
    private static final String SECRET = "private-test-password";
    private static final class Processor extends ArticleProcessor {
        private String message;
        @Override protected void renderArticlePwdForm(final RequestContext context, final JSONObject article, final String text) {
            message = text;
        }
    }
    private record Result(int status, String location, String retryAfter, String message) { }

    @BeforeClass public void configure() throws Exception { LatkeTestSupport.configure(); }

    private Processor processor() throws Exception {
        final Processor processor = new Processor();
        final var query = ArticleProcessor.class.getDeclaredField("articleQueryService");
        query.setAccessible(true);
        query.set(processor, new ArticleQueryService() {
            @Override public JSONObject getArticleById(final String id) {
                if ("missing".equals(id)) return null;
                return new JSONObject().put(Keys.OBJECT_ID, id).put(Article.ARTICLE_TITLE, "Protected article")
                        .put(Article.ARTICLE_STATUS, "draft".equals(id) ? Article.ARTICLE_STATUS_C_DRAFT : Article.ARTICLE_STATUS_C_PUBLISHED)
                        .put(Article.ARTICLE_VIEW_PWD, SECRET).put(Article.ARTICLE_PERMALINK, "/protected.html");
            }
        });
        return processor;
    }

    private Result request(final Processor processor, final String address, final String id, final String password,
                           final boolean post, final boolean crossSite) {
        final var http = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, post ? HttpMethod.POST : HttpMethod.GET, "/console/article-pwd");
        final var raw = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        try {
            if (crossSite) http.headers().set("Sec-Fetch-Site", "cross-site");
            final Request request = new Request(null, http) {
                @Override public String getRemoteAddr() { return address; }
                @Override public Session getSession() { return null; }
            };
            if (id != null) request.setParameter("articleId", id);
            if (password != null) request.setParameter("pwdTyped", password);
            final RequestContext context = new RequestContext(request, new Response(null, raw));
            processor.message = null;
            if (post) processor.onArticlePwdForm(context); else processor.showArticlePwdForm(context);
            Assert.assertEquals(raw.headers().get("Cache-Control"), "no-store");
            return new Result(raw.status().code(), raw.headers().get("Location"), raw.headers().get("Retry-After"), processor.message);
        } finally { http.release(); raw.release(); }
    }

    @Test public void repeatedFailuresAlsoBlockACorrectGuessWithRetryAfter() throws Exception {
        final Processor processor = processor();
        for (int i = 0; i < 5; i++) Assert.assertEquals(request(processor, "198.51.100.1", "1", "wrong", true, false).status(), 302);
        final Result blocked = request(processor, "198.51.100.1", "1", SECRET, true, false);
        Assert.assertEquals(blocked.status(), 429);
        Assert.assertTrue(Integer.parseInt(blocked.retryAfter()) > 0);
        Assert.assertNull(blocked.location());
        Assert.assertTrue(blocked.message().contains("秒后重试"));
        Assert.assertFalse(blocked.message().contains(SECRET));
        Assert.assertEquals(request(processor, "198.51.100.2", "1", SECRET, true, false).location(), Latkes.getServePath() + "/protected.html");
        Assert.assertEquals(request(processor, "198.51.100.1", "2", SECRET, true, false).location(), Latkes.getServePath() + "/protected.html");
    }

    @Test public void rotatingArticlesCannotBypassTheSourceBudget() throws Exception {
        final Processor processor = processor();
        for (int i = 0; i < 20; i++) Assert.assertEquals(request(processor, "198.51.100.3", Integer.toString(i), "wrong", true, false).status(), 302);
        Assert.assertEquals(request(processor, "198.51.100.3", "another", SECRET, true, false).status(), 429);
    }

    @Test public void ipv6PrivacyAddressesShareTheSameBudget() throws Exception {
        final Processor processor = processor();
        for (int i = 1; i <= 5; i++) request(processor, "2001:db8::" + i, "1", "wrong", true, false);
        Assert.assertEquals(request(processor, "2001:db8::ffff", "1", SECRET, true, false).status(), 429);
        Assert.assertEquals(request(processor, "2001:db8:1::1", "1", SECRET, true, false).status(), 302);
    }

    @Test public void readingTheFormDoesNotConsumeAttemptsAndSuccessResetsTheArticleWindow() throws Exception {
        final Processor processor = processor();
        for (int i = 0; i < 10; i++) Assert.assertEquals(request(processor, "198.51.100.4", "1", null, false, false).status(), 200);
        for (int i = 0; i < 4; i++) request(processor, "198.51.100.4", "1", "wrong", true, false);
        Assert.assertEquals(request(processor, "198.51.100.4", "1", SECRET, true, false).location(), Latkes.getServePath() + "/protected.html");
        Assert.assertEquals(request(processor, "198.51.100.4", "1", "wrong", true, false).status(), 302);
    }

    @Test public void invalidMissingAndDraftRequestsDoNotExposeAPasswordForm() throws Exception {
        final Processor processor = processor();
        Assert.assertEquals(request(processor, "198.51.100.5", null, "wrong", true, false).status(), 400);
        Assert.assertEquals(request(processor, "198.51.100.5", "1", "x".repeat(1025), true, false).status(), 400);
        for (final String id : new String[]{"missing", "draft"}) {
            Assert.assertEquals(request(processor, "198.51.100.5", id, "wrong", true, false).status(), 404);
            final Result form = request(processor, "198.51.100.5", id, null, false, false);
            Assert.assertEquals(form.status(), 404); Assert.assertNull(form.message());
        }
    }

    @Test public void crossSiteFormsCannotSpendAReadersBudget() throws Exception {
        final Processor processor = processor();
        for (int i = 0; i < 10; i++) Assert.assertEquals(request(processor, "198.51.100.6", "1", "wrong", true, true).status(), 403);
        Assert.assertEquals(request(processor, "198.51.100.6", "1", SECRET, true, false).location(), Latkes.getServePath() + "/protected.html");
    }
}
