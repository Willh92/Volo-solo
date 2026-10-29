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

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.apache.logging.log4j.Level;
import org.b3log.solo.logging.Logger;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Image utilities.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 2.7.0
 */
public final class Images {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(Images.class);

    /**
     * Community file service URL.
     */
    public static String COMMUNITY_FILE_URL = "https://b3logfile.com";

    /**
     * Private constructor.
     */
    private Images() {
    }

    /**
     * Qiniu image processing.
     *
     * @param html the specified content HTML
     * @return processed content
     */
    public static String qiniuImgProcessing(final String html) {
        String ret = GitHubStats.rewriteImages(html);
        final String[] imgSrcs = StringUtils.substringsBetween(ret, "<img src=\"", "\"");
        if (null == imgSrcs) {
            return ret;
        }

        for (final String imgSrc : imgSrcs) {
            if (!canProcessImage(imgSrc)) {
                continue;
            }

            ret = StringUtils.replace(ret, imgSrc, imgSrc + "?imageView2/2/w/1280/format/jpg/interlace/1/q/100");
        }

        return ret;
    }

    /**
     * Returns image URL of Qiniu image processing style with the specified width and height.
     *
     * @param imageURL the specified image URL
     * @param width    the specified width
     * @param height   the specified height
     * @return image URL
     */
    public static String imageSize(final String imageURL, final int width, final int height) {
        if (!canProcessImage(imageURL)) {
            return imageURL;
        }

        return imageURL + "?imageView2/1/w/" + width + "/h/" + height + "/interlace/1/q/100";
    }

    private static boolean canProcessImage(final String url) {
        if (url == null || url.contains("?") || url.contains("#")) return false;
        try {
            final String path = java.net.URI.create(url).getPath();
            return path != null && path.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(?:png|jpe?g|webp|bmp)$");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static File compressImage(File inputFile, float quality) throws IOException {
        if (!Float.isFinite(quality) || quality < 0) {
            throw new IOException("Image compression quality must be a finite, non-negative number");
        }
        // Quality 1 (and the legacy value 10) keeps the original file unchanged.
        if (quality >= 1) return inputFile;

        final BufferedImage image;
        try (ImageInputStream input = ImageIO.createImageInputStream(inputFile)) {
            if (input == null) throw new IOException("Cannot open uploaded image");
            final java.util.Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Unsupported image format");
            final ImageReader reader = readers.next();
            try {
                // JPEG compression must not discard PNG transparency or GIF animation.
                if (!"JPEG".equalsIgnoreCase(reader.getFormatName())) return inputFile;
                reader.setInput(input);
                image = reader.read(0);
            } finally {
                reader.dispose();
            }
        }

        final File compressedFile = File.createTempFile("compressed-", ".jpg", inputFile.getAbsoluteFile().getParentFile());
        final ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        boolean completed = false;
        try {
            try (ImageOutputStream output = ImageIO.createImageOutputStream(compressedFile)) {
                if (output == null) throw new IOException("Cannot create compressed image");
                writer.setOutput(output);
                final ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
                writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
            }
            completed = compressedFile.length() < inputFile.length();
            return completed ? compressedFile : inputFile;
        } finally {
            writer.dispose();
            if (!completed) java.nio.file.Files.deleteIfExists(compressedFile.toPath());
        }
    }


    /**
     * Gets an image URL randomly. Sees https://github.com/b3log/bing for more details.
     * Generating a cover must not download or re-upload it during page rendering.
     *
     * @return an image URL
     */
    public static String randImage() {
        try {
            final long min = DateUtils.parseDate("20171104", new String[]{"yyyyMMdd"}).getTime();
            // The archive includes 2023-12-20; the random upper bound is exclusive.
            final long max = DateUtils.parseDate("20231221", new String[]{"yyyyMMdd"}).getTime();
            final long delta = max - min;
            final long time = ThreadLocalRandom.current().nextLong(0, delta) + min;

            return COMMUNITY_FILE_URL + "/bing/" + DateFormatUtils.format(time, "yyyyMMdd") + ".jpg";
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Generates random image URL failed", e);

            return COMMUNITY_FILE_URL + "/bing/20171104.jpg";
        }
    }

    /**
     * Gets image URLs randomly.
     *
     * @param n the specified size
     * @return image URLs
     */
    public static List<String> randomImages(final int n) {
        final List<String> ret = new ArrayList<>();

        int i = 0;
        while (i < n * 5) {
            final String url = randImage();
            if (!ret.contains(url)) {
                ret.add(url);
            }

            if (ret.size() >= n) {
                return ret;
            }

            i++;
        }

        return ret;
    }
}
