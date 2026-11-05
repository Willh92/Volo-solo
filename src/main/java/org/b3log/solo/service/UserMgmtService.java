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

import org.apache.commons.lang3.StringUtils;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.ioc.BeanManager;
import org.b3log.latke.ioc.Inject;
import org.apache.logging.log4j.Level;
import org.b3log.solo.cache.PublicViewCache;
import org.b3log.solo.logging.Logger;
import org.b3log.latke.model.Role;
import org.b3log.latke.model.User;
import org.b3log.latke.repository.Transaction;
import org.b3log.latke.service.LangPropsService;
import org.b3log.latke.service.ServiceException;
import org.b3log.latke.service.annotation.Service;
import org.b3log.solo.bolo.Global;
import org.b3log.solo.bolo.tool.MD5Utils;
import org.b3log.solo.model.Common;
import org.b3log.solo.model.Option;
import org.b3log.solo.model.UserExt;
import org.b3log.solo.repository.OptionRepository;
import org.b3log.solo.repository.UserRepository;
import org.b3log.solo.util.Solos;
import org.json.JSONObject;

import jodd.http.HttpRequest;
import jodd.http.HttpResponse;

/**
 * User management service.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @author <a href="https://ld246.com/member/DASHU">DASHU</a>
 * @author <a href="https://github.com/nanolikeyou">nanolikeyou</a>
 * @since 0.4.0
 */
@Service
public class UserMgmtService {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(UserMgmtService.class);

    /**
     * Length of hashed password.
     */
    private static final int HASHED_PASSWORD_LENGTH = 32;

    /**
     * User repository.
     */
    @Inject
    private UserRepository userRepository;

    /**
     * Language service.
     */
    @Inject
    private LangPropsService langPropsService;

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
     * Init service.
     */
    @Inject
    private InitService initService;

    /**
     * Option repository.
     */
    @Inject
    private OptionRepository optionRepository;

    @Inject
    private PublicViewCache publicViewCache;

    private void invalidatePublicCommon() {
        if (null != publicViewCache) {
            publicViewCache.invalidateCommon();
        }
    }

    /**
     * Refresh usite. 展示站点连接 https://github.com/b3log/solo/issues/12719
     */
    public void refreshUSite() {
        if (!initService.isInited()) {
            return;
        }

        final BeanManager beanManager = BeanManager.getInstance();
        UserQueryService userQueryService = beanManager.getReference(UserQueryService.class);
        String userName = userQueryService.getB3username();
        String userB3Key = userQueryService.getB3password();
        if (Option.DefaultPreference.DEFAULT_B3LOG_USERNAME.equals(userName)) {
            LOGGER.log(Level.INFO, "Usite refresh skipped because using the default B3 account.");

            return;
        }

        JSONObject usite;
        try {
            final JSONObject requestJSON = new JSONObject().put(User.USER_NAME, userName).put(UserExt.USER_B3_KEY,
                    userB3Key);
            final HttpResponse res = HttpRequest.post("https://" + Global.HACPAI_DOMAIN + "/user/usite")
                    .connectionTimeout(3000).timeout(7000).header("User-Agent", Solos.USER_AGENT)
                    .body(requestJSON.toString()).send();
            if (200 != res.statusCode()) {
                return;
            }
            res.charset("UTF-8");
            final JSONObject result = new JSONObject(res.bodyText());
            if (0 != result.optInt(org.b3log.solo.model.Common.STATUS_CODE)) {
                LOGGER.log(Level.DEBUG, "Updates usite option failed: Invalid Username or B3log Key.");

                return;
            }
            usite = result.optJSONObject(Common.DATA);
        } catch (final Exception e) {
            LOGGER.log(Level.DEBUG, "Gets usite failed", e);

            return;
        }

        JSONObject usiteOpt = optionQueryService.getOptionById(Option.ID_C_USITE);
        if (null == usiteOpt) {
            usiteOpt = new JSONObject();
            usiteOpt.put(Keys.OBJECT_ID, Option.ID_C_USITE);
            usiteOpt.put(Option.OPTION_CATEGORY, Option.CATEGORY_C_HACPAI);
        }
        usiteOpt.put(Option.OPTION_VALUE, usite.toString());
        try {
            optionMgmtService.addOrUpdateOption(usiteOpt);
            invalidatePublicCommon();
            LOGGER.log(Level.INFO, "Community contact settings refreshed");
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Updates usite option failed", e);
        }
    }

