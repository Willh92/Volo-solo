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
package org.b3log.solo.bolo.pic;

import org.apache.commons.io.FileUtils;
import org.b3log.latke.Latkes;
import org.b3log.latke.http.FileUpload;
import org.b3log.latke.http.HttpMethod;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.http.Response;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.ioc.Singleton;
import org.b3log.solo.bolo.pic.util.UploadUtil;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.model.Option;
import org.b3log.solo.repository.OptionRepository;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.util.AdminRequests;
import org.b3log.solo.util.Images;
import org.b3log.solo.util.Solos;
import org.b3log.solo.util.Uploads;
import org.b3log.solo.web.JsonResponses;
import org.b3log.solo.web.PublicResourceHandler;
import org.b3log.solo.web.annotation.RequestProcessing;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * <h3>bolo-solo</h3>
 * <p>图床上传</p>
 *
 * @author : https://github.com/adlered
 * @date : 2020-03-04 20:50
 **/
@Singleton
public class PicUploadProcessor {

    private static final Logger LOGGER = Logger.getLogger(PicUploadProcessor.class);

    private static final Pattern SAFE_IMAGE_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
    private static final long MAX_LOCAL_IMAGE_BYTES = 50L * 1024L * 1024L;
    private static final byte[] EMPTY_BYTES = new byte[0];

    /** Maximum image size eligible for the hot in-memory local image cache (256 KiB). */
    public static final int MAX_CACHEABLE_IMAGE_BYTES = 256 * 1024;
    /** Total memory ceiling across cached hot thumbnails/avatars (4 MiB). */
    public static final int MAX_TOTAL_IMAGE_CACHE_BYTES = 4 * 1024 * 1024;
    private static final long BASE_DIR_VERIFY_MS = 5_000L;
    private static final String LOCAL_CONFIG_PREFIX = "local<<>>";
    private static final String CONFIG_DELIMITER = "<<>>";

    private static volatile CachedBaseDir cachedBaseDir;
    private static final LinkedHashMap<String, CachedImage> IMAGE_CACHE = new LinkedHashMap<>(64, 0.75f, true);
    private static long currentImageCacheBytes;

    private record CachedBaseDir(String rawPath, Path baseDir, Path realBase, long verifiedAtMillis) {
    }

    private static final class CachedImage {
        final Path realBase;
        final Path realTarget;
        final byte[] bytes;
        final String contentType;
        final String etag;
        final String lastModifiedHeader;
        final long modifiedMillis;
        final long size;

        CachedImage(final Path realBase,
                    final Path realTarget,
                    final byte[] bytes,
                    final String contentType,
                    final String etag,
                    final String lastModifiedHeader,
                    final long modifiedMillis,
                    final long size) {
            this.realBase = realBase;
            this.realTarget = realTarget;
            this.bytes = bytes;
            this.contentType = contentType;
            this.etag = etag;
            this.lastModifiedHeader = lastModifiedHeader;
            this.modifiedMillis = modifiedMillis;
            this.size = size;
        }
    }

    /** Clears the cached canonical local storage directory and hot local-image byte cache. */
    public static void clearCache() {
        cachedBaseDir = null;
        synchronized (IMAGE_CACHE) {
            IMAGE_CACHE.clear();
            currentImageCacheBytes = 0L;
        }
    }

    @Inject
    private OptionQueryService optionQueryService;

    @Inject
    private OptionRepository optionRepository;

