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

import org.b3log.solo.model.Sign;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ArticleSignSecurityTest {
    @Test
    public void escapesTitlesAndPreservesSignatureMarkup() {
        final String html = Sign.render("<hr>标题：{title}<br><a href=\"{url}\">{author}</a>",
                "<img src=1 onerror=alert(1)>", "A & B", "https://example.test/?a=1&b=2", "https://example.test");
        Assert.assertFalse(html.contains("<img"));
        Assert.assertTrue(html.startsWith("<hr>标题：&lt;img"));
        Assert.assertTrue(html.contains("A &amp; B</a>"));
        Assert.assertTrue(html.contains("href=\"https://example.test/?a=1&amp;b=2\""));
    }

    @Test
    public void doesNotExpandPlaceholdersWithinTheTitle() {
        Assert.assertEquals(Sign.render("{title} {author}", "Literal {author} $1", "Name", "url", "blog"),
                "Literal {author} $1 Name");
    }
}
