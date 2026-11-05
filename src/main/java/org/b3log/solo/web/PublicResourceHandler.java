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
package org.b3log.solo.web;

import org.apache.commons.lang3.StringUtils;
import org.b3log.latke.Latkes;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.http.function.Handler;
import org.b3log.solo.SoloApplication;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Public static files only; application configuration and template sources stay private. */
public final class PublicResourceHandler implements Handler {
    private static final Set<String> EXTENSIONS = Set.of("css","js","png","jpg","jpeg","gif","svg","ico","webp","avif","woff","woff2","ttf","eot","otf","json","mp3","mp4","webm","ogg","moc","mtn","txt");
    private static final List<String> PUBLIC_PREFIXES = List.of("/scss/", "/js/", "/skins/", "/plugins/", "/images/");
    private static final Set<String> PUBLIC_EXACT_PATHS = Set.of("/favicon.ico", "/robots.txt", "/sw.js", "/service-worker.js");
    private static final Set<String> PUBLIC_ASSET_EXACT_PATHS = Set.of("/favicon.ico", "/sw.js", "/service-worker.js");
    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "HEAD");
    private static final Set<String> NO_CACHE_PATHS = Set.of("/sw.js", "/service-worker.js", "/robots.txt");
    private static final Set<String> GZIPPABLE_EXTENSIONS = Set.of("js", "css", "svg", "json");
    private static final byte[] EMPTY_BYTES = new byte[0];

    /** Maximum uncompressed file size eligible for the in-memory hot static asset cache (128 KiB). */
    public static final int MAX_CACHEABLE_FILE_BYTES = 128 * 1024;
    /** Total memory ceiling across all cached raw + gzip static asset representations (12 MiB). */
    public static final int MAX_TOTAL_CACHE_BYTES = 12 * 1024 * 1024;
    private static final long PROD_STAT_VERIFY_MS = 5_000L;

    /** Open-ended {@code Range: bytes=start-} chunk cap (4 MiB) so media streaming stays heap-bounded. */
    public static final long OPEN_RANGE_CHUNK_BYTES = 4L * 1024L * 1024L;
    /** Explicit single-range {@code Range: bytes=start-end} upper cap (16 MiB). */
    private static final long MAX_EXPLICIT_RANGE_BYTES = 16L * 1024L * 1024L;

    private static final LinkedHashMap<String, CachedAsset> ASSET_CACHE = new LinkedHashMap<>(256, 0.75f, true);
    private static long currentCacheBytes;

    private static final class CachedAsset {
        final Path root;
        final Path realFile;
        final byte[] rawBytes;
        final byte[] gzBytes;
        final String contentType;
        final String rawEtag;
        final String gzEtag;
        final String lastModifiedHeader;
        final long modifiedMillis;
        final long rawSize;
        final int weightBytes;
        volatile long verifiedAtMillis;

        CachedAsset(final Path root,
                    final Path realFile,
                    final byte[] rawBytes,
                    final byte[] gzBytes,
                    final String contentType,
                    final String rawEtag,
                    final String gzEtag,
                    final String lastModifiedHeader,
                    final long modifiedMillis,
                    final long rawSize,
                    final long verifiedAtMillis) {
            this.root = root;
            this.realFile = realFile;
            this.rawBytes = rawBytes;
            this.gzBytes = gzBytes;
            this.contentType = contentType;
            this.rawEtag = rawEtag;
            this.gzEtag = gzEtag;
            this.lastModifiedHeader = lastModifiedHeader;
            this.modifiedMillis = modifiedMillis;
            this.rawSize = rawSize;
            this.weightBytes = rawBytes.length + (gzBytes != null ? gzBytes.length : 0);
            this.verifiedAtMillis = verifiedAtMillis;
        }
    }

    /** Clears the in-memory hot static asset cache. */
    public static void clearCache() {
        synchronized (ASSET_CACHE) {
            ASSET_CACHE.clear();
            currentCacheBytes = 0L;
        }
    }

    public static boolean isPublicAssetUri(final String rawUri) {
        if (rawUri == null || rawUri.isEmpty()) return false;
        String uri = rawUri;
        final int q = uri.indexOf('?');
        if (q >= 0) uri = uri.substring(0, q);
        final String ctx = Latkes.getContextPath();
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) uri = uri.substring(ctx.length());
        if (PUBLIC_ASSET_EXACT_PATHS.contains(uri)) return true;
        boolean prefixMatched = false;
        for (int i = 0; i < PUBLIC_PREFIXES.size(); i++) {
            if (uri.startsWith(PUBLIC_PREFIXES.get(i))) { prefixMatched = true; break; }
        }
        if (!prefixMatched) return false;
        final int dot = uri.lastIndexOf('.');
        return dot >= 0 && EXTENSIONS.contains(uri.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String contentTypeForExtension(final String extension, final Path file) throws IOException {
        return switch (extension) {
            case "js" -> "application/javascript";
            case "css" -> "text/css";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "avif" -> "image/avif";
            case "ico" -> "image/x-icon";
            case "woff2" -> "font/woff2";
            case "woff" -> "font/woff";
            case "ttf" -> "font/ttf";
            case "otf" -> "font/otf";
            case "eot" -> "application/vnd.ms-fontobject";
            case "json" -> "application/json";
            case "txt" -> "text/plain; charset=UTF-8";
            case "ogg" -> "audio/ogg";
            case "mp3" -> "audio/mpeg";
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            default -> Files.probeContentType(file);
        };
    }

    @Override public void handle(final RequestContext context) {
        try {
            String uri = context.requestURI();
            final String ctxPath = Latkes.getContextPath();
            if (!ctxPath.isEmpty() && uri.startsWith(ctxPath)) {
                uri = uri.substring(ctxPath.length());
            }
            if (!isCleanSingleSlashAsciiPath(uri)) {
                uri = java.net.URI.create(uri).getPath();
            }
            if (uri == null || uri.isEmpty()) {
                context.sendError(404); context.abort(); return;
            }
            final String lower = uri.toLowerCase(Locale.ROOT);
            if (uri.indexOf('\\') >= 0 || lower.contains("/web-inf") || lower.contains("/meta-inf")
                    || lower.contains("/.git") || lower.contains("/.idea")) {
                context.sendError(404); context.abort(); return;
            }
            boolean publicPath = PUBLIC_EXACT_PATHS.contains(uri);
            if (!publicPath) {
                for (int i = 0; i < PUBLIC_PREFIXES.size(); i++) {
                    if (uri.startsWith(PUBLIC_PREFIXES.get(i))) { publicPath = true; break; }
                }
            }
            final int dot = uri.lastIndexOf('.');
            final String extension = dot >= 0 ? lower.substring(dot + 1) : "";
            if (!publicPath || !EXTENSIONS.contains(extension)) { context.handle(); return; }
            if (!ALLOWED_METHODS.contains(context.method())) { context.sendError(405); context.abort(); return; }

            final WebResources resources = SoloApplication.getWebResources();
            final CachedAsset hotAsset = lookupValidCachedAsset(resources, uri);
            if (hotAsset != null) {
                serveCachedAsset(context, uri, hotAsset);
                return;
            }

            final Path realRoot = resources.realRoot();
            final Path file = resources.resolve(uri);
            final BasicFileAttributes fileAttrs;
            try {
                fileAttrs = Files.readAttributes(file, BasicFileAttributes.class);
            } catch (final IOException missing) {
                context.sendError(404); context.abort(); return;
            }
            if (!fileAttrs.isRegularFile()) { context.sendError(404); context.abort(); return; }
            final Path realFile = file.toRealPath();
            if (!realFile.startsWith(realRoot)) { context.sendError(404); context.abort(); return; }

            final long modified = fileAttrs.lastModifiedTime().toMillis();
            final long fileSize = fileAttrs.size();
            final String resolvedType = contentTypeForExtension(extension, realFile);
            final String contentType = resolvedType == null ? "application/octet-stream" : resolvedType;
            final String lastModifiedHeader = DateTimeFormatter.RFC_1123_DATE_TIME
                    .format(Instant.ofEpochMilli(modified).atZone(ZoneOffset.UTC));

            if (fileSize <= MAX_CACHEABLE_FILE_BYTES) {
                final byte[] rawBytes = Files.readAllBytes(realFile);
                if (rawBytes.length <= MAX_CACHEABLE_FILE_BYTES) {
                    byte[] gzBytes = null;
                    if (GZIPPABLE_EXTENSIONS.contains(extension)) {
                        final Path gzipFile = file.resolveSibling(file.getFileName() + ".gz");
                        try {
                            final BasicFileAttributes gzAttrs =
                                    Files.readAttributes(gzipFile, BasicFileAttributes.class);
                            if (gzAttrs.isRegularFile()
                                    && gzAttrs.lastModifiedTime().toMillis() >= modified
                                    && gzAttrs.size() <= MAX_CACHEABLE_FILE_BYTES) {
                                final Path realGzip = gzipFile.toRealPath();
                                if (realGzip.startsWith(realRoot)) {
                                    gzBytes = Files.readAllBytes(realGzip);
                                }
                            }
                        } catch (final IOException ignored) {
                        }
                        if (gzBytes == null && rawBytes.length >= HttpCompression.MIN_GZIP_BYTES) {
                            final byte[] dynamicallyCompressed = HttpCompression.gzip(rawBytes);
                            if (dynamicallyCompressed.length < rawBytes.length) {
                                gzBytes = dynamicallyCompressed;
                            }
                        }
                    }
                    final String baseEtagHex = Long.toHexString(modified) + "-" + Long.toHexString(rawBytes.length);
                    final String rawEtag = "W/\"" + baseEtagHex + "\"";
                    final String gzEtag = gzBytes != null ? "W/\"" + baseEtagHex + "-gzip\"" : null;
                    final CachedAsset fresh = new CachedAsset(
                            resources.root(), realFile, rawBytes, gzBytes, contentType,
                            rawEtag, gzEtag, lastModifiedHeader, modified, rawBytes.length,
                            System.currentTimeMillis());
                    putCachedAsset(uri, fresh);
                    serveCachedAsset(context, uri, fresh);
                    return;
                }
            }

            Path representation = realFile;
            long representationSize = fileSize;
            boolean gzipServed = false;
            if (GZIPPABLE_EXTENSIONS.contains(extension)) {
                final Path gzipFile = file.resolveSibling(file.getFileName() + ".gz");
                try {
                    final BasicFileAttributes gzAttrs =
                            Files.readAttributes(gzipFile, BasicFileAttributes.class);
                    if (gzAttrs.isRegularFile() && gzAttrs.lastModifiedTime().toMillis() >= modified) {
                        final Path realGzip = gzipFile.toRealPath();
                        if (realGzip.startsWith(realRoot)) {
                            context.setHeader("Vary", "Accept-Encoding");
                            if (context.getRequest().getHeader("Range") == null
                                    && acceptsGzip(context.header("Accept-Encoding"))) {
                                representation = realGzip;
                                representationSize = gzAttrs.size();
                                gzipServed = true;
                                context.setHeader("Content-Encoding", "gzip");
                            }
                        }
                    }
                } catch (final IOException ignored) { }
            }
            context.getResponse().setContentType(contentType);
            context.setHeader("X-Content-Type-Options", "nosniff");
            if (!gzipServed) {
                context.setHeader("Accept-Ranges", "bytes");
            }
            final String etag = "W/\"" + Long.toHexString(modified) + "-" + Long.toHexString(fileSize)
                    + (gzipServed ? "-gzip" : "") + "\"";
            context.setHeader("ETag", etag);
            context.setHeader("Last-Modified", lastModifiedHeader);
            context.setHeader("Cache-Control", resolveCacheControl(context, uri));

            if (isNotModified(context, etag, modified)) {
                context.setStatus(304);
                context.sendBytes(EMPTY_BYTES);
                context.abort();
                return;
            }

            final boolean headOnly = "HEAD".equalsIgnoreCase(context.method());
            if (!gzipServed && serveByteRangeIfPresent(
                    context, representation, null, fileSize, etag, lastModifiedHeader, modified, headOnly)) {
                context.abort();
                return;
            }

            if (headOnly) {
                final String sizeStr = Long.toString(representationSize);
                context.setHeader("Content-Length", sizeStr);
                context.sendBytes(EMPTY_BYTES);
                context.setHeader("Content-Length", sizeStr);
            } else {
                context.sendBytes(Files.readAllBytes(representation));
            }
            context.abort();
        } catch (final Exception e) { context.sendError(404); context.abort(); }
    }

    private static CachedAsset lookupValidCachedAsset(final WebResources resources, final String uri) {
        final CachedAsset cached;
        synchronized (ASSET_CACHE) {
            cached = ASSET_CACHE.get(uri);
        }
        if (cached == null || !cached.root.equals(resources.root())) {
            return null;
        }
        final long now = System.currentTimeMillis();
        if (isProductionRuntime() && now - cached.verifiedAtMillis < PROD_STAT_VERIFY_MS) {
            return cached;
        }
        try {
            final BasicFileAttributes attrs = Files.readAttributes(cached.realFile, BasicFileAttributes.class);
            if (attrs.isRegularFile()
                    && attrs.lastModifiedTime().toMillis() == cached.modifiedMillis
                    && attrs.size() == cached.rawSize) {
                cached.verifiedAtMillis = now;
                return cached;
            }
        } catch (final IOException ignored) {
        }
        evictCachedAsset(uri, cached);
        return null;
    }

    private static void putCachedAsset(final String uri, final CachedAsset asset) {
        if (asset.weightBytes > MAX_TOTAL_CACHE_BYTES) {
            return;
        }
        synchronized (ASSET_CACHE) {
            final CachedAsset prev = ASSET_CACHE.remove(uri);
            if (prev != null) {
                currentCacheBytes = Math.max(0L, currentCacheBytes - prev.weightBytes);
            }
            final Iterator<Map.Entry<String, CachedAsset>> it = ASSET_CACHE.entrySet().iterator();
            while (currentCacheBytes + asset.weightBytes > MAX_TOTAL_CACHE_BYTES && it.hasNext()) {
                final CachedAsset evicted = it.next().getValue();
                it.remove();
                currentCacheBytes = Math.max(0L, currentCacheBytes - evicted.weightBytes);
            }
            ASSET_CACHE.put(uri, asset);
            currentCacheBytes += asset.weightBytes;
        }
    }

    private static void evictCachedAsset(final String uri, final CachedAsset expected) {
        synchronized (ASSET_CACHE) {
            final CachedAsset current = ASSET_CACHE.get(uri);
            if (current == expected) {
                ASSET_CACHE.remove(uri);
                currentCacheBytes = Math.max(0L, currentCacheBytes - expected.weightBytes);
            }
        }
    }

    private static void serveCachedAsset(final RequestContext context,
                                         final String uri,
                                         final CachedAsset cached) throws IOException {
        final boolean hasGzip = cached.gzBytes != null;
        final String rangeHeader = context.getRequest().getHeader("Range");
        final boolean gzipServed;
        if (hasGzip) {
            context.setHeader("Vary", "Accept-Encoding");
            gzipServed = rangeHeader == null && acceptsGzip(context.header("Accept-Encoding"));
            if (gzipServed) {
                context.setHeader("Content-Encoding", "gzip");
            }
        } else {
            gzipServed = false;
        }
        context.getResponse().setContentType(cached.contentType);
        context.setHeader("X-Content-Type-Options", "nosniff");
        if (!gzipServed) {
            context.setHeader("Accept-Ranges", "bytes");
        }
        final String etag = gzipServed ? cached.gzEtag : cached.rawEtag;
        context.setHeader("ETag", etag);
        context.setHeader("Last-Modified", cached.lastModifiedHeader);
        context.setHeader("Cache-Control", resolveCacheControl(context, uri));

        if (isNotModified(context, etag, cached.modifiedMillis)) {
            context.setStatus(304);
            context.sendBytes(EMPTY_BYTES);
            context.abort();
            return;
        }

        final boolean headOnly = "HEAD".equalsIgnoreCase(context.method());
        if (!gzipServed && rangeHeader != null && serveByteRangeIfPresent(
                context, cached.realFile, cached.rawBytes, cached.rawSize, etag,
                cached.lastModifiedHeader, cached.modifiedMillis, headOnly)) {
            context.abort();
            return;
        }

        final byte[] selectedBytes = gzipServed ? cached.gzBytes : cached.rawBytes;
        if (headOnly) {
            final String sizeStr = Integer.toString(selectedBytes.length);
            context.setHeader("Content-Length", sizeStr);
            context.sendBytes(EMPTY_BYTES);
            context.setHeader("Content-Length", sizeStr);
        } else {
            context.sendBytes(selectedBytes);
        }
        context.abort();
    }

    private static String resolveCacheControl(final RequestContext context, final String uri) {
        if (NO_CACHE_PATHS.contains(uri)) {
            return "no-cache";
        }
        if (context.getRequest() != null
                && StringUtils.isNotBlank(context.getRequest().getQueryString())
                && isVersionableImmutablePath(uri)) {
            return "public, max-age=31536000, immutable";
        }
        return "public, max-age=3600";
    }

    private static boolean isVersionableImmutablePath(final String uri) {
        return uri != null && (uri.startsWith("/js/")
                || uri.startsWith("/scss/")
                || uri.startsWith("/skins/")
                || uri.startsWith("/plugins/"));
    }

    private static boolean isNotModified(final RequestContext context,
                                         final String etag,
                                         final long modifiedMillis) {
        final String condition = context.getRequest().getHeader("If-None-Match");
        if (condition != null) {
            return matchesIfNoneMatch(condition, etag);
        }
        final String since = context.getRequest().getHeader("If-Modified-Since");
        if (since != null) {
            try {
                return modifiedMillis / 1000L <= ZonedDateTime.parse(since,
                        DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond();
            } catch (final DateTimeParseException ignored) {
            }
        }
        return false;
    }

    private static boolean isProductionRuntime() {
        try {
            return Latkes.RuntimeMode.PRODUCTION == Latkes.getRuntimeMode();
        } catch (final RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isCleanSingleSlashAsciiPath(final String uri) {
        if (uri == null || uri.isEmpty() || uri.charAt(0) != '/' || (uri.length() > 1 && uri.charAt(1) == '/')) {
            return false;
        }
        for (int i = 1; i < uri.length(); i++) {
            final char c = uri.charAt(i);
            final boolean safe = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '/' || c == '.' || c == '-' || c == '_';
            if (!safe) {
                return false;
            }
        }
        return true;
    }

    /**
     * Evaluates {@code Range: bytes=...} (and optional {@code If-Range}) for an uncompressed static/media file.
     *
     * @return {@code true} if a {@code 206 Partial Content} or {@code 416 Range Not Satisfiable} response was written;
     *         {@code false} if no applicable single byte range was requested and the caller should serve {@code 200 OK}
     */
    public static boolean serveByteRangeIfPresent(final RequestContext context,
                                                  final Path file,
                                                  final long totalSize,
                                                  final String etag,
                                                  final String lastModifiedHeader,
                                                  final long modifiedMillis,
                                                  final boolean headOnly) throws IOException {
        return serveByteRangeIfPresent(
                context, file, null, totalSize, etag, lastModifiedHeader, modifiedMillis, headOnly);
    }

    public static boolean serveByteRangeIfPresent(final RequestContext context,
                                                  final Path file,
                                                  final byte[] inMemoryBytes,
                                                  final long totalSize,
                                                  final String etag,
                                                  final String lastModifiedHeader,
                                                  final long modifiedMillis,
                                                  final boolean headOnly) throws IOException {
        final String rangeHeader = context.getRequest().getHeader("Range");
        if (rangeHeader == null || !rangeHeader.regionMatches(true, 0, "bytes=", 0, 6)) {
            return false;
        }
        final String ifRange = context.getRequest().getHeader("If-Range");
        if (!isIfRangeSatisfied(ifRange, etag, lastModifiedHeader, modifiedMillis)) {
            return false;
        }
        final String spec = rangeHeader.substring(6).trim();
        if (spec.isEmpty()) {
            sendRangeNotSatisfiable(context, totalSize);
            return true;
        }
        if (spec.indexOf(',') >= 0) {
            // Fall back to full 200 OK representation on multi-part byte range requests.
            return false;
        }
        final int dash = spec.indexOf('-');
        if (dash < 0 || totalSize <= 0L) {
            sendRangeNotSatisfiable(context, totalSize);
            return true;
        }

        final String left = spec.substring(0, dash).trim();
        final String right = spec.substring(dash + 1).trim();
        final long start;
        long end;

        if (left.isEmpty()) {
            // Suffix byte-range-spec: bytes=-suffixLength
            final long suffixLength = parseNonNegativeLong(right);
            if (suffixLength <= 0L) {
                sendRangeNotSatisfiable(context, totalSize);
                return true;
            }
            final long cappedSuffix = Math.min(suffixLength, MAX_EXPLICIT_RANGE_BYTES);
            start = Math.max(0L, totalSize - cappedSuffix);
            end = totalSize - 1L;
        } else {
            start = parseNonNegativeLong(left);
            if (start < 0L || start >= totalSize) {
                sendRangeNotSatisfiable(context, totalSize);
                return true;
            }
            if (right.isEmpty()) {
                // Open-ended range (bytes=start-): cap single chunk to 4 MiB on large files.
                end = Math.min(totalSize - 1L, start + OPEN_RANGE_CHUNK_BYTES - 1L);
            } else {
                final long requestedEnd = parseNonNegativeLong(right);
                if (requestedEnd < 0L || requestedEnd < start) {
                    sendRangeNotSatisfiable(context, totalSize);
                    return true;
                }
                end = Math.min(totalSize - 1L, requestedEnd);
                end = Math.min(end, start + MAX_EXPLICIT_RANGE_BYTES - 1L);
            }
        }

        final int requestedLength = (int) (end - start + 1L);
        final byte[] slice;
        if (headOnly) {
            slice = EMPTY_BYTES;
        } else if (inMemoryBytes != null) {
            final int memStart = (int) Math.min(start, inMemoryBytes.length);
            final int memEndExclusive = (int) Math.min((long) inMemoryBytes.length, start + requestedLength);
            slice = memEndExclusive > memStart
                    ? Arrays.copyOfRange(inMemoryBytes, memStart, memEndExclusive)
                    : EMPTY_BYTES;
        } else {
            slice = readFileSlice(file, start, requestedLength);
        }
        if (!headOnly && slice.length == 0) {
            sendRangeNotSatisfiable(context, totalSize);
            return true;
        }
        final long actualEnd = headOnly ? end : (start + slice.length - 1L);
        final int contentLength = headOnly ? requestedLength : slice.length;
        final String contentLengthStr = Integer.toString(contentLength);

        context.setStatus(206);
        context.setHeader("Content-Range", "bytes " + start + "-" + actualEnd + "/" + totalSize);
        context.setHeader("Content-Length", contentLengthStr);
        context.sendBytes(slice);
        if (headOnly) {
            context.setHeader("Content-Length", contentLengthStr);
        }
        return true;
    }

    public static byte[] readFileSlice(final Path file, final long start, final int length) throws IOException {
        if (length <= 0) {
            return EMPTY_BYTES;
        }
        final byte[] data = new byte[length];
        final ByteBuffer buf = ByteBuffer.wrap(data);
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            channel.position(start);
            while (buf.hasRemaining()) {
                final int n = channel.read(buf);
                if (n < 0) {
                    break;
                }
            }
        }
        if (buf.hasRemaining()) {
            return Arrays.copyOf(data, buf.position());
        }
        return data;
    }

    private static void sendRangeNotSatisfiable(final RequestContext context, final long totalSize) {
        context.setStatus(416);
        context.setHeader("Content-Range", "bytes */" + Math.max(0L, totalSize));
        context.sendBytes(EMPTY_BYTES);
    }

    private static boolean isIfRangeSatisfied(final String rawIfRange,
                                              final String etag,
                                              final String lastModifiedHeader,
                                              final long modifiedMillis) {
        if (rawIfRange == null) {
            return true;
        }
        final String trimmed = rawIfRange.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        if (trimmed.equals(etag)) {
            return true;
        }
        if (etag != null && etag.startsWith("W/") && trimmed.equals(etag.substring(2))) {
            return true;
        }
        if (trimmed.equals(lastModifiedHeader)) {
            return true;
        }
        try {
            return ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()
                    == modifiedMillis / 1000L;
        } catch (final DateTimeParseException ignored) {
            return false;
        }
    }

    private static long parseNonNegativeLong(final String text) {
        if (text == null || text.isEmpty() || text.length() > 18) {
            return -1L;
        }
        long val = 0L;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return -1L;
            }
            val = val * 10L + (c - '0');
        }
        return val;
    }

    public static boolean matchesIfNoneMatch(final String header, final String etag) {
        if (header == null || etag == null || etag.isEmpty()) {
            return false;
        }
        final int tagStart = etag.length() > 2
                && (etag.charAt(0) == 'W' || etag.charAt(0) == 'w')
                && etag.charAt(1) == '/'
                ? 2 : 0;
        final int tagLen = etag.length() - tagStart;
        final int len = header.length();
        int i = 0;
        while (i < len) {
            while (i < len && (header.charAt(i) == ',' || header.charAt(i) <= ' ')) i++;
            if (i >= len) break;
            int end = header.indexOf(',', i);
            if (end < 0) end = len;
            int trimEnd = end;
            while (trimEnd > i && header.charAt(trimEnd - 1) <= ' ') trimEnd--;
            if (trimEnd - i == 1 && header.charAt(i) == '*') return true;
            int cmpStart = i;
            if (trimEnd - cmpStart > 2
                    && (header.charAt(cmpStart) == 'W' || header.charAt(cmpStart) == 'w')
                    && header.charAt(cmpStart + 1) == '/') {
                cmpStart += 2;
            }
            if (trimEnd - cmpStart == tagLen
                    && header.regionMatches(cmpStart, etag, tagStart, tagLen)) {
                return true;
            }
            i = end + 1;
        }
        return false;
    }

    /**
     * Zero-allocation RFC 9110 {@code Accept-Encoding} scanner supporting case-insensitive {@code gzip},
     * {@code *} wildcards, and {@code q=0} / {@code q=0.000} exclusions.
     */
    public static boolean acceptsGzip(final String header) {
        if (header == null || header.isEmpty()) {
            return false;
        }
        final int len = header.length();
        boolean wildcardAccepted = false;
        int i = 0;
        while (i < len) {
            while (i < len && (header.charAt(i) == ',' || header.charAt(i) <= ' ')) {
                i++;
            }
            if (i >= len) {
                break;
            }
            int entryEnd = header.indexOf(',', i);
            if (entryEnd < 0) {
                entryEnd = len;
            }
            int semi = header.indexOf(';', i);
            final int tokenLimit = (semi >= 0 && semi < entryEnd) ? semi : entryEnd;
            int tokenEnd = tokenLimit;
            while (tokenEnd > i && header.charAt(tokenEnd - 1) <= ' ') {
                tokenEnd--;
            }
            final int tokenLen = tokenEnd - i;
            final boolean isGzip = tokenLen == 4 && header.regionMatches(true, i, "gzip", 0, 4);
            final boolean isWildcard = tokenLen == 1 && header.charAt(i) == '*';
            if (isGzip || isWildcard) {
                boolean accepted = true;
                int pStart = (semi >= 0 && semi < entryEnd) ? semi + 1 : entryEnd;
                while (pStart < entryEnd) {
                    int nextSemi = header.indexOf(';', pStart);
                    if (nextSemi < 0 || nextSemi > entryEnd) {
                        nextSemi = entryEnd;
                    }
                    int ps = pStart;
                    int pe = nextSemi;
                    while (ps < pe && header.charAt(ps) <= ' ') {
                        ps++;
                    }
                    while (pe > ps && header.charAt(pe - 1) <= ' ') {
                        pe--;
                    }
                    if (pe - ps >= 2
                            && (header.charAt(ps) == 'q' || header.charAt(ps) == 'Q')
                            && header.charAt(ps + 1) == '=') {
                        accepted = isPositiveUnitQValue(header, ps + 2, pe);
                    }
                    pStart = nextSemi + 1;
                }
                if (isGzip) {
                    return accepted;
                }
                wildcardAccepted = accepted;
            }
            i = entryEnd + 1;
        }
        return wildcardAccepted;
    }

    private static boolean isPositiveUnitQValue(final String s, final int start, final int end) {
        if (start >= end) {
            return false;
        }
        final char first = s.charAt(start);
        if (first == '1') {
            if (start + 1 == end) {
                return true;
            }
            if (s.charAt(start + 1) != '.') {
                return false;
            }
            for (int k = start + 2; k < end; k++) {
                if (s.charAt(k) != '0') {
                    return false;
                }
            }
            return true;
        }
        if (first == '0') {
            if (start + 1 == end) {
                return false;
            }
            if (s.charAt(start + 1) != '.') {
                return false;
            }
            boolean anyNonZero = false;
            for (int k = start + 2; k < end; k++) {
                final char c = s.charAt(k);
                if (c < '0' || c > '9') {
                    return false;
                }
                if (c != '0') {
                    anyNonZero = true;
                }
            }
            return anyNonZero;
        }
        if (first == '.' && start + 1 < end) {
            boolean anyNonZero = false;
            for (int k = start + 1; k < end; k++) {
                final char c = s.charAt(k);
                if (c < '0' || c > '9') {
                    return false;
                }
                if (c != '0') {
                    anyNonZero = true;
                }
            }
            return anyNonZero;
        }
        return false;
    }
}
