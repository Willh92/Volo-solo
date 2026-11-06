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
package org.b3log.solo.bolo.pic.util;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.PutObjectRequest;
import com.google.gson.Gson;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qiniu.common.QiniuException;
import com.qiniu.http.Response;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.Region;
import com.qiniu.storage.UploadManager;
import com.qiniu.storage.model.DefaultPutRet;
import com.qiniu.util.Auth;
import com.upyun.RestManager;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.Level;
import org.b3log.latke.Latkes;
import org.b3log.solo.bolo.pic.PicUploadProcessor;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.util.S3CompatibleUploader;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * <h3>bolo-solo</h3>
 * <p>图片上传</p>
 *
 * @author : https://github.com/adlered
 * @date : 2020-03-06 14:35
 **/
public class UploadUtil {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(UploadUtil.class);
    private static final Object LOCAL_NAME_LOCK = new Object();
    private static final Object SDK_POOL_LOCK = new Object();

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_REQUEST_TIMEOUT_MS = 30_000;
    private static final int CONNECT_TIMEOUT_SEC = 10;
    private static final int READ_REQUEST_TIMEOUT_SEC = 30;

    private static final RequestConfig PICUANG_REQUEST_CONFIG = RequestConfig.custom()
            .setConnectTimeout(CONNECT_TIMEOUT_MS)
            .setConnectionRequestTimeout(READ_REQUEST_TIMEOUT_MS)
            .setSocketTimeout(READ_REQUEST_TIMEOUT_MS)
            .build();

