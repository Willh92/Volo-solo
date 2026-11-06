/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Pure-JDK AWS Signature Version 4 ({@code AWS4-HMAC-SHA256}) S3-compatible object storage client
 * supporting AWS S3, Cloudflare R2, MinIO, Alibaba Cloud OSS, Tencent COS, Backblaze B2, and Garage.
 *
 * @author <a href="https://github.com/Willh92">Willh92</a>
 * @since 3.0.0
 */
public final class S3CompatibleUploader {

    public static final String EMPTY_PAYLOAD_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String SERVICE = "s3";
    private static final String TERMINATOR = "aws4_request";
    private static final int MAX_URL_LENGTH = 2048;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final DateTimeFormatter AMZ_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final Pattern DNS_BUCKET_PATTERN =
            Pattern.compile("^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$");
    private static final Pattern IPV4_LITERAL_PATTERN =
            Pattern.compile("^(?:\\d{1,3}\\.){3}\\d{1,3}$");
    private static final HexFormat HEX = HexFormat.of();

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(ProxySelector.of(null))
            .build();

    private S3CompatibleUploader() {
    }

    /**
     * S3-compatible storage configuration.
     */
    public record S3Config(
            String endpoint,
            String accessKey,
            String secretKey,
            String bucket,
            String region,
            String publicDomain,
            String subDir,
            boolean pathStyle) {

        public S3Config {
            endpoint = endpoint == null ? "" : endpoint.strip();
            accessKey = accessKey == null ? "" : accessKey.strip();
            secretKey = secretKey == null ? "" : secretKey.strip();
            bucket = bucket == null ? "" : bucket.strip();
            region = (region == null || region.isBlank()) ? "auto" : region.strip();
            publicDomain = publicDomain == null ? "" : publicDomain.strip();
            subDir = normalizeSubDir(subDir);
        }
    }

    /**
     * Deterministic SigV4 signing output for HTTP execution and offline vector verification.
     */
    public record SignedRequest(
            URI uri,
            String method,
            String canonicalRequest,
            String stringToSign,
            String authorization,
            Map<String, String> headers) {
    }

    /**
     * Parses {@code tuChuangConfig} string in format:
     * {@code s3<<>>{endpoint}<<>>{accessKey}<<>>{secretKey}<<>>{bucket}<<>>{region}<<>>{publicDomain}<<>>{subDir}<<>>{pathStyle}}.
     */
    public static S3Config parseTuChuangConfig(final String rawConfig) {
        if (rawConfig == null || rawConfig.isBlank()) {
            throw new IllegalArgumentException("S3 图床配置不能为空。");
        }
        final String[] parts = rawConfig.split("<<>>", -1);
        if (parts.length < 5 || !"s3".equalsIgnoreCase(parts[0].strip())) {
            throw new IllegalArgumentException("S3 图床配置格式不正确。");
        }
        final String endpoint = parts[1].strip();
        final String accessKey = parts[2].strip();
        final String secretKey = parts[3].strip();
        final String bucket = parts[4].strip();
        if (endpoint.isEmpty() || accessKey.isEmpty() || secretKey.isEmpty() || bucket.isEmpty()) {
            throw new IllegalArgumentException("S3 Endpoint、AccessKey、SecretKey 与 Bucket 不能为空。");
        }
        final String region = parts.length > 5 && !parts[5].isBlank() ? parts[5].strip() : "auto";
        final String publicDomain = parts.length > 6 ? parts[6].strip() : "";
        final String subDir = parts.length > 7 ? normalizeSubDir(parts[7]) : "";
        final boolean pathStyle = parts.length > 8 && "true".equalsIgnoreCase(parts[8].strip());
        return new S3Config(endpoint, accessKey, secretKey, bucket, region, publicDomain, subDir, pathStyle);
    }

