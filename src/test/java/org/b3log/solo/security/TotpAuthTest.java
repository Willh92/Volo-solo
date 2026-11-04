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
package org.b3log.solo.security;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.b3log.latke.Keys;
import org.b3log.latke.http.Cookie;
import org.b3log.latke.http.Request;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.http.Response;
import org.b3log.latke.http.renderer.JsonRenderer;
import org.b3log.latke.model.Role;
import org.b3log.latke.model.User;
import org.b3log.latke.repository.Query;
import org.b3log.latke.repository.Transaction;
import org.b3log.solo.bolo.tool.MD5Utils;
import org.b3log.solo.model.Option;
import org.b3log.solo.model.UserExt;
import org.b3log.solo.processor.CaptchaProcessor;
import org.b3log.solo.processor.OAuthProcessor;
import org.b3log.solo.processor.console.TotpConsole;
import org.b3log.solo.repository.OptionRepository;
import org.b3log.solo.service.CaptchaService;
import org.b3log.solo.service.ExportService;
import org.b3log.solo.service.InitService;
import org.b3log.solo.service.OptionMgmtService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.service.TotpService;
import org.b3log.solo.service.UpgradeService;
import org.b3log.solo.service.UserQueryService;
import org.b3log.solo.util.Solos;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class TotpAuthTest {
    @BeforeClass
    public void configure() throws Exception {
        LatkeTestSupport.configure();
        final var local = new java.util.Properties();
        local.setProperty("runtimeDatabase", "NONE");
        local.setProperty("cookieHttpOnly", "true");
        local.setProperty("loginTrustedProxies", "127.0.0.1,::1");
        org.b3log.latke.Latkes.setLocalProps(local);
    }

    @AfterClass
    public void restore() throws Exception {
        LatkeTestSupport.configure();
    }

    private static final class Store {
        final Map<String, JSONObject> rows = new HashMap<>();
        final AtomicLong now = new AtomicLong(1_730_700_000_000L);
        final TotpService service;

        Store() throws Exception {
            rows.put(Option.ID_C_BLOG_TITLE, new JSONObject()
                    .put(Keys.OBJECT_ID, Option.ID_C_BLOG_TITLE)
                    .put(Option.OPTION_VALUE, "Volo Notes"));
            final OptionRepository repository = new OptionRepository() {
                @Override
                public Transaction beginTransaction() {
                    final Map<String, JSONObject> before = new HashMap<>(rows);
                    return new Transaction() {
                        boolean active = true;
                        @Override public String getId() { return "tx"; }
                        @Override public void commit() { active = false; }
                        @Override public void rollback() { rows.clear(); rows.putAll(before); active = false; }
                        @Override public boolean isActive() { return active; }
                    };
                }
                @Override public JSONObject get(final String id) { return rows.get(id); }
                @Override public String add(final JSONObject value) {
                    rows.put(value.getString(Keys.OBJECT_ID), new JSONObject(value.toString()));
                    return value.getString(Keys.OBJECT_ID);
                }
                @Override public void update(final String id, final JSONObject value, final String... fields) {
                    rows.put(id, new JSONObject(value.toString()));
                }
                @Override public void remove(final String id) { rows.remove(id); }
                @Override public JSONObject get(final Query query) {
                    return new JSONObject().put(Keys.RESULTS, new JSONArray(rows.values()));
                }
            };
            final OptionMgmtService mgmt = new OptionMgmtService();
            final var repoField = OptionMgmtService.class.getDeclaredField("optionRepository");
            repoField.setAccessible(true);
            repoField.set(mgmt, repository);
            final OptionQueryService query = new OptionQueryService() {
                @Override public JSONObject getOptionById(final String id) { return rows.get(id); }
            };
            service = new TotpService(now::get);
            final var qField = TotpService.class.getDeclaredField("optionQueryService");
            qField.setAccessible(true);
            qField.set(service, query);
            final var mField = TotpService.class.getDeclaredField("optionMgmtService");
            mField.setAccessible(true);
            mField.set(service, mgmt);
        }
    }

    @Test
    public void matchesRfc6238Sha1VectorsAndBuildsStandardOtpAuthUri() {
        // RFC 6238 Appendix B SHA-1 ASCII seed "12345678901234567890" -> Base32 GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ
        final String rfcSeed = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
        Assert.assertEquals(TotpService.generateCode(rfcSeed, 59_000L), "287082");
        Assert.assertEquals(TotpService.generateCode(rfcSeed, 1_111_111_109_000L), "081804");
        Assert.assertEquals(TotpService.generateCode(rfcSeed, 1_234_567_890_000L), "005924");

        final String uri = TotpService.buildOtpAuthUri("我的博客 Space", "admin", rfcSeed);
        Assert.assertTrue(uri.startsWith("otpauth://totp/%E6%88%91%E7%9A%84%E5%8D%9A%E5%AE%A2%20Space:admin?"));
        Assert.assertTrue(uri.contains("secret=" + rfcSeed));
        Assert.assertTrue(uri.contains("issuer=%E6%88%91%E7%9A%84%E5%8D%9A%E5%AE%A2%20Space"));
        Assert.assertTrue(uri.contains("algorithm=SHA1&digits=6&period=30"));
        Assert.assertFalse(uri.contains("+"), "Spaces must be %20 so iOS/Android authenticators render spaces cleanly");
    }

    @Test
    public void lifecycleEnforcesBindingReplayProtectionRecoveryCodesAndRebindVerification() throws Exception {
        final Store store = new Store();
        final JSONObject initial = store.service.publicStatus();
        Assert.assertFalse(initial.getBoolean("enabled"));
        Assert.assertFalse(initial.getBoolean("bound"));
        Assert.assertFalse(initial.has("secret"));
        Assert.assertEquals(initial.getString("defaultIssuer"), "Volo Notes");

        Assert.expectThrows(IllegalArgumentException.class,
                () -> store.service.updateSettings(new JSONObject().put("enabled", "on")));

        final JSONObject setup = store.service.prepareSetup("admin-1", "admin");
        final String secret = setup.getString("secret");
        Assert.assertEquals(secret.length(), 32);
        Assert.assertFalse(setup.getBoolean("requireCurrentCode"));

        Assert.expectThrows(IllegalArgumentException.class,
                () -> store.service.confirmSetup("admin-1", setup.getString("setupId"), "000000", ""));

        final String firstCode = TotpService.generateCode(secret, store.now.get());
        final JSONObject confirmed = store.service.confirmSetup("admin-1", setup.getString("setupId"), firstCode, "");
        final JSONArray recoveryCodes = confirmed.getJSONArray("recoveryCodes");
        Assert.assertEquals(recoveryCodes.length(), 8);
        Assert.assertTrue(confirmed.getJSONObject("totp").getBoolean("enabled"));
        Assert.assertTrue(confirmed.getJSONObject("totp").getBoolean("bound"));
        Assert.assertFalse(confirmed.getJSONObject("totp").has("secret"));

        // Replay of the exact same 30s time-step code must be rejected.
        final JSONObject adminUser = new JSONObject()
                .put(Keys.OBJECT_ID, "admin-1")
                .put(User.USER_NAME, "admin")
                .put(User.USER_ROLE, Role.ADMIN_ROLE)
                .put(UserExt.USER_B3_KEY, "hash-1");
        final String challenge1 = store.service.issueLoginChallenge(adminUser, "198.51.100.10");
        Assert.assertEquals(
                store.service.verifyLoginChallenge(challenge1, "198.51.100.10", firstCode).status(),
                TotpService.ChallengeStatus.INVALID_CODE,
                "Already accepted time-step code must not be reusable"
        );

        // Next 30s window succeeds.
        store.now.addAndGet(30_000L);
        final String secondCode = TotpService.generateCode(secret, store.now.get());
        Assert.assertEquals(
                store.service.verifyLoginChallenge(challenge1, "198.51.100.10", secondCode).status(),
                TotpService.ChallengeStatus.VERIFIED
        );

        // Single-use recovery code works once and cannot be reused.
        final String recovery0 = recoveryCodes.getString(0);
        final String challenge2 = store.service.issueLoginChallenge(adminUser, "198.51.100.10");
        Assert.assertEquals(
                store.service.verifyLoginChallenge(challenge2, "198.51.100.10", recovery0.toLowerCase()).status(),
                TotpService.ChallengeStatus.VERIFIED
        );
        Assert.assertEquals(store.service.publicStatus().getInt("recoveryRemaining"), 7);
        final String challenge3 = store.service.issueLoginChallenge(adminUser, "198.51.100.10");
        Assert.assertEquals(
                store.service.verifyLoginChallenge(challenge3, "198.51.100.10", recovery0).status(),
                TotpService.ChallengeStatus.INVALID_CODE
        );

        // Replacing an active secret requires proving possession of the current authenticator or recovery code.
        store.now.addAndGet(30_000L);
        final JSONObject rebind = store.service.prepareSetup("admin-1", "admin");
        Assert.assertTrue(rebind.getBoolean("requireCurrentCode"));
        final String newSecretCode = TotpService.generateCode(rebind.getString("secret"), store.now.get());
        Assert.expectThrows(IllegalArgumentException.class,
                () -> store.service.confirmSetup("admin-1", rebind.getString("setupId"), newSecretCode, ""));
        final String validCurrent = TotpService.generateCode(secret, store.now.get());
        final JSONObject rebound = store.service.confirmSetup("admin-1", rebind.getString("setupId"), newSecretCode, validCurrent);
        Assert.assertEquals(rebound.getJSONObject("totp").getInt("recoveryRemaining"), 8);

        // Disabling and unbinding require verification when enabled.
        Assert.expectThrows(IllegalArgumentException.class,
                () -> store.service.updateSettings(new JSONObject().put("enabled", "off")));
        store.now.addAndGet(30_000L);
        final String disableCode = TotpService.generateCode(rebind.getString("secret"), store.now.get());
        final JSONObject paused = store.service.updateSettings(new JSONObject()
                .put("enabled", "off")
                .put("scope", "all")
                .put("issuer", "My Blog")
                .put("windowSteps", 2)
                .put("rememberDays", 14)
                .put("verifyCode", disableCode));
        Assert.assertFalse(paused.getBoolean("enabled"));
        Assert.assertTrue(paused.getBoolean("bound"));
        Assert.assertEquals(paused.getString("scope"), "all");
        Assert.assertEquals(paused.getString("issuer"), "My Blog");
        Assert.assertEquals(paused.getInt("windowSteps"), 2);
        Assert.assertEquals(paused.getInt("rememberDays"), 14);

        // Re-enabling also requires fresh TOTP verification to prevent accidental lockout.
        Assert.expectThrows(IllegalArgumentException.class,
                () -> store.service.updateSettings(new JSONObject().put("enabled", "on")));
        store.now.addAndGet(30_000L);
        final String reenableCode = TotpService.generateCode(rebind.getString("secret"), store.now.get());
        Assert.assertTrue(store.service.updateSettings(new JSONObject()
                .put("enabled", "on").put("verifyCode", reenableCode)).getBoolean("enabled"));

        store.now.addAndGet(30_000L);
        final String unbindCode = TotpService.generateCode(rebind.getString("secret"), store.now.get());
        final JSONObject unbound = store.service.unbind(unbindCode, null);
        Assert.assertFalse(unbound.getBoolean("enabled"));
        Assert.assertFalse(unbound.getBoolean("bound"));
    }

    @Test
    public void loginProcessorEnforcesTwoFactorCaptchaToggleScopeIpBindingAndTrustedDeviceCookies() throws Exception {
        final Store store = new Store();
        final OAuthProcessor processor = buildLoginProcessor(store.service);
        final CaptchaProcessor captchaProcessor = buildCaptchaProcessor(store.service);

        // 0. Before TOTP is enabled, login image captcha MUST remain required.
        Assert.assertTrue(fetchLoginCaptcha(captchaProcessor).getBoolean("required"));
        final JSONObject missingCaptcha = postLoginInternal(processor, "198.51.100.30",
                Map.of("username", "totp-admin", "password", "Password42"), null, null, false, 400);
        Assert.assertFalse(missingCaptcha.getBoolean("sc"));
        final JSONObject withCaptchaOk = postLogin(processor, "198.51.100.30",
                Map.of("username", "totp-admin", "password", "Password42"), null, 200);
        Assert.assertTrue(withCaptchaOk.getBoolean("sc"));

        final JSONObject setup = store.service.prepareSetup("u-admin", "totp-admin");
        final String secret = setup.getString("secret");
        store.service.confirmSetup("u-admin", setup.getString("setupId"),
                TotpService.generateCode(secret, store.now.get()), "");
        store.service.updateSettings(new JSONObject()
                .put("enabled", "on")
                .put("scope", "admin")
                .put("rememberDays", 7));
        store.now.addAndGet(30_000L);

        // Once TOTP is enabled, login image captcha MUST be turned off.
        final JSONObject captchaWhenTotpOn = fetchLoginCaptcha(captchaProcessor);
        Assert.assertFalse(captchaWhenTotpOn.getBoolean("required"));
        Assert.assertFalse(captchaWhenTotpOn.has("image"));

        // 1. Wrong password (without captcha) must NOT reveal whether TOTP is enabled.
        final JSONObject wrongPass = postLoginWithoutCaptcha(processor, "198.51.100.31", Map.of(
                "username", "totp-admin", "password", "WrongPass99"
        ), null, 401);
        Assert.assertFalse(wrongPass.getBoolean("sc"));
        Assert.assertFalse(wrongPass.has("totpRequired"));
        Assert.assertFalse(wrongPass.has("totpToken"));

        // 2. Non-admin author bypasses TOTP when scope == "admin" (also without image captcha while TOTP is active).
        final JSONObject authorOk = postLoginWithoutCaptcha(processor, "198.51.100.32", Map.of(
                "username", "totp-author", "password", "Password42"
        ), null, 200);
        Assert.assertTrue(authorOk.getBoolean("sc"));

        // 3. Admin password succeeds without captcha -> receives totpRequired challenge without session cookie yet.
        final List<Cookie> step1Cookies = new ArrayList<>();
        final JSONObject step1 = postLoginWithoutCaptcha(processor, "198.51.100.33", Map.of(
                "username", "totp-admin", "password", "Password42"
        ), step1Cookies, 200);
        Assert.assertFalse(step1.getBoolean("sc"));
        Assert.assertTrue(step1.getBoolean("totpRequired"));
        Assert.assertEquals(step1.getInt("rememberDays"), 7);
        Assert.assertTrue(step1Cookies.isEmpty(), "Session cookie must not be issued before TOTP passes");
        final String token = step1.getString("totpToken");

        // 4. Token cannot be used from a different client IP.
        final JSONObject wrongIp = postTotpStep(processor, "198.51.100.99", Map.of(
                "totpToken", token, "totpCode", TotpService.generateCode(secret, store.now.get())
        ), null, 401);
        Assert.assertTrue(wrongIp.getBoolean("totpExpired"));

        // 5. Wrong TOTP code keeps challenge alive and returns 401 without cookie.
        final JSONObject wrongOtp = postTotpStep(processor, "198.51.100.33", Map.of(
                "totpToken", token, "totpCode", "000000"
        ), null, 401);
        Assert.assertFalse(wrongOtp.getBoolean("sc"));
        Assert.assertTrue(wrongOtp.getBoolean("totpRequired"));

        // 6. Correct TOTP code with rememberDevice=on logs in and sets both session + trusted-device cookies.
        final List<Cookie> step2Cookies = new ArrayList<>();
        final JSONObject step2 = postTotpStep(processor, "198.51.100.33", Map.of(
                "totpToken", token,
                "totpCode", TotpService.generateCode(secret, store.now.get()),
                "rememberDevice", "on"
        ), step2Cookies, 200);
        Assert.assertTrue(step2.getBoolean("sc"));
        Assert.assertEquals(step2.getString("redirect"), "/admin-index.do#main");
        final Cookie trustCookie = step2Cookies.stream()
                .filter(c -> (Solos.COOKIE_NAME + "_totp").equals(c.getName()))
                .findFirst().orElseThrow();

        // 7. Subsequent login carrying the valid trust cookie skips Step 2 (and still requires no image captcha).
        store.now.addAndGet(60_000L);
        final JSONObject remembered = postLoginWithCookie(processor, "198.51.100.34",
                "totp-admin", "Password42", trustCookie, 200);
        Assert.assertTrue(remembered.getBoolean("sc"), "Valid trusted-device cookie should skip TOTP step");

        store.service.revokeTrustedDevices(null);
        final JSONObject afterRevoke = postLoginWithCookie(processor, "198.51.100.34",
                "totp-admin", "Password42", trustCookie, 200);
        Assert.assertFalse(afterRevoke.getBoolean("sc"));
        Assert.assertTrue(afterRevoke.getBoolean("totpRequired"), "Revoked device cookie must require TOTP again");

        // 8. Disabling TOTP immediately restores mandatory login image captcha.
        store.now.addAndGet(30_000L);
        store.service.updateSettings(new JSONObject()
                .put("enabled", "off")
                .put("verifyCode", TotpService.generateCode(secret, store.now.get())));
        Assert.assertTrue(fetchLoginCaptcha(captchaProcessor).getBoolean("required"));
        final JSONObject missingCaptchaAfterDisable = postLoginWithoutCaptcha(processor, "198.51.100.35",
                Map.of("username", "totp-admin", "password", "Password42"), null, 400);
        Assert.assertFalse(missingCaptchaAfterDisable.getBoolean("sc"));
    }

    @Test
    public void consoleMutationsRequireAdminAndSameOriginHeadersAndExportRedactsSecrets() throws Exception {
        final Store store = new Store();
        final JSONObject setup = store.service.prepareSetup("u-admin", "totp-admin");
        store.service.confirmSetup("u-admin", setup.getString("setupId"),
                TotpService.generateCode(setup.getString("secret"), store.now.get()), "");

        // Verify ExportService.getJSONs() never leaks voloTotpAuth
        final ExportService exportService = new ExportService();
        for (final var field : ExportService.class.getDeclaredFields()) {
            if (field.getType().getSimpleName().endsWith("Repository")) {
                field.setAccessible(true);
                if (field.getType() == OptionRepository.class) {
                    field.set(exportService, new OptionRepository() {
                        @Override public JSONObject get(final Query query) {
                            return new JSONObject().put(Keys.RESULTS, new JSONArray(store.rows.values()));
                        }
                    });
                } else {
                    final Object repoInstance = field.getType().getDeclaredConstructor().newInstance();
                    final var baseRepo = org.b3log.latke.repository.AbstractRepository.class.getDeclaredField("repository");
                    baseRepo.setAccessible(true);
                    baseRepo.set(repoInstance, java.lang.reflect.Proxy.newProxyInstance(
                            org.b3log.latke.repository.Repository.class.getClassLoader(),
                            new Class<?>[]{org.b3log.latke.repository.Repository.class},
                            (proxy, method, args) -> "get".equals(method.getName())
                                    ? new JSONObject().put(Keys.RESULTS, new JSONArray()) : null
                    ));
                    field.set(exportService, repoInstance);
                }
            }
        }
        final JSONArray exportedOptions = exportService.getJSONs().getJSONArray(Option.OPTIONS);
        Assert.assertEquals(exportedOptions.length(), 1);
        Assert.assertEquals(exportedOptions.getJSONObject(0).getString(Keys.OBJECT_ID), Option.ID_C_BLOG_TITLE);

        // Verify TotpConsole unauthenticated access is blocked on GET and POST
        final TotpConsole console = new TotpConsole() {
            @Override protected boolean adminLoggedIn(final RequestContext context) { return false; }
        };
        final var svcField = TotpConsole.class.getDeclaredField("totpService");
        svcField.setAccessible(true);
        svcField.set(console, store.service);

        final DefaultFullHttpRequest getHttp = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/console/security/totp");
        final DefaultFullHttpResponse getRaw = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        try {
            console.status(new RequestContext(new Request(null, getHttp), new Response(null, getRaw)));
            Assert.assertEquals(getRaw.status().code(), 403);
        } finally {
            getHttp.release();
            getRaw.release();
        }

        final byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        final DefaultFullHttpRequest postHttp = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/console/security/totp/setup", Unpooled.wrappedBuffer(body));
        postHttp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        final DefaultFullHttpResponse postRaw = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        try {
            console.prepareSetup(new RequestContext(new Request(null, postHttp), new Response(null, postRaw)));
            Assert.assertEquals(postRaw.status().code(), 401);
        } finally {
            postHttp.release();
            postRaw.release();
        }
    }

    @Test
    public void hardensSetupFailureCapConsoleVerificationThrottleAndPasswordChangeCookieInvalidation() throws Exception {
        final Store store = new Store();

        // 1. Pending setup session is evicted after 5 failed confirmation attempts.
        final JSONObject setup = store.service.prepareSetup("u-admin", "totp-admin");
        final String setupId = setup.getString("setupId");
        final String validCode = TotpService.generateCode(setup.getString("secret"), store.now.get());
        for (int i = 0; i < 5; i++) {
            Assert.expectThrows(IllegalArgumentException.class,
                    () -> store.service.confirmSetup("u-admin", setupId, "000000", ""));
        }
        Assert.expectThrows(IllegalArgumentException.class,
                () -> store.service.confirmSetup("u-admin", setupId, validCode, ""));

        // Bind a fresh authenticator and trust a browser.
        final JSONObject setup2 = store.service.prepareSetup("u-admin", "totp-admin");
        final String secret = setup2.getString("secret");
        store.service.confirmSetup("u-admin", setup2.getString("setupId"),
                TotpService.generateCode(secret, store.now.get()), "");
        store.service.updateSettings(new JSONObject().put("enabled", "on").put("rememberDays", 14));

        final JSONObject userBefore = new JSONObject()
                .put(Keys.OBJECT_ID, "u-admin")
                .put(User.USER_NAME, "totp-admin")
                .put(User.USER_ROLE, Role.ADMIN_ROLE)
                .put(UserExt.USER_B3_KEY, MD5Utils.stringToMD5Twice("Password42"));
        final DefaultFullHttpResponse cookieRespRaw = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        final DefaultFullHttpRequest checkHttp = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        try {
            final Response cookieResp = new Response(null, cookieRespRaw);
            store.service.writeTrustedDeviceCookie(userBefore, cookieResp);
            final Cookie trustCookie = cookieResp.getCookies().stream()
                    .filter(c -> (Solos.COOKIE_NAME + "_totp").equals(c.getName()))
                    .findFirst().orElseThrow();
            final Request trustedReq = new Request(null, checkHttp);
            trustedReq.addCookie(trustCookie);
            Assert.assertFalse(store.service.requiresVerification(userBefore, trustedReq));

            // 2. Changing the account password (USER_B3_KEY) immediately invalidates existing trusted-browser cookies.
            final JSONObject userAfterPwdChange = new JSONObject(userBefore.toString())
                    .put(UserExt.USER_B3_KEY, MD5Utils.stringToMD5Twice("NewPass99"));
            Assert.assertTrue(store.service.requiresVerification(userAfterPwdChange, trustedReq),
                    "Password change must invalidate trusted-browser cookies");
        } finally {
            cookieRespRaw.release();
            checkHttp.release();
        }

        // 3. Console 2FA verification endpoints throttle repeated wrong codes (HTTP 429 after 5 failed attempts).
        final AtomicLong nanoClock = new AtomicLong(1_000_000_000L);
        final TotpConsole authedConsole = new TotpConsole(new org.b3log.solo.util.LoginThrottle(nanoClock::get)) {
            @Override protected boolean adminLoggedIn(final RequestContext context) { return true; }
            @Override protected boolean allowWrite(final RequestContext context) { return true; }
            @Override protected JSONObject currentAdmin(final RequestContext context) { return userBefore; }
            @Override protected String clientAddress(final RequestContext context) { return "198.51.100.77"; }
        };
        final var svcField = TotpConsole.class.getDeclaredField("totpService");
        svcField.setAccessible(true);
        svcField.set(authedConsole, store.service);

        for (int i = 0; i < 5; i++) {
            final byte[] wrongBody = "{\"verifyCode\":\"000000\"}".getBytes(StandardCharsets.UTF_8);
            final DefaultFullHttpRequest req = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.POST, "/console/security/totp/unbind", Unpooled.wrappedBuffer(wrongBody));
            req.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            final DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            try {
                authedConsole.unbind(new RequestContext(new Request(null, req), new Response(null, resp)));
                Assert.assertEquals(resp.status().code(), 400);
            } finally {
                req.release();
                resp.release();
            }
        }

        final byte[] throttledBody = "{\"verifyCode\":\"000000\"}".getBytes(StandardCharsets.UTF_8);
        final DefaultFullHttpRequest throttledReq = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/console/security/totp/unbind", Unpooled.wrappedBuffer(throttledBody));
        throttledReq.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        final DefaultFullHttpResponse throttledResp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        try {
            authedConsole.unbind(new RequestContext(new Request(null, throttledReq), new Response(null, throttledResp)));
            Assert.assertEquals(throttledResp.status().code(), 429);
        } finally {
            throttledReq.release();
            throttledResp.release();
        }

        // 4. Cross-site login POSTs must be rejected with 403 (preventing Login CSRF when 2FA disables login CAPTCHA).
        final DefaultFullHttpRequest crossSiteReq = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/oauth/bolo/login");
        crossSiteReq.headers().set("Accept", "application/json");
        crossSiteReq.headers().set("Origin", "https://evil.example");
        crossSiteReq.headers().set("Sec-Fetch-Site", "cross-site");
        final DefaultFullHttpResponse crossSiteResp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        try {
            final Request req = new Request(null, crossSiteReq) {
                @Override public String getRemoteAddr() { return "198.51.100.77"; }
            };
            req.setParameter("username", "totp-admin");
            req.setParameter("password", "Password42");
            buildLoginProcessor(store.service).adminLogin(new RequestContext(req, new Response(null, crossSiteResp)));
            Assert.assertEquals(crossSiteResp.status().code(), 403);
        } finally {
            crossSiteReq.release();
            crossSiteResp.release();
        }
    }

    private static OAuthProcessor buildLoginProcessor(final TotpService totpService) throws Exception {
        final OAuthProcessor processor = new OAuthProcessor();
        final var init = OAuthProcessor.class.getDeclaredField("initService");
        init.setAccessible(true);
        init.set(processor, new InitService() { @Override public boolean isInited() { return true; } });
        final var users = OAuthProcessor.class.getDeclaredField("userQueryService");
        users.setAccessible(true);
        users.set(processor, new UserQueryService() {
            @Override
            public JSONObject getUserByName(final String name) {
                if ("totp-admin".equals(name)) {
                    return new JSONObject().put(Keys.OBJECT_ID, "u-admin")
                            .put(User.USER_NAME, name).put(User.USER_ROLE, Role.ADMIN_ROLE)
                            .put(UserExt.USER_B3_KEY, MD5Utils.stringToMD5Twice("Password42"));
                }
                if ("totp-author".equals(name)) {
                    return new JSONObject().put(Keys.OBJECT_ID, "u-author")
                            .put(User.USER_NAME, name).put(User.USER_ROLE, Role.DEFAULT_ROLE)
                            .put(UserExt.USER_B3_KEY, MD5Utils.stringToMD5Twice("Password42"));
                }
                return null;
            }
        });
        final var captchaField = OAuthProcessor.class.getDeclaredField("captchaService");
        captchaField.setAccessible(true);
        captchaField.set(processor, new CaptchaService(() -> "AB234", System::currentTimeMillis));
        final var totpField = OAuthProcessor.class.getDeclaredField("totpService");
        totpField.setAccessible(true);
        totpField.set(processor, totpService);
        return processor;
    }

    private static CaptchaProcessor buildCaptchaProcessor(final TotpService totpService) throws Exception {
        final CaptchaProcessor processor = new CaptchaProcessor();
        final var captchaField = CaptchaProcessor.class.getDeclaredField("captchaService");
        captchaField.setAccessible(true);
        captchaField.set(processor, new CaptchaService(() -> "AB234", System::currentTimeMillis));
        final var initField = CaptchaProcessor.class.getDeclaredField("initService");
        initField.setAccessible(true);
        initField.set(processor, new InitService() { @Override public boolean isInited() { return true; } });
        final var totpField = CaptchaProcessor.class.getDeclaredField("totpService");
        totpField.setAccessible(true);
        totpField.set(processor, totpService);
        return processor;
    }

    private static JSONObject fetchLoginCaptcha(final CaptchaProcessor processor) {
        final DefaultFullHttpRequest http = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/captcha?purpose=login");
        final DefaultFullHttpResponse raw = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        try {
            final Request request = new Request(null, http) {
                @Override public String getRemoteAddr() { return "198.51.100.40"; }
            };
            request.setParameter("purpose", "login");
            final RequestContext context = new RequestContext(request, new Response(null, raw));
            processor.challenge(context);
            Assert.assertEquals(raw.status().code(), 200);
            return ((JsonRenderer) context.getRenderer()).getJSONObject();
        } finally {
            http.release();
            raw.release();
        }
    }

    private static JSONObject postLogin(final OAuthProcessor processor, final String remoteIp,
                                        final Map<String, String> params, final List<Cookie> outCookies,
                                        final int expectedStatus) throws Exception {
        return postLoginInternal(processor, remoteIp, params, null, outCookies, true, expectedStatus);
    }

    private static JSONObject postLoginWithoutCaptcha(final OAuthProcessor processor, final String remoteIp,
                                                      final Map<String, String> params, final List<Cookie> outCookies,
                                                      final int expectedStatus) throws Exception {
        return postLoginInternal(processor, remoteIp, params, null, outCookies, false, expectedStatus);
    }

    private static JSONObject postLoginWithCookie(final OAuthProcessor processor, final String remoteIp,
                                                  final String username, final String password,
                                                  final Cookie cookie, final int expectedStatus) throws Exception {
        return postLoginInternal(processor, remoteIp,
                Map.of("username", username, "password", password), cookie, null, false, expectedStatus);
    }

    private static JSONObject postTotpStep(final OAuthProcessor processor, final String remoteIp,
                                           final Map<String, String> params, final List<Cookie> outCookies,
                                           final int expectedStatus) throws Exception {
        return postLoginInternal(processor, remoteIp, params, null, outCookies, false, expectedStatus);
    }

    private static JSONObject postLoginInternal(final OAuthProcessor processor, final String remoteIp,
                                                final Map<String, String> params, final Cookie cookie,
                                                final List<Cookie> outCookies, final boolean withCaptcha,
                                                final int expectedStatus) throws Exception {
        final boolean migration = UpgradeService.boloFastMigration;
        final DefaultFullHttpRequest http = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/oauth/bolo/login");
        final DefaultFullHttpResponse rawResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        try {
            UpgradeService.boloFastMigration = false;
            http.headers().set("Accept", "application/json");
            http.headers().set("X-Requested-With", "XMLHttpRequest");
            final Request request = new Request(null, http) {
                @Override public String getRemoteAddr() { return remoteIp; }
            };
            if (cookie != null) {
                request.addCookie(cookie);
            }
            for (final var entry : params.entrySet()) {
                request.setParameter(entry.getKey(), entry.getValue());
            }
            if (withCaptcha) {
                CaptchaTestSupport.prepare(processor, request);
            }
            final Response response = new Response(null, rawResponse);
            final RequestContext context = new RequestContext(request, response);
            processor.adminLogin(context);
            Assert.assertEquals(rawResponse.status().code(), expectedStatus);
            if (outCookies != null) {
                outCookies.addAll(response.getCookies());
            }
            return ((JsonRenderer) context.getRenderer()).getJSONObject();
        } finally {
            UpgradeService.boloFastMigration = migration;
            http.release();
            rawResponse.release();
        }
    }
}
