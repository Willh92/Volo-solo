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
import org.b3log.solo.util.Uploads;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

public class UploadSecurityTest {
    @DataProvider
    public Object[][] invalidNames() {
        return new Object[][] {{"../index.ftl"}, {"/../skins/index.ftl"}, {"..\\index.png"},
                {"C:\\index.png"}, {"image.png:template.ftl"}, {"image.png."}, {"image.png "},
                {"image\u0000.png"}, {"image.svg"}, {"index.ftl"}};
    }

    @Test(dataProvider = "invalidNames", expectedExceptions = java.io.IOException.class)
    public void rejectsUnsafeImageNames(final String filename) throws Exception {
        Uploads.extension(filename, "png", "jpg", "gif");
    }

    @Test
    public void stagesUnderGeneratedNameOutsideTheWebRoot() throws Exception {
        final Path directory = Files.createTempDirectory("bolo-upload-test-");
        try {
            final File staged = Uploads.stage("中文备份.ZIP", new java.io.ByteArrayInputStream(new byte[]{1,2,3}), directory, "zip");
            Assert.assertTrue(staged.toPath().startsWith(directory));
            Assert.assertNotEquals(staged.getName(), "中文备份.ZIP");
            Assert.assertTrue(staged.getName().endsWith(".zip"));
            Assert.assertEquals(Files.readAllBytes(staged.toPath()), new byte[] {1, 2, 3});
        } finally {
            FileUtils.deleteDirectory(directory.toFile());
        }
    }

    @Test
    public void validatesImageContentInsteadOfTrustingTheExtension() throws Exception {
        final Path file = Files.createTempFile("bolo-fake-image-", ".png");
        try {
            Files.writeString(file, "<#assign execute='freemarker.template.utility.Execute'?new()>");
            Assert.expectThrows(java.io.IOException.class, () -> Uploads.validateImage(file.toFile()));
            javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", file.toFile());
            Uploads.validateImage(file.toFile());
        } finally {
            Files.delete(file);
        }
    }

    @Test
    public void localUploadUrlsResolveFromNestedArticlesAndRespectTheContextPath() throws Exception {
        LatkeTestSupport.configure();
        final String originalContext = org.b3log.latke.Latkes.getContextPath();
        final Path directory = Files.createTempDirectory("volo-local-image-test-");
        final Path destination = directory.resolve("pictures");
        final byte[] content = new byte[]{1, 2, 3};
        try {
            for (final String context : new String[]{"", "/blog"}) {
                org.b3log.latke.Latkes.setContextPath(context);
                final Path input = Files.createTempFile(directory, "source-", ".png");
                Files.write(input, content);
                final String address = org.b3log.solo.bolo.pic.util.UploadUtil.upload("local<<>>" + destination, input.toFile());
                Assert.assertTrue(address.startsWith(context + "/image/"), address);
                final java.net.URI article = java.net.URI.create("https://example.test" + context + "/posts/entry.html");
                final java.net.URI image = article.resolve(address);
                Assert.assertEquals(image.getHost(), "example.test");
                Assert.assertTrue(image.getPath().startsWith(context + "/image/"), image.toString());
                Assert.assertFalse(image.getPath().contains("/posts/"));
                final String filename = address.substring(address.lastIndexOf('/') + 1);
                Assert.assertEquals(Files.readAllBytes(destination.resolve(filename)), content);
            }
        } finally {
            org.b3log.latke.Latkes.setContextPath(originalContext);
            FileUtils.deleteDirectory(directory.toFile());
        }
    }

    @Test
    public void compressionPreservesPngTransparencyAndGifBytes() throws Exception {
        final Path directory = Files.createTempDirectory("volo-image-format-test-");
        try {
            final java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            image.setRGB(1, 1, 0x80ff0000);
            for (final String format : new String[]{"png", "gif"}) {
                final File input = directory.resolve("source." + format).toFile();
                Assert.assertTrue(javax.imageio.ImageIO.write(image, format, input));
                final byte[] original = Files.readAllBytes(input.toPath());
                for (final float quality : new float[]{0.6f, 1f, 10f}) {
                    final File result = org.b3log.solo.util.Images.compressImage(input, quality);
                    Assert.assertEquals(result, input, "Non-JPEG files must retain transparency and animation");
                    Assert.assertEquals(Files.readAllBytes(result.toPath()), original);
                }
            }
            try (java.util.stream.Stream<Path> files = Files.list(directory)) {
                Assert.assertEquals(files.count(), 2L);
            }
        } finally {
            FileUtils.deleteDirectory(directory.toFile());
        }
    }

    @Test
    public void compressesJpegsWithoutDeletingTheOriginalUpload() throws Exception {
        final Path directory = Files.createTempDirectory("volo-jpeg-test-");
        try {
            final File input = directory.resolve("source.jpg").toFile();
            final java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(128, 128, java.awt.image.BufferedImage.TYPE_INT_RGB);
            final java.util.Random random = new java.util.Random(42);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, random.nextInt(0x1000000));
            }
            Assert.assertTrue(javax.imageio.ImageIO.write(image, "jpg", input));
            final byte[] original = Files.readAllBytes(input.toPath());
            final File result = org.b3log.solo.util.Images.compressImage(input, 0.3f);
            Assert.assertNotEquals(result, input);
            Assert.assertTrue(result.length() < input.length());
            final java.awt.image.BufferedImage decoded = javax.imageio.ImageIO.read(result);
            Assert.assertEquals(decoded.getWidth(), 128);
            Assert.assertEquals(decoded.getHeight(), 128);
            Assert.assertEquals(Files.readAllBytes(input.toPath()), original);
        } finally {
            FileUtils.deleteDirectory(directory.toFile());
        }
    }

    @Test
    public void invalidCompressionQualityDoesNotAlterTheUpload() throws Exception {
        final Path input = Files.createTempFile("volo-invalid-quality-", ".png");
        try {
            final byte[] original = new byte[]{1, 2, 3};
            Files.write(input, original);
            for (final float quality : new float[]{Float.NaN, Float.POSITIVE_INFINITY, -0.1f}) {
                Assert.expectThrows(java.io.IOException.class, () -> org.b3log.solo.util.Images.compressImage(input.toFile(), quality));
                Assert.assertEquals(Files.readAllBytes(input), original);
            }
        } finally {
            Files.delete(input);
        }
    }
}
