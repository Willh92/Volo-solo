/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.util;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.Test;

public class GitHubContentsTest {
    @Test public void anExistingRepositoryDoesNotRequireMetadataWritePermission() throws Exception {
        final List<String> requests = new ArrayList<>();
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            final byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(exchange.getRequestMethod().equals("GET") ? 200 : 403, body.length);
            try (var stream = exchange.getResponseBody()) { stream.write(body); }
        });
        server.start();
        try {
            Assert.assertTrue(GitHubs.ensureRepositoryAt("http://127.0.0.1:" + server.getAddress().getPort(), "synthetic", "owner", "owner", "profile", "https://example.test"));
            Assert.assertEquals(requests, List.of("GET /repos/owner/owner"));
        } finally { server.stop(0); }
    }
    @Test public void contentsApiUsesTheDefaultBranchAndTheCurrentFileSha() throws Exception {
        for (final int existing : new int[]{200, 404, 403}) {
            final List<String> requests = new ArrayList<>(); final List<JSONObject> writes = new ArrayList<>();
            final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                final boolean get = exchange.getRequestMethod().equals("GET");
                final String body = get ? new JSONObject().put("sha", "current-sha").toString() : "{}";
                if (!get) writes.add(new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(get ? existing : 201, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            server.start();
            try {
                final boolean ok = GitHubs.updateFileAt("http://127.0.0.1:" + server.getAddress().getPort(), "synthetic-token", "owner", "repo", "README.md", "new text".getBytes(StandardCharsets.UTF_8));
                Assert.assertEquals(ok, existing != 403);
                Assert.assertEquals(requests.get(0), "GET /repos/owner/repo/contents/README.md");
                Assert.assertFalse(requests.toString().contains("master"));
                Assert.assertEquals(writes.size(), existing == 403 ? 0 : 1);
                if (!writes.isEmpty()) {
                    Assert.assertEquals(writes.get(0).optString("sha"), existing == 200 ? "current-sha" : "");
                    Assert.assertFalse(writes.get(0).has("branch"));
                    Assert.assertEquals(writes.get(0).getString("content"), Base64.getEncoder().encodeToString("new text".getBytes(StandardCharsets.UTF_8)));
                }
            } finally { server.stop(0); }
        }
    }
}
