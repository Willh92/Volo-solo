/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.service;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;
import org.b3log.latke.Keys;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.repository.jdbc.JdbcRepository;
import org.b3log.latke.service.annotation.Service;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.model.Option;
import org.b3log.solo.repository.OptionRepository;
import org.b3log.solo.util.OptionDocuments;
import org.b3log.solo.util.OutboundUrls;
import org.b3log.solo.util.S3CompatibleUploader;
import org.json.JSONObject;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Off-site backup synchronization service supporting WebDAV (Nextcloud, Alist, Jianguoyun, Synology)
 * and S3-compatible object storage (AWS S3, Cloudflare R2, MinIO, OSS, COS, B2).
 *
 * @author <a href="https://github.com/Willh92">Willh92</a>
 * @since 3.0.0
 */
@Service
public class RemoteBackupSyncService {

    private static final Logger LOGGER = Logger.getLogger(RemoteBackupSyncService.class);

    public static final String CLEAR_SECRET_SENTINEL = "__VOLO_CLEAR_SECRET__";
    public static final String DEFAULT_REMOTE_DIR = "volo-backups";
    private static final Set<String> ALLOWED_PROTOCOLS = Set.of("webdav", "s3");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration UPLOAD_TIMEOUT = Duration.ofSeconds(120);

    private static final HttpClient WEBDAV_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(ProxySelector.of(null))
            .build();

    private static final ThreadPoolExecutor SYNC_EXECUTOR = createWorker();

