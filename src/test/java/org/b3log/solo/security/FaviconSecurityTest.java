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

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import javax.imageio.ImageIO;
import org.b3log.solo.util.Favicons;
import org.b3log.solo.web.WebResources;
import org.testng.Assert;
import org.testng.annotations.Test;

public class FaviconSecurityTest {
    @Test public void builtinVectorHasValidPngSizesForPwaClients() throws Exception {
        final var icon = ImageIO.read(new ByteArrayInputStream(Favicons.render("/images/volo-mark.svg", 192, 192, null)));
        Assert.assertEquals(icon.getWidth(), 192);
        Assert.assertEquals(icon.getHeight(), 192);
        Assert.assertEquals(icon.getRGB(96, 20) & 0xffffff, 0x246b51);
    }
    @Test(expectedExceptions = IllegalArgumentException.class)
    public void oversizedRequestsCannotAllocateAnUnboundedImage() throws Exception { Favicons.render(null, Integer.MAX_VALUE, 192, null); }

    @Test public void invalidLocalImageFallsBackWithoutExposingTheFile() throws Exception {
        LatkeTestSupport.configure();
        final var root = Files.createTempDirectory("volo-icon-test-");
        try {
            Files.writeString(root.resolve("private.txt"), "not-an-image-secret");
            final byte[] icon = Favicons.render("private.txt", 48, 48, new WebResources(root));
            Assert.assertNotNull(ImageIO.read(new ByteArrayInputStream(icon)));
            Assert.assertFalse(new String(icon, java.nio.charset.StandardCharsets.ISO_8859_1).contains("not-an-image-secret"));
        } finally { Files.deleteIfExists(root.resolve("private.txt")); Files.deleteIfExists(root); }
    }
}
