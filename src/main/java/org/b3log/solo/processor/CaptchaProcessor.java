/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.processor;

import org.b3log.latke.http.HttpMethod;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.ioc.Singleton;
import org.b3log.solo.service.CaptchaService;
import org.b3log.solo.util.AdminRequests;
import org.b3log.solo.util.LoginThrottle;
import org.b3log.solo.util.Solos;
import org.b3log.solo.web.annotation.RequestProcessing;
import org.json.JSONObject;

@Singleton
public class CaptchaProcessor {
    @Inject private CaptchaService captchaService;
    @RequestProcessing(value = "/captcha", method = HttpMethod.GET)
    public void challenge(final RequestContext context) {
        context.setHeader("Cache-Control", "no-store");
        try {
            final String purpose = context.param("purpose");
            if (!java.util.Set.of("login", "comment").contains(purpose == null ? "" : purpose)) throw new IllegalArgumentException("验证码用途不正确。");
            if (purpose.equals("comment") && (Solos.isAdminLoggedIn(context) || !captchaService.commentsRequired())) {
                context.renderJSON(new JSONObject().put("sc", true).put("required", false)); return;
            }
            final var challenge = captchaService.issue(purpose, LoginThrottle.clientAddress(context.getRequest()));
            context.renderJSON(new JSONObject().put("sc", true).put("required", true).put("captchaId", challenge.id()).put("image", challenge.image()).put("expiresAt", challenge.expiresAt()));
        } catch (IllegalArgumentException invalid) { AdminRequests.reject(context, 400, invalid.getMessage()); }
        catch (IllegalStateException limited) { context.setHeader("Retry-After", "60"); AdminRequests.reject(context, 429, limited.getMessage()); }
        catch (Exception failure) { AdminRequests.reject(context, 503, "验证码暂时无法生成，请稍后重试。"); }
    }
}
