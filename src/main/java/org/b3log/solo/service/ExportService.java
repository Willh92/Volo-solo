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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.ioc.BeanManager;
import org.b3log.latke.ioc.Inject;
import org.apache.logging.log4j.Level;
import org.b3log.solo.logging.Logger;
import org.b3log.latke.model.Plugin;
import org.b3log.latke.model.User;
import org.b3log.latke.repository.Filter;
import org.b3log.latke.repository.FilterOperator;
import org.b3log.latke.repository.PropertyFilter;
import org.b3log.latke.repository.Query;
import org.b3log.latke.repository.Repository;
import org.b3log.latke.repository.RepositoryException;
import org.b3log.latke.repository.SortDirection;
import org.b3log.latke.repository.jdbc.JdbcRepository;
import org.b3log.latke.repository.jdbc.util.Connections;
import org.b3log.latke.service.annotation.Service;
import org.b3log.latke.util.Strings;
import org.b3log.solo.SoloApplication;
import org.b3log.solo.bolo.Global;
import org.b3log.solo.model.ArchiveDate;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Category;
import org.b3log.solo.model.Comment;
import org.b3log.solo.model.Follow;
import org.b3log.solo.model.Link;
import org.b3log.solo.model.Option;
import org.b3log.solo.model.Page;
import org.b3log.solo.model.Tag;
import org.b3log.solo.repository.ArchiveDateArticleRepository;
import org.b3log.solo.repository.ArchiveDateRepository;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.repository.ArticleRevisionRepository;
import org.b3log.solo.repository.FollowRepository;
import org.b3log.solo.repository.CategoryRepository;
import org.b3log.solo.repository.CategoryTagRepository;
import org.b3log.solo.repository.CommentRepository;
import org.b3log.solo.repository.LinkRepository;
import org.b3log.solo.repository.OptionRepository;
import org.b3log.solo.repository.PageRepository;
import org.b3log.solo.repository.PluginRepository;
import org.b3log.solo.repository.TagArticleRepository;
import org.b3log.solo.repository.TagRepository;
import org.b3log.solo.repository.UserRepository;
import org.b3log.solo.util.GitHubs;
import org.b3log.solo.util.Solos;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.yaml.snakeyaml.Yaml;

import jodd.http.HttpRequest;
import jodd.http.HttpResponse;
import jodd.io.ZipUtil;

/**
 * Export service.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 2.5.0
 */
@Service
public class ExportService {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(ExportService.class);

    private static final int EXPORT_PAGE_SIZE = 200;

    /**
     * Archive date repository.
     */
    @Inject
    private ArchiveDateRepository archiveDateRepository;

    /**
     * Archive date-Article repository.
     */
    @Inject
    private ArchiveDateArticleRepository archiveDateArticleRepository;

    /**
     * Article repository.
     */
    @Inject
    private ArticleRepository articleRepository;

    /**
     * Category repository.
     */
    @Inject
    private CategoryRepository categoryRepository;

    /**
     * Category-Tag relation repository.
     */
    @Inject
    private CategoryTagRepository categoryTagRepository;

    /**
     * Comment repository.
     */
    @Inject
    private CommentRepository commentRepository;

    /**
     * Link repository.
     */
    @Inject
    private LinkRepository linkRepository;

    /**
     * Option repository.
     */
    @Inject
    private OptionRepository optionRepository;

    /**
     * Page repository.
     */
    @Inject
    private PageRepository pageRepository;

    /**
     * Plugin repository.
     */
    @Inject
    private PluginRepository pluginRepository;

    /**
     * Tag repository.
     */
    @Inject
    private TagRepository tagRepository;

    /**
     * Tag-Article repository.
     */
    @Inject
    private TagArticleRepository tagArticleRepository;

    /**
     * User repository.
     */
    @Inject
    private UserRepository userRepository;

    @Inject
    private FollowRepository followRepository;

    @Inject
    private ArticleRevisionRepository articleRevisionRepository;

    /**
     * Option query service.
     */
    @Inject
    private OptionQueryService optionQueryService;

    /**
     * Article query service.
     */
    @Inject
    private ArticleQueryService articleQueryService;

    /**
     * Statistic query service.
     */
    @Inject
    private StatisticQueryService statisticQueryService;

    /**
     * Tag query service.
     */
    @Inject
    private TagQueryService tagQueryService;

