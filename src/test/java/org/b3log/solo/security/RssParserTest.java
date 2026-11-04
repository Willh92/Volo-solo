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

import com.sun.net.httpserver.HttpServer;
import org.b3log.solo.model.Article;
import org.b3log.solo.rss.RssParser;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class RssParserTest {
    @BeforeClass public void configure() throws Exception { LatkeTestSupport.configure(); }

    @Test public void feedBytesSanitizeMarkupProtocolsDoctypesAndPrivateAddresses() throws Exception {
        Assert.expectThrows(IllegalStateException.class,
                () -> new RssParser("http://127.0.0.1:8080/rss", "", "Feed author").parse2Article());
        Assert.expectThrows(IllegalStateException.class,
                () -> new RssParser("file:///etc/passwd", "", "Feed author").parse2Article());
        final StringBuilder items = new StringBuilder();
        items.append("<item><title>Without description</title><link>https://example.test/one</link>")
                .append("<enclosure url='https://example.test/file' length='1'/></item>")
                .append("<item><title>Second post</title><description>Readable summary</description></item>")
                .append("<item><title>&lt;img src=x onerror=alert(1)&gt;Safe title</title>")
                .append("<link>javascript:alert(1)</link>")
                .append("<description>&lt;script&gt;alert(1)&lt;/script&gt;&lt;p&gt;Clean body&lt;/p&gt;</description></item>");
        for (int i = 0; i < 60; i++) {
            items.append("<item><title>Extra ").append(i).append("</title><description>Body</description></item>");
        }
        final byte[] xml = ("<?xml version='1.0'?><rss version='2.0'><channel><title>&lt;b&gt;Feed&lt;/b&gt;</title>"
                + "<link>https://example.test/</link><description>Feed description</description>"
                + items + "</channel></rss>").getBytes(StandardCharsets.UTF_8);
        final var articles = new RssParser("https://example.test/rss", "javascript:alert(1)", "<b>Feed author</b>")
                .parseFeedBytes(xml);
        Assert.assertEquals(articles.size(), 50);
        Assert.assertEquals(articles.get(0).getString(Article.ARTICLE_CONTENT), "");
        Assert.assertEquals(articles.get(0).getString("authorThumbnailURL"), "");
        Assert.assertEquals(articles.get(0).getString("authorName"), "Feed author");
        Assert.assertEquals(articles.get(1).getString(Article.ARTICLE_CONTENT), "Readable summary");
        Assert.assertEquals(articles.get(2).getString(Article.ARTICLE_TITLE), "Safe title");
        Assert.assertFalse(articles.get(2).getString(Article.ARTICLE_CONTENT).contains("<script"));
        Assert.assertTrue(articles.get(2).getString(Article.ARTICLE_CONTENT).contains("Clean body"));
        Assert.assertFalse(articles.get(2).getString(Article.ARTICLE_PERMALINK).startsWith("javascript:"));

        final byte[] xxeXml = ("<?xml version='1.0'?><!DOCTYPE rss [<!ENTITY xxe 'boom'>]>"
                + "<rss version='2.0'><channel><title>Feed</title></channel></rss>").getBytes(StandardCharsets.UTF_8);
        Assert.expectThrows(Exception.class,
                () -> new RssParser("https://example.test/rss", "", "Feed").parseFeedBytes(xxeXml));
    }

    @Test public void optionalFeedFieldsDoNotDiscardTheFeed() throws Exception {
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rss", exchange -> {
            final byte[] xml = ("<?xml version='1.0'?><rss version='2.0'><channel><title>Feed</title>"
                    + "<link>https://example.test/</link><description>Feed description</description>"
                    + "<item><title>Without description</title><link>https://example.test/one</link>"
                    + "<enclosure url='https://example.test/file' length='1'/></item>"
                    + "<item><title>Second post</title><description>Readable summary</description></item>"
                    + "<item><title>&lt;img src=x onerror=alert(1)&gt;Safe title</title>"
                    + "<link>javascript:alert(1)</link>"
                    + "<description>&lt;script&gt;alert(1)&lt;/script&gt;&lt;p&gt;Clean body&lt;/p&gt;</description></item>"
                    + "</channel></rss>").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/rss+xml; charset=UTF-8");
            exchange.sendResponseHeaders(200, xml.length);
            try (var body = exchange.getResponseBody()) { body.write(xml); }
        });
        server.createContext("/not-a-feed", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            final String base = "http://127.0.0.1:" + server.getAddress().getPort();
            Assert.expectThrows(IllegalStateException.class,
                    () -> new RssParser(base + "/rss", "", "Feed author").parse2Article());
            final var articles = new RssParser(base + "/rss", "javascript:alert(1)", "Feed author", true).parse2Article();
            Assert.assertEquals(articles.size(), 3);
            Assert.assertEquals(articles.get(0).getString(Article.ARTICLE_CONTENT), "");
            Assert.assertEquals(articles.get(1).getString(Article.ARTICLE_CONTENT), "Readable summary");
            Assert.assertEquals(articles.get(2).getString(Article.ARTICLE_TITLE), "Safe title");
            Assert.assertFalse(articles.get(2).getString(Article.ARTICLE_CONTENT).contains("<script"));
            Assert.assertTrue(articles.get(2).getString(Article.ARTICLE_CONTENT).contains("Clean body"));
            Assert.assertFalse(articles.get(2).getString(Article.ARTICLE_PERMALINK).startsWith("javascript:"));
            Assert.expectThrows(IllegalStateException.class,
                    () -> new RssParser(base + "/not-a-feed", "", "Feed", true).parse2Article());
        } finally { server.stop(0); }
    }

    @Test(timeOut = 12000) public void unresponsiveFeedHasABoundedWait() throws Exception {
        final var worker = Executors.newSingleThreadExecutor();
        final var release = new CountDownLatch(1);
        try (final var socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            worker.submit(() -> {
                try (var connection = socket.accept()) { release.await(10, TimeUnit.SECONDS); }
                catch (Exception ignored) { }
            });
            final long start = System.nanoTime();
            Assert.expectThrows(IllegalStateException.class, () -> new RssParser(
                    "http://127.0.0.1:" + socket.getLocalPort() + "/stalled.xml", "", "Feed", true).parse2Article());
            Assert.assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 9000);
        } finally {
            release.countDown();
            worker.shutdownNow();
            Assert.assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}
