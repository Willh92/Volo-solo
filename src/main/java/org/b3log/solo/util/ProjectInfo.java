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
package org.b3log.solo.util;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.b3log.solo.SoloApplication;
import org.json.JSONObject;

/** Public GitHub metadata, fetched asynchronously and cached without blocking HTTP event loops. */
public final class ProjectInfo {
    public static final String REPOSITORY = "https://github.com/Willh92/Volo-solo";
    public static final String UPSTREAM = "https://github.com/bolo-blog/bolo-solo";
    private static final String API = "https://api.github.com/repos/Willh92/Volo-solo";
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final AtomicBoolean LOADING = new AtomicBoolean();
    private static volatile String cached;
    private static volatile long refreshAt = System.nanoTime();
    private static volatile boolean stale;
    private ProjectInfo() { }

    public static JSONObject snapshot() {
        if (System.nanoTime() >= refreshAt && LOADING.compareAndSet(false, true)) {
            refreshAt = System.nanoTime() + Duration.ofMinutes(10).toNanos();
            fetch("").thenCombine(fetch("/releases/latest"), ProjectInfo::parse).whenComplete((result, failure) -> {
                if (failure == null) { cached = result.put("syncedAt", Instant.now().toString()).toString(); stale = false; }
                else { stale = true; refreshAt = System.nanoTime() + Duration.ofMinutes(1).toNanos(); }
                LOADING.set(false);
            });
        }
        final String value = cached;
        final JSONObject result = value == null ? new JSONObject() : new JSONObject(value);
        return result.put("product", "Volo").put("version", SoloApplication.BOLO_VERSION_SOURCE)
                .put("repository", REPOSITORY).put("upstream", UPSTREAM).put("forkedFrom", "bolo-blog/bolo-solo")
                .put("loading", LOADING.get()).put("available", value != null).put("stale", stale);
    }

    private static CompletableFuture<JSONObject> fetch(final String path) {
        final HttpRequest request = HttpRequest.newBuilder(URI.create(API + path)).timeout(Duration.ofSeconds(4))
                .header("User-Agent", "Volo/" + SoloApplication.BOLO_VERSION_SOURCE)
                .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2026-03-10").GET().build();
        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(response -> {
            if (path.equals("/releases/latest") && response.statusCode() == 404) return null;
            if (response.statusCode() != 200 || response.body().length() > 131072) throw new IllegalStateException("GitHub metadata unavailable");
            return new JSONObject(response.body());
        });
    }

    public static JSONObject parse(final JSONObject repository, final JSONObject release) {
        if (repository == null || !"Willh92/Volo-solo".equalsIgnoreCase(repository.optString("full_name"))) {
            throw new IllegalArgumentException("Unexpected repository");
        }
        final JSONObject result = new JSONObject().put("name", "Willh92/Volo-solo")
                .put("description", text(repository, "description", 600)).put("stars", Math.max(0, repository.optLong("stargazers_count")))
                .put("forks", Math.max(0, repository.optLong("forks_count"))).put("issues", Math.max(0, repository.optLong("open_issues_count")))
                .put("language", text(repository, "language", 50)).put("branch", text(repository, "default_branch", 128))
                .put("pushedAt", text(repository, "pushed_at", 40));
        final JSONObject license = repository.optJSONObject("license");
        result.put("license", license == null ? "请查看仓库 LICENSE" : text(license, "spdx_id", 80));
        if (release != null && !release.optBoolean("draft") && !release.optBoolean("prerelease")) {
            final String tag = text(release, "tag_name", 128);
            result.put("release", new JSONObject().put("tag", tag).put("name", text(release, "name", 200))
                    .put("publishedAt", text(release, "published_at", 40))
                    .put("url", REPOSITORY + "/releases/tag/" + URLEncoder.encode(tag, StandardCharsets.UTF_8).replace("+", "%20")));
        }
        return result;
    }

    private static String text(final JSONObject source, final String key, final int maximum) {
        final String value = source.optString(key);
        return value.substring(0, Math.min(maximum, value.length()));
    }
}
