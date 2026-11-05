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

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import org.b3log.latke.Latkes;
import org.b3log.solo.model.Page;
import org.json.JSONObject;

/** Server-owned management URLs and role-aware navigation entries. */
public final class AdminPaths {
    private static final Set<String> RESERVED = Set.of("js", "scss", "skins", "images", "image", "favicon",
            "console", "oauth", "error", "plugins", "start", "root", "logout", "article", "articles", "page",
            "tags", "category", "categories", "archives", "search", "github-stats", "upload", "uploads",
            "admin", "api", "apis", "user", "blog", "follow", "authors", "fix", "captcha", "get-article-content", "kill-browser");

    private AdminPaths() { }
    private static volatile String loginOverride;
    public static String validateLogin(final String input) {
        final String path = input == null ? "" : input.strip();
        if (path.isEmpty() || path.equals("/start")) return "";
        if (path.length() > 120 || !path.matches("/[A-Za-z0-9][A-Za-z0-9_-]*(/[A-Za-z0-9][A-Za-z0-9_-]*)*")
                || RESERVED.contains(path.substring(1).split("/")[0].toLowerCase(java.util.Locale.ROOT))
                || path.equals(prefix()) || !prefix().isEmpty() && path.startsWith(prefix() + "/")) {
            throw new IllegalArgumentException("登录路径请填写 /private-login 等本地路径，不能使用保留路径或后台面板路径。");
        }
        return path;
    }
    public static void configureLogin(final String path) { loginOverride = path == null ? null : validateLogin(path); }
    public static String loginRoute() { return customized() ? prefix() + "/login" : "/start"; }

    public static String prefix() {
        final String configured = Latkes.getLocalProperty("adminPath");
        final String value = configured == null ? "" : configured.trim();
        if (value.isEmpty()) return "";
        if (value.length() > 120 || !value.matches("/[A-Za-z0-9][A-Za-z0-9_-]*(/[A-Za-z0-9][A-Za-z0-9_-]*)*")
                || RESERVED.contains(value.substring(1).split("/")[0].toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("adminPath must be a local path such as /private-panel, without a trailing slash or a reserved prefix");
        }
        return value;
    }

    public static boolean customized() { return !prefix().isEmpty(); }
    public static String panel() { return customized() ? prefix() : "/admin-index.do"; }
    public static String login() { return loginOverride == null ? loginRoute() : loginOverride.isEmpty() ? "/start" : loginOverride; }
    public static String pageBase() { return prefix() + "/"; }

    public static boolean legacyEntry(final String uri) {
        if (uri.equals(login())) return false;
        if (loginOverride != null && (uri.equals(loginRoute()) || uri.equals("/start") || uri.equals("/root"))) return true;
        return customized() && (uri.equals("/start") || uri.equals("/root") || uri.matches("/admin-[a-z-]+\\.do"));
    }

    public static String route(final String uri) {
        if ("/start".equals(uri)) return loginRoute();
        if ("/admin-index.do".equals(uri)) return panel();
        if (uri.matches("/admin-[a-z-]+\\.do")) return prefix() + uri;
        return uri;
    }

    public static void fill(final Map<String, Object> model) {
        final String context = Latkes.getContextPath();
        model.put("adminPanelPath", context + panel());
        model.put("adminLoginPath", context + login());
        model.put("adminPageBase", context + pageBase());
    }

    /** Use each skin's existing custom-navigation renderer without changing stored site navigation. */
    public static List<JSONObject> navigation(final List<JSONObject> configured, final boolean administrator) {
        return navigation(configured, administrator, false);
    }

    public static List<JSONObject> navigation(final List<JSONObject> configured, final boolean administrator, final boolean showLogin) {
        final List<JSONObject> result = new ArrayList<>(configured);
        if (administrator) {
            result.add(navigationItem("后台管理", Latkes.getServePath() + panel(), "admin-panel.svg"));
            result.add(navigationItem("退出登录", Latkes.getServePath() + "/logout", "admin-logout.svg"));
        } else if (showLogin) result.add(navigationItem("管理员登录", Latkes.getServePath() + login(), "admin-login.svg"));
        return result;
    }

    private static JSONObject navigationItem(final String title, final String target, final String icon) {
        return new JSONObject().put(Page.PAGE_TITLE, title).put(Page.PAGE_PERMALINK, target)
                .put(Page.PAGE_OPEN_TARGET, "_self").put(Page.PAGE_ICON, Latkes.getContextPath() + "/images/" + icon);
    }

    public static boolean reserved(final String uri) {
        return uri.equals(login()) || customized() && (uri.equals(prefix()) || uri.startsWith(prefix() + "/"));
    }
}
