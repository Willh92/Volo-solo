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

import org.b3log.solo.model.Article;
import org.b3log.solo.service.ImportService;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.Test;

public class MarkdownImportSecurityTest {
    private JSONObject parse(final String markdown) throws Exception {
        final java.lang.reflect.Method method = ImportService.class.getDeclaredMethod("parseArticle", String.class, String.class);
        method.setAccessible(true);
        return (JSONObject) method.invoke(new ImportService(), "fallback.md", markdown);
    }

    public static class UnexpectedObject {
        static boolean constructed;
        public UnexpectedObject() {
            constructed = true;
        }
    }

    @Test
    public void preservesOrdinaryFrontMatter() throws Exception {
        final JSONObject article = parse("---\ntitle: Hello & 世界\ntags: [Java, Blog]\ndate: 2026-10-29 12:30:00\n---\n# Content");
        Assert.assertEquals(article.getString(Article.ARTICLE_TITLE), "Hello & 世界");
        Assert.assertEquals(java.util.Set.of(article.getString(Article.ARTICLE_TAGS_REF).split(",")), java.util.Set.of("Java", "Blog"));
        Assert.assertEquals(article.getString(Article.ARTICLE_CONTENT), "# Content");
    }

    @Test
    public void neverConstructsYamlJavaObjects() throws Exception {
        UnexpectedObject.constructed = false;
        final JSONObject article = parse("---\n!!" + UnexpectedObject.class.getName() + " {}\n---\nBody");
        Assert.assertFalse(UnexpectedObject.constructed);
        Assert.assertEquals(article.getString(Article.ARTICLE_TITLE), "fallback");
    }

    @Test
    public void handlesPlainMarkdownAndNonMappingYaml() throws Exception {
        for (final String markdown : new String[] {"", "plain markdown", "---\n[one, two]\n---\nBody", "---\ntitle: one\ntitle: two\n---\nBody"}) {
            Assert.assertEquals(parse(markdown).getString(Article.ARTICLE_TITLE), "fallback");
        }
    }
}
