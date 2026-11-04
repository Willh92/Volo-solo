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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.b3log.latke.Latkes;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.owasp.encoder.Encode;

/** A local card backed by the repository data Bolo already synchronizes. No external image service. */
public final class GitHubStats {
    private GitHubStats() { }

    public static boolean validUser(final String username) {
        return username != null && username.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?");
    }

    public static String localSource(final String source) {
        try {
            final URI uri = URI.create(source.replace("&amp;", "&"));
            if (!"github-readme-stats.vercel.app".equalsIgnoreCase(uri.getHost())
                    || !"/api".equals(uri.getPath()) || uri.getRawQuery() == null) return source;
            for (final String parameter : uri.getRawQuery().split("&")) {
                if (parameter.startsWith("username=")) {
                    final String username = URLDecoder.decode(parameter.substring(9), StandardCharsets.UTF_8);
                    if (validUser(username)) return Latkes.getContextPath() + "/github-stats/" + username + ".svg";
                }
            }
        } catch (IllegalArgumentException ignored) { }
        return source;
    }

    public static String rewriteImages(final String html) {
        if (html == null) return null;
        final boolean hasStatsImage = html.contains("github-readme-stats.vercel.app") || html.contains("/github-stats/");
        final boolean hasRepoTable = html.contains("<table") && html.contains("项目简介") && html.contains("编程语言");
        if (!hasStatsImage && !hasRepoTable) return html;
        final var document = Jsoup.parseBodyFragment(html);
        document.outputSettings().prettyPrint(false);
        if (hasStatsImage) {
            final java.util.Set<String> profiles = new java.util.LinkedHashSet<>();
            for (final var image : document.select("img[src]")) {
                final String source = localSource(image.attr("src"));
                image.attr("src", source);
                try {
                    final URI uri = URI.create(source);
                    final String prefix = Latkes.getContextPath() + "/github-stats/";
                    final String path = uri.getPath();
                    if (path == null || !path.startsWith(prefix) || !path.endsWith(".svg")
                            || (uri.getRawAuthority() != null && !java.util.Objects.equals(uri.getAuthority(), URI.create(Latkes.getServePath()).getAuthority()))) continue;
                    final String username = path.substring(prefix.length(), path.length() - 4);
                    if (!validUser(username)) continue;
                    final String profile = "https://github.com/" + username;
                    profileLink(image, profile);
                    profiles.add(profile);
                } catch (IllegalArgumentException ignored) { }
            }
            if (profiles.size() == 1) {
                final String profile = profiles.iterator().next();
                for (final var image : document.select("img[src]")) {
                    try {
                        final String path = URI.create(image.attr("src")).getPath();
                        if (path != null && path.endsWith("/images/github_repo.jpg")) profileLink(image, profile);
                    } catch (IllegalArgumentException ignored) { }
                }
                for (final var heading : document.select("h2")) {
                    if ("github stats".equalsIgnoreCase(heading.text().trim())) {
                        if (heading.childrenSize() == 1 && "a".equals(heading.child(0).tagName())) {
                            heading.child(0).attr("href", profile).attr("target", "_blank").attr("rel", "noopener noreferrer");
                        } else if (heading.childrenSize() == 0) {
                            final String text = heading.text();
                            heading.empty().appendElement("a").attr("href", profile).attr("target", "_blank")
                                    .attr("rel", "noopener noreferrer").text(text);
                        }
                    }
                }
            }
        }
        if (hasRepoTable) {
            enhanceRepositoryTables(document);
        }
        return document.body().html();
    }

