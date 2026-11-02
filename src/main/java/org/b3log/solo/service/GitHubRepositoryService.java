/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.service;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.service.ServiceException;
import org.b3log.latke.service.annotation.Service;
import org.b3log.solo.model.Option;
import org.b3log.solo.util.GitHubStats;
import org.b3log.solo.util.OptionDocuments;
import org.json.JSONArray;
import org.json.JSONObject;

/** Stores the complete public snapshot separately from its display selection. */
@Service
public class GitHubRepositoryService {
    private static final String DISPLAY_OPTION = "githubRepositoryDisplay";
    @Inject private OptionQueryService optionQueryService;
    @Inject private OptionMgmtService optionMgmtService;

    public JSONObject snapshot() {
        final String value = OptionDocuments.read(optionQueryService, Option.ID_C_GITHUB_REPOS, "[]");
        if (!value.stripLeading().startsWith("[")) return new JSONObject(value);
        final JSONArray repositories = new JSONArray(value);
        String username = "";
        if (!repositories.isEmpty()) {
            final JSONObject first = repositories.getJSONObject(0);
            username = first.optString("githubrepoOwnerLogin");
            if (username.isEmpty()) {
                final String url = first.optString("githubrepoHTMLURL");
                if (url.startsWith("https://github.com/")) username = url.substring(19).split("/", 2)[0];
            }
        }
        return new JSONObject().put("username", username).put("updatedAt", 0).put("repositories", repositories);
    }

    public void storeSnapshot(final String username, final JSONArray repositories) throws ServiceException {
        if (!GitHubStats.validUser(username)) throw new IllegalArgumentException("GitHub 用户名不正确。");
        optionMgmtService.storeDocument(Option.ID_C_GITHUB_REPOS, Option.CATEGORY_C_GITHUB,
                new JSONObject().put("username", username).put("updatedAt", System.currentTimeMillis())
                        .put("repositories", repositories).toString());
    }

    private JSONObject display() {
        return new JSONObject(OptionDocuments.read(optionQueryService, DISPLAY_OPTION, "{}"));
    }

    private static Set<String> hidden(final JSONObject display) {
        final Set<String> hidden = new LinkedHashSet<>();
        final JSONArray values = display.optJSONArray("hidden");
        if (values != null) for (int i = 0; i < values.length(); i++) hidden.add(values.getString(i).toLowerCase(Locale.ROOT));
        return hidden;
    }

    /** Validate before saving any preferences; preserve other accounts' selections. */
    public JSONObject prepareDisplay(final String username, final JSONObject requested) {
        if (!GitHubStats.validUser(username) || !username.equalsIgnoreCase(requested.optString("username"))
                || !(requested.opt("includeForks") instanceof Boolean) || !(requested.opt("hidden") instanceof JSONArray)) {
            throw new IllegalArgumentException("仓库显示设置不正确，请重新载入此页面。");
        }
        final JSONArray values = requested.getJSONArray("hidden");
        if (values.length() > 10000) throw new IllegalArgumentException("仓库显示设置过多。");
        final String prefix = username.toLowerCase(Locale.ROOT) + "/";
        final Set<String> selected = hidden(display());
        selected.removeIf(name -> name.startsWith(prefix));
        for (int i = 0; i < values.length(); i++) {
            if (!(values.opt(i) instanceof String)) throw new IllegalArgumentException("仓库名称不正确。");
            final String name = values.getString(i).toLowerCase(Locale.ROOT);
            if (!name.startsWith(prefix) || !name.substring(prefix.length()).matches("[a-z0-9_.-]{1,100}")
                    || name.endsWith("/.") || name.endsWith("/..")) {
                throw new IllegalArgumentException("仓库名称不正确。");
            }
            selected.add(name);
        }
        final JSONObject result = new JSONObject().put("includeForks", requested.getBoolean("includeForks"))
                .put("hidden", new JSONArray(selected));
        if (result.toString().length() > 1_000_000) throw new IllegalArgumentException("仓库显示设置过多。");
        return result;
    }

    public void saveDisplay(final JSONObject prepared) throws ServiceException {
        optionMgmtService.storeDocument(DISPLAY_OPTION, Option.CATEGORY_C_GITHUB, prepared.toString());
    }

    private static String name(final JSONObject repository, final String username) {
        final String fullName = repository.optString("githubrepoFullName");
        if (!fullName.isEmpty()) return fullName.toLowerCase(Locale.ROOT);
        final String prefix = "https://github.com/";
        final String url = repository.optString("githubrepoHTMLURL");
        return url.regionMatches(true, 0, prefix, 0, prefix.length())
                ? url.substring(prefix.length()).toLowerCase(Locale.ROOT)
                : (username + "/" + repository.optString("githubrepoName")).toLowerCase(Locale.ROOT);
    }

    public boolean hasSnapshot(final String username) {
        return GitHubStats.validUser(username) && username.equalsIgnoreCase(snapshot().optString("username"));
    }

    public JSONArray visibleFor(final String username) {
        final JSONObject snapshot = snapshot();
        final JSONArray result = new JSONArray();
        if (!GitHubStats.validUser(username) || !username.equalsIgnoreCase(snapshot.optString("username"))) return result;
        final JSONObject display = display();
        final Set<String> hidden = hidden(display);
        final JSONArray repositories = snapshot.getJSONArray("repositories");
        for (int i = 0; i < repositories.length(); i++) {
            final JSONObject repo = repositories.getJSONObject(i);
            if (!name(repo, username).startsWith(username.toLowerCase(Locale.ROOT) + "/")) continue;
            if (hidden.contains(name(repo, username)) || (repo.optBoolean("githubrepoFork") && !display.optBoolean("includeForks"))) continue;
            result.put(repo);
        }
        return result;
    }

    public JSONObject consoleData(final String username) {
        final JSONObject snapshot = snapshot(), display = display();
        final boolean synced = GitHubStats.validUser(username) && username.equalsIgnoreCase(snapshot.optString("username"));
        final Set<String> hidden = hidden(display);
        final JSONArray rows = new JSONArray();
        final JSONArray repositories = synced ? snapshot.getJSONArray("repositories") : new JSONArray();
        for (int i = 0; i < repositories.length(); i++) {
            final JSONObject repo = repositories.getJSONObject(i);
            if (!name(repo, username).startsWith(username.toLowerCase(Locale.ROOT) + "/")) continue;
            rows.put(new JSONObject().put("fullName", name(repo, username)).put("name", repo.optString("githubrepoName"))
                    .put("description", repo.optString("githubrepoDescription")).put("fork", repo.optBoolean("githubrepoFork"))
                    .put("stars", Math.max(0, repo.optLong("githubrepoStargazersCount")))
                    .put("forks", Math.max(0, repo.optLong("githubrepoForksCount")))
                    .put("selected", !hidden.contains(name(repo, username))));
        }
        return new JSONObject().put("sc", true).put("username", username).put("synced", synced)
                .put("updatedAt", synced ? snapshot.optLong("updatedAt") : 0)
                .put("includeForks", display.optBoolean("includeForks")).put("repositories", rows);
    }
}
