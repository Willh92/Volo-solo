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
package org.b3log.solo.service;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.apache.logging.log4j.Level;
import org.b3log.latke.Keys;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.repository.jdbc.JdbcRepository;
import org.b3log.latke.service.annotation.Service;
import org.b3log.latke.util.Ids;
import org.b3log.latke.util.Strings;
import org.b3log.solo.SoloApplication;
import org.b3log.solo.bolo.tool.ZipFilterUtils;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.model.Article;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.web.WebResources;
import org.json.JSONArray;
import org.json.JSONObject;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Import service.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 2.2.0
 */
@Service
public class ImportService {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(ImportService.class);

    /**
     * Default tag.
     */
    private static final String DEFAULT_TAG = "Note";

    private static final long MAX_SINGLE_MD_BYTES = 10L * 1024L * 1024L;
    private static final long MAX_BATCH_ZIP_BYTES = 100L * 1024L * 1024L;
    private static final int MAX_BATCH_ARTICLES = 2000;

    private static final ThreadPoolExecutor IMPORT_EXECUTOR = createImportExecutor();

    private static ThreadPoolExecutor createImportExecutor() {
        final ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 1, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2),
                runnable -> {
                    final Thread t = new Thread(runnable, "volo-markdown-import");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.DiscardPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /**
     * Gracefully shuts down the background markdown import executor.
     */
    public static void shutdown() {
        IMPORT_EXECUTOR.shutdown();
        try {
            if (!IMPORT_EXECUTOR.awaitTermination(5L, TimeUnit.SECONDS)) {
                IMPORT_EXECUTOR.shutdownNow();
            }
        } catch (final InterruptedException e) {
            IMPORT_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Article management service.
     */
    @Inject
    private ArticleMgmtService articleMgmtService;

    /**
     * User query service.
     */
    @Inject
    private UserQueryService userQueryService;

    /**
     * Article repository.
     */
    @Inject
    private ArticleRepository articleRepository;

    /**
     * Permalink query service.
     */
    @Inject
    private PermalinkQueryService permalinkQueryService;

    /**
     * Imports markdowns files as articles. See <a href="https://ld246.com/article/1498490209748">Solo 支持 Hexo/Jekyll 数据导入</a> for
     * more details.
     */
    public void importMarkdowns() {
        if (IMPORT_EXECUTOR.isShutdown()) {
            return;
        }
        IMPORT_EXECUTOR.execute(() -> {
            try {
                final WebResources servletContext = SoloApplication.getWebResources();
                final String markdownsPath = servletContext.getRealPath("markdowns");
                new File(markdownsPath).mkdirs();
                LOGGER.debug("Import directory [" + markdownsPath + "]");

                final JSONObject admin = userQueryService.getAdmin();
                if (null == admin) { // Not init yet
                    return;
                }

                final String adminId = admin.optString(Keys.OBJECT_ID);

                int succCnt = 0, failCnt = 0;
                final Set<String> failSet = new TreeSet<>();
                final Collection<File> mds = FileUtils.listFiles(new File(markdownsPath), new String[]{"md"}, true);
                if (mds.isEmpty()) {
                    return;
                }

                for (final File md : mds) {
                    final String fileName = md.getName();
                    if (StringUtils.equalsIgnoreCase(fileName, "README.md")) {
                        continue;
                    }

                    try {
                        final String fileContent = FileUtils.readFileToString(md, StandardCharsets.UTF_8);
                        final JSONObject article = parseArticle(fileName, fileContent);
                        article.put(Article.ARTICLE_AUTHOR_ID, adminId);

                        final JSONObject request = new JSONObject();
                        request.put(Article.ARTICLE, article);

                        final String id = articleMgmtService.addArticle(request);
                        FileUtils.moveFile(md, new File(md.getPath() + "." + id));
                        LOGGER.info("Imported article [" + article.optString(Article.ARTICLE_TITLE) + "]");
                        succCnt++;
                    } catch (final Exception e) {
                        LOGGER.log(Level.ERROR, "Import file [" + fileName + "] failed", e);

                        failCnt++;
                        failSet.add(fileName);
                    }
                }

                if (0 == succCnt && 0 == failCnt) {
                    return;
                }

                final StringBuilder logBuilder = new StringBuilder();
                logBuilder.append("[").append(succCnt).append("] imported, [").append(failCnt).append("] failed");
                if (failCnt > 0) {
                    logBuilder.append(": ").append(Strings.LINE_SEPARATOR);

                    for (final String fail : failSet) {
                        logBuilder.append("    ").append(fail).append(Strings.LINE_SEPARATOR);
                    }
                } else {
                    logBuilder.append(" :p");
                }
                LOGGER.info(logBuilder.toString());
            } catch (final Exception e) {
                LOGGER.log(Level.ERROR, "Async markdown import failed", e);
            } finally {
                JdbcRepository.dispose();
            }
        });
    }

    /**
     * 同步导入 Markdown
     */
    public String importMarkdownsSync() {
        final WebResources servletContext = SoloApplication.getWebResources();
        final String markdownsPath = servletContext.getRealPath("markdowns");
        return importMarkdownsSync(new File(markdownsPath));
    }

    public String importMarkdownsSync(final File directory) {
        final String markdownsPath = directory.getAbsolutePath();
        LOGGER.debug("Import directory [" + markdownsPath + "]");

        final JSONObject admin = userQueryService.getAdmin();
        if (null == admin) { // Not init yet
            return "导入失败";
        }

        final String adminId = admin.optString(Keys.OBJECT_ID);

        int succCnt = 0, failCnt = 0;
        final Set<String> failSet = new TreeSet<>();
        final Collection<File> mds = FileUtils.listFiles(new File(markdownsPath), new String[]{"md"}, true);
        if (mds.isEmpty()) {
            return "导入失败";
        }

        for (final File md : mds) {
            final String fileName = md.getName();
            if (StringUtils.equalsIgnoreCase(fileName, "README.md")) {
                continue;
            }

            try {
                final String fileContent = FileUtils.readFileToString(md, StandardCharsets.UTF_8);
                final JSONObject article = parseArticle(fileName, fileContent);
                article.put(Article.ARTICLE_AUTHOR_ID, adminId);

                final JSONObject request = new JSONObject();
                request.put(Article.ARTICLE, article);

                final String id = articleMgmtService.addArticle(request);
                FileUtils.moveFile(md, new File(md.getPath() + "." + id));
                LOGGER.info("Imported article [" + article.optString(Article.ARTICLE_TITLE) + "]");
                succCnt++;
            } catch (final Exception e) {
                LOGGER.log(Level.ERROR, "Import file [" + fileName + "] failed", e);

                failCnt++;
                failSet.add(fileName);
            }
        }

        if (0 == succCnt && 0 == failCnt) {
            return "导入失败";
        }

        final StringBuilder logBuilder = new StringBuilder();
        logBuilder.append("[").append(succCnt).append("] 导入成功, [").append(failCnt).append("] 导入失败");
        if (failCnt > 0) {
            logBuilder.append(": ").append(Strings.LINE_SEPARATOR);

            for (final String fail : failSet) {
                logBuilder.append("    ").append(fail).append(Strings.LINE_SEPARATOR);
            }
        } else {
            logBuilder.append(" :p");
        }
        LOGGER.info(logBuilder.toString());

        return logBuilder.toString();
    }

    /**
     * Imports a batch of Markdown ({@code .md}/{@code .markdown}) and/or ZIP ({@code .zip}) files
     * with duplicate-permalink handling and draft/publish mode selection.
     *
     * @param markdownOrZipFiles     staged `.md`, `.markdown`, or `.zip` paths
     * @param publishImmediately     {@code true} to publish immediately, {@code false} to save as draft
     * @param skipDuplicatePermalink {@code true} to skip existing permalinks, {@code false} to regenerate a unique permalink
     * @return summary JSON {@code {imported, skipped, failed, errors}}
     */
    public JSONObject importBatchFiles(final List<Path> markdownOrZipFiles,
                                       final boolean publishImmediately,
                                       final boolean skipDuplicatePermalink) {
        final JSONArray errors = new JSONArray();
        if (markdownOrZipFiles == null || markdownOrZipFiles.isEmpty()) {
            return new JSONObject()
                    .put("imported", 0)
                    .put("skipped", 0)
                    .put("failed", 0)
                    .put("errors", errors.put("未提供可导入的文件。"));
        }

        final JSONObject admin = userQueryService == null ? null : userQueryService.getAdmin();
        if (admin == null) {
            return new JSONObject()
                    .put("imported", 0)
                    .put("skipped", 0)
                    .put("failed", markdownOrZipFiles.size())
                    .put("errors", errors.put("系统尚未初始化管理员账号。"));
        }
        final String adminId = admin.optString(Keys.OBJECT_ID);

        int imported = 0;
        int skipped = 0;
        int failed = 0;
        Path tempUnpackRoot = null;

        try {
            tempUnpackRoot = Files.createTempDirectory("volo-import-batch-");
            final List<StagedMarkdown> candidates = new ArrayList<>();
            int zipIndex = 0;

            for (final Path input : markdownOrZipFiles) {
                if (input == null || !Files.isRegularFile(input)) {
                    failed++;
                    appendError(errors, "上传文件不存在或不可读。");
                    continue;
                }
                final String name = input.getFileName().toString();
                final String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".zip")) {
                    try {
                        final long zipSize = Files.size(input);
                        if (zipSize > MAX_BATCH_ZIP_BYTES) {
                            failed++;
                            appendError(errors, name + ": ZIP 压缩包超过大小限制（100MB）。");
                            continue;
                        }
                        final Path zipOut = tempUnpackRoot.resolve("zip-" + (++zipIndex));
                        ZipFilterUtils.unpackFilteredZip(input.toFile(), zipOut.toFile(), entry -> {
                            final String lowerEntry = entry.toLowerCase(Locale.ROOT);
                            return (lowerEntry.endsWith(".md") || lowerEntry.endsWith(".markdown")) ? entry : null;
                        });
                        final List<File> extracted = new ArrayList<>(
                                FileUtils.listFiles(zipOut.toFile(), new String[]{"md", "markdown"}, true));
                        extracted.sort(Comparator.comparing(File::getAbsolutePath));
                        for (final File md : extracted) {
                            if (StringUtils.equalsIgnoreCase(md.getName(), "README.md")) {
                                continue;
                            }
                            candidates.add(new StagedMarkdown(md.toPath(), md.getName()));
                        }
                    } catch (final Exception zipErr) {
                        failed++;
                        appendError(errors, name + ": 解压失败（" + safeMsg(zipErr) + "）");
                    }
                } else if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
                    candidates.add(new StagedMarkdown(input, name));
                } else {
                    failed++;
                    appendError(errors, name + ": 不支持的文件格式（仅支持 .md / .markdown / .zip）。");
                }
            }

            if (candidates.size() > MAX_BATCH_ARTICLES) {
                appendError(errors, "单次批量导入最多处理 " + MAX_BATCH_ARTICLES + " 篇 Markdown 文档。");
                candidates.subList(MAX_BATCH_ARTICLES, candidates.size()).clear();
            }

            for (final StagedMarkdown candidate : candidates) {
                try {
                    final long size = Files.size(candidate.path());
                    if (size > MAX_SINGLE_MD_BYTES) {
                        failed++;
                        appendError(errors, candidate.displayName() + ": 单篇 Markdown 文件超过 10MB 限制。");
                        continue;
                    }
                    final String fileContent = Files.readString(candidate.path(), StandardCharsets.UTF_8);
                    if (StringUtils.isBlank(fileContent)) {
                        skipped++;
                        continue;
                    }
                    final JSONObject article = parseArticle(candidate.displayName(), fileContent);
                    article.put(Article.ARTICLE_AUTHOR_ID, adminId);
                    article.put(Article.ARTICLE_STATUS,
                            publishImmediately ? Article.ARTICLE_STATUS_C_PUBLISHED : Article.ARTICLE_STATUS_C_DRAFT);

                    if (!resolveImportPermalink(article, skipDuplicatePermalink)) {
                        skipped++;
                        continue;
                    }

                    final JSONObject request = new JSONObject();
                    request.put(Article.ARTICLE, article);
                    articleMgmtService.addArticle(request);
                    imported++;
                } catch (final Exception e) {
                    LOGGER.log(Level.WARN, "Batch import failed for [" + candidate.displayName() + "]", e);
                    failed++;
                    appendError(errors, candidate.displayName() + ": " + safeMsg(e));
                }
            }
        } catch (final Exception fatal) {
            LOGGER.log(Level.ERROR, "Batch import failed", fatal);
            failed++;
            appendError(errors, "批量导入异常：" + safeMsg(fatal));
        } finally {
            if (tempUnpackRoot != null) {
                FileUtils.deleteQuietly(tempUnpackRoot.toFile());
            }
        }

        return new JSONObject()
                .put("imported", imported)
                .put("skipped", skipped)
                .put("failed", failed)
                .put("errors", errors);
    }

