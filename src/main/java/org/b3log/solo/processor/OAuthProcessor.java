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
package org.b3log.solo.processor;

import org.b3log.solo.util.AdminPaths;

import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.ioc.BeanManager;
import org.b3log.latke.ioc.Inject;
import org.apache.logging.log4j.Level;
import org.b3log.solo.logging.Logger;
import org.b3log.latke.model.Role;
import org.b3log.latke.model.User;
import org.b3log.latke.repository.RepositoryException;
import org.b3log.latke.repository.Transaction;
import org.b3log.latke.repository.jdbc.util.Connections;
import org.b3log.latke.service.LangPropsService;
import org.b3log.latke.http.HttpMethod;
import org.b3log.latke.http.RequestContext;
import org.b3log.solo.web.annotation.RequestProcessing;
import org.b3log.latke.ioc.Singleton;
import org.b3log.latke.util.Requests;
import org.b3log.solo.bolo.tool.MD5Utils;
import org.b3log.solo.model.Option;
import org.b3log.solo.model.UserExt;
import org.b3log.solo.repository.OptionRepository;
import org.b3log.solo.service.*;
import org.b3log.solo.util.Solos;
import org.b3log.solo.util.LoginThrottle;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;

import org.b3log.latke.http.Request;
import org.b3log.latke.http.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OAuth processor.
 * <ul>
 * <li>Redirects to auth page (/oauth/github/redirect), GET</li>
 * <li>OAuth callback (/oauth/github), GET</li>
 * </ul>
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 2.9.5
 */
@Singleton
public class OAuthProcessor {
    private static final LoginThrottle LOGIN_THROTTLE = new LoginThrottle();

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(OAuthProcessor.class);

    /**
     * OAuth parameters - state.
     */
    private static final Set<String> STATES = ConcurrentHashMap.newKeySet();

    /**
     * Option query service.
     */
    @Inject
    private OptionQueryService optionQueryService;

    /**
     * Option management service.
     */
    @Inject
    private OptionMgmtService optionMgmtService;

    /**
     * User query service.
     */
    @Inject
    private UserQueryService userQueryService;

    /**
     * User management service.
     */
    @Inject
    private UserMgmtService userMgmtService;

    /**
     * Initialization service.
     */
    @Inject
    private InitService initService;

    /**
     * Language service.
     */
    @Inject
    private LangPropsService langPropsService;

    /**
     * Bolo admin login.
     *
     * @param context
     */
    @RequestProcessing(value = "/oauth/bolo/login", method = HttpMethod.POST)
    public synchronized void adminLogin(final RequestContext context) {
        final Request request = context.getRequest();
        final String username = request.getParameter("username");
        final String srcPassword = request.getParameter("password");
        context.getResponse().setHeader("Cache-Control", "no-store");
        final String clientAddress = LoginThrottle.clientAddress(request);
        final LoginThrottle.Attempt attempt = LOGIN_THROTTLE.acquire(clientAddress, username);
        if (attempt.retryAfter() > 0) {
            context.getResponse().setHeader("Retry-After", String.valueOf(attempt.retryAfter()));
            context.setStatus(429);
            context.renderJSON(new JSONObject().put("sc", false).put("code", 1)
                    .put("msg", "登录尝试过于频繁，请稍后再试。").put("retryAfter", attempt.retryAfter()));
            return;
        }
        if ((username != null && username.length() > 128) || (srcPassword != null && srcPassword.length() > 1024)) {
            loginResult(context, false, null, "用户名或密码格式不正确。", 400);
            return;
        }
        if (StringUtils.isBlank(username) || StringUtils.isEmpty(srcPassword)) {
            loginResult(context, false, null, "请填写用户名和密码。", 400);
            return;
        }
        try {
            final String password = MD5Utils.stringToMD5Twice(srcPassword);
            if ((UpgradeService.boloFastMigration || !initService.isInited())
                    && !srcPassword.matches("(?s)(?=.*[0-9])(?=.*[a-zA-Z]).{8,30}")) {
                loginResult(context, false, null, "请使用 8–30 位、包含字母和数字的密码。", 400);
                return;
            }
            if (UpgradeService.boloFastMigration) {
                // 快速迁移程序
                final JSONObject preference = optionQueryService.getPreference();
                final String currentVer = preference.getString(Option.ID_C_VERSION);
                int version = Integer.parseInt(currentVer.replaceAll("\\.", ""));
                if (version < 432) {
                    fastMigrate(currentVer, username, srcPassword, context);
                } else {
                    fastMigrate(currentVer, username, password, context);
                }
                Solos.login(userQueryService.getAdmin(), context.getResponse());
                LOGIN_THROTTLE.succeeded(attempt);
                loginResult(context, true, AdminPaths.panel() + "#main", "迁移完成。", 200);
            } else if (!initService.isInited()) {
                LOGGER.log(Level.INFO, "Bolo initializing...");
                final JSONObject initReq = new JSONObject();
                initReq.put(User.USER_NAME, username);
                initReq.put(UserExt.USER_B3_KEY, password);
                initReq.put(UserExt.USER_AVATAR, Latkes.getContextPath() + "/images/volo-mark.svg");
                initReq.put(UserExt.USER_GITHUB_ID, "000000");
                initService.init(initReq);
                Solos.login(userQueryService.getAdmin(), context.getResponse());
                LOGIN_THROTTLE.succeeded(attempt);
                loginResult(context, true, AdminPaths.panel() + "#main", "初始化完成。", 200);
            } else {
                final JSONObject user = userQueryService.getUserByName(username);
                if (user == null || (!Role.ADMIN_ROLE.equals(user.optString(User.USER_ROLE))
                        && !Role.DEFAULT_ROLE.equals(user.optString(User.USER_ROLE)))
                        || !username.equals(user.optString(User.USER_NAME))
                        || !password.equals(user.optString(UserExt.USER_B3_KEY))) {
                    loginResult(context, false, null, "用户名或密码错误。", 401);
                    return;
                }
                Solos.login(user, context.getResponse());
                LOGIN_THROTTLE.succeeded(attempt);
                LOGGER.log(Level.INFO, "Logged in [name={0}, remoteAddr={1}] with Volo auth", username, clientAddress);
                loginResult(context, true, AdminPaths.panel() + "#main", "登录成功。", 200);
            }
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Bolo login failed", e);
            loginResult(context, false, null, "暂时无法登录，请稍后重试。", 500);
        }
    }

