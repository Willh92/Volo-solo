/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.security;

import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;
import org.b3log.solo.service.CaptchaService;
import org.testng.Assert;
import org.testng.annotations.Test;

public class CaptchaServiceTest {
    @Test public void correctAnswerWorksOnceAndImageDoesNotExposeText() throws Exception {
        final var service = new CaptchaService(() -> "AB234", () -> 1000);
        final var challenge = service.issue("login", "198.51.100.1");
        Assert.assertEquals(challenge.expiresAt(), 181000L);
        Assert.assertTrue(challenge.image().startsWith("data:image/png;base64,"));
        final byte[] png = Base64.getDecoder().decode(challenge.image().split(",", 2)[1]);
        Assert.assertEquals(png[0] & 255, 137);
        Assert.assertEquals(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png)).getWidth(), 180);
        Assert.assertTrue(service.consume("login", "198.51.100.1", challenge.id(), "ab234"));
        Assert.assertFalse(service.consume("login", "198.51.100.1", challenge.id(), "AB234"));
    }
    @Test public void expiredWrongPurposeWrongClientAndWrongAnswerAreRejected() throws Exception {
        final var clock = new AtomicLong(1000); final var service = new CaptchaService(() -> "AB234", clock::get);
        var challenge = service.issue("login", "198.51.100.1");
        Assert.assertFalse(service.consume("comment", "198.51.100.1", challenge.id(), "AB234"));
        challenge = service.issue("comment", "198.51.100.1");
        Assert.assertFalse(service.consume("comment", "198.51.100.2", challenge.id(), "AB234"));
        challenge = service.issue("comment", "198.51.100.1");
        Assert.assertFalse(service.consume("comment", "198.51.100.1", challenge.id(), "12345"));
        Assert.assertFalse(service.consume("comment", "198.51.100.1", challenge.id(), "AB234"));
        challenge = service.issue("comment", "198.51.100.1"); clock.addAndGet(180000);
        Assert.assertFalse(service.consume("comment", "198.51.100.1", challenge.id(), "AB234"));
        Assert.assertFalse(service.consume("comment", "198.51.100.1", "", ""));
    }
    @Test public void repeatedAnswersHaveIndependentImagesAndChallenges() throws Exception {
        final var service = new CaptchaService(() -> "MW298", () -> 1000);
        final var images = new java.util.HashSet<String>();
        for (int i = 0; i < 8; i++) {
            final var challenge = service.issue("comment", "198.51.100.1");
            Assert.assertTrue(images.add(challenge.image()), "Rendering must vary even for the same answer");
            final var decoded = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(challenge.image().split(",", 2)[1])));
            Assert.assertEquals(decoded.getWidth(), 180);
            Assert.assertEquals(decoded.getHeight(), 64);
            for (int slot = 0; slot < 5; slot++) {
                int ink = 0;
                for (int y = 6; y < 58; y++) for (int x = 9 + slot * 31; x < 40 + slot * 31; x++) {
                    final var pixel = new java.awt.Color(decoded.getRGB(x, y));
                    if (pixel.getRed() < 100 && pixel.getGreen() < 100 && pixel.getBlue() < 100) ink++;
                }
                Assert.assertTrue(ink > 40, "Every character must remain inside the image");
            }
            Assert.assertTrue(service.consume("comment", "198.51.100.1", challenge.id(), "mw298"));
            Assert.assertFalse(service.consume("comment", "198.51.100.1", challenge.id(), "MW298"));
        }
    }
    @Test public void issuingChallengesIsBoundedPerRealClientAndPurposeIsRestricted() throws Exception {
        final var clock = new AtomicLong(1000); final var service = new CaptchaService(() -> "AB234", clock::get);
        for (int i = 0; i < 20; i++) service.issue("login", "198.51.100.1");
        Assert.expectThrows(IllegalStateException.class, () -> service.issue("comment", "198.51.100.1"));
        Assert.assertNotNull(service.issue("login", "198.51.100.2"));
        clock.addAndGet(60000); Assert.assertNotNull(service.issue("comment", "198.51.100.1"));
        Assert.expectThrows(IllegalArgumentException.class, () -> service.issue("unknown", "198.51.100.1"));
    }
}