    private record StagedMarkdown(Path path, String displayName) { }

    /**
     * Resolves duplicate or invalid {@code articlePermalink} before calling {@code addArticle}.
     *
     * @return {@code true} to proceed with importing this article, {@code false} if skipped as a duplicate
     */
    private boolean resolveImportPermalink(final JSONObject article, final boolean skipDuplicatePermalink)
            throws Exception {
        final String raw = StringUtils.trimToEmpty(article.optString(Article.ARTICLE_PERMALINK));
        if (raw.isEmpty()) {
            article.remove(Article.ARTICLE_PERMALINK);
            return true;
        }
        final String normalized = normalizeImportedPermalink(raw);
        if (normalized.isEmpty()) {
            article.remove(Article.ARTICLE_PERMALINK);
            return true;
        }
        final boolean existingArticle = articleRepository != null && articleRepository.getByPermalink(normalized) != null;
        final boolean existingAny = existingArticle
                || (permalinkQueryService != null && permalinkQueryService.exist(normalized));

        if (existingAny) {
            if (skipDuplicatePermalink) {
                return false;
            }
            if (PermalinkQueryService.matchDefaultArticlePermalinkFormat(normalized)
                    || PermalinkQueryService.invalidArticlePermalinkFormat(normalized)) {
                article.remove(Article.ARTICLE_PERMALINK);
                return true;
            }
            final String unique = generateUniquePermalinkSuffix(normalized);
            if (unique != null) {
                article.put(Article.ARTICLE_PERMALINK, unique);
            } else {
                article.remove(Article.ARTICLE_PERMALINK);
            }
            return true;
        }

        if (PermalinkQueryService.invalidArticlePermalinkFormat(normalized)) {
            article.remove(Article.ARTICLE_PERMALINK);
            return true;
        }
        article.put(Article.ARTICLE_PERMALINK, normalized);
        return true;
    }

