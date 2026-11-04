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

import org.b3log.latke.http.FileUpload;
import org.b3log.latke.ioc.Inject;
import org.b3log.solo.logging.Logger;
import org.b3log.latke.http.HttpMethod;
import org.b3log.latke.http.RequestContext;
import org.b3log.solo.web.annotation.RequestProcessing;
import org.b3log.latke.ioc.Singleton;
import org.b3log.solo.bolo.pic.util.UploadUtil;
import org.b3log.solo.model.Option;
import org.b3log.solo.repository.OptionRepository;
import org.b3log.solo.util.AdminRequests;
import org.b3log.solo.util.Images;
import org.b3log.solo.util.Solos;
import org.b3log.solo.util.Uploads;
import org.apache.commons.io.FileUtils;

import org.b3log.latke.http.Response;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
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

    @Inject
    private OptionRepository optionRepository;

    /**
     * 自定义图床上传目录
     */
    @RequestProcessing(value = "/pic/upload", method = {HttpMethod.POST})
    public void uploadPicture(final RequestContext context) {
        synchronized (this) {
            context.setHeader("Cache-Control", "no-store");
            if (!Solos.isAdminOrAuthorLoggedIn(context)) {
                context.sendError(401);
                return;
            }
            if (!AdminRequests.allowEditorWrite(context)) {
                return;
            }

            Path directory = null;
            List<FileUpload> itemList = java.util.Collections.emptyList();
            Map<String, String> okPic = new HashMap<>();
            List<String> errFiles = new ArrayList<>();
            try {
                directory = Files.createTempDirectory("bolo-image-");
                itemList = Uploads.files(context.getRequest());
                for (FileUpload item : itemList) {
                    String name = item.getFilename();
                    File file;
                    try {
                        file = Uploads.stage(item, directory, "jpg", "jpeg", "png", "gif", "bmp", "webp");
                        Uploads.validateImage(file);
                    } catch (Exception e) {
                        errFiles.add(name);
                        continue;
                    }
                    String config;
                    try {
                        config = optionRepository.get(Option.ID_C_TUCHUANG_CONFIG).optString(Option.OPTION_VALUE);
                    } catch (Exception e) {
                        config = "hacpai";
                    }
                    String value;
                    try {
                        value = optionRepository.get(Option.ID_C_IMAGE_UPLOAD_COMPRESS).optString(Option.OPTION_VALUE);
                    } catch (Exception e) {
                        value = "10";
                    }
                    try {
                        String url = UploadUtil.upload(config, Images.compressImage(file, Float.parseFloat(value)));
                        if (url.isEmpty()) {
                            url = "接口调用错误，请检查偏好设置-自定义图床配置，清除浏览器缓存并重启服务端。";
                        }
                        okPic.put(name, url);
                    } catch (Exception e) {
                        errFiles.add(name);
                    }
                }
            } catch (Exception e) {
                org.b3log.solo.web.JsonResponses.json(context).renderCode(400);
                org.b3log.solo.web.JsonResponses.json(context).renderMsg("图片上传失败，请检查文件类型和大小。");
                return;
            } finally {
                FileUtils.deleteQuietly(directory == null ? null : directory.toFile());
            }
            Map<String, Object> map = new HashMap<>();
            map.put("succMap", okPic);
            map.put("errFiles", errFiles);
            org.b3log.solo.web.JsonResponses.json(context).renderData(map);
            org.b3log.solo.web.JsonResponses.json(context).renderCode(0);
            org.b3log.solo.web.JsonResponses.json(context).renderMsg("");
        }
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
            org.b3log.solo.web.JsonResponses.json(context).renderCode(500);
            org.b3log.solo.web.JsonResponses.json(context).renderMsg("测试失败！目录格式错误。");
            return;
        }
        try {
            final Path normalized = Path.of(path.strip()).toAbsolutePath().normalize();
            final Path appRoot = Path.of("").toAbsolutePath().normalize();
            if (normalized.equals(appRoot) || normalized.getParent() == null) {
                org.b3log.solo.web.JsonResponses.json(context).renderCode(500);
                org.b3log.solo.web.JsonResponses.json(context).renderMsg("测试失败！不允许直接使用程序根目录或磁盘根目录作为图床目录。");
                return;
            }
            if (!Files.exists(normalized)) {
                final Path parent = normalized.getParent();
                if (parent == null || !Files.isDirectory(parent) || !Files.isWritable(parent)) {
                    org.b3log.solo.web.JsonResponses.json(context).renderCode(500);
                    org.b3log.solo.web.JsonResponses.json(context).renderMsg("测试失败！上级目录不存在或没有写入权限。");
                    return;
                }
                Files.createDirectory(normalized);
            } else if (!Files.isDirectory(normalized) || Files.isSymbolicLink(normalized)) {
                org.b3log.solo.web.JsonResponses.json(context).renderCode(500);
                org.b3log.solo.web.JsonResponses.json(context).renderMsg("测试失败！目标路径不是有效目录。");
                return;
            }
            final Path probe = Files.createTempFile(normalized, "volo-probe-", ".tmp");
            Files.deleteIfExists(probe);
            org.b3log.solo.web.JsonResponses.json(context).renderCode(200);
            org.b3log.solo.web.JsonResponses.json(context).renderMsg("测试成功 :)");
        } catch (Exception e) {
            org.b3log.solo.web.JsonResponses.json(context).renderCode(500);
            org.b3log.solo.web.JsonResponses.json(context).renderMsg("测试失败！目录格式错误或没有写入权限。");
        }
    }

    /**
     * 获取本地图床存储位置的图片，并返回
     */
    @RequestProcessing(value = "/image/{imageFilename}", method = HttpMethod.GET)
    public void getLocalImage(final RequestContext context) {
        try {
            final Response response = context.getResponse();
            final String config = optionRepository.get(Option.ID_C_TUCHUANG_CONFIG).optString(Option.OPTION_VALUE);
            final String[] parts = config.split("<<>>", 3);
            if (parts.length < 2 || !"local".equals(parts[0]) || parts[1].isBlank()) {
                context.sendError(404);
                return;
            }
            final String filename = context.pathVar("imageFilename");
            final String contentType = safeImageContentType(filename);
            if (contentType == null) {
                context.sendError(404);
                return;
            }
            final Path baseDir = Path.of(parts[1].strip()).toAbsolutePath().normalize();
            if (!Files.isDirectory(baseDir) || Files.isSymbolicLink(baseDir)) {
                context.sendError(404);
                return;
            }
            final Path resolved = baseDir.resolve(filename).normalize();
            if (!resolved.startsWith(baseDir) || !Files.isRegularFile(resolved) || Files.isSymbolicLink(resolved)) {
                context.sendError(404);
                return;
            }
            final Path realBase = baseDir.toRealPath();
            final Path realTarget = resolved.toRealPath();
            final long size = Files.size(realTarget);
            if (!realTarget.startsWith(realBase) || size > MAX_LOCAL_IMAGE_BYTES) {
                context.sendError(404);
                return;
            }
            final long modified = Files.getLastModifiedTime(realTarget).toMillis();
            final String etag = "W/\"" + Long.toHexString(modified) + "-" + Long.toHexString(size) + "\"";
            context.setHeader("X-Content-Type-Options", "nosniff");
            context.setHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'");
            context.setHeader("ETag", etag);
            context.setHeader("Last-Modified", java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                    .format(java.time.Instant.ofEpochMilli(modified).atZone(java.time.ZoneOffset.UTC)));
            context.setHeader("Cache-Control", "public, max-age=86400");
            response.setContentType(contentType);
            final String ifNoneMatch = context.getRequest().getHeader("If-None-Match");
            boolean notModified = false;
            if (ifNoneMatch != null) {
                notModified = java.util.Arrays.stream(ifNoneMatch.split(",")).map(String::trim)
                        .anyMatch(value -> "*".equals(value) || etag.substring(2).equals(value.replaceFirst("^W/", "")));
            } else {
                final String since = context.getRequest().getHeader("If-Modified-Since");
                if (since != null) {
                    try {
                        notModified = modified / 1000 <= java.time.ZonedDateTime.parse(since,
                                java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond();
                    } catch (java.time.format.DateTimeParseException ignored) {
                    }
                }
            }
            if (notModified) {
                context.setStatus(304);
                response.sendBytes(new byte[0]);
                return;
            }
            final byte[] data = Files.readAllBytes(realTarget);
            response.sendBytes(data);
        } catch (Exception e) {
            context.sendError(404);
        }
    }

    public static String safeImageContentType(final String filename) {
        if (filename == null || !SAFE_IMAGE_NAME.matcher(filename).matches() || filename.contains("..")) {
            return null;
        }
        try {
            final String ext = Uploads.extension(filename, "jpg", "jpeg", "png", "gif", "bmp", "webp")
                    .toLowerCase(Locale.ROOT);
            return switch (ext) {
                case "jpg", "jpeg" -> "image/jpeg";
                case "png" -> "image/png";
                case "gif" -> "image/gif";
                case "bmp" -> "image/bmp";
                case "webp" -> "image/webp";
                default -> null;
            };
        } catch (Exception invalid) {
            return null;
        }
    }
}
