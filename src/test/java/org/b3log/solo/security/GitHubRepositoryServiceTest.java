/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.security;

import java.util.HashMap;
import java.util.Map;
import org.b3log.solo.model.Option;
import org.b3log.solo.service.GitHubRepositoryService;
import org.b3log.solo.service.OptionMgmtService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.util.GitHubStats;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.Test;

public class GitHubRepositoryServiceTest {
    private static JSONObject repo(String name, boolean fork, int stars) {
        return new JSONObject().put("githubrepoName", name).put("githubrepoFullName", "Owner/" + name)
                .put("githubrepoHTMLURL", "https://github.com/Owner/" + name).put("githubrepoFork", fork)
                .put("githubrepoStargazersCount", stars).put("githubrepoForksCount", 1);
    }
    private static GitHubRepositoryService service(final Map<String, JSONObject> rows) throws Exception {
        final GitHubRepositoryService service = new GitHubRepositoryService();
        final OptionQueryService query = new OptionQueryService() { @Override public JSONObject getOptionById(String id) { return rows.get(id); } };
        final OptionMgmtService management = new OptionMgmtService() {
            @Override public void storeDocument(String id, String category, String value) {
                rows.put(id, new JSONObject().put(Option.OPTION_VALUE, value));
            }
        };
        for (final Object[] injection : new Object[][]{{"optionQueryService", query}, {"optionMgmtService", management}}) {
            final var field = GitHubRepositoryService.class.getDeclaredField((String) injection[0]); field.setAccessible(true); field.set(service, injection[1]);
        }
        return service;
    }

    @Test public void displayedRepositoriesAndStatsUseTheSameSelectionAcrossRefreshes() throws Exception {
        final Map<String, JSONObject> rows = new HashMap<>(); final var service = service(rows);
        final JSONArray repos = new JSONArray().put(repo("original", false, 12)).put(repo("forked", true, 3));
        service.storeSnapshot("Owner", repos);
        Assert.assertEquals(service.snapshot().getJSONArray("repositories").length(), 2);
        Assert.assertEquals(service.visibleFor("owner").length(), 1);
        Assert.assertTrue(GitHubStats.svg("owner", service.visibleFor("owner")).contains("12 Stars"));
        service.saveDisplay(service.prepareDisplay("Owner", new JSONObject().put("username", "owner").put("includeForks", true)
                .put("hidden", new JSONArray().put("OWNER/original"))));
        Assert.assertTrue(GitHubStats.svg("owner", service.visibleFor("owner")).contains("3 Stars"));
        service.storeSnapshot("Owner", new JSONArray().put(repo("original", false, 20)).put(repo("forked", true, 4)));
        final var reloaded = service(rows);
        Assert.assertEquals(reloaded.visibleFor("Owner").length(), 1);
        Assert.assertTrue(GitHubStats.svg("owner", reloaded.visibleFor("Owner")).contains("4 Stars"));
        Assert.assertFalse(reloaded.consoleData("Owner").getJSONArray("repositories").getJSONObject(0).getBoolean("selected"));
        Assert.assertTrue(reloaded.visibleFor("another").isEmpty());
        reloaded.saveDisplay(reloaded.prepareDisplay("Owner", new JSONObject().put("username", "Owner").put("includeForks", true)
                .put("hidden", new JSONArray().put("Owner/original").put("Owner/forked"))));
        Assert.assertTrue(GitHubStats.svg("owner", reloaded.visibleFor("Owner")).contains("0 个公开项目"));
    }

    @Test public void oldArraySnapshotsAreReadableAndMalformedSelectionCannotReplaceThem() throws Exception {
        final Map<String, JSONObject> rows = new HashMap<>(); final var service = service(rows);
        rows.put(Option.ID_C_GITHUB_REPOS, new JSONObject().put(Option.OPTION_VALUE, new JSONArray().put(repo("original", false, 12)).toString()));
        Assert.assertTrue(service.hasSnapshot("Owner")); Assert.assertEquals(service.visibleFor("Owner").length(), 1);
        for (final Object hidden : new Object[]{new JSONArray().put("Other/repo"), new JSONArray().put("Owner/.."), new JSONArray().put(1), "invalid"}) {
            Assert.expectThrows(IllegalArgumentException.class, () -> service.prepareDisplay("Owner", new JSONObject().put("username", "Owner").put("includeForks", true).put("hidden", hidden)));
        }
        Assert.assertEquals(service.visibleFor("Owner").length(), 1);
    }
}
