/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.service;

import org.apache.logging.log4j.Level;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.ioc.BeanManager;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.model.Plugin;
import org.b3log.latke.model.Role;
import org.b3log.latke.model.User;
import org.b3log.latke.repository.AbstractRepository;
import org.b3log.latke.repository.Query;
import org.b3log.latke.repository.Transaction;
import org.b3log.latke.service.annotation.Service;
import org.b3log.solo.SoloApplication;
import org.b3log.solo.bolo.prop.MailService;
import org.b3log.solo.bolo.waf.WAF;
import org.b3log.solo.cache.ArticleCache;
import org.b3log.solo.cache.CommentCache;
import org.b3log.solo.cache.FollowArticleCache;
import org.b3log.solo.cache.OptionCache;
import org.b3log.solo.cache.PageCache;
import org.b3log.solo.cache.StatisticCache;
import org.b3log.solo.cache.UserCache;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.model.ArchiveDate;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Category;
import org.b3log.solo.model.Comment;
import org.b3log.solo.model.Follow;
import org.b3log.solo.model.Link;
import org.b3log.solo.model.Option;
import org.b3log.solo.model.Page;
import org.b3log.solo.model.Tag;
import org.b3log.solo.model.UserExt;
import org.b3log.solo.repository.ArchiveDateArticleRepository;
import org.b3log.solo.repository.ArchiveDateRepository;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.repository.ArticleRevisionRepository;
import org.b3log.solo.repository.CategoryRepository;
import org.b3log.solo.repository.CategoryTagRepository;
import org.b3log.solo.repository.CommentRepository;
import org.b3log.solo.repository.FollowRepository;
import org.b3log.solo.repository.LinkRepository;
import org.b3log.solo.repository.OptionRepository;
import org.b3log.solo.repository.PageRepository;
import org.b3log.solo.repository.PluginRepository;
import org.b3log.solo.repository.TagArticleRepository;
import org.b3log.solo.repository.TagRepository;
import org.b3log.solo.repository.UserRepository;
import org.b3log.solo.upgrade.V20261101_20261105;
import org.b3log.solo.util.AdminPaths;
import org.b3log.solo.util.Markdowns;
import org.b3log.solo.util.QueryResults;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Full 15-table backup archive inspection and transactional one-click disaster recovery service.
 *
 * @author <a href="https://github.com/Willh92">Willh92</a>
 * @since 3.0.0
 */
@Service
public class BackupRestoreService {

    private static final Logger LOGGER = Logger.getLogger(BackupRestoreService.class);

    public static final int MAX_ZIP_ENTRIES = 5_000;
    public static final long MAX_JSON_UNCOMPRESSED_BYTES = 256L * 1024L * 1024L;
    private static final Pattern SOLO_JSON_ENTRY_PATTERN =
            Pattern.compile("^solo-[A-Za-z0-9_.-]*\\.json$", Pattern.CASE_INSENSITIVE);
    private static final Map<String, Boolean> BOOLEAN_SWITCH_DEFAULTS = buildBooleanSwitchDefaults();

    @Inject
    private TagArticleRepository tagArticleRepository;

    @Inject
    private ArchiveDateArticleRepository archiveDateArticleRepository;

    @Inject
    private CategoryTagRepository categoryTagRepository;

    @Inject
    private CommentRepository commentRepository;

    @Inject
    private ArticleRevisionRepository articleRevisionRepository;

    @Inject
    private ArticleRepository articleRepository;

    @Inject
    private ArchiveDateRepository archiveDateRepository;

    @Inject
    private CategoryRepository categoryRepository;

    @Inject
    private TagRepository tagRepository;

    @Inject
    private PageRepository pageRepository;

    @Inject
    private LinkRepository linkRepository;

    @Inject
    private FollowRepository followRepository;

    @Inject
    private PluginRepository pluginRepository;

    @Inject
    private OptionRepository optionRepository;

    @Inject
    private UserRepository userRepository;

    @Inject
    private LocalRollingBackupService localRollingBackupService;

    @Inject
    private OptionQueryService optionQueryService;

    @Inject
    private OptionMgmtService optionMgmtService;

    @Inject
    private InstantSearchService instantSearchService;

    @Inject
    private BacklinkService backlinkService;

    /**
     * Safely inspects a Volo (`volo-backup-*.zip`) or Solo (`solo-json-*.zip`) backup archive
     * without modifying the database.
     */
    public JSONObject inspectBackupZip(final Path zipPath) throws IOException {
        final ParsedBackup parsed = readAndValidateBackupZip(zipPath);
        final JSONObject data = parsed.data();

        final JSONArray articlesArr = optTableArray(data, Article.ARTICLES);
        int publishedArticles = 0;
        int drafts = 0;
        for (int i = 0; i < articlesArr.length(); i++) {
            final JSONObject article = articlesArr.optJSONObject(i);
            if (article == null) {
                continue;
            }
            if (Article.ARTICLE_STATUS_C_DRAFT == article.optInt(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED)) {
                drafts++;
            } else {
                publishedArticles++;
            }
        }

        final JSONArray usersArr = optTableArray(data, User.USERS);
        final boolean hasAdminUser = containsAdminUser(usersArr);
        final JSONArray optionsArr = optTableArray(data, Option.OPTIONS);
        final String version = extractVersion(optionsArr);

        final JSONObject counts = new JSONObject()
                .put("articles", publishedArticles)
                .put("drafts", drafts)
                .put("comments", optTableArray(data, Comment.COMMENTS).length())
                .put("categories", optTableArray(data, Category.CATEGORIES).length())
                .put("tags", optTableArray(data, Tag.TAGS).length())
                .put("pages", optTableArray(data, Page.PAGES).length())
                .put("links", optTableArray(data, Link.LINKS).length())
                .put("follows", optTableArray(data, Follow.FOLLOWS).length())
                .put("users", usersArr.length())
                .put("options", optionsArr.length())
                .put("revisions", optTableArray(data, "articleRevisions", "revisions", "article_revision").length());

        return new JSONObject()
                .put("sourceName", zipPath.getFileName().toString())
                .put("version", version)
                .put("counts", counts)
                .put("hasAdminUser", hasAdminUser);
    }

