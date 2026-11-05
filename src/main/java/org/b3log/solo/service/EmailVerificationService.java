/*
 * Volo - A lightweight, secure, and modern blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.service;

import io.github.biezhi.ome.OhMyEmail;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.logging.log4j.Level;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.service.annotation.Service;
import org.b3log.solo.bolo.prop.MailService;
import org.b3log.solo.bolo.prop.SmtpSettings;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.model.Option;
import org.b3log.solo.util.LoginThrottle;
import org.json.JSONObject;

/**
 * Issues and verifies 6-digit email verification codes with bounded memory, per-email cooldown, and per-IP rate limits.
 */
@Service
public class EmailVerificationService {

    private static final Logger LOGGER = Logger.getLogger(EmailVerificationService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,189}\\.[A-Za-z]{2,24}$");

    public static final long CODE_TTL_MS = 10 * 60_000L;
    public static final long EMAIL_COOLDOWN_MS = 60_000L;
    public static final long IP_WINDOW_MS = 60 * 60_000L;
    public static final int MAX_SENDS_PER_IP_HOUR = 10;
    public static final int MAX_VERIFY_ATTEMPTS = 5;
    private static final int MAX_ACTIVE_CHALLENGES = 2048;
    private static final int MAX_IP_WINDOWS = 4096;

    @FunctionalInterface
    public interface MailSender {
        void send(String toEmail, String blogTitle, String subject, String htmlBody) throws Exception;
    }

    private static final class ChallengeEntry {
        final String salt;
        final byte[] digest;
        final long issuedAt;
        final long expiresAt;
        int failedAttempts;

        ChallengeEntry(final String salt, final byte[] digest, final long issuedAt, final long expiresAt) {
            this.salt = salt;
            this.digest = digest;
            this.issuedAt = issuedAt;
            this.expiresAt = expiresAt;
        }
    }

    private record IpSendWindow(long windowStart, int count) {
    }

    private final LinkedHashMap<String, ChallengeEntry> challenges = new LinkedHashMap<>();
    private final LinkedHashMap<String, IpSendWindow> ipWindows = new LinkedHashMap<>();
    private final LongSupplier clock;
    private volatile MailSender mailSender;
    private volatile boolean customSender;

    @Inject
    private OptionQueryService optionQueryService;

    public EmailVerificationService() {
        this.clock = System::currentTimeMillis;
        this.mailSender = EmailVerificationService::sendViaOhMyEmail;
        this.customSender = false;
    }

    public EmailVerificationService(final LongSupplier clock, final MailSender mailSender) {
        this.clock = clock;
        this.mailSender = mailSender == null ? EmailVerificationService::sendViaOhMyEmail : mailSender;
        this.customSender = mailSender != null;
    }

    public void setMailSender(final MailSender sender) {
        this.mailSender = sender == null ? EmailVerificationService::sendViaOhMyEmail : sender;
        this.customSender = sender != null;
    }

    public static String normalizeEmail(final String rawEmail) {
        if (rawEmail == null) {
            return "";
        }
        final String cleaned = rawEmail.strip().toLowerCase(Locale.ROOT);
        if (cleaned.length() > 254 || cleaned.chars().anyMatch(Character::isISOControl)) {
            return "";
        }
        return EMAIL_PATTERN.matcher(cleaned).matches() ? cleaned : "";
    }

    /**
     * Generates and sends a 6-digit verification code to {@code rawEmail}.
     *
     * @return cooldown seconds (`60`) on success
     */
    public int sendRegistrationCode(final String rawEmail, final String clientAddress) {
        final String email = normalizeEmail(rawEmail);
        if (email.isEmpty()) {
            throw new IllegalArgumentException("请输入有效的邮箱地址。");
        }
        final JSONObject preference = optionQueryService == null ? null : optionQueryService.getPreference();
        final boolean smtpReady = checkSmtpReady(preference);
        if (!smtpReady) {
            throw new IllegalStateException("站点尚未完成邮箱发信配置，暂时无法发送验证码。");
        }

        final String sourceKey = LoginThrottle.sourceKey(clientAddress);
        final String code;
        final ChallengeEntry previousEntry;
        synchronized (this) {
            final long now = clock.getAsLong();
            pruneExpired(now);

            final ChallengeEntry existing = challenges.get(email);
            if (existing != null && now - existing.issuedAt < EMAIL_COOLDOWN_MS) {
                final int remain = (int) Math.max(1L, (EMAIL_COOLDOWN_MS - (now - existing.issuedAt) + 999L) / 1000L);
                throw new CooldownException("验证码发送过于频繁，请在 " + remain + " 秒后重试。", remain);
            }

            final IpSendWindow ipWin = ipWindows.get(sourceKey);
            final int nextCount = (ipWin == null || now - ipWin.windowStart >= IP_WINDOW_MS) ? 1 : ipWin.count + 1;
            if (nextCount > MAX_SENDS_PER_IP_HOUR) {
                throw new CooldownException("当前网络发送验证码次数已达上限，请稍后再试。", 300);
            }
            if (ipWin == null && ipWindows.size() >= MAX_IP_WINDOWS) {
                ipWindows.remove(ipWindows.keySet().iterator().next());
            }
            ipWindows.put(sourceKey, new IpSendWindow(nextCount == 1 ? now : ipWin.windowStart, nextCount));

            while (challenges.size() >= MAX_ACTIVE_CHALLENGES && !challenges.containsKey(email)) {
                challenges.remove(challenges.keySet().iterator().next());
            }

            code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
            final byte[] saltBytes = new byte[12];
            RANDOM.nextBytes(saltBytes);
            final String salt = HexFormat.of().formatHex(saltBytes);
            previousEntry = challenges.put(email,
                    new ChallengeEntry(salt, digest(email, salt, code), now, now + CODE_TTL_MS));
        }

        final String blogTitle = (preference == null ? "" : preference.optString(Option.ID_C_BLOG_TITLE, "Volo")).strip();
        final String displayBlog = blogTitle.isEmpty() ? "Volo" : blogTitle;
        final String subject = "【" + displayBlog + "】账号注册邮箱验证码";
        final String html = buildVerificationHtml(displayBlog, code);
        try {
            mailSender.send(email, displayBlog, subject, html);
            return (int) (EMAIL_COOLDOWN_MS / 1000L);
        } catch (final Exception sendFailure) {
            synchronized (this) {
                if (previousEntry != null) {
                    challenges.put(email, previousEntry);
                } else {
                    challenges.remove(email);
                }
            }
            LOGGER.log(Level.WARN, "Failed to send reader verification email", sendFailure);
            throw new IllegalStateException("验证邮件发送失败，请稍后重试或联系站长检查 SMTP 配置。");
        }
    }