    private static String normalizeImportedPermalink(final String raw) {
        String s = StringUtils.trimToEmpty(raw);
        if (s.isEmpty()) {
            return "";
        }
        final int qIdx = s.indexOf('?');
        if (qIdx >= 0) {
            s = s.substring(0, qIdx);
        }
        final int hIdx = s.indexOf('#');
        if (hIdx >= 0) {
            s = s.substring(0, hIdx);
        }
        if (s.regionMatches(true, 0, "http://", 0, 7) || s.regionMatches(true, 0, "https://", 0, 8)) {
            try {
                s = StringUtils.defaultString(java.net.URI.create(s.replace(" ", "%20")).getPath());
            } catch (final Exception ignored) {
                return "";
            }
        }
        s = s.trim().replaceAll("\\s+", "-");
        while (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty() || "/".equals(s)) {
            return "";
        }
        if (!s.startsWith("/")) {
            s = "/" + s;
        }
        if (!PermalinkQueryService.invalidArticlePermalinkFormat(s)) {
            return s;
        }
        final int lastSlash = s.lastIndexOf('/');
        if (lastSlash > 0 && lastSlash < s.length() - 1) {
            String tail = "/" + s.substring(lastSlash + 1);
            if (Strings.isNumeric(tail.substring(1))) {
                tail = tail + ".html";
            }
            if (!PermalinkQueryService.invalidArticlePermalinkFormat(tail)) {
                return tail;
            }
        }
        return "";
    }

