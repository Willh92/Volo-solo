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

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.LongSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.codec.binary.Base32;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.http.Cookie;
import org.b3log.latke.http.Request;
import org.b3log.latke.http.Response;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.model.Role;
import org.b3log.latke.model.User;
import org.b3log.latke.service.annotation.Service;
import org.b3log.solo.model.Option;
import org.b3log.solo.model.UserExt;
import org.b3log.solo.util.IpAddresses;
import org.b3log.solo.util.OptionDocuments;
import org.b3log.solo.util.Solos;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * RFC 6238 TOTP two-factor authentication (2FA) configuration, secret binding, recovery codes,
 * replay protection, trusted-browser cookies, and short-lived login challenges.
 */
@Service
public class TotpService {
    public static final String OPTION_ID = "voloTotpAuth";
    private static final String OPTION_CATEGORY = "security";
    private static final String RECOVERY_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int SECRET_BYTES = 20;
    private static final int PERIOD_SECONDS = 30;
    private static final int RECOVERY_COUNT = 8;
    private static final long SETUP_TTL_MS = 600_000L;
    private static final long CHALLENGE_TTL_MS = 300_000L;
    private static final int MAX_CHALLENGE_FAILURES = 5;
    private static final Set<Integer> ALLOWED_WINDOWS = Set.of(0, 1, 2);
    private static final Set<Integer> ALLOWED_REMEMBER_DAYS = Set.of(0, 7, 14, 30);
    private static final Set<String> ALLOWED_SCOPES = Set.of("admin", "all");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private record State(
            boolean enabled,
            String scope,
            String issuer,
            int windowSteps,
            int rememberDays,
            String secret,
            String boundUser,
            long boundAt,
            long lastAcceptedStep,
            long deviceEpoch,
            String deviceKey,
            String recoverySalt,
            List<String> recoveryDigests
    ) {
        boolean bound() {
            return secret != null && !secret.isEmpty();
        }
    }

    private static final class PendingSetup {
        private final String userId;
        private final String accountName;
        private final String secret;
        private final String issuer;
        private final long expiresAt;
        private int failures;

        private PendingSetup(final String userId, final String accountName, final String secret,
                             final String issuer, final long expiresAt) {
            this.userId = userId;
            this.accountName = accountName;
            this.secret = secret;
            this.issuer = issuer;
            this.expiresAt = expiresAt;
        }

        String userId() { return userId; }
        String accountName() { return accountName; }
        String secret() { return secret; }
        String issuer() { return issuer; }
        long expiresAt() { return expiresAt; }
    }

    private static final class LoginChallenge {
        private final String userId;
        private final String username;
        private final String passwordHash;
        private final String address;
        private final long expiresAt;
        private int failures;

        private LoginChallenge(final String userId, final String username, final String passwordHash,
                               final String address, final long expiresAt) {
            this.userId = userId;
            this.username = username;
            this.passwordHash = passwordHash;
            this.address = address;
            this.expiresAt = expiresAt;
        }
    }

    public enum ChallengeStatus {
        VERIFIED,
        INVALID_CODE,
        EXPIRED
    }

    public record ChallengeResult(ChallengeStatus status, String userId, String username, String passwordHash, String message) {
        static ChallengeResult verified(final String userId, final String username, final String passwordHash) {
            return new ChallengeResult(ChallengeStatus.VERIFIED, userId, username, passwordHash, "");
        }

        static ChallengeResult invalid(final String message) {
            return new ChallengeResult(ChallengeStatus.INVALID_CODE, null, null, null, message);
        }

        static ChallengeResult expired(final String message) {
            return new ChallengeResult(ChallengeStatus.EXPIRED, null, null, null, message);
        }
    }

    private final LongSupplier clock;
    private final LinkedHashMap<String, PendingSetup> pendingSetups = new LinkedHashMap<>();
    private final LinkedHashMap<String, LoginChallenge> challenges = new LinkedHashMap<>();
    private volatile State snapshot;

    @Inject
    private OptionQueryService optionQueryService;

    @Inject
    private OptionMgmtService optionMgmtService;

    public TotpService() {
        this(System::currentTimeMillis);
    }

    public TotpService(final LongSupplier clock) {
        this.clock = clock;
    }