    /**
     * Exports SQL in zip bytes.
     *
     * @return data bytes, returns {@code null} if occurs exception
     */
    public byte[] exportSQL() {
        final Latkes.RuntimeDatabase runtimeDatabase = Latkes.getRuntimeDatabase();
        if (Latkes.RuntimeDatabase.H2 != runtimeDatabase && Latkes.RuntimeDatabase.MYSQL != runtimeDatabase) {
            LOGGER.log(Level.ERROR, "Just support MySQL/H2 export now");

            return null;
        }

        final String dbUser = Latkes.getLocalProperty("jdbc.username");
        final String dbPwd = Latkes.getLocalProperty("jdbc.password");
        final String dbURL = Latkes.getLocalProperty("jdbc.URL");
        final String date = DateFormatUtils.format(new Date(), "yyyyMMddHHmmss");

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("volo-export-sql-");
            final Path sqlPath = tempDir.resolve("solo-" + date + ".sql");
            final File localFile = sqlPath.toFile();

            if (Latkes.RuntimeDatabase.MYSQL == runtimeDatabase) {
                String db = StringUtils.substringAfterLast(dbURL, "/");
                db = StringUtils.substringBefore(db, "?");

                try {
                    final ProcessBuilder pb = new ProcessBuilder("mysqldump", "-u" + dbUser, "--databases", db);
                    if (StringUtils.isNotBlank(dbPwd)) {
                        pb.environment().put("MYSQL_PWD", dbPwd);
                    }
                    pb.redirectErrorStream(false);
                    final Process process = pb.start();
                    process.getOutputStream().close();
                    final java.util.concurrent.atomic.AtomicReference<IOException> stdoutErrHolder =
                            new java.util.concurrent.atomic.AtomicReference<>();
                    final java.util.concurrent.atomic.AtomicReference<String> stderrHolder =
                            new java.util.concurrent.atomic.AtomicReference<>("");
                    final Thread outReader = new Thread(() -> {
                        try (var stdout = process.getInputStream();
                             OutputStream fileOut = Files.newOutputStream(sqlPath)) {
                            stdout.transferTo(fileOut);
                        } catch (final IOException ioe) {
                            stdoutErrHolder.set(ioe);
                        }
                    }, "volo-mysqldump-stdout");
                    outReader.setDaemon(true);
                    final Thread errReader = new Thread(() -> {
                        try (var stderr = process.getErrorStream()) {
                            final byte[] errBytes = stderr.readNBytes(8192);
                            stderrHolder.set(new String(errBytes, StandardCharsets.UTF_8));
                        } catch (final IOException ignored) { }
                    }, "volo-mysqldump-stderr");
                    errReader.setDaemon(true);
                    outReader.start();
                    errReader.start();
                    if (!process.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)) {
                        process.destroyForcibly();
                        LOGGER.log(Level.ERROR, "Export failed, mysqldump timed out after 5 minutes");
                        return null;
                    }
                    outReader.join(5000L);
                    errReader.join(2000L);
                    if (process.exitValue() != 0) {
                        LOGGER.log(Level.ERROR, "Export failed, mysqldump exited with code "
                                + process.exitValue() + ": " + stderrHolder.get());
                        return null;
                    }
                    if (stdoutErrHolder.get() != null) {
                        throw stdoutErrHolder.get();
                    }
                } catch (final Exception e) {
                    LOGGER.log(Level.ERROR, "Export failed, please check command \"mysqldump\" is on your system", e);

                    return null;
                }
            } else if (Latkes.RuntimeDatabase.H2 == runtimeDatabase) {
                try (final Connection connection = Connections.getConnection();
                     final Statement statement = connection.createStatement();
                     final ResultSet resultSet = statement.executeQuery("SCRIPT");
                     final BufferedWriter writer = Files.newBufferedWriter(sqlPath, StandardCharsets.UTF_8)) {
                    while (resultSet.next()) {
                        final String stmt = resultSet.getString(1);
                        if (stmt != null) {
                            writer.write(stmt);
                            writer.write(Strings.LINE_SEPARATOR);
                        }
                    }
                } catch (final Exception e) {
                    LOGGER.log(Level.ERROR, "Export failed", e);

                    return null;
                }
            }

            if (!localFile.isFile() || localFile.length() == 0L) {
                LOGGER.log(Level.ERROR, "Export failed, executing export script returns empty");

                return null;
            }

            final File zipFile = ZipUtil.zip(localFile);
            try (final FileInputStream inputStream = new FileInputStream(zipFile)) {
                return IOUtils.toByteArray(inputStream);
            }
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Export failed", e);

