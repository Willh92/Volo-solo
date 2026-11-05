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

import io.github.biezhi.ome.OhMyEmail;
import io.github.biezhi.ome.SendMailException;
import org.apache.logging.log4j.Level;
import org.b3log.solo.logging.Logger;
import org.b3log.latke.http.HttpMethod;
import org.b3log.latke.http.RequestContext;
import org.b3log.solo.web.annotation.RequestProcessing;
import org.b3log.latke.ioc.Singleton;
import org.b3log.solo.util.Solos;

import org.b3log.latke.http.Request;
import org.b3log.latke.http.Response;

/**
 * <h3>bolo-solo</h3>
 * <p>属性 API.</p>
 *
 * @author : https://github.com/adlered
 * @date : 2019-12-20 20:02
 **/
@Singleton
public class MailProcessor {
    private static final Logger LOGGER = Logger.getLogger(MailProcessor.class);
    private static final java.util.concurrent.ThreadPoolExecutor MAILS = new java.util.concurrent.ThreadPoolExecutor(
            1, 2, 60L, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(64), task -> {
                final Thread thread = new Thread(task, "volo-mail"); thread.setDaemon(true); return thread;
            });

    static {
        MAILS.allowCoreThreadTimeOut(true);
    }

    public static void shutdown() {
        MAILS.shutdown();
    }

    /**
     * 发送普通邮件
     *
     * @param subject
     * @param from
     * @param to
     * @param html
     * @throws SendMailException
     */
    public static void localSendMailMethod(String subject, String from, String to, String html) {
        if (!MailService.isConfigured() || to == null || to.isBlank()) return;
        try {
            MAILS.execute(() -> {
                if (!MailService.isConfigured()) return;
                try {
                    OhMyEmail.subject(subject).from(from, MailService.senderAddress()).to(to).html(html).send();
                    LOGGER.log(Level.DEBUG, "Comment notification sent.");
                } catch (SendMailException failure) {
                    LOGGER.log(Level.WARN, "Comment notification failed. Check SMTP settings.");
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            LOGGER.log(Level.WARN, "Comment notification queue is full; the comment is already saved.");
        }
    }

    /*
        === 静态方法区 ===
     */

    @RequestProcessing(value = "/prop/mail/send", method = {HttpMethod.POST})
    public void sendMail(final RequestContext context) {
        if (!org.b3log.solo.util.AdminRequests.allowWrite(context)) return;
        try {
            final var payload = context.requestJSON();
            final String subject = payload.optString("subject"), from = payload.optString("from"), to = payload.optString("to"), html = payload.optString("html");
            if (subject.length() > 200 || from.length() > 200 || to.length() > 254 || !to.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") || html.length() > 50000) {
                org.b3log.solo.util.AdminRequests.reject(context, 400, "邮件参数不正确。"); return;
            }
            if (!MailService.isConfigured()) { org.b3log.solo.util.AdminRequests.reject(context, 400, "请先配置通知邮箱。"); return; }
            localSendMailMethod(subject, from, to, html);
            context.renderJSON(new org.json.JSONObject().put("sc", true).put("msg", "已加入邮件发送队列。"));
        } catch (RuntimeException invalid) { org.b3log.solo.util.AdminRequests.reject(context, 400, "邮件请求格式不正确。"); }
    }
}