    private synchronized State state() {
        if (snapshot == null) {
            final JSONObject raw = new JSONObject(OptionDocuments.read(optionQueryService, OPTION_ID, "{}"));
            final String scope = ALLOWED_SCOPES.contains(raw.optString("scope")) ? raw.optString("scope") : "admin";
            final int windowSteps = ALLOWED_WINDOWS.contains(raw.optInt("windowSteps", 1)) ? raw.optInt("windowSteps", 1) : 1;
            final int rememberDays = ALLOWED_REMEMBER_DAYS.contains(raw.optInt("rememberDays", 0)) ? raw.optInt("rememberDays", 0) : 0;
            final String secret = normalizeBase32Secret(raw.optString("secret"));
            final boolean enabled = !secret.isEmpty() && raw.optBoolean("enabled", false);
            final List<String> digests = new ArrayList<>();
            final JSONArray storedDigests = raw.optJSONArray("recoveryDigests");
            if (storedDigests != null) {
                for (int i = 0; i < storedDigests.length(); i++) {
                    final String item = storedDigests.optString(i);
                    if (item.matches("[0-9a-f]{64}")) {
                        digests.add(item);
                    }
                }
            }
            String deviceKey = raw.optString("deviceKey");
            if (!deviceKey.matches("[0-9a-f]{64}")) {
                deviceKey = randomHex(32);
            }
            String recoverySalt = raw.optString("recoverySalt");
            if (!recoverySalt.matches("[0-9a-f]{32}")) {
                recoverySalt = randomHex(16);
            }
            snapshot = new State(
                    enabled,
                    scope,
                    sanitizeIssuerOrEmpty(raw.optString("issuer")),
                    windowSteps,
                    rememberDays,
                    secret,
                    raw.optString("boundUser"),
                    raw.optLong("boundAt", 0L),
                    raw.optLong("lastAcceptedStep", -1L),
                    Math.max(1L, raw.optLong("deviceEpoch", 1L)),
                    deviceKey,
                    recoverySalt,
                    List.copyOf(digests)
            );
        }
        return snapshot;
    }

    private synchronized void persist(final State next) throws Exception {
        final JSONObject data = new JSONObject()
                .put("version", 1)
                .put("enabled", next.enabled())
                .put("scope", next.scope())
                .put("issuer", next.issuer())
                .put("windowSteps", next.windowSteps())
                .put("rememberDays", next.rememberDays())
                .put("secret", next.secret())
                .put("boundUser", next.boundUser())
                .put("boundAt", next.boundAt())
                .put("lastAcceptedStep", next.lastAcceptedStep())
                .put("deviceEpoch", next.deviceEpoch())
                .put("deviceKey", next.deviceKey())
                .put("recoverySalt", next.recoverySalt())
                .put("recoveryDigests", new JSONArray(next.recoveryDigests()));
        optionMgmtService.storeDocument(OPTION_ID, OPTION_CATEGORY, data.toString());
        snapshot = next;
    }

    public synchronized JSONObject publicStatus() {
        final State current = state();
        return toStatusJson(current);
    }

    private JSONObject toStatusJson(final State current) {
        return new JSONObject()
                .put("enabled", current.enabled())
                .put("bound", current.bound())
                .put("boundUser", current.boundUser())
                .put("boundAt", current.boundAt())
                .put("scope", current.scope())
                .put("issuer", current.issuer())
                .put("defaultIssuer", defaultIssuer())
                .put("windowSteps", current.windowSteps())
                .put("rememberDays", current.rememberDays())
                .put("loginCaptchaRequired", !(current.enabled() && current.bound()))
                .put("recoveryRemaining", current.recoveryDigests().size());
    }

    private String defaultIssuer() {
        if (optionQueryService != null) {
            final JSONObject titleOpt = optionQueryService.getOptionById(Option.ID_C_BLOG_TITLE);
            if (titleOpt != null) {
                final String raw = titleOpt.optString(Option.OPTION_VALUE).replace(':', '-').strip();
                final String cleaned = raw.replaceAll("\\p{Cntrl}", "");
                if (!cleaned.isEmpty()) {
                    return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
                }
            }
        }
        return "Volo";
    }

    private String effectiveIssuer(final State current) {
        return current.issuer().isEmpty() ? defaultIssuer() : current.issuer();
    }

