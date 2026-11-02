/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.security;

import org.b3log.latke.http.Request;
import org.b3log.solo.processor.OAuthProcessor;
import org.b3log.solo.service.CaptchaService;
import org.b3log.solo.util.LoginThrottle;

final class CaptchaTestSupport {
    static void prepare(final OAuthProcessor processor, final Request request) throws Exception {
        synchronized (processor) {
            final var field = OAuthProcessor.class.getDeclaredField("captchaService"); field.setAccessible(true);
            CaptchaService service = (CaptchaService) field.get(processor);
            if (service == null) { service = new CaptchaService(() -> "AB234", System::currentTimeMillis); field.set(processor, service); }
            final var challenge = service.issue("login", LoginThrottle.clientAddress(request));
            request.setParameter("captchaId", challenge.id()); request.setParameter("captchaAnswer", "AB234");
        }
    }
}
