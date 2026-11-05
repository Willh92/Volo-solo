/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.service;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.apache.logging.log4j.Level;
import org.b3log.latke.Keys;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.repository.Transaction;
import org.b3log.latke.service.annotation.Service;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.model.Option;
import org.b3log.solo.repository.OptionRepository;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Scheduled and on-demand local rolling archive backup service retaining the latest {@code N} copies.
 *
 * @author <a href="https://github.com/Willh92">Willh92</a>
 * @since 3.0.0
 */
@Service
public class LocalRollingBackupService {

    private static final Logger LOGGER = Logger.getLogger(LocalRollingBackupService.class);
    public static final String DEFAULT_BACKUP_DIR = "backups";
    public static final int DEFAULT_KEEP_COUNT = 7;
    private static final Pattern BACKUP_FILE_PATTERN =
            Pattern.compile("^volo-backup-[0-9]{8}-[0-9]{6}(?:-[0-9]{2})?\\.zip$");

    @Inject
    private OptionQueryService optionQueryService;

    @Inject
    private OptionRepository optionRepository;

    @Inject
    private ExportService exportService;

    @Inject
    private WebhookNotifyService webhookNotifyService;

    /**
     * Reads current local rolling backup configuration.
     */
    public JSONObject getConfig() {
        final JSONObject pref = optionQueryService == null ? null : optionQueryService.getPreference();
        final boolean auto = Option.optBooleanSwitch(pref, Option.ID_C_LOCAL_BACKUP_AUTO, false);
        final int keepCount = clampKeepCount(pref == null
                ? DEFAULT_KEEP_COUNT
                : pref.optInt(Option.ID_C_LOCAL_BACKUP_KEEP_COUNT, DEFAULT_KEEP_COUNT));
        final String rawDir = pref == null ? DEFAULT_BACKUP_DIR : pref.optString(Option.ID_C_LOCAL_BACKUP_DIR, DEFAULT_BACKUP_DIR);
        final String dir = StringUtils.isBlank(rawDir) ? DEFAULT_BACKUP_DIR : rawDir.trim();
        return new JSONObject()
                .put(Option.ID_C_LOCAL_BACKUP_AUTO, auto)
                .put(Option.ID_C_LOCAL_BACKUP_KEEP_COUNT, keepCount)
                .put(Option.ID_C_LOCAL_BACKUP_DIR, dir);
    }

    /**
     * Updates local rolling backup settings in preference options.
     */
    public synchronized JSONObject updateConfig(final JSONObject payload) {
        if (payload == null) {
            throw new IllegalArgumentException("备份配置参数不能为空。");
        }
        final String autoNormalized = Option.normalizeBooleanSwitch(
                payload.opt(Option.ID_C_LOCAL_BACKUP_AUTO), false, "自动备份开关配置不正确。");
        final boolean auto = "true".equals(autoNormalized);
        final int keepCount = clampKeepCount(payload.optInt(Option.ID_C_LOCAL_BACKUP_KEEP_COUNT, DEFAULT_KEEP_COUNT));
        String dir = StringUtils.trimToEmpty(payload.optString(Option.ID_C_LOCAL_BACKUP_DIR));
        if (dir.isEmpty()) {
            dir = DEFAULT_BACKUP_DIR;
        }
        resolveSafeBackupDir(dir);

        final Transaction tx = optionRepository.beginTransaction();
        try {
            upsertPreferenceOption(Option.ID_C_LOCAL_BACKUP_AUTO, String.valueOf(auto));
            upsertPreferenceOption(Option.ID_C_LOCAL_BACKUP_KEEP_COUNT, String.valueOf(keepCount));
            upsertPreferenceOption(Option.ID_C_LOCAL_BACKUP_DIR, dir);
            tx.commit();
        } catch (final Exception e) {
            if (tx.isActive()) {
                tx.rollback();
            }
            throw new IllegalStateException("保存本地滚动备份配置失败", e);
        }
        return getConfig();
    }

    /**
     * Invoked by daily cron when {@code localBackupAuto} is enabled.
     */
    public void runScheduledBackupIfEnabled() {
        final JSONObject cfg = getConfig();
        if (!cfg.optBoolean(Option.ID_C_LOCAL_BACKUP_AUTO, false)) {
            return;
        }
        try {
            runBackup(false);
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Scheduled local rolling backup failed", e);
        }
    }