    /**
     * 自定义图床上传目录
     */
    @RequestProcessing(value = "/pic/upload", method = {HttpMethod.POST})
    public void uploadPicture(final RequestContext context) {
        context.setHeader("Cache-Control", "no-store");
        if (!Solos.isAdminOrAuthorLoggedIn(context)) {
            context.sendError(401);
            return;
        }
        if (!AdminRequests.allowEditorWrite(context)) {
            return;
        }

        Path directory = null;
        List<FileUpload> itemList = Collections.emptyList();
        final Map<String, String> okPic = new HashMap<>();
        final List<String> errFiles = new ArrayList<>();
        try {
            directory = Files.createTempDirectory("bolo-image-");
            itemList = Uploads.files(context.getRequest());
            if (!itemList.isEmpty()) {
                final String config = getOptionValue(Option.ID_C_TUCHUANG_CONFIG, "hacpai");
                final String compressValue = getOptionValue(Option.ID_C_IMAGE_UPLOAD_COMPRESS, "10");
                for (final FileUpload item : itemList) {
                    final String name = item.getFilename();
                    final File file;
                    try {
                        file = Uploads.stage(item, directory, "jpg", "jpeg", "png", "gif", "bmp", "webp", "avif");
                        Uploads.validateImage(file);
                    } catch (final Exception e) {
                        errFiles.add(name);
                        continue;
                    }
                    try {
                        final String lowerStaged = file.getName().toLowerCase(Locale.ROOT);
                        final File uploadTarget = (lowerStaged.endsWith(".webp") || lowerStaged.endsWith(".avif"))
                                ? file
                                : Images.compressImage(file, Float.parseFloat(compressValue));
                        final String url = UploadUtil.upload(config, uploadTarget);
                        if (url == null || url.isBlank()) {
                            errFiles.add(name);
                        } else {
                            okPic.put(name, url);
                        }
                    } catch (final Exception e) {
                        errFiles.add(name);
                    }
                }
            }
        } catch (final Exception e) {
            JsonResponses.json(context).renderCode(400);
            JsonResponses.json(context).renderMsg("图片上传失败，请检查文件类型和大小。");
            return;
        } finally {
            FileUtils.deleteQuietly(directory == null ? null : directory.toFile());
        }
        final Map<String, Object> map = new HashMap<>();
        map.put("succMap", okPic);
        map.put("errFiles", errFiles);
        JsonResponses.json(context).renderData(map);
        JsonResponses.json(context).renderCode(0);
        JsonResponses.json(context).renderMsg("");
    }

    /**
     * 检查本地图床是否符合规则
     */
    @RequestProcessing(value = "/pic/local/check", method = {HttpMethod.POST})
    public void checkLocalImageBedAvailable(final RequestContext context) {
        if (!AdminRequests.allowWrite(context)) {
            return;
        }

        final String path = context.param("path");
        if (path == null || path.isBlank() || "undefined".equals(path) || path.indexOf('\0') >= 0) {
            JsonResponses.json(context).renderCode(500);
            JsonResponses.json(context).renderMsg("测试失败！目录格式错误。");
            return;
        }
        try {
            final Path normalized = Path.of(path.strip()).toAbsolutePath().normalize();
            final Path appRoot = Path.of("").toAbsolutePath().normalize();
            if (normalized.equals(appRoot) || normalized.getParent() == null) {
                JsonResponses.json(context).renderCode(500);
                JsonResponses.json(context).renderMsg("测试失败！不允许直接使用程序根目录或磁盘根目录作为图床目录。");
                return;
            }
            if (!Files.exists(normalized)) {
                final Path parent = normalized.getParent();
                if (parent == null || !Files.isDirectory(parent) || !Files.isWritable(parent)) {
                    JsonResponses.json(context).renderCode(500);
                    JsonResponses.json(context).renderMsg("测试失败！上级目录不存在或没有写入权限。");
                    return;
                }
                Files.createDirectory(normalized);
            } else if (!Files.isDirectory(normalized) || Files.isSymbolicLink(normalized)) {
                JsonResponses.json(context).renderCode(500);
                JsonResponses.json(context).renderMsg("测试失败！目标路径不是有效目录。");
                return;
            }
            final Path probe = Files.createTempFile(normalized, "volo-probe-", ".tmp");
            Files.deleteIfExists(probe);
            JsonResponses.json(context).renderCode(200);
            JsonResponses.json(context).renderMsg("测试成功 :)");
        } catch (final Exception e) {
            JsonResponses.json(context).renderCode(500);
            JsonResponses.json(context).renderMsg("测试失败！目录格式错误或没有写入权限。");
        }
    }