    public synchronized JSONObject prepareSetup(final String userId, final String accountName) {
        if (userId == null || userId.isBlank() || accountName == null || accountName.isBlank()) {
            throw new IllegalArgumentException("无法识别当前管理员账号。");
        }
        final long now = clock.getAsLong();
        pruneSetups(now);
        final State current = state();
        final String secret = generateSecret();
        final String setupId = randomToken();
        final String issuer = effectiveIssuer(current);
        final long expiresAt = now + SETUP_TTL_MS;
        pendingSetups.put(setupId, new PendingSetup(userId, accountName.strip(), secret, issuer, expiresAt));
        return new JSONObject()
                .put("setupId", setupId)
                .put("secret", secret)
                .put("formattedSecret", formatSecret(secret))
                .put("otpauthUri", buildOtpAuthUri(issuer, accountName.strip(), secret))
                .put("account", accountName.strip())
                .put("issuer", issuer)
                .put("requireCurrentCode", current.enabled() && current.bound())
                .put("expiresAt", expiresAt);
    }

    public synchronized JSONObject confirmSetup(final String userId, final String setupId, final String newCode,
                                                final String currentCode) throws Exception {
        if (setupId == null || !setupId.matches("[A-Za-z0-9_-]{32}")) {
            throw new IllegalArgumentException("绑定会话无效或已过期，请重新生成绑定密钥。");
        }
        final long now = clock.getAsLong();
        pruneSetups(now);
        final PendingSetup pending = pendingSetups.get(setupId);
        if (pending == null || !pending.userId().equals(userId) || pending.expiresAt() <= now) {
            pendingSetups.remove(setupId);
            throw new IllegalArgumentException("绑定会话已过期，请重新生成绑定密钥。");
        }
        final State current = state();
        if (current.enabled() && current.bound()) {
            if (!verifyActiveTotpOrRecovery(current, currentCode, now).matched()) {
                failSetupAttempt(setupId, pending, "原验证器动态口令或应急恢复码不正确。");
            }
        }
        final long acceptedStep = matchTotpStep(pending.secret(), newCode, current.windowSteps(), -1L, now);
        if (acceptedStep < 0) {
            failSetupAttempt(setupId, pending, "新验证器 6 位动态口令不正确，请确认设备时间准确后重试。");
        }
        pendingSetups.remove(setupId);
        final String recoverySalt = randomHex(16);
        final List<String> plainRecovery = generateRecoveryCodes();
        final List<String> digests = plainRecovery.stream()
                .map(code -> hashRecovery(recoverySalt, normalizeRecoveryCode(code)))
                .toList();
        final State next = new State(
                true,
                current.scope(),
                current.issuer(),
                current.windowSteps(),
                current.rememberDays(),
                pending.secret(),
                pending.accountName(),
                now,
                acceptedStep,
                current.deviceEpoch() + 1L,
                randomHex(32),
                recoverySalt,
                digests
        );
        persist(next);
        return new JSONObject()
                .put("totp", toStatusJson(next))
                .put("recoveryCodes", new JSONArray(plainRecovery));
    }

    private void failSetupAttempt(final String setupId, final PendingSetup pending, final String message) {
        pending.failures++;
        if (pending.failures >= MAX_CHALLENGE_FAILURES) {
            pendingSetups.remove(setupId);
            throw new IllegalArgumentException("验证错误次数过多，绑定会话已失效，请重新生成绑定密钥。");
        }
        throw new IllegalArgumentException(message);
    }

    public synchronized boolean settingRequiresVerification(final JSONObject request) {
        if (request == null) {
            return false;
        }
        final State current = state();
        if (!current.bound()) {
            return false;
        }
        final String enabledParam = request.optString("enabled", current.enabled() ? "on" : "off");
        return ("on".equals(enabledParam) && !current.enabled())
                || ("off".equals(enabledParam) && current.enabled());
    }

