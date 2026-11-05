/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.service;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.http.Response;
import org.b3log.latke.http.renderer.AbstractResponseRenderer;
import org.b3log.latke.service.annotation.Service;
import org.b3log.solo.web.HttpCompression;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single-flight in-memory XML snapshot and HTTP conditional ETag / 304 cache for
 * {@code /sitemap.xml}, {@code /atom.xml}, and {@code /rss.xml}.
 *
 * @author <a href="https://github.com/Willh92">Willh92</a>
 * @since 3.0.0
 */
@Service
public class SyndicationCacheService {

    public static final String KEY_SITEMAP = "sitemap";
    public static final String KEY_ATOM = "atom";
    public static final String KEY_RSS = "rss";
    public static final String CACHE_CONTROL_VALUE = "public, max-age=300";

    private static final int MAX_STALE_KEYS = 32;
    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final DateTimeFormatter HTTP_DATE_GMT = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneOffset.UTC);

    private final ConcurrentHashMap<String, Snapshot> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Snapshot> staleCache = new ConcurrentHashMap<>();
    private final Object[] stripeLocks = createLocks(16);
    private final AtomicLong generation = new AtomicLong(0L);

    private static Object[] createLocks(final int count) {
        final Object[] arr = new Object[count];
        for (int i = 0; i < count; i++) {
            arr[i] = new Object();
        }
        return arr;
    }

    /**
     * Immutable cached syndication XML payload with strong ETag and RFC 1123 GMT timestamp.
     */
    public record Snapshot(String xml, String etag, String lastModifiedHttp) {

        public static Snapshot of(final String xml) {
            final String safeXml = xml == null ? "" : xml;
            final String etag = "\"" + DigestUtils.sha256Hex(safeXml).substring(0, 32) + "\"";
            final String lastModifiedHttp = HTTP_DATE_GMT.format(Instant.now());
            return new Snapshot(safeXml, etag, lastModifiedHttp);
        }
    }

    /**
     * Returns the cached {@link Snapshot} for {@code key}, or computes it under a per-key striped
     * single-flight lock via {@code builder}, falling back to the last good snapshot if {@code builder}
     * throws a transient exception.
     */
    public Snapshot getOrCompute(final String key, final Callable<String> builder) throws Exception {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(builder, "builder");

        final Snapshot fast = cache.get(key);
        if (fast != null) {
            return fast;
        }

        final Object lock = stripeLocks[(key.hashCode() & 0x7fffffff) % stripeLocks.length];
        synchronized (lock) {
            final Snapshot recheck = cache.get(key);
            if (recheck != null) {
                return recheck;
            }

            final long genBefore = generation.get();
            try {
                final String builtXml = builder.call();
                final Snapshot fresh = Snapshot.of(builtXml);
                if (staleCache.size() >= MAX_STALE_KEYS && !staleCache.containsKey(key)) {
                    staleCache.clear();
                }
                staleCache.put(key, fresh);

                if (generation.get() == genBefore) {
                    cache.put(key, fresh);
                    if (generation.get() != genBefore) {
                        cache.remove(key, fresh);
                    }
                }
                return fresh;
            } catch (final Exception e) {
                final Snapshot stale = staleCache.get(key);
                if (stale != null) {
                    return stale;
                }
                throw e;
            }
        }
    }

    /**
     * Evaluates the request {@code If-None-Match} header against {@code etag}, supporting
     * {@code *}, comma-separated entity-tag lists, optional {@code W/} weak prefixes, and RFC 7232
     * {@code -gzip} representation suffixes.
     */
    public static boolean matchesIfNoneMatch(final RequestContext context, final String etag) {
        if (context == null || context.getRequest() == null || StringUtils.isBlank(etag)) {
            return false;
        }
        final String header = context.getRequest().getHeader("If-None-Match");
        if (StringUtils.isBlank(header)) {
            return false;
        }
        final String normalizedTarget = normalizeEntityTag(etag.trim());
        if (normalizedTarget.isEmpty()) {
            return false;
        }
        for (final String rawToken : header.split(",")) {
            final String token = rawToken.trim();
            if (token.isEmpty()) {
                continue;
            }
            if ("*".equals(token)) {
                return true;
            }
            if (normalizedTarget.equals(normalizeEntityTag(token))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Applies standard syndication cache headers ({@code Vary: Accept-Encoding}, representation-aware
     * {@code ETag}, {@code Last-Modified}, {@code Cache-Control: public, max-age=300}) and short-circuits
     * with HTTP {@code 304 Not Modified} and an empty body renderer when {@code If-None-Match} matches.
     *
     * @return {@code true} if HTTP 304 was served; {@code false} if the caller should render the 200 body
     */
    public static boolean tryServeNotModified(final RequestContext context,
                                              final Snapshot snapshot,
                                              final String contentType) {
        if (context == null || context.getResponse() == null || snapshot == null) {
            return false;
        }
        final Response response = context.getResponse();
        if (StringUtils.isNotBlank(contentType)) {
            response.setContentType(contentType);
        }
        HttpCompression.ensureVaryAcceptEncoding(response);
        final boolean willGzip = HttpCompression.willCompress(context, snapshot.xml());
        final String responseEtag = willGzip ? withEncodingSuffix(snapshot.etag(), "-gzip") : snapshot.etag();
        response.setHeader("ETag", responseEtag);
        response.setHeader("Last-Modified", snapshot.lastModifiedHttp());
        response.setHeader("Cache-Control", CACHE_CONTROL_VALUE);

        if (matchesIfNoneMatch(context, snapshot.etag())) {
            response.setStatus(304);
            context.setRenderer(new AbstractResponseRenderer() {
                @Override
                public void render(final RequestContext ctx) {
                    if (ctx != null && ctx.getResponse() != null && !ctx.getResponse().isCommitted()) {
                        ctx.getResponse().setStatus(304);
                        ctx.getResponse().sendBytes(EMPTY_BYTES);
                    }
                }
            });
            response.sendBytes(EMPTY_BYTES);
            return true;
        }
        return false;
    }

    /**
     * Invalidates all cached syndication snapshots and advances generation.
     */
    public void invalidate() {
        generation.incrementAndGet();
        cache.clear();
    }

    public static String withEncodingSuffix(final String etag, final String suffix) {
        if (StringUtils.isBlank(etag) || StringUtils.isEmpty(suffix)) {
            return etag;
        }
        final String trimmed = etag.trim();
        if (trimmed.length() >= 2 && trimmed.endsWith("\"")) {
            if (trimmed.endsWith(suffix + "\"")) {
                return trimmed;
            }
            return trimmed.substring(0, trimmed.length() - 1) + suffix + "\"";
        }
        return trimmed.endsWith(suffix) ? trimmed : (trimmed + suffix);
    }

    private static String normalizeEntityTag(final String tag) {
        String s = tag;
        if (s.startsWith("W/") || s.startsWith("w/")) {
            s = s.substring(2).trim();
        }
        if (s.length() > 7 && s.startsWith("\"") && s.endsWith("-gzip\"")) {
            return s.substring(0, s.length() - 6) + "\"";
        }
        if (s.length() > 5 && !s.startsWith("\"") && s.endsWith("-gzip")) {
            return s.substring(0, s.length() - 5);
        }
        return s;
    }
}
