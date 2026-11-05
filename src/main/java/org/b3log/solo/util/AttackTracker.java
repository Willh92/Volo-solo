/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.util;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/** Bounded, short-lived counters. Only literal public client addresses enter this tracker. */
public final class AttackTracker {
    public record Trigger(String code, String reason) { }
    private static final long WINDOW = 120_000;
    private static final int TOTAL_CAPACITY = 10_000;
    private static final int SHARDS = 16;
    private static final int SHARD_CAPACITY = TOTAL_CAPACITY / SHARDS;
    private static final int MAX_PROBE_PATHS = 16;
    private static final int MAX_MISSING_PATHS = 32;
    private static final java.util.regex.Pattern SENSITIVE_PATH_PATTERN = java.util.regex.Pattern.compile(
            ".*(?:^|/)(?:\\.env(?:[./].*)?|\\.git(?:/.*)?|web-inf(?:/.*)?|meta-inf(?:/.*)?|etc/passwd|actuator/(?:env|heapdump)|wp-config\\.php|vendor/phpunit/.*|phpmyadmin(?:/.*)?)$");
    private static final java.util.regex.Pattern SCRIPT_PATH_PATTERN = java.util.regex.Pattern.compile(
            ".*\\.(?:php[0-9]?|asp|aspx|jsp|cgi)(?:/.*)?$");
    private static final java.util.regex.Pattern ASSET_PATH_PATTERN = java.util.regex.Pattern.compile(
            ".*\\.(?:css|js|map|png|jpg|jpeg|webp|gif|svg|ico|woff2?|ttf|otf)$");

    private final LongSupplier clock;
    private final Shard[] shards = new Shard[SHARDS];

