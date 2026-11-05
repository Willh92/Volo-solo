/*
 * Volo - A lightweight, secure, and modern blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.processor;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.http.HttpMethod;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.http.renderer.AbstractFreeMarkerRenderer;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.ioc.Singleton;
import org.b3log.latke.model.Role;
import org.b3log.latke.model.User;
import org.b3log.latke.service.LangPropsService;
import org.b3log.latke.service.ServiceException;
import org.b3log.solo.SoloApplication;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.model.Common;
import org.b3log.solo.model.Option;
import org.b3log.solo.model.UserExt;
import org.b3log.solo.repository.UserRepository;
import org.b3log.solo.service.CaptchaService;
import org.b3log.solo.service.DataModelService;
import org.b3log.solo.service.EmailVerificationService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.service.RiskControlService;
import org.b3log.solo.service.UserMgmtService;
import org.b3log.solo.util.AdminRequests;
import org.b3log.solo.util.LoginThrottle;
import org.b3log.solo.util.PasswordHashes;
import org.b3log.solo.util.Solos;
import org.b3log.solo.web.annotation.RequestProcessing;
import org.json.JSONObject;

/**
 * Independent public reader authentication and email-verified registration endpoints.
 * Completely isolated from the hidden administrator login path and TOTP 2FA flow.
 */
@Singleton
public class ReaderAuthProcessor {

    private static final Logger LOGGER = Logger.getLogger(ReaderAuthProcessor.class);
    private static final LoginThrottle READER_THROTTLE = new LoginThrottle();
    private static final Set<String> RESERVED_NAMES = Set.of(
            "admin", "administrator", "root", "system", "webmaster", "owner", "author", "站长", "博主", "管理员"
    );

    @Inject
    private OptionQueryService optionQueryService;

    @Inject
    private UserRepository userRepository;

    @Inject
    private UserMgmtService userMgmtService;

    @Inject
    private CaptchaService captchaService;

    @Inject
    private EmailVerificationService emailVerificationService;

    @Inject
    private DataModelService dataModelService;

    @Inject
    private LangPropsService langPropsService;

    @RequestProcessing(value = "/user/login", method = HttpMethod.GET)
    public void showReaderLogin(final RequestContext context) {
        renderReaderAuthPage(context, "login");
    }

    @RequestProcessing(value = "/user/register", method = HttpMethod.GET)
    public void showReaderRegister(final RequestContext context) {
        renderReaderAuthPage(context, "register");
    }

    private void renderReaderAuthPage(final RequestContext context, final String mode) {
        context.setHeader("Cache-Control", "no-store");
        Solos.addGoogleNoIndex(context);
        final JSONObject currentUser = Solos.peekCurrentUser(context.getRequest());
        final String redirect = sanitizeRelativeRedirect(context.param("redirect"));
        if (currentUser != null) {
            context.sendRedirect(Latkes.getServePath() + redirect);
            return;
        }
        try {
            final AbstractFreeMarkerRenderer renderer = new SkinRenderer(context, "common-template/reader-auth.ftl");
            final Map<String, Object> dataModel = renderer.getDataModel();
            final JSONObject preference = optionQueryService.getPreference();
            dataModel.putAll(langPropsService.getAll(Latkes.getLocale()));
            dataModelService.fillCommon(context, dataModel, preference);
            dataModelService.fillFaviconURL(dataModel, preference);
            dataModelService.fillUsite(dataModel);
            Keys.fillServer(dataModel);
            Keys.fillRuntime(dataModel);
            dataModelService.fillMinified(dataModel);
            final boolean allowRegister = Option.optBooleanSwitch(preference, Option.ID_C_ALLOW_REGISTER, false);
            dataModel.put("authMode", "register".equals(mode) && allowRegister ? "register" : "login");
            dataModel.put("allowRegister", allowRegister);
            dataModel.put("redirectTarget", redirect);
            dataModel.put(Common.BOLO_VERSION, SoloApplication.BOLO_VERSION);
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Renders reader auth page failed", e);
            context.sendError(500);
        }
    }

