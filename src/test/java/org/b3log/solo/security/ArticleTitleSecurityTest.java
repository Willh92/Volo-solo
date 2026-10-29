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

import freemarker.template.Configuration;
import freemarker.template.Template;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ArticleTitleSecurityTest {
    private static final String PAYLOAD = "<img src=1 onerror=alert(1)>\"' & </script><script>alert(2)</script>";

    @Test
    public void allTitleOutputsUseContextualEscaping() throws Exception {
        final Configuration configuration = new Configuration(Configuration.VERSION_2_3_35);
        final Pattern titles = Pattern.compile("\\$\\{([^{}]*\\b(?:articleTitle|nextArticleTitle|previousArticleTitle)\\b[^{}]*)}");
        final Map<String, Object> model = Map.of("article", Map.of("articleTitle", PAYLOAD),
                "articleTitle", PAYLOAD, "nextArticleTitle", PAYLOAD, "previousArticleTitle", PAYLOAD);
        int checked = 0;
        try (java.util.stream.Stream<Path> files = Files.walk(Path.of("src/main/webapp"))) {
            for (final Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".ftl"))::iterator) {
                final String source = Files.readString(file);
                final Matcher matcher = titles.matcher(source);
                while (matcher.find()) {
                    final String prefix = source.substring(0, matcher.start());
                    if (prefix.lastIndexOf("<@") > prefix.lastIndexOf('>')) {
                        continue; // Head macros escape the complete composed title.
                    }
                    final String expression = matcher.group(1);
                    Assert.assertTrue(expression.contains("?html") || expression.contains("?js_string") || expression.contains("?url"), file + ": " + expression);
                    final Template template = new Template(file.toString(), new StringReader(matcher.group()), configuration);
                    final StringWriter output = new StringWriter();
                    template.process(model, output);
                    Assert.assertFalse(output.toString().contains("</script>"), file.toString());
                    if (expression.contains("?html")) {
                        Assert.assertFalse(output.toString().contains("<img"), file.toString());
                        Assert.assertTrue(output.toString().contains("&amp;"), file.toString());
                    }
                    checked++;
                }
            }
        }
        Assert.assertTrue(checked >= 150, "Title coverage unexpectedly decreased: " + checked);
    }

    @Test
    public void composedHeadTitlesAreEscapedExactlyOnce() throws Exception {
        final String source = Files.readString(Path.of("src/main/webapp/common-template/macro-common_head.ftl"));
        Assert.assertTrue(source.contains("<title>${title?html}</title>"));
        final Configuration configuration = new Configuration(Configuration.VERSION_2_3_35);
        final Template template = new Template("head", new StringReader("<#macro head title><title>${title?html}</title></#macro><@head title='${article.articleTitle} - Blog'/>") , configuration);
        final StringWriter output = new StringWriter();
        template.process(Map.of("article", Map.of("articleTitle", "A & B <C>")), output);
        Assert.assertEquals(output.toString(), "<title>A &amp; B &lt;C&gt; - Blog</title>");
    }
}
