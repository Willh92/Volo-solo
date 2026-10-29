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
package org.b3log.solo.bolo.tool;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.apache.logging.log4j.Level;
import org.b3log.latke.ioc.BeanManager;
import org.b3log.latke.repository.Query;
import org.b3log.latke.repository.Transaction;
import org.b3log.latke.repository.jdbc.JdbcRepository;
import org.b3log.solo.bolo.pic.util.UploadUtil;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Option;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.repository.OptionRepository;
import org.b3log.solo.util.Uploads;
import org.json.JSONObject;

/** Runs one image migration at a time without retaining a database connection during downloads. */
public class PBThread {
    private static final Logger LOGGER = Logger.getLogger(PBThread.class);
    private static final Pattern IMAGE = Pattern.compile("(?<=\\]\\()https://(?:img\\.hacpai\\.com|b3logfile\\.com)/[^\\s)]+(?=\\))");
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile String status = "空闲";

    public String getStatus() { return status; }

    public boolean start() {
        if (!running.compareAndSet(false, true)) return false;
        status = "运行中";
        try {
            final Thread worker = new Thread(this::convert, "volo-image-migration");
            worker.setDaemon(true);
            worker.start();
            return true;
        } catch (RuntimeException exception) {
            running.set(false);
            status = "启动失败，请重试";
            throw exception;
        }
    }

    private void convert() {
        int failures = 0;
        try {
            final BeanManager beans = BeanManager.getInstance();
            final ArticleRepository articles = beans.getReference(ArticleRepository.class);
            final OptionRepository options = beans.getReference(OptionRepository.class);
            final List<JSONObject> records = articles.getList(new Query());
            final JSONObject option = options.get(Option.ID_C_TUCHUANG_CONFIG);
            final String config = option == null ? "hacpai" : option.optString(Option.OPTION_VALUE);
            JdbcRepository.dispose();
            if ("hacpai".equalsIgnoreCase(config)) {
                status = "未开始：请先选择其他图床";
                return;
            }
            for (JSONObject article : records) {
                final String original = article.optString(Article.ARTICLE_CONTENT);
                String content = original;
                final List<String> urls = IMAGE.matcher(original).results().map(match -> match.group()).distinct().toList();
                for (String source : urls) {
                    try {
                        final File temporary = download(source);
                        try {
                            final String uploaded = UploadUtil.upload(config, temporary);
                            if (uploaded == null || uploaded.isBlank()) throw new IOException("Empty upload result");
                            content = content.replace(source, uploaded);
                        } finally {
                            Files.deleteIfExists(temporary.toPath());
                            JdbcRepository.dispose();
                        }
                    } catch (Exception exception) {
                        failures++;
                        status = "运行中，部分图片处理失败，请查看日志";
                        LOGGER.log(Level.WARN, "An image could not be migrated; its original URL is retained");
                    }
                    // Respect the upstream image service request limit without holding a JDBC connection.
                    Thread.sleep(60_000);
                }
                if (content.equals(original)) continue;
                final Transaction transaction = articles.beginTransaction();
                try {
                    final String id = article.optString("oId");
                    final JSONObject current = articles.get(id);
                    if (current == null || !original.equals(current.optString(Article.ARTICLE_CONTENT))) {
                        failures++;
                        continue; // An author edited the article while downloads were in progress.
                    }
                    current.put(Article.ARTICLE_CONTENT, content);
                    articles.update(id, current, Article.ARTICLE_CONTENT);
                    transaction.commit();
                } finally {
                    if (transaction.isActive()) transaction.rollback();
                    JdbcRepository.dispose();
                }
            }
            status = failures == 0 ? "空闲" : "已完成，部分图片未转换，请查看日志";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            status = "已停止，可重新开始";
        } catch (Exception exception) {
            status = "转换失败，请查看日志";
            LOGGER.log(Level.ERROR, "Image migration failed");
        } finally {
            try { JdbcRepository.dispose(); } finally { running.set(false); }
        }
    }

    private static File download(final String source) throws IOException {
        final URI uri = URI.create(source);
        if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1
                || !List.of("img.hacpai.com", "b3logfile.com").contains(uri.getHost())) {
            throw new IOException("Unsupported image host");
        }
        final HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent", "Volo image migration");
        File temporary = null;
        try {
            if (connection.getResponseCode() != 200 || connection.getContentLengthLong() > Uploads.MAX_FILE_SIZE) {
                throw new IOException("Image download failed or exceeds size limit");
            }
            String extension = uri.getPath().toLowerCase().replaceFirst(".*\\.", "");
            if (!List.of("png", "jpg", "jpeg", "gif", "webp", "bmp").contains(extension)) extension = "jpg";
            temporary = Files.createTempFile("volo-migration-", "." + extension).toFile();
            try (InputStream input = connection.getInputStream(); OutputStream output = new FileOutputStream(temporary)) {
                long size = 0;
                final byte[] buffer = new byte[8192];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    size += length;
                    if (size > Uploads.MAX_FILE_SIZE) throw new IOException("Image exceeds size limit");
                    output.write(buffer, 0, length);
                }
            }
            Uploads.validateImage(temporary);
            return temporary;
        } catch (IOException | RuntimeException exception) {
            if (temporary != null) Files.deleteIfExists(temporary.toPath());
            throw exception;
        } finally {
            connection.disconnect();
        }
    }
}