    private String generateUniquePermalinkSuffix(final String basePermalink) throws Exception {
        final boolean endsWithHtml = basePermalink.toLowerCase(Locale.ROOT).endsWith(".html");
        final String stem = endsWithHtml
                ? basePermalink.substring(0, basePermalink.length() - 5)
                : basePermalink;
        for (int seq = 2; seq <= 100; seq++) {
            final String candidate = stem + "-" + seq + (endsWithHtml ? ".html" : "");
            if (PermalinkQueryService.invalidArticlePermalinkFormat(candidate)) {
                return null;
            }
            final boolean taken = (articleRepository != null && articleRepository.getByPermalink(candidate) != null)
                    || (permalinkQueryService != null && permalinkQueryService.exist(candidate));
            if (!taken) {
                return candidate;
            }
        }
        return null;
    }

    private static void appendError(final JSONArray errors, final String message) {
        if (errors.length() < 100) {
            errors.put(message);
        }
    }

    private static String safeMsg(final Throwable t) {
        final String msg = t.getMessage();
        return (msg == null || msg.isBlank()) ? t.getClass().getSimpleName() : msg;
    }

    private JSONObject parseArticle(final String fileName, String fileContent) {
        fileContent = StringUtils.trim(fileContent);
        String frontMatter = StringUtils.substringBefore(fileContent, "---");
        if (StringUtils.isBlank(frontMatter)) {
            fileContent = StringUtils.substringAfter(fileContent, "---");
            frontMatter = StringUtils.substringBefore(fileContent, "---");
        }

        final JSONObject ret = new JSONObject();
        final LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(20);
        options.setNestingDepthLimit(30);
        options.setCodePointLimit(1024 * 1024);
        final Yaml yaml = new Yaml(new SafeConstructor(options));
        Map elems;

        try {
            final Object loaded = yaml.load(frontMatter);
            if (!(loaded instanceof Map)) {
                throw new IllegalArgumentException("Front matter must be a mapping");
            }
            elems = (Map) loaded;
        } catch (final Exception e) {
            // treat it as plain markdown
            final long now = System.currentTimeMillis();
            ret.put(Keys.OBJECT_ID, Ids.genTimeMillisId());
            ret.put(Article.ARTICLE_TITLE, StringUtils.substringBeforeLast(fileName, "."));
            ret.put(Article.ARTICLE_CONTENT, fileContent);
            ret.put(Article.ARTICLE_ABSTRACT, Article.getAbstractText(fileContent));
            ret.put(Article.ARTICLE_CREATED, now);
            ret.put(Article.ARTICLE_UPDATED, now);
            ret.put(Article.ARTICLE_TAGS_REF, DEFAULT_TAG);
            ret.put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED);
            ret.put(Article.ARTICLE_COMMENTABLE, true);
            ret.put(Article.ARTICLE_VIEW_PWD, "");

            return ret;
        }