    private static void enhanceRepositoryTables(final org.jsoup.nodes.Document document) {
        for (final var table : document.select("table")) {
            final var headerRow = table.selectFirst("thead > tr, tr");
            if (headerRow == null) continue;
            final var headers = headerRow.select("> th");
            if (headers.size() != 5
                    || !"仓库".equals(headers.get(0).text().trim())
                    || !"项目简介".equals(headers.get(1).text().trim())
                    || !"Stars".equals(headers.get(2).text().trim())
                    || !"Forks".equals(headers.get(3).text().trim())
                    || !"编程语言".equals(headers.get(4).text().trim())) {
                continue;
            }
            table.addClass("volo-github-repos-table");
            if (table.parent() == null || !table.parent().hasClass("volo-github-repos-wrap")) {
                table.wrap("<div class=\"volo-github-repos-wrap\"></div>");
            }
            final var rows = table.select("tbody > tr");
            for (final var row : rows.isEmpty() ? table.select("> tr") : rows) {
                final var cells = row.select("> td");
                if (cells.size() < 5) continue;
                for (final var link : cells.get(0).select("a[href]")) {
                    link.attr("target", "_blank").attr("rel", "noopener noreferrer");
                }
                final var descCell = cells.get(1);
                descCell.addClass("volo-github-repo-desc-cell");
                if (descCell.selectFirst("> .volo-github-repo-desc") == null) {
                    final String plainDesc = descCell.text().trim();
                    if (!plainDesc.isEmpty()) {
                        final var wrapper = document.createElement("div")
                                .addClass("volo-github-repo-desc")
                                .attr("title", plainDesc);
                        for (final var child : new java.util.ArrayList<>(descCell.childNodes())) {
                            wrapper.appendChild(child);
                        }
                        descCell.appendChild(wrapper);
                    }
                }
            }
        }
    }

    private static void profileLink(final org.jsoup.nodes.Element image, final String profile) {
        org.jsoup.nodes.Element link = image.closest("a");
        if (link == null) {
            image.wrap("<a></a>");
            link = image.parent();
        }
        link.attr("href", profile).attr("target", "_blank").attr("rel", "noopener noreferrer").attr("data-github-profile", "true");
    }

    public static void normalizeNavigationIcon(final JSONObject page) {
        if ("/github".equals(page.optString(org.b3log.solo.model.Page.PAGE_PERMALINK))
                && page.optString(org.b3log.solo.model.Page.PAGE_ICON).endsWith("/images/github-icon.png")) {
            page.put(org.b3log.solo.model.Page.PAGE_ICON, Latkes.getContextPath() + "/images/github-icon.png");
        }
    }

    public static String svg(final String username, final JSONArray repositories) {
        return svg(username, repositories, false);
    }

    public static String svg(final String username, final JSONArray repositories, final boolean dark) {
        if (!validUser(username)) throw new IllegalArgumentException("Invalid GitHub username");
        long count = 0, stars = 0, forks = 0;
        final String prefix = "https://github.com/" + username.toLowerCase(java.util.Locale.ROOT) + "/";
        for (int index = 0; index < repositories.length(); index++) {
            final JSONObject repo = repositories.optJSONObject(index);
            if (repo == null || !repo.optString("githubrepoHTMLURL").toLowerCase(java.util.Locale.ROOT).startsWith(prefix)) continue;
            count++;
            stars += Math.max(0, repo.optLong("githubrepoStargazersCount"));
            forks += Math.max(0, repo.optLong("githubrepoForksCount"));
        }
        final String details = count + " 个公开项目     ·     " + stars + " Stars     ·     " + forks + " Forks";
        final String background = dark ? "#242b2c" : "#f5f9f5";
        final String border = dark ? "#3c4945" : "#dce8df";
        final String heading = dark ? "#d6dfda" : "#244b3f";
        final String body = dark ? "#a1b1a8" : "#4b6858";
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"640\" height=\"150\" viewBox=\"0 0 640 150\" role=\"img\" aria-label=\"GitHub repository statistics\">"
                + "<rect x=\"1\" y=\"1\" width=\"638\" height=\"148\" rx=\"16\" fill=\"" + background + "\" stroke=\"" + border + "\"/>"
                + "<text x=\"28\" y=\"48\" fill=\"" + heading + "\" font-family=\"sans-serif\" font-size=\"22\" font-weight=\"600\">GitHub · " + Encode.forXmlContent(username) + "</text>"
                + "<text x=\"28\" y=\"86\" fill=\"" + body + "\" font-family=\"sans-serif\" font-size=\"18\">" + Encode.forXmlContent(details) + "</text>"
                + "<text x=\"28\" y=\"122\" fill=\"" + body + "\" font-family=\"sans-serif\" font-size=\"11\">按当前展示的公开仓库汇总 · Stars 为这些仓库收到的 Star 总数</text></svg>";
    }
}
