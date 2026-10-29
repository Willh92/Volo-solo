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

import org.b3log.solo.util.Images;
import org.b3log.solo.model.Article;
import org.b3log.solo.util.GitHubStats;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class ImageResourceTest {
    @BeforeClass public void configure() throws Exception { LatkeTestSupport.configure(); }

    @Test public void coversUseTheFirstContentImageAndPreserveSignedUrls() {
        final String source = "https://example.com/photo.jpg?token=signed&size=wide";
        Assert.assertEquals(Article.getArticleCoverURL("<p><img src='" + source.replace("&", "&amp;")
                + "'></p><img src='/second.png'>"), source);
        Assert.assertEquals(Article.getArticleCoverURL("<p>No image.</p>"), "");
    }

    @Test public void coversSkipUnsafeUrlsAndIconsWithoutRandomFallback() {
        Assert.assertEquals(Article.getArticleCoverURL("<img src='javascript:alert(1)'><img src='/favicon.ico'><img src='/images/cover.svg'>"), "/images/cover.svg");
        Assert.assertEquals(Article.getArticleCoverURL("<img src='data:image/svg+xml,unsafe'><img src='file:///etc/passwd'>"), "");
    }

    @Test public void randomBingImagesStayInsideTheAvailableArchive() {
        final java.time.LocalDate first = java.time.LocalDate.of(2017, 11, 4);
        final java.time.LocalDate last = java.time.LocalDate.of(2023, 12, 20);
        final java.util.Set<String> generated = new java.util.HashSet<>();
        for (int i = 0; i < 512; i++) {
            final String image = Images.randImage();
            generated.add(image);
            final var date = java.util.regex.Pattern.compile("/bing/(\\d{8})\\.jpg$").matcher(image);
            Assert.assertTrue(date.find(), image);
            final java.time.LocalDate day = java.time.LocalDate.parse(date.group(1), java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            Assert.assertFalse(day.isBefore(first), image);
            Assert.assertFalse(day.isAfter(last), image);
        }
        Assert.assertTrue(generated.size() > 1, "Random covers must not collapse to the exception fallback when no image bed is configured");
    }

    @Test public void preservesDynamicAndSignedImageUrls() {
        for (final String url : new String[]{"https://github-readme-stats.vercel.app/api?username=willh92&show_icons=true", "https://example.com/photo.jpg?token=signed", "https://example.com/card.svg", "https://example.com/photo.gif"}) {
            Assert.assertEquals(Images.imageSize(url, 960, 540), url);
        }
        Assert.assertTrue(Images.imageSize("https://example.com/photo.jpg", 960, 540).contains("?imageView2/1/w/960/h/540"));
    }

    @Test public void existingGithubCardsUseTheLocalEndpoint() {
        final String html = GitHubStats.rewriteImages("<p><img src=\"https://github-readme-stats.vercel.app/api?username=willh92&amp;show_icons=true?imageView2/2/w/1280\"></p>");
        Assert.assertTrue(html.contains("/github-stats/willh92.svg"));
        Assert.assertFalse(html.contains("vercel.app"));
        Assert.assertEquals(GitHubStats.localSource("https://example.com/card.svg"), "https://example.com/card.svg");
    }

    @Test public void cardsCountOnlyTheRequestedOwnersCachedPublicRepos() {
        final JSONArray repos = new JSONArray().put(new JSONObject().put("githubrepoHTMLURL", "https://github.com/Willh92/bolo-solo")
                .put("githubrepoStargazersCount", 12).put("githubrepoForksCount", 3))
                .put(new JSONObject().put("githubrepoHTMLURL", "https://github.com/other/repo").put("githubrepoStargazersCount", 999));
        final String svg = GitHubStats.svg("willh92", repos);
        Assert.assertTrue(svg.contains("12 Stars"));
        Assert.assertFalse(svg.contains("999"));
        Assert.assertFalse(GitHubStats.validUser("<script>"));
        Assert.assertTrue(GitHubStats.svg("willh92", repos, true).contains("#242b2c"));
    }

    @Test public void oldGithubNavigationIconsNeverPointAtTheVisitorsLocalhost() {
        final JSONObject page = new JSONObject().put("pagePermalink", "/github")
                .put("pageIcon", "https://localhost/images/github-icon.png");
        GitHubStats.normalizeNavigationIcon(page);
        Assert.assertEquals(page.getString("pageIcon"), "/images/github-icon.png");
    }
}