    @RequestProcessing(value = "/api/reader/session", method = HttpMethod.GET)
    public void session(final RequestContext context) {
        context.setHeader("Cache-Control", "no-store");
        final JSONObject preference = optionQueryService.getPreference();
        final boolean allowRegister = Option.optBooleanSwitch(preference, Option.ID_C_ALLOW_REGISTER, false);
        final String commentAccessMode = Option.effectiveCommentAccessMode(preference);
        final JSONObject currentUser = Solos.peekCurrentUser(context.getRequest());
        final JSONObject ret = new JSONObject()
                .put("sc", true)
                .put("loggedIn", currentUser != null)
                .put("allowRegister", allowRegister)
                .put("commentAccessMode", commentAccessMode);
        if (currentUser != null) {
            ret.put("user", toPublicReader(currentUser));
        }
        context.renderJSON(ret);
    }

    @RequestProcessing(value = "/api/reader/send-code", method = HttpMethod.POST)
    public void sendCode(final RequestContext context) {
        context.setHeader("Cache-Control", "no-store");
        if (AdminRequests.isCrossSiteRequest(context)) {
            AdminRequests.reject(context, 403, "请求来源不正确，请刷新页面后重试。");
            return;
        }
        final JSONObject preference = optionQueryService.getPreference();
        if (!Option.optBooleanSwitch(preference, Option.ID_C_ALLOW_REGISTER, false)) {
            AdminRequests.reject(context, 403, "当前站点未开放公开用户注册。");
            return;
        }
        try {
            final JSONObject body = context.requestJSON();
            final String email = EmailVerificationService.normalizeEmail(body.optString("email"));
            if (email.isEmpty()) {
                AdminRequests.reject(context, 400, "请填写有效的邮箱地址。");
                return;
            }
            final String userName = body.optString("userName").strip();
            if (!userName.isEmpty()) {
                final String nameErr = validateReaderName(userName);
                if (nameErr != null) {
                    AdminRequests.reject(context, 400, nameErr);
                    return;
                }
            }
            final String clientAddress = LoginThrottle.clientAddress(context.getRequest());
            final String captchaId = body.optString("captchaId");
            final String captchaAnswer = body.optString("captchaAnswer");
            if (!captchaService.consume("register", clientAddress, captchaId, captchaAnswer)) {
                AdminRequests.reject(context, 400, "图形验证码错误或已过期，请点击验证码刷新后重试。");
                return;
            }
            if (userRepository.getByEmail(email) != null) {
                AdminRequests.reject(context, 409, "该邮箱已注册，请直接登录。");
                return;
            }
            if (!userName.isEmpty() && userRepository.getByUserName(userName) != null) {
                AdminRequests.reject(context, 409, "该昵称已被使用，请更换其他昵称。");
                return;
            }
            final int cooldown = emailVerificationService.sendRegistrationCode(email, clientAddress);
            context.renderJSON(new JSONObject()
                    .put("sc", true)
                    .put("cooldown", cooldown)
                    .put("msg", "6 位数字验证码已发送至您的邮箱，10 分钟内有效。"));
        } catch (final EmailVerificationService.CooldownException cooldown) {
            context.setHeader("Retry-After", String.valueOf(cooldown.retryAfterSeconds()));
            context.setStatus(429);
            context.renderJSON(new JSONObject()
                    .put("sc", false)
                    .put("retryAfter", cooldown.retryAfterSeconds())
                    .put("msg", cooldown.getMessage()));
        } catch (final IllegalArgumentException badInput) {
            AdminRequests.reject(context, 400, badInput.getMessage());
        } catch (final IllegalStateException unavailable) {
            AdminRequests.reject(context, 503, unavailable.getMessage());
        } catch (final Exception failure) {
            LOGGER.log(Level.ERROR, "Send reader verification code failed", failure);
            AdminRequests.reject(context, 500, "发送验证码失败，请稍后重试。");
        }
    }

