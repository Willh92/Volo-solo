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
import org.b3log.solo.bolo.tool.ZipFilterUtils;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ZipSecurityTest {
    @DataProvider
    public Object[][] paths() {
        return new Object[][] {{"../outside.md"}, {"nested/../../outside.md"}, {"/tmp/outside.md"},
                {"..\\outside.md"}, {"C:/outside.md"}, {"nested/file.md:payload.ftl"},
                {"nested/.. /outside.md"}, {"nested/.. ./outside.md"}};
    }

    private Path archive(final Path directory, final String... names) throws IOException {
        final Path zip = directory.resolve("input.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (final String name : names) {
                output.putNextEntry(new ZipEntry(name));
                output.write("title: Example\n---\nMarkdown".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return zip;
    }

    @Test(dataProvider = "paths")
    public void rejectsTraversalBeforeWriting(final String entry) throws Exception {
        final Path directory = Files.createTempDirectory("bolo-zip-test-");
        try {
            final Path zip = archive(directory, entry);
            final Path output = directory.resolve("output");
            Assert.expectThrows(Exception.class, () -> ZipFilterUtils.unpackFilteredZip(zip.toFile(), output.toFile(), null));
            try (java.util.stream.Stream<Path> files = Files.walk(output)) {
                Assert.assertEquals(files.filter(Files::isRegularFile).count(), 0L);
            }
            Assert.assertFalse(Files.exists(directory.resolve("outside.md")));
        } finally {
            FileUtils.deleteDirectory(directory.toFile());
        }
    }

    @Test
    public void extractsNestedMarkdownAndSkipsMacMetadata() throws Exception {
        final Path directory = Files.createTempDirectory("bolo-zip-test-");
        try {
            final Path zip = archive(directory, "posts/article.md", "__MACOSX/._article.md", "posts/.DS_Store");
            final Path output = directory.resolve("output");
            ZipFilterUtils.unpackFilteredZip(zip.toFile(), output.toFile(), null);
            Assert.assertTrue(Files.readString(output.resolve("posts/article.md")).contains("Markdown"));
            Assert.assertFalse(Files.exists(output.resolve("__MACOSX")));
            Assert.assertFalse(Files.exists(output.resolve("posts/.DS_Store")));
            final Path mappedOutput = directory.resolve("mapped");
            Assert.expectThrows(Exception.class, () -> ZipFilterUtils.unpackFilteredZip(zip.toFile(), mappedOutput.toFile(), name -> "../mapped.md"));
            Assert.assertFalse(Files.exists(directory.resolve("mapped.md")));
        } finally {
            FileUtils.deleteDirectory(directory.toFile());
        }
    }
}