    public synchronized JSONObject updateSettings(final JSONObject request) throws Exception {
        if (request == null) {
            throw new IllegalArgumentException("2FA 配置不能为空。");
        }
        final State current = state();
        final String enabledParam = request.optString("enabled", current.enabled() ? "on" : "off");
        if (!Set.of("on", "off").contains(enabledParam)) {
            throw new IllegalArgumentException("2FA 开关配置不正确。");
        }
        final boolean targetEnabled = "on".equals(enabledParam);
        if (targetEnabled && !current.bound()) {
            throw new IllegalArgumentException("请先点击“生成绑定密钥”并使用身份验证器应用扫码验证后再启用 2FA。");
        }
        final String scope = request.optString("scope", current.scope());
        if (!ALLOWED_SCOPES.contains(scope)) {
            throw new IllegalArgumentException("适用账号范围配置不正确。");
        }
        final String issuer = validateIssuer(request.optString("issuer", current.issuer()));
        final int windowSteps = parseIntOption(request, "windowSteps", current.windowSteps(), ALLOWED_WINDOWS, "时间偏移容差配置不正确。");
        final int rememberDays = parseIntOption(request, "rememberDays", current.rememberDays(), ALLOWED_REMEMBER_DAYS, "免二次验证时长配置不正确。");

        final long now = clock.getAsLong();
        long lastStep = current.lastAcceptedStep();
        List<String> recoveryDigests = current.recoveryDigests();
        long deviceEpoch = current.deviceEpoch();
        String deviceKey = current.deviceKey();

        final String verifyCode = request.optString("verifyCode");
        if (current.enabled() && !targetEnabled) {
            final VerificationOutcome verification = verifyActiveTotpOrRecovery(current, verifyCode, now);
            if (!verification.matched()) {
                throw new IllegalArgumentException("停用 2FA 需输入当前 6 位动态口令或应急恢复码。");
            }
            lastStep = verification.acceptedStep();
            recoveryDigests = verification.remainingDigests();
            deviceEpoch = current.deviceEpoch() + 1L;
            deviceKey = randomHex(32);
        } else if (!current.enabled() && targetEnabled) {
            final long step = matchTotpStep(current.secret(), verifyCode, windowSteps, current.lastAcceptedStep(), now);
            if (step < 0) {
                throw new IllegalArgumentException("重新启用 2FA 需输入验证器当前显示的 6 位动态口令。");
            }
            lastStep = step;
            deviceEpoch = current.deviceEpoch() + 1L;
            deviceKey = randomHex(32);
        } else if (rememberDays == 0 && current.rememberDays() > 0) {
            deviceEpoch = current.deviceEpoch() + 1L;
            deviceKey = randomHex(32);
        }

        final State next = new State(
                targetEnabled,
                scope,
                issuer,
                windowSteps,
                rememberDays,
                current.secret(),
                current.boundUser(),
                current.boundAt(),
                lastStep,
                deviceEpoch,
                deviceKey,
                current.recoverySalt(),
                recoveryDigests
        );
        persist(next);
        return toStatusJson(next);
    }

    public synchronized JSONObject regenerateRecoveryCodes(final String verifyCode) throws Exception {
        final State current = state();
        if (!current.bound()) {
            throw new IllegalArgumentException("当前尚未绑定 2FA 验证器。");
        }
        final long now = clock.getAsLong();
        final long step = matchTotpStep(current.secret(), verifyCode, current.windowSteps(), current.lastAcceptedStep(), now);
        if (step < 0) {
            throw new IllegalArgumentException("请输入当前有效的 6 位动态口令以重新生成应急恢复码。");
        }
        final String recoverySalt = randomHex(16);
        final List<String> plainRecovery = generateRecoveryCodes();
        final List<String> digests = plainRecovery.stream()
                .map(code -> hashRecovery(recoverySalt, normalizeRecoveryCode(code)))
                .toList();
        final State next = new State(
                current.enabled(),
                current.scope(),
                current.issuer(),
                current.windowSteps(),
                current.rememberDays(),
                current.secret(),
                current.boundUser(),
                current.boundAt(),
                step,
                current.deviceEpoch(),
                current.deviceKey(),
                recoverySalt,
                digests
        );
        persist(next);
        return new JSONObject()
                .put("totp", toStatusJson(next))
                .put("recoveryCodes", new JSONArray(plainRecovery));
    }

