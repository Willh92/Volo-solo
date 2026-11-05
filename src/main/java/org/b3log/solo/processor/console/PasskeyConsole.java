/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.processor.console;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;
import org.b3log.latke.Keys;
import org.b3log.latke.http.HttpMethod;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.ioc.Singleton;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.service.AnalyticsTrendService;
import org.b3log.solo.service.PasskeyService;
import org.b3log.solo.util.AdminRequests;
import org.b3log.solo.util.Solos;
import org.b3log.solo.web.annotation.RequestProcessing;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Console endpoints for privacy-first analytics trends ({@code /console/stat/trends})
 * and administrator WebAuthn/Passkey management ({@code /console/security/passkeys/*}).
 *
 * @author <a href="https://github.com/Willh92">Willh92</a>
 * @since 3.0.0
 */
@Singleton
public class PasskeyConsole {

    private static final Logger LOGGER = Logger.getLogger(PasskeyConsole.class);

    @Inject
    private AnalyticsTrendService analyticsTrendService;

    @Inject
    private PasskeyService passkeyService;

    protected boolean adminOrAuthorLoggedIn(final RequestContext context) {
        return Solos.isAdminOrAuthorLoggedIn(context);
    }

    protected boolean userLoggedIn(final RequestContext context) {
        return Solos.isLoggedIn(context);
    }

    protected boolean adminLoggedIn(final RequestContext context) {
        return Solos.isAdminLoggedIn(context);
    }

    protected boolean allowWrite(final RequestContext context) {
        return AdminRequests.allowWrite(context);
    }

    protected JSONObject currentAdmin(final RequestContext context) {
        return Solos.getCurrentUser(context.getRequest(), context.getResponse());
    }

    @RequestProcessing(value = "/console/stat/trends", method = HttpMethod.GET)
    public void getStatTrends(final RequestContext context) {
        context.setHeader("Cache-Control", "no-store");
        if (!adminOrAuthorLoggedIn(context)) {
            AdminRequests.reject(context, 401, "请先登录管理后台。");
            return;
        }
        int days = 7;
        final String rawDays = context.param("days");
        if (StringUtils.isNotBlank(rawDays)) {
            try {
                days = Integer.parseInt(rawDays.trim());
            } catch (final NumberFormatException ignored) {
                days = 7;
            }
        }
        try {
            final JSONObject trends = analyticsTrendService.queryTrends(days);
            context.renderJSON(new JSONObject()
                    .put("sc", true)
                    .put("data", trends));
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Queries analytics trends failed", e);
            AdminRequests.reject(context, 503, "访问趋势数据暂时无法读取。");
        }
    }

    @RequestProcessing(value = "/console/security/passkeys", method = HttpMethod.GET)
    public void listPasskeys(final RequestContext context) {
        context.setHeader("Cache-Control", "no-store");
        if (!adminLoggedIn(context)) {
            AdminRequests.reject(context, userLoggedIn(context) ? 403 : 401, "仅管理员可以管理通行密钥。");
            return;
        }
        try {
            final JSONObject currentUser = currentAdmin(context);
            if (currentUser == null) {
                AdminRequests.reject(context, 401, "请先登录管理后台。");
                return;
            }
            final JSONArray credentials = passkeyService.listCredentialsForUser(currentUser.optString(Keys.OBJECT_ID));
            context.renderJSON(new JSONObject()
                    .put("sc", true)
                    .put("data", new JSONObject()
                            .put("rpId", resolveRpIdSafe())
                            .put("credentials", credentials)));
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Lists passkeys failed", e);
            AdminRequests.reject(context, 503, "通行密钥列表暂时无法读取。");
        }
    }

    @RequestProcessing(value = "/console/security/passkeys/register/options", method = HttpMethod.POST)
    public void registerOptions(final RequestContext context) {
        if (!allowWrite(context)) {
            return;
        }
        if (!adminLoggedIn(context)) {
            AdminRequests.reject(context, 403, "仅管理员可以管理通行密钥。");
            return;
        }
        try {
            final JSONObject currentUser = currentAdmin(context);
            if (currentUser == null) {
                AdminRequests.reject(context, 401, "请先登录管理后台。");
                return;
            }
            final JSONObject body = parseBody(context);
            final JSONObject options = passkeyService.startRegistration(currentUser, body.optString("name"));
            context.renderJSON(new JSONObject()
                    .put("sc", true)
                    .put("data", options));
        } catch (final IllegalArgumentException | IllegalStateException invalid) {
            AdminRequests.reject(context, 400, invalid.getMessage());
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Starts passkey registration failed", e);
            AdminRequests.reject(context, 503, "暂时无法生成通行密钥注册参数，请稍后重试。");
        }
    }

    @RequestProcessing(value = "/console/security/passkeys/register/verify", method = HttpMethod.POST)
    public void registerVerify(final RequestContext context) {
        if (!allowWrite(context)) {
            return;
        }
        if (!adminLoggedIn(context)) {
            AdminRequests.reject(context, 403, "仅管理员可以管理通行密钥。");
            return;
        }
        try {
            final JSONObject currentUser = currentAdmin(context);
            if (currentUser == null) {
                AdminRequests.reject(context, 401, "请先登录管理后台。");
                return;
            }
            final JSONObject body = parseBody(context);
            final JSONObject data = passkeyService.finishRegistration(currentUser, body);
            context.renderJSON(new JSONObject()
                    .put("sc", true)
                    .put("msg", "通行密钥已成功绑定。")
                    .put("data", data));
        } catch (final IllegalArgumentException | IllegalStateException invalid) {
            AdminRequests.reject(context, 400, invalid.getMessage());
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Verifies passkey registration failed", e);
            AdminRequests.reject(context, 503, "通行密钥绑定失败，请重试。");
        }
    }

    @RequestProcessing(value = "/console/security/passkeys/{id}", method = HttpMethod.DELETE)
    public void deletePasskey(final RequestContext context) {
        if (!allowWrite(context)) {
            return;
        }
        if (!adminLoggedIn(context)) {
            AdminRequests.reject(context, 403, "仅管理员可以管理通行密钥。");
            return;
        }
        try {
            final JSONObject currentUser = currentAdmin(context);
            if (currentUser == null) {
                AdminRequests.reject(context, 401, "请先登录管理后台。");
                return;
            }
            final String id = StringUtils.trimToEmpty(context.pathVar("id"));
            final JSONArray remaining = passkeyService.removeCredential(currentUser.optString(Keys.OBJECT_ID), id);
            context.renderJSON(new JSONObject()
                    .put("sc", true)
                    .put("msg", "通行密钥已移除。")
                    .put("data", new JSONObject().put("credentials", remaining)));
        } catch (final IllegalArgumentException invalid) {
            AdminRequests.reject(context, 400, invalid.getMessage());
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Removes passkey failed", e);
            AdminRequests.reject(context, 503, "移除通行密钥失败，请重试。");
        }
    }

    private static JSONObject parseBody(final RequestContext context) {
        try {
            final JSONObject json = context.requestJSON();
            return json != null ? json : new JSONObject();
        } catch (final Exception ignored) {
            return new JSONObject();
        }
    }

    private static String resolveRpIdSafe() {
        try {
            return PasskeyService.currentRpId();
        } catch (final Exception ignored) {
            return "";
        }
    }
}