    /**
     * Gracefully shuts down the background remote backup sync executor, waiting up to 10 seconds for in-flight uploads.
     */
    public static void shutdown() {
        SYNC_EXECUTOR.shutdown();
        try {
            if (!SYNC_EXECUTOR.awaitTermination(10L, TimeUnit.SECONDS)) {
                SYNC_EXECUTOR.shutdownNow();
            }
        } catch (final InterruptedException e) {
            SYNC_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Inject
    private OptionQueryService optionQueryService;

    @Inject
    private OptionMgmtService optionMgmtService;

    @Inject
    private OptionRepository optionRepository;

    private volatile long lastSyncTime = 0L;
    private volatile String lastSyncStatus = "";

    private static ThreadPoolExecutor createWorker() {
        final ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 1, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32),
                task -> {
                    final Thread t = new Thread(task, "volo-remote-backup-sync");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.DiscardOldestPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /**
     * Returns the current remote backup configuration with secrets redacted for admin UI display.
     */
    public JSONObject getRedactedConfig() {
        final JSONObject raw = loadEffectiveConfig();
        final String protocol = normalizeProtocol(raw.optString("protocol", "webdav"));
        final String webdavPassword = raw.optString("webdavPassword", "");
        final String s3SecretKey = raw.optString("s3SecretKey", "");
        final boolean webdavSecretSet = !webdavPassword.isBlank();
        final boolean s3SecretSet = !s3SecretKey.isBlank();
        final boolean activeSecretSet = "s3".equals(protocol) ? s3SecretSet : webdavSecretSet;

        final JSONObject out = new JSONObject();
        out.put("enabled", Option.optBooleanSwitch(raw, "enabled", false));
        out.put("protocol", protocol);
        out.put("webdavUrl", raw.optString("webdavUrl", ""));
        out.put("webdavUser", raw.optString("webdavUser", ""));
        out.put("remoteDir", raw.optString("remoteDir", DEFAULT_REMOTE_DIR));
        out.put("s3Endpoint", raw.optString("s3Endpoint", ""));
        out.put("s3AccessKey", raw.optString("s3AccessKey", ""));
        out.put("s3Bucket", raw.optString("s3Bucket", ""));
        out.put("s3Region", raw.optString("s3Region", "auto"));
        out.put("s3Prefix", raw.optString("s3Prefix", DEFAULT_REMOTE_DIR));
        out.put("s3PathStyle", Option.optBooleanSwitch(raw, "s3PathStyle", false));
        out.put("secretSet", activeSecretSet);
        out.put("webdavPasswordSet", webdavSecretSet);
        out.put("s3SecretKeySet", s3SecretSet);
        out.put("lastSyncTime", lastSyncTime);
        out.put("lastSyncStatus", lastSyncStatus);
        return out;
    }

    /**
     * Masks secret fields inside a raw {@code voloRemoteBackup} JSON option value during JSON export.
     */
    public static String maskJsonString(final String rawJson) {
        if (StringUtils.isBlank(rawJson) || rawJson.startsWith("{\"storage\":\"chunks\"")) {
            return "{}";
        }
        try {
            final JSONObject copy = new JSONObject(rawJson);
            final String protocol = copy.optString("protocol", "webdav").strip().toLowerCase(Locale.ROOT);
            final boolean hasSecret = "s3".equals(protocol)
                    ? !copy.optString("s3SecretKey", "").isBlank()
                    : !copy.optString("webdavPassword", "").isBlank();
            copy.remove("secret");
            copy.remove("webdavPassword");
            copy.remove("s3SecretKey");
            copy.put("secretSet", hasSecret);
            return copy.toString();
        } catch (final Exception ignored) {
            return "{}";
        }
    }

    /**
     * Validates and persists remote backup configuration.
     */
    public synchronized JSONObject updateConfig(final JSONObject payload) throws Exception {
        if (payload == null) {
            throw new IllegalArgumentException("异地备份配置不能为空。");
        }
        final JSONObject merged = mergeWithStoredConfig(payload);
        validateMergedConfig(merged, merged.optBoolean("enabled", false));
        persistConfigDocument(merged.toString());
        return getRedactedConfig();
    }

    /**
     * Tests connectivity against the stored configuration or an optional unsaved override payload.
     */
    public JSONObject testConnection(final JSONObject optionalOverridePayload) throws IOException {
        final JSONObject cfg = (optionalOverridePayload == null || optionalOverridePayload.isEmpty())
                ? loadEffectiveConfig()
                : mergeWithStoredConfig(optionalOverridePayload);
        validateMergedConfig(cfg, true);

        final String protocol = cfg.optString("protocol", "webdav");
        if ("s3".equals(protocol)) {
            final S3CompatibleUploader.S3Config s3Cfg = toS3Config(cfg);
            S3CompatibleUploader.verifyConnectivity(s3Cfg);
            return new JSONObject()
                    .put("protocol", "s3")
                    .put("reachable", true);
        }

        testWebDavConnection(cfg);
        return new JSONObject()
                .put("protocol", "webdav")
                .put("reachable", true);
    }

    /**
     * Synchronously uploads a local backup archive to the configured WebDAV or S3 destination.
     */
    public JSONObject syncBackupFile(final Path localZip) throws IOException {
        if (localZip == null || !Files.isRegularFile(localZip)) {
            throw new IOException("待同步的本地备份归档文件不存在。");
        }
        final JSONObject cfg = loadEffectiveConfig();
        validateMergedConfig(cfg, true);
        final String protocol = cfg.optString("protocol", "webdav");
        final String fileName = localZip.getFileName().toString();
        try {
            final String remoteTarget;
            if ("s3".equals(protocol)) {
                final S3CompatibleUploader.S3Config s3Cfg = toS3Config(cfg);
                final String prefix = s3Cfg.subDir();
                final String objectKey = prefix.isEmpty() ? fileName : (prefix + "/" + fileName);
                S3CompatibleUploader.uploadObject(s3Cfg, objectKey, localZip, "application/zip");
                remoteTarget = "s3://" + s3Cfg.bucket() + "/" + objectKey;
            } else {
                remoteTarget = uploadViaWebDav(cfg, localZip);
            }
            final long now = System.currentTimeMillis();
            this.lastSyncTime = now;
            this.lastSyncStatus = "success: " + fileName;
            return new JSONObject()
                    .put("protocol", protocol)
                    .put("fileName", fileName)
                    .put("remoteTarget", remoteTarget)
                    .put("lastSyncTime", now)
                    .put("lastSyncStatus", lastSyncStatus);
        } catch (final IOException | RuntimeException e) {
            this.lastSyncTime = System.currentTimeMillis();
            this.lastSyncStatus = "failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            if (e instanceof IOException io) {
                throw io;
            }
            throw new IOException("异地备份同步失败：" + e.getMessage(), e);
        }
    }

    /**
     * Submits an asynchronous remote backup upload when {@code enabled == true}.
     */
    public void syncBackupAsync(final Path localZip) {
        if (localZip == null || !Files.isRegularFile(localZip)) {
            return;
        }
        final JSONObject cfg = loadEffectiveConfig();
        if (!Option.optBooleanSwitch(cfg, "enabled", false) || SYNC_EXECUTOR.isShutdown()) {
            return;
        }
        SYNC_EXECUTOR.execute(() -> {
            try {
                syncBackupFile(localZip);
                LOGGER.log(Level.INFO, "Off-site backup synced successfully: {0}", localZip.getFileName());
            } catch (final Exception e) {
                LOGGER.log(Level.WARN, "Asynchronous off-site backup sync failed: " + e.getMessage(), e);
            } finally {
                JdbcRepository.dispose();
            }
        });
    }

    private void testWebDavConnection(final JSONObject cfg) throws IOException {
        final URI baseUri = OutboundUrls.requirePublicHttpUri(cfg.optString("webdavUrl", ""), false);
        final String authHeader = buildBasicAuthHeader(cfg.optString("webdavUser", ""), cfg.optString("webdavPassword", ""));

        final HttpRequest.Builder propfind = HttpRequest.newBuilder(baseUri)
                .timeout(CONNECT_TIMEOUT)
                .header("User-Agent", "Volo-BackupSync/3.0")
                .header("Depth", "0")
                .method("PROPFIND", HttpRequest.BodyPublishers.noBody());
        if (authHeader != null) {
            propfind.header("Authorization", authHeader);
        }
        HttpResponse<String> response = sendWebDav(propfind.build());
        int status = response.statusCode();
        if (status == 405 || status == 501) {
            final HttpRequest.Builder options = HttpRequest.newBuilder(baseUri)
                    .timeout(CONNECT_TIMEOUT)
                    .header("User-Agent", "Volo-BackupSync/3.0")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody());
            if (authHeader != null) {
                options.header("Authorization", authHeader);
            }
            response = sendWebDav(options.build());
            status = response.statusCode();
        }
        if (status == 200 || status == 204 || status == 207) {
            return;
        }
        if (status == 401 || status == 403) {
            throw new IOException("WebDAV 认证失败（HTTP " + status + "），请检查用户名或应用专用密码。");
        }
        throw new IOException("WebDAV 连接测试失败（HTTP " + status + "）。");
    }

    private String uploadViaWebDav(final JSONObject cfg, final Path localZip) throws IOException {
        final URI baseUri = OutboundUrls.requirePublicHttpUri(cfg.optString("webdavUrl", ""), false);
        final String authHeader = buildBasicAuthHeader(cfg.optString("webdavUser", ""), cfg.optString("webdavPassword", ""));
        final List<String> dirSegments = splitSafeRelativeSegments(cfg.optString("remoteDir", DEFAULT_REMOTE_DIR));

        // Ensure remote directory hierarchy exists via MKCOL (RFC 4918: 201 Created, or 405/301/200 if already exists).
        final List<String> cumulative = new ArrayList<>(dirSegments.size());
        for (final String seg : dirSegments) {
            cumulative.add(seg);
            final URI colUri = appendWebDavPath(baseUri, cumulative, true);
            final HttpRequest.Builder mkcol = HttpRequest.newBuilder(colUri)
                    .timeout(CONNECT_TIMEOUT)
                    .header("User-Agent", "Volo-BackupSync/3.0")
                    .method("MKCOL", HttpRequest.BodyPublishers.noBody());
            if (authHeader != null) {
                mkcol.header("Authorization", authHeader);
            }
            final int mkStatus = sendWebDav(mkcol.build()).statusCode();
            if (mkStatus == 401 || mkStatus == 403) {
                throw new IOException("WebDAV 目录创建鉴权失败（HTTP " + mkStatus + "）。");
            }
        }

        final List<String> fileSegments = new ArrayList<>(dirSegments);
        fileSegments.add(localZip.getFileName().toString());
        final URI targetFileUri = appendWebDavPath(baseUri, fileSegments, false);
        final HttpRequest.Builder put = HttpRequest.newBuilder(targetFileUri)
                .timeout(UPLOAD_TIMEOUT)
                .header("User-Agent", "Volo-BackupSync/3.0")
                .header("Content-Type", "application/zip")
                .PUT(HttpRequest.BodyPublishers.ofFile(localZip));
        if (authHeader != null) {
            put.header("Authorization", authHeader);
        }
        final int putStatus = sendWebDav(put.build()).statusCode();
        if (putStatus < 200 || putStatus >= 300) {
            throw new IOException("WebDAV 上传归档失败（HTTP " + putStatus + "）。");
        }
        return targetFileUri.toString();
    }

    private static HttpResponse<String> sendWebDav(final HttpRequest request) throws IOException {
        try {
            return WEBDAV_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("WebDAV 请求被中断。", e);
        }
    }

    private static URI appendWebDavPath(final URI baseUri, final List<String> segments, final boolean trailingSlash)
            throws IOException {
        String basePath = baseUri.getRawPath();
        if (basePath == null) {
            basePath = "";
        }
        while (basePath.endsWith("/")) {
            basePath = basePath.substring(0, basePath.length() - 1);
        }
        final StringBuilder sb = new StringBuilder(basePath);
        for (final String seg : segments) {
            sb.append('/').append(S3CompatibleUploader.awsUriEncode(seg, true));
        }
        if (trailingSlash && (sb.length() == 0 || sb.charAt(sb.length() - 1) != '/')) {
            sb.append('/');
        }
        try {
            return new URI(
                    baseUri.getScheme(),
                    null,
                    baseUri.getHost(),
                    baseUri.getPort(),
                    null,
                    null,
                    null).resolve(sb.length() == 0 ? "/" : sb.toString());
        } catch (final URISyntaxException e) {
            throw new IOException("构建 WebDAV 目标路径失败。", e);
        }
    }

    private static String buildBasicAuthHeader(final String user, final String password) {
        if ((user == null || user.isEmpty()) && (password == null || password.isEmpty())) {
            return null;
        }
        final String u = user == null ? "" : user;
        final String p = password == null ? "" : password;
        if (u.indexOf(':') >= 0 || u.indexOf('\r') >= 0 || u.indexOf('\n') >= 0
                || p.indexOf('\r') >= 0 || p.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("WebDAV 认证凭据包含非法字符。");
        }
        final String token = Base64.getEncoder().encodeToString((u + ":" + p).getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    private JSONObject mergeWithStoredConfig(final JSONObject incoming) {
        final JSONObject stored = loadEffectiveConfig();
        final String enabledNormalized = Option.normalizeBooleanSwitch(
                incoming.has("enabled") ? incoming.opt("enabled") : stored.opt("enabled"),
                false,
                "异地备份开关配置不正确。");
        final String pathStyleNormalized = Option.normalizeBooleanSwitch(
                incoming.has("s3PathStyle") ? incoming.opt("s3PathStyle") : stored.opt("s3PathStyle"),
                false,
                "S3 Path-Style 开关配置不正确。");

        final String protocol = normalizeProtocol(
                incoming.has("protocol") ? incoming.optString("protocol") : stored.optString("protocol", "webdav"));

        final String webdavUrl = StringUtils.trimToEmpty(
                incoming.has("webdavUrl") ? incoming.optString("webdavUrl") : stored.optString("webdavUrl"));
        final String webdavUser = StringUtils.trimToEmpty(
                incoming.has("webdavUser") ? incoming.optString("webdavUser") : stored.optString("webdavUser"));
        final String remoteDir = normalizeRelativeDir(
                incoming.has("remoteDir") ? incoming.optString("remoteDir") : stored.optString("remoteDir", DEFAULT_REMOTE_DIR),
                DEFAULT_REMOTE_DIR);

        final String s3Endpoint = StringUtils.trimToEmpty(
                incoming.has("s3Endpoint") ? incoming.optString("s3Endpoint") : stored.optString("s3Endpoint"));
        final String s3AccessKey = StringUtils.trimToEmpty(
                incoming.has("s3AccessKey") ? incoming.optString("s3AccessKey") : stored.optString("s3AccessKey"));
        final String s3Bucket = StringUtils.trimToEmpty(
                incoming.has("s3Bucket") ? incoming.optString("s3Bucket") : stored.optString("s3Bucket"));
        String s3Region = StringUtils.trimToEmpty(
                incoming.has("s3Region") ? incoming.optString("s3Region") : stored.optString("s3Region", "auto"));
        if (s3Region.isEmpty()) {
            s3Region = "auto";
        }
        final String s3Prefix = normalizeRelativeDir(
                incoming.has("s3Prefix") ? incoming.optString("s3Prefix") : stored.optString("s3Prefix", DEFAULT_REMOTE_DIR),
                DEFAULT_REMOTE_DIR);

        final String webdavPassword = resolveSecretField(
                incoming.optString("webdavPassword", ""),
                stored.optString("webdavPassword", ""));
        final String s3SecretKey = resolveSecretField(
                incoming.optString("s3SecretKey", ""),
                stored.optString("s3SecretKey", ""));

        return new JSONObject()
                .put("enabled", "true".equals(enabledNormalized))
                .put("protocol", protocol)
                .put("webdavUrl", webdavUrl)
                .put("webdavUser", webdavUser)
                .put("webdavPassword", webdavPassword)
                .put("remoteDir", remoteDir)
                .put("s3Endpoint", s3Endpoint)
                .put("s3AccessKey", s3AccessKey)
                .put("s3SecretKey", s3SecretKey)
                .put("s3Bucket", s3Bucket)
                .put("s3Region", s3Region)
                .put("s3Prefix", s3Prefix)
                .put("s3PathStyle", "true".equals(pathStyleNormalized));
    }

    private static void validateMergedConfig(final JSONObject cfg, final boolean requireCompleteTarget) {
        final String protocol = cfg.optString("protocol", "webdav");
        if (!ALLOWED_PROTOCOLS.contains(protocol)) {
            throw new IllegalArgumentException("不支持的异地备份协议：" + protocol);
        }
        if ("webdav".equals(protocol)) {
            final String url = cfg.optString("webdavUrl", "");
            if (requireCompleteTarget && url.isBlank()) {
                throw new IllegalArgumentException("启用 WebDAV 异地备份时，服务器地址不能为空。");
            }
            if (!url.isBlank() && !OutboundUrls.isHttpOrHttps(url)) {
                throw new IllegalArgumentException("WebDAV 服务器地址必须为合法的 http:// 或 https:// URL。");
            }
        } else {
            final String endpoint = cfg.optString("s3Endpoint", "");
            final String ak = cfg.optString("s3AccessKey", "");
            final String sk = cfg.optString("s3SecretKey", "");
            final String bucket = cfg.optString("s3Bucket", "");
            if (requireCompleteTarget && (endpoint.isBlank() || ak.isBlank() || sk.isBlank() || bucket.isBlank())) {
                throw new IllegalArgumentException("启用 S3 异地备份时，Endpoint、AccessKey、SecretKey 与 Bucket 不能为空。");
            }
            if (!endpoint.isBlank() && !OutboundUrls.isHttpOrHttps(endpoint)) {
                throw new IllegalArgumentException("S3 Endpoint 必须为合法的 http:// 或 https:// URL。");
            }
        }
    }

    private static S3CompatibleUploader.S3Config toS3Config(final JSONObject cfg) {
        return new S3CompatibleUploader.S3Config(
                cfg.optString("s3Endpoint", ""),
                cfg.optString("s3AccessKey", ""),
                cfg.optString("s3SecretKey", ""),
                cfg.optString("s3Bucket", ""),
                cfg.optString("s3Region", "auto"),
                "",
                cfg.optString("s3Prefix", DEFAULT_REMOTE_DIR),
                Option.optBooleanSwitch(cfg, "s3PathStyle", false));
    }

    private static String resolveSecretField(final String incoming, final String stored) {
        if (CLEAR_SECRET_SENTINEL.equals(incoming)) {
            return "";
        }
        if (incoming == null || incoming.isBlank()) {
            return stored == null ? "" : stored;
        }
        return incoming.strip();
    }

    private static String normalizeProtocol(final String raw) {
        final String cleaned = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (cleaned.isEmpty()) {
            return "webdav";
        }
        if (!ALLOWED_PROTOCOLS.contains(cleaned)) {
            throw new IllegalArgumentException("不支持的异地备份协议：" + raw);
        }
        return cleaned;
    }

    private static String normalizeRelativeDir(final String raw, final String fallback) {
        if (raw == null) {
            return fallback;
        }
        final String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            return "";
        }
        return String.join("/", splitSafeRelativeSegments(trimmed));
    }

    private static List<String> splitSafeRelativeSegments(final String raw) {
        final List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (final String seg : raw.strip().replace('\\', '/').split("/")) {
            final String part = seg.strip();
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part) || part.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("远端目录路径不允许包含 .. 跳转。");
            }
            out.add(part);
        }
        return out;
    }