    /**
     * Validates an S3 endpoint URL and blocks SSRF targets (loopback, link-local, cloud metadata,
     * unspecified {@code 0.0.0.0}/{@code ::}, and multicast), while permitting public endpoints
     * and RFC1918 LAN addresses for self-hosted MinIO/Garage instances.
     */
    public static URI validateEndpointUri(final String rawEndpoint) throws IOException {
        if (rawEndpoint == null) {
            throw new IOException("S3 Endpoint 不能为空。");
        }
        final String trimmed = rawEndpoint.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_URL_LENGTH || hasControlOrWhitespace(trimmed)) {
            throw new IOException("S3 Endpoint URL 格式不合法。");
        }
        final URI uri;
        try {
            uri = new URI(trimmed);
        } catch (final URISyntaxException e) {
            throw new IOException("S3 Endpoint URL 解析失败。", e);
        }
        final String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IOException("S3 Endpoint 必须包含 http:// 或 https:// 协议头。");
        }
        final String lowerScheme = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(lowerScheme) && !"https".equals(lowerScheme)) {
            throw new IOException("S3 Endpoint 仅支持 http 或 https 协议。");
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IOException("S3 Endpoint 不允许包含认证信息、查询参数或锚点。");
        }
        final String rawHost = uri.getHost();
        validateHostAddresses(rawHost);
        return normalizeBaseUri(uri);
    }

    /**
     * Resolves {@code rawHost} and verifies every resolved address passes {@link #isAllowedS3EndpointAddress(InetAddress)}.
     */
    public static void validateHostAddresses(final String rawHost) throws IOException {
        if (rawHost == null || rawHost.isBlank()) {
            throw new IOException("S3 Endpoint 缺少有效主机名。");
        }
        final String host = stripBrackets(rawHost);
        final InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0) {
            throw new IOException("S3 Endpoint 主机无法解析。");
        }
        for (final InetAddress address : addresses) {
            if (!isAllowedS3EndpointAddress(address)) {
                throw new IOException("S3 Endpoint 目标地址受安全策略限制（禁止回环、链路本地及云元数据地址）。");
            }
        }
    }

    /**
     * Checks whether a resolved {@link InetAddress} is safe for S3 outbound connections.
     */
    public static boolean isAllowedS3EndpointAddress(final InetAddress address) {
        if (address == null) {
            return false;
        }
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        final byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return isAllowedS3Ipv4(bytes);
        }
        if (bytes.length == 16) {
            final int b0 = bytes[0] & 0xff;
            final int b1 = bytes[1] & 0xff;
            final int b2 = bytes[2] & 0xff;
            final int b3 = bytes[3] & 0xff;
            // AWS EC2 IPv6 IMDS fd00:ec2::254
            if (b0 == 0xfd && b1 == 0x00 && b2 == 0x0e && b3 == 0xc2
                    && allZero(bytes, 4, 14) && (bytes[14] & 0xff) == 0x02 && (bytes[15] & 0xff) == 0x54) {
                return false;
            }
            // IPv4-compatible ::/96 and IPv4-mapped ::ffff:0:0/96
            if (allZero(bytes, 0, 10)
                    && ((bytes[10] == 0 && bytes[11] == 0)
                    || ((bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff))) {
                return isAllowedS3Ipv4(new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]});
            }
            // SIIT IPv4-translated ::ffff:0:0:0/96
            if (allZero(bytes, 0, 8)
                    && (bytes[8] & 0xff) == 0xff && (bytes[9] & 0xff) == 0xff
                    && bytes[10] == 0 && bytes[11] == 0) {
                return isAllowedS3Ipv4(new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]});
            }
            // NAT64 well-known prefix 64:ff9b::/96
            if (b0 == 0x00 && b1 == 0x64 && b2 == 0xff && b3 == 0x9b && allZero(bytes, 4, 12)) {
                return isAllowedS3Ipv4(new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]});
            }
            // 6to4 2002::/16
            if (b0 == 0x20 && b1 == 0x02) {
                return isAllowedS3Ipv4(new byte[]{bytes[2], bytes[3], bytes[4], bytes[5]});
            }
        }
        return true;
    }

    private static boolean isAllowedS3Ipv4(final byte[] b) {
        final int b0 = b[0] & 0xff;
        final int b1 = b[1] & 0xff;
        final int b2 = b[2] & 0xff;
        final int b3 = b[3] & 0xff;
        if (b0 == 0 || b0 == 127 || b0 >= 224) {
            return false;
        }
        // Link-local & AWS/GCP/Azure metadata 169.254.0.0/16
        if (b0 == 169 && b1 == 254) {
            return false;
        }
        // Alibaba Cloud ECS metadata 100.100.100.200
        if (b0 == 100 && b1 == 100 && b2 == 100 && b3 == 200) {
            return false;
        }
        return true;
    }

    /**
     * Pure deterministic AWS Signature Version 4 ({@code AWS4-HMAC-SHA256}) request signer for S3.
     */
    public static SignedRequest signRequest(
            final String method,
            final URI uri,
            final String region,
            final String accessKey,
            final String secretKey,
            final String payloadSha256Hex,
            final String amzDate) {
        return signRequest(method, uri, region, accessKey, secretKey, payloadSha256Hex, amzDate, Map.of());
    }

    /**
     * Pure deterministic AWS Signature Version 4 ({@code AWS4-HMAC-SHA256}) request signer for S3 with extra headers.
     */
    public static SignedRequest signRequest(
            final String method,
            final URI uri,
            final String region,
            final String accessKey,
            final String secretKey,
            final String payloadSha256Hex,
            final String amzDate,
            final Map<String, String> extraHeaders) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(accessKey, "accessKey");
        Objects.requireNonNull(secretKey, "secretKey");
        Objects.requireNonNull(payloadSha256Hex, "payloadSha256Hex");
        if (amzDate == null || amzDate.length() < 8) {
            throw new IllegalArgumentException("amzDate must be formatted as yyyyMMdd'T'HHmmss'Z'");
        }

        final String upperMethod = method.strip().toUpperCase(Locale.ROOT);
        final String effectiveRegion = (region == null || region.isBlank()) ? "auto" : region.strip();
        final String dateStamp = amzDate.substring(0, 8);
        final String hostHeader = computeHostHeader(uri);
        final String normalizedPayloadHash = payloadSha256Hex.strip().toLowerCase(Locale.ROOT);

        final TreeMap<String, String> canonicalHeaderMap = new TreeMap<>();
        if (extraHeaders != null) {
            for (final Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    final String lowerKey = entry.getKey().strip().toLowerCase(Locale.ROOT);
                    if (!lowerKey.isEmpty()) {
                        canonicalHeaderMap.put(lowerKey, collapseHeaderWhitespace(entry.getValue()));
                    }
                }
            }
        }
        canonicalHeaderMap.put("host", hostHeader);
        canonicalHeaderMap.put("x-amz-content-sha256", normalizedPayloadHash);
        canonicalHeaderMap.put("x-amz-date", amzDate.strip());

        final StringBuilder canonicalHeaders = new StringBuilder();
        final StringBuilder signedHeadersBuilder = new StringBuilder();
        for (final Map.Entry<String, String> entry : canonicalHeaderMap.entrySet()) {
            canonicalHeaders.append(entry.getKey()).append(':').append(entry.getValue()).append('\n');
            if (signedHeadersBuilder.length() > 0) {
                signedHeadersBuilder.append(';');
            }
            signedHeadersBuilder.append(entry.getKey());
        }
        final String signedHeaders = signedHeadersBuilder.toString();

        final String canonicalUri = buildCanonicalUri(uri.getRawPath());
        final String canonicalQuery = buildCanonicalQueryString(uri.getRawQuery());

        final String canonicalRequest = upperMethod + '\n'
                + canonicalUri + '\n'
                + canonicalQuery + '\n'
                + canonicalHeaders + '\n'
                + signedHeaders + '\n'
                + normalizedPayloadHash;

        final String credentialScope = dateStamp + '/' + effectiveRegion + '/' + SERVICE + '/' + TERMINATOR;
        final String stringToSign = ALGORITHM + '\n'
                + amzDate.strip() + '\n'
                + credentialScope + '\n'
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));

        final byte[] signingKey = deriveSigningKey(secretKey, dateStamp, effectiveRegion, SERVICE);
        final String signature = HEX.formatHex(hmacSha256(signingKey, stringToSign.getBytes(StandardCharsets.UTF_8)));

        final String authorization = ALGORITHM
                + " Credential=" + accessKey.strip() + '/' + credentialScope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;

        final Map<String, String> requestHeaders = new LinkedHashMap<>();
        requestHeaders.put("Host", hostHeader);
        requestHeaders.put("x-amz-date", amzDate.strip());
        requestHeaders.put("x-amz-content-sha256", normalizedPayloadHash);
        if (extraHeaders != null) {
            for (final Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    final String key = entry.getKey().strip();
                    final String lower = key.toLowerCase(Locale.ROOT);
                    if (!key.isEmpty() && !"host".equals(lower)
                            && !"x-amz-date".equals(lower) && !"x-amz-content-sha256".equals(lower)
                            && !"authorization".equals(lower)) {
                        requestHeaders.put(key, collapseHeaderWhitespace(entry.getValue()));
                    }
                }
            }
        }
        requestHeaders.put("Authorization", authorization);

        return new SignedRequest(
                uri,
                upperMethod,
                canonicalRequest,
                stringToSign,
                authorization,
                Collections.unmodifiableMap(requestHeaders));
    }

    /**
     * Builds the target S3 request URI for either path-style or virtual-hosted-style addressing.
     */
    public static URI buildTargetUri(final URI validatedEndpoint, final S3Config cfg,
                                     final String objectKey, final String rawQuery) throws IOException {
        final String bucket = validateBucketName(cfg.bucket());
        final String host = stripBrackets(validatedEndpoint.getHost());
        final boolean usePathStyle = cfg.pathStyle()
                || isIpLiteral(host)
                || !DNS_BUCKET_PATTERN.matcher(bucket).matches();

        final String basePath = trimSlashes(validatedEndpoint.getRawPath());
        final String encodedKey = encodeObjectKeyPath(objectKey);

        final String targetHost;
        final StringBuilder pathBuilder = new StringBuilder();
        if (!basePath.isEmpty()) {
            pathBuilder.append('/').append(basePath);
        }
        if (usePathStyle) {
            targetHost = formatHostForUri(host);
            pathBuilder.append('/').append(awsUriEncode(bucket, true));
        } else {
            targetHost = bucket + '.' + host.toLowerCase(Locale.ROOT);
            validateHostAddresses(targetHost);
        }
        if (!encodedKey.isEmpty()) {
            pathBuilder.append('/').append(encodedKey);
        } else if (pathBuilder.length() == 0) {
            pathBuilder.append('/');
        }

        final String scheme = validatedEndpoint.getScheme().toLowerCase(Locale.ROOT);
        final int port = normalizePort(scheme, validatedEndpoint.getPort());
        final String authority = port == -1 ? targetHost : (targetHost + ":" + port);
        final String querySuffix = (rawQuery == null || rawQuery.isBlank()) ? "" : ("?" + rawQuery);
        try {
            return new URI(scheme + "://" + authority + pathBuilder + querySuffix);
        } catch (final URISyntaxException e) {
            throw new IOException("构建 S3 请求地址失败。", e);
        }
    }

    /**
     * Uploads a local file to S3-compatible object storage via signed HTTP {@code PUT} and returns its public URL.
     */
    public static String uploadObject(final S3Config cfg, final String objectKey,
                                      final Path file, final String contentType) throws IOException {
        validateConfigCredentials(cfg);
        if (file == null || !Files.isRegularFile(file)) {
            throw new IOException("待上传文件不存在。");
        }
        final String normalizedKey = normalizeObjectKey(objectKey);
        if (normalizedKey.isEmpty()) {
            throw new IOException("S3 对象 Key 不能为空。");
        }
        final URI baseUri = validateEndpointUri(cfg.endpoint());
        final URI targetUri = buildTargetUri(baseUri, cfg, normalizedKey, null);
        final String payloadHash = sha256Hex(file);
        final String amzDate = AMZ_DATE_FORMATTER.format(Instant.now());
        final String effectiveContentType = (contentType == null || contentType.isBlank())
                ? "application/octet-stream"
                : contentType.strip();

        final SignedRequest signed = signRequest(
                "PUT",
                targetUri,
                cfg.region(),
                cfg.accessKey(),
                cfg.secretKey(),
                payloadHash,
                amzDate,
                Map.of("Content-Type", effectiveContentType));

        final HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(targetUri)
                .timeout(REQUEST_TIMEOUT)
                .PUT(HttpRequest.BodyPublishers.ofFile(file));
        applyHttpClientHeaders(reqBuilder, signed.headers());

        final BoundedHttpResponse response = sendRequest(reqBuilder.build());
        final int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("S3 上传失败（HTTP " + status + "）。");
        }
        return buildPublicUrl(cfg, normalizedKey, targetUri);
    }

    /**
     * Uploads raw bytes to S3-compatible object storage via signed HTTP {@code PUT}.
     */
    public static void putBytes(final S3Config cfg, final String objectKey,
                                final byte[] bytes, final String contentType) throws IOException {
        validateConfigCredentials(cfg);
        final byte[] payload = bytes == null ? new byte[0] : bytes;
        final String normalizedKey = normalizeObjectKey(objectKey);
        if (normalizedKey.isEmpty()) {
            throw new IOException("S3 对象 Key 不能为空。");
        }
        final URI baseUri = validateEndpointUri(cfg.endpoint());
        final URI targetUri = buildTargetUri(baseUri, cfg, normalizedKey, null);
        final String payloadHash = sha256Hex(payload);
        final String amzDate = AMZ_DATE_FORMATTER.format(Instant.now());
        final String effectiveContentType = (contentType == null || contentType.isBlank())
                ? "application/octet-stream"
                : contentType.strip();

        final SignedRequest signed = signRequest(
                "PUT",
                targetUri,
                cfg.region(),
                cfg.accessKey(),
                cfg.secretKey(),
                payloadHash,
                amzDate,
                Map.of("Content-Type", effectiveContentType));

        final HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(targetUri)
                .timeout(REQUEST_TIMEOUT)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(payload));
        applyHttpClientHeaders(reqBuilder, signed.headers());

        final BoundedHttpResponse response = sendRequest(reqBuilder.build());
        final int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("S3 写入对象失败（HTTP " + status + "）。");
        }
    }

    /**
     * Verifies S3 endpoint reachability, credentials, and bucket access via signed {@code GET ?list-type=2&max-keys=1}
     * (falling back to signed {@code HEAD} on bucket root if {@code ListObjectsV2} is restricted to Put/Head-only IAM policies).
     */
    public static void verifyConnectivity(final S3Config cfg) throws IOException {
        validateConfigCredentials(cfg);
        final URI baseUri = validateEndpointUri(cfg.endpoint());
        final URI listUri = buildTargetUri(baseUri, cfg, "", "list-type=2&max-keys=1");
        final String amzDate = AMZ_DATE_FORMATTER.format(Instant.now());
        final SignedRequest signedList = signRequest(
                "GET",
                listUri,
                cfg.region(),
                cfg.accessKey(),
                cfg.secretKey(),
                EMPTY_PAYLOAD_SHA256,
                amzDate);

        final HttpRequest.Builder listBuilder = HttpRequest.newBuilder(listUri)
                .timeout(CONNECT_TIMEOUT)
                .GET();
        applyHttpClientHeaders(listBuilder, signedList.headers());

        final BoundedHttpResponse response = sendRequest(listBuilder.build());
        final int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return;
        }
        if (status == 403) {
            final URI headUri = buildTargetUri(baseUri, cfg, cfg.subDir(), null);
            final String headDate = AMZ_DATE_FORMATTER.format(Instant.now());
            final SignedRequest signedHead = signRequest(
                    "HEAD",
                    headUri,
                    cfg.region(),
                    cfg.accessKey(),
                    cfg.secretKey(),
                    EMPTY_PAYLOAD_SHA256,
                    headDate);
            final HttpRequest.Builder headBuilder = HttpRequest.newBuilder(headUri)
                    .timeout(CONNECT_TIMEOUT)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody());
            applyHttpClientHeaders(headBuilder, signedHead.headers());
            final BoundedHttpResponse headResp = sendRequest(headBuilder.build());
            final int headStatus = headResp.statusCode();
            if (headStatus == 200 || headStatus == 204 || headStatus == 404) {
                return;
            }
            throw new IOException("S3 鉴权或权限校验失败（HTTP " + headStatus + "），请检查 AccessKey / SecretKey / Region 与存储桶权限。");
        }
        if (status == 401) {
            throw new IOException("S3 鉴权或权限校验失败（HTTP " + status + "），请检查 AccessKey / SecretKey / Region 与存储桶权限。");
        }
        if (status == 404) {
            throw new IOException("S3 存储桶不存在（HTTP 404），请检查 Bucket 名称或开启 Path-Style 路径模式。");
        }
        throw new IOException("S3 连通性测试失败（HTTP " + status + "）。");
    }

    /**
     * Builds the public URL for an uploaded object key.
     */
    public static String buildPublicUrl(final S3Config cfg, final String normalizedKey, final URI requestUri) throws IOException {
        final String encodedKey = encodeObjectKeyPath(normalizedKey);
        final String rawDomain = cfg.publicDomain();
        if (rawDomain != null && !rawDomain.isBlank()) {
            String base = rawDomain.strip();
            while (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            if (!base.regionMatches(true, 0, "http://", 0, 7)
                    && !base.regionMatches(true, 0, "https://", 0, 8)) {
                base = "https://" + base;
            }
            if (hasControlOrWhitespace(base)) {
                throw new IOException("S3 自定义公开域名格式不合法。");
            }
            try {
                final URI parsed = new URI(base);
                final String scheme = parsed.getScheme();
                if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                        || parsed.getHost() == null || parsed.getRawUserInfo() != null) {
                    throw new IOException("S3 自定义公开域名格式不合法。");
                }
            } catch (final URISyntaxException e) {
                throw new IOException("S3 自定义公开域名格式不合法。", e);
            }
            return base + "/" + encodedKey;
        }
        return requestUri.toString();
    }

    private record BoundedHttpResponse(int statusCode, String bodyPreview) {
    }

    private static BoundedHttpResponse sendRequest(final HttpRequest request) throws IOException {
        validateHostAddresses(request.uri().getHost());
        try {
            final HttpResponse<InputStream> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
            final String preview;
            try (final InputStream in = response.body()) {
                if (in != null) {
                    final byte[] buf = in.readNBytes(8192);
                    preview = new String(buf, StandardCharsets.UTF_8);
                } else {
                    preview = "";
                }
            }
            return new BoundedHttpResponse(response.statusCode(), preview);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("S3 请求被中断。", e);
        }
    }

    private static void applyHttpClientHeaders(final HttpRequest.Builder builder, final Map<String, String> headers) {
        for (final Map.Entry<String, String> entry : headers.entrySet()) {
            // JDK HttpClient manages the restricted "Host" header automatically from the request URI.
            if (!"host".equalsIgnoreCase(entry.getKey())) {
                builder.header(entry.getKey(), entry.getValue());
            }
        }
        builder.header("User-Agent", "Volo-S3Uploader/3.0");
    }

    private static void validateConfigCredentials(final S3Config cfg) throws IOException {
        if (cfg == null
                || cfg.endpoint().isBlank()
                || cfg.accessKey().isBlank()
                || cfg.secretKey().isBlank()
                || cfg.bucket().isBlank()) {
            throw new IOException("S3 配置不完整（Endpoint、AccessKey、SecretKey、Bucket 均为必填项）。");
        }
    }

    private static String validateBucketName(final String rawBucket) throws IOException {
        if (rawBucket == null || rawBucket.isBlank()) {
            throw new IOException("S3 Bucket 不能为空。");
        }
        final String bucket = rawBucket.strip();
        if (bucket.length() > 63 || bucket.contains("/") || bucket.contains("\\")
                || bucket.contains("..") || bucket.contains("?") || bucket.contains("#")
                || hasControlOrWhitespace(bucket)) {
            throw new IOException("S3 Bucket 名称不合法。");
        }
        return bucket;
    }

    public static String normalizeSubDir(final String rawSubDir) {
        if (rawSubDir == null || rawSubDir.isBlank()) {
            return "";
        }
        final String normalized = rawSubDir.strip().replace('\\', '/');
        final List<String> cleanSegments = new ArrayList<>();
        for (final String seg : normalized.split("/")) {
            final String trimmed = seg.strip();
            if (trimmed.isEmpty() || ".".equals(trimmed)) {
                continue;
            }
            if ("..".equals(trimmed) || trimmed.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("S3 子目录不允许包含 .. 路径跳转。");
            }
            cleanSegments.add(trimmed);
        }
        return String.join("/", cleanSegments);
    }

    public static String normalizeObjectKey(final String rawKey) throws IOException {
        if (rawKey == null) {
            return "";
        }
        final String slashNormalized = rawKey.strip().replace('\\', '/');
        final List<String> segments = new ArrayList<>();
        for (final String part : slashNormalized.split("/")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part) || part.indexOf('\0') >= 0) {
                throw new IOException("非法的 S3 对象路径。");
            }
            segments.add(part);
        }
        return String.join("/", segments);
    }

    private static String encodeObjectKeyPath(final String rawKey) throws IOException {
        final String normalized = normalizeObjectKey(rawKey);
        if (normalized.isEmpty()) {
            return "";
        }
        final String[] parts = normalized.split("/");
        final StringBuilder sb = new StringBuilder(normalized.length() + 16);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(awsUriEncode(parts[i], true));
        }
        return sb.toString();
    }

    private static String buildCanonicalUri(final String rawPath) {
        if (rawPath == null || rawPath.isEmpty() || "/".equals(rawPath)) {
            return "/";
        }
        final String ensuredLeading = rawPath.startsWith("/") ? rawPath : ("/" + rawPath);
        final String[] rawSegments = ensuredLeading.split("/", -1);
        final StringBuilder sb = new StringBuilder(ensuredLeading.length() + 16);
        for (int i = 0; i < rawSegments.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            final String decoded = rawPercentDecode(rawSegments[i]);
            sb.append(awsUriEncode(decoded, true));
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    private static String buildCanonicalQueryString(final String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        final List<String[]> pairs = new ArrayList<>();
        for (final String param : rawQuery.split("&")) {
            if (param.isEmpty()) {
                continue;
            }
            final int eq = param.indexOf('=');
            final String rawName = eq >= 0 ? param.substring(0, eq) : param;
            final String rawVal = eq >= 0 ? param.substring(eq + 1) : "";
            final String encName = awsUriEncode(rawPercentDecode(rawName), true);
            final String encVal = awsUriEncode(rawPercentDecode(rawVal), true);
            pairs.add(new String[]{encName, encVal});
        }
        pairs.sort((a, b) -> {
            final int cmp = a[0].compareTo(b[0]);
            return cmp != 0 ? cmp : a[1].compareTo(b[1]);
        });
        final StringBuilder sb = new StringBuilder(rawQuery.length() + 16);
        for (int i = 0; i < pairs.size(); i++) {
            if (i > 0) {
                sb.append('&');
            }
            sb.append(pairs.get(i)[0]).append('=').append(pairs.get(i)[1]);
        }
        return sb.toString();
    }

    /**
     * RFC 3986 percent-encoding required by AWS SigV4 (unreserved: {@code A-Z a-z 0-9 - _ . ~}).
     */
    public static String awsUriEncode(final String input, final boolean encodeSlash) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        final byte[] utf8 = input.getBytes(StandardCharsets.UTF_8);
        final StringBuilder out = new StringBuilder(utf8.length * 2);
        for (final byte b : utf8) {
            final int ch = b & 0xff;
            if ((ch >= 'A' && ch <= 'Z')
                    || (ch >= 'a' && ch <= 'z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '_' || ch == '-' || ch == '~' || ch == '.') {
                out.append((char) ch);
            } else if (ch == '/' && !encodeSlash) {
                out.append('/');
            } else {
                out.append('%');
                out.append( Character.toUpperCase(Character.forDigit((ch >>> 4) & 0x0f, 16)) );
                out.append( Character.toUpperCase(Character.forDigit(ch & 0x0f, 16)) );
            }
        }
        return out.toString();
    }

    private static String rawPercentDecode(final String value) {
        if (value == null || value.indexOf('%') < 0) {
            return value == null ? "" : value;
        }
        final byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        final byte[] decoded = new byte[utf8.length];
        int out = 0;
        for (int i = 0; i < utf8.length; i++) {
            final byte b = utf8[i];
            if (b == '%' && i + 2 < utf8.length) {
                final int hi = Character.digit((char) (utf8[i + 1] & 0xff), 16);
                final int lo = Character.digit((char) (utf8[i + 2] & 0xff), 16);
                if (hi >= 0 && lo >= 0) {
                    decoded[out++] = (byte) ((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            decoded[out++] = b;
        }
        return new String(decoded, 0, out, StandardCharsets.UTF_8);
    }

    private static byte[] deriveSigningKey(final String secretKey, final String dateStamp,
                                           final String region, final String service) {
        final byte[] kSecret = ("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8);
        final byte[] kDate = hmacSha256(kSecret, dateStamp.getBytes(StandardCharsets.UTF_8));
        final byte[] kRegion = hmacSha256(kDate, region.getBytes(StandardCharsets.UTF_8));
        final byte[] kService = hmacSha256(kRegion, service.getBytes(StandardCharsets.UTF_8));
        return hmacSha256(kService, TERMINATOR.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmacSha256(final byte[] key, final byte[] data) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public static String sha256Hex(final byte[] bytes) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(md.digest(bytes == null ? new byte[0] : bytes));
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String sha256Hex(final Path file) throws IOException {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                final byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    md.update(buffer, 0, read);
                }
            }
            return HEX.formatHex(md.digest());
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static URI normalizeBaseUri(final URI uri) throws IOException {
        final String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        final String host = formatHostForUri(stripBrackets(uri.getHost()));
        final int port = normalizePort(scheme, uri.getPort());
        final String authority = port == -1 ? host : (host + ":" + port);
        final String basePath = trimSlashes(uri.getRawPath());
        final String pathPart = basePath.isEmpty() ? "" : ("/" + basePath);
        try {
            return new URI(scheme + "://" + authority + pathPart);
        } catch (final URISyntaxException e) {
            throw new IOException("S3 Endpoint 规范化失败。", e);
        }
    }

    private static String computeHostHeader(final URI uri) {
        final String scheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase(Locale.ROOT);
        final String rawHost = uri.getHost();
        if (rawHost == null) {
            throw new IllegalArgumentException("URI missing host: " + uri);
        }
        final String host = formatHostForUri(stripBrackets(rawHost));
        final int port = normalizePort(scheme, uri.getPort());
        return port == -1 ? host : (host + ":" + port);
    }

    private static int normalizePort(final String lowerScheme, final int port) {
        if (port <= 0) {
            return -1;
        }
        if (("http".equals(lowerScheme) && port == 80) || ("https".equals(lowerScheme) && port == 443)) {
            return -1;
        }
        return port;
    }

    private static String formatHostForUri(final String bareHost) {
        final String lower = bareHost.toLowerCase(Locale.ROOT);
        return lower.indexOf(':') >= 0 ? ("[" + lower + "]") : lower;
    }

    private static boolean isIpLiteral(final String bareHost) {
        if (bareHost == null || bareHost.isEmpty()) {
            return false;
        }
        return bareHost.indexOf(':') >= 0 || IPV4_LITERAL_PATTERN.matcher(bareHost).matches();
    }

    private static String stripBrackets(final String host) {
        if (host == null) {
            return "";
        }
        return host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
    }

    private static String trimSlashes(final String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        int start = 0;
        int end = path.length();
        while (start < end && path.charAt(start) == '/') {
            start++;
        }
        while (end > start && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(start, end);
    }

    private static String collapseHeaderWhitespace(final String value) {
        final String trimmed = value == null ? "" : value.strip();
        if (trimmed.indexOf(' ') < 0 && trimmed.indexOf('\t') < 0) {
            return trimmed;
        }
        final StringBuilder sb = new StringBuilder(trimmed.length());
        boolean inSpace = false;
        for (int i = 0; i < trimmed.length(); i++) {
            final char c = trimmed.charAt(i);
            if (c == ' ' || c == '\t') {
                if (!inSpace) {
                    sb.append(' ');
                    inSpace = true;
                }
            } else {
                sb.append(c);
                inSpace = false;
            }
        }
        return sb.toString();
    }

    private static boolean allZero(final byte[] bytes, final int fromInclusive, final int toExclusive) {
        for (int i = fromInclusive; i < toExclusive; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasControlOrWhitespace(final String value) {
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c <= 0x20 || c == 0x7f) {
                return true;
            }
        }
        return false;
    }
}
