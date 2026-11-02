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

import static pers.adlered.blog_platform_export_tool.Launcher.run;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.b3log.latke.http.Response;

import org.b3log.latke.http.FileUpload;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.b3log.latke.Keys;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.repository.Transaction;
import org.b3log.latke.http.HttpMethod;
import org.b3log.latke.http.RequestContext;
import org.b3log.solo.web.annotation.RequestProcessing;
import org.b3log.latke.ioc.Singleton;
import org.b3log.latke.util.Ids;
import org.b3log.solo.bolo.tool.ZipFilterUtils;
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
import org.apache.commons.io.FileUtils;
import org.json.JSONObject;

import pers.adlered.blog_platform_export_tool.module.TranslateResult;

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
        List<FileUpload> itemList = java.util.Collections.emptyList();
        try {
            directory = Files.createTempDirectory("bolo-cnblogs-");
            itemList = Uploads.files(context.getRequest());
            for (FileUpload item : itemList) {
                Uploads.stage(item, directory, "zip", "xml");
            }
            List<TranslateResult> list = run("CNBlogs", directory.toString());
            for (TranslateResult i : list) {
                Transaction transaction = null;
                try {
                    transaction = userRepository.beginTransaction();
                    final JSONObject article = new JSONObject();
                    final String ret = Ids.genTimeMillisId();
                    article.put(Keys.OBJECT_ID, ret);
                    article.put(Article.ARTICLE_TITLE, i.getTitle());
                    String content = i.getArticleContent();
                    article.put(Article.ARTICLE_ABSTRACT_TEXT, "");
                    article.put(Article.ARTICLE_ABSTRACT, "");
                    article.put(Article.ARTICLE_CONTENT, content);
                    article.put(Article.ARTICLE_TAGS_REF, "待分类");
                    final String permalink = "/articles/" + DateFormatUtils.format(i.getDate(), "yyyy/MM/dd") + "/"
                            + article.optString(Keys.OBJECT_ID) + ".html";
                    article.put(Article.ARTICLE_PERMALINK, permalink);
                    article.put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED);
                    article.put(Article.ARTICLE_SIGN_ID, "1");
                    article.put(Article.ARTICLE_COMMENT_COUNT, 0);
                    article.put(Article.ARTICLE_VIEW_COUNT, 0);
                    final JSONObject admin = userRepository.getAdmin();
                    final long now = System.currentTimeMillis();
                    article.put(Article.ARTICLE_CREATED, i.getDate().getTime());
                    article.put(Article.ARTICLE_UPDATED, now);
                    article.put(Article.ARTICLE_PUT_TOP, false);
                    article.put(Article.ARTICLE_RANDOM_DOUBLE, Math.random());
                    article.put(Article.ARTICLE_AUTHOR_ID, admin.optString(Keys.OBJECT_ID));
                    article.put(Article.ARTICLE_COMMENTABLE, true);
                    article.put(Article.ARTICLE_VIEW_PWD, "");
                    final String articleImg1URL = Article.getArticleImg1URL(article);
                    article.put(Article.ARTICLE_IMG1_URL, articleImg1URL);
                    long contentLength = content.getBytes(StandardCharsets.UTF_8).length;
                    System.out.println("========== 正在导入 ==========");
                    System.out.println("文章标题：" + i.getTitle());
                    System.out.println("文章长度：" + contentLength);
                    initService.importArticle(article);
                    System.out.println("========== 导入完成 ==========");

                    transaction.commit();
                } catch (Exception e) {
                    if (transaction != null && transaction.isActive()) {
                        transaction.rollback();
                    }
                    e.printStackTrace();
                    continue;
                }
            }
        } catch (Exception e) {
            org.b3log.solo.web.JsonResponses.json(context).renderMsg("从博客园恢复备份失败，请重试！");

            return;
        } finally {
            FileUtils.deleteQuietly(directory == null ? null : directory.toFile());
        }

        System.out.println("博客园文章已全部导入。");
        org.b3log.solo.web.JsonResponses.json(context).renderMsg("从博客园恢复备份成功。");

        return;
    }

    @RequestProcessing(value = "/import/markdown", method = { HttpMethod.POST })
    public void importFromMarkdown(final RequestContext context) {
        if (!org.b3log.solo.util.AdminRequests.allowWrite(context)) return;

        Path directory = null;
        List<FileUpload> itemList = java.util.Collections.emptyList();
        String result = "";
        try {
            directory = Files.createTempDirectory("bolo-markdown-");
            final Path articles = Files.createDirectory(directory.resolve("articles"));
            itemList = Uploads.files(context.getRequest());
            for (FileUpload item : itemList) {
                final File file = Uploads.stage(item, directory, "zip");
                ZipFilterUtils.unpackFilteredZip(file, articles.toFile(), null);
                Files.delete(file.toPath());
            }
            result = importService.importMarkdownsSync(articles.toFile());
        } catch (Exception e) {
            org.b3log.solo.web.JsonResponses.json(context).renderMsg("从 Markdown zip 恢复备份失败，请重试！" + result);

            return;
        } finally {
            FileUtils.deleteQuietly(directory == null ? null : directory.toFile());
        }

        org.b3log.solo.web.JsonResponses.json(context).renderMsg("从 Markdown zip 恢复备份成功。" + result);

        return;
    }
}