    /**
     * Executes a full JSON + Markdown ZIP backup into the configured directory and prunes older archives.
     */
    public synchronized JSONObject runBackup(final boolean manual) throws IOException {
        final JSONObject cfg = getConfig();
        final String dirStr = cfg.optString(Option.ID_C_LOCAL_BACKUP_DIR, DEFAULT_BACKUP_DIR);
        final int keepCount = clampKeepCount(cfg.optInt(Option.ID_C_LOCAL_BACKUP_KEEP_COUNT, DEFAULT_KEEP_COUNT));
        final Path dir = resolveSafeBackupDir(dirStr);
        Files.createDirectories(dir);
        if (Files.isSymbolicLink(dir)) {
            throw new IllegalArgumentException("备份目录不允许使用符号链接。");
        }

        final long now = System.currentTimeMillis();
        final String stamp = DateFormatUtils.format(new Date(now), "yyyyMMdd-HHmmss");
        String fileName = "volo-backup-" + stamp + ".zip";
        Path target = dir.resolve(fileName);
        int seq = 1;
        while (Files.exists(target) && seq < 99) {
            fileName = String.format(Locale.ROOT, "volo-backup-%s-%02d.zip", stamp, seq++);
            target = dir.resolve(fileName);
        }

        final JSONObject allData = exportService != null ? exportService.getJSONs() : new JSONObject();
        final JSONObject mds = exportService != null ? exportService.exportHexoMDs() : new JSONObject();

        final Path tempTarget = dir.resolve(fileName + ".tmp");
        try {
            try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tempTarget)), StandardCharsets.UTF_8)) {
                zos.putNextEntry(new ZipEntry("data.json"));
                zos.write(allData.toString(2).getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                writeMarkdownSection(zos, "posts", mds.opt("posts"));
                writeMarkdownSection(zos, "drafts", mds.opt("drafts"));
                writeMarkdownSection(zos, "passwords", mds.opt("passwords"));
            }
            Files.move(tempTarget, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tempTarget);
        }

        final int pruned = pruneOldBackups(dir, keepCount);
        final long size = Files.size(target);

        if (webhookNotifyService != null) {
            webhookNotifyService.notifyEvent(
                    "backup.completed",
                    (manual ? "手动" : "定时") + "本地滚动备份已完成：" + fileName,
                    new JSONObject()
                            .put("fileName", fileName)
                            .put("sizeBytes", size)
                            .put("prunedCount", pruned));
        }

        return new JSONObject()
                .put("name", fileName)
                .put("size", size)
                .put("lastModified", now)
                .put("prunedCount", pruned)
                .put("dir", dir.toString());
    }

    /**
     * Lists existing rolling backup archives in the configured directory, newest first.
     */
    public JSONArray listBackups() {
        final JSONArray items = new JSONArray();
        try {
            final Path dir = resolveSafeBackupDir(getConfig().optString(Option.ID_C_LOCAL_BACKUP_DIR, DEFAULT_BACKUP_DIR));
            if (!Files.isDirectory(dir) || Files.isSymbolicLink(dir)) {
                return items;
            }
            final List<Path> files = listMatchingBackupFiles(dir);
            for (final Path file : files) {
                items.put(new JSONObject()
                        .put("name", file.getFileName().toString())
                        .put("size", Files.size(file))
                        .put("lastModified", Files.getLastModifiedTime(file).toMillis()));
            }
        } catch (final Exception e) {
            LOGGER.log(Level.WARN, "Failed to list local rolling backups", e);
        }
        return items;
    }

    /**
     * Resolves a validated backup archive path for safe download or deletion.
     */
    public Path resolveValidatedBackupFile(final String fileName) throws IOException {
        return resolveValidatedBackupFileInDir(
                getConfig().optString(Option.ID_C_LOCAL_BACKUP_DIR, DEFAULT_BACKUP_DIR), fileName);
    }

    public static Path resolveValidatedBackupFileInDir(final String dirStr, final String fileName) throws IOException {
        if (fileName == null || !BACKUP_FILE_PATTERN.matcher(fileName).matches()
                || fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            throw new IllegalArgumentException("非法的备份归档文件名。");
        }
        final Path baseDir = resolveSafeBackupDir(dirStr);
        if (!Files.isDirectory(baseDir) || Files.isSymbolicLink(baseDir)) {
            throw new IOException("备份目录不存在。");
        }
        final Path candidate = baseDir.resolve(fileName).normalize();
        if (!candidate.startsWith(baseDir) || !Files.isRegularFile(candidate) || Files.isSymbolicLink(candidate)) {
            throw new IOException("指定的备份归档不存在。");
        }
        final Path realBase = baseDir.toRealPath();
        final Path realTarget = candidate.toRealPath();
        if (!realTarget.startsWith(realBase)) {
            throw new IllegalArgumentException("备份文件路径越界。");
        }
        return realTarget;
    }

    /**
     * Deletes a single backup archive safely.
     */
    public synchronized boolean deleteBackup(final String fileName) throws IOException {
        final Path file = resolveValidatedBackupFile(fileName);
        return Files.deleteIfExists(file);
    }

    /**
     * Prunes oldest {@code volo-backup-*.zip} files in {@code dir} so at most {@code keepCount} remain.
     */
    public static int pruneOldBackups(final Path dir, final int keepCount) throws IOException {
        final int safeKeep = clampKeepCount(keepCount);
        final List<Path> sortedNewestFirst = listMatchingBackupFiles(dir);
        int pruned = 0;
        for (int i = safeKeep; i < sortedNewestFirst.size(); i++) {
            if (Files.deleteIfExists(sortedNewestFirst.get(i))) {
                pruned++;
            }
        }
        return pruned;
    }

    public static Path resolveSafeBackupDir(final String rawDir) {
        if (rawDir == null || rawDir.isBlank() || rawDir.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("备份目录路径不能为空。");
        }
        final String trimmed = rawDir.strip();
        if (trimmed.length() > 260) {
            throw new IllegalArgumentException("备份目录路径过长。");
        }
        // Reject explicit traversal segments in relative paths
        for (final String part : trimmed.replace('\\', '/').split("/")) {
            if ("..".equals(part)) {
                throw new IllegalArgumentException("备份目录路径不允许包含上级目录跳转（..）。");
            }
        }
        final Path appRoot = Path.of("").toAbsolutePath().normalize();
        final Path resolved = Path.of(trimmed).toAbsolutePath().normalize();
        if (resolved.getParent() == null || resolved.equals(appRoot)) {
            throw new IllegalArgumentException("不允许直接使用磁盘根目录或程序根目录作为备份目录。");
        }
        if (resolved.startsWith(appRoot)) {
            final Path rel = appRoot.relativize(resolved);
            final String topSegment = rel.getNameCount() == 0 ? "" : rel.getName(0).toString().toLowerCase(Locale.ROOT);
            if (SetOfProtectedDirs.NAMES.contains(topSegment)) {
                throw new IllegalArgumentException("不允许使用系统源码或运行目录作为备份目录。");
            }
        }
        if (Files.exists(resolved) && Files.isSymbolicLink(resolved)) {
            throw new IllegalArgumentException("备份目录不允许为符号链接。");
        }
        return resolved;
    }

    public static int clampKeepCount(final int value) {
        if (value < 1) {
            return 1;
        }
        return Math.min(value, 90);
    }

    private static List<Path> listMatchingBackupFiles(final Path dir) throws IOException {
        final List<Path> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !Files.isSymbolicLink(p))
                    .filter(p -> BACKUP_FILE_PATTERN.matcher(p.getFileName().toString()).matches())
                    .forEach(result::add);
        }
        result.sort(Comparator.<Path, String>comparing(p -> p.getFileName().toString()).reversed());
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void writeMarkdownSection(final ZipOutputStream zos, final String folder, final Object rawList)
            throws IOException {
        if (!(rawList instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        int idx = 1;
        for (final Object item : list) {
            if (!(item instanceof JSONObject post)) {
                continue;
            }
            String title = post.optString("title", "untitled").replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
            title = StringUtils.strip(title, "-.");
            if (title.isEmpty()) {
                title = "article-" + idx;
            }
            if (title.length() > 60) {
                title = title.substring(0, 60);
            }
            final String entryName = folder + "/" + String.format(Locale.ROOT, "%04d-", idx++) + title + ".md";
            final String md = "---\n" + post.optString("front", "") + "---\n\n" + post.optString("content", "");
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(md.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }

    private void upsertPreferenceOption(final String id, final String value) throws Exception {
        JSONObject opt = optionRepository.get(id);
        if (opt == null) {
            opt = new JSONObject();
            opt.put(Keys.OBJECT_ID, id);
            opt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE);
            opt.put(Option.OPTION_VALUE, value);
            optionRepository.add(opt);
        } else {
            opt.put(Option.OPTION_VALUE, value);
            optionRepository.update(id, opt);
        }
    }

    private static final class SetOfProtectedDirs {
        static final java.util.Set<String> NAMES = java.util.Set.of(
                "src", "target", ".git", "node_modules", "skins", "js", "scss", "admin");
    }
}