    /**
     * Transactionally restores all 15 database tables from a validated backup archive,
     * creating an automatic pre-restore rollback backup first.
     */
    public synchronized JSONObject restoreFromBackupZip(final Path zipPath, final boolean overwriteAuth) throws Exception {
        // 1. Lightweight streaming ZIP pre-validation before triggering pre-restore backup (zero DOM heap).
        preValidateBackupZip(zipPath);

        // 2. Create automatic pre-restore rollback backup on disk while restore payload is not yet in heap.
        String preRestoreFileName = "";
        if (localRollingBackupService != null) {
            final JSONObject preBackup = localRollingBackupService.runBackup(true);
            preRestoreFileName = preBackup.optString("name", "");
        }

        // 3. Parse and validate full restore payload before touching the database.
        final ParsedBackup parsed = readAndValidateBackupZip(zipPath);
        final JSONObject data = parsed.data();
        final JSONArray backupUsers = optTableArray(data, User.USERS);
        final boolean hasAdminInBackup = containsAdminUser(backupUsers);
        if (overwriteAuth && !hasAdminInBackup) {
            throw new IllegalArgumentException("备份包中不包含有效管理员账号，无法覆盖账号凭证。");
        }

        // 4. Snapshot current protected state when !overwriteAuth (or masked secret sources when overwriteAuth).
        final List<JSONObject> preservedAdmins = new ArrayList<>();
        final List<JSONObject> preservedProtectedOptions = new ArrayList<>();
        String preRestoreWebhooksJson = "";
        String preRestoreRemoteBackupJson = "";
        if (!overwriteAuth) {
            if (userRepository != null) {
                final JSONArray currentUsers = QueryResults.array(userRepository.get(new Query().setPageCount(1)));
                for (int i = 0; i < currentUsers.length(); i++) {
                    final JSONObject u = currentUsers.optJSONObject(i);
                    if (u != null && Role.ADMIN_ROLE.equals(u.optString(User.USER_ROLE))) {
                        preservedAdmins.add(org.b3log.solo.util.Solos.clone(u));
                    }
                }
            }
            if (optionRepository != null) {
                final JSONArray currentOptions = QueryResults.array(optionRepository.get(new Query().setPageCount(1)));
                for (int i = 0; i < currentOptions.length(); i++) {
                    final JSONObject o = currentOptions.optJSONObject(i);
                    if (o != null && isProtectedOptionId(o.optString(Keys.OBJECT_ID))) {
                        preservedProtectedOptions.add(org.b3log.solo.util.Solos.clone(o));
                    }
                }
            }
        } else {
            preRestoreWebhooksJson = readStoredOptionDocument(Option.ID_C_VOLO_WEBHOOKS);
            preRestoreRemoteBackupJson = readStoredOptionDocument(Option.ID_C_VOLO_REMOTE_BACKUP);
        }

        // Clear caches prior to transactional delete/insert so OptionCache missingIds do not skip deletes.
        clearAllCaches();

        // 5. Execute atomic 15-table clear & repopulate inside a single Latke Transaction.
        final JSONObject restoredCounts = new JSONObject();
        final Transaction tx = optionRepository.beginTransaction();
        try {
            // Delete in child/join-first FK-safe order.
            clearRepository(tagArticleRepository);
            clearRepository(archiveDateArticleRepository);
            clearRepository(categoryTagRepository);
            clearRepository(commentRepository);
            clearRepository(articleRevisionRepository);
            clearRepository(articleRepository);
            clearRepository(archiveDateRepository);
            clearRepository(categoryRepository);
            clearRepository(tagRepository);
            clearRepository(pageRepository);
            clearRepository(linkRepository);
            clearRepository(followRepository);
            clearRepository(pluginRepository);
            clearRepository(optionRepository);
            clearRepository(userRepository);

            // Insert in parent-first FK-safe order.
            final UserRestoreResult userResult = restoreUsers(backupUsers, preservedAdmins, overwriteAuth);
            restoredCounts.put("users", userResult.count());

            final int optionCount = restoreOptions(
                    optTableArray(data, Option.OPTIONS),
                    preservedProtectedOptions,
                    preRestoreWebhooksJson,
                    preRestoreRemoteBackupJson,
                    overwriteAuth);
            restoredCounts.put("options", optionCount);

            restoredCounts.put("plugins", restoreSimpleTable(pluginRepository, optTableArray(data, Plugin.PLUGINS), BackupRestoreService::sanitizePlugin));
            restoredCounts.put("follows", restoreSimpleTable(followRepository, optTableArray(data, Follow.FOLLOWS), BackupRestoreService::sanitizeFollow));
            restoredCounts.put("links", restoreSimpleTable(linkRepository, optTableArray(data, Link.LINKS), BackupRestoreService::sanitizeLink));
            restoredCounts.put("pages", restoreSimpleTable(pageRepository, optTableArray(data, Page.PAGES), BackupRestoreService::sanitizePage));
            restoredCounts.put("tags", restoreSimpleTable(tagRepository, optTableArray(data, Tag.TAGS), BackupRestoreService::sanitizeTag));
            restoredCounts.put("categories", restoreSimpleTable(categoryRepository, optTableArray(data, Category.CATEGORIES), BackupRestoreService::sanitizeCategory));
            restoredCounts.put("archiveDates", restoreSimpleTable(archiveDateRepository, optTableArray(data, ArchiveDate.ARCHIVE_DATES), null));

            final int articleCount = restoreArticles(
                    optTableArray(data, Article.ARTICLES),
                    userResult.restoredUserIds(),
                    userResult.primaryAdminId(),
                    overwriteAuth);
            restoredCounts.put("articles", articleCount);

            restoredCounts.put("revisions", restoreSimpleTable(
                    articleRevisionRepository,
                    optTableArray(data, "articleRevisions", "revisions", "article_revision"),
                    BackupRestoreService::sanitizeRevision));
            restoredCounts.put("comments", restoreSimpleTable(
                    commentRepository,
                    optTableArray(data, Comment.COMMENTS),
                    BackupRestoreService::sanitizeComment));
            restoredCounts.put("categoryTags", restoreSimpleTable(
                    categoryTagRepository,
                    optTableArray(data, Category.CATEGORY + "_" + Tag.TAG),
                    null));
            restoredCounts.put("archiveDateArticles", restoreSimpleTable(
                    archiveDateArticleRepository,
                    optTableArray(data, ArchiveDate.ARCHIVE_DATE + "_" + Article.ARTICLE, "archivedate_article"),
                    null));
            restoredCounts.put("tagArticles", restoreSimpleTable(
                    tagArticleRepository,
                    optTableArray(data, Tag.TAG + "_" + Article.ARTICLES, "tag_article"),
                    null));

            tx.commit();
        } catch (final Exception e) {
            if (tx != null && tx.isActive()) {
                tx.rollback();
            }
            clearAllCaches();
            throw e;
        }

        // 6. Post-commit normalization & runtime cache/service refresh.
        clearAllCaches();
        if (optionQueryService != null && optionMgmtService != null) {
            try {
                V20261101_20261105.migrateBooleanSwitchOptions(optionQueryService, optionMgmtService);
            } catch (final Exception e) {
                LOGGER.log(Level.WARN, "Post-restore boolean switch migration warning", e);
            }
        }
        refreshRuntimeState();

        return new JSONObject()
                .put("preRestoreBackup", preRestoreFileName)
                .put("reloginRequired", overwriteAuth)
                .put("restoredCounts", restoredCounts);
    }

