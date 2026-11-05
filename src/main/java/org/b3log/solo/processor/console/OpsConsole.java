/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.processor.console;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.http.FileUpload;
import org.b3log.latke.http.HttpMethod;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.http.Response;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.ioc.Singleton;
import org.b3log.latke.repository.AbstractRepository;
import org.b3log.latke.repository.Query;
import org.b3log.latke.repository.SortDirection;
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
import org.b3log.solo.service.BackupRestoreService;
import org.b3log.solo.service.ImportService;
import org.b3log.solo.service.LocalRollingBackupService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.service.PreferenceMgmtService;
import org.b3log.solo.service.RemoteBackupSyncService;
import org.b3log.solo.service.WebhookNotifyService;
import org.b3log.solo.util.AdminRequests;
import org.b3log.solo.util.QueryResults;
import org.b3log.solo.util.S3CompatibleUploader;
import org.b3log.solo.util.Uploads;
import org.b3log.solo.web.annotation.Before;
import org.b3log.solo.web.annotation.RequestProcessing;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final int ORPHAN_SCAN_PAGE_SIZE = 200;

    @Inject
    private OptionQueryService optionQueryService;

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
    private BackupRestoreService backupRestoreService;

    @Inject
    private RemoteBackupSyncService remoteBackupSyncService;

    @Inject
    private ImportService importService;

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
                        final BasicFileAttributes attrs;
                        try {
                            attrs = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        } catch (final IOException ignored) {
                            continue;
                        }
                        if (!attrs.isRegularFile() || attrs.isSymbolicLink()) {
                            continue;
                        }
                        final String name = p.getFileName().toString();
                        if (PicUploadProcessor.safeImageContentType(name) == null) {
                            continue;
                        }
                        if (!keyword.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(keyword)) {
                            continue;
                        }
                        final long size = attrs.size();
                        final long modified = attrs.lastModifiedTime().toMillis();
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
            final Map<String, Long> candidateSizes = new LinkedHashMap<>();
            try (Stream<Path> stream = Files.list(baseDir)) {
                for (final Path p : stream.toList()) {
                    final BasicFileAttributes attrs;
                    try {
                        attrs = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    } catch (final IOException ignored) {
                        continue;
                    }
                    if (!attrs.isRegularFile() || attrs.isSymbolicLink()) {
                        continue;
                    }
                    final String name = p.getFileName().toString();
                    if (PicUploadProcessor.safeImageContentType(name) != null) {
                        candidateSizes.put(name, attrs.size());
                    }
                }
            }
            final Set<String> orphanSet = findOrphanMediaNames(candidateSizes.keySet());
            final JSONArray orphanNames = new JSONArray();
            long orphanBytes = 0L;
            for (final String name : orphanSet) {
                orphanNames.put(name);
                orphanBytes += candidateSizes.getOrDefault(name, 0L);
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
            if (deleted > 0) {
                PicUploadProcessor.clearCache();
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

    private JSONObject getOptionById(final String optionId) throws Exception {
        if (optionQueryService != null) {
            return optionQueryService.getOptionById(optionId);
        }
        return optionRepository == null ? null : optionRepository.get(optionId);
    }

    private Path resolveLocalImageBedDir() throws Exception {
        final JSONObject opt = getOptionById(Option.ID_C_TUCHUANG_CONFIG);
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

    /**
     * Scans all 9 content/metadata repositories in bounded 200-row pages (selecting only text/URL columns),
     * eliminating referenced image candidates on the fly and short-circuiting as soon as none remain.
     */
    private Set<String> findOrphanMediaNames(final Set<String> candidateNames) throws Exception {
        if (candidateNames == null || candidateNames.isEmpty()) {
            return Set.of();
        }
        final Map<String, String[]> remaining = new LinkedHashMap<>(candidateNames.size() * 2);
        for (final String name : candidateNames) {
            if (name != null && !name.isEmpty()) {
                remaining.put(name, buildMatchTokens(name));
            }
        }
        if (remaining.isEmpty()) {
            return Set.of();
        }

        eliminateReferencedInRepo(remaining, articleRepository,
                Article.ARTICLE_CONTENT, Article.ARTICLE_ABSTRACT, Article.ARTICLE_IMG1_URL, Article.ARTICLE_AUDIO_URL);
        eliminateReferencedInRepo(remaining, pageRepository,
                Page.PAGE_ICON, Page.PAGE_PERMALINK);
        eliminateReferencedInRepo(remaining, optionRepository,
                Option.OPTION_VALUE);
        eliminateReferencedInRepo(remaining, userRepository,
                UserExt.USER_AVATAR);
        eliminateReferencedInRepo(remaining, linkRepository,
                Link.LINK_ICON, Link.LINK_ADDRESS);
        eliminateReferencedInRepo(remaining, categoryRepository,
                Category.CATEGORY_DESCRIPTION);
        eliminateReferencedInRepo(remaining, followRepository,
                Follow.FOLLOW_ICON, Follow.FOLLOW_DESCRIPTION);
        eliminateReferencedInRepo(remaining, commentRepository,
                Comment.COMMENT_CONTENT, Comment.COMMENT_THUMBNAIL_URL);
        eliminateReferencedInRepo(remaining, articleRevisionRepository,
                ArticleRevision.REVISION_CONTENT, ArticleRevision.REVISION_ABSTRACT);

        return remaining.keySet();
    }

    private static void eliminateReferencedInRepo(final Map<String, String[]> remaining,
                                                  final AbstractRepository repository,
                                                  final String... textColumns) throws Exception {
        if (remaining.isEmpty() || repository == null || textColumns == null || textColumns.length == 0) {
            return;
        }


        final Set<String> seenIds = new HashSet<>();
        String page1FirstSig = null;

        for (int page = 1; !remaining.isEmpty(); page++) {
            final Query query = new Query()
                    .select(Keys.OBJECT_ID, textColumns)
                    .addSort(Keys.OBJECT_ID, SortDirection.ASCENDING)
                    .setPage(page, ORPHAN_SCAN_PAGE_SIZE)
                    .setPageCount(1);
            final JSONArray rows = QueryResults.array(repository.get(query));
            if (rows.isEmpty()) {
                break;
            }
            final JSONObject firstObj = rows.optJSONObject(0);
            final String firstSig = firstObj == null
                    ? ""
                    : (firstObj.has(Keys.OBJECT_ID) ? firstObj.optString(Keys.OBJECT_ID) : firstObj.toString());
            if (page == 1) {
                page1FirstSig = firstSig;
            } else if (!firstSig.isEmpty() && firstSig.equals(page1FirstSig)) {
                break;
            }

            int newRowsInPage = 0;
            for (int i = 0; i < rows.length(); i++) {
                final JSONObject row = rows.optJSONObject(i);
                if (row == null) {
                    continue;
                }
                final String oId = row.optString(Keys.OBJECT_ID, "");
                if (!oId.isEmpty() && !seenIds.add(oId)) {
                    continue;
                }
                newRowsInPage++;

                for (final String col : textColumns) {
                    final String text = row.optString(col, "");
                    if (text.isEmpty()) {
                        continue;
                    }
                    final Iterator<Map.Entry<String, String[]>> it = remaining.entrySet().iterator();
                    while (it.hasNext()) {
                        final String[] tokens = it.next().getValue();
                        for (final String token : tokens) {
                            if (text.contains(token)) {
                                it.remove();
                                break;
                            }
                        }
                    }
                    if (remaining.isEmpty()) {
                        return;
                    }
                }
            }
            if (rows.length() < ORPHAN_SCAN_PAGE_SIZE || (page > 1 && newRowsInPage == 0)) {
                break;
            }
        }
    }

    private static String[] buildMatchTokens(final String filename) {
        final String formEncoded = URLEncoder.encode(filename, StandardCharsets.UTF_8);
        final String pathEncoded = formEncoded.replace("+", "%20");
        if (filename.equals(formEncoded)) {
            return new String[]{filename};
        }
        if (formEncoded.equals(pathEncoded)) {
            return new String[]{filename, formEncoded};
        }
        return new String[]{filename, formEncoded, pathEncoded};
    }

    public static boolean isMediaReferenced(final String corpus, final String filename) {
        if (corpus == null || corpus.isEmpty() || filename == null || filename.isEmpty()) {
            return false;
        }
        if (corpus.contains(filename)) {
            return true;
        }
        final String formEncoded = URLEncoder.encode(filename, StandardCharsets.UTF_8);
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

    // =========================================================================
    // 4. S3 Image Bed Connectivity Check
    // =========================================================================

    @RequestProcessing(value = "/console/s3/check", method = HttpMethod.POST)
    public void checkS3ImageBed(final RequestContext context) {
        if (!AdminRequests.allowWrite(context)) {
            return;
        }
        try {
            final JSONObject req = tryRequestJson(context);
            final JSONObject storedOpt = getOptionById(Option.ID_C_TUCHUANG_CONFIG);
            final String storedRaw = storedOpt == null ? "" : storedOpt.optString(Option.OPTION_VALUE, "");

            final String rawTuple = req != null && req.has("config")
                    ? req.optString("config", "")
                    : StringUtils.trimToEmpty(context.param("config"));

            final S3CompatibleUploader.S3Config s3Cfg;
            if (!rawTuple.isEmpty()) {
                final String merged = PreferenceMgmtService.mergeTuChuangConfig(rawTuple, storedRaw);
                s3Cfg = S3CompatibleUploader.parseTuChuangConfig(merged);
            } else {
                final String endpoint = optField(req, context, "endpoint");
                final String accessKey = optField(req, context, "accessKey");
                String secretKey = optField(req, context, "secretKey");
                final String bucket = optField(req, context, "bucket");
                final String region = optField(req, context, "region");
                final String publicDomain = optField(req, context, "publicDomain");
                final String subDir = optField(req, context, "subDir");
                final Object rawPathStyle = (req != null && req.has("pathStyle"))
                        ? req.opt("pathStyle")
                        : context.param("pathStyle");
                final boolean pathStyle = "true".equals(Option.normalizeBooleanSwitch(
                        rawPathStyle, false, "S3 Path-Style 开关配置不正确。"));

                if (PreferenceMgmtService.CLEAR_SECRET_SENTINEL.equals(secretKey)) {
                    secretKey = "";
                } else if (secretKey.isEmpty() && !storedRaw.isEmpty()) {
                    final String[] parts = storedRaw.split("<<>>", -1);
                    if (parts.length > 3 && "s3".equalsIgnoreCase(parts[0].strip())) {
                        secretKey = parts[3].strip();
                    }
                }
                s3Cfg = new S3CompatibleUploader.S3Config(
                        endpoint, accessKey, secretKey, bucket, region, publicDomain, subDir, pathStyle);
            }

            S3CompatibleUploader.verifyConnectivity(s3Cfg);
            context.renderJSON(new JSONObject()
                    .put("sc", true)
                    .put("code", 200)
                    .put("msg", "S3 连通性测试成功 :)"));
        } catch (final IllegalArgumentException invalid) {
            AdminRequests.reject(context, 400, invalid.getMessage());
        } catch (final Exception e) {
            AdminRequests.reject(context, 502, e.getMessage() == null ? "S3 连接测试失败。" : e.getMessage());
        }
    }

    // =========================================================================
    // 5. Backup Archive Inspection & Transactional Restore
    // =========================================================================

    @RequestProcessing(value = "/console/backups/local/inspect", method = HttpMethod.POST)
    public void inspectLocalOrUploadedBackup(final RequestContext context) {
        if (!AdminRequests.allowWrite(context)) {
            return;
        }
        Path tempDir = null;
        try {
            final Path targetZip;
            if (isMultipart(context)) {
                tempDir = Files.createTempDirectory("volo-backup-inspect-");
                targetZip = stageUploadedBackupZip(context, tempDir);
            } else {
                final JSONObject req = tryRequestJson(context);
                String fileName = optField(req, context, "fileName");
                if (fileName.isEmpty()) {
                    fileName = optField(req, context, "name");
                }
                targetZip = localRollingBackupService.resolveValidatedBackupFile(fileName);
            }
            final JSONObject summary = backupRestoreService.inspectBackupZip(targetZip);
            context.renderJSON(new JSONObject()
                    .put("sc", true)
                    .put("summary", summary)
                    .put("sourceName", summary.optString("sourceName"))
                    .put("version", summary.optString("version"))
                    .put("counts", summary.optJSONObject("counts"))
                    .put("hasAdminUser", summary.optBoolean("hasAdminUser")));
        } catch (final IllegalArgumentException invalid) {
            AdminRequests.reject(context, 400, invalid.getMessage());
        } catch (final Exception e) {
            LOGGER.log(Level.WARN, "Inspect backup archive failed", e);
            AdminRequests.reject(context, 400, e.getMessage() == null ? "解析备份归档失败。" : e.getMessage());
        } finally {
            if (tempDir != null) {
                FileUtils.deleteQuietly(tempDir.toFile());
            }
        }
    }

    @RequestProcessing(value = "/console/backups/local/restore", method = HttpMethod.POST)
    public void restoreFromLocalOrUploadedBackup(final RequestContext context) {
        if (!AdminRequests.allowWrite(context)) {
            return;
        }
        Path tempDir = null;
        try {
            final Path targetZip;
            final boolean overwriteAuth;
            if (isMultipart(context)) {
                tempDir = Files.createTempDirectory("volo-backup-restore-");
                targetZip = stageUploadedBackupZip(context, tempDir);
                overwriteAuth = "true".equals(Option.normalizeBooleanSwitch(
                        context.param("overwriteAuth"), false, "覆盖账号凭证开关配置不正确。"));
            } else {
                final JSONObject req = tryRequestJson(context);
                String fileName = optField(req, context, "fileName");
                if (fileName.isEmpty()) {
                    fileName = optField(req, context, "name");
                }
                targetZip = localRollingBackupService.resolveValidatedBackupFile(fileName);
                final Object rawOverwrite = (req != null && req.has("overwriteAuth"))
                        ? req.opt("overwriteAuth")
                        : context.param("overwriteAuth");
                overwriteAuth = "true".equals(Option.normalizeBooleanSwitch(
                        rawOverwrite, false, "覆盖账号凭证开关配置不正确。"));
            }

            final JSONObject result = backupRestoreService.restoreFromBackupZip(targetZip, overwriteAuth);
            context.renderJSON(new JSONObject()
                    .put("sc", true)
                    .put("result", result)
                    .put("preRestoreBackup", result.optString("preRestoreBackup"))
                    .put("reloginRequired", result.optBoolean("reloginRequired"))
                    .put("restoredCounts", result.optJSONObject("restoredCounts"))
                    .put("items", localRollingBackupService.listBackups())
                    .put("msg", "全量数据恢复完成（已自动保存回滚快照：" + result.optString("preRestoreBackup") + "）。"));
        } catch (final IllegalArgumentException invalid) {
            AdminRequests.reject(context, 400, invalid.getMessage());
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Restore from backup archive failed", e);
            AdminRequests.reject(context, 503, "执行数据恢复失败：" + (e.getMessage() == null ? "内部错误" : e.getMessage()));
        } finally {
            if (tempDir != null) {
                FileUtils.deleteQuietly(tempDir.toFile());
            }
        }
    }

    // =========================================================================
    // 6. Off-Site Backup Sync (WebDAV & S3)
    // =========================================================================

    @RequestProcessing(value = "/console/backups/remote", method = HttpMethod.GET)
    public void getRemoteBackupConfig(final RequestContext context) {
        context.setHeader("Cache-Control", "no-store");
        try {
            context.renderJSON(new JSONObject()
                    .put("sc", true)
                    .put("config", remoteBackupSyncService.getRedactedConfig()));
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Get remote backup config failed", e);
            AdminRequests.reject(context, 503, "读取异地备份配置失败。");
        }
    }

    @RequestProcessing(value = "/console/backups/remote/config", method = HttpMethod.POST)
    public void updateRemoteBackupConfig(final RequestContext context) {
        if (!AdminRequests.allowWrite(context)) {
            return;
        }
        try {
            final JSONObject saved = remoteBackupSyncService.updateConfig(tryRequestJson(context));
            context.renderJSON(new JSONObject()
                    .put("sc", true)
                    .put("config", saved)
                    .put("msg", "异地备份同步配置已保存。"));
        } catch (final IllegalArgumentException invalid) {
            AdminRequests.reject(context, 400, invalid.getMessage());
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Save remote backup config failed", e);
            AdminRequests.reject(context, 503, "保存异地备份配置失败。");
        }
    }

    @RequestProcessing(value = "/console/backups/remote/test", method = HttpMethod.POST)
    public void testRemoteBackupConnection(final RequestContext context) {
        if (!AdminRequests.allowWrite(context)) {
            return;
        }
        try {
            final JSONObject result = remoteBackupSyncService.testConnection(tryRequestJson(context));
            context.renderJSON(new JSONObject()
                    .put("sc", true)
                    .put("result", result)
                    .put("msg", "异地备份远端连接测试成功。"));
        } catch (final IllegalArgumentException invalid) {
            AdminRequests.reject(context, 400, invalid.getMessage());
        } catch (final Exception e) {
            AdminRequests.reject(context, 502, e.getMessage() == null ? "远端连接测试失败。" : e.getMessage());
        }
    }

    @RequestProcessing(value = "/console/backups/remote/sync", method = HttpMethod.POST)
    public void syncRemoteBackupNow(final RequestContext context) {
        if (!AdminRequests.allowWrite(context)) {
            return;
        }
        try {
            final JSONObject req = tryRequestJson(context);
            String targetName = optField(req, context, "fileName");
            if (targetName.isEmpty()) {
                targetName = optField(req, context, "name");
            }
            if (targetName.isEmpty()) {
                final JSONArray existing = localRollingBackupService.listBackups();
                if (!existing.isEmpty()) {
                    targetName = existing.getJSONObject(0).optString("name");
                } else {
                    targetName = localRollingBackupService.runBackup(true).optString("name");
                }
            }
            final Path localZip = localRollingBackupService.resolveValidatedBackupFile(targetName);
            final JSONObject syncResult = remoteBackupSyncService.syncBackupFile(localZip);
            context.renderJSON(new JSONObject()
                    .put("sc", true)
                    .put("result", syncResult)
                    .put("config", remoteBackupSyncService.getRedactedConfig())
                    .put("msg", "异地备份同步成功：" + targetName));
        } catch (final IllegalArgumentException invalid) {
            AdminRequests.reject(context, 400, invalid.getMessage());
        } catch (final Exception e) {
            LOGGER.log(Level.WARN, "Manual remote backup sync failed", e);
            AdminRequests.reject(context, 502, e.getMessage() == null ? "异地备份同步失败。" : e.getMessage());
        }
    }

    // =========================================================================
    // 7. Batch Markdown / ZIP Drag-Drop Import
    // =========================================================================

    @RequestProcessing(value = "/console/import/batch", method = HttpMethod.POST)
    public void importBatchMarkdownOrZip(final RequestContext context) {
        if (!AdminRequests.allowWrite(context)) {
            return;
        }
        Path tempDir = null;
        try {
            final String rawPublish = context.param("publishImmediately");
            final String rawStatus = StringUtils.trimToEmpty(context.param("publishStatus"));
            final boolean defaultPublish = "published".equalsIgnoreCase(rawStatus);
            final boolean publishImmediately = "true".equals(Option.normalizeBooleanSwitch(
                    rawPublish, defaultPublish, "直接发布开关配置不正确。"));
            final String rawSkip = context.param("skipDuplicatePermalink");
            final String rawDupMode = StringUtils.trimToEmpty(context.param("duplicateMode"));
            final boolean defaultSkip = !"copy".equalsIgnoreCase(rawDupMode);
            final boolean skipDuplicatePermalink = "true".equals(Option.normalizeBooleanSwitch(
                    rawSkip, defaultSkip, "跳过重复链接开关配置不正确。"));

            tempDir = Files.createTempDirectory("volo-console-batch-import-");
            final List<FileUpload> uploads = collectMultipartUploads(context, 100L * 1024L * 1024L, 200L * 1024L * 1024L);
            if (uploads.isEmpty()) {
                AdminRequests.reject(context, 400, "请选择至少一个 .md 或 .zip 文件进行导入。");
                return;
            }

            final List<Path> stagedPaths = new ArrayList<>(uploads.size());
            int seq = 0;
            for (final FileUpload upload : uploads) {
                final String rawName = upload.getFilename();
                final String ext = Uploads.extension(rawName, "md", "markdown", "zip");
                final Path slotDir = Files.createDirectories(tempDir.resolve("item-" + (++seq)));
                final String safeFileName = sanitizeBatchUploadFilename(rawName, ext, seq);
                final Path target = slotDir.resolve(safeFileName).normalize();
                if (!target.startsWith(slotDir)) {
                    throw new IllegalArgumentException("上传文件名不合法。");
                }
                Files.write(target, upload.getData());
                stagedPaths.add(target);
            }

            final JSONObject summary = importService.importBatchFiles(
                    stagedPaths, publishImmediately, skipDuplicatePermalink);
            final int imported = summary.optInt("imported");
            final int skipped = summary.optInt("skipped");
            final int failed = summary.optInt("failed");
            context.renderJSON(new JSONObject()
                    .put("sc", true)
                    .put("result", summary)
                    .put("imported", imported)
                    .put("skipped", skipped)
                    .put("failed", failed)
                    .put("errors", summary.optJSONArray("errors"))
                    .put("msg", String.format(Locale.ROOT,
                            "批量导入完成：成功 %d 篇，跳过 %d 篇，失败 %d 篇。", imported, skipped, failed)));
        } catch (final IllegalArgumentException invalid) {
            AdminRequests.reject(context, 400, invalid.getMessage());
        } catch (final IOException ioErr) {
            AdminRequests.reject(context, 400, ioErr.getMessage() == null ? "上传文件校验失败。" : ioErr.getMessage());
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Batch markdown/zip import failed", e);
            AdminRequests.reject(context, 503, "批量导入失败。");
        } finally {
            if (tempDir != null) {
                FileUtils.deleteQuietly(tempDir.toFile());
            }
        }
    }

    private static boolean isMultipart(final RequestContext context) {
        final String ct = context.header("Content-Type");
        return ct != null && ct.toLowerCase(Locale.ROOT).startsWith("multipart/");
    }

    private static JSONObject tryRequestJson(final RequestContext context) {
        if (isMultipart(context)) {
            return null;
        }
        try {
            return context.requestJSON();
        } catch (final Exception ignored) {
            return null;
        }
    }

    private static String optField(final JSONObject req, final RequestContext context, final String key) {
        if (req != null && req.has(key) && !req.isNull(key)) {
            return StringUtils.trimToEmpty(String.valueOf(req.opt(key)));
        }
        return StringUtils.trimToEmpty(context.param(key));
    }

    private static Path stageUploadedBackupZip(final RequestContext context, final Path tempDir) throws IOException {
        final List<FileUpload> uploads = collectMultipartUploads(context, 128L * 1024L * 1024L, 128L * 1024L * 1024L);
        if (uploads.isEmpty()) {
            throw new IllegalArgumentException("请上传 .zip 备份归档文件或指定本地归档文件名。");
        }
        final FileUpload item = uploads.get(0);
        Uploads.extension(item.getFilename(), "zip");
        final String safeName = sanitizeBatchUploadFilename(item.getFilename(), "zip", 1);
        final Path dest = tempDir.resolve(safeName).normalize();
        if (!dest.startsWith(tempDir)) {
            throw new IllegalArgumentException("非法的备份压缩包文件名。");
        }
        Files.write(dest, item.getData());
        return dest;
    }

    private static List<FileUpload> collectMultipartUploads(final RequestContext context,
                                                            final long maxPerFileBytes,
                                                            final long maxTotalBytes) throws IOException {
        final List<FileUpload> files = new ArrayList<>();
        for (final String field : List.of("file", "file[]", "files", "files[]", "backup")) {
            final List<FileUpload> part = context.getRequest().getFileUploads(field);
            if (part != null) {
                files.addAll(part);
            }
        }
        if (files.size() > 200) {
            throw new IllegalArgumentException("单次上传文件数量不能超过 200 个。");
        }
        long total = 0L;
        for (final FileUpload f : files) {
            final byte[] data = f.getData();
            final long len = data == null ? 0L : data.length;
            if (len > maxPerFileBytes) {
                throw new IllegalArgumentException("单个上传文件大小超出限制。");
            }
            total += len;
            if (total > maxTotalBytes) {
                throw new IllegalArgumentException("上传文件总大小超出限制。");
            }
        }
        return files;
    }

    private static String sanitizeBatchUploadFilename(final String rawName, final String ext, final int seq) {
        final int dot = rawName.lastIndexOf('.');
        String stem = dot > 0 ? rawName.substring(0, dot) : rawName;
        stem = stem.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "_").strip();
        while (stem.contains("..")) {
            stem = stem.replace("..", "_");
        }
        stem = StringUtils.strip(stem, ". ");
        if (stem.isEmpty()) {
            stem = "import-" + seq;
        }
        if (stem.length() > 100) {
            stem = stem.substring(0, 100);
        }
        return stem + "." + ext;
    }
}
