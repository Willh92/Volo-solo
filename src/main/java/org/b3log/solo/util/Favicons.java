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

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.file.Files;
import javax.imageio.ImageIO;
import org.b3log.latke.Latkes;
import org.b3log.solo.web.WebResources;

/** Bounded favicon conversion. The built-in vector mark is also rendered as PNG for PWA icons. */
public final class Favicons {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private Favicons() { }

    public static byte[] render(final String source, final int width, final int height, final WebResources resources) throws Exception {
        if (width < 16 || height < 16 || width > 512 || height > 512) throw new IllegalArgumentException("Invalid icon dimensions");
        BufferedImage image = null;
        if (source != null && !source.isBlank() && !source.endsWith("/images/volo-mark.svg")
                && !source.endsWith("/3e4a3ce8-8882-4258-9860-a337bf859605.png")) {
            try { image = read(source, resources); } catch (Exception ignored) { /* A failed remote icon cannot break the page. */ }
        }
        final BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final var graphics = output.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            if (image != null) graphics.drawImage(image, 0, 0, width, height, null);
            else {
                graphics.scale(width / 64.0, height / 64.0);
                graphics.setColor(new Color(0x246b51));
                graphics.fill(new RoundRectangle2D.Double(0, 0, 64, 64, 38, 38));
                final Path2D mark = new Path2D.Double();
                mark.moveTo(15, 18); mark.lineTo(27, 47); mark.lineTo(36, 47); mark.lineTo(49, 18);
                mark.lineTo(38, 18); mark.lineTo(32, 35); mark.lineTo(26, 18); mark.closePath();
                graphics.setColor(new Color(0xeef5ee)); graphics.fill(mark);
            }
        } finally { graphics.dispose(); }
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(output, "png", bytes);
        return bytes.toByteArray();
    }

    private static BufferedImage read(final String source, final WebResources resources) throws Exception {
        final URI uri = URI.create(source);
        final byte[] bytes;
        if (uri.getScheme() == null) {
            String path = uri.getPath();
            if (!Latkes.getContextPath().isEmpty() && path.startsWith(Latkes.getContextPath() + "/")) path = path.substring(Latkes.getContextPath().length());
            final var file = resources.resolve(path);
            if (!Files.isRegularFile(file) || !file.toRealPath().startsWith(resources.root().toRealPath()) || Files.size(file) > MAX_BYTES) return null;
            bytes = Files.readAllBytes(file);
        } else {
            if (!OutboundUrls.isHttpOrHttps(source)) return null;
            bytes = OutboundUrls.fetchBytes(source, 2000, 3000, MAX_BYTES, "image/*", Solos.USER_AGENT, false);
        }
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            final var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            final var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                if ((long) reader.getWidth(0) * reader.getHeight(0) > 16_777_216L) return null;
                return reader.read(0);
            } finally { reader.dispose(); }
        }
    }
}
