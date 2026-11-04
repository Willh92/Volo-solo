/*
 * Bolo - A stable and beautiful blogging system based in Solo.
 * Copyright (c) 2020-present, https://github.com/bolo-blog
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.b3log.solo.security;

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import java.io.IOException;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import org.b3log.latke.Keys;
import org.b3log.latke.http.Request;
import org.b3log.latke.http.Session;
import org.b3log.latke.http.Sessions;
import org.b3log.latke.model.User;
import org.b3log.solo.SoloApplication;
import org.b3log.solo.bolo.pic.PicUploadProcessor;
import org.b3log.solo.cache.ArticleCache;
import org.b3log.solo.cache.CommentCache;
import org.b3log.solo.cache.FollowArticleCache;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Common;
import org.b3log.solo.model.UserExt;
import org.b3log.solo.util.Emotions;
import org.b3log.solo.util.OutboundUrls;
import org.b3log.solo.util.Solos;
import org.b3log.solo.web.PublicResourceHandler;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import pers.adlered.simplecurrentlimiter.control.MainControl;

public class ConsoleAndApiSecurityTest {

    @BeforeClass
    public void configure() throws Exception {
        LatkeTestSupport.configure();
    }

    @Test
    public void outboundUrlsRejectSsrfTargetsAndNonHttpSchemes() throws Exception {
        for (final String blocked : new String[]{
                "http://127.0.0.1/admin",
                "http://10.0.0.1/internal",
                "http://172.16.5.4/internal",
                "http://192.168.1.1/router",
                "http://169.254.169.254/latest/meta-data/",
                "http://100.100.100.200/latest/meta-data/",
                "http://[::1]:8080/secret",
                "http://[::ffff:127.0.0.1]/secret",
                "http://[fc00::1]/ula",
                "http://[2001:db8::1]/doc",
                "http://[64:ff9b::127.0.0.1]/nat64",
                "http://[2002:7f00:0001::1]/6to4",
                "http://[2001:0000:4136:e378:8000:63bf:f8ff:fefe]/teredo",
                "http://192.0.2.1/test-net",
                "file:///etc/passwd",
                "javascript:alert(1)",
                "https://user:pass@93.184.216.34/feed.xml"
        }) {
            Assert.expectThrows(IOException.class, () -> OutboundUrls.requirePublicHttpUri(blocked, false));
        }

        Assert.assertNotNull(OutboundUrls.requirePublicHttpUri("https://93.184.216.34/feed.xml", false));
        Assert.assertTrue(OutboundUrls.isAllowedRemoteAddress(InetAddress.getByName("127.0.0.1"), true));
        Assert.assertFalse(OutboundUrls.isAllowedRemoteAddress(InetAddress.getByName("127.0.0.1"), false));
        Assert.assertTrue(OutboundUrls.isOptionalSafeIconOrAvatar(""));
        Assert.assertTrue(OutboundUrls.isOptionalSafeIconOrAvatar("/images/avatar.png"));
        Assert.assertFalse(OutboundUrls.isOptionalSafeIconOrAvatar("//evil.example/avatar.png"));
        Assert.assertFalse(OutboundUrls.isOptionalSafeIconOrAvatar("/images/../WEB-INF/web.xml"));
        Assert.assertFalse(OutboundUrls.isOptionalSafeIconOrAvatar("javascript:alert(1)"));
    }

    @Test
    public void sanitizeUserStripsSecretsWithoutMutatingOriginal() {
        final JSONObject source = new JSONObject()
                .put(Keys.OBJECT_ID, "u-1")
                .put(User.USER_NAME, "alice")
                .put(User.USER_PASSWORD, "hashed-password")
                .put(UserExt.USER_B3_KEY, "secret-b3-key");

        final JSONObject sanitized = Solos.sanitizeUser(source);
        Assert.assertNotNull(sanitized);
        Assert.assertEquals(sanitized.optString(User.USER_NAME), "alice");
        Assert.assertFalse(sanitized.has(User.USER_PASSWORD));
        Assert.assertFalse(sanitized.has(UserExt.USER_B3_KEY));
        Assert.assertEquals(source.optString(User.USER_PASSWORD), "hashed-password");
        Assert.assertEquals(source.optString(UserExt.USER_B3_KEY), "secret-b3-key");
        Assert.assertNull(Solos.sanitizeUser(null));
    }

    @Test
    public void localImageContentTypeBlocksTraversalAndActiveDocuments() {
        Assert.assertEquals(PicUploadProcessor.safeImageContentType("20261104_cover.webp"), "image/webp");
        Assert.assertEquals(PicUploadProcessor.safeImageContentType("avatar.JPG"), "image/jpeg");
        Assert.assertEquals(PicUploadProcessor.safeImageContentType("icon.png"), "image/png");
        for (final String unsafe : new String[]{
                null,
                "",
                "../secret.png",
                "sub/photo.png",
                "sub\\photo.png",
                ".hidden.png",
                "vector.svg",
                "script.html",
                "archive.zip"
        }) {
            Assert.assertNull(PicUploadProcessor.safeImageContentType(unsafe), "Expected rejection for: " + unsafe);
        }
    }

    @Test
    public void entityCachesEnforceBoundsDeduplicatePermalinksAndIsolateClones() {
        final ArticleCache articleCache = new ArticleCache();
        for (int i = 0; i < ArticleCache.MAX_ENTRIES + 40; i++) {
            articleCache.putArticle(new JSONObject()
                    .put(Keys.OBJECT_ID, "id-" + i)
                    .put(Article.ARTICLE_PERMALINK, "/p-" + i + ".html")
                    .put(Article.ARTICLE_TITLE, "Title " + i));
        }
        Assert.assertEquals(articleCache.size(), ArticleCache.MAX_ENTRIES);
        Assert.assertNull(articleCache.getArticle("id-0"));
        Assert.assertNull(articleCache.getArticleByPermalink("/p-0.html"));
        Assert.assertNotNull(articleCache.getArticleByPermalink("/p-" + (ArticleCache.MAX_ENTRIES + 39) + ".html"));

        // Updating an article's permalink cleans up the stale permalink mapping
        final String targetId = "id-" + (ArticleCache.MAX_ENTRIES + 39);
        articleCache.putArticle(new JSONObject()
                .put(Keys.OBJECT_ID, targetId)
                .put(Article.ARTICLE_PERMALINK, "/renamed.html")
                .put(Article.ARTICLE_TITLE, "Renamed"));
        Assert.assertNull(articleCache.getArticleByPermalink("/p-" + (ArticleCache.MAX_ENTRIES + 39) + ".html"));
        Assert.assertEquals(articleCache.getArticleByPermalink("/renamed.html").getString(Article.ARTICLE_TITLE), "Renamed");

        final CommentCache commentCache = new CommentCache();
        for (int i = 0; i < CommentCache.MAX_ENTRIES + 50; i++) {
            commentCache.putComment(new JSONObject().put(Keys.OBJECT_ID, "c-" + i));
        }
        Assert.assertEquals(commentCache.size(), CommentCache.MAX_ENTRIES);
        Assert.assertNull(commentCache.getComment("c-0"));

        final FollowArticleCache followCache = new FollowArticleCache();
        final Map<String, JSONObject> feedItems = new LinkedHashMap<>();
        feedItems.put("Post", new JSONObject().put(Article.ARTICLE_TITLE, "Original"));
        followCache.putArticles("bob", feedItems);
        followCache.getFollowArticles("bob").get("Post").put(Article.ARTICLE_TITLE, "Mutated");
        Assert.assertEquals(followCache.getFollowArticles("bob").get("Post").getString(Article.ARTICLE_TITLE), "Original");
    }

    @Test
    public void transientAnonymousSessionsAreEvictedWhileUnlockedArticleSessionsPersist() {
        final DefaultFullHttpRequest anonHttp = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        final DefaultFullHttpRequest pwdHttp = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/console/article-pwd");
        try {
            final Session transientSession = Sessions.add();
            final Request anonReq = new Request(null, anonHttp) {
                @Override public Session getSession() { return transientSession; }
            };
            anonReq.setAttribute("bolo.hadIncomingSession", false);
            Assert.assertTrue(Sessions.contains(transientSession.getId()));
            SoloApplication.evictTransientSession(anonReq);
            Assert.assertFalse(Sessions.contains(transientSession.getId()));

            final Session unlockedSession = Sessions.add();
            unlockedSession.setAttribute(Common.ARTICLES_VIEW_PWD, "{\"a1\":\"secret\"}");
            final Request pwdReq = new Request(null, pwdHttp) {
                @Override public Session getSession() { return unlockedSession; }
            };
            pwdReq.setAttribute("bolo.hadIncomingSession", false);
            SoloApplication.evictTransientSession(pwdReq);
            Assert.assertTrue(Sessions.contains(unlockedSession.getId()));
            Sessions.CACHE.remove(unlockedSession.getId());
        } finally {
            anonHttp.release();
            pwdHttp.release();
        }
    }

    @Test
    public void rateLimiterAndStaticFastPathAndEmotionsRemainAccurateAndBounded() {
        final MainControl limiter = new MainControl();
        limiter.setExpireTimeSecond(60);
        limiter.setFrequencyTime(2);
        Assert.assertTrue(limiter.write("198.51.100.10"));
        Assert.assertTrue(limiter.write("198.51.100.10"));
        Assert.assertFalse(limiter.write("198.51.100.10"));
        for (int i = 0; i < MainControl.MAX_CACHE_ENTRIES + 100; i++) {
            limiter.write("203.0.113." + i);
        }
        Assert.assertTrue(limiter.cachePairMap.size() <= MainControl.MAX_CACHE_ENTRIES);
        Assert.assertFalse(limiter.write("198.51.100.10"),
                "Spoofed-IP flood eviction must not reset counters of actively rate-limited clients");

        Assert.assertTrue(PublicResourceHandler.isPublicAssetUri("/scss/appearance.css"));
        Assert.assertTrue(PublicResourceHandler.isPublicAssetUri("/skins/volo-studio/css/base.css?v=1"));
        Assert.assertTrue(PublicResourceHandler.isPublicAssetUri("/favicon.ico"));
        Assert.assertFalse(PublicResourceHandler.isPublicAssetUri("/robots.txt"));
        Assert.assertFalse(PublicResourceHandler.isPublicAssetUri("/articles/2026/11/04/hello.html"));
        Assert.assertFalse(PublicResourceHandler.isPublicAssetUri("/admin-index.do"));

        final String converted = Emotions.convert("Hello :huaji: and :vditor: and :not_a_real_shortcode: at 12:30:huaji:!");
        Assert.assertTrue(converted.contains("huaji.gif"));
        Assert.assertTrue(converted.contains("vditor.png"));
        Assert.assertTrue(converted.contains(":not_a_real_shortcode:"));
        Assert.assertTrue(converted.contains("12:30<img"), "Time colons before an emoji shortcode must remain intact");
    }

    @Test
    public void markdownSanitizationArticleAbstractAndSafeCoverUrlsPreserveCodeWhileBlockingXss() {
        final String rendered = "<pre><code class=\"language-java\">List&lt;T&gt; items;</code></pre>"
                + "<p><a href=\"/articles/2026/11/05/demo.html\">Local</a> "
                + "<a href=\"javascript:alert(1)\" onclick=\"alert(2)\">Bad</a></p>"
                + "<ul><li class=\"task-list-item\"><input type=\"checkbox\" disabled checked/>Done</li></ul>"
                + "<img src=\"x\" onerror=\"alert(3)\"/><script>alert(4)</script>";
        final String cleaned = org.b3log.solo.util.Markdowns.clean(rendered);
        Assert.assertTrue(cleaned.contains("List&lt;T&gt; items;"), "Code generics must remain intact");
        Assert.assertTrue(cleaned.contains("href=\"/articles/2026/11/05/demo.html\""), "Root-relative links must be preserved");
        Assert.assertTrue(cleaned.contains("type=\"checkbox\""), "GFM task-list checkboxes must be preserved");
        Assert.assertFalse(cleaned.contains("<script"));
        Assert.assertFalse(cleaned.contains("onerror"));
        Assert.assertFalse(cleaned.contains("onclick"));
        Assert.assertFalse(cleaned.contains("javascript:"));

        final String abstractText = Article.getAbstractText("Hello **A & B** and <C>");
        Assert.assertEquals(abstractText, "Hello A & B and");
        Assert.assertEquals(Article.getAbstractText("Hello **A & B**"), "Hello A & B");

        Assert.assertEquals(Article.getArticleCoverURL("<p><img src=\"//evil.example/track.png\"/></p>"), "");
        Assert.assertEquals(Article.getArticleCoverURL("<p><img src=\"javascript:alert(1)\"/></p>"), "");
        Assert.assertTrue(Article.getArticleCoverURL("<p><img src=\"/image/demo.webp\"/></p>").startsWith("/image/demo.webp"));
    }

    @Test
    public void negativeCachesCloneIsolationAndXssFastPathRemainConsistent() {
        Assert.assertNull(Solos.clone(null));
        Assert.assertEquals(Solos.clone(new JSONObject()).length(), 0);

        final org.b3log.solo.cache.OptionCache optionCache = new org.b3log.solo.cache.OptionCache();
        Assert.assertFalse(optionCache.isMissing("nonexistentOption"));
        optionCache.markMissing("nonexistentOption");
        Assert.assertTrue(optionCache.isMissing("nonexistentOption"));
        optionCache.putOption(new JSONObject().put(Keys.OBJECT_ID, "nonexistentOption").put("optionCategory", "test").put("optionValue", "v"));
        Assert.assertFalse(optionCache.isMissing("nonexistentOption"));

        final org.b3log.solo.cache.UserCache userCache = new org.b3log.solo.cache.UserCache();
        userCache.putAdmin(new JSONObject().put(Keys.OBJECT_ID, "admin-1").put(User.USER_NAME, "admin").put(User.USER_ROLE, org.b3log.latke.model.Role.ADMIN_ROLE));
        userCache.getAdmin().put(User.USER_NAME, "tampered");
        Assert.assertEquals(userCache.getAdmin().getString(User.USER_NAME), "admin");
        userCache.removeUser("admin-1");
        Assert.assertNull(userCache.getAdmin());

        Assert.assertFalse(org.b3log.solo.util.XssPayloads.executable("/articles/2026/11/05/hello.html"));
        Assert.assertFalse(org.b3log.solo.util.XssPayloads.executable("/tags/%E6%8A%80%E6%9C%AF"));
        Assert.assertTrue(org.b3log.solo.util.XssPayloads.executable("/search/%3Cscript%3Ealert(1)%3C/script%3E"));
        Assert.assertTrue(org.b3log.solo.util.XssPayloads.executable("&#x3c;img src=x onerror=alert(1)&#x3e;"));
        Assert.assertTrue(org.b3log.solo.util.XssPayloads.executable("javascript:alert(1)"));
    }
}