    private void loginResult(final RequestContext context, final boolean success, final String target,
                             final String message, final int status) {
        final boolean json = "XMLHttpRequest".equalsIgnoreCase(context.header("X-Requested-With"))
                || StringUtils.containsIgnoreCase(context.header("Accept"), "application/json");
        if (json) {
            context.setStatus(status);
            final JSONObject result = new JSONObject().put("sc", success).put("code", success ? 0 : 1).put("msg", message);
            if (success) result.put("redirect", Latkes.getContextPath() + target);
            context.renderJSON(result);
        } else {
            context.sendRedirect(Latkes.getServePath() + (success ? target : AdminPaths.login() + "?status=error"));
        }
    }

    private void fastMigrate(String currentVer, String username, String password, final RequestContext context) throws SQLException, RepositoryException {
        int version = Integer.parseInt(currentVer.replaceAll("\\.", ""));
        if (version <= 420) {
            // 低版本，正常迁移
            migrateLow(version, username, password, context);
        } else {
            // 高版本，先加表
            migrateHigh(version);
            migrateLow(version, username, password, context);
        }
    }

    private void migrateLow(int version, String username, String password, final RequestContext context) throws SQLException {
        final String tablePrefix = Latkes.getLocalProperty("jdbc.tablePrefix") + "_";
        try (Connection connection = Connections.getConnection()) {
            try {
                migrateAdministrator(connection, tablePrefix, username, password, Latkes.getServePath());
                // Category tables may be absent in older Solo schemas.
                try (Statement statement = connection.createStatement()) {
                    for (final String table : List.of("category", "category_tag")) {
                        try {
                            statement.executeUpdate("DELETE FROM `" + tablePrefix.replace("`", "``") + table + "`");
                        } catch (SQLException ignored) {
                        }
                    }
                }
                connection.commit();
            } catch (SQLException failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
        // 改皮肤
        UpgradeService.boloFastMigration = false;
        BeanManager beanManager = BeanManager.getInstance();
        JSONObject skin = optionQueryService.getSkin();
        final SkinMgmtService skinMgmtService = beanManager.getReference(SkinMgmtService.class);
        try {
            skinMgmtService.loadSkins(skin);
        } catch (Exception ignored) {
        }
        // 升级
        final UpgradeService upgradeService = beanManager.getReference(UpgradeService.class);
        upgradeService.upgrade();
    }

    static void migrateAdministrator(final Connection connection, final String tablePrefix, final String username,
                                     final String password, final String site) throws SQLException {
        final String table = "`" + tablePrefix.replace("`", "``") + "user`";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM " + table + " WHERE userRole = 'adminRole'");
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + table
                + " (oId, userName, userURL, userRole, userAvatar, userB3Key, userGitHubId)"
                + " VALUES ('default', ?, ?, 'adminRole', ?, ?, 'none')")) {
            statement.setString(1, username);
            statement.setString(2, site);
            statement.setString(3, site + "/images/volo-mark.svg");
            statement.setString(4, password);
            statement.executeUpdate();
        }
    }