    @RequestProcessing(value = "/api/reader/register", method = HttpMethod.POST)
    public void register(final RequestContext context) {
        context.setHeader("Cache-Control", "no-store");
        if (AdminRequests.isCrossSiteRequest(context)) {
            AdminRequests.reject(context, 403, "请求来源不正确，请刷新页面后重试。");
            return;
        }
        final JSONObject preference = optionQueryService.getPreference();
        if (!Option.optBooleanSwitch(preference, Option.ID_C_ALLOW_REGISTER, false)) {
            AdminRequests.reject(context, 403, "当前站点未开放公开用户注册。");
            return;
        }
        try {
            final JSONObject body = context.requestJSON();
            final String userName = body.optString("userName").strip();
            final String email = EmailVerificationService.normalizeEmail(body.optString("email"));
            final String password = body.optString("password");
            final String emailCode = body.optString("emailCode").strip();
            final String userURL = body.optString("userURL").strip();

            final String nameErr = validateReaderName(userName);
            if (nameErr != null) {
                AdminRequests.reject(context, 400, nameErr);
                return;
            }
            if (email.isEmpty()) {
                AdminRequests.reject(context, 400, "请填写有效的邮箱地址。");
                return;
            }
            if (!PasswordHashes.validReaderPassword(password)) {
                AdminRequests.reject(context, 400, "密码需为 8-64 位，且同时包含字母和数字。");
                return;
            }
            if (!emailCode.matches("[0-9]{6}")) {
                AdminRequests.reject(context, 400, "请输入 6 位数字邮箱验证码。");
                return;
            }

            final String clientAddress = LoginThrottle.clientAddress(context.getRequest());
            final LoginThrottle.Attempt attempt = READER_THROTTLE.acquire(clientAddress, email);
            if (attempt.retryAfter() > 0) {
                context.setHeader("Retry-After", String.valueOf(attempt.retryAfter()));
                RiskControlService.recordFailure(context, 429);
                context.setStatus(429);
                context.renderJSON(new JSONObject().put("sc", false).put("retryAfter", attempt.retryAfter())
                        .put("msg", "尝试过于频繁，请稍后再试。"));
                return;
            }

            if (!emailVerificationService.verifyAndConsume(email, emailCode)) {
                AdminRequests.reject(context, 400, "邮箱验证码错误或已过期，请重新获取。");
                return;
            }

            final JSONObject registered = userMgmtService.registerReader(userName, email, password, userURL);
            Solos.login(registered, context.getResponse());
            READER_THROTTLE.succeeded(attempt);
            LOGGER.log(Level.INFO, "Registered reader [name={0}, email={1}]", userName, email);
            notifyReaderRegisteredSafely(userName, email);

            context.renderJSON(new JSONObject()
                    .put("sc", true)
                    .put("msg", "注册成功，已自动登录。")
                    .put("user", toPublicReader(registered)));
        } catch (final ServiceException bizError) {
            AdminRequests.reject(context, 409, bizError.getMessage());
        } catch (final Exception failure) {
            LOGGER.log(Level.ERROR, "Reader registration failed", failure);
            AdminRequests.reject(context, 500, "注册失败，请稍后重试。");
        }
    }

    @RequestProcessing(value = "/api/reader/login", method = HttpMethod.POST)
    public void login(final RequestContext context) {
        context.setHeader("Cache-Control", "no-store");
        if (AdminRequests.isCrossSiteRequest(context)) {
            AdminRequests.reject(context, 403, "请求来源不正确，请刷新页面后重试。");
            return;
        }
        try {
            final JSONObject body = context.requestJSON();
            final String account = body.optString("account").strip();
            final String password = body.optString("password");
            if (account.isEmpty() || account.length() > 254 || StringUtils.isEmpty(password) || password.length() > 1024) {
                AdminRequests.reject(context, 400, "请填写邮箱/昵称和密码。");
                return;
            }

            final String clientAddress = LoginThrottle.clientAddress(context.getRequest());
            final LoginThrottle.Attempt attempt = READER_THROTTLE.acquire(clientAddress, account);
            if (attempt.retryAfter() > 0) {
                context.setHeader("Retry-After", String.valueOf(attempt.retryAfter()));
                RiskControlService.recordFailure(context, 429);
                context.setStatus(429);
                context.renderJSON(new JSONObject().put("sc", false).put("retryAfter", attempt.retryAfter())
                        .put("msg", "登录尝试过于频繁，请稍后再试。"));
                return;
            }

            final JSONObject user;
            if (account.contains("@")) {
                final String normalizedEmail = EmailVerificationService.normalizeEmail(account);
                user = normalizedEmail.isEmpty() ? null : userRepository.getByEmail(normalizedEmail);
            } else {
                user = userRepository.getByUserName(account);
            }

            // Strictly isolate administrator/author accounts from public reader login so admin login paths & TOTP 2FA can never be bypassed
            if (user == null || !Role.VISITOR_ROLE.equals(user.optString(User.USER_ROLE))
                    || !PasswordHashes.verify(password, user)) {
                AdminRequests.reject(context, 401, "邮箱/昵称或密码错误（博主请从管理入口登录）。");
                return;
            }

            if (user.optInt(UserExt.USER_STATUS, UserExt.USER_STATUS_C_VALID) != UserExt.USER_STATUS_C_VALID) {
                AdminRequests.reject(context, 403, "该账号已被站点管理员禁用。");
                return;
            }

            // Transparently upgrade legacy visitor accounts without PBKDF2 hash
            if (user.optString(UserExt.USER_PASSWORD_HASH).isBlank()) {
                userMgmtService.upgradePasswordHashQuietly(user.optString(Keys.OBJECT_ID), password);
            }

            Solos.login(user, context.getResponse());
            READER_THROTTLE.succeeded(attempt);
            context.renderJSON(new JSONObject()
                    .put("sc", true)
                    .put("msg", "登录成功。")
                    .put("user", toPublicReader(user)));
        } catch (final Exception failure) {
            LOGGER.log(Level.ERROR, "Reader login failed", failure);
            AdminRequests.reject(context, 500, "登录失败，请稍后重试。");
        }
    }

