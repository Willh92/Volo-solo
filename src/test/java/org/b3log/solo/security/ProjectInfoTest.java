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

import org.b3log.solo.util.ProjectInfo;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ProjectInfoTest {
    @Test public void publicMetadataAndReleaseLinksAreRestrictedToTheConfiguredProject() {
        final JSONObject repository = new JSONObject().put("full_name", "Willh92/Volo-solo")
                .put("description", "<script>alert(1)</script>").put("stargazers_count", 7).put("forks_count", -5)
                .put("license", new JSONObject().put("spdx_id", "AGPL-3.0"));
        final JSONObject release = new JSONObject().put("tag_name", "v3.1/a b").put("html_url", "javascript:alert(1)");
        final JSONObject parsed = ProjectInfo.parse(repository, release);
        Assert.assertEquals(parsed.getLong("stars"), 7);
        Assert.assertEquals(parsed.getLong("forks"), 0);
        Assert.assertEquals(parsed.getString("license"), "AGPL-3.0");
        Assert.assertEquals(parsed.getJSONObject("release").getString("url"), ProjectInfo.REPOSITORY + "/releases/tag/v3.1%2Fa%20b");
        Assert.assertFalse(ProjectInfo.parse(repository, null).has("release"));
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void unrelatedRepositoryCannotPopulateTheCache() { ProjectInfo.parse(new JSONObject().put("full_name", "someone/other"), null); }
}
