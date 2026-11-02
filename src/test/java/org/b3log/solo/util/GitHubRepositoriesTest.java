/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.util;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.Test;

public class GitHubRepositoriesTest {
    private static JSONObject repository(String name, boolean fork, int stars) {
        return new JSONObject().put("id", stars + 1).put("name", name).put("full_name", "Owner/" + name)
                .put("html_url", "https://github.com/Owner/" + name).put("fork", fork)
                .put("owner", new JSONObject().put("login", "oWnEr").put("id", 1))
                .put("stargazers_count", stars).put("updated_at", "2026-11-03T00:00:00Z");
    }

    @Test public void allPublicOwnedRepositoriesArePagedIncludingForksAndProfileRepositories() throws Exception {
        final JSONArray first = new JSONArray();
        for (int i = 0; i < 100; i++) first.put(repository(i == 0 ? "Owner" : "repo-" + i, i % 2 == 1, i));
        final JSONArray second = new JSONArray().put(repository("fork-last", true, 150))
                .put(repository("private", false, 999).put("private", true))
                .put(repository("disabled", false, 999).put("disabled", true))
                .put(repository("foreign", false, 999).put("owner", new JSONObject().put("login", "other")));
        final AtomicInteger requests = new AtomicInteger();
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            Assert.assertTrue(exchange.getRequestURI().getQuery().contains("type=owner"));
            final byte[] data = (exchange.getRequestURI().getQuery().endsWith("page=1") ? first : second).toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, data.length);
            try (var output = exchange.getResponseBody()) { output.write(data); }
        });
        server.start();
        try {
            final JSONArray result = GitHubs.getGitHubReposAt("http://127.0.0.1:" + server.getAddress().getPort(), "Owner");
            Assert.assertEquals(result.length(), 101); Assert.assertEquals(requests.get(), 2);
            Assert.assertEquals(result.getJSONObject(0).getString("githubrepoName"), "fork-last");
            Assert.assertTrue(result.getJSONObject(0).getBoolean("githubrepoFork"));
            Assert.assertTrue(result.toString().contains("https://github.com/Owner/Owner"));
            Assert.assertFalse(result.toString().contains("/private")); Assert.assertFalse(result.toString().contains("/foreign"));
        } finally { server.stop(0); }
    }

    @Test public void aLaterPageFailureNeverPublishesAPartialSnapshot() throws Exception {
        final JSONArray first = new JSONArray(); for (int i = 0; i < 100; i++) first.put(repository("repo-" + i, false, i));
        final AtomicInteger requests = new AtomicInteger();
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            final int request = requests.incrementAndGet(); final byte[] data = (request == 1 ? first.toString() : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(request == 1 ? 200 : 403, data.length);
            try (var output = exchange.getResponseBody()) { output.write(data); }
        });
        server.start();
        try {
            final String base = "http://127.0.0.1:" + server.getAddress().getPort();
            Assert.assertNull(GitHubs.getGitHubReposAt(base, "Owner"));
            Assert.assertNull(GitHubs.getGitHubReposAt(base, "../private"));
            Assert.assertEquals(requests.get(), 2);
        } finally { server.stop(0); }
    }
}
