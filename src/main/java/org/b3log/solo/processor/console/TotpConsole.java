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
package org.b3log.solo.processor.console;

import org.b3log.latke.Keys;
import org.b3log.latke.http.HttpMethod;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.ioc.Singleton;
import org.b3log.latke.model.User;
import org.b3log.solo.service.RiskControlService;
import org.b3log.solo.service.TotpService;
import org.b3log.solo.util.AdminRequests;
import org.b3log.solo.util.LoginThrottle;
import org.b3log.solo.util.Solos;
import org.b3log.solo.web.annotation.RequestProcessing;
import org.json.JSONObject;

/**
 * Administrator-only 2FA (RFC 6238 TOTP) management endpoints.
 */
@Singleton
public class TotpConsole {
    private final LoginThrottle verificationThrottle;

    @Inject
    private TotpService totpService;

    public TotpConsole() {
        this(new LoginThrottle());
    }

    public TotpConsole(final LoginThrottle verificationThrottle) {
        this.verificationThrottle = verificationThrottle;
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

    protected String clientAddress(final RequestContext context) {
        return LoginThrottle.clientAddress(context.getRequest());
    }

    private LoginThrottle.Attempt acquireVerification(final RequestContext context, final JSONObject admin) {
        final String account = "totp:" + (admin == null ? "" : admin.optString(Keys.OBJECT_ID));
        final LoginThrottle.Attempt attempt = verificationThrottle.acquire(clientAddress(context), account);
        if (attempt.retryAfter() > 0) {
            RiskControlService.recordFailure(context, 429);
            context.setHeader("Retry-After", String.valueOf(attempt.retryAfter()));
            context.setStatus(429);
            context.renderJSON(new JSONObject()
                    .put("sc", false)
                    .put("msg", "动态口令校验尝试过于频繁，请稍后再试。")
                    .put("retryAfter", attempt.retryAfter()));
            return null;
        }
        return attempt;
    }

    @RequestProcessing(value = "/console/security/totp", method = HttpMethod.GET)
    public void status(final RequestContext context) {
        context.setHeader("Cache-Control", "no-store");
        if (!adminLoggedIn(context)) {
            AdminRequests.reject(context, 403, "仅管理员可以查看 2FA 设置。");
            return;
        }
        try {
            context.renderJSON(new JSONObject().put("sc", true).put("totp", totpService.publicStatus()));
        } catch (final Exception failure) {
            AdminRequests.reject(context, 503, "2FA 配置暂时无法读取。");
        }
    }

    @RequestProcessing(value = "/console/security/totp/settings", method = HttpMethod.PUT)
    public void saveSettings(final RequestContext context) {
        if (!allowWrite(context)) {
            return;
        }
        try {
            final JSONObject request = context.requestJSON();
            LoginThrottle.Attempt attempt = null;
            if (totpService.settingRequiresVerification(request)) {
                attempt = acquireVerification(context, currentAdmin(context));
                if (attempt == null) {
                    return;
                }
            }
            final JSONObject totp = totpService.updateSettings(request);
            if (attempt != null) {
                verificationThrottle.succeeded(attempt);
            }
            context.renderJSON(new JSONObject()
                    .put("sc", true)
                    .put("msg", "2FA 配置已保存，立即生效。")
                    .put("totp", totp));
        } catch (final IllegalArgumentException invalid) {
            AdminRequests.reject(context, 400, invalid.getMessage());
        } catch (final Exception failure) {
            AdminRequests.reject(context, 503, "2FA 配置保存失败，请重试。");
        }
    }

    @RequestProcessing(value = "/console/security/totp/setup", method = HttpMethod.POST)
    public void prepareSetup(final RequestContext context) {
        if (!allowWrite(context)) {
            return;
        }
        try {
            final JSONObject user = currentAdmin(context);
            if (user == null) {
                AdminRequests.reject(context, 401, "请先登录管理后台。");
                return;
            }
            final JSONObject setup = totpService.prepareSetup(
                    user.optString(Keys.OBJECT_ID),
                    user.optString(User.USER_NAME)
            );
            context.renderJSON(new JSONObject().put("sc", true).put("setup", setup));
        } catch (final IllegalArgumentException invalid) {
            AdminRequests.reject(context, 400, invalid.getMessage());
        } catch (final Exception failure) {
            AdminRequests.reject(context, 503, "暂时无法生成绑定密钥，请稍后重试。");
        }
    }

    @RequestProcessing(value = "/console/security/totp/confirm", method = HttpMethod.POST)
    public void confirmSetup(final RequestContext context) {
        if (!allowWrite(context)) {
            return;
        }
        try {
            final JSONObject user = currentAdmin(context);
            if (user == null) {
                AdminRequests.reject(context, 401, "请先登录管理后台。");
                return;
            }
            final LoginThrottle.Attempt attempt = acquireVerification(context, user);
            if (attempt == null) {
                return;
            }
            final JSONObject request = context.requestJSON();
            final JSONObject result = totpService.confirmSetup(
                    user.optString(Keys.OBJECT_ID),
                    request.optString("setupId"),
                    request.optString("code"),
                    request.optString("currentCode")
            );
            verificationThrottle.succeeded(attempt);
            TotpService.clearTrustedDeviceCookie(context.getResponse());
            context.renderJSON(result
                    .put("sc", true)
                    .put("msg", "2FA 已绑定并启用，请妥善保存应急恢复码。"));
        } catch (final IllegalArgumentException invalid) {
            AdminRequests.reject(context, 400, invalid.getMessage());
        } catch (final Exception failure) {
            AdminRequests.reject(context, 503, "2FA 绑定保存失败，请重试。");
        }
    }

    @RequestProcessing(value = "/console/security/totp/recovery", method = HttpMethod.POST)
    public void regenerateRecovery(final RequestContext context) {
        if (!allowWrite(context)) {
            return;
        }
        try {
            final LoginThrottle.Attempt attempt = acquireVerification(context, currentAdmin(context));
            if (attempt == null) {
                return;
            }
            final JSONObject request = context.requestJSON();
            final JSONObject result = totpService.regenerateRecoveryCodes(request.optString("verifyCode"));
            verificationThrottle.succeeded(attempt);
            context.renderJSON(result
                    .put("sc", true)
                    .put("msg", "已重新生成 8 组应急恢复码，原有恢复码已失效。"));
        } catch (final IllegalArgumentException invalid) {
            AdminRequests.reject(context, 400, invalid.getMessage());
        } catch (final Exception failure) {
            AdminRequests.reject(context, 503, "应急恢复码生成失败，请重试。");
        }
    }

    @RequestProcessing(value = "/console/security/totp/revoke-devices", method = HttpMethod.POST)
    public void revokeDevices(final RequestContext context) {
        if (!allowWrite(context)) {
            return;
        }
        try {
            final JSONObject totp = totpService.revokeTrustedDevices(context.getResponse());
            context.renderJSON(new JSONObject()
                    .put("sc", true)
                    .put("msg", "已撤销全部记住的浏览器，下次登录均需输入动态口令。")
                    .put("totp", totp));
        } catch (final Exception failure) {
            AdminRequests.reject(context, 503, "撤销受信浏览器失败，请重试。");
        }
    }

    @RequestProcessing(value = "/console/security/totp/unbind", method = HttpMethod.POST)
    public void unbind(final RequestContext context) {
        if (!allowWrite(context)) {
            return;
        }
        try {
            LoginThrottle.Attempt attempt = null;
            if (totpService.isActive()) {
                attempt = acquireVerification(context, currentAdmin(context));
                if (attempt == null) {
                    return;
                }
            }
            final JSONObject request = context.requestJSON();
            final JSONObject totp = totpService.unbind(request.optString("verifyCode"), context.getResponse());
            if (attempt != null) {
                verificationThrottle.succeeded(attempt);
            }
            context.renderJSON(new JSONObject()
                    .put("sc", true)
                    .put("msg", "已解绑 2FA 并关闭登录二次验证。")
                    .put("totp", totp));
        } catch (final IllegalArgumentException invalid) {
            AdminRequests.reject(context, 400, invalid.getMessage());
        } catch (final Exception failure) {
            AdminRequests.reject(context, 503, "解绑 2FA 失败，请重试。");
        }
    }
}
