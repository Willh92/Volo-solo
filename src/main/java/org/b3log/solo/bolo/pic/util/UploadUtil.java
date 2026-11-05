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
import com.qcloud.cos.model.PutObjectResult;
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
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.Level;
import org.b3log.latke.Latkes;
import org.b3log.solo.bolo.pic.PicUploadProcessor;
import org.b3log.solo.bolo.tool.PassSSL;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.util.S3CompatibleUploader;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

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

    public static String upload(String config, File file) throws Exception {
        String result = "";
        String type = config == null ? "" : config.split("<<>>", 2)[0].strip().toLowerCase(Locale.ROOT);
        String localFilePath = file.getAbsolutePath();
        switch (type) {
            case "s3":
                final S3CompatibleUploader.S3Config s3Cfg = S3CompatibleUploader.parseTuChuangConfig(config);
                final Date s3Now = new Date();
                final String s3MonthDir = new SimpleDateFormat("yyyy/MM").format(s3Now);
                final String s3Timestamp = new SimpleDateFormat("yyyyMMddHHmmssSSS").format(s3Now);
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
            case "local":
                String localImagePath = config.split("<<>>")[1];
                File localImageBedDir = new File(localImagePath);
                if (!localImageBedDir.exists()) {
                    localImageBedDir.mkdirs();
                }

                // 组建目录（并发安全防同毫秒覆盖）
                String localExt = "";
                try {
                    final int dotIdx = file.getName().lastIndexOf(".");
                    if (dotIdx >= 0) {
                        localExt = file.getName().substring(dotIdx).replace(" ", "_");
                    }
                } catch (Exception ignored) {
                }
                String localFilename;
                File localNewFile;
                synchronized (LOCAL_NAME_LOCK) {
                    final String localDate = new SimpleDateFormat("yyyyMMddHHmmssSSS").format(new Date());
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
            case "picuang":
                String picuangSite = config.split("<<>>")[1];
                String picuangPassword = "";
                try {
                    picuangPassword = config.split("<<>>")[2];
                } catch (Exception ignored) {
                }
                CloseableHttpClient picuangHttpClient = new PassSSL().createSSLClientDefault();
                try {
                    HttpPost httpPost = new HttpPost(picuangSite + "/upload/auth?password=" + picuangPassword);
                    FileBody bin = new FileBody(file);
                    StringBody comment = new StringBody("file", ContentType.TEXT_PLAIN);
                    HttpEntity reqEntity = MultipartEntityBuilder.create().addPart("file", bin).addPart("comment", comment).build();
                    httpPost.setEntity(reqEntity);
                    try (CloseableHttpResponse response = picuangHttpClient.execute(httpPost)) {
                        if (response.getStatusLine().getStatusCode() == 200) {
                            HttpEntity resEntity = response.getEntity();
                            String str = EntityUtils.toString(resEntity);
                            EntityUtils.consume(resEntity);
                            JSONObject jsonObject = new JSONObject(str);
                            result = picuangSite + jsonObject.get("msg");
                        } else {
                            throw new NullPointerException();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    try {
                        picuangHttpClient.close();
                    } catch (IOException IOE) {
                        IOE.printStackTrace();
                    }
                }
                break;
            case "qiniu":
                Configuration qiNiuConfig = new Configuration(Region.autoRegion());
                UploadManager qiniuUploadManager = new UploadManager(qiNiuConfig);
                String qiniuAccessKey = config.split("<<>>")[1];
                String qiniuSecretKey = config.split("<<>>")[2];
                String qiniuBucket = config.split("<<>>")[3];
                String qiniuDomain = config.split("<<>>")[4];
                String qiniuTreaty = config.split("<<>>")[5];

                Auth qiniuAuth = Auth.create(qiniuAccessKey, qiniuSecretKey);
                String qiniuUpToken = qiniuAuth.uploadToken(qiniuBucket);

                String fileName = file.getName();
                int index = fileName.lastIndexOf(".");
                String extName = fileName.substring(index + 1, fileName.length());
                String mainName = fileName.substring(0, index);

                String key = mainName + "_" + System.currentTimeMillis() + "." + extName;

                try {
                    Response response = qiniuUploadManager.put(localFilePath, key, qiniuUpToken);
                    DefaultPutRet putRet = new Gson().fromJson(response.bodyString(), DefaultPutRet.class);
                    result = qiniuTreaty + "://" + qiniuDomain + "/" + putRet.key;
                } catch (QiniuException e) {
                    throw new NullPointerException();
                }
                break;
            case "aliyun":
                String aliyunAccessKeyID = config.split("<<>>")[1];
                String aliyunAccessKeySecret = config.split("<<>>")[2];
                String aliyunEndPoint = config.split("<<>>")[3];
                String aliyunBucketName = config.split("<<>>")[4];
                String aliyunBucketDomain = config.split("<<>>")[5];
                String aliyunTreaty = config.split("<<>>")[6];
                String aliyunFilename;
                try {
                    String subDir = config.split("<<>>")[7];
                    aliyunFilename = subDir + "/" + RandomStringUtils.randomAlphanumeric(3) + "_" + file.getName().replace(" ", "_");
                } catch (Exception e) {
                    aliyunFilename = RandomStringUtils.randomAlphanumeric(3) + "_" + file.getName().replace(" ", "_");
                }
                OSS aliyunOSSClient = new OSSClientBuilder().build(aliyunEndPoint, aliyunAccessKeyID, aliyunAccessKeySecret);
                PutObjectRequest aliyunPutObjectRequest = new PutObjectRequest(aliyunBucketName, aliyunFilename, file);
                try {
                    aliyunOSSClient.putObject(aliyunPutObjectRequest);
                    result = aliyunTreaty + "://" + aliyunBucketDomain + "/" + aliyunFilename;
                } catch (OSSException | ClientException e) {
                    throw new NullPointerException();
                } finally {
                    aliyunOSSClient.shutdown();
                }
                break;
            case "upyun":
                String upyunZoneName = config.split("<<>>")[1];
                String upyunName = config.split("<<>>")[2];
                String upyunPassword = config.split("<<>>")[3];
                String upyunDomain = config.split("<<>>")[4];
                String upyunTreaty = config.split("<<>>")[5];
                String upyunFilename = RandomStringUtils.randomAlphanumeric(3) + "_" + file.getName().replace(" ", "_");
                RestManager upyunRestManager = new RestManager(upyunZoneName, upyunName, upyunPassword);
                upyunRestManager.setApiDomain(RestManager.ED_AUTO);
                Map<String, String> upyunParams = new HashMap<>();
                // 获取当前日期 LocalDate.now().toString() 2021-05-26 [0]=2021 [1]=05 [2]=26
                String nowDate[] = LocalDate.now().toString().split("-");
                String dateDir = "/" + nowDate[0] + "/" + nowDate[1] + "-" + nowDate[2] + "/";
                if (!upyunRestManager.mkDir(dateDir).isSuccessful()) {
                    LOGGER.log(Level.INFO, "Directory creation failed [path=" + dateDir + "]");
                }
                upyunRestManager.writeFile(dateDir + upyunFilename, file, upyunParams);
                result = upyunTreaty + "://" + upyunDomain + dateDir + upyunFilename;
                break;
            case "tencent":
                String tencentCosSecretId = config.split("<<>>")[1];
                String tencentCosSecretKey = config.split("<<>>")[2];
                String tencentCosRegion = config.split("<<>>")[3];
                String tencentCosBucketName = config.split("<<>>")[4];
                String tencentCosSubDir = config.split("<<>>")[5].replaceAll("^/|/$", "");
                String tencentCosKey;
                if (!tencentCosSubDir.isEmpty()) {
                    tencentCosKey = tencentCosSubDir + "/" + RandomStringUtils.randomAlphanumeric(3) + "_" + file.getName().replace(" ", "_");
                } else {
                    tencentCosKey = RandomStringUtils.randomAlphanumeric(3) + "_" + file.getName().replace(" ", "_");
                }
                String tencentCosDomain = config.split("<<>>")[6];

                COSCredentials tencentCosCred = new BasicCOSCredentials(tencentCosSecretId, tencentCosSecretKey);
                com.qcloud.cos.region.Region tencentRegion = new com.qcloud.cos.region.Region(tencentCosRegion);
                ClientConfig tencentClientConfig = new ClientConfig(tencentRegion);
                COSClient tencentCosClient = new COSClient(tencentCosCred, tencentClientConfig);
                try {
                    com.qcloud.cos.model.PutObjectRequest tencentPutObjectRequest = new com.qcloud.cos.model.PutObjectRequest(tencentCosBucketName, tencentCosKey, file);
                    PutObjectResult tencentPutObjectResult = tencentCosClient.putObject(tencentPutObjectRequest);
                    result = tencentCosDomain + "/" + tencentCosKey;
                } finally {
                    tencentCosClient.shutdown();
                }
                break;
        }
        file.delete();
        return result;
    }
}