    /**
     * Updates a user by the specified request json object.
     *
     * @param requestJSONObject the specified request json object
     * @throws ServiceException service exception
     */
    public void updateUser(final JSONObject requestJSONObject, boolean changePassword) throws ServiceException {
        final Transaction transaction = userRepository.beginTransaction();

        try {
            final String oldUserId = requestJSONObject.optString(Keys.OBJECT_ID);
            final JSONObject oldUser = userRepository.get(oldUserId);
            if (null == oldUser) {
                throw new ServiceException(langPropsService.get("updateFailLabel"));
            }

            final String userName = requestJSONObject.optString(User.USER_NAME);
            if (UserExt.invalidUserName(userName)) {
                throw new ServiceException(langPropsService.get("userNameInvalidLabel"));
            }

            JSONObject mayBeAnother = userRepository.getByUserName(userName);
            if (null != mayBeAnother && !mayBeAnother.optString(Keys.OBJECT_ID).equals(oldUserId)) {
                throw new ServiceException(langPropsService.get("duplicatedUserNameLabel"));
            }
            oldUser.put(User.USER_NAME, userName);

            final String userRole = requestJSONObject.optString(User.USER_ROLE);
            if (Role.ADMIN_ROLE.equals(oldUser.optString(User.USER_ROLE)) && !Role.ADMIN_ROLE.equals(userRole)) {
                throw new ServiceException("管理员角色不能降级。");
            }
            if (!Role.ADMIN_ROLE.equals(oldUser.optString(User.USER_ROLE))
                    && !Role.DEFAULT_ROLE.equals(userRole) && !Role.VISITOR_ROLE.equals(userRole)) {
                throw new ServiceException("用户角色不正确。");
            }
            oldUser.put(User.USER_ROLE, userRole);

            final String userURL = requestJSONObject.optString(User.USER_URL).trim();
            if (!org.b3log.solo.util.OutboundUrls.isOptionalSafeIconOrAvatar(userURL)) {
                throw new ServiceException("用户主页地址仅支持站内路径或 http(s):// 地址。");
            }
            oldUser.put(User.USER_URL, userURL);

            final String userAvatar = requestJSONObject.optString(UserExt.USER_AVATAR).trim();
            if (!org.b3log.solo.util.OutboundUrls.isOptionalSafeIconOrAvatar(userAvatar)) {
                throw new ServiceException("用户头像地址不合法。");
            }
            oldUser.put(UserExt.USER_AVATAR, userAvatar);

            if (changePassword) {
                final String userB3Key = requestJSONObject.optString(UserExt.USER_B3_KEY);
                if (userB3Key != null && !userB3Key.isEmpty()) {
                    oldUser.put(UserExt.USER_B3_KEY, userB3Key);
                }
                final String passwordHash = requestJSONObject.optString(UserExt.USER_PASSWORD_HASH);
                if (passwordHash != null && !passwordHash.isEmpty()) {
                    oldUser.put(UserExt.USER_PASSWORD_HASH, passwordHash);
                }
            }

            final String userGitHubId = requestJSONObject.optString(UserExt.USER_GITHUB_ID);
            if (StringUtils.isNotBlank(userGitHubId)) {
                oldUser.put(UserExt.USER_GITHUB_ID, userGitHubId);
            }

            userRepository.update(oldUserId, oldUser);
            transaction.commit();
            invalidatePublicCommon();
        } catch (final Exception e) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
            if (e instanceof ServiceException se) {
                throw se;
            }

            LOGGER.log(Level.ERROR, "Updates a user failed", e);
            throw new ServiceException(e);
        }
    }

    /**
     * Switches the user role between "defaultRole" and "visitorRole" by the specified user id.
     *
     * @param userId the specified user id
     * @throws ServiceException exception
     * @see User
     */
    public void changeRole(final String userId) throws ServiceException {
        final Transaction transaction = userRepository.beginTransaction();

        try {
            final JSONObject oldUser = userRepository.get(userId);

            if (null == oldUser) {
                throw new ServiceException(langPropsService.get("updateFailLabel"));
            }

            final String role = oldUser.optString(User.USER_ROLE);

            if (Role.VISITOR_ROLE.equals(role)) {
                oldUser.put(User.USER_ROLE, Role.DEFAULT_ROLE);
            } else if (Role.DEFAULT_ROLE.equals(role)) {
                oldUser.put(User.USER_ROLE, Role.VISITOR_ROLE);
            }

            userRepository.update(userId, oldUser, User.USER_ROLE);

            transaction.commit();
            invalidatePublicCommon();
        } catch (final Exception e) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
            if (e instanceof ServiceException se) {
                throw se;
            }

            LOGGER.log(Level.ERROR, "Updates a user failed", e);
            throw new ServiceException(e);
        }
    }

    /**
     * Adds a user with the specified request json object.
     *
     * @param requestJSONObject the specified request json object
     * @return generated user id
     * @throws ServiceException service exception
     */
    public synchronized String addUser(final JSONObject requestJSONObject) throws ServiceException {
        final Transaction transaction = userRepository.beginTransaction();

        try {
            final String userName = requestJSONObject.optString(User.USER_NAME);
            if (UserExt.invalidUserName(userName)) {
                throw new ServiceException(langPropsService.get("userNameInvalidLabel"));
            }

            JSONObject duplicatedUser = userRepository.getByUserName(userName);
            if (null != duplicatedUser) {
                if (transaction.isActive()) {
                    transaction.rollback();
                }

                throw new ServiceException(langPropsService.get("duplicatedUserNameLabel"));
            }
            final JSONObject user = new JSONObject();
            user.put(User.USER_NAME, userName);

            String userURL = requestJSONObject.optString(User.USER_URL);
            if (StringUtils.isBlank(userURL)) {
                userURL = Latkes.getServePath();
            }
            if (!org.b3log.solo.util.OutboundUrls.isHttpOrHttps(userURL)) {
                throw new ServiceException(langPropsService.get("urlInvalidLabel"));
            }
            user.put(User.USER_URL, userURL);

            final String roleName = requestJSONObject.optString(User.USER_ROLE, Role.DEFAULT_ROLE);
            user.put(User.USER_ROLE, roleName);

            final String userAvatar = requestJSONObject.optString(UserExt.USER_AVATAR).trim();
            if (!org.b3log.solo.util.OutboundUrls.isOptionalSafeIconOrAvatar(userAvatar)) {
                throw new ServiceException("用户头像地址不合法。");
            }
            user.put(UserExt.USER_AVATAR, userAvatar);

            final String userGitHubId = requestJSONObject.optString(UserExt.USER_GITHUB_ID);
            user.put(UserExt.USER_GITHUB_ID, userGitHubId);

            final String userB3Key = requestJSONObject.optString(UserExt.USER_B3_KEY);
            user.put(UserExt.USER_B3_KEY, MD5Utils.stringToMD5Twice(userB3Key));

            userRepository.add(user);
            transaction.commit();
            invalidatePublicCommon();

            return user.optString(Keys.OBJECT_ID);
        } catch (final Exception e) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
            if (e instanceof ServiceException se) {
                throw se;
            }

            LOGGER.log(Level.ERROR, "Adds a user failed", e);
            throw new ServiceException(e);
        }
    }

    /**
     * Registers a verified public reader account strictly isolated to {@link Role#VISITOR_ROLE}.
     */
    public synchronized JSONObject registerReader(final String rawUserName, final String normalizedEmail,
                                                  final String rawPassword, final String rawUserUrl) throws ServiceException {
        final Transaction transaction = userRepository.beginTransaction();
        try {
            final String userName = rawUserName == null ? "" : rawUserName.strip();
            if (UserExt.invalidUserName(userName) || org.b3log.solo.processor.ReaderAuthProcessor.validateReaderName(userName) != null) {
                throw new ServiceException("用户昵称格式不合法。");
            }
            if (userRepository.getByUserName(userName) != null) {
                throw new ServiceException("该昵称已被占用，请更换其他昵称。");
            }
            if (userRepository.getByEmail(normalizedEmail) != null) {
                throw new ServiceException("该邮箱已被注册，请直接登录。");
            }
            String userURL = rawUserUrl == null ? "" : rawUserUrl.strip();
            if (!userURL.isEmpty() && !org.b3log.solo.util.OutboundUrls.isHttpOrHttps(userURL)) {
                throw new ServiceException("个人主页地址必须以 http:// 或 https:// 开头。");
            }
            final JSONObject user = new JSONObject();
            user.put(User.USER_NAME, userName);
            user.put(User.USER_URL, userURL);
            user.put(User.USER_ROLE, Role.VISITOR_ROLE);
            user.put(UserExt.USER_AVATAR, "");
            user.put(UserExt.USER_GITHUB_ID, "");
            user.put(UserExt.USER_EMAIL, normalizedEmail);
            user.put(UserExt.USER_EMAIL_VERIFIED, true);
            user.put(UserExt.USER_PASSWORD_HASH, org.b3log.solo.util.PasswordHashes.hash(rawPassword));
            user.put(UserExt.USER_B3_KEY, org.b3log.solo.util.PasswordHashes.randomSessionKey());
            user.put(UserExt.USER_STATUS, UserExt.USER_STATUS_C_VALID);
            user.put(UserExt.USER_CREATED, System.currentTimeMillis());
            final String id = userRepository.add(user);
            user.put(Keys.OBJECT_ID, id);
            transaction.commit();
            invalidatePublicCommon();
            return user;
        } catch (final Exception e) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
            if (e instanceof ServiceException se) {
                throw se;
            }
            LOGGER.log(Level.ERROR, "Registers reader failed", e);
            throw new ServiceException("注册读者账号失败，请稍后重试。");
        }
    }

    public void upgradePasswordHashQuietly(final String userId, final String rawPassword) {
        if (StringUtils.isBlank(userId) || StringUtils.isEmpty(rawPassword)) {
            return;
        }
        final Transaction transaction = userRepository.beginTransaction();
        try {
            final JSONObject user = userRepository.get(userId);
            if (user != null) {
                user.put(UserExt.USER_PASSWORD_HASH, org.b3log.solo.util.PasswordHashes.hash(rawPassword));
                userRepository.update(userId, user, UserExt.USER_PASSWORD_HASH);
            }
            transaction.commit();
        } catch (final Exception ignored) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
        }
    }

    /**
     * Enables or disables a non-administrator user account and immediately revokes active cookies on disable.
     */
    public synchronized void setUserStatus(final String userId, final int status) throws ServiceException {
        if (status != UserExt.USER_STATUS_C_VALID && status != UserExt.USER_STATUS_C_DISABLED) {
            throw new ServiceException("用户状态参数不正确。");
        }
        final Transaction transaction = userRepository.beginTransaction();
        try {
            final JSONObject user = userRepository.get(userId);
            if (user == null) {
                throw new ServiceException("用户不存在。");
            }
            if (Role.ADMIN_ROLE.equals(user.optString(User.USER_ROLE))) {
                throw new ServiceException("不能禁用站点管理员账号。");
            }
            user.put(UserExt.USER_STATUS, status);
            if (status == UserExt.USER_STATUS_C_DISABLED && Role.VISITOR_ROLE.equals(user.optString(User.USER_ROLE))) {
                user.put(UserExt.USER_B3_KEY, org.b3log.solo.util.PasswordHashes.randomSessionKey());
                userRepository.update(userId, user, UserExt.USER_STATUS, UserExt.USER_B3_KEY);
            } else {
                userRepository.update(userId, user, UserExt.USER_STATUS);
            }
            transaction.commit();
            invalidatePublicCommon();
        } catch (final Exception e) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
            if (e instanceof ServiceException se) {
                throw se;
            }
            LOGGER.log(Level.ERROR, "Updates user status failed", e);
            throw new ServiceException("更新用户状态失败。");
        }
    }

    public void removeUser(final String userId) throws ServiceException {
        final Transaction transaction = userRepository.beginTransaction();
        try {
            final JSONObject user = userRepository.get(userId);
            if (user != null && org.b3log.latke.model.Role.ADMIN_ROLE.equals(user.optString(User.USER_ROLE))) {
                transaction.rollback();
                throw new ServiceException("不能删除管理员账号。");
            }
            userRepository.remove(userId);

            transaction.commit();
            invalidatePublicCommon();
        } catch (final Exception e) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
            if (e instanceof ServiceException se) {
                throw se;
            }

            LOGGER.log(Level.ERROR, "Removes a user [id=" + userId + "] failed", e);
            throw new ServiceException(e);
        }
    }
}