    /**
     * 获取本地图床存储位置的图片，并返回
     */
    @RequestProcessing(value = "/image/{imageFilename}", method = {HttpMethod.GET, HttpMethod.HEAD})
    public void getLocalImage(final RequestContext context) {
        try {
            final Response response = context.getResponse();
            final String config = getOptionValue(Option.ID_C_TUCHUANG_CONFIG, "");
            final String rawStoragePath = extractLocalStoragePath(config);
            if (rawStoragePath == null) {
                context.sendError(404);
                return;
            }
            final String filename = context.pathVar("imageFilename");
            final String contentType = safeImageContentType(filename);
            if (contentType == null) {
                context.sendError(404);
                return;
            }
            final CachedBaseDir base = resolveBaseDir(rawStoragePath);
            if (base == null) {
                context.sendError(404);
                return;
            }
            final Path baseDir = base.baseDir();
            final Path realBase = base.realBase();
            final String cacheKey = realBase.toString() + "/" + filename;

            final CachedImage hot = lookupValidCachedImage(cacheKey, realBase);
            if (hot != null) {
                serveImageResponse(context, response, hot.realTarget, hot.bytes, hot.size,
                        hot.contentType, hot.etag, hot.lastModifiedHeader, hot.modifiedMillis);
                return;
            }

            final Path resolved = baseDir.resolve(filename).normalize();
            if (!resolved.startsWith(baseDir) || Files.isSymbolicLink(resolved)) {
                context.sendError(404);
                return;
            }
            final BasicFileAttributes attrs;
            try {
                attrs = Files.readAttributes(resolved, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (final IOException missing) {
                context.sendError(404);
                return;
            }
            final long size = attrs.size();
            if (!attrs.isRegularFile() || size > MAX_LOCAL_IMAGE_BYTES) {
                context.sendError(404);
                return;
            }
            final Path realTarget = resolved.toRealPath();
            if (!realTarget.startsWith(realBase)) {
                context.sendError(404);
                return;
            }

            final long modified = attrs.lastModifiedTime().toMillis();
            final String etag = "W/\"" + Long.toHexString(modified) + "-" + Long.toHexString(size) + "\"";
            final String lastModifiedHeader = DateTimeFormatter.RFC_1123_DATE_TIME
                    .format(Instant.ofEpochMilli(modified).atZone(ZoneOffset.UTC));

            byte[] cachedBytes = null;
            if (size <= MAX_CACHEABLE_IMAGE_BYTES) {
                cachedBytes = readBoundedFile(realTarget, size, MAX_CACHEABLE_IMAGE_BYTES);
                if (cachedBytes.length == size) {
                    putCachedImage(cacheKey, new CachedImage(
                            realBase, realTarget, cachedBytes, contentType,
                            etag, lastModifiedHeader, modified, size));
                }
            }

            serveImageResponse(context, response, realTarget, cachedBytes, size,
                    contentType, etag, lastModifiedHeader, modified);
        } catch (final Exception e) {
            context.sendError(404);
        }
    }

    private static void serveImageResponse(final RequestContext context,
                                           final Response response,
                                           final Path realTarget,
                                           final byte[] preloadedBytes,
                                           final long size,
                                           final String contentType,
                                           final String etag,
                                           final String lastModifiedHeader,
                                           final long modified) throws IOException {
        context.setHeader("X-Content-Type-Options", "nosniff");
        context.setHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'");
        context.setHeader("Accept-Ranges", "bytes");
        context.setHeader("ETag", etag);
        context.setHeader("Last-Modified", lastModifiedHeader);
        context.setHeader("Cache-Control", "public, max-age=86400");
        response.setContentType(contentType);

        final String ifNoneMatch = context.getRequest().getHeader("If-None-Match");
        boolean notModified = false;
        if (ifNoneMatch != null) {
            notModified = PublicResourceHandler.matchesIfNoneMatch(ifNoneMatch, etag);
        } else {
            final String since = context.getRequest().getHeader("If-Modified-Since");
            if (since != null) {
                try {
                    notModified = modified / 1000L <= ZonedDateTime.parse(since,
                            DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond();
                } catch (final DateTimeParseException ignored) {
                }
            }
        }
        if (notModified) {
            context.setStatus(304);
            response.sendBytes(EMPTY_BYTES);
            return;
        }
        final boolean headOnly = "HEAD".equalsIgnoreCase(context.method());
        if (PublicResourceHandler.serveByteRangeIfPresent(
                context, realTarget, preloadedBytes, size, etag, lastModifiedHeader, modified, headOnly)) {
            return;
        }
        if (headOnly) {
            final String sizeStr = Long.toString(size);
            response.setHeader("Content-Length", sizeStr);
            response.sendBytes(EMPTY_BYTES);
            response.setHeader("Content-Length", sizeStr);
            return;
        }
        final byte[] payload = preloadedBytes != null
                ? preloadedBytes
                : readBoundedFile(realTarget, size, MAX_LOCAL_IMAGE_BYTES);
        response.sendBytes(payload);
    }

    private static String extractLocalStoragePath(final String config) {
        if (config == null || !config.startsWith(LOCAL_CONFIG_PREFIX)) {
            return null;
        }
        final int pathStart = LOCAL_CONFIG_PREFIX.length();
        final int nextDelim = config.indexOf(CONFIG_DELIMITER, pathStart);
        final String rawPath = nextDelim >= 0 ? config.substring(pathStart, nextDelim) : config.substring(pathStart);
        final String stripped = rawPath.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static CachedBaseDir resolveBaseDir(final String strippedPath) throws IOException {
        final long now = System.currentTimeMillis();
        final CachedBaseDir current = cachedBaseDir;
        if (current != null && strippedPath.equals(current.rawPath())) {
            if (isProductionRuntime() && now - current.verifiedAtMillis() < BASE_DIR_VERIFY_MS) {
                return current;
            }
            if (Files.isDirectory(current.baseDir()) && !Files.isSymbolicLink(current.baseDir())) {
                final Path verifiedReal = current.baseDir().toRealPath();
                if (verifiedReal.equals(current.realBase())) {
                    final CachedBaseDir refreshed = new CachedBaseDir(
                            strippedPath, current.baseDir(), verifiedReal, now);
                    cachedBaseDir = refreshed;
                    return refreshed;
                }
            }
        }
        final Path baseDir = Path.of(strippedPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(baseDir) || Files.isSymbolicLink(baseDir)) {
            return null;
        }
        final Path realBase = baseDir.toRealPath();
        final CachedBaseDir fresh = new CachedBaseDir(strippedPath, baseDir, realBase, now);
        cachedBaseDir = fresh;
        return fresh;
    }

    private static CachedImage lookupValidCachedImage(final String cacheKey, final Path realBase) {
        final CachedImage cached;
        synchronized (IMAGE_CACHE) {
            cached = IMAGE_CACHE.get(cacheKey);
        }
        if (cached == null || !cached.realBase.equals(realBase)) {
            return null;
        }
        try {
            final BasicFileAttributes attrs = Files.readAttributes(
                    cached.realTarget, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isRegularFile()
                    && attrs.lastModifiedTime().toMillis() == cached.modifiedMillis
                    && attrs.size() == cached.size) {
                return cached;
            }
        } catch (final IOException ignored) {
        }
        synchronized (IMAGE_CACHE) {
            final CachedImage current = IMAGE_CACHE.get(cacheKey);
            if (current == cached) {
                IMAGE_CACHE.remove(cacheKey);
                currentImageCacheBytes = Math.max(0L, currentImageCacheBytes - cached.bytes.length);
            }
        }
        return null;
    }

    private static void putCachedImage(final String cacheKey, final CachedImage image) {
        final int weight = image.bytes.length;
        if (weight > MAX_TOTAL_IMAGE_CACHE_BYTES) {
            return;
        }
        synchronized (IMAGE_CACHE) {
            final CachedImage prev = IMAGE_CACHE.remove(cacheKey);
            if (prev != null) {
                currentImageCacheBytes = Math.max(0L, currentImageCacheBytes - prev.bytes.length);
            }
            final Iterator<Map.Entry<String, CachedImage>> it = IMAGE_CACHE.entrySet().iterator();
            while (currentImageCacheBytes + weight > MAX_TOTAL_IMAGE_CACHE_BYTES && it.hasNext()) {
                final CachedImage evicted = it.next().getValue();
                it.remove();
                currentImageCacheBytes = Math.max(0L, currentImageCacheBytes - evicted.bytes.length);
            }
            IMAGE_CACHE.put(cacheKey, image);
            currentImageCacheBytes += weight;
        }
    }

    private static byte[] readBoundedFile(final Path file, final long expectedSize, final long maxBytes)
            throws IOException {
        if (expectedSize < 0L || expectedSize > maxBytes || expectedSize > Integer.MAX_VALUE - 8) {
            throw new IOException("Local image exceeds maximum allowed size");
        }
        if (expectedSize == 0L) {
            try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
                final ByteBuffer probe = ByteBuffer.allocate(1);
                if (channel.read(probe) <= 0) {
                    return EMPTY_BYTES;
                }
            }
        }
        final int initialCap = (int) Math.min(expectedSize, maxBytes);
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            final byte[] fastBuf = new byte[initialCap];
            final ByteBuffer wrapper = ByteBuffer.wrap(fastBuf);
            while (wrapper.hasRemaining()) {
                final int n = channel.read(wrapper);
                if (n < 0) {
                    break;
                }
            }
            if (wrapper.hasRemaining()) {
                final byte[] shrunk = new byte[wrapper.position()];
                System.arraycopy(fastBuf, 0, shrunk, 0, wrapper.position());
                return shrunk;
            }
            final ByteBuffer overflow = ByteBuffer.allocate(8192);
            final int extra = channel.read(overflow);
            if (extra <= 0) {
                return fastBuf;
            }
            long total = fastBuf.length + extra;
            if (total > maxBytes) {
                throw new IOException("Local image grew beyond maximum allowed size");
            }
            final ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(maxBytes, total + 8192L));
            out.write(fastBuf);
            out.write(overflow.array(), 0, extra);
            while (true) {
                overflow.clear();
                final int n = channel.read(overflow);
                if (n < 0) {
                    break;
                }
                total += n;
                if (total > maxBytes) {
                    throw new IOException("Local image grew beyond maximum allowed size");
                }
                out.write(overflow.array(), 0, n);
            }
            return out.toByteArray();
        }
    }

    private static boolean isProductionRuntime() {
        try {
            return Latkes.RuntimeMode.PRODUCTION == Latkes.getRuntimeMode();
        } catch (final RuntimeException ignored) {
            return false;
        }
    }

    private String getOptionValue(final String optionId, final String fallback) {
        try {
            final JSONObject opt = optionQueryService != null
                    ? optionQueryService.getOptionById(optionId)
                    : (optionRepository != null ? optionRepository.get(optionId) : null);
            if (opt != null && !opt.isNull(Option.OPTION_VALUE)) {
                return opt.optString(Option.OPTION_VALUE, fallback);
            }
        } catch (final Exception ignored) {
        }
        return fallback;
    }

    public static String safeImageContentType(final String filename) {
        if (filename == null || !SAFE_IMAGE_NAME.matcher(filename).matches() || filename.contains("..")) {
            return null;
        }
        try {
            final String ext = Uploads.extension(filename, "jpg", "jpeg", "png", "gif", "bmp", "webp", "avif")
                    .toLowerCase(Locale.ROOT);
            return switch (ext) {
                case "jpg", "jpeg" -> "image/jpeg";
                case "png" -> "image/png";
                case "gif" -> "image/gif";
                case "bmp" -> "image/bmp";
                case "webp" -> "image/webp";
                case "avif" -> "image/avif";
                default -> null;
            };
        } catch (final Exception invalid) {
            return null;
        }
    }
}