    public static boolean isProtectedOptionId(final String optId) {
        if (optId == null || optId.isEmpty()) {
            return false;
        }
        return TotpService.isProtectedOptionId(optId)
                || ShareTokenService.OPTION_ID_SECRET.equals(optId)
                || Option.ID_C_VOLO_PASSKEY_AUTH.equals(optId)
                || optId.startsWith(Option.ID_C_VOLO_PASSKEY_AUTH + ".")
                || Option.ID_C_VOLO_REMOTE_BACKUP.equals(optId)
                || optId.startsWith(Option.ID_C_VOLO_REMOTE_BACKUP + ".")
                || Option.ID_C_VOLO_WEBHOOKS.equals(optId)
                || optId.startsWith(Option.ID_C_VOLO_WEBHOOKS + ".");
    }

    private record ParsedBackup(String entryName, JSONObject data) { }

    private record UserRestoreResult(int count, Set<String> restoredUserIds, String primaryAdminId) { }

    @FunctionalInterface
    private interface RowSanitizer {
        void sanitize(JSONObject row);
    }

    /**
     * Lightweight streaming pre-validation verifying the backup archive opens cleanly, stays within entry/byte limits,
     * contains {@code data.json} (or a single {@code solo-*.json}), and begins with {@code '{'} without building a DOM.
     */
    private void preValidateBackupZip(final Path zipPath) throws IOException {
        if (zipPath == null || !Files.isRegularFile(zipPath)) {
            throw new IllegalArgumentException("备份压缩包文件不存在。");
        }
        try (ZipFile zipFile = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8)) {
            final ZipEntry chosen = locateBackupJsonEntry(zipFile);
            try (InputStream in = stripOptionalUtf8Bom(zipFile.getInputStream(chosen))) {
                final byte[] buffer = new byte[16384];
                long total = 0L;
                int firstNonWs = -1;
                int lastNonWs = -1;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_JSON_UNCOMPRESSED_BYTES) {
                        throw new IOException("备份数据文件解压后超过 256MB 安全上限。");
                    }
                    for (int i = 0; i < read; i++) {
                        final int b = buffer[i] & 0xFF;
                        if (b > ' ') {
                            if (firstNonWs < 0) {
                                firstNonWs = b;
                                if (firstNonWs != '{') {
                                    throw new IllegalArgumentException("备份包 JSON 数据格式损坏，无法解析。");
                                }
                            }
                            lastNonWs = b;
                        }
                    }
                }
                if (total < 2 || firstNonWs != '{' || lastNonWs != '}') {
                    throw new IllegalArgumentException("备份包 JSON 数据格式损坏，无法解析。");
                }
            }
        }
    }

    private ParsedBackup readAndValidateBackupZip(final Path zipPath) throws IOException {
        if (zipPath == null || !Files.isRegularFile(zipPath)) {
            throw new IllegalArgumentException("备份压缩包文件不存在。");
        }
        try (ZipFile zipFile = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8)) {
            final ZipEntry chosen = locateBackupJsonEntry(zipFile);
            final JSONObject parsed = parseBoundedJsonObject(zipFile, chosen, MAX_JSON_UNCOMPRESSED_BYTES);

            if (!(parsed.opt(Article.ARTICLES) instanceof JSONArray)
                    && !(parsed.opt(Option.OPTIONS) instanceof JSONArray)
                    && !(parsed.opt(User.USERS) instanceof JSONArray)) {
                throw new IllegalArgumentException("无效的备份数据包：缺少 articles / options / users 核心表数据。");
            }
            return new ParsedBackup(chosen.getName(), parsed);
        }
    }

    private static ZipEntry locateBackupJsonEntry(final ZipFile zipFile) throws IOException {
        final Enumeration<? extends ZipEntry> entries = zipFile.entries();
        int entryCount = 0;
        ZipEntry dataJsonEntry = null;
        ZipEntry soloJsonEntry = null;
        int rootJsonCount = 0;

        while (entries.hasMoreElements()) {
            final ZipEntry entry = entries.nextElement();
            if (++entryCount > MAX_ZIP_ENTRIES) {
                throw new IOException("备份压缩包包含过多文件条目（上限 " + MAX_ZIP_ENTRIES + " 个）。");
            }
            if (entry.isDirectory()) {
                continue;
            }
            final String rawName = entry.getName().replace('\\', '/');
            if (rawName.startsWith("__MACOSX/") || rawName.contains("/__MACOSX/")) {
                continue;
            }
            if ("data.json".equals(rawName)) {
                dataJsonEntry = entry;
            } else if (rawName.indexOf('/') < 0 && SOLO_JSON_ENTRY_PATTERN.matcher(rawName).matches()) {
                soloJsonEntry = entry;
                rootJsonCount++;
            }
        }

        final ZipEntry chosen = dataJsonEntry != null
                ? dataJsonEntry
                : (rootJsonCount == 1 ? soloJsonEntry : null);
        if (chosen == null) {
            throw new IllegalArgumentException("备份压缩包中未找到 data.json（或标准 solo-*.json 数据文件）。");
        }
        if (chosen.getSize() > MAX_JSON_UNCOMPRESSED_BYTES) {
            throw new IOException("备份数据文件超过 256MB 安全解压上限。");
        }
        return chosen;
    }

    private static InputStream stripOptionalUtf8Bom(final InputStream raw) throws IOException {
        final PushbackInputStream pb = new PushbackInputStream(raw, 3);
        final byte[] bom = new byte[3];
        int read = 0;
        while (read < 3) {
            final int n = pb.read(bom, read, 3 - read);
            if (n < 0) {
                break;
            }
            read += n;
        }
        if (!(read == 3 && (bom[0] & 0xFF) == 0xEF && (bom[1] & 0xFF) == 0xBB && (bom[2] & 0xFF) == 0xBF)) {
            if (read > 0) {
                pb.unread(bom, 0, read);
            }
        }
        return pb;
    }

    private static JSONObject parseBoundedJsonObject(final ZipFile zipFile, final ZipEntry entry, final long maxBytes)
            throws IOException {
        try (InputStream bounded = new BoundedInputStream(stripOptionalUtf8Bom(zipFile.getInputStream(entry)), maxBytes);
             BufferedReader reader = new BufferedReader(new InputStreamReader(bounded, StandardCharsets.UTF_8), 32768)) {
            return new JSONObject(new JSONTokener(reader));
        } catch (final IOException io) {
            throw io;
        } catch (final Exception e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IllegalArgumentException("备份包 JSON 数据格式损坏，无法解析。", e);
        }
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private final long maxBytes;
        private long total;

        BoundedInputStream(final InputStream in, final long maxBytes) {
            super(in);
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            final int b = super.read();
            if (b != -1 && ++total > maxBytes) {
                throw new IOException("备份数据文件解压后超过 256MB 安全上限。");
            }
            return b;
        }

        @Override
        public int read(final byte[] b, final int off, final int len) throws IOException {
            final int n = super.read(b, off, len);
            if (n > 0) {
                total += n;
                if (total > maxBytes) {
                    throw new IOException("备份数据文件解压后超过 256MB 安全上限。");
                }
            }
            return n;
        }
    }

    private static void clearRepository(final AbstractRepository repository) throws Exception {
        if (repository == null) {
            return;
        }
        if (Latkes.getRuntimeDatabase() != Latkes.RuntimeDatabase.NONE
                && repository.getClass().getEnclosingClass() == null) {
            repository.remove(new Query());
            return;
        }
        final JSONArray existing = QueryResults.array(repository.get(new Query().select(Keys.OBJECT_ID).setPageCount(1)));
        for (int i = 0; i < existing.length(); i++) {
            final JSONObject row = existing.optJSONObject(i);
            if (row != null) {
                final String id = row.optString(Keys.OBJECT_ID);
                if (!id.isEmpty()) {
                    repository.remove(id);
                }
            }
        }
    }

    private UserRestoreResult restoreUsers(final JSONArray backupUsers,
                                           final List<JSONObject> preservedAdmins,
                                           final boolean overwriteAuth) throws Exception {
        if (userRepository == null) {
            return new UserRestoreResult(0, Set.of(), "");
        }
        final Set<String> usedIds = new HashSet<>();
        final Set<String> usedNames = new HashSet<>();
        final Set<String> usedEmails = new HashSet<>();
        String primaryAdminId = "";
        int count = 0;

        if (!overwriteAuth) {
            for (final JSONObject admin : preservedAdmins) {
                sanitizeUser(admin);
                final String id = userRepository.add(admin);
                usedIds.add(id);
                addNormalizedIfNotBlank(usedNames, admin.optString(User.USER_NAME));
                addNormalizedIfNotBlank(usedEmails, admin.optString(UserExt.USER_EMAIL));
                if (primaryAdminId.isEmpty()) {
                    primaryAdminId = id;
                }
                count++;
            }
        }

        for (int i = 0; i < backupUsers.length(); i++) {
            final JSONObject raw = backupUsers.optJSONObject(i);
            backupUsers.put(i, (Object) null);
            if (raw == null) {
                continue;
            }
            final String role = raw.optString(User.USER_ROLE);
            if (!overwriteAuth && Role.ADMIN_ROLE.equals(role)) {
                continue;
            }
            sanitizeUser(raw);
            final String oId = raw.optString(Keys.OBJECT_ID);
            final String lowerName = raw.optString(User.USER_NAME).strip().toLowerCase(Locale.ROOT);
            final String lowerEmail = raw.optString(UserExt.USER_EMAIL).strip().toLowerCase(Locale.ROOT);

            if ((!oId.isEmpty() && usedIds.contains(oId))
                    || (!lowerName.isEmpty() && usedNames.contains(lowerName))
                    || (!lowerEmail.isEmpty() && usedEmails.contains(lowerEmail))) {
                continue;
            }
            final String addedId = userRepository.add(raw);
            usedIds.add(addedId);
            if (!lowerName.isEmpty()) {
                usedNames.add(lowerName);
            }
            if (!lowerEmail.isEmpty()) {
                usedEmails.add(lowerEmail);
            }
            if (Role.ADMIN_ROLE.equals(role) && primaryAdminId.isEmpty()) {
                primaryAdminId = addedId;
            }
            count++;
        }

        return new UserRestoreResult(count, usedIds, primaryAdminId);
    }

    private int restoreOptions(final JSONArray backupOptions,
                               final List<JSONObject> preservedProtectedOptions,
                               final String preRestoreWebhooksJson,
                               final String preRestoreRemoteBackupJson,
                               final boolean overwriteAuth) throws Exception {
        if (optionRepository == null) {
            return 0;
        }
        final Map<String, JSONObject> toInsert = new LinkedHashMap<>();

        for (int i = 0; i < backupOptions.length(); i++) {
            final JSONObject raw = backupOptions.optJSONObject(i);
            backupOptions.put(i, (Object) null);
            if (raw == null) {
                continue;
            }
            final String optId = raw.optString(Keys.OBJECT_ID);
            if (optId.isEmpty()) {
                continue;
            }
            if (!overwriteAuth && isProtectedOptionId(optId)) {
                continue;
            }
            sanitizeAndNormalizeOption(raw);
            toInsert.put(optId, raw);
        }

        if (!overwriteAuth) {
            for (final JSONObject protectedOpt : preservedProtectedOptions) {
                final String optId = protectedOpt.optString(Keys.OBJECT_ID);
                if (!optId.isEmpty()) {
                    if (protectedOpt.isNull(Option.OPTION_CATEGORY)) {
                        protectedOpt.put(Option.OPTION_CATEGORY, "");
                    }
                    if (protectedOpt.isNull(Option.OPTION_VALUE)) {
                        protectedOpt.put(Option.OPTION_VALUE, "");
                    }
                    toInsert.put(optId, protectedOpt);
                }
            }
        } else {
            final JSONObject webhooksOpt = toInsert.get(Option.ID_C_VOLO_WEBHOOKS);
            if (webhooksOpt != null) {
                webhooksOpt.put(Option.OPTION_VALUE, mergeWebhooksSecrets(
                        webhooksOpt.optString(Option.OPTION_VALUE, ""),
                        preRestoreWebhooksJson));
            }
            final JSONObject remoteBackupOpt = toInsert.get(Option.ID_C_VOLO_REMOTE_BACKUP);
            if (remoteBackupOpt != null) {
                remoteBackupOpt.put(Option.OPTION_VALUE, mergeRemoteBackupSecrets(
                        remoteBackupOpt.optString(Option.OPTION_VALUE, ""),
                        preRestoreRemoteBackupJson));
            }
        }

        final JSONObject finalRemoteBackupOpt = toInsert.get(Option.ID_C_VOLO_REMOTE_BACKUP);
        if (finalRemoteBackupOpt != null) {
            final String cat = finalRemoteBackupOpt.optString(Option.OPTION_CATEGORY, "");
            if (cat.isEmpty() || Option.CATEGORY_C_PREFERENCE.equals(cat)) {
                finalRemoteBackupOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_REMOTE_BACKUP);
            }
        }

        // Ensure database migration version stays aligned with current Volo schema.
        final JSONObject versionOpt = toInsert.get(Option.ID_C_VERSION);
        if (versionOpt != null) {
            versionOpt.put(Option.OPTION_VALUE, SoloApplication.DATABASE_VERSION);
        } else {
            toInsert.put(Option.ID_C_VERSION, new JSONObject()
                    .put(Keys.OBJECT_ID, Option.ID_C_VERSION)
                    .put(Option.OPTION_CATEGORY, Option.CATEGORY_C_PREFERENCE)
                    .put(Option.OPTION_VALUE, SoloApplication.DATABASE_VERSION));
        }

        int count = 0;
        for (final JSONObject opt : toInsert.values()) {
            optionRepository.add(opt);
            count++;
        }
        return count;
    }

    private String readStoredOptionDocument(final String rootId) {
        try {
            if (optionQueryService != null) {
                final String val = org.b3log.solo.util.OptionDocuments.read(optionQueryService, rootId, "");
                if (!val.isEmpty()) {
                    return val;
                }
            }
            if (optionRepository == null) {
                return "";
            }
            JSONObject root = optionRepository.get(rootId);
            Map<String, JSONObject> fallbackById = null;
            if (root == null) {
                final JSONArray all = QueryResults.array(optionRepository.get(new Query().setPageCount(1)));
                fallbackById = new HashMap<>();
                for (int i = 0; i < all.length(); i++) {
                    final JSONObject o = all.optJSONObject(i);
                    if (o != null) {
                        final String id = o.optString(Keys.OBJECT_ID, "");
                        if (!id.isEmpty()) {
                            fallbackById.put(id, o);
                        }
                    }
                }
                root = fallbackById.get(rootId);
            }
            if (root == null) {
                return "";
            }
            final String value = root.optString(Option.OPTION_VALUE, "");
            if (!value.startsWith("{\"storage\":\"chunks\"")) {
                return value;
            }
            final JSONObject manifest = new JSONObject(value);
            final String generation = manifest.optString("generation", "");
            final int count = manifest.optInt("count", 0);
            if (!generation.matches("[a-f0-9]{32}") || count < 1 || count > 128) {
                return "";
            }
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < count; i++) {
                final String chunkId = rootId + "." + generation + "." + i;
                JSONObject chunk = optionRepository.get(chunkId);
                if (chunk == null && fallbackById != null) {
                    chunk = fallbackById.get(chunkId);
                }
                if (chunk == null) {
                    return "";
                }
                sb.append(chunk.optString(Option.OPTION_VALUE, ""));
            }
            return sb.toString();
        } catch (final Exception ignored) {
            return "";
        }
    }

    private static String mergeRemoteBackupSecrets(final String incomingRaw, final String preRestoreRaw) {
        if (incomingRaw == null || incomingRaw.startsWith("{\"storage\":\"chunks\"")) {
            return incomingRaw == null ? "" : incomingRaw;
        }
        try {
            final JSONObject inObj = incomingRaw.isBlank() ? new JSONObject() : new JSONObject(incomingRaw);
            inObj.remove("secretSet");
            if (preRestoreRaw == null || preRestoreRaw.isBlank()) {
                return inObj.toString();
            }
            final JSONObject oldObj = new JSONObject(preRestoreRaw);
            for (final String secretKey : new String[]{"webdavPassword", "s3SecretKey", "secret"}) {
                final String inSecret = inObj.optString(secretKey, "");
                final String oldSecret = oldObj.optString(secretKey, "");
                if (inSecret.isBlank() && !oldSecret.isBlank()) {
                    inObj.put(secretKey, oldSecret);
                }
            }
            return inObj.toString();
        } catch (final Exception ignored) {
            return incomingRaw;
        }
    }

    private static String mergeWebhooksSecrets(final String incomingRaw, final String preRestoreRaw) {
        if (incomingRaw == null || incomingRaw.isBlank() || incomingRaw.startsWith("{\"storage\":\"chunks\"")) {
            return incomingRaw == null ? "" : incomingRaw;
        }
        try {
            final JSONArray inArr = new JSONArray(incomingRaw);
            final Map<String, String> secretById = new HashMap<>();
            final Map<String, String> secretByUrl = new HashMap<>();
            if (preRestoreRaw != null && !preRestoreRaw.isBlank()) {
                final JSONArray oldArr = new JSONArray(preRestoreRaw);
                for (int i = 0; i < oldArr.length(); i++) {
                    final JSONObject oldItem = oldArr.optJSONObject(i);
                    if (oldItem == null) {
                        continue;
                    }
                    final String oldSecret = oldItem.optString("secret", "");
                    if (oldSecret.isBlank()) {
                        continue;
                    }
                    final String id = oldItem.optString("id", "").strip();
                    if (!id.isEmpty()) {
                        secretById.put(id, oldSecret);
                    }
                    final String url = oldItem.optString("url", "").strip();
                    if (!url.isEmpty()) {
                        secretByUrl.putIfAbsent(url, oldSecret);
                    }
                }
            }
            for (int i = 0; i < inArr.length(); i++) {
                final JSONObject item = inArr.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                item.remove("secretSet");
                final String inSecret = item.optString("secret", "");
                if (inSecret.isBlank()) {
                    String restored = secretById.get(item.optString("id", "").strip());
                    if (restored == null || restored.isBlank()) {
                        restored = secretByUrl.get(item.optString("url", "").strip());
                    }
                    if (restored != null && !restored.isBlank()) {
                        item.put("secret", restored);
                    }
                }
            }
            return inArr.toString();
        } catch (final Exception ignored) {
            return incomingRaw;
        }
    }

    private int restoreArticles(final JSONArray backupArticles,
                                final Set<String> restoredUserIds,
                                final String primaryAdminId,
                                final boolean overwriteAuth) throws Exception {
        if (articleRepository == null) {
            return 0;
        }
        final Set<String> seenIds = new HashSet<>();
        int count = 0;
        for (int i = 0; i < backupArticles.length(); i++) {
            final JSONObject raw = backupArticles.optJSONObject(i);
            backupArticles.put(i, (Object) null);
            if (raw == null) {
                continue;
            }
            final String id = raw.optString(Keys.OBJECT_ID);
            if (!id.isEmpty() && !seenIds.add(id)) {
                continue;
            }
            sanitizeArticle(raw);
            if (!overwriteAuth && !primaryAdminId.isEmpty()) {
                final String authorId = raw.optString(Article.ARTICLE_AUTHOR_ID);
                if (authorId.isEmpty() || (!restoredUserIds.isEmpty() && !restoredUserIds.contains(authorId))) {
                    raw.put(Article.ARTICLE_AUTHOR_ID, primaryAdminId);
                }
            }
            articleRepository.add(raw);
            count++;
        }
        return count;
    }

    private static int restoreSimpleTable(final AbstractRepository repository,
                                          final JSONArray rows,
                                          final RowSanitizer sanitizer) throws Exception {
        if (repository == null || rows == null) {
            return 0;
        }
        final Set<String> seenIds = new HashSet<>();
        int count = 0;
        for (int i = 0; i < rows.length(); i++) {
            final JSONObject raw = rows.optJSONObject(i);
            rows.put(i, (Object) null);
            if (raw == null) {
                continue;
            }
            final String id = raw.optString(Keys.OBJECT_ID);
            if (!id.isEmpty() && !seenIds.add(id)) {
                continue;
            }
            if (sanitizer != null) {
                sanitizer.sanitize(raw);
            }
            repository.add(raw);
            count++;
        }
        return count;
    }

    private void clearAllCaches() {
        try {
            final BeanManager bm = BeanManager.getInstance();
            if (bm == null) {
                return;
            }
            clearCacheSafe(() -> {
                final OptionCache c = optBean(bm, OptionCache.class);
                if (c != null) {
                    c.clear();
                }
            });
            clearCacheSafe(() -> {
                final ArticleCache c = optBean(bm, ArticleCache.class);
                if (c != null) {
                    c.clear();
                }
            });
            clearCacheSafe(() -> {
                final PageCache c = optBean(bm, PageCache.class);
                if (c != null) {
                    c.clear();
                }
            });
            clearCacheSafe(() -> {
                final UserCache c = optBean(bm, UserCache.class);
                if (c != null) {
                    c.clear();
                }
            });
            clearCacheSafe(() -> {
                final CommentCache c = optBean(bm, CommentCache.class);
                if (c != null) {
                    c.clear();
                }
            });
            clearCacheSafe(() -> {
                final StatisticCache c = optBean(bm, StatisticCache.class);
                if (c != null) {
                    c.clear();
                }
            });
            clearCacheSafe(() -> {
                final FollowArticleCache c = optBean(bm, FollowArticleCache.class);
                if (c != null) {
                    c.clear();
                }
            });
            invokeOptionalBeanFirstAvailable(bm, "org.b3log.solo.cache.PublicViewCache", "invalidateAll", "clear");
            invokeOptionalBeanFirstAvailable(bm, "org.b3log.solo.service.StatisticMgmtService", "resetPendingViewDeltas");
            invokeOptionalBeanFirstAvailable(bm, "org.b3log.solo.service.ArticleMgmtService", "resetPendingViewDeltas");
            clearCacheSafe(org.b3log.solo.processor.ErrorProcessor::clearCache);
            clearCacheSafe(org.b3log.solo.web.PublicResourceHandler::clearCache);
            clearCacheSafe(org.b3log.solo.bolo.pic.PicUploadProcessor::clearCache);
            clearCacheSafe(org.b3log.solo.util.Skins::clearTemplateCache);
        } catch (final Throwable ignored) {
        }
    }

    private void refreshRuntimeState() {
        clearAllCaches();
        if (backlinkService != null) {
            clearCacheSafe(backlinkService::invalidateAll);
        }
        if (instantSearchService != null) {
            clearCacheSafe(instantSearchService::invalidateAll);
        }
        clearCacheSafe(Markdowns::clearCache);
        clearCacheSafe(MailService::loadMailSettings);
        clearCacheSafe(WAF::set);

        try {
            final BeanManager bm = BeanManager.getInstance();
            if (bm == null) {
                return;
            }
            final OptionQueryService optQuery = optionQueryService != null
                    ? optionQueryService
                    : optBean(bm, OptionQueryService.class);
            if (optQuery != null) {
                clearCacheSafe(() -> {
                    final JSONObject skin = optQuery.getSkin();
                    if (skin != null) {
                        final SkinMgmtService skinMgmt = optBean(bm, SkinMgmtService.class);
                        if (skinMgmt != null) {
                            skinMgmt.loadSkins(skin);
                        }
                    }
                });
                final JSONObject preference = optQuery.getPreference();
                if (preference != null) {
                    Markdowns.SHOW_CODE_BLOCK_LN = Option.optBooleanSwitch(
                            preference, Option.ID_C_SHOW_CODE_BLOCK_LN, false);
                    clearCacheSafe(() -> {
                        final RiskControlService risk = optBean(bm, RiskControlService.class);
                        if (risk != null) {
                            risk.configure(Option.optBooleanSwitch(preference, "riskControlEnabled", true));
                        }
                    });
                    clearCacheSafe(() -> {
                        final CaptchaService captcha = optBean(bm, CaptchaService.class);
                        if (captcha != null) {
                            captcha.configureComments(Option.optBooleanSwitch(preference, "commentCaptchaEnabled", true));
                        }
                    });
                    clearCacheSafe(() -> AdminPaths.configureLogin(preference.optString("adminLoginPath", "")));
                }
            }
            if (backlinkService == null) {
                clearCacheSafe(() -> {
                    final BacklinkService backlink = optBean(bm, BacklinkService.class);
                    if (backlink != null) {
                        backlink.invalidateAll();
                    }
                });
            }
            if (instantSearchService == null) {
                clearCacheSafe(() -> {
                    final InstantSearchService search = optBean(bm, InstantSearchService.class);
                    if (search != null) {
                        search.invalidateAll();
                    }
                });
            }
            clearCacheSafe(() -> {
                final CommentFilterService filter = optBean(bm, CommentFilterService.class);
                if (filter != null) {
                    filter.reload();
                }
            });

            invokeOptionalBeanNoArg(bm, "org.b3log.solo.cache.OptionCache", "clear");
            invokeOptionalBeanNoArg(bm, "org.b3log.solo.cache.ArticleCache", "clear");
            invokeOptionalBeanNoArg(bm, "org.b3log.solo.cache.CommentCache", "clear");
            invokeOptionalBeanNoArg(bm, "org.b3log.solo.cache.UserCache", "clear");
            invokeOptionalBeanFirstAvailable(bm, "org.b3log.solo.cache.PublicViewCache", "invalidateAll", "clear");
            invokeOptionalBeanFirstAvailable(bm, "org.b3log.solo.service.StatisticMgmtService", "resetPendingViewDeltas");
            invokeOptionalBeanFirstAvailable(bm, "org.b3log.solo.service.ArticleMgmtService", "resetPendingViewDeltas");

            clearCacheSafe(org.b3log.solo.processor.ErrorProcessor::clearCache);
            clearCacheSafe(org.b3log.solo.web.PublicResourceHandler::clearCache);
            clearCacheSafe(org.b3log.solo.bolo.pic.PicUploadProcessor::clearCache);
            clearCacheSafe(org.b3log.solo.util.Skins::clearTemplateCache);

            invokeOptionalBeanNoArg(bm, "org.b3log.solo.service.SyndicationCacheService", "invalidate");
            invokeOptionalBeanFirstAvailable(bm, "org.b3log.solo.service.AnalyticsTrendService", "reloadFromStorage", "reload", "invalidate");
            invokeOptionalBeanFirstAvailable(bm, "org.b3log.solo.service.SeriesAndBookshelfService", "reloadFromStorage", "reload", "invalidate");
            invokeOptionalBeanFirstAvailable(bm, "org.b3log.solo.service.PasskeyService", "reloadFromStorage", "reload", "invalidate");
            invokeOptionalBeanFirstAvailable(bm, "org.b3log.solo.service.TotpService", "reloadFromStorage", "reload", "invalidate");
            invokeOptionalBeanFirstAvailable(bm, "org.b3log.solo.service.IpBanService", "reloadFromStorage", "reload", "invalidate");
            invokeOptionalBeanFirstAvailable(bm, "org.b3log.solo.service.GeoAccessService", "reload", "reloadFromStorage", "invalidate");
        } catch (final Throwable e) {
            LOGGER.log(Level.WARN, "Non-fatal warning refreshing runtime caches after restore", e);
        }
    }

    private static <T> T optBean(final BeanManager bm, final Class<T> beanClass) {
        try {
            return bm == null ? null : bm.getReference(beanClass);
        } catch (final Throwable ignored) {
            return null;
        }
    }

    private static void invokeOptionalBeanNoArg(final BeanManager bm, final String className, final String methodName) {
        invokeOptionalBeanFirstAvailable(bm, className, methodName);
    }

    private static void invokeOptionalBeanFirstAvailable(final BeanManager bm,
                                                         final String className,
                                                         final String... methodNames) {
        if (bm == null || methodNames == null || methodNames.length == 0) {
            return;
        }
        try {
            final Class<?> cls = Class.forName(className);
            final Object bean = optBean(bm, cls);
            if (bean == null) {
                return;
            }
            for (final String methodName : methodNames) {
                try {
                    final Method m = cls.getMethod(methodName);
                    m.invoke(bean);
                    return;
                } catch (final NoSuchMethodException ignored) {
                }
            }
        } catch (final Throwable ignored) {
        }
    }

    private static void clearCacheSafe(final ThrowingRunnable action) {
        try {
            action.run();
        } catch (final Throwable ignored) {
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static boolean containsAdminUser(final JSONArray users) {
        if (users == null) {
            return false;
        }
        for (int i = 0; i < users.length(); i++) {
            final JSONObject user = users.optJSONObject(i);
            if (user != null && Role.ADMIN_ROLE.equals(user.optString(User.USER_ROLE))) {
                return true;
            }
        }
        return false;
    }

    private static String extractVersion(final JSONArray options) {
        if (options == null) {
            return "unknown";
        }
        for (int i = 0; i < options.length(); i++) {
            final JSONObject opt = options.optJSONObject(i);
            if (opt != null && Option.ID_C_VERSION.equals(opt.optString(Keys.OBJECT_ID))) {
                final String val = opt.optString(Option.OPTION_VALUE, "");
                if (!val.isBlank()) {
                    return val.strip();
                }
            }
        }
        return "unknown";
    }

    private static JSONArray optTableArray(final JSONObject data, final String... candidateKeys) {
        for (final String key : candidateKeys) {
            final JSONArray arr = data.optJSONArray(key);
            if (arr != null) {
                return arr;
            }
        }
        return new JSONArray();
    }

    private static void addNormalizedIfNotBlank(final Set<String> target, final String value) {
        if (value != null && !value.isBlank()) {
            target.add(value.strip().toLowerCase(Locale.ROOT));
        }
    }

    private static void sanitizeUser(final JSONObject user) {
        ensureString(user, User.USER_NAME, "");
        ensureString(user, User.USER_URL, "");
        ensureString(user, User.USER_ROLE, Role.DEFAULT_ROLE);
        ensureString(user, UserExt.USER_AVATAR, "");
        ensureString(user, UserExt.USER_B3_KEY, "");
        ensureString(user, UserExt.USER_GITHUB_ID, "");
        ensureString(user, UserExt.USER_EMAIL, "");
        ensureBoolean(user, UserExt.USER_EMAIL_VERIFIED, false);
        ensureString(user, UserExt.USER_PASSWORD_HASH, "");
        ensureInt(user, UserExt.USER_STATUS, UserExt.USER_STATUS_C_VALID);
        ensureLong(user, UserExt.USER_CREATED, 0L);
    }

    private static void sanitizeAndNormalizeOption(final JSONObject opt) {
        ensureString(opt, Option.OPTION_CATEGORY, "");
        ensureString(opt, Option.OPTION_VALUE, "");
        final String id = opt.optString(Keys.OBJECT_ID);
        final Boolean defaultBool = BOOLEAN_SWITCH_DEFAULTS.get(id);
        if (defaultBool != null) {
            final String raw = opt.optString(Option.OPTION_VALUE, "");
            opt.put(Option.OPTION_VALUE, normalizeLegacyBoolean(raw, defaultBool));
        }
    }

    private static void sanitizeArticle(final JSONObject article) {
        ensureString(article, Article.ARTICLE_TITLE, "Untitled");
        ensureString(article, Article.ARTICLE_CONTENT, "");
        ensureString(article, Article.ARTICLE_VIEW_PWD, "");
        final String abs = article.isNull(Article.ARTICLE_ABSTRACT)
                ? Article.getAbstractText(article)
                : article.optString(Article.ARTICLE_ABSTRACT, "");
        article.put(Article.ARTICLE_ABSTRACT, abs);
        if (article.isNull(Article.ARTICLE_ABSTRACT_TEXT)) {
            article.put(Article.ARTICLE_ABSTRACT_TEXT, Article.getAbstractText(article));
        }
        ensureString(article, Article.ARTICLE_TAGS_REF, "");
        ensureString(article, Article.ARTICLE_AUTHOR_ID, "");
        ensureString(article, Article.ARTICLE_PERMALINK, "");
        ensureString(article, Article.ARTICLE_SIGN_ID, "1");
        ensureString(article, Article.ARTICLE_IMG1_URL, "");
        ensureLong(article, Article.ARTICLE_SCHEDULE_TIME, 0L);
        ensureString(article, Article.ARTICLE_AUDIO_URL, "");
        ensureInt(article, Article.ARTICLE_AUDIO_DURATION, 0);
        ensureLong(article, Article.ARTICLE_AUDIO_SIZE, 0L);
        ensureInt(article, Article.ARTICLE_COMMENT_COUNT, 0);
        ensureInt(article, Article.ARTICLE_VIEW_COUNT, 0);
        ensureBoolean(article, Article.ARTICLE_PUT_TOP, false);
        ensureBoolean(article, Article.ARTICLE_COMMENTABLE, true);
        if (article.isNull(Article.ARTICLE_RANDOM_DOUBLE)) {
            article.put(Article.ARTICLE_RANDOM_DOUBLE, Math.random());
        }
        final long now = System.currentTimeMillis();
        ensureLong(article, Article.ARTICLE_CREATED, now);
        ensureLong(article, Article.ARTICLE_UPDATED, article.optLong(Article.ARTICLE_CREATED, now));
        ensureInt(article, Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED);
    }

    private static void sanitizeRevision(final JSONObject rev) {
        ensureString(rev, "articleOId", "");
        ensureString(rev, "revisionTitle", "");
        ensureLong(rev, "revisionCreated", System.currentTimeMillis());
    }

    private static void sanitizeComment(final JSONObject cmt) {
        ensureString(cmt, Comment.COMMENT_CONTENT, "");
        ensureString(cmt, Comment.COMMENT_NAME, "");
        ensureString(cmt, Comment.COMMENT_ON_ID, "");
        ensureString(cmt, Comment.COMMENT_SHARP_URL, "");
        ensureString(cmt, Comment.COMMENT_THUMBNAIL_URL, "");
        ensureString(cmt, Comment.COMMENT_URL, "");
        ensureString(cmt, Comment.COMMENT_ORIGINAL_COMMENT_ID, "");
        ensureString(cmt, Comment.COMMENT_ORIGINAL_COMMENT_NAME, "");
        ensureString(cmt, Comment.COMMENT_USER_ID, "");
        ensureString(cmt, "commentClientIP", "");
        ensureString(cmt, "commentNotifyEmail", "");
        ensureString(cmt, "commentGuestKeyHash", "");
        ensureInt(cmt, Comment.COMMENT_STATUS, Comment.COMMENT_STATUS_C_APPROVED);
        ensureBoolean(cmt, "commentDeleted", false);
        ensureLong(cmt, Comment.COMMENT_CREATED, System.currentTimeMillis());
    }

    private static void sanitizeCategory(final JSONObject cat) {
        ensureString(cat, Category.CATEGORY_TITLE, "");
        ensureString(cat, Category.CATEGORY_URI, "");
        ensureString(cat, Category.CATEGORY_DESCRIPTION, "");
        if (!cat.has(Category.CATEGORY_ORDER)) {
            cat.put(Category.CATEGORY_ORDER, 0);
        }
        if (!cat.has(Category.CATEGORY_TAG_CNT)) {
            cat.put(Category.CATEGORY_TAG_CNT, 0);
        }
    }

    private static void sanitizeTag(final JSONObject tag) {
        ensureString(tag, Tag.TAG_TITLE, "");
    }

    private static void sanitizePage(final JSONObject page) {
        ensureString(page, Page.PAGE_PERMALINK, "");
        ensureString(page, Page.PAGE_TITLE, "");
        ensureString(page, Page.PAGE_OPEN_TARGET, "_self");
        ensureString(page, Page.PAGE_ICON, "");
        if (!page.has(Page.PAGE_ORDER)) {
            page.put(Page.PAGE_ORDER, 0);
        }
    }

    private static void sanitizeLink(final JSONObject link) {
        ensureString(link, Link.LINK_ADDRESS, "");
        ensureString(link, Link.LINK_DESCRIPTION, "");
        ensureString(link, Link.LINK_TITLE, "");
        ensureString(link, Link.LINK_ICON, "");
        if (!link.has(Link.LINK_ORDER)) {
            link.put(Link.LINK_ORDER, 0);
        }
    }

    private static void sanitizeFollow(final JSONObject follow) {
        ensureString(follow, Follow.FOLLOW_ADDRESS, "");
        ensureString(follow, Follow.FOLLOW_DESCRIPTION, "");
        ensureString(follow, Follow.FOLLOW_TITLE, "");
        ensureString(follow, Follow.FOLLOW_ICON, "");
        if (!follow.has(Follow.FOLLOW_ORDER)) {
            follow.put(Follow.FOLLOW_ORDER, 0);
        }
    }

    private static void sanitizePlugin(final JSONObject plugin) {
        ensureString(plugin, "author", "");
        ensureString(plugin, "name", "");
        ensureString(plugin, Plugin.PLUGIN_STATUS, "ENABLED");
        ensureString(plugin, "version", "1.0.0");
    }

    private static void ensureString(final JSONObject obj, final String key, final String fallback) {
        if (obj.isNull(key)) {
            obj.put(key, fallback);
        }
    }

    private static void ensureInt(final JSONObject obj, final String key, final int fallback) {
        if (obj.isNull(key)) {
            obj.put(key, fallback);
        }
    }

    private static void ensureLong(final JSONObject obj, final String key, final long fallback) {
        if (obj.isNull(key)) {
            obj.put(key, fallback);
        }
    }

    private static void ensureBoolean(final JSONObject obj, final String key, final boolean fallback) {
        if (!obj.has(key) || obj.isNull(key)) {
            obj.put(key, fallback);
            return;
        }
        final Object raw = obj.opt(key);
        if (raw instanceof Boolean) {
            return;
        }
        if (raw instanceof Number num) {
            obj.put(key, num.intValue() != 0);
            return;
        }
        final String s = String.valueOf(raw).strip();
        if ("true".equalsIgnoreCase(s) || "1".equals(s)) {
            obj.put(key, true);
        } else if ("false".equalsIgnoreCase(s) || "0".equals(s) || s.isEmpty()) {
            obj.put(key, false);
        } else {
            obj.put(key, fallback);
        }
    }

    private static String normalizeLegacyBoolean(final String raw, final boolean defaultVal) {
        if (raw == null) {
            return Boolean.toString(defaultVal);
        }
        final String trimmed = raw.strip().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return Boolean.toString(defaultVal);
        }
        return switch (trimmed) {
            case "true", "on", "1" -> "true";
            case "false", "off", "0" -> "false";
            default -> Boolean.toString(defaultVal);
        };
    }

    private static Map<String, Boolean> buildBooleanSwitchDefaults() {
        final Map<String, Boolean> map = new HashMap<>();
        for (final String k : new String[]{
                "smtpAuth",
                Option.ID_C_COMMENT_FILTER_BUILTIN,
                Option.ID_C_INTERACTIVE,
                Option.ID_C_WAF_POWER,
                Option.ID_C_ADMIN_ACTIVE_SENT_TO_MAILBOX,
                "riskControlEnabled",
                "commentCaptchaEnabled",
                Option.ID_C_SYNC_GITHUB,
                Option.ID_C_PULL_GITHUB,
                Option.ID_C_COMMENTABLE,
                Option.ID_C_ENABLE_READING_STATS,
                Option.ID_C_ENABLE_INSTANT_SEARCH
        }) {
            map.put(k, Boolean.TRUE);
        }
        for (final String k : new String[]{
                Option.ID_C_SHOW_LOGIN_ENTRY,
                Option.ID_C_ALLOW_REGISTER,
                Option.ID_C_SHOW_CODE_BLOCK_LN,
                Option.ID_C_ENABLE_ARTICLE_UPDATE_HINT,
                Option.ID_C_ALLOW_VISIT_DRAFT_VIA_PERMALINK,
                Option.ID_C_WELFARE_LUTE_SERVICE,
                Option.ID_C_ENABLE_AUTO_FLUSH_GITHUB,
                Option.ID_C_ENABLE_AUTO_FLUSH_BLOG_TO_GITHUB_PROFILE,
                Option.ID_C_HELP_IMPROVE_PLAN,
                Option.ID_C_LOCAL_BACKUP_AUTO,
                Option.ID_C_IMAGE_WEBP_ADAPTIVE,
                Option.ID_C_INDEXNOW_ENABLED
        }) {
            map.put(k, Boolean.FALSE);
        }
        return Map.copyOf(map);
    }
}
