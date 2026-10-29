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
import org.b3log.solo.util.Images;
import org.b3log.solo.util.Solos;
import org.b3log.solo.util.Uploads;
import org.apache.commons.io.FileUtils;

import org.b3log.latke.http.Response;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <h3>bolo-solo</h3>
 * <p>图床上传</p>
 *
 * @author : https://github.com/adlered
 * @date : 2020-03-04 20:50
 **/
@Singleton
public class PicUploadProcessor {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(PicUploadProcessor.class);

    /**
     * Option repository.
     */
    @Inject
    private OptionRepository optionRepository;

    /**
     * 自定义图床上传目录
     *
     * @param context RT
     */
    @RequestProcessing(value = "/pic/upload", method = {HttpMethod.POST})
    public void uploadPicture(final RequestContext context) {
        synchronized (this) {
            if (!Solos.isAdminOrAuthorLoggedIn(context)) {
                context.sendError(401);

                return;
            }

            java.nio.file.Path directory = null;
            List<FileUpload> itemList = java.util.Collections.emptyList();
            Map okPic = new HashMap();
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
            Map map = new HashMap();
            map.put("succMap", okPic);
            map.put("errFiles", errFiles);
            org.b3log.solo.web.JsonResponses.json(context).renderData(map);
            org.b3log.solo.web.JsonResponses.json(context).renderCode(0);
            org.b3log.solo.web.JsonResponses.json(context).renderMsg("");
        }
    }

    /**
     * 检查本地图床是否符合规则
     *
     * @param context RT
     */
    @RequestProcessing(value = "/pic/local/check", method = {HttpMethod.GET})
    public void checkLocalImageBedAvailable(final RequestContext context) {
        if (!Solos.isAdminLoggedIn(context)) {
            context.sendError(401);

            return;
        }

        String path = context.param("path");
        if (path == null || "undefined".equals(path)) {
            org.b3log.solo.web.JsonResponses.json(context).renderCode(500);
            org.b3log.solo.web.JsonResponses.json(context).renderMsg("测试失败！目录格式错误。");

            return;
        }
        File pathFile = new File(path);
        // 检查目录是否存在，如果不存在则创建
        if (!pathFile.exists()) {
            if (!pathFile.mkdirs()) {
                org.b3log.solo.web.JsonResponses.json(context).renderCode(500);
                org.b3log.solo.web.JsonResponses.json(context).renderMsg("创建目录失败！目录格式错误或没有权限。");

                return;
            }
        }
        // 判断目录是否可写
        try {
            File tmpFile = new File(pathFile.getAbsolutePath() + "/" + "temp_bolo_solo.tmp");
            tmpFile.createNewFile();
            tmpFile.delete();

            org.b3log.solo.web.JsonResponses.json(context).renderCode(200);
            org.b3log.solo.web.JsonResponses.json(context).renderMsg("测试成功 :)");

            return;
        } catch (Exception e) {
            org.b3log.solo.web.JsonResponses.json(context).renderCode(500);
            org.b3log.solo.web.JsonResponses.json(context).renderMsg("测试失败！目录没有写入权限。");

            return;
        }
    }

    /**
     * 获取本地图床存储位置的图片，并返回
     *
     * @param context RT
     */
    @RequestProcessing(value = "/image/{imageFilename}", method = HttpMethod.GET)
    public void getLocalImage(final RequestContext context) {
        try {
            final Response response = context.getResponse();
            String config = optionRepository.get(Option.ID_C_TUCHUANG_CONFIG).optString(Option.OPTION_VALUE);
            String type = config.split("<<>>")[0];
            if ("local".equals(type)) {
                String userInputFilename = context.pathVar("imageFilename");
                userInputFilename = URLEncoder.encode(userInputFilename, "UTF-8");
                userInputFilename = userInputFilename.replaceAll("%", "");
                String filePath = config.split("<<>>")[1] + "/" + userInputFilename;
                FileInputStream fileInputStream = new FileInputStream(filePath);
                int fileSize = fileInputStream.available();
                byte[] data = new byte[fileSize];
                fileInputStream.read(data);
                fileInputStream.close();
                String contentType = Files.probeContentType(Paths.get(filePath));
                response.setContentType(contentType);
                response.sendBytes(data);
            }
        } catch (Exception e) {
            context.sendError(404);

            return;
        }
    }
}