    @RequestProcessing(value = "/api/reader/logout", method = HttpMethod.POST)
    public void logout(final RequestContext context) {
        context.setHeader("Cache-Control", "no-store");
        if (AdminRequests.isCrossSiteRequest(context)) {
            AdminRequests.reject(context, 403, "请求来源不正确。");
            return;
        }
        Solos.logout(context.getRequest(), context.getResponse());
        context.renderJSON(new JSONObject().put("sc", true).put("msg", "已退出登录。"));
    }

    public static String validateReaderName(final String rawName) {
        if (rawName == null) {
            return "请填写用户昵称。";
        }
        final String name = rawName.strip();
        if (name.length() < 2 || name.length() > 32) {
            return "用户昵称长度需在 2 至 32 个字符之间。";
        }
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            if (Character.isISOControl(c) || "<>\"'&@\\".indexOf(c) >= 0) {
                return "用户昵称不能包含特殊控制字符或 @、<、>、引号。";
            }
        }
        final String lower = name.toLowerCase(Locale.ROOT);
        if (RESERVED_NAMES.contains(lower) || lower.contains("admin")) {
            return "该昵称为系统保留名称，请更换其他昵称。";
        }
        return null;
    }

    public static JSONObject toPublicReader(final JSONObject user) {
        final String avatar = user.optString(UserExt.USER_AVATAR);
        return new JSONObject()
                .put(Keys.OBJECT_ID, user.optString(Keys.OBJECT_ID))
                .put(User.USER_NAME, user.optString(User.USER_NAME))
                .put(UserExt.USER_EMAIL, user.optString(UserExt.USER_EMAIL))
                .put(User.USER_ROLE, user.optString(User.USER_ROLE))
                .put(User.USER_URL, user.optString(User.USER_URL))
                .put(UserExt.USER_AVATAR, avatar.isEmpty() ? Solos.getDefaultAvatar() : avatar);
    }

    private static String sanitizeRelativeRedirect(final String raw) {
        if (raw == null || raw.isBlank()) {
            return "/";
        }
        final String trimmed = raw.strip();
        if (!trimmed.startsWith("/") || trimmed.startsWith("//") || trimmed.contains("\\")
                || trimmed.chars().anyMatch(Character::isISOControl)) {
            return "/";
        }
        final String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("/user/login") || lower.startsWith("/user/register")
                || lower.startsWith("/console") || lower.startsWith("/admin")) {
            return "/";
        }
        return trimmed;
    }

    private void notifyReaderRegisteredSafely(final String userName, final String email) {
        try {
            final var beans = org.b3log.latke.ioc.BeanManager.getInstance();
            final Class<?> webhookCls = Class.forName("org.b3log.solo.service.WebhookNotifyService");
            final Object svc = beans.getReference(webhookCls);
            if (svc != null) {
                webhookCls.getMethod("notifyEvent", String.class, String.class, JSONObject.class)
                        .invoke(svc, "user.registered", "新读者注册：" + userName,
                                new JSONObject().put("userName", userName).put("email", email));
            }
        } catch (final Throwable ignored) {
            // WebhookNotifyService is wired in Phase 6 or optional.
        }
    }
}