        String title = elems.get("title") == null ? null : String.valueOf(elems.get("title"));
        if (StringUtils.isBlank(title)) {
            title = StringUtils.substringBeforeLast(fileName, ".");
        }
        ret.put(Article.ARTICLE_TITLE, title);

        String content = StringUtils.substringAfter(fileContent, frontMatter);
        if (StringUtils.startsWith(content, "---")) {
            content = StringUtils.substringAfter(content, "---");
            content = StringUtils.trim(content);
        }
        ret.put(Article.ARTICLE_CONTENT, content);

        final String abs = parseAbstract(elems, content);
        ret.put(Article.ARTICLE_ABSTRACT, abs);

        final Date date = parseDate(elems, "date", new Date());
        final Date updatedDate = parseDate(elems, "updated", date);
        ret.put(Article.ARTICLE_CREATED, date.getTime());
        ret.put(Article.ARTICLE_UPDATED, updatedDate.getTime());

        // Generate a collision-free unique article oId while preserving ARTICLE_CREATED / ARTICLE_UPDATED timestamps.
        ret.put(Keys.OBJECT_ID, Ids.genTimeMillisId());

        final Object permalinkObj = elems.get("permalink");
        final String permalink = permalinkObj == null ? null : String.valueOf(permalinkObj);
        if (StringUtils.isNotBlank(permalink)) {
            ret.put(Article.ARTICLE_PERMALINK, permalink.trim());
        }

