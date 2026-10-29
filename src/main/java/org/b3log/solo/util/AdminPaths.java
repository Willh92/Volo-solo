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
import java.util.Set;
import org.b3log.latke.Latkes;

/** Server-owned management URLs. Public templates never render these paths. */
public final class AdminPaths {
    private static final Set<String> RESERVED = Set.of("js", "scss", "skins", "images", "image", "favicon",
            "console", "oauth", "error", "plugins", "start", "root", "logout", "article", "articles", "page",
            "tags", "category", "categories", "archives", "search", "github-stats", "upload", "uploads",
            "admin", "apis", "blog", "follow", "authors", "fix", "captcha", "get-article-content", "kill-browser");

    private AdminPaths() { }

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
    public static String login() { return customized() ? prefix() + "/login" : "/start"; }
    public static String pageBase() { return prefix() + "/"; }

    public static boolean legacyEntry(final String uri) {
        return customized() && (uri.equals("/start") || uri.equals("/root") || uri.matches("/admin-[a-z-]+\\.do"));
    }

    public static String route(final String uri) {
        if ("/start".equals(uri)) return login();
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

    public static boolean reserved(final String uri) {
        return customized() && (uri.equals(prefix()) || uri.startsWith(prefix() + "/"));
    }
}