    private void migrateHigh(int version) throws SQLException, RepositoryException {
        // 高版本降级到4.2.0
        Connection connection = Connections.getConnection();
        Statement statement = connection.createStatement();
        String tablePrefix = Latkes.getLocalProperty("jdbc.tablePrefix") + "_";
        // 降级表到4.2.0
        // 添加列
        try {
            statement.executeUpdate("ALTER TABLE `" + tablePrefix + "article` ADD COLUMN `articleCommentCount` int(11) NOT NULL COMMENT '文章评论计数' DEFAULT 0;");
        } catch (Exception ignored) {
        }
        try {
            statement.executeUpdate("ALTER TABLE `" + tablePrefix + "article` ADD COLUMN `articleViewCount` int(11) NOT NULL COMMENT '文章浏览计数' DEFAULT 0;");
        } catch (Exception ignored) {
        }
        try {
            statement.executeUpdate("ALTER TABLE `" + tablePrefix + "article` ADD COLUMN `articleCommentable` char(1) NOT NULL COMMENT '文章是否可以评论' DEFAULT '1';");
        } catch (Exception ignored) {
        }
        // comment表
        try {
            statement.executeUpdate("CREATE TABLE `b3_solo_comment` ( `oId` varchar(19) NOT NULL COMMENT '主键', `commentContent` text NOT NULL COMMENT '评论内容', `commentCreated` bigint(20) NOT NULL COMMENT '评论时间戳', `commentName` varchar(50) NOT NULL COMMENT '评论人名称', `commentOnId` varchar(19) NOT NULL COMMENT '评论的文章/页面的 id', `commentSharpURL` varchar(255) NOT NULL COMMENT '评论访问路径，带 # 锚点', `commentThumbnailURL` text NOT NULL COMMENT '评论人头像图片链接地址', `commentURL` varchar(255) NOT NULL COMMENT '评论人链接地址', `commentOriginalCommentId` varchar(19) DEFAULT NULL COMMENT '评论回复时原始的评论 id，即父评论 id', `commentOriginalCommentName` varchar(50) DEFAULT NULL COMMENT '评论回复时原始的评论人名称，即父评论人名称', PRIMARY KEY (`oId`) ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评论表';");
        } catch (Exception ignored) {
        }
        statement.close();
        connection.commit();
        connection.close();
        // 降级版本，添加设定
        BeanManager beanManager = BeanManager.getInstance();
        OptionRepository optionRepository = (OptionRepository)beanManager.getReference(OptionRepository.class);
        Transaction transaction = optionRepository.beginTransaction();
        JSONObject versionOpt = optionRepository.get("version");
        versionOpt.put("optionValue", "4.2.0");
        try {
            optionRepository.update("version", versionOpt, new String[0]);
        } catch (Exception ignored) {
        }
        List<Object[]> optList = new ArrayList<>();
        Collections.addAll(optList,
                    new Object[] { Option.ID_C_MOST_COMMENT_ARTICLE_DISPLAY_CNT, Option.CATEGORY_C_PREFERENCE, Option.DefaultPreference.DEFAULT_MOST_COMMENT_ARTICLE_DISPLAY_COUNT },
                    new Object[] { Option.ID_C_MOST_VIEW_ARTICLE_DISPLAY_CNT, Option.CATEGORY_C_PREFERENCE, Option.DefaultPreference.DEFAULT_MOST_VIEW_ARTICLES_DISPLAY_COUNT },
                    new Object[] { Option.ID_C_RECENT_COMMENT_DISPLAY_CNT, Option.CATEGORY_C_PREFERENCE, Option.DefaultPreference.DEFAULT_RECENT_COMMENT_DISPLAY_COUNT },
                    new Object[] { Option.ID_C_COMMENTABLE, Option.CATEGORY_C_PREFERENCE, Option.DefaultPreference.DEFAULT_COMMENTABLE }
                );
        for (Object[] i : optList) {
            saveOption(i, optionRepository);
        }
        transaction.commit();
        version = 420;
    }

    private void saveOption(Object[] opt, OptionRepository optionRepository) {
        final JSONObject optOpt = new JSONObject();
        optOpt
                .put(Keys.OBJECT_ID, opt[0])
                .put(Option.OPTION_CATEGORY, opt[1])
                .put(Option.OPTION_VALUE, opt[2]);
        try {
            optionRepository.add(optOpt);
        } catch (Exception ignored) {
        }
    }
}