    /**
     * Verifies and consumes a 6-digit code in constant time. Invalidates challenge after {@link #MAX_VERIFY_ATTEMPTS} misses.
     */
    public synchronized boolean verifyAndConsume(final String rawEmail, final String rawCode) {
        final String email = normalizeEmail(rawEmail);
        if (email.isEmpty() || rawCode == null) {
            return false;
        }
        final String code = rawCode.strip();
        if (!code.matches("[0-9]{6}")) {
            return false;
        }
        final long now = clock.getAsLong();
        pruneExpired(now);
        final ChallengeEntry entry = challenges.get(email);
        if (entry == null || entry.expiresAt <= now) {
            challenges.remove(email);
            return false;
        }
        final byte[] actual = digest(email, entry.salt, code);
        if (MessageDigest.isEqual(entry.digest, actual)) {
            challenges.remove(email);
            return true;
        }
        entry.failedAttempts++;
        if (entry.failedAttempts >= MAX_VERIFY_ATTEMPTS) {
            challenges.remove(email);
        }
        return false;
    }

    private void pruneExpired(final long now) {
        challenges.values().removeIf(entry -> entry.expiresAt <= now);
        ipWindows.values().removeIf(win -> now - win.windowStart >= IP_WINDOW_MS);
    }

    private boolean checkSmtpReady(final JSONObject preference) {
        if (customSender) {
            return true;
        }
        if (MailService.isConfigured()) {
            return true;
        }
        if (preference == null) {
            return false;
        }
        try {
            final SmtpSettings smtp = SmtpSettings.read(preference);
            if (smtp.configured()) {
                MailService.loadMailSettings();
                return MailService.isConfigured();
            }
        } catch (final Exception ignored) {
        }
        return false;
    }

    private static byte[] digest(final String email, final String salt, final String code) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest((email + ":" + salt + ":" + code).getBytes(StandardCharsets.UTF_8));
        } catch (final Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String buildVerificationHtml(final String blogTitle, final String code) {
        final String safeTitle = StringEscapeUtils.escapeHtml4(blogTitle);
        final String safeCode = StringEscapeUtils.escapeHtml4(code);
        return "<div style=\"max-width:520px;margin:24px auto;padding:28px 32px;border-radius:14px;"
                + "background:#f8faf9;border:1px solid #dce5e0;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;color:#1c2b26;\">"
                + "<div style=\"font-size:13px;font-weight:600;letter-spacing:.08em;color:#2d6a5b;text-transform:uppercase;\">Volo Email Verification</div>"
                + "<h2 style=\"margin:10px 0 14px;font-size:20px;color:#142822;\">欢迎注册「" + safeTitle + "」</h2>"
                + "<p style=\"margin:0 0 18px;font-size:14px;line-height:1.7;color:#3d524b;\">您正在进行邮箱身份验证，请在 10 分钟内输入以下 6 位数字验证码完成注册：</p>"
                + "<div style=\"margin:18px 0;padding:16px 20px;text-align:center;font-size:30px;font-weight:700;"
                + "letter-spacing:8px;color:#173f3b;background:#ffffff;border-radius:10px;border:1px dashed #94b5aa;\">"
                + safeCode + "</div>"
                + "<p style=\"margin:16px 0 0;font-size:12px;line-height:1.6;color:#6c8079;\">若非您本人操作，请直接忽略本邮件，您的邮箱不会被绑定。<br/>Powered by Volo</p>"
                + "</div>";
    }

    private static void sendViaOhMyEmail(final String toEmail, final String blogTitle,
                                         final String subject, final String htmlBody) throws Exception {
        OhMyEmail.subject(subject)
                .from(blogTitle)
                .to(toEmail)
                .html(htmlBody)
                .send();
    }

    public static final class CooldownException extends RuntimeException {
        private final int retryAfterSeconds;

        public CooldownException(final String message, final int retryAfterSeconds) {
            super(message);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public int retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}
