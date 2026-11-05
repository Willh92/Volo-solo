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
    private static final java.util.concurrent.Semaphore RENDER_PERMITS =
            new java.util.concurrent.Semaphore(Math.max(2, Runtime.getRuntime().availableProcessors()));
    private static final Color BG_COLOR = new Color(237, 243, 239);
    private static final Color BACK_CURVE_COLOR = new Color(167, 185, 173, 150);
    private static final Color FRONT_CURVE_COLOR = new Color(96, 125, 106, 175);
    private static final BasicStroke CURVE_STROKE = new BasicStroke(1.1f);
    private static final Font[][] GLYPH_FONTS = initGlyphFonts();

    private static Font[][] initGlyphFonts() {
        final String[] families = {Font.SANS_SERIF, Font.SERIF, Font.MONOSPACED};
        final Font[][] matrix = new Font[families.length][4];
        for (int f = 0; f < families.length; f++) {
            for (int s = 0; s < 4; s++) {
                matrix[f][s] = new Font(families[f], Font.BOLD, 32 + s);
            }
        }
        return matrix;
    }
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
        return comments = option == null || !"false".equalsIgnoreCase(option.optString(org.b3log.solo.model.Option.OPTION_VALUE));
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
    public Challenge issue(final String purpose, final String address) throws Exception {
        if (!Set.of("login", "comment", "register").contains(purpose)) throw new IllegalArgumentException("验证码用途不正确。");
        final String ip = IpAddresses.canonical(address);
        if (ip.isEmpty()) throw new IllegalArgumentException("无法识别验证码请求来源。");
        final String id;
        final String answer;
        final long expiresAt;
        synchronized (this) {
            final long now = clock.getAsLong();
            final Rate rate = rates.get(ip);
            final int count = rate == null || now - rate.start >= 60_000 ? 1 : rate.count + 1;
            if (count > 20) throw new IllegalStateException("验证码刷新过于频繁，请稍后再试。");
            rates.put(ip, new Rate(count == 1 ? now : rate.start, count));
            while (rates.size() > 10_000) rates.remove(rates.keySet().iterator().next());
            entries.values().removeIf(value -> value.expires <= now);
            while (entries.size() >= 4096) entries.remove(entries.keySet().iterator().next());
            final byte[] nonce = new byte[24]; RANDOM.nextBytes(nonce);
            id = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
            answer = answers.get();
            expiresAt = now + TTL;
            entries.put(id, new Entry(purpose, ip, digest(id, answer), expiresAt));
        }
        try {
            return new Challenge(id, image(answer), expiresAt);
        } catch (final Exception renderFailure) {
            synchronized (this) { entries.remove(id); }
            throw renderFailure;
        }
    }
    public synchronized boolean consume(final String purpose, final String address, final String id, final String answer) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{32}") || answer == null || !answer.strip().matches("[A-Za-z0-9]{5}")) return false;
        final Entry value = entries.remove(id);
        return value != null && value.expires > clock.getAsLong() && value.purpose.equals(purpose)
                && value.address.equals(IpAddresses.canonical(address)) && MessageDigest.isEqual(value.digest, digest(id, answer));
    }
    private static String image(final String answer) throws Exception {
        if (!RENDER_PERMITS.tryAcquire(2, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new IllegalStateException("验证码服务繁忙，请稍后重试。");
        }
        try {
            final BufferedImage image = new BufferedImage(180, 64, BufferedImage.TYPE_INT_RGB);
            final var graphics = image.createGraphics();
            try {
                graphics.setColor(BG_COLOR); graphics.fillRect(0, 0, 180, 64);
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                curves(graphics, 4, BACK_CURVE_COLOR);
                final BufferedImage letters = new BufferedImage(180, 64, BufferedImage.TYPE_INT_ARGB);
                final var glyphs = letters.createGraphics();
                try {
                    glyphs.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    for (int i = 0; i < answer.length(); i++) {
                        final var transform = glyphs.getTransform();
                        glyphs.setFont(GLYPH_FONTS[RANDOM.nextInt(GLYPH_FONTS.length)][RANDOM.nextInt(4)]);
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
                final var jitter = java.util.concurrent.ThreadLocalRandom.current();
                final double phaseX = jitter.nextDouble() * Math.PI * 2, phaseY = jitter.nextDouble() * Math.PI * 2;
                final int[] dx = new int[64];
                for (int y = 0; y < 64; y++) dx[y] = (int) Math.round(2.8 * Math.sin(y / 11.0 + phaseX));
                final int[] dy = new int[180];
                for (int x = 0; x < 180; x++) dy[x] = (int) Math.round(2.2 * Math.sin(x / 17.0 + phaseY));
                final int[] srcPixels = letters.getRGB(0, 0, 180, 64, null, 0, 180);
                final int[] dstPixels = new int[180 * 64];
                for (int y = 0; y < 64; y++) {
                    final int shiftX = dx[y], rowOffset = y * 180;
                    for (int x = 0; x < 180; x++) {
                        final int sourceX = x + shiftX, sourceY = y + dy[x];
                        if (sourceX >= 0 && sourceX < 180 && sourceY >= 0 && sourceY < 64) {
                            dstPixels[rowOffset + x] = srcPixels[sourceY * 180 + sourceX];
                        }
                    }
                }
                letters.setRGB(0, 0, 180, 64, dstPixels, 0, 180);
                graphics.drawImage(letters, 0, 0, null);
                curves(graphics, 2, FRONT_CURVE_COLOR);
                for (int i = 0; i < 90; i++) {
                    graphics.setColor(new Color(80 + RANDOM.nextInt(80), 110 + RANDOM.nextInt(65), 90 + RANDOM.nextInt(75), 150));
                    graphics.fillOval(RANDOM.nextInt(180), RANDOM.nextInt(64), 1 + RANDOM.nextInt(2), 1 + RANDOM.nextInt(2));
                }
            } finally { graphics.dispose(); }
            final ByteArrayOutputStream output = new ByteArrayOutputStream(8192); ImageIO.write(image, "png", output);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
        } finally {
            RENDER_PERMITS.release();
        }
    }
    private static void curves(final java.awt.Graphics2D graphics, final int count, final Color color) {
        graphics.setColor(color);
        graphics.setStroke(CURVE_STROKE);
        for (int i = 0; i < count; i++) graphics.draw(new CubicCurve2D.Double(0, 12 + RANDOM.nextInt(40),
                45, RANDOM.nextInt(64), 120, RANDOM.nextInt(64), 180, 12 + RANDOM.nextInt(40)));
    }
}
