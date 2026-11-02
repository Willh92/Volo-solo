/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.service;

import java.awt.Color;
import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.geom.CubicCurve2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.service.annotation.Service;
import org.b3log.solo.util.IpAddresses;

/** Expiring single-use image challenges, bound to purpose and real client address. */
@Service
public class CaptchaService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final long TTL = 180_000;
    private record Entry(String purpose, String address, byte[] digest, long expires) { }
    private record Rate(long start, int count) { }
    public record Challenge(String id, String image, long expiresAt) { }
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private final LinkedHashMap<String, Rate> rates = new LinkedHashMap<>();
    private final Supplier<String> answers;
    private final LongSupplier clock;
    private volatile Boolean comments;
    @Inject private OptionQueryService optionQueryService;
    public CaptchaService() { this(CaptchaService::answer, System::currentTimeMillis); }
    public CaptchaService(final Supplier<String> answers, final LongSupplier clock) { this.answers = answers; this.clock = clock; }
    public boolean commentsRequired() {
        if (comments != null) return comments;
        final var option = optionQueryService.getOptionById("commentCaptchaEnabled");
        return comments = option == null || !"off".equals(option.optString(org.b3log.solo.model.Option.OPTION_VALUE));
    }
    public void configureComments(final boolean enabled) { comments = enabled; }
    private static String answer() {
        final StringBuilder value = new StringBuilder();
        for (int i = 0; i < 5; i++) value.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return value.toString();
    }
    private static byte[] digest(final String id, final String answer) {
        try { return MessageDigest.getInstance("SHA-256").digest((id + ":" + answer.strip().toUpperCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public synchronized Challenge issue(final String purpose, final String address) throws Exception {
        if (!Set.of("login", "comment").contains(purpose)) throw new IllegalArgumentException("验证码用途不正确。");
        final String ip = IpAddresses.canonical(address);
        if (ip.isEmpty()) throw new IllegalArgumentException("无法识别验证码请求来源。");
        final long now = clock.getAsLong();
        final Rate rate = rates.get(ip);
        final int count = rate == null || now - rate.start >= 60_000 ? 1 : rate.count + 1;
        if (count > 20) throw new IllegalStateException("验证码刷新过于频繁，请稍后再试。");
        rates.put(ip, new Rate(count == 1 ? now : rate.start, count));
        while (rates.size() > 10_000) rates.remove(rates.keySet().iterator().next());
        entries.values().removeIf(value -> value.expires <= now);
        while (entries.size() >= 4096) entries.remove(entries.keySet().iterator().next());
        final byte[] nonce = new byte[24]; RANDOM.nextBytes(nonce);
        final String id = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce), answer = answers.get();
        final String image = image(answer);
        entries.put(id, new Entry(purpose, ip, digest(id, answer), now + TTL));
        return new Challenge(id, image, now + TTL);
    }
    public synchronized boolean consume(final String purpose, final String address, final String id, final String answer) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{32}") || answer == null || !answer.strip().matches("[A-Za-z0-9]{5}")) return false;
        final Entry value = entries.remove(id);
        return value != null && value.expires > clock.getAsLong() && value.purpose.equals(purpose)
                && value.address.equals(IpAddresses.canonical(address)) && MessageDigest.isEqual(value.digest, digest(id, answer));
    }
    private static String image(final String answer) throws Exception {
        final BufferedImage image = new BufferedImage(180, 64, BufferedImage.TYPE_INT_RGB);
        final var graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(237, 243, 239)); graphics.fillRect(0, 0, 180, 64);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            curves(graphics, 4, new Color(167, 185, 173, 150));
            final BufferedImage letters = new BufferedImage(180, 64, BufferedImage.TYPE_INT_ARGB);
            final var glyphs = letters.createGraphics();
            try {
                glyphs.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                final String[] fonts = {Font.SANS_SERIF, Font.SERIF, Font.MONOSPACED};
                for (int i = 0; i < answer.length(); i++) {
                    final var transform = glyphs.getTransform();
                    glyphs.setFont(new Font(fonts[RANDOM.nextInt(fonts.length)], Font.BOLD, 32 + RANDOM.nextInt(4)));
                    glyphs.translate(25 + i * 31 + RANDOM.nextInt(5) - 2, 33 + RANDOM.nextInt(7) - 3);
                    glyphs.rotate((RANDOM.nextDouble() - .5) * .56);
                    glyphs.shear((RANDOM.nextDouble() - .5) * .32, 0);
                    glyphs.scale(.92 + RANDOM.nextDouble() * .16, .94 + RANDOM.nextDouble() * .12);
                    glyphs.setColor(new Color(30 + RANDOM.nextInt(35), 50 + RANDOM.nextInt(35), 43 + RANDOM.nextInt(35)));
                    final String letter = answer.substring(i, i + 1);
                    glyphs.drawString(letter, -glyphs.getFontMetrics().stringWidth(letter) / 2f, 12);
                    glyphs.setTransform(transform);
                }
            } finally { glyphs.dispose(); }
            final double phaseX = RANDOM.nextDouble() * Math.PI * 2, phaseY = RANDOM.nextDouble() * Math.PI * 2;
            final BufferedImage warped = new BufferedImage(180, 64, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 180; x++) {
                    final int sourceX = x + (int) Math.round(2.8 * Math.sin(y / 11.0 + phaseX));
                    final int sourceY = y + (int) Math.round(2.2 * Math.sin(x / 17.0 + phaseY));
                    if (sourceX >= 0 && sourceX < 180 && sourceY >= 0 && sourceY < 64) warped.setRGB(x, y, letters.getRGB(sourceX, sourceY));
                }
            }
            graphics.drawImage(warped, 0, 0, null);
            curves(graphics, 2, new Color(96, 125, 106, 175));
            for (int i = 0; i < 90; i++) {
                graphics.setColor(new Color(80 + RANDOM.nextInt(80), 110 + RANDOM.nextInt(65), 90 + RANDOM.nextInt(75), 150));
                graphics.fillOval(RANDOM.nextInt(180), RANDOM.nextInt(64), 1 + RANDOM.nextInt(2), 1 + RANDOM.nextInt(2));
            }
        } finally { graphics.dispose(); }
        final ByteArrayOutputStream output = new ByteArrayOutputStream(); ImageIO.write(image, "png", output);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
    }
    private static void curves(final java.awt.Graphics2D graphics, final int count, final Color color) {
        graphics.setColor(color);
        graphics.setStroke(new BasicStroke(1.1f));
        for (int i = 0; i < count; i++) graphics.draw(new CubicCurve2D.Double(0, 12 + RANDOM.nextInt(40),
                45, RANDOM.nextInt(64), 120, RANDOM.nextInt(64), 180, 12 + RANDOM.nextInt(40)));
    }
}
