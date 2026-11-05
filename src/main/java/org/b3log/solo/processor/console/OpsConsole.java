/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.processor.console;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;
import org.b3log.latke.Latkes;
import org.b3log.latke.http.HttpMethod;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.http.Response;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.ioc.Singleton;
import org.b3log.latke.repository.Query;
import org.b3log.solo.bolo.pic.PicUploadProcessor;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.ArticleRevision;
import org.b3log.solo.model.Category;
import org.b3log.solo.model.Comment;
import org.b3log.solo.model.Follow;
import org.b3log.solo.model.Link;
import org.b3log.solo.model.Option;
import org.b3log.solo.model.Page;
import org.b3log.solo.model.UserExt;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.repository.ArticleRevisionRepository;
import org.b3log.solo.repository.CategoryRepository;
import org.b3log.solo.repository.CommentRepository;
import org.b3log.solo.repository.FollowRepository;
import org.b3log.solo.repository.LinkRepository;
import org.b3log.solo.repository.OptionRepository;
import org.b3log.solo.repository.PageRepository;
import org.b3log.solo.repository.UserRepository;
import org.b3log.solo.service.LocalRollingBackupService;
import org.b3log.solo.service.WebhookNotifyService;
import org.b3log.solo.util.AdminRequests;
import org.b3log.solo.util.QueryResults;
import org.b3log.solo.web.annotation.Before;
import org.b3log.solo.web.annotation.RequestProcessing;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Admin operations console for Local Media Library (#4), Local Rolling Backups (#11), and Generic Webhooks (#11).
 *
 * @author <a href="https://github.com/Willh92">Willh92</a>
 * @since 3.0.0
 */
@Singleton
@Before(ConsoleAdminAuthAdvice.class)
public class OpsConsole {

    private static final Logger LOGGER = Logger.getLogger(OpsConsole.class);

    @Inject
    private OptionRepository optionRepository;

    @Inject
    private ArticleRepository articleRepository;

    @Inject
    private PageRepository pageRepository;

    @Inject
    private LinkRepository linkRepository;

    @Inject
    private UserRepository userRepository;

    @Inject
    private CategoryRepository categoryRepository;

    @Inject
    private FollowRepository followRepository;

    @Inject
    private CommentRepository commentRepository;

    @Inject
    private ArticleRevisionRepository articleRevisionRepository;

    @Inject
    private LocalRollingBackupService localRollingBackupService;

    @Inject
    private WebhookNotifyService webhookNotifyService;

    // =========================================================================
    // 1. Local Media Library (#4)
    // =========================================================================

    @RequestProcessing(value = "/console/media/local", method = HttpMethod.GET)
    public void listLocalMedia(final RequestContext context) {
        context.setHeader("Cache-Control", "no-store");
        try {
            final Path baseDir = resolveLocalImageBedDir();
            if (baseDir == null) {
                context.renderJSON(new JSONObject()
                        .put("sc", true)
                        .put("configured", false)
                        .put("items", new JSONArray())
                        .put("msg", "当前未启用本地图床（可在「偏好设定 → 图床设置」切换为本地图床）。"));
                return;
            }
            final String keyword = StringUtils.trimToEmpty(context.param("q")).toLowerCase(Locale.ROOT);
            final List<JSONObject> list = new ArrayList<>();
            long totalBytes = 0L;
            if (Files.isDirectory(baseDir) && !Files.isSymbolicLink(baseDir)) {
                try (Stream<Path> stream = Files.list(baseDir)) {
                    for (final Path p : stream.toList()) {
                        if (!Files.isRegularFile(p) || Files.isSymbolicLink(p)) {
                            continue;
                        }
                        final String name = p.getFileName().toString();
                        if (PicUploadProcessor.safeImageContentType(name) == null) {
                            continue;
                        }
                        if (!keyword.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(keyword)) {
                            continue;
                        }
                        final long size = Files.size(p);
                        final long modified = Files.getLastModifiedTime(p).toMillis();
                        final String publicUrl = Latkes.getContextPath() + "/image/" + name;
                        totalBytes += size;
                        list.add(new JSONObject()
                                .put("name", name)
                                .put("url", publicUrl)
                                .put("markdown", "![](" + publicUrl + ")")
                                .put("size", size)
                                .put("lastModified", modified));
                    }
                }
            }
            list.sort(Comparator.comparingLong((JSONObject o) -> o.optLong("lastModified", 0L)).reversed());
            context.renderJSON(new JSONObject()
                    .put("sc", true)
                    .put("configured", true)
                    .put("dir", baseDir.toString())
                    .put("totalCount", list.size())
                    .put("totalBytes", totalBytes)
                    .put("items", new JSONArray(list)));
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "List local media failed", e);
            AdminRequests.reject(context, 503, "读取本地图床目录失败。");
        }
    }

    @RequestProcessing(value = "/console/media/local/scan-orphans", method = HttpMethod.POST)
    public void scanOrphanMedia(final RequestContext context) {
        if (!AdminRequests.allowWrite(context)) {
            return;
        }
        try {
            final Path baseDir = resolveLocalImageBedDir();
            if (baseDir == null || !Files.isDirectory(baseDir) || Files.isSymbolicLink(baseDir)) {
                context.renderJSON(new JSONObject()
                        .put("sc", true)
                        .put("orphanNames", new JSONArray())
                        .put("orphanCount", 0)
                        .put("orphanBytes", 0L));
                return;
            }
            final List<Path> candidates = new ArrayList<>();
            try (Stream<Path> stream = Files.list(baseDir)) {
                for (final Path p : stream.toList()) {
                    if (Files.isRegularFile(p) && !Files.isSymbolicLink(p)
                            && PicUploadProcessor.safeImageContentType(p.getFileName().toString()) != null) {
                        candidates.add(p);
                    }
                }
            }
            final String corpus = buildReferenceCorpus();
            final JSONArray orphanNames = new JSONArray();
            long orphanBytes = 0L;
            for (final Path p : candidates) {
                final String name = p.getFileName().toString();
                if (!isMediaReferenced(corpus, name)) {
                    orphanNames.put(name);
                    orphanBytes += Files.size(p);
                }
            }
            context.renderJSON(new JSONObject()
                    .put("sc", true)
                    .put("orphanNames", orphanNames)
                    .put("orphanCount", orphanNames.length())
                    .put("orphanBytes", orphanBytes)
                    .put("msg", orphanNames.isEmpty()
                            ? "扫描完成：所有本地图片均被引用，没有发现孤儿图片。"
                            : ("扫描完成：发现 " + orphanNames.length() + " 张未被引用的孤儿图片。")));
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Scan orphan local media failed", e);
            AdminRequests.reject(context, 503, "扫描孤儿图片失败。");
        }
    }

    @RequestProcessing(value = "/console/media/local/delete", method = HttpMethod.POST)
    public void deleteLocalMedia(final RequestContext context) {
        if (!AdminRequests.allowWrite(context)) {
            return;
        }
        try {
            final Path baseDir = resolveLocalImageBedDir();
            if (baseDir == null || !Files.isDirectory(baseDir) || Files.isSymbolicLink(baseDir)) {
                AdminRequests.reject(context, 400, "本地图床目录未启用或不存在。");
                return;
            }
            final JSONObject req = context.requestJSON();
            final Set<String> targets = new HashSet<>();
            if (req != null) {
                final String single = StringUtils.trimToEmpty(req.optString("name"));
                if (!single.isEmpty()) {
                    targets.add(single);
                }
                final JSONArray arr = req.optJSONArray("names");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        final String n = StringUtils.trimToEmpty(arr.optString(i));
                        if (!n.isEmpty()) {
                            targets.add(n);
                        }
                    }
                }
            }
            if (targets.isEmpty()) {
                AdminRequests.reject(context, 400, "请选择要删除的图片文件。");
                return;
            }
            int deleted = 0;
            for (final String name : targets) {
                final Path validated = resolveSafeMediaFile(baseDir, name);
                if (Files.deleteIfExists(validated)) {
                    deleted++;
                }
            }
            context.renderJSON(new JSONObject()
                    .put("sc", true)
                    .put("deletedCount", deleted)
                    .put("msg", "已安全清理 " + deleted + " 张本地图片。"));
        } catch (final IllegalArgumentException invalid) {
            AdminRequests.reject(context, 400, invalid.getMessage());
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Delete local media failed", e);
            AdminRequests.reject(context, 503, "删除本地图片失败。");
        }
    }

    public static Path resolveSafeMediaFile(final Path baseDir, final String filename) throws IOException {
        if (baseDir == null || filename == null || filename.isBlank()
                || filename.contains("..") || filename.contains("/") || filename.contains("\\")
                || PicUploadProcessor.safeImageContentType(filename) == null) {
            throw new IllegalArgumentException("非法的图片文件名。");
        }
        if (!Files.isDirectory(baseDir) || Files.isSymbolicLink(baseDir)) {
            throw new IllegalArgumentException("图床目录不可用。");
        }
        final Path candidate = baseDir.resolve(filename).normalize();
        if (!candidate.startsWith(baseDir) || !Files.isRegularFile(candidate) || Files.isSymbolicLink(candidate)) {
            throw new IllegalArgumentException("目标图片不存在或类型不受支持。");
        }
        final Path realBase = baseDir.toRealPath();
        final Path realTarget = candidate.toRealPath();
        if (!realTarget.startsWith(realBase)) {
            throw new IllegalArgumentException("图片文件路径越界。");
        }
        return realTarget;
    }

    private Path resolveLocalImageBedDir() throws Exception {
        final JSONObject opt = optionRepository.get(Option.ID_C_TUCHUANG_CONFIG);
        if (opt == null) {
            return null;
        }
        final String config = opt.optString(Option.OPTION_VALUE, "");
        final String[] parts = config.split("<<>>", 3);
        if (parts.length < 2 || !"local".equals(parts[0]) || parts[1].isBlank()) {
            return null;
        }
        return Path.of(parts[1].strip()).toAbsolutePath().normalize();
    }

    private String buildReferenceCorpus() throws Exception {
        final StringBuilder sb = new StringBuilder(65536);
        final JSONArray articles = QueryResults.array(articleRepository.get(new Query()));
        for (int i = 0; i < articles.length(); i++) {
            final JSONObject a = articles.optJSONObject(i);
            if (a != null) {
                sb.append(a.optString(Article.ARTICLE_CONTENT)).append('\n')
                        .append(a.optString(Article.ARTICLE_ABSTRACT)).append('\n')
                        .append(a.optString(Article.ARTICLE_IMG1_URL)).append('\n')
                        .append(a.optString(Article.ARTICLE_AUDIO_URL)).append('\n');
            }
        }
        final JSONArray pages = QueryResults.array(pageRepository.get(new Query()));
        for (int i = 0; i < pages.length(); i++) {
            final JSONObject p = pages.optJSONObject(i);
            if (p != null) {
                sb.append(p.optString(Page.PAGE_ICON)).append('\n')
                        .append(p.optString(Page.PAGE_PERMALINK)).append('\n');
            }
        }
        final JSONArray links = QueryResults.array(linkRepository.get(new Query()));
        for (int i = 0; i < links.length(); i++) {
            final JSONObject l = links.optJSONObject(i);
            if (l != null) {
                sb.append(l.optString(Link.LINK_ICON)).append('\n')
                        .append(l.optString(Link.LINK_ADDRESS)).append('\n');
            }
        }
        final JSONArray users = QueryResults.array(userRepository.get(new Query()));
        for (int i = 0; i < users.length(); i++) {
            final JSONObject u = users.optJSONObject(i);
            if (u != null) {
                sb.append(u.optString(UserExt.USER_AVATAR)).append('\n');
            }
        }
        final JSONArray options = QueryResults.array(optionRepository.get(new Query()));
        for (int i = 0; i < options.length(); i++) {
            final JSONObject o = options.optJSONObject(i);
            if (o != null) {
                sb.append(o.optString(Option.OPTION_VALUE)).append('\n');
            }
        }
        if (categoryRepository != null) {
            final JSONArray categories = QueryResults.array(categoryRepository.get(new Query()));
            for (int i = 0; i < categories.length(); i++) {
                final JSONObject c = categories.optJSONObject(i);
                if (c != null) {
                    sb.append(c.optString(Category.CATEGORY_DESCRIPTION)).append('\n');
                }
            }
        }
        if (followRepository != null) {
            final JSONArray follows = QueryResults.array(followRepository.get(new Query()));
            for (int i = 0; i < follows.length(); i++) {
                final JSONObject f = follows.optJSONObject(i);
                if (f != null) {
                    sb.append(f.optString(Follow.FOLLOW_ICON)).append('\n')
                            .append(f.optString(Follow.FOLLOW_DESCRIPTION)).append('\n');
                }
            }
        }
        if (commentRepository != null) {
            final JSONArray comments = QueryResults.array(commentRepository.get(new Query()));
            for (int i = 0; i < comments.length(); i++) {
                final JSONObject cm = comments.optJSONObject(i);
                if (cm != null) {
                    sb.append(cm.optString(Comment.COMMENT_CONTENT)).append('\n')
                            .append(cm.optString(Comment.COMMENT_THUMBNAIL_URL)).append('\n');
                }
            }
        }
        if (articleRevisionRepository != null) {
            final JSONArray revisions = QueryResults.array(articleRevisionRepository.get(new Query()));
            for (int i = 0; i < revisions.length(); i++) {
                final JSONObject r = revisions.optJSONObject(i);
                if (r != null) {
                    sb.append(r.optString(ArticleRevision.REVISION_CONTENT)).append('\n')
                            .append(r.optString(ArticleRevision.REVISION_ABSTRACT)).append('\n');
                }
            }
        }
        return sb.toString();
    }

    public static boolean isMediaReferenced(final String corpus, final String filename) {
        if (corpus == null || corpus.isEmpty() || filename == null || filename.isEmpty()) {
            return false;
        }
        if (corpus.contains(filename)) {
            return true;
        }
        final String formEncoded = java.net.URLEncoder.encode(filename, java.nio.charset.StandardCharsets.UTF_8);
        if (corpus.contains(formEncoded)) {
            return true;
        }
        final String pathEncoded = formEncoded.replace("+", "%20");
        return corpus.contains(pathEncoded);
    }

    // =========================================================================
    // 2. Local Rolling Backups (#11)
    // =========================================================================

    @RequestProcessing(value = "/console/backups/local", method = HttpMethod.GET)
    public void getLocalBackups(final RequestContext context) {
        context.setHeader("Cache-Control", "no-store");
        try {
            context.renderJSON(new JSONObject()
                    .put("sc", true)
                    .put("config", localRollingBackupService.getConfig())
                    .put("items", localRollingBackupService.listBackups()));
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Get local backups failed", e);
            AdminRequests.reject(context, 503, "读取本地备份列表失败。");
        }
    }

    @RequestProcessing(value = "/console/backups/local/config", method = HttpMethod.POST)
    public void updateLocalBackupConfig(final RequestContext context) {
        if (!AdminRequests.allowWrite(context)) {
            return;
        }
        try {
            final JSONObject saved = localRollingBackupService.updateConfig(context.requestJSON());
            context.renderJSON(new JSONObject()
                    .put("sc", true)
                    .put("config", saved)
                    .put("msg", "本地滚动备份策略已保存。"));
        } catch (final IllegalArgumentException invalid) {
            AdminRequests.reject(context, 400, invalid.getMessage());
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Save local backup config failed", e);
            AdminRequests.reject(context, 503, "保存备份策略失败。");
        }
    }

    @RequestProcessing(value = "/console/backups/local/run", method = HttpMethod.POST)
    public void runLocalBackupNow(final RequestContext context) {
        if (!AdminRequests.allowWrite(context)) {
            return;
        }
        try {
            final JSONObject created = localRollingBackupService.runBackup(true);
            context.renderJSON(new JSONObject()
                    .put("sc", true)
                    .put("backup", created)
                    .put("items", localRollingBackupService.listBackups())
                    .put("msg", "归档完成：" + created.optString("name")));
        } catch (final IllegalArgumentException invalid) {
            AdminRequests.reject(context, 400, invalid.getMessage());
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Run local rolling backup failed", e);
            AdminRequests.reject(context, 503, "执行本地归档备份失败。");
        }
    }

    @RequestProcessing(value = "/console/backups/local/download", method = HttpMethod.GET)
    public void downloadLocalBackup(final RequestContext context) {
        try {
            final String name = StringUtils.trimToEmpty(context.param("name"));
            final Path file = localRollingBackupService.resolveValidatedBackupFile(name);
            final byte[] bytes = Files.readAllBytes(file);
            final Response response = context.getResponse();
            context.setHeader("Cache-Control", "no-store");
            context.setHeader("X-Content-Type-Options", "nosniff");
            context.setHeader("Content-Disposition", "attachment; filename=\"" + file.getFileName().toString() + "\"");
            response.setContentType("application/zip");
            response.sendBytes(bytes);
        } catch (final IllegalArgumentException invalid) {
            AdminRequests.reject(context, 400, invalid.getMessage());
        } catch (final Exception e) {
            LOGGER.log(Level.WARN, "Download local backup failed", e);
            context.sendError(404);
        }
    }

    @RequestProcessing(value = "/console/backups/local/delete", method = HttpMethod.POST)
    public void deleteLocalBackup(final RequestContext context) {
        if (!AdminRequests.allowWrite(context)) {
            return;
        }
        try {
            final JSONObject req = context.requestJSON();
            final String name = req == null ? "" : StringUtils.trimToEmpty(req.optString("name"));
            final boolean removed = localRollingBackupService.deleteBackup(name);
            context.renderJSON(new JSONObject()
                    .put("sc", removed)
                    .put("items", localRollingBackupService.listBackups())
                    .put("msg", removed ? "已删除归档文件：" + name : "归档文件不存在。"));
        } catch (final IllegalArgumentException invalid) {
            AdminRequests.reject(context, 400, invalid.getMessage());
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Delete local backup failed", e);
            AdminRequests.reject(context, 503, "删除归档文件失败。");
        }
    }

    // =========================================================================
    // 3. Generic Webhooks (#11)
    // =========================================================================

    @RequestProcessing(value = "/console/webhooks", method = HttpMethod.GET)
    public void listWebhooks(final RequestContext context) {
        context.setHeader("Cache-Control", "no-store");
        try {
            context.renderJSON(new JSONObject()
                    .put("sc", true)
                    .put("webhooks", webhookNotifyService.listMasked()));
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "List webhooks failed", e);
            AdminRequests.reject(context, 503, "读取 Webhook 配置失败。");
        }
    }

    @RequestProcessing(value = "/console/webhooks", method = HttpMethod.POST)
    public void saveWebhook(final RequestContext context) {
        if (!AdminRequests.allowWrite(context)) {
            return;
        }
        try {
            final JSONObject saved = webhookNotifyService.saveWebhook(context.requestJSON());
            context.renderJSON(new JSONObject()
                    .put("sc", true)
                    .put("webhook", saved)
                    .put("webhooks", webhookNotifyService.listMasked())
                    .put("msg", "Webhook 推送通道已保存。"));
        } catch (final IllegalArgumentException invalid) {
            AdminRequests.reject(context, 400, invalid.getMessage());
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Save webhook failed", e);
            AdminRequests.reject(context, 503, "保存 Webhook 配置失败。");
        }
    }

    @RequestProcessing(value = "/console/webhooks/{id}", method = HttpMethod.DELETE)
    public void deleteWebhook(final RequestContext context) {
        if (!AdminRequests.allowWrite(context)) {
            return;
        }
        try {
            final String id = StringUtils.trimToEmpty(context.pathVar("id"));
            final boolean ok = webhookNotifyService.removeWebhook(id);
            context.renderJSON(new JSONObject()
                    .put("sc", ok)
                    .put("webhooks", webhookNotifyService.listMasked())
                    .put("msg", ok ? "已删除 Webhook 推送通道。" : "未找到对应的推送通道。"));
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Delete webhook failed", e);
            AdminRequests.reject(context, 503, "删除 Webhook 失败。");
        }
    }

    @RequestProcessing(value = "/console/webhooks/{id}/test", method = HttpMethod.POST)
    public void testWebhook(final RequestContext context) {
        if (!AdminRequests.allowWrite(context)) {
            return;
        }
        try {
            final String id = StringUtils.trimToEmpty(context.pathVar("id"));
            final int status = webhookNotifyService.testWebhook(id);
            context.renderJSON(new JSONObject()
                    .put("sc", true)
                    .put("status", status)
                    .put("msg", "测试消息已成功送达（HTTP " + status + "）。"));
        } catch (final IllegalArgumentException invalid) {
            AdminRequests.reject(context, 400, invalid.getMessage());
        } catch (final Exception e) {
            AdminRequests.reject(context, 502, "测试推送失败：" + e.getMessage());
        }
    }
}
