/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.service;

import java.util.ArrayList;
import org.b3log.solo.model.Option;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.Test;

public class GitHubSyncTest {
    @Test public void eachScheduledRunUsesCurrentContributionAndProfileSwitches() throws Exception {
        final JSONObject preference = new JSONObject().put(Option.ID_C_MY_GITHUB_ID, "owner");
        final var refreshes = new ArrayList<String>(); final var profiles = new ArrayList<Boolean>();
        final CronMgmtService service = new CronMgmtService();
        inject(service, "optionQueryService", new OptionQueryService() { @Override public JSONObject getPreference() { return preference; } });
        inject(service, "articleMgmtService", new ArticleMgmtService() { @Override public boolean refreshGitHub(final String id) { refreshes.add(id); return true; } });
        inject(service, "exportService", new ExportService() { @Override public boolean exportGitHub(final boolean profile) { profiles.add(profile); return true; } });
        service.synchronizeGitHub();
        preference.put(Option.ID_C_ENABLE_AUTO_FLUSH_GITHUB, true).put(Option.ID_C_ENABLE_AUTO_FLUSH_BLOG_TO_GITHUB_PROFILE, true);
        service.synchronizeGitHub();
        preference.put(Option.ID_C_ENABLE_AUTO_FLUSH_GITHUB, false).put(Option.ID_C_ENABLE_AUTO_FLUSH_BLOG_TO_GITHUB_PROFILE, false);
        service.synchronizeGitHub();
        Assert.assertEquals(refreshes, java.util.List.of("owner"));
        Assert.assertEquals(profiles, java.util.List.of(false, true, false));
    }
    private static void inject(final Object target, final String name, final Object value) throws Exception {
        final var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
}