    private static final DateTimeFormatter S3_MONTH_FMT =
            DateTimeFormatter.ofPattern("yyyy/MM", Locale.ROOT);
    private static final DateTimeFormatter TIMESTAMP_MILLIS_FMT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS", Locale.ROOT);
    private static final DateTimeFormatter DATE_COMPACT_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);
    private static final DateTimeFormatter UPYUN_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy/MM-dd", Locale.ROOT);

    private static CloseableHttpClient sharedPicuangClient;
    private static SdkClientEntry<?> activeSdkEntry;

    private static final class SdkClientEntry<T> {
        final String type;
        final String configKey;
        final T client;
        final Consumer<T> closer;
        int inFlight;
        boolean retired;

        SdkClientEntry(final String type, final String configKey, final T client, final Consumer<T> closer) {
            this.type = type;
            this.configKey = configKey;
            this.client = client;
            this.closer = closer;
        }

        void closeNow() {
            if (closer != null && client != null) {
                try {
                    closer.accept(client);
                } catch (final Exception e) {
                    LOGGER.log(Level.WARN, "Closing cloud upload SDK client [" + type + "] failed", e);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> SdkClientEntry<T> acquireSdkClient(final String type,
                                                          final String configKey,
                                                          final Class<T> clientType,
                                                          final Supplier<T> factory,
                                                          final Consumer<T> closer) {
        SdkClientEntry<?> toCloseOutsideLock = null;
        final SdkClientEntry<T> acquired;
        synchronized (SDK_POOL_LOCK) {
            if (activeSdkEntry != null
                    && !activeSdkEntry.retired
                    && Objects.equals(activeSdkEntry.type, type)
                    && Objects.equals(activeSdkEntry.configKey, configKey)
                    && clientType.isInstance(activeSdkEntry.client)) {
                activeSdkEntry.inFlight++;
                return (SdkClientEntry<T>) activeSdkEntry;
            }
            if (activeSdkEntry != null) {
                activeSdkEntry.retired = true;
                if (activeSdkEntry.inFlight == 0) {
                    toCloseOutsideLock = activeSdkEntry;
                }
                activeSdkEntry = null;
            }
            final T created = factory.get();
            acquired = new SdkClientEntry<>(type, configKey, created, closer);
            acquired.inFlight = 1;
            activeSdkEntry = acquired;
        }
        if (toCloseOutsideLock != null) {
            toCloseOutsideLock.closeNow();
        }
        return acquired;
    }

    private static void releaseSdkClient(final SdkClientEntry<?> entry) {
        if (entry == null) {
            return;
        }
        boolean closeNow = false;
        synchronized (SDK_POOL_LOCK) {
            entry.inFlight = Math.max(0, entry.inFlight - 1);
            if (entry.retired && entry.inFlight == 0) {
                closeNow = true;
            }
        }
        if (closeNow) {
            entry.closeNow();
        }
    }

    private static CloseableHttpClient getPicuangHttpClient() {
        synchronized (SDK_POOL_LOCK) {
            if (sharedPicuangClient == null) {
                sharedPicuangClient = HttpClients.custom()
                        .useSystemProperties()
                        .setDefaultRequestConfig(PICUANG_REQUEST_CONFIG)
                        .disableRedirectHandling()
                        .build();
            }
            return sharedPicuangClient;
        }
    }

    /**
     * Shuts down any cached cloud image-bed SDK client pool and the shared Picuang HTTP client.
     */
    public static void shutdown() {
        SdkClientEntry<?> sdkToClose = null;
        final CloseableHttpClient httpToClose;
        synchronized (SDK_POOL_LOCK) {
            if (activeSdkEntry != null) {
                activeSdkEntry.retired = true;
                if (activeSdkEntry.inFlight == 0) {
                    sdkToClose = activeSdkEntry;
                }
                activeSdkEntry = null;
            }
            httpToClose = sharedPicuangClient;
            sharedPicuangClient = null;
        }
        if (sdkToClose != null) {
            sdkToClose.closeNow();
        }
        if (httpToClose != null) {
            try {
                httpToClose.close();
            } catch (final IOException ignored) {
            }
        }
    }

    public static String upload(final String config, final File file) throws Exception {
        if (file == null || !file.isFile()) {
            throw new IOException("Upload file does not exist");
        }
        String result = "";
        final String[] parts = config == null ? new String[0] : config.split("<<>>", -1);
        final String type = parts.length == 0 ? "" : parts[0].strip().toLowerCase(Locale.ROOT);
        final String localFilePath = file.getAbsolutePath();
        try {
            switch (type) {
                case "s3": {
                    final S3CompatibleUploader.S3Config s3Cfg = S3CompatibleUploader.parseTuChuangConfig(config);
                    final ZonedDateTime s3Now = ZonedDateTime.now();
                    final String s3MonthDir = s3Now.format(S3_MONTH_FMT);
                    final String s3Timestamp = s3Now.format(TIMESTAMP_MILLIS_FMT);
                    String s3RawName = file.getName() == null ? "image" : file.getName().replaceAll("[^A-Za-z0-9._-]", "_");
                    while (s3RawName.contains("..")) {
                        s3RawName = s3RawName.replace("..", "_");
                    }
                    s3RawName = s3RawName.replaceAll("^[^A-Za-z0-9]+", "");
                    if (s3RawName.isEmpty()) {
                        s3RawName = "image";
                    }
                    if (s3RawName.length() > 80) {
                        s3RawName = s3RawName.substring(s3RawName.length() - 80);
                    }
                    final String s3Leaf = s3Timestamp + "_" + s3RawName;
                    final String s3Key = s3Cfg.subDir().isEmpty()
                            ? (s3MonthDir + "/" + s3Leaf)
                            : (s3Cfg.subDir() + "/" + s3MonthDir + "/" + s3Leaf);
                    String s3ContentType = PicUploadProcessor.safeImageContentType(file.getName());
                    if (s3ContentType == null) {
                        s3ContentType = PicUploadProcessor.safeImageContentType("img_" + s3RawName);
                    }
                    // Stream directly from staged file Path without loading full file bytes into heap.
                    result = S3CompatibleUploader.uploadObject(s3Cfg, s3Key, file.toPath(), s3ContentType);
                    break;
                }
                case "local": {
                    requireConfigParts(parts, 2, "local", 1);
                    final String localImagePath = parts[1].strip();
                    final File localImageBedDir = new File(localImagePath);
                    if (!localImageBedDir.exists() && !localImageBedDir.mkdirs() && !localImageBedDir.isDirectory()) {
                        throw new IOException("Failed to create local image directory: " + localImagePath);
                    }

                    // 组建目录（并发安全防同毫秒覆盖）
                    String localExt = "";
                    final String rawName = file.getName();
                    final int dotIdx = rawName.lastIndexOf('.');
                    if (dotIdx >= 0) {
                        localExt = rawName.substring(dotIdx).replace(" ", "_");
                    }
                    String localFilename;
                    File localNewFile;
                    synchronized (LOCAL_NAME_LOCK) {
                        final String localDate = ZonedDateTime.now().format(TIMESTAMP_MILLIS_FMT);
                        localFilename = localDate + localExt;
                        localNewFile = new File(localImageBedDir, localFilename);
                        int seq = 0;
                        while (localNewFile.exists()) {
                            seq++;
                            localFilename = localDate + "_" + seq + localExt;
                            localNewFile = new File(localImageBedDir, localFilename);
                        }
                        localNewFile.createNewFile();
                    }

                    // 传入文件
                    FileUtils.copyFile(file, localNewFile);
                    result = Latkes.getContextPath() + "/image/" + localFilename;
                    LOGGER.log(Level.INFO, "An image has been uploaded to local [path=" + localNewFile.getAbsolutePath() + "]");
                    break;
                }
                case "picuang": {
                    requireConfigParts(parts, 2, "picuang", 1);
                    String picuangSite = parts[1].strip();
                    while (picuangSite.endsWith("/")) {
                        picuangSite = picuangSite.substring(0, picuangSite.length() - 1);
                    }
                    validatePicuangSiteUrl(picuangSite);
                    final String picuangPassword = parts.length > 2 ? parts[2] : "";
                    final String encodedPwd = URLEncoder.encode(picuangPassword, StandardCharsets.UTF_8);

                    try {
                        final CloseableHttpClient picuangHttpClient = getPicuangHttpClient();
                        final HttpPost httpPost = new HttpPost(picuangSite + "/upload/auth?password=" + encodedPwd);
                        httpPost.setConfig(PICUANG_REQUEST_CONFIG);
                        final FileBody bin = new FileBody(file);
                        final StringBody comment = new StringBody("file", ContentType.TEXT_PLAIN);
                        final HttpEntity reqEntity = MultipartEntityBuilder.create()
                                .addPart("file", bin)
                                .addPart("comment", comment)
                                .build();
                        httpPost.setEntity(reqEntity);
                        try (final CloseableHttpResponse response = picuangHttpClient.execute(httpPost)) {
                            final int statusCode = response.getStatusLine().getStatusCode();
                            final HttpEntity resEntity = response.getEntity();
                            String str = "";
                            if (resEntity != null) {
                                try (final InputStream in = resEntity.getContent()) {
                                    if (in != null) {
                                        str = new String(in.readNBytes(65536), StandardCharsets.UTF_8);
                                    }
                                } finally {
                                    EntityUtils.consumeQuietly(resEntity);
                                }
                            }
                            if (statusCode != 200) {
                                LOGGER.log(Level.ERROR, "Picuang upload failed with HTTP status " + statusCode);
                                throw new IOException("Picuang upload failed with HTTP status " + statusCode);
                            }
                            final JSONObject jsonObject = new JSONObject(str);
                            final String msgPath = jsonObject.optString("msg", "").strip();
                            if (msgPath.isEmpty()) {
                                LOGGER.log(Level.ERROR, "Picuang upload response missing msg path");
                                throw new IOException("Picuang upload response missing msg path");
                            }
                            result = picuangSite + (msgPath.startsWith("/") ? msgPath : "/" + msgPath);
                        }
                    } catch (final Exception e) {
                        LOGGER.log(Level.ERROR, "Picuang image upload failed", e);
                        throw e;
                    }
                    break;
                }
                case "qiniu": {
                    requireConfigParts(parts, 6, "qiniu", 1, 2, 3, 4, 5);
                    final String qiniuAccessKey = parts[1].strip();
                    final String qiniuSecretKey = parts[2].strip();
                    final String qiniuBucket = parts[3].strip();
                    final String qiniuDomain = parts[4].strip();
                    final String qiniuTreaty = parts[5].strip();

                    final SdkClientEntry<UploadManager> entry = acquireSdkClient(
                            "qiniu",
                            config,
                            UploadManager.class,
                            () -> {
                                final Configuration qiNiuConfig = new Configuration(Region.autoRegion());
                                qiNiuConfig.connectTimeout = CONNECT_TIMEOUT_SEC;
                                qiNiuConfig.readTimeout = READ_REQUEST_TIMEOUT_SEC;
                                qiNiuConfig.writeTimeout = READ_REQUEST_TIMEOUT_SEC;
                                return new UploadManager(qiNiuConfig);
                            },
                            null);
                    try {
                        final Auth qiniuAuth = Auth.create(qiniuAccessKey, qiniuSecretKey);
                        final String qiniuUpToken = qiniuAuth.uploadToken(qiniuBucket);

                        final String fileName = file.getName();
                        final int index = fileName.lastIndexOf('.');
                        final String key;
                        if (index > 0 && index < fileName.length() - 1) {
                            final String mainName = fileName.substring(0, index);
                            final String extName = fileName.substring(index + 1);
                            key = mainName + "_" + System.currentTimeMillis() + "." + extName;
                        } else {
                            String base = index == 0
                                    ? fileName.substring(1)
                                    : (index == fileName.length() - 1 ? fileName.substring(0, index) : fileName);
                            if (base.isEmpty()) {
                                base = DATE_COMPACT_FMT.format(LocalDate.now());
                            }
                            key = base + "_" + System.currentTimeMillis();
                        }

                        Response response = null;
                        try {
                            response = entry.client.put(localFilePath, key, qiniuUpToken);
                            if (response == null || !response.isOK()) {
                                final int code = response == null ? -1 : response.statusCode;
                                LOGGER.log(Level.ERROR, "Qiniu upload failed with status " + code);
                                throw new IOException("Qiniu upload failed with status " + code);
                            }
                            final DefaultPutRet putRet = new Gson().fromJson(response.bodyString(), DefaultPutRet.class);
                            if (putRet == null || putRet.key == null || putRet.key.isBlank()) {
                                throw new IOException("Qiniu upload returned empty object key");
                            }
                            result = qiniuTreaty + "://" + qiniuDomain + "/" + putRet.key;
                        } catch (final QiniuException e) {
                            if (e.response != null) {
                                e.response.close();
                            }
                            LOGGER.log(Level.ERROR, "Qiniu image upload failed", e);
                            throw new IOException("Qiniu image upload failed", e);
                        } finally {
                            if (response != null) {
                                response.close();
                            }
                        }
                    } finally {
                        releaseSdkClient(entry);
                    }
                    break;
                }
                case "aliyun": {
                    requireConfigParts(parts, 7, "aliyun", 1, 2, 3, 4, 5, 6);
                    final String aliyunAccessKeyID = parts[1].strip();
                    final String aliyunAccessKeySecret = parts[2].strip();
                    final String aliyunEndPoint = parts[3].strip();
                    final String aliyunBucketName = parts[4].strip();
                    final String aliyunBucketDomain = parts[5].strip();
                    final String aliyunTreaty = parts[6].strip();
                    final String subDir = parts.length > 7 ? parts[7].strip().replaceAll("^/+|/+$", "") : "";
                    final String leaf = RandomStringUtils.randomAlphanumeric(3) + "_" + file.getName().replace(" ", "_");
                    final String aliyunFilename = subDir.isEmpty() ? leaf : (subDir + "/" + leaf);

                    final SdkClientEntry<OSS> entry = acquireSdkClient(
                            "aliyun",
                            config,
                            OSS.class,
                            () -> {
                                final ClientBuilderConfiguration conf = new ClientBuilderConfiguration();
                                conf.setConnectionTimeout(CONNECT_TIMEOUT_MS);
                                conf.setSocketTimeout(READ_REQUEST_TIMEOUT_MS);
                                conf.setConnectionRequestTimeout(READ_REQUEST_TIMEOUT_MS);
                                conf.setRequestTimeoutEnabled(true);
                                conf.setRequestTimeout(READ_REQUEST_TIMEOUT_MS);
                                return new OSSClientBuilder().build(
                                        aliyunEndPoint, aliyunAccessKeyID, aliyunAccessKeySecret, conf);
                            },
                            OSS::shutdown);
                    final PutObjectRequest aliyunPutObjectRequest = new PutObjectRequest(aliyunBucketName, aliyunFilename, file);
                    try {
                        entry.client.putObject(aliyunPutObjectRequest);
                        result = aliyunTreaty + "://" + aliyunBucketDomain + "/" + aliyunFilename;
                    } catch (final OSSException | ClientException e) {
                        LOGGER.log(Level.ERROR, "Aliyun OSS image upload failed", e);
                        throw new IOException("Aliyun OSS image upload failed", e);
                    } finally {
                        releaseSdkClient(entry);
                    }
                    break;
                }
                case "upyun": {
                    requireConfigParts(parts, 6, "upyun", 1, 2, 3, 4, 5);
                    final String upyunZoneName = parts[1].strip();
                    final String upyunName = parts[2].strip();
                    final String upyunPassword = parts[3].strip();
                    final String upyunDomain = parts[4].strip();
                    final String upyunTreaty = parts[5].strip();
                    final String upyunFilename = RandomStringUtils.randomAlphanumeric(3) + "_" + file.getName().replace(" ", "_");

                    final SdkClientEntry<RestManager> entry = acquireSdkClient(
                            "upyun",
                            config,
                            RestManager.class,
                            () -> {
                                final RestManager mgr = new RestManager(upyunZoneName, upyunName, upyunPassword);
                                mgr.setApiDomain(RestManager.ED_AUTO);
                                mgr.setTimeout(READ_REQUEST_TIMEOUT_SEC);
                                return mgr;
                            },
                            null);
                    try {
                        final Map<String, String> upyunParams = new HashMap<>();
                        final String dateDir = "/" + LocalDate.now().format(UPYUN_DATE_FMT) + "/";
                        try (final okhttp3.Response mkResp = entry.client.mkDir(dateDir)) {
                            if (mkResp == null || !mkResp.isSuccessful()) {
                                LOGGER.log(Level.INFO, "Directory creation failed [path=" + dateDir + "]");
                            }
                        }
                        try (final okhttp3.Response writeResp = entry.client.writeFile(dateDir + upyunFilename, file, upyunParams)) {
                            if (writeResp == null || !writeResp.isSuccessful()) {
                                final int code = writeResp == null ? -1 : writeResp.code();
                                LOGGER.log(Level.ERROR, "UpYun image upload failed [path=" + dateDir + upyunFilename + ", status=" + code + "]");
                                throw new IOException("UpYun image upload failed with HTTP status " + code);
                            }
                        }
                        result = upyunTreaty + "://" + upyunDomain + dateDir + upyunFilename;
                    } finally {
                        releaseSdkClient(entry);
                    }
                    break;
                }
                case "tencent": {
                    requireConfigParts(parts, 7, "tencent", 1, 2, 3, 4, 6);
                    final String tencentCosSecretId = parts[1].strip();
                    final String tencentCosSecretKey = parts[2].strip();
                    final String tencentCosRegion = parts[3].strip();
                    final String tencentCosBucketName = parts[4].strip();
                    final String tencentCosSubDir = parts[5].strip().replaceAll("^/+|/+$", "");
                    final String leaf = RandomStringUtils.randomAlphanumeric(3) + "_" + file.getName().replace(" ", "_");
                    final String tencentCosKey = tencentCosSubDir.isEmpty() ? leaf : (tencentCosSubDir + "/" + leaf);
                    String tencentCosDomain = parts[6].strip();
                    while (tencentCosDomain.endsWith("/")) {
                        tencentCosDomain = tencentCosDomain.substring(0, tencentCosDomain.length() - 1);
                    }

                    final SdkClientEntry<COSClient> entry = acquireSdkClient(
                            "tencent",
                            config,
                            COSClient.class,
                            () -> {
                                final COSCredentials tencentCosCred =
                                        new BasicCOSCredentials(tencentCosSecretId, tencentCosSecretKey);
                                final com.qcloud.cos.region.Region tencentRegion =
                                        new com.qcloud.cos.region.Region(tencentCosRegion);
                                final ClientConfig tencentClientConfig = new ClientConfig(tencentRegion);
                                tencentClientConfig.setConnectionTimeout(CONNECT_TIMEOUT_MS);
                                tencentClientConfig.setSocketTimeout(READ_REQUEST_TIMEOUT_MS);
                                tencentClientConfig.setConnectionRequestTimeout(READ_REQUEST_TIMEOUT_MS);
                                return new COSClient(tencentCosCred, tencentClientConfig);
                            },
                            COSClient::shutdown);
                    try {
                        final com.qcloud.cos.model.PutObjectRequest tencentPutObjectRequest =
                                new com.qcloud.cos.model.PutObjectRequest(tencentCosBucketName, tencentCosKey, file);
                        entry.client.putObject(tencentPutObjectRequest);
                        result = tencentCosDomain + "/" + tencentCosKey;
                    } catch (final Exception e) {
                        LOGGER.log(Level.ERROR, "Tencent COS image upload failed", e);
                        throw e;
                    } finally {
                        releaseSdkClient(entry);
                    }
                    break;
                }
                default:
                    LOGGER.log(Level.WARN, "Unsupported image bed provider type [" + type + "]");
                    break;
            }
            return result;
        } finally {
            if (file.exists()) {
                file.delete();
            }
        }
    }

    private static void requireConfigParts(final String[] parts,
                                           final int minLength,
                                           final String provider,
                                           final int... nonBlankIndices) {
        if (parts == null || parts.length < minLength) {
            final String msg = "Invalid [" + provider + "] upload configuration: expected at least "
                    + minLength + " fields, got " + (parts == null ? 0 : parts.length);
            LOGGER.log(Level.ERROR, msg);
            throw new IllegalArgumentException(msg);
        }
        for (final int idx : nonBlankIndices) {
            if (idx < 0 || idx >= parts.length || parts[idx] == null || parts[idx].isBlank()) {
                final String msg = "Invalid [" + provider + "] upload configuration: required field at index "
                        + idx + " is blank";
                LOGGER.log(Level.ERROR, msg);
                throw new IllegalArgumentException(msg);
            }
        }
    }

    private static void validatePicuangSiteUrl(final String siteUrl) throws IOException {
        if (siteUrl == null || siteUrl.isBlank()) {
            throw new IOException("Picuang endpoint URL cannot be blank");
        }
        final URI uri;
        try {
            uri = new URI(siteUrl);
        } catch (final URISyntaxException e) {
            throw new IOException("Invalid Picuang endpoint URL", e);
        }
        final String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new IOException("Picuang endpoint must use http or https");
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IOException("Picuang endpoint must not contain userInfo, query, or fragment");
        }
        S3CompatibleUploader.validateHostAddresses(uri.getHost());
    }
}