    public synchronized JSONObject revokeTrustedDevices(final Response response) throws Exception {
        final State current = state();
        final State next = new State(
                current.enabled(),
                current.scope(),
                current.issuer(),
                current.windowSteps(),
                current.rememberDays(),
                current.secret(),
                current.boundUser(),
                current.boundAt(),
                current.lastAcceptedStep(),
                current.deviceEpoch() + 1L,
                randomHex(32),
                current.recoverySalt(),
                current.recoveryDigests()
        );
        persist(next);
        clearTrustedDeviceCookie(response);
        return toStatusJson(next);
    }

    public synchronized JSONObject unbind(final String verifyCode, final Response response) throws Exception {
        final State current = state();
        if (!current.bound()) {
            throw new IllegalArgumentException("当前尚未绑定 2FA 验证器。");
        }
        if (current.enabled()) {
            final VerificationOutcome verification = verifyActiveTotpOrRecovery(current, verifyCode, clock.getAsLong());
            if (!verification.matched()) {
                throw new IllegalArgumentException("解绑 2FA 需输入当前 6 位动态口令或应急恢复码。");
            }
        }
        final State next = new State(
                false,
                current.scope(),
                current.issuer(),
                current.windowSteps(),
                current.rememberDays(),
                "",
                "",
                0L,
                -1L,
                current.deviceEpoch() + 1L,
                randomHex(32),
                randomHex(16),
                List.of()
        );
        persist(next);
        clearTrustedDeviceCookie(response);
        return toStatusJson(next);
    }

    public synchronized boolean isActive() {
        final State current = state();
        return current.enabled() && current.bound();
    }

    public synchronized boolean requiresVerification(final JSONObject user, final Request request) {
        if (user == null) {
            return false;
        }
        final State current = state();
        if (!current.enabled() || !current.bound()) {
            return false;
        }
        final String role = user.optString(User.USER_ROLE);
        if (!"all".equals(current.scope()) && !Role.ADMIN_ROLE.equals(role)) {
            return false;
        }
        return !isDeviceTrusted(current, user, request);
    }

    public synchronized int rememberDays() {
        return state().rememberDays();
    }

    public synchronized String issueLoginChallenge(final JSONObject user, final String clientAddress) {
        final String ip = IpAddresses.canonical(clientAddress);
        if (ip.isEmpty()) {
            throw new IllegalArgumentException("无法识别登录请求来源。");
        }
        final long now = clock.getAsLong();
        pruneChallenges(now);
        while (challenges.size() >= 4096) {
            challenges.remove(challenges.keySet().iterator().next());
        }
        final String token = randomToken();
        challenges.put(token, new LoginChallenge(
                user.optString(Keys.OBJECT_ID),
                user.optString(User.USER_NAME),
                user.optString(UserExt.USER_B3_KEY),
                ip,
                now + CHALLENGE_TTL_MS
        ));
        return token;
    }

