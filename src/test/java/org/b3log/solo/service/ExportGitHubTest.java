/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.service;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import org.b3log.solo.model.Article;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ExportGitHubTest {
    @Test public void aFailedUploadRemainsRetryableAndOnlySuccessIsCached() throws Exception {
        final var directory = Files.createTempDirectory("volo-github-cache-");
        final int[] calls = {0}; final boolean[] success = {false};
        final ExportService service = new ExportService() {
            @Override protected File githubCacheFile(final String login, final String repository, final String name) { return directory.resolve(login + "-" + repository + "-" + name).toFile(); }
            @Override protected boolean uploadGitHubReadme(final String token, final String login, final String repo, final String path, final byte[] content) { calls[0]++; return success[0]; }
        };
        try {
            Assert.assertFalse(service.diffUploadReadme("test", "profile", "owner", "new content", "readme.md"));
            try (var files = Files.list(directory)) { Assert.assertEquals(files.count(), 0L); }
            success[0] = true;
            Assert.assertTrue(service.diffUploadReadme("test", "profile", "owner", "new content", "readme.md"));
            Assert.assertTrue(service.diffUploadReadme("test", "profile", "owner", "new content", "readme.md"));
            Assert.assertEquals(calls[0], 2);
            Assert.assertTrue(service.diffUploadReadme("test", "profile", "different", "new content", "readme.md"));
            Assert.assertEquals(calls[0], 3, "Accounts must not share a global temporary success cache");
        } finally {
            try (var paths = Files.walk(directory)) { for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path); }
        }
    }
    @Test public void profileReadmeContainsExactlyTheFiveNewestPosts() throws Exception {
        final ExportService service = new ExportService() { @Override protected String gitHubBlogOrigin() { return "https://example.test"; } };
        final var posts = new ArrayList<JSONObject>();
        for (int i = 0; i < 6; i++) posts.add(new JSONObject().put(Article.ARTICLE_TITLE, "Post " + i).put(Article.ARTICLE_PERMALINK, "/post-" + i));
        final var render = ExportService.class.getDeclaredMethod("genMeRepoReadme", java.util.List.class, String.class); render.setAccessible(true);
        final String readme = (String) render.invoke(service, posts, "owner");
        for (int i = 0; i < 5; i++) Assert.assertTrue(readme.contains("https://example.test/post-" + i));
        Assert.assertFalse(readme.contains("https://example.test/post-5"));
        Assert.assertEquals(posts.size(), 6, "Rendering must not mutate the query result");
    }
}
