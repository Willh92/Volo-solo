/* Volo, derived from Bolo. Copyright (c) 2020-present, bolo-blog. AGPL-3.0-or-later. */
package org.b3log.solo.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import org.b3log.latke.Latkes;

/** Atomic anonymous-comment budgets. State is bounded and active limits are never evicted. */
public final class CommentThrottle {
    private static final int CAPACITY = 10_000;
    private final Map<String, Bucket> sources = new LinkedHashMap<>();
    private final Map<String, Bucket> articles = new LinkedHashMap<>();
    private final Map<String, Long> duplicates = new LinkedHashMap<>();
    private final LongSupplier clock;
    private final Limits limits;
    private Bucket global;

    public record Limits(int intervalSeconds, int windowSeconds, int perSource, int perArticle, int siteWide) {
        public Limits {
            if (intervalSeconds < 1 || windowSeconds < intervalSeconds || perSource < 1 || perArticle < 1 || siteWide < 1)
                throw new IllegalArgumentException("Invalid comment limits");
        }
        public static Limits configured() {
            final int window = setting("commentRateWindowSeconds", 600, 60, 86_400);
            return new Limits(setting("commentMinIntervalSeconds", 15, 1, window), window,
                    setting("commentRateMaxPerIp", 5, 1, 1000), setting("commentRateMaxPerArticle", 30, 1, 10_000),
                    setting("commentRateMaxGlobal", 100, 1, 100_000));
        }
        private static int setting(final String key, final int fallback, final int min, final int max) {
            try { final int value = Integer.parseInt(Latkes.getLocalProperty(key)); return value >= min && value <= max ? value : fallback; }
            catch (RuntimeException ignored) { return fallback; }
        }
    }
    private static final class Bucket {
        private final long expires;
        private int attempts;
        private long last;
        private Bucket(final long expires) { this.expires = expires; }
    }
    public record Attempt(int retryAfter, String message, String reservation, long expires) { public boolean allowed() { return retryAfter == 0; } }

    public CommentThrottle() { this(System::nanoTime, Limits.configured()); }
    public CommentThrottle(final LongSupplier clock, final Limits limits) { this.clock = clock; this.limits = limits; }

    public synchronized Attempt acquire(final String address, final String article, final String content) {
        final long now = clock.getAsLong(), window = Duration.ofSeconds(limits.windowSeconds()).toNanos();
        prune(sources, now); prune(articles, now);
        final var iterator = duplicates.values().iterator();
        while (iterator.hasNext()) { if (iterator.next() > now) break; iterator.remove(); }
        if (global == null || global.expires <= now) global = new Bucket(now + window);
        final String source = LoginThrottle.sourceKey(address);
        final String fingerprint = source + ':' + digest(content);
        final Long duplicate = duplicates.get(fingerprint);
        if (duplicate != null) return denied(duplicate - now, "这条内容刚刚已经提交，请勿重复留言。");
        final Bucket ip = sources.get(source), post = articles.get(article);
        long until = now;
        if (ip != null && ip.attempts > 0) until = Math.max(until, ip.last + Duration.ofSeconds(limits.intervalSeconds()).toNanos());
        if (ip != null && ip.attempts >= limits.perSource()) until = Math.max(until, ip.expires);
        if (post != null && post.attempts >= limits.perArticle()) until = Math.max(until, post.expires);
        if (global.attempts >= limits.siteWide()) until = Math.max(until, global.expires);
        if ((ip == null && sources.size() >= CAPACITY) || (post == null && articles.size() >= CAPACITY) || duplicates.size() >= CAPACITY)
            until = Math.max(until, now + Duration.ofMinutes(1).toNanos());
        if (until > now) return denied(until - now, "留言过于频繁，请稍后再试。");
        final Bucket sourceBucket = sources.computeIfAbsent(source, ignored -> new Bucket(now + window));
        sourceBucket.attempts++; sourceBucket.last = now;
        articles.computeIfAbsent(article, ignored -> new Bucket(now + window)).attempts++;
        global.attempts++;
        // Reserve before persistence, so concurrent duplicate requests cannot both pass.
        duplicates.put(fingerprint, now + window);
        return new Attempt(0, "", fingerprint, now + window);
    }

    /** Failed validation/persistence may be retried; admission budgets still limit its cost. */
    public synchronized void failed(final Attempt attempt) {
        if (attempt != null && attempt.allowed()) duplicates.remove(attempt.reservation(), attempt.expires());
    }

    private static Attempt denied(final long nanos, final String message) {
        return new Attempt((int) Math.max(1, (nanos + 999_999_999L) / 1_000_000_000L), message, null, 0);
    }
    private static void prune(final Map<String, Bucket> buckets, final long now) {
        final var iterator = buckets.values().iterator();
        while (iterator.hasNext()) { if (iterator.next().expires > now) break; iterator.remove(); }
    }
    private static String digest(final String content) {
        final String normalized = Normalizer.normalize(content, Normalizer.Form.NFKC).replaceAll("[\\p{Cf}\\s\\p{Z}]+", "").trim();
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
