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

import org.apache.commons.io.FileUtils;
import org.testng.Assert;
import org.testng.annotations.Test;
import pers.adlered.blog_platform_export_tool.Launcher;
import pers.adlered.blog_platform_export_tool.module.TranslateResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class CnblogsImportSecurityTest {
    @Test
    public void importsOrdinaryRssUsingModernRome() throws Exception {
        final Path directory = Files.createTempDirectory("bolo-cnblogs-test-");
        try {
            Files.writeString(directory.resolve("backup.xml"), "<rss version=\"2.0\"><channel><title>Blog</title><link>https://example.test</link><description>Backup</description><item><title>Article &amp; title</title><link>https://example.test/post</link><description>Content</description><pubDate>Thu, 29 Oct 2026 12:00:00 GMT</pubDate></item></channel></rss>");
            final List<TranslateResult> result = Launcher.run("CNBlogs", directory.toString());
            Assert.assertEquals(result.size(), 1);
            Assert.assertEquals(result.get(0).getTitle(), "Article & title");
            Assert.assertEquals(result.get(0).getArticleContent(), "Content");
        } finally {
            FileUtils.deleteDirectory(directory.toFile());
        }
    }

    @Test
    public void doesNotResolveXmlExternalEntities() throws Exception {
        final Path directory = Files.createTempDirectory("bolo-cnblogs-test-");
        try {
            final Path secret = Files.writeString(directory.resolve("secret.txt"), "EXTERNAL_ENTITY_MUST_NOT_LOAD");
            Files.writeString(directory.resolve("backup.xml"), "<!DOCTYPE rss [<!ENTITY xxe SYSTEM \"" + secret.toUri() + "\">]><rss version=\"2.0\"><channel><title>Blog</title><link>https://example.test</link><description>Backup</description><item><title>Article</title><description>&xxe;</description></item></channel></rss>");
            Assert.assertTrue(Launcher.run("CNBlogs", directory.toString()).isEmpty());
        } finally {
            FileUtils.deleteDirectory(directory.toFile());
        }
    }
}