    private JSONObject loadEffectiveConfig() {
        try {
            String raw = "{}";
            if (optionQueryService != null) {
                raw = OptionDocuments.read(optionQueryService, Option.ID_C_VOLO_REMOTE_BACKUP, "{}");
            } else if (optionRepository != null) {
                final JSONObject opt = optionRepository.get(Option.ID_C_VOLO_REMOTE_BACKUP);
                if (opt != null) {
                    raw = opt.optString(Option.OPTION_VALUE, "{}");
                }
            }
            return StringUtils.isBlank(raw) ? new JSONObject() : new JSONObject(raw);
        } catch (final Exception e) {
            LOGGER.log(Level.WARN, "Failed to read remote backup config, falling back to defaults", e);
            return new JSONObject();
        }
    }

    private void persistConfigDocument(final String jsonValue) throws Exception {
        if (optionMgmtService != null) {
            optionMgmtService.storeDocument(Option.ID_C_VOLO_REMOTE_BACKUP, Option.CATEGORY_C_PREFERENCE, jsonValue);
            return;
        }
        if (optionRepository != null) {
            final JSONObject existing = optionRepository.get(Option.ID_C_VOLO_REMOTE_BACKUP);
            if (existing == null) {
                optionRepository.add(new JSONObject()
                        .put(Keys.OBJECT_ID, Option.ID_C_VOLO_REMOTE_BACKUP)
                        .put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE)
                        .put(Option.OPTION_VALUE, jsonValue));
            } else {
                existing.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
                existing.put(Option.OPTION_VALUE, jsonValue);
                optionRepository.update(Option.ID_C_VOLO_REMOTE_BACKUP, existing);
            }
        }
    }
}