    private static final class Shard extends LinkedHashMap<String, Window> {
        Shard() {
            super(64, .75f, true);
        }

        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, Window> eldest) {
            return size() > SHARD_CAPACITY;
        }
    }

    private static final class Window {
        final long start;
        int requests, failures, missing, probes;
        long[] paths;
        int pathCount;
        long[] probePaths;
        int probePathCount;

        Window(final long start) {
            this.start = start;
        }

        void addProbePath(final long fp) {
            for (int i = 0; i < probePathCount; i++) {
                if (probePaths[i] == fp) return;
            }
            if (probePathCount >= MAX_PROBE_PATHS) return;
            if (probePaths == null) {
                probePaths = new long[4];
            } else if (probePathCount == probePaths.length) {
                probePaths = Arrays.copyOf(probePaths, Math.min(MAX_PROBE_PATHS, probePaths.length << 1));
            }
            probePaths[probePathCount++] = fp;
        }

        void addMissingPath(final long fp) {
            for (int i = 0; i < pathCount; i++) {
                if (paths[i] == fp) return;
            }
            if (pathCount >= MAX_MISSING_PATHS) return;
            if (paths == null) {
                paths = new long[4];
            } else if (pathCount == paths.length) {
                paths = Arrays.copyOf(paths, Math.min(MAX_MISSING_PATHS, paths.length << 1));
            }
            paths[pathCount++] = fp;
        }
    }

    public AttackTracker() {
        this(System::currentTimeMillis);
    }

    public AttackTracker(final LongSupplier clock) {
        this.clock = clock;
        for (int i = 0; i < SHARDS; i++) {
            shards[i] = new Shard();
        }
    }

    private Shard shardFor(final String address) {
        final int h = address == null ? 0 : address.hashCode();
        return shards[(h ^ (h >>> 16)) & (SHARDS - 1)];
    }

    /** Strong 64-bit FNV-1a + SplitMix64 fingerprint over normalized UTF-16 path characters (zero String retention). */
    private static long fingerprint(final String s) {
        long h = 0xcbf29ce484222325L;
        final int len = s.length();
        for (int i = 0; i < len; i++) {
            final char c = s.charAt(i);
            h ^= (c & 0xff);
            h *= 0x100000001b3L;
            h ^= (c >>> 8);
            h *= 0x100000001b3L;
        }
        h ^= len;
        h = (h ^ (h >>> 30)) * 0xbf58476d1ce4e5b9L;
        h = (h ^ (h >>> 27)) * 0x94d049bb133111ebL;
        return h ^ (h >>> 31);
    }

    /** Includes loopback, private, link-local, multicast, unspecified and carrier NAT ranges. */
    public static boolean internal(final String address) {
        final byte[] b = IpAddresses.bytes(address);
        if (b == null) return true;
        final int a = b[0] & 255, c = b[1] & 255;
        if (b.length == 4) return a == 0 || a == 10 || a == 127 || a >= 224
                || a == 169 && c == 254 || a == 172 && c >= 16 && c <= 31
                || a == 192 && c == 168 || a == 100 && c >= 64 && c <= 127;
        boolean loopback = true;
        for (int i = 0; i < 15; i++) loopback &= b[i] == 0;
        return loopback && (b[15] == 0 || b[15] == 1) || (a & 254) == 252
                || a == 254 && (c & 192) == 128 || a == 255;
    }

    public Trigger record(final String address, final String input, final int status, final boolean administrator) {
        if (administrator || internal(address)) return null;
        final int queryIdx = input == null ? -1 : input.indexOf('?');
        String path = input == null ? "/" : (queryIdx < 0 ? input : input.substring(0, queryIdx));
        if (path.length() > 4096) path = path.substring(0, 4096);
        for (int i = 0; i < 2 && path.indexOf('%') >= 0; i++) {
            try { path = URLDecoder.decode(path.indexOf('+') >= 0 ? path.replace("+", "%2B") : path, StandardCharsets.UTF_8); }
            catch (IllegalArgumentException malformed) { break; }
        }
        path = path.toLowerCase(Locale.ROOT);
        if (path.indexOf('\\') >= 0) path = path.replace('\\', '/');
        if (path.startsWith("/error/")) return null;
        final boolean traversal = path.contains("../") || path.indexOf('\0') >= 0;
        final boolean sensitive = SENSITIVE_PATH_PATTERN.matcher(path).matches();
        final boolean script = SCRIPT_PATH_PATTERN.matcher(path).matches() || path.startsWith("/wp-admin/") || path.startsWith("/wp-includes/");
        final boolean asset = ASSET_PATH_PATTERN.matcher(path).matches();
        // Assets, repeated one-off broken links and successful requests do not count as failed probes.
        if (asset && !traversal && !sensitive && !script) return null;
        final boolean needsFingerprint = (status == 0 && !traversal && (sensitive || script)) || status == 404;
        final long pathFp = needsFingerprint ? fingerprint(path) : 0L;
        final long now = clock.getAsLong();
        final Shard shard = shardFor(address);
        synchronized (shard) {
            Window state = shard.get(address);
            if (state == null || now - state.start >= WINDOW) {
                state = new Window(now);
                shard.put(address, state);
            }
            if (status == 0) {
                state.requests++;
                if (traversal) return new Trigger("path_traversal", "检测到路径遍历或空字节探测");
                if (sensitive || script) {
                    state.addProbePath(pathFp);
                    state.probes++;
                    if (state.probePathCount >= 3 || state.probes >= 8)
                        return new Trigger(sensitive ? "sensitive_probe" : "script_scan", "2 分钟内多次探测敏感文件或非本站脚本路径（" + state.probes + " 次，" + state.probePathCount + " 个路径）");
                }
                if (state.requests >= 480) return new Trigger("request_flood", "2 分钟内至少 480 次非静态资源请求");
            } else if (status == 404) {
                state.missing++;
                state.addMissingPath(pathFp);
                if (state.pathCount >= 20 || state.missing >= 60)
                    return new Trigger("not_found_scan", "2 分钟内连续 404（" + state.missing + " 次，" + state.pathCount + " 个路径）");
            } else if (status == 400 || status == 401 || status == 403 || status == 405 || status == 429) {
                if (++state.failures >= 40) return new Trigger("rejected_flood", "2 分钟内至少 40 次无效、未授权或超限请求");
            }
            return null;
        }
    }

    public void clear(final String address) {
        if (address == null) return;
        final Shard shard = shardFor(address);
        synchronized (shard) {
            shard.remove(address);
        }
    }
}