        final List<String> tags = parseTags(elems);
        final StringBuilder tagBuilder = new StringBuilder();
        for (final String tag : tags) {
            tagBuilder.append(tag).append(",");
        }
        tagBuilder.deleteCharAt(tagBuilder.length() - 1);
        ret.put(Article.ARTICLE_TAGS_REF, tagBuilder.toString());

        ret.put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED);
        ret.put(Article.ARTICLE_COMMENTABLE, true);
        ret.put(Article.ARTICLE_VIEW_PWD, "");

        return ret;
    }

    private String parseAbstract(final Map map, final String content) {
        Object retObj = map.get("description");
        if (null == retObj) {
            retObj = map.get("summary");
        }
        if (null == retObj) {
            retObj = map.get("abstract");
        }
        final String ret = retObj == null ? null : String.valueOf(retObj);
        if (StringUtils.isNotBlank(ret)) {
            return ret;
        }

        return Article.getAbstractText(content);
    }

    private Date parseDate(final Map map, final String key, final Date fallback) {
        final Object date = map.get(key);
        if (null == date) {
            return fallback;
        }

        if (date instanceof Date d) {
            return d;
        }
        if (date instanceof String dateStr) {
            final String trimmed = dateStr.trim();
            if (trimmed.isEmpty()) {
                return fallback;
            }
            try {
                return DateUtils.parseDate(trimmed, new String[]{
                        "yyyy/MM/dd HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "dd/MM/yyyy HH:mm:ss",
                        "dd-MM-yyyy HH:mm:ss", "yyyyMMdd HH:mm:ss",
                        "yyyy/MM/dd HH:mm", "yyyy-MM-dd HH:mm", "dd/MM/yyyy HH:mm",
                        "dd-MM-yyyy HH:mm", "yyyyMMdd HH:mm",
                        "yyyy-MM-dd", "yyyy/MM/dd", "yyyyMMdd",
                        "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ssXXX"});
            } catch (final Exception e) {
                LOGGER.log(Level.ERROR, "Parse date [" + date + "] failed", e);

                throw new RuntimeException(e);
            }
        }

        return fallback;
    }

    @SuppressWarnings("unchecked")
    private List<String> parseTags(final Map map) {
        final List<String> ret = new ArrayList<>();

        Object tags = map.get("tags");
        if (null == tags) {
            tags = map.get("category");
        }
        if (null == tags) {
            tags = map.get("categories");
        }
        if (null == tags) {
            tags = map.get("keyword");
        }
        if (null == tags) {
            tags = map.get("keywords");
        }
        if (null == tags) {
            ret.add(DEFAULT_TAG);

            return ret;
        }

        final Collection<?> rawTags;
        if (tags instanceof String tagStr) {
            final String[] tagArr = tagStr.contains(",") ? tagStr.split(",") : tagStr.split("\\s+");
            rawTags = Arrays.asList(tagArr);
        } else if (tags instanceof Collection<?> col) {
            rawTags = col;
        } else {
            rawTags = List.of(String.valueOf(tags));
        }

        final Set<String> tagSet = new TreeSet<>();
        for (final Object item : rawTags) {
            final String tag = item == null ? "" : String.valueOf(item).trim();
            if (StringUtils.isBlank(tag)) {
                tagSet.add(DEFAULT_TAG);
            } else {
                tagSet.add(tag);
            }
        }
        if (tagSet.isEmpty()) {
            tagSet.add(DEFAULT_TAG);
        }
        ret.addAll(tagSet);

        return ret;
    }
}