    public synchronized String peekChallengeUsername(final String token, final String clientAddress) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{32}")) {
            return null;
        }
        final long now = clock.getAsLong();
        final LoginChallenge challenge = challenges.get(token);
        if (challenge == null || challenge.expiresAt <= now) {
            if (challenge != null) {
                challenges.remove(token);
            }
            return null;
        }
        return challenge.address.equals(IpAddresses.canonical(clientAddress)) ? challenge.username : null;
    }

    public synchronized ChallengeResult verifyLoginChallenge(final String token, final String clientAddress,
                                                             final String code) throws Exception {
        if (token == null || !token.matches("[A-Za-z0-9_-]{32}")) {
            return ChallengeResult.expired("2FA 验证会话无效或已过期，请重新输入用户名和密码。");
        }
        final long now = clock.getAsLong();
        pruneChallenges(now);
        final LoginChallenge challenge = challenges.get(token);
        if (challenge == null || !challenge.address.equals(IpAddresses.canonical(clientAddress))) {
            return ChallengeResult.expired("2FA 验证会话已过期，请重新输入用户名和密码。");
        }
        final State current = state();
        if (!current.enabled() || !current.bound()) {
            challenges.remove(token);
            return ChallengeResult.verified(challenge.userId, challenge.username, challenge.passwordHash);
        }
        final VerificationOutcome outcome = verifyActiveTotpOrRecovery(current, code, now);
        if (!outcome.matched()) {
            challenge.failures++;
            if (challenge.failures >= MAX_CHALLENGE_FAILURES) {
                challenges.remove(token);
                return ChallengeResult.expired("动态口令连续错误次数过多，请重新输入用户名和密码。");
            }
            return ChallengeResult.invalid("动态口令或应急恢复码不正确，请重试。");
        }
        challenges.remove(token);
        final State next = new State(
                current.enabled(),
                current.scope(),
                current.issuer(),
                current.windowSteps(),
                current.rememberDays(),
                current.secret(),
                current.boundUser(),
                current.boundAt(),
                outcome.acceptedStep(),
                current.deviceEpoch(),
                current.deviceKey(),
                current.recoverySalt(),
                outcome.remainingDigests()
        );
        persist(next);
        return ChallengeResult.verified(challenge.userId, challenge.username, challenge.passwordHash);
    }

    public synchronized void writeTrustedDeviceCookie(final JSONObject user, final Response response) {
        final State current = state();
        if (response == null || user == null || !current.enabled() || current.rememberDays() <= 0) {
            return;
        }
        final long expiresAt = clock.getAsLong() + current.rememberDays() * 86_400_000L;
        final String sig = signTrustedDevice(current, user, expiresAt);
        final Cookie cookie = new Cookie(trustedCookieName(), expiresAt + "." + sig);
        cookie.setPath("/");
        cookie.setMaxAge(current.rememberDays() * 86400);
        cookie.setHttpOnly(Solos.COOKIE_HTTP_ONLY);
        cookie.setSecure("https".equalsIgnoreCase(Latkes.getServerScheme()));
        cookie.setSameSite("Lax");
        response.getCookies().removeIf(existing -> trustedCookieName().equals(existing.getName()));
        response.addCookie(cookie);
    }

    public static void clearTrustedDeviceCookie(final Response response) {
        if (response == null) {
            return;
        }
        final String name = trustedCookieName();
        response.getCookies().removeIf(existing -> name.equals(existing.getName()));
        final Cookie cookie = new Cookie(name, "");
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setHttpOnly(Solos.COOKIE_HTTP_ONLY);
        cookie.setSecure("https".equalsIgnoreCase(Latkes.getServerScheme()));
        cookie.setSameSite("Lax");
        response.addCookie(cookie);
    }

    private boolean isDeviceTrusted(final State current, final JSONObject user, final Request request) {
        if (request == null || current.rememberDays() <= 0) {
            return false;
        }
        final String name = trustedCookieName();
        for (final Cookie cookie : request.getCookies()) {
            if (!name.equals(cookie.getName())) {
                continue;
            }
            final String value = cookie.getValue();
            if (value == null || !value.matches("[0-9]{1,16}\\.[0-9a-f]{64}")) {
                return false;
            }
            final int dot = value.indexOf('.');
            final long expiresAt;
            try {
                expiresAt = Long.parseLong(value.substring(0, dot));
            } catch (final NumberFormatException invalid) {
                return false;
            }
            final long now = clock.getAsLong();
            final long maxAllowed = now + current.rememberDays() * 86_400_000L + 60_000L;
            if (expiresAt <= now || expiresAt > maxAllowed) {
                return false;
            }
            final String expected = signTrustedDevice(current, user, expiresAt);
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.US_ASCII),
                    value.substring(dot + 1).getBytes(StandardCharsets.US_ASCII)
            );
        }
        return false;
    }

    private static String signTrustedDevice(final State current, final JSONObject user, final long expiresAt) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HEX.parseHex(current.deviceKey()), "HmacSHA256"));
            final String payload = user.optString(Keys.OBJECT_ID) + ":"
                    + user.optString(UserExt.USER_B3_KEY) + ":"
                    + current.deviceEpoch() + ":" + expiresAt;
            return HEX.formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (final GeneralSecurityException impossible) {
            throw new IllegalStateException("Cannot compute trusted-device HMAC", impossible);
        }
    }

    private static String trustedCookieName() {
        return Solos.COOKIE_NAME + "_totp";
    }

    private record VerificationOutcome(boolean matched, long acceptedStep, List<String> remainingDigests) { }

    private VerificationOutcome verifyActiveTotpOrRecovery(final State current, final String rawInput, final long now) {
        if (rawInput == null || rawInput.isBlank()) {
            return new VerificationOutcome(false, current.lastAcceptedStep(), current.recoveryDigests());
        }
        final long step = matchTotpStep(current.secret(), rawInput, current.windowSteps(), current.lastAcceptedStep(), now);
        if (step >= 0) {
            return new VerificationOutcome(true, step, current.recoveryDigests());
        }
        final String normalizedRecovery = normalizeRecoveryCode(rawInput);
        if (!normalizedRecovery.isEmpty() && !current.recoveryDigests().isEmpty()) {
            final byte[] candidate = hashRecovery(current.recoverySalt(), normalizedRecovery).getBytes(StandardCharsets.US_ASCII);
            int matchedIndex = -1;
            for (int i = 0; i < current.recoveryDigests().size(); i++) {
                final byte[] stored = current.recoveryDigests().get(i).getBytes(StandardCharsets.US_ASCII);
                if (MessageDigest.isEqual(stored, candidate)) {
                    matchedIndex = i;
                }
            }
            if (matchedIndex >= 0) {
                final List<String> updated = new ArrayList<>(current.recoveryDigests());
                updated.remove(matchedIndex);
                return new VerificationOutcome(true, current.lastAcceptedStep(), List.copyOf(updated));
            }
        }
        return new VerificationOutcome(false, current.lastAcceptedStep(), current.recoveryDigests());
    }

    static long matchTotpStep(final String base32Secret, final String rawCode, final int windowSteps,
                              final long lastAcceptedStep, final long nowMillis) {
        if (rawCode == null) {
            return -1L;
        }
        final String compact = rawCode.replace(" ", "").strip();
        if (!compact.matches("[0-9]{6}")) {
            return -1L;
        }
        final byte[] secretBytes = decodeBase32(base32Secret);
        if (secretBytes.length == 0) {
            return -1L;
        }
        final long currentStep = Math.floorDiv(nowMillis / 1000L, PERIOD_SECONDS);
        final byte[] inputBytes = compact.getBytes(StandardCharsets.US_ASCII);
        final int[] offsets = windowSteps == 0 ? new int[]{0}
                : windowSteps == 1 ? new int[]{0, -1, 1}
                : new int[]{0, -1, 1, -2, 2};
        for (final int offset : offsets) {
            final long candidateStep = currentStep + offset;
            if (candidateStep < 0 || candidateStep <= lastAcceptedStep) {
                continue;
            }
            final String expected = generateCodeAtStep(secretBytes, candidateStep);
            if (MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), inputBytes)) {
                return candidateStep;
            }
        }
        return -1L;
    }

    public static String generateCode(final String base32Secret, final long nowMillis) {
        final byte[] secretBytes = decodeBase32(base32Secret);
        if (secretBytes.length == 0) {
            throw new IllegalArgumentException("Invalid Base32 TOTP secret");
        }
        return generateCodeAtStep(secretBytes, Math.floorDiv(nowMillis / 1000L, PERIOD_SECONDS));
    }

    static String generateCodeAtStep(final byte[] secretBytes, final long step) {
        try {
            final byte[] counter = ByteBuffer.allocate(8).putLong(step).array();
            final Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secretBytes, "HmacSHA1"));
            final byte[] hash = mac.doFinal(counter);
            final int offset = hash[hash.length - 1] & 0x0f;
            final int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            final int otp = binary % 1_000_000;
            return String.format(Locale.ROOT, "%06d", otp);
        } catch (final GeneralSecurityException impossible) {
            throw new IllegalStateException("Cannot compute TOTP HMAC-SHA1", impossible);
        }
    }

    public static String generateSecret() {
        final byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return new Base32().encodeToString(bytes).replace("=", "");
    }

    public static String buildOtpAuthUri(final String issuer, final String accountName, final String secret) {
        final String safeIssuer = issuer == null || issuer.isBlank() ? "Volo" : issuer.replace(':', '-').strip();
        final String safeAccount = accountName == null || accountName.isBlank() ? "admin" : accountName.replace(':', '_').strip();
        final String encodedIssuer = percentEncode(safeIssuer);
        final String encodedAccount = percentEncode(safeAccount);
        return "otpauth://totp/" + encodedIssuer + ":" + encodedAccount
                + "?secret=" + normalizeBase32Secret(secret)
                + "&issuer=" + encodedIssuer
                + "&algorithm=SHA1&digits=6&period=" + PERIOD_SECONDS;
    }

    private static String percentEncode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String formatSecret(final String secret) {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < secret.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                out.append(' ');
            }
            out.append(secret.charAt(i));
        }
        return out.toString();
    }

    private static String normalizeBase32Secret(final String raw) {
        if (raw == null) {
            return "";
        }
        final String cleaned = raw.replace(" ", "").replace("=", "").strip().toUpperCase(Locale.ROOT);
        return cleaned.matches("[A-Z2-7]{16,128}") ? cleaned : "";
    }

    private static byte[] decodeBase32(final String secret) {
        final String normalized = normalizeBase32Secret(secret);
        return normalized.isEmpty() ? new byte[0] : new Base32().decode(normalized);
    }

    private static List<String> generateRecoveryCodes() {
        final List<String> codes = new ArrayList<>(RECOVERY_COUNT);
        while (codes.size() < RECOVERY_COUNT) {
            final StringBuilder code = new StringBuilder(9);
            for (int i = 0; i < 8; i++) {
                if (i == 4) {
                    code.append('-');
                }
                code.append(RECOVERY_ALPHABET.charAt(RANDOM.nextInt(RECOVERY_ALPHABET.length())));
            }
            final String candidate = code.toString();
            if (!codes.contains(candidate)) {
                codes.add(candidate);
            }
        }
        return List.copyOf(codes);
    }

    private static String normalizeRecoveryCode(final String raw) {
        if (raw == null) {
            return "";
        }
        final String cleaned = raw.replace("-", "").replace(" ", "").strip().toUpperCase(Locale.ROOT);
        return cleaned.matches("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{8}") ? cleaned : "";
    }

    private static String hashRecovery(final String salt, final String normalizedCode) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("totp-recovery:" + salt + ":" + normalizedCode).getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(digest);
        } catch (final GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String validateIssuer(final String raw) {
        if (raw == null) {
            return "";
        }
        final String trimmed = raw.strip();
        if (trimmed.length() > 64) {
            throw new IllegalArgumentException("验证器显示名称最多 64 个字符。");
        }
        if (trimmed.indexOf(':') >= 0) {
            throw new IllegalArgumentException("验证器显示名称不能包含冒号（:）。");
        }
        if (trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("验证器显示名称包含无效控制字符。");
        }
        return trimmed;
    }

    private static String sanitizeIssuerOrEmpty(final String raw) {
        try {
            return validateIssuer(raw);
        } catch (final IllegalArgumentException invalid) {
            return "";
        }
    }

    private static int parseIntOption(final JSONObject request, final String key, final int fallback,
                                      final Set<Integer> allowed, final String errorMessage) {
        if (!request.has(key)) {
            return fallback;
        }
        final Object raw = request.get(key);
        final int parsed;
        if (raw instanceof Number number) {
            if (!raw.toString().matches("-?[0-9]{1,4}")) {
                throw new IllegalArgumentException(errorMessage);
            }
            parsed = number.intValue();
        } else if (raw instanceof String text && text.strip().matches("-?[0-9]{1,4}")) {
            parsed = Integer.parseInt(text.strip());
        } else {
            throw new IllegalArgumentException(errorMessage);
        }
        if (!allowed.contains(parsed)) {
            throw new IllegalArgumentException(errorMessage);
        }
        return parsed;
    }

    private void pruneSetups(final long now) {
        pendingSetups.values().removeIf(item -> item.expiresAt() <= now);
        while (pendingSetups.size() >= 256) {
            pendingSetups.remove(pendingSetups.keySet().iterator().next());
        }
    }

    private void pruneChallenges(final long now) {
        challenges.values().removeIf(item -> item.expiresAt <= now);
    }

    private static String randomToken() {
        final byte[] nonce = new byte[24];
        RANDOM.nextBytes(nonce);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
    }

    private static String randomHex(final int byteCount) {
        final byte[] bytes = new byte[byteCount];
        RANDOM.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }

    public static boolean isProtectedOptionId(final String id) {
        return OPTION_ID.equals(id) || (id != null && id.startsWith(OPTION_ID + "."));
    }
}
