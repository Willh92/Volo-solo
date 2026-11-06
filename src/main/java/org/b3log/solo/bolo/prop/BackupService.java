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
package org.b3log.solo.bolo.prop;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.Level;
import org.b3log.latke.Keys;
import org.b3log.latke.http.FileUpload;
import org.b3log.latke.http.HttpMethod;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.ioc.Singleton;
import org.b3log.latke.util.Ids;
import org.b3log.solo.bolo.tool.ZipFilterUtils;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Option;
import org.b3log.solo.repository.UserRepository;
import org.b3log.solo.service.ArticleMgmtService;
import org.b3log.solo.service.ExportService;
import org.b3log.solo.service.ImportService;
import org.b3log.solo.service.InitService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.util.Solos;
import org.b3log.solo.util.Uploads;
import org.b3log.solo.web.annotation.RequestProcessing;
import org.json.JSONObject;

import pers.adlered.blog_platform_export_tool.module.TranslateResult;
import pers.adlered.blog_platform_export_tool.module.cnblogs.CNBLOGSModuleServiceImpl;
import pers.adlered.blog_platform_export_tool.util.Decompress;

/**
 * <h3>bolo-solo</h3>
 * <p>
 * 备份服务
 * </p>
 *
 * @author : https://github.com/adlered
 * @date : 2020-01-05 13:39
 **/
@Singleton
public class BackupService {

    private static final Logger LOGGER = Logger.getLogger(BackupService.class);

    /**
     * Option query service.
     */
    @Inject
    private OptionQueryService optionQueryService;

    /**
     * Export service.
     */
    @Inject
    private ExportService exportService;

    /**
     * Article Mgmt Service.
     */
    @Inject
    private ArticleMgmtService articleMgmtService;

    @Inject
    private org.b3log.solo.service.GitHubRepositoryService gitHubRepositoryService;

    /**
     * User repository.
     */
    @Inject
    private UserRepository userRepository;

    /**
     * Init service.
     */
    @Inject
    private InitService initService;

    /**
     * Import service.
     */
    @Inject
    private ImportService importService;

    @RequestProcessing(value = "/sync/github/repo/do/get", method = HttpMethod.POST)
    public void syncGitHubRepos(final RequestContext context) {
        if (!org.b3log.solo.util.AdminRequests.allowWrite(context)) return;
        try {
            final String githubId = java.util.Objects.toString(context.param("githubId"), "").strip();
            if (!githubId.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")) {
                org.b3log.solo.util.AdminRequests.reject(context, 400, "请填写正确的 GitHub 用户名。");
                return;
            }
            final boolean ok = articleMgmtService.refreshGitHub(githubId);
            context.setStatus(ok ? 200 : 502);
            context.renderJSON(new JSONObject().put("sc", ok).put("msg", ok ? "开源项目文章已更新。" : "更新失败，请核对 GitHub 用户名、公开仓库和 API 访问状态。"));
        } catch (final Exception e) {
            org.b3log.solo.util.AdminRequests.reject(context, 500, "更新开源项目文章失败，请查看本地日志。");
        }
    }

    @RequestProcessing(value = "/console/github-repositories", method = HttpMethod.GET)
    public void gitHubRepositories(final RequestContext context) {
        context.setHeader("Cache-Control", "no-store");
        if (!Solos.isAdminLoggedIn(context)) {
            org.b3log.solo.util.AdminRequests.reject(context, 401, "请先登录管理后台。");
            return;
        }
        final JSONObject preference = optionQueryService.getPreference();
        context.renderJSON(gitHubRepositoryService.consoleData(preference == null ? "" : preference.optString(Option.ID_C_MY_GITHUB_ID).strip()));
    }

    @RequestProcessing(value = "/prop/backup/github/do/upload", method = HttpMethod.POST)
    public void uploadBackupToGithub(final RequestContext context) {
        if (!org.b3log.solo.util.AdminRequests.allowWrite(context)) return;
        try {
            boolean enableAutoFlushGitHubProfile;
            try {
                enableAutoFlushGitHubProfile = optionQueryService
                        .getOptionById(Option.ID_C_ENABLE_AUTO_FLUSH_BLOG_TO_GITHUB_PROFILE)
                        .optBoolean(Option.OPTION_VALUE);
            } catch (Exception e) {
                enableAutoFlushGitHubProfile = false;
            }
            final boolean ok = exportService.exportGitHub(enableAutoFlushGitHubProfile);
            context.setStatus(ok ? 200 : 502);
            context.renderJSON(new JSONObject().put("sc", ok).put("msg", ok ? "GitHub 备份已完成。" : "GitHub 同步未完成，请检查 Token 的仓库读写权限、公开文章和网络连接。"));
        } catch (final Exception e) {
            org.b3log.solo.util.AdminRequests.reject(context, 500, "GitHub 同步失败，请查看本地日志。");
        }
    }

    @RequestProcessing(value = "/prop/backup/hacpai/do/upload", method = HttpMethod.POST)
    public void uploadBackupToHacpai(final RequestContext context) {
        if (!org.b3log.solo.util.AdminRequests.allowWrite(context)) return;
        if (!org.b3log.solo.util.PluginUtil.b3logPluginEnabled()) {
            context.sendError(404);
            return;
        }

        try {
            exportService.exportHacPai(true);

            org.b3log.solo.web.JsonResponses.json(context).renderCode(200);
            org.b3log.solo.web.JsonResponses.json(context).renderMsg("Exported backup to HacPai manual successfully.");

            return;
        } catch (final Exception e) {
            context.sendError(500);

            return;
        }
    }

