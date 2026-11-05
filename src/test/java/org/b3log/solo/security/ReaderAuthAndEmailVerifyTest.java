/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.security;

import org.b3log.latke.model.Role;
import org.b3log.latke.model.User;
import org.b3log.solo.bolo.tool.MD5Utils;
import org.b3log.solo.model.UserExt;
import org.b3log.solo.service.EmailVerificationService;
import org.b3log.solo.util.PasswordHashes;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReaderAuthAndEmailVerifyTest {

    @BeforeClass
    public void setup() throws Exception {
        LatkeTestSupport.configure();
    }

    @Test
    public void pbkdf2HashesUseRandomSaltVerifyCorrectlyAndPreserveLegacyFallback() {
        final String h1 = PasswordHashes.hash("VoloReader#2026");
        final String h2 = PasswordHashes.hash("VoloReader#2026");
        Assert.assertNotEquals(h1, h2, "Each PBKDF2 hash must generate a fresh random salt");
        Assert.assertTrue(h1.startsWith("pbkdf2$sha256$120000$"));
        Assert.assertTrue(PasswordHashes.verifyHash("VoloReader#2026", h1));
        Assert.assertFalse(PasswordHashes.verifyHash("wrong-password", h1));
        Assert.assertFalse(PasswordHashes.verifyHash("VoloReader#2026", h1.substring(0, h1.length() - 2) + "AA"));

        final JSONObject modernUser = new JSONObject()
                .put(UserExt.USER_PASSWORD_HASH, h1)
                .put(UserExt.USER_B3_KEY, PasswordHashes.randomSessionKey());
        Assert.assertTrue(PasswordHashes.verify("VoloReader#2026", modernUser));
        Assert.assertFalse(PasswordHashes.verify("legacy", modernUser));

        final JSONObject legacyUser = new JSONObject()
                .put(UserExt.USER_PASSWORD_HASH, "")
                .put(UserExt.USER_B3_KEY, MD5Utils.stringToMD5Twice("legacy-pass"));
        Assert.assertTrue(PasswordHashes.verify("legacy-pass", legacyUser));
        Assert.assertFalse(PasswordHashes.verify("other", legacyUser));
    }

    @Test
    public void emailVerificationEnforcesCooldownMaxAttemptsAndTenMinuteExpiry() {
        final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        final AtomicReference<String> lastHtml = new AtomicReference<>("");
        final EmailVerificationService svc = new EmailVerificationService(clock::get, (to, blog, subject, html) -> lastHtml.set(html));

        final int cooldown = svc.sendRegistrationCode("Reader@Example.com", "198.51.100.10");
        Assert.assertEquals(cooldown, 60);
        Assert.assertTrue(lastHtml.get().contains("Powered by Volo"), "Verification email must carry Volo branding");
        Assert.assertFalse(lastHtml.get().toLowerCase().contains("bolo"));

        final Matcher m = Pattern.compile(">(\\d{6})<").matcher(lastHtml.get());
        Assert.assertTrue(m.find(), "Email HTML must contain a 6-digit verification code");
        final String code = m.group(1);

        // Cooldown blocks immediate re-send
        clock.addAndGet(15_000L);
        Assert.expectThrows(EmailVerificationService.CooldownException.class,
                () -> svc.sendRegistrationCode("reader@example.com", "198.51.100.10"));

        // 5 wrong attempts invalidate the active code
        for (int i = 0; i < EmailVerificationService.MAX_VERIFY_ATTEMPTS; i++) {
            Assert.assertFalse(svc.verifyAndConsume("reader@example.com", "000000".equals(code) ? "999999" : "000000"));
        }
        Assert.assertFalse(svc.verifyAndConsume("reader@example.com", code),
                "Challenge must be invalidated after 5 failed guesses");

        // Advance past cooldown, send new code, verify expiry after 10 minutes
        clock.addAndGet(60_000L);
        svc.sendRegistrationCode("reader@example.com", "198.51.100.10");
        final Matcher m2 = Pattern.compile(">(\\d{6})<").matcher(lastHtml.get());
        Assert.assertTrue(m2.find());
        final String code2 = m2.group(1);

        clock.addAndGet(EmailVerificationService.CODE_TTL_MS + 1000L);
        Assert.assertFalse(svc.verifyAndConsume("reader@example.com", code2), "Expired code must be rejected");

        // Fresh code succeeds once and is consumed
        svc.sendRegistrationCode("reader@example.com", "198.51.100.10");
        final Matcher m3 = Pattern.compile(">(\\d{6})<").matcher(lastHtml.get());
        Assert.assertTrue(m3.find());
        final String code3 = m3.group(1);
        Assert.assertTrue(svc.verifyAndConsume("  READER@EXAMPLE.COM ", code3));
        Assert.assertFalse(svc.verifyAndConsume("reader@example.com", code3), "Consumed code cannot be replayed");
    }

    @Test
    public void visitorRoleIsStrictlyDistinguishedFromAdminAndAuthorRoles() {
        final JSONObject visitor = new JSONObject()
                .put(User.USER_NAME, "reader1")
                .put(User.USER_ROLE, Role.VISITOR_ROLE);
        Assert.assertEquals(visitor.getString(User.USER_ROLE), Role.VISITOR_ROLE);
        Assert.assertNotEquals(visitor.getString(User.USER_ROLE), Role.ADMIN_ROLE);
        Assert.assertNotEquals(visitor.getString(User.USER_ROLE), Role.DEFAULT_ROLE);
    }
}
