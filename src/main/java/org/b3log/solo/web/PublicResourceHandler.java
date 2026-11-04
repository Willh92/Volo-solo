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

import org.b3log.latke.Latkes;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.http.function.Handler;
import org.b3log.solo.SoloApplication;
import java.nio.file.*;
import java.util.*;

/** Public static files only; application configuration and template sources stay private. */
public final class PublicResourceHandler implements Handler {
    private static final Set<String> EXTENSIONS = Set.of("css","js","png","jpg","jpeg","gif","svg","ico","webp","avif","woff","woff2","ttf","eot","otf","json","mp3","mp4","webm","ogg","moc","mtn","txt");
    private static final List<String> PUBLIC_PREFIXES = List.of("/scss/", "/js/", "/skins/", "/plugins/", "/images/");
    private static final Set<String> PUBLIC_EXACT_PATHS = Set.of("/favicon.ico", "/robots.txt", "/sw.js", "/service-worker.js");
    private static final Set<String> PUBLIC_ASSET_EXACT_PATHS = Set.of("/favicon.ico", "/sw.js", "/service-worker.js");
    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "HEAD");
    private static final Set<String> NO_CACHE_PATHS = Set.of("/sw.js", "/service-worker.js");
    private static final Set<String> GZIPPABLE_EXTENSIONS = Set.of("js", "css", "svg", "json");
    private static final byte[] EMPTY_BYTES = new byte[0];

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

    private static String contentTypeForExtension(final String extension, final Path file) throws java.io.IOException {
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
            if (!Latkes.getContextPath().isEmpty() && uri.startsWith(Latkes.getContextPath())) uri = uri.substring(Latkes.getContextPath().length());
            uri = java.net.URI.create(uri).getPath();
            final String lower = uri.toLowerCase(Locale.ROOT);
            if (uri.contains("\\") || lower.contains("/web-inf") || lower.contains("/meta-inf") || lower.contains("/.git") || lower.contains("/.idea")) {
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
            final Path realRoot = resources.realRoot();
            final Path file = resources.resolve(uri);
            final java.nio.file.attribute.BasicFileAttributes fileAttrs;
            try {
                fileAttrs = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
            } catch (java.io.IOException missing) {
                context.sendError(404); context.abort(); return;
            }
            if (!fileAttrs.isRegularFile() || !file.toRealPath().startsWith(realRoot)) { context.sendError(404); context.abort(); return; }
            final long modified = fileAttrs.lastModifiedTime().toMillis();
            Path representation = file;
            if (GZIPPABLE_EXTENSIONS.contains(extension)) {
                final Path gzipFile = file.resolveSibling(file.getFileName() + ".gz");
                try {
                    final java.nio.file.attribute.BasicFileAttributes gzAttrs =
                            Files.readAttributes(gzipFile, java.nio.file.attribute.BasicFileAttributes.class);
                    if (gzAttrs.isRegularFile()
                            && gzipFile.toRealPath().startsWith(realRoot)
                            && gzAttrs.lastModifiedTime().toMillis() >= modified) {
                        context.setHeader("Vary", "Accept-Encoding");
                        if (acceptsGzip(context.header("Accept-Encoding"))) {
                            representation = gzipFile;
                            context.setHeader("Content-Encoding", "gzip");
                        }
                    }
                } catch (java.io.IOException ignored) { }
            }
            final String type = contentTypeForExtension(extension, file);
            context.getResponse().setContentType(type == null ? "application/octet-stream" : type);
            context.setHeader("X-Content-Type-Options", "nosniff");
            final String etag = "W/\"" + Long.toHexString(modified) + "-" + Long.toHexString(fileAttrs.size())
                    + (representation.equals(file) ? "" : "-gzip") + "\"";
            context.setHeader("ETag", etag);
            context.setHeader("Last-Modified", java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                    .format(java.time.Instant.ofEpochMilli(modified).atZone(java.time.ZoneOffset.UTC)));
            context.setHeader("Cache-Control", NO_CACHE_PATHS.contains(uri)
                    ? "no-cache" : "public, max-age=3600");
            final String condition = context.getRequest().getHeader("If-None-Match");
            boolean notModified = false;
            if (condition != null) {
                notModified = matchesIfNoneMatch(condition, etag.substring(2));
            } else {
                final String since = context.getRequest().getHeader("If-Modified-Since");
                if (since != null) {
                    try {
                        notModified = modified / 1000 <= java.time.ZonedDateTime.parse(since,
                                java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond();
                    } catch (java.time.format.DateTimeParseException ignored) { }
                }
            }
            if (notModified) {
                context.setStatus(304);
                context.sendBytes(EMPTY_BYTES);
                context.abort();
                return;
            }
            context.sendBytes(context.method().equals("HEAD") ? EMPTY_BYTES : Files.readAllBytes(representation));
            context.abort();
        } catch (Exception e) { context.sendError(404); context.abort(); }
    }

    private static boolean matchesIfNoneMatch(final String header, final String quotedTag) {
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
            if (trimEnd - cmpStart > 2 && header.charAt(cmpStart) == 'W' && header.charAt(cmpStart + 1) == '/') {
                cmpStart += 2;
            }
            if (trimEnd - cmpStart == quotedTag.length()
                    && header.regionMatches(cmpStart, quotedTag, 0, quotedTag.length())) {
                return true;
            }
            i = end + 1;
        }
        return false;
    }

    private static boolean acceptsGzip(final String header) {
        if (header == null) return false;
        double wildcard = 0;
        for (String entry : header.split(",")) {
            final String[] parts = entry.trim().split(";");
            if (!parts[0].equalsIgnoreCase("gzip") && !parts[0].equals("*")) continue;
            double quality = 1;
            for (int i = 1; i < parts.length; i++) {
                final String parameter = parts[i].trim();
                if (parameter.startsWith("q=")) {
                    try { quality = Double.parseDouble(parameter.substring(2)); }
                    catch (NumberFormatException ignored) { quality = 0; }
                }
            }
            if (parts[0].equalsIgnoreCase("gzip")) return quality > 0 && quality <= 1;
            wildcard = quality;
        }
        return wildcard > 0 && wildcard <= 1;
    }
}