    @RequestProcessing(value = "/import/cnblogs", method = { HttpMethod.POST })
    public void importFromCnblogs(final RequestContext context) {
        if (!org.b3log.solo.util.AdminRequests.allowWrite(context)) return;

        Path directory = null;
        try {
            final JSONObject admin = userRepository.getAdmin();
            if (admin == null) {
                org.b3log.solo.web.JsonResponses.json(context).renderMsg("系统尚未初始化管理员账号。");
                return;
            }
            final String adminId = admin.optString(Keys.OBJECT_ID);

            directory = Files.createTempDirectory("bolo-cnblogs-");
            final List<FileUpload> itemList = Uploads.files(context.getRequest());
            for (int idx = 0; idx < itemList.size(); idx++) {
                final FileUpload item = itemList.set(idx, null);
                if (item != null) {
                    Uploads.stage(item, directory, "zip", "xml");
                }
            }

            final String targetDir = Decompress.unpackDirectory(directory.toString());
            final List<TranslateResult> list = new CNBLOGSModuleServiceImpl().analyze(targetDir);
            int importedCount = 0;
            for (final TranslateResult i : list) {
                if (i == null) {
                    continue;
                }
                try {
                    final JSONObject article = new JSONObject();
                    article.put(Keys.OBJECT_ID, Ids.genTimeMillisId());
                    final String title = (i.getTitle() == null || i.getTitle().isBlank()) ? "Untitled" : i.getTitle().trim();
                    final String content = i.getArticleContent() == null ? "" : i.getArticleContent();
                    final String abstractText = Article.getAbstractText(content);
                    article.put(Article.ARTICLE_TITLE, title);
                    article.put(Article.ARTICLE_ABSTRACT, abstractText);
                    article.put(Article.ARTICLE_ABSTRACT_TEXT, abstractText);
                    article.put(Article.ARTICLE_CONTENT, content);
                    article.put(Article.ARTICLE_TAGS_REF, "待分类");
                    article.put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED);
                    article.put(Article.ARTICLE_SIGN_ID, "1");
                    final Date pubDate = i.getDate() != null ? i.getDate() : new Date();
                    article.put(Article.ARTICLE_CREATED, pubDate.getTime());
                    article.put(Article.ARTICLE_UPDATED, pubDate.getTime());
                    article.put(Article.ARTICLE_AUTHOR_ID, adminId);
                    article.put(Article.ARTICLE_COMMENTABLE, true);
                    article.put(Article.ARTICLE_VIEW_PWD, "");

                    final JSONObject request = new JSONObject();
                    request.put(Article.ARTICLE, article);
                    articleMgmtService.addArticle(request);
                    importedCount++;
                } catch (final Exception e) {
                    LOGGER.log(Level.WARN, "Import CNBlogs article [" + i.getTitle() + "] failed", e);
                }
            }
            LOGGER.log(Level.INFO, "Imported [" + importedCount + "] articles from CNBlogs backup");
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Import from CNBlogs backup failed", e);
            org.b3log.solo.web.JsonResponses.json(context).renderMsg("从博客园恢复备份失败，请重试！");
            return;
        } finally {
            Decompress.clearCurrentPath();
            FileUtils.deleteQuietly(directory == null ? null : directory.toFile());
        }

        org.b3log.solo.web.JsonResponses.json(context).renderMsg("从博客园恢复备份成功。");
    }

    @RequestProcessing(value = "/import/markdown", method = { HttpMethod.POST })
    public void importFromMarkdown(final RequestContext context) {
        if (!org.b3log.solo.util.AdminRequests.allowWrite(context)) return;

        Path directory = null;
        String result = "";
        try {
            directory = Files.createTempDirectory("bolo-markdown-");
            final Path articles = Files.createDirectory(directory.resolve("articles"));
            final List<FileUpload> itemList = Uploads.files(context.getRequest());
            for (int idx = 0; idx < itemList.size(); idx++) {
                final FileUpload item = itemList.set(idx, null);
                if (item == null) {
                    continue;
                }
                final File file = Uploads.stage(item, directory, "zip");
                ZipFilterUtils.unpackFilteredZip(file, articles.toFile(), entry -> {
                    final String lower = entry.toLowerCase(Locale.ROOT);
                    return (lower.endsWith(".md") || lower.endsWith(".markdown")) ? entry : null;
                });
                Files.deleteIfExists(file.toPath());
            }
            result = importService.importMarkdownsSync(articles.toFile());
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Import from Markdown zip failed", e);
            org.b3log.solo.web.JsonResponses.json(context).renderMsg("从 Markdown zip 恢复备份失败，请重试！" + result);
            return;
        } finally {
            FileUtils.deleteQuietly(directory == null ? null : directory.toFile());
        }

        org.b3log.solo.web.JsonResponses.json(context).renderMsg("从 Markdown zip 恢复备份成功。" + result);
    }
}