            return null;
        } finally {
            if (tempDir != null) {
                FileUtils.deleteQuietly(tempDir.toFile());
            }
        }
    }

    /**
     * Exports public articles to GitHub repo "bolo-blog and user index repo".
     * 
     */
    public synchronized boolean exportGitHub(final boolean enableAutoFlushGitHubProfile) {
        final JSONObject preference = optionQueryService.getPreference();
        if (null == preference) {
            return false;
        }
        if (Latkes.RuntimeMode.PRODUCTION != Latkes.getRuntimeMode()) {
            return false;
        }
        String pat = preference.optString(Option.ID_C_GITHUB_PAT);
        if (StringUtils.isBlank(pat)) {
            return false;
        }
        final JSONObject gitHubUser = GitHubs.getGitHubUser(pat);
        if (null == gitHubUser) {
            return false;
        }
        final Filter published = new PropertyFilter(Article.ARTICLE_STATUS, FilterOperator.EQUAL,
                Article.ARTICLE_STATUS_C_PUBLISHED);
        List<JSONObject> recentArticles;
        try {
            recentArticles = articleRepository.getList(new Query().setFilter(published)
                    .select(Keys.OBJECT_ID, Article.ARTICLE_TITLE, Article.ARTICLE_PERMALINK)
                    .addSort(Article.ARTICLE_CREATED, SortDirection.DESCENDING).setPage(1, 20));
        } catch (RepositoryException e) {
            LOGGER.error(String.format("export blog article error: %s", e.getMessage()));
            return false;
        }
        if (null == recentArticles || recentArticles.size() == 0) {
            return false;
        }
        final boolean backup = exportGitHubBlog(recentArticles, preference, gitHubUser);
        final boolean profile = !enableAutoFlushGitHubProfile || exportGitHubProfile(recentArticles, preference, gitHubUser);
        return backup && profile;
    }

    /**
     * Exports public articles to GitHub repo "solo-blog". 同步 GitHub solo-blog 仓库功能
     * https://github.com/88250/solo/issues/125
     * 
     */
    private boolean exportGitHubBlog(final List<JSONObject> recentArticles, final JSONObject preference,
            final JSONObject gitHubUser) {
        try {
            String pat = preference.optString(Option.ID_C_GITHUB_PAT);
            final JSONObject mds = exportHexoMDs(true);
            JdbcRepository.dispose();
            final List<JSONObject> posts = (List<JSONObject>) mds.opt("posts");

            File localFile = null;
            File zipFile = null;
            byte[] zipData;
            try {
                localFile = Files.createTempDirectory("solo-blog-repo-").toFile();
                final File postDir = new File(localFile, "posts");
                exportHexoMd(posts, postDir.getPath());

                zipFile = ZipUtil.zip(localFile);
                try (final FileInputStream inputStream = new FileInputStream(zipFile)) {
                    zipData = IOUtils.toByteArray(inputStream);
                }
            } finally {
                if (localFile != null) {
                    FileUtils.deleteQuietly(localFile);
                }
                if (zipFile != null) {
                    FileUtils.deleteQuietly(zipFile);
                }
            }

            final String clientTitle = preference.optString(Option.ID_C_BLOG_TITLE);
            final String clientSubtitle = preference.optString(Option.ID_C_BLOG_SUBTITLE);

            final String loginName = gitHubUser.optString("login");
            final String repoName = "bolo-blog";

            boolean ok = GitHubs.createOrUpdateGitHubRepo(pat, loginName, repoName,
                    "✍️ " + clientTitle + " - " + clientSubtitle, Latkes.getServePath());
            if (!ok) {
                return false;
            }
            final String readme = genSoloBlogReadme(recentArticles, clientTitle, clientSubtitle,
                    preference.optString(Option.ID_C_FAVICON_URL), loginName + "/" + repoName);
            ok = diffUploadReadme(pat, repoName, loginName, readme, "bolo-blog-readme.md");
            if (ok) {
                ok = GitHubs.updateFile(pat, loginName, repoName, "backup.zip", zipData);
            }
            return ok;
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Exports public articles to your repo failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Exports public articles to github index repo.
     */
    private boolean exportGitHubProfile(final List<JSONObject> recentArticles, final JSONObject preference,
            final JSONObject gitHubUser) {
        try {
            String pat = preference.optString(Option.ID_C_GITHUB_PAT);
            final String loginName = gitHubUser.optString("login");
            final String clientTitle = preference.optString(Option.ID_C_BLOG_TITLE);
            final String clientSubtitle = preference.optString(Option.ID_C_BLOG_SUBTITLE);
            boolean ok = GitHubs.createOrUpdateGitHubRepo(pat, loginName, loginName,
                    "✍️ " + clientTitle + " - " + clientSubtitle, Latkes.getServePath());
            if (!ok) {
                return false;
            }

            final String readme = genMeRepoReadme(recentArticles, loginName);
            ok = diffUploadReadme(pat, loginName, loginName, readme, "profile-readme.md");
            JdbcRepository.dispose();
            return ok;
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Exports public articles to your repo failed: " + e.getMessage());
            return false;
        }
    }

    synchronized boolean diffUploadReadme(final String pat, final String repoName, final String loginName,
            final String readme,
            final String cacheName) throws IOException {
        LOGGER.info(String.format("begin get %s README.md:", repoName));
        final File file = githubCacheFile(loginName, repoName, cacheName);

        // 第一次运行的判断条件
        boolean firstTimeRun = false;
        String oldReadme = null;

        // 比对更新，但是第一次启动的时候并没有readme文件，尤其是 docker 环境中，没有上次的原始文件的情况更多
        try {
            oldReadme = FileUtils.readFileToString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            firstTimeRun = true;
        }

        if (!firstTimeRun) {
            if (oldReadme != null && oldReadme.equals(readme)) {
                LOGGER.info(String.format("not need update %s readme.", repoName));
                return true;
            }
        }
        boolean ok = uploadGitHubReadme(pat, loginName, repoName, "README.md",
                readme.getBytes(StandardCharsets.UTF_8));
        if (ok) {
            FileUtils.write(file, readme, StandardCharsets.UTF_8);
            LOGGER.log(Level.INFO, String.format("Exported public articles to your repo [%s]", repoName));
        }
        return ok;
    }

    protected boolean uploadGitHubReadme(final String pat, final String login, final String repository,
                                         final String path, final byte[] content) {
        return GitHubs.updateFile(pat, login, repository, path, content);
    }

    protected File githubCacheFile(final String login, final String repository, final String cacheName) {
        return new File(System.getProperty("base.dir", ".") + File.separator + ".cache" + File.separator + "github",
                "account-" + java.net.URLEncoder.encode(login + "/" + repository, StandardCharsets.UTF_8) + "-" + cacheName);
    }

    protected String gitHubBlogOrigin() { return Latkes.getServePath(); }

    /**
     * Exports public articles to admin's HacPai account.
     */
    public void exportHacPai(boolean manual) {
        LOGGER.log(Level.INFO, "Backup public articles to HacPai....");
        try {
            final JSONObject preference = optionQueryService.getPreference();
            if (null == preference) {
                return;
            }

            if (!manual) {
                if (!preference.optBoolean(Option.ID_C_SYNC_GITHUB)) {
                    return;
                }

                if (Latkes.RuntimeMode.PRODUCTION != Latkes.getRuntimeMode()) {
                    return;
                }
            }

            final JSONObject mds = exportHexoMDs(true);
            final List<JSONObject> posts = (List<JSONObject>) mds.opt("posts");

            File localFile = null;
            File zipFile = null;
            byte[] zipData;
            try {
                localFile = Files.createTempDirectory("solo-blog-repo-").toFile();
                final File postDir = new File(localFile, "posts");
                exportHexoMd(posts, postDir.getPath());

                zipFile = ZipUtil.zip(localFile);
                try (final FileInputStream inputStream = new FileInputStream(zipFile)) {
                    zipData = IOUtils.toByteArray(inputStream);
                }
            } finally {
                if (localFile != null) {
                    FileUtils.deleteQuietly(localFile);
                }
                if (zipFile != null) {
                    FileUtils.deleteQuietly(zipFile);
                }
            }

            final BeanManager beanManager = BeanManager.getInstance();
            UserQueryService userQueryService = beanManager.getReference(UserQueryService.class);
            String userName = userQueryService.getB3username();
            String userB3Key = userQueryService.getB3password();
            if (Option.DefaultPreference.DEFAULT_B3LOG_USERNAME.equals(userName)) {
                LOGGER.log(Level.INFO,
                        "Backup public articles to HacPai skipped because using the default B3 account.");

                return;
            }

            final String clientTitle = preference.optString(Option.ID_C_BLOG_TITLE);
            final String clientSubtitle = preference.optString(Option.ID_C_BLOG_SUBTITLE);

            final Set<String> articleIds = new HashSet<>();
            final Filter published = new PropertyFilter(Article.ARTICLE_STATUS, FilterOperator.EQUAL,
                    Article.ARTICLE_STATUS_C_PUBLISHED);

            final StringBuilder bodyBuilder = new StringBuilder("### 最新\n");
            final List<JSONObject> recentArticles = articleRepository.getList(new Query().setFilter(published)
                    .select(Keys.OBJECT_ID, Article.ARTICLE_TITLE, Article.ARTICLE_PERMALINK)
                    .addSort(Article.ARTICLE_CREATED, SortDirection.DESCENDING).setPage(1, 20));
            for (final JSONObject article : recentArticles) {
                final String title = article.optString(Article.ARTICLE_TITLE);
                final String link = Latkes.getServePath() + article.optString(Article.ARTICLE_PERMALINK);
                bodyBuilder.append("\n* [").append(title).append("](").append(link).append(")");
                articleIds.add(article.optString(Keys.OBJECT_ID));
            }
            bodyBuilder.append("\n\n");

            final StringBuilder mostViewBuilder = new StringBuilder();
            final List<JSONObject> mostViewArticles = articleRepository.getList(new Query().setFilter(published)
                    .select(Keys.OBJECT_ID, Article.ARTICLE_TITLE, Article.ARTICLE_PERMALINK)
                    .addSort(Article.ARTICLE_VIEW_COUNT, SortDirection.DESCENDING).setPage(1, 40));
            int count = 0;
            for (final JSONObject article : mostViewArticles) {
                final String articleId = article.optString(Keys.OBJECT_ID);
                if (!articleIds.contains(articleId)) {
                    final String title = article.optString(Article.ARTICLE_TITLE);
                    final String link = Latkes.getServePath() + article.optString(Article.ARTICLE_PERMALINK);
                    mostViewBuilder.append("\n* [").append(title).append("](").append(link).append(")");
                    articleIds.add(articleId);
                    count++;
                }
                if (20 <= count) {
                    break;
                }
            }
            if (0 < mostViewBuilder.length()) {
                bodyBuilder.append("### 热门\n").append(mostViewBuilder).append("\n\n");
            }

            final StringBuilder mostCmtBuilder = new StringBuilder();
            final List<JSONObject> mostCmtArticles = articleRepository.getList(new Query().setFilter(published)
                    .select(Keys.OBJECT_ID, Article.ARTICLE_TITLE, Article.ARTICLE_PERMALINK)
                    .addSort(Article.ARTICLE_COMMENT_COUNT, SortDirection.DESCENDING).setPage(1, 60));
            count = 0;
            for (final JSONObject article : mostCmtArticles) {
                final String articleId = article.optString(Keys.OBJECT_ID);
                if (!articleIds.contains(articleId)) {
                    final String title = article.optString(Article.ARTICLE_TITLE);
                    final String link = Latkes.getServePath() + article.optString(Article.ARTICLE_PERMALINK);
                    mostCmtBuilder.append("\n* [").append(title).append("](").append(link).append(")");
                    articleIds.add(articleId);
                    count++;
                }
                if (20 <= count) {
                    break;
                }
            }
            if (0 < mostCmtBuilder.length()) {
                bodyBuilder.append("### 热议\n").append(mostCmtBuilder);
            }

            final JSONObject stat = new JSONObject();
            stat.put("recentArticleTime", articleQueryService.getRecentArticleTime());
            final JSONObject statistic = statisticQueryService.getStatistic();
            stat.put("articleCount", statistic.getLong(Option.ID_T_STATISTIC_PUBLISHED_ARTICLE_COUNT));
            stat.put("commentCount", statistic.getLong(Option.ID_T_STATISTIC_PUBLISHED_BLOG_COMMENT_COUNT));
            stat.put("tagCount", tagQueryService.getTagCount());
            stat.put("skin",
                    optionQueryService.getOptionById(Option.ID_C_SKIN_DIR_NAME).optString(Option.OPTION_VALUE));
            stat.put("mobileSkin",
                    optionQueryService.getOptionById(Option.ID_C_MOBILE_SKIN_DIR_NAME).optString(Option.OPTION_VALUE));

            final HttpResponse response = HttpRequest.post("https://" + Global.HACPAI_DOMAIN + "/github/repos")
                    .connectionTimeout(7000).timeout(60000).header("User-Agent", Solos.USER_AGENT)
                    .form("userName", userName,
                            "userB3Key", userB3Key,
                            "clientName", "Solo",
                            "clientVersion", SoloApplication.VERSION,
                            "clientHost", Latkes.getServePath(),
                            "clientFavicon", preference.optString(Option.ID_C_FAVICON_URL),
                            "clientTitle", clientTitle,
                            "clientSubtitle", clientSubtitle,
                            "clientBody", bodyBuilder.toString(),
                            "stat", stat.toString(),
                            "file", zipData)
                    .send();
            response.close();
            response.charset("UTF-8");
            LOGGER.info("Backup public articles to HacPai completed: " + response.bodyText());
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Exports articles to github repo failed:" + e.getMessage());
        }
    }

    // Thanks to this lady
    // @see https://github.com/xingwanying/xingwanying/blob/main/README.md
    private String genMeRepoReadme(List<JSONObject> recentArticles, final String username) throws RepositoryException {
        if (null != recentArticles && recentArticles.size() > 5) {
            recentArticles = recentArticles.subList(0, 5);
        }
        String readme = "\n" + //
                "\n" + //
                "<img align=\"left\" src=\"https://user-images.githubusercontent.com/65187002/144930161-2f783401-8d27-4fdf-a2f7-cc0ba32f1f1f.gif\" width=\"30%\" style=\"display:inline;\"><img align=\"right\" src=\"https://user-images.githubusercontent.com/65187002/144930161-2f783401-8d27-4fdf-a2f7-cc0ba32f1f1f.gif\" width=\"30%\" style=\"display:inline;\">\n"
                + //
                "<br>\n" + //
                "<p align=\"center\">\n" + //
                "    <h1 align=\"center\">🌟&emsp;${name}&emsp;🌟</h1>\n" + //
                "</p>\n" + //
                "\n" + //
                "\n" + //
                "\n" + //
                "${body}" + //
                "  ";
        final StringBuilder ret = recentArticles(recentArticles);
        readme = readme.replace("${name}", username)
                .replace("${body}", ret.toString());
        return readme;
    }

    private StringBuilder recentArticles(final List<JSONObject> articles) throws RepositoryException {
        final StringBuilder ret = new StringBuilder("### Latest Blog Posts:\n");
        for (final JSONObject article : articles) {
            final String title = article.optString(Article.ARTICLE_TITLE);
            final String link = gitHubBlogOrigin() + article.optString(Article.ARTICLE_PERMALINK);
            ret.append("\n* [").append(title).append("](").append(link).append(")");
        }
        ret.append("\n* [").append("More").append("](").append(gitHubBlogOrigin()).append(")");
        ret.append("\n\n");
        return ret;
    }

    private String genSoloBlogReadme(final List<JSONObject> recentArticles, final String blogTitle,
            final String blogSubTitle, final String favicon,
            final String repoFullName) throws RepositoryException {
        final StringBuilder bodyBuilder = recentArticles(recentArticles);
        String ret = "<p align=\"center\"><img alt=\"${title}\" src=\"${favicon}\"></p><h2 align=\"center\">\n" +
                "${title}\n" +
                "</h2>\n" +
                "\n" +
                "<h4 align=\"center\">${subtitle}</h4>\n" +
                "<p align=\"center\">" +
                "<a title=\"${title}\" target=\"_blank\" href=\"https://github.com/${repoFullName}\"><img src=\"https://img.shields.io/github/last-commit/${repoFullName}.svg?style=flat-square&color=FF9900\"></a>\n"
                +
                "<a title=\"GitHub repo size in bytes\" target=\"_blank\" href=\"https://github.com/${repoFullName}\"><img src=\"https://img.shields.io/github/repo-size/${repoFullName}.svg?style=flat-square\"></a>\n"
                +
                "<a title=\"Volo Version\" target=\"_blank\" href=\"https://github.com/Willh92/Volo-solo\"><img src=\"https://img.shields.io/badge/Volo-${soloVer}-f1e05a.svg?style=flat-square&color=blueviolet\"></a>\n"
                +
                "<a title=\"Hits\" target=\"_blank\" href=\"https://github.com/88250/hits\"><img src=\"https://hits.b3log.org/${repoFullName}.svg\"></a>"
                +
                "</p>\n" +
                "\n" +
                "${body}\n\n" +
                "---\n" +
                "\n" +
                "本仓库通过 [Volo](https://github.com/Willh92/Volo-solo) 自动同步更新，源自 [Bolo](https://github.com/bolo-blog/bolo-solo)。";
        ret = ret.replace("${title}", blogTitle).replace("${subtitle}", blogSubTitle).replace("${favicon}", favicon)
                .replace("${repoFullName}", repoFullName).replace("${soloVer}", SoloApplication.BOLO_VERSION)
                .replace("${body}", bodyBuilder.toString());
        return ret;
    }

    /**
     * Exports the specified articles to the specified dir path.
     *
     * @param articles the specified articles
     * @param dirPath  the specified dir path
     */
    public void exportHexoMd(final List<JSONObject> articles, final String dirPath) {
        articles.forEach(article -> {
            final String filename = Solos.sanitizeFilename(article.optString("title")) + ".md";
            final String text = article.optString("front") + "---" + Strings.LINE_SEPARATOR
                    + article.optString("content");

            try {
                final String date = DateFormatUtils.format(article.optLong("created"), "yyyyMM");
                final String dir = dirPath + File.separator + date + File.separator;
                new File(dir).mkdirs();
                FileUtils.writeStringToFile(new File(dir + filename), text, "UTF-8");
            } catch (final Exception e) {
                LOGGER.log(Level.ERROR, "Write markdown file failed", e);
            }
        });
    }

    /**
     * Exports as Hexo markdown format.
     *
     * @return posts, password posts and drafts,
     * 
     *         <pre>
     * {
     *     "posts": [
     *         {
     *             "front": "", // yaml front matter,
     *             "title": "",
     *             "content": "",
     *             "created": long
     *         }, ....
     *     ],
     *     "passwords": [], // format is same as post
     *     "drafts": [] // format is same as post
     * }
     *         </pre>
     */
    public JSONObject exportHexoMDs() {
        return exportHexoMDs(false);
    }

    public JSONObject exportHexoMDs(final boolean stripLockedContent) {
        final JSONObject ret = new JSONObject();
        final List<JSONObject> posts = new ArrayList<>();
        ret.put("posts", (Object) posts);
        final List<JSONObject> passwords = new ArrayList<>();
        ret.put("passwords", (Object) passwords);
        final List<JSONObject> drafts = new ArrayList<>();
        ret.put("drafts", (Object) drafts);

        final Yaml yaml = new Yaml();
        final JSONArray articles = getJSONs(articleRepository);
        for (int i = 0; i < articles.length(); i++) {
            final JSONObject article = articles.optJSONObject(i);
            if (article == null) {
                continue;
            }
            final String title = article.optString(Article.ARTICLE_TITLE);
            final Map<String, Object> front = buildHexoFrontMatter(article, title);
            final JSONObject one = new JSONObject();
            one.put("front", yaml.dump(front));
            one.put("title", title);
            final String rawContent = article.optString(Article.ARTICLE_CONTENT);
            one.put("content", stripLockedContent ? HiddenContentService.stripForFeed(rawContent) : rawContent);
            one.put("created", article.optLong(Article.ARTICLE_CREATED));

            if (StringUtils.isNotBlank(article.optString(Article.ARTICLE_VIEW_PWD))) {
                passwords.add(one);
            } else if (Article.ARTICLE_STATUS_C_PUBLISHED == article.optInt(Article.ARTICLE_STATUS)) {
                posts.add(one);
            } else {
                drafts.add(one);
            }
        }

        return ret;
    }

    /**
     * Streams Hexo Markdown entries ({@code posts/}, {@code drafts/}, {@code passwords/}) in pages directly into
     * {@code zos} without materializing a full-database {@link JSONObject} in heap.
     */
    public void writeHexoMDsToZip(final ZipOutputStream zos) throws IOException {
        if (zos == null || articleRepository == null) {
            return;
        }
        final Yaml yaml = new Yaml();
        final int[] counters = {1, 1, 1}; // [0]=posts, [1]=drafts, [2]=passwords
        streamRepositoryRows(articleRepository, article -> {
            final String folder;
            final int idx;
            if (StringUtils.isNotBlank(article.optString(Article.ARTICLE_VIEW_PWD))) {
                folder = "passwords";
                idx = counters[2]++;
            } else if (Article.ARTICLE_STATUS_C_PUBLISHED == article.optInt(Article.ARTICLE_STATUS)) {
                folder = "posts";
                idx = counters[0]++;
            } else {
                folder = "drafts";
                idx = counters[1]++;
            }

            final String rawTitle = article.optString(Article.ARTICLE_TITLE, "untitled");
            final Map<String, Object> front = buildHexoFrontMatter(article, article.optString(Article.ARTICLE_TITLE));
            String safeTitle = rawTitle.replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
            safeTitle = StringUtils.strip(safeTitle, "-.");
            if (safeTitle.isEmpty()) {
                safeTitle = "article-" + idx;
            }
            if (safeTitle.length() > 60) {
                safeTitle = safeTitle.substring(0, 60);
            }
            final String entryName = folder + "/" + String.format(Locale.ROOT, "%04d-", idx) + safeTitle + ".md";
            final String md = "---\n" + yaml.dump(front) + "---\n\n" + article.optString(Article.ARTICLE_CONTENT, "");
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(md.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        });
    }

    private static Map<String, Object> buildHexoFrontMatter(final JSONObject article, final String title) {
        final Map<String, Object> front = new LinkedHashMap<>();
        front.put("title", title);
        front.put("date", DateFormatUtils.format(article.optLong(Article.ARTICLE_CREATED), "yyyy-MM-dd HH:mm:ss"));
        front.put("updated", DateFormatUtils.format(article.optLong(Article.ARTICLE_UPDATED), "yyyy-MM-dd HH:mm:ss"));
        final List<String> tags = Arrays.stream(article.optString(Article.ARTICLE_TAGS_REF).split(","))
                .filter(StringUtils::isNotBlank).map(String::trim).collect(Collectors.toList());
        if (tags.isEmpty()) {
            tags.add("Solo");
        }
        front.put("tags", tags);
        front.put("permalink", article.optString(Article.ARTICLE_PERMALINK));
        return front;
    }

    /**
     * Streams all 15 database tables in pages directly to {@code writer} in the exact {@link #getJSONs()} schema
     * with secret redaction/masking applied, avoiding full-DB DOM allocation in heap.
     */
    public void writeJSONsToStream(final Writer writer) throws IOException {
        writer.write("{\n");
        writeTableEntry(writer, ArchiveDate.ARCHIVE_DATES, archiveDateRepository, false, true);
        writeTableEntry(writer, ArchiveDate.ARCHIVE_DATE + "_" + Article.ARTICLE, archiveDateArticleRepository, false, false);
        writeTableEntry(writer, Article.ARTICLES, articleRepository, false, false);
        writeTableEntry(writer, "articleRevisions", articleRevisionRepository, false, false);
        writeTableEntry(writer, Category.CATEGORIES, categoryRepository, false, false);
        writeTableEntry(writer, Category.CATEGORY + "_" + Tag.TAG, categoryTagRepository, false, false);
        writeTableEntry(writer, Comment.COMMENTS, commentRepository, false, false);
        writeTableEntry(writer, Link.LINKS, linkRepository, false, false);
        writeTableEntry(writer, Follow.FOLLOWS, followRepository, false, false);
        writeTableEntry(writer, Option.OPTIONS, optionRepository, true, false);
        writeTableEntry(writer, Page.PAGES, pageRepository, false, false);
        writeTableEntry(writer, Plugin.PLUGINS, pluginRepository, false, false);
        writeTableEntry(writer, Tag.TAGS, tagRepository, false, false);
        writeTableEntry(writer, Tag.TAG + "_" + Article.ARTICLES, tagArticleRepository, false, false);
        writeTableEntry(writer, User.USERS, userRepository, false, false);
        writer.write("\n}");
        writer.flush();
    }

    /**
     * Gets all data as JSON format.
     */
    public JSONObject getJSONs() {
        final JSONObject ret = new JSONObject();
        final JSONArray archiveDates = getJSONs(archiveDateRepository);
        ret.put(ArchiveDate.ARCHIVE_DATES, archiveDates);

        final JSONArray archiveDateArticles = getJSONs(archiveDateArticleRepository);
        ret.put(ArchiveDate.ARCHIVE_DATE + "_" + Article.ARTICLE, archiveDateArticles);

        final JSONArray articles = getJSONs(articleRepository);
        ret.put(Article.ARTICLES, articles);
        ret.put("articleRevisions", getJSONs(articleRevisionRepository));

        final JSONArray categories = getJSONs(categoryRepository);
        ret.put(Category.CATEGORIES, categories);

        final JSONArray categoryTags = getJSONs(categoryTagRepository);
        ret.put(Category.CATEGORY + "_" + Tag.TAG, categoryTags);

        final JSONArray comments = getJSONs(commentRepository);
        ret.put(Comment.COMMENTS, comments);

        final JSONArray links = getJSONs(linkRepository);
        ret.put(Link.LINKS, links);
        ret.put(Follow.FOLLOWS, getJSONs(followRepository));

        final JSONArray rawOptions = getJSONs(optionRepository);
        final JSONArray options = new JSONArray();
        for (int i = 0; i < rawOptions.length(); i++) {
            final JSONObject masked = filterAndMaskExportOption(rawOptions.optJSONObject(i));
            if (masked != null) {
                options.put(masked);
            }
        }
        ret.put(Option.OPTIONS, options);

        final JSONArray pages = getJSONs(pageRepository);
        ret.put(Page.PAGES, pages);

        final JSONArray plugins = getJSONs(pluginRepository);
        ret.put(Plugin.PLUGINS, plugins);

        final JSONArray tags = getJSONs(tagRepository);
        ret.put(Tag.TAGS, tags);

        final JSONArray tagArticles = getJSONs(tagArticleRepository);
        ret.put(Tag.TAG + "_" + Article.ARTICLES, tagArticles);

        final JSONArray users = getJSONs(userRepository);
        ret.put(User.USERS, users);

        return ret;
    }

    private static JSONObject filterAndMaskExportOption(final JSONObject option) {
        if (option == null) {
            return null;
        }
        final String optId = option.optString(Keys.OBJECT_ID);
        if (TotpService.isProtectedOptionId(optId)
                || ShareTokenService.OPTION_ID_SECRET.equals(optId)
                || Option.ID_C_VOLO_PASSKEY_AUTH.equals(optId)
                || optId.startsWith(Option.ID_C_VOLO_PASSKEY_AUTH + ".")
                || optId.startsWith(Option.ID_C_VOLO_REMOTE_BACKUP + ".")) {
            return null;
        }
        if (Option.ID_C_VOLO_REMOTE_BACKUP.equals(optId)) {
            final JSONObject masked = org.b3log.solo.util.Solos.clone(option);
            masked.put(Option.OPTION_VALUE, RemoteBackupSyncService.maskJsonString(option.optString(Option.OPTION_VALUE)));
            return masked;
        }
        if (Option.ID_C_VOLO_WEBHOOKS.equals(optId)) {
            final JSONObject masked = org.b3log.solo.util.Solos.clone(option);
            masked.put(Option.OPTION_VALUE, WebhookNotifyService.maskJsonArrayString(option.optString(Option.OPTION_VALUE)));
            return masked;
        }
        return option;
    }

    @FunctionalInterface
    private interface IOConsumer<T> {
        void accept(T item) throws IOException;
    }

    private void writeTableEntry(final Writer writer,
                                 final String tableKey,
                                 final Repository repository,
                                 final boolean maskOptions,
                                 final boolean firstTable) throws IOException {
        if (!firstTable) {
            writer.write(",\n");
        }
        writer.write("  ");
        writer.write(JSONObject.quote(tableKey));
        writer.write(": [");
        final boolean[] hasAny = {false};
        streamRepositoryRows(repository, row -> {
            final JSONObject effective = maskOptions ? filterAndMaskExportOption(row) : row;
            if (effective == null) {
                return;
            }
            if (hasAny[0]) {
                writer.write(",\n    ");
            } else {
                writer.write("\n    ");
                hasAny[0] = true;
            }
            try {
                effective.write(writer);
            } catch (final JSONException je) {
                if (je.getCause() instanceof IOException ioe) {
                    throw ioe;
                }
                throw je;
            }
        });
        if (hasAny[0]) {
            writer.write("\n  ]");
        } else {
            writer.write("]");
        }
    }

    private void streamRepositoryRows(final Repository repository, final IOConsumer<JSONObject> consumer)
            throws IOException {
        if (repository == null) {
            return;
        }
        final Set<String> seenIds = new HashSet<>();
        String page1FirstSig = null;
        for (int page = 1; ; page++) {
            final JSONArray rows;
            try {
                final Query query = new Query()
                        .addSort(Keys.OBJECT_ID, SortDirection.ASCENDING)
                        .setPage(page, EXPORT_PAGE_SIZE)
                        .setPageCount(1);
                rows = org.b3log.solo.util.QueryResults.array(repository.get(query));
            } catch (final Exception e) {
                LOGGER.log(Level.ERROR, "Gets data from repository [" + safeRepoName(repository) + "] failed", e);
                break;
            }
            if (rows.isEmpty()) {
                break;
            }
            final JSONObject firstRow = rows.optJSONObject(0);
            final String firstSig = firstRow == null ? "" : firstRow.optString(Keys.OBJECT_ID, firstRow.toString());
            if (page == 1) {
                page1FirstSig = firstSig;
            } else if (firstSig.equals(page1FirstSig)) {
                break;
            }
            int emittedInPage = 0;
            for (int i = 0; i < rows.length(); i++) {
                final JSONObject row = rows.optJSONObject(i);
                if (row == null) {
                    continue;
                }
                final String oid = row.optString(Keys.OBJECT_ID);
                if (!oid.isEmpty() && !seenIds.add(oid)) {
                    continue;
                }
                emittedInPage++;
                consumer.accept(row);
            }
            if (rows.length() < EXPORT_PAGE_SIZE || emittedInPage == 0) {
                break;
            }
        }
    }

    private JSONArray getJSONs(final Repository repository) {
        final JSONArray result = new JSONArray();
        try {
            streamRepositoryRows(repository, result::put);
        } catch (final IOException ignored) {
        }
        return result;
    }

    private static String safeRepoName(final Repository repository) {
        try {
            return String.valueOf(repository.getName());
        } catch (final Exception ignored) {
            return repository.getClass().getSimpleName();
        }
    }
}
