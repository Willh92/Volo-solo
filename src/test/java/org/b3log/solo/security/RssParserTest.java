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
/* SPDX-License-Identifier: AGPL-3.0-or-later */
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

    @Test public void optionalFeedFieldsDoNotDiscardTheFeed() throws Exception {
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rss", exchange -> {
            final byte[] xml = ("<?xml version='1.0'?><rss version='2.0'><channel><title>Feed</title>"
                    + "<link>https://example.test/</link><description>Feed description</description>"
                    + "<item><title>Without description</title><link>https://example.test/one</link>"
                    + "<enclosure url='https://example.test/file' length='1'/></item>"
                    + "<item><title>Second post</title><description>Readable summary</description></item>"
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
            final var articles = new RssParser(base + "/rss", "", "Feed author").parse2Article();
            Assert.assertEquals(articles.size(), 2);
            Assert.assertEquals(articles.get(0).getString(Article.ARTICLE_CONTENT), "");
            Assert.assertEquals(articles.get(1).getString(Article.ARTICLE_CONTENT), "Readable summary");
            Assert.expectThrows(IllegalStateException.class, () -> new RssParser(base + "/not-a-feed", "", "Feed").parse2Article());
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
                    "http://localhost:" + socket.getLocalPort() + "/stalled.xml", "", "Feed").parse2Article());
            Assert.assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 9000);
        } finally {
            release.countDown();
            worker.shutdownNow();
            Assert.assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}
