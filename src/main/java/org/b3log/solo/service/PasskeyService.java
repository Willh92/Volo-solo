/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.service;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.model.Role;
import org.b3log.latke.model.User;
import org.b3log.latke.service.annotation.Service;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.model.Option;
import org.b3log.solo.model.UserExt;
import org.b3log.solo.repository.UserRepository;
import org.b3log.solo.util.OptionDocuments;
import org.b3log.solo.util.Solos;
import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EllipticCurve;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Pure Java 17 WebAuthn Level 2 / FIDO2 Passkey registration, management, and passwordless assertion verification.
 *
 * @author <a href="https://github.com/Willh92">Willh92</a>
 * @since 3.0.0
 */
@Service
public class PasskeyService {

    private static final Logger LOGGER = Logger.getLogger(PasskeyService.class);

    public static final String OPTION_ID = Option.ID_C_VOLO_PASSKEY_AUTH;
    private static final String OPTION_CATEGORY = "security";

    public static final int MAX_CHALLENGES = 256;
    public static final long CHALLENGE_TTL_MS = 300_000L;
    public static final int MAX_CREDENTIALS_PER_USER = 20;
    public static final int MAX_CBOR_BYTES = 65_536;

    public static final int ALG_ES256 = -7;
    public static final int ALG_RS256 = -257;

    private static final byte FLAG_USER_PRESENT = 0x01;
    private static final byte FLAG_ATTESTED_CREDENTIAL_DATA = 0x40;
    private static final int FLAG_EXTENSION_DATA = 0x80;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    /**
     * Short-lived single-use WebAuthn challenge entry.
     */
    public record ChallengeEntry(
            String challengeId,
            String purpose,
            byte[] challengeBytes,
            String userId,
            String rpId,
            String expectedOrigin,
            long expiresAt
    ) {
    }

    private static final class StoredCredential {
        private final String id;
        private final String userId;
        private final String credentialId;
        private final String publicKeySpki;
        private final int alg;
        private final long signCount;
        private final String name;
        private final long createdAt;
        private final long lastUsedAt;
        private volatile PublicKey cachedPublicKey;

        StoredCredential(final String id,
                         final String userId,
                         final String credentialId,
                         final String publicKeySpki,
                         final int alg,
                         final long signCount,
                         final String name,
                         final long createdAt,
                         final long lastUsedAt) {
            this(id, userId, credentialId, publicKeySpki, alg, signCount, name, createdAt, lastUsedAt, null);
        }

        StoredCredential(final String id,
                         final String userId,
                         final String credentialId,
                         final String publicKeySpki,
                         final int alg,
                         final long signCount,
                         final String name,
                         final long createdAt,
                         final long lastUsedAt,
                         final PublicKey cachedPublicKey) {
            this.id = id;
            this.userId = userId;
            this.credentialId = credentialId;
            this.publicKeySpki = publicKeySpki;
            this.alg = alg;
            this.signCount = signCount;
            this.name = name;
            this.createdAt = createdAt;
            this.lastUsedAt = lastUsedAt;
            this.cachedPublicKey = cachedPublicKey;
        }

        String id() { return id; }
        String userId() { return userId; }
        String credentialId() { return credentialId; }
        String publicKeySpki() { return publicKeySpki; }
        int alg() { return alg; }
        long signCount() { return signCount; }
        String name() { return name; }
        long createdAt() { return createdAt; }
        long lastUsedAt() { return lastUsedAt; }

        PublicKey resolvePublicKey() throws GeneralSecurityException {
            PublicKey key = cachedPublicKey;
            if (key != null) {
                return key;
            }
            final String keyAlgorithm;
            if (alg == ALG_ES256) {
                keyAlgorithm = "EC";
            } else if (alg == ALG_RS256) {
                keyAlgorithm = "RSA";
            } else {
                throw new GeneralSecurityException("Unsupported WebAuthn COSE algorithm: " + alg);
            }
            final byte[] spki = Base64.getDecoder().decode(publicKeySpki);
            key = KeyFactory.getInstance(keyAlgorithm).generatePublic(new X509EncodedKeySpec(spki));
            cachedPublicKey = key;
            return key;
        }

        JSONObject toPublicJson() {
            return new JSONObject()
                    .put("id", id)
                    .put("credentialId", credentialId)
                    .put("name", name)
                    .put("alg", alg)
                    .put("signCount", signCount)
                    .put("createdAt", createdAt)
                    .put("lastUsedAt", lastUsedAt);
        }

        JSONObject toStorageJson() {
            return toPublicJson()
                    .put("userId", userId)
                    .put("publicKeySpki", publicKeySpki);
        }
    }

    private record ParsedCoseKey(PublicKey publicKey, int alg) {
    }

    private final LongSupplier clock;
    private final LinkedHashMap<String, ChallengeEntry> challenges = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> pendingDeviceNames = new LinkedHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean metadataDirty = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile List<StoredCredential> cachedCredentials;

    @Inject
    private OptionQueryService optionQueryService;

    @Inject
    private OptionMgmtService optionMgmtService;

    @Inject
    private UserRepository userRepository;

    @Inject
    private UserQueryService userQueryService;

    public PasskeyService() {
        this(System::currentTimeMillis);
    }

    public PasskeyService(final LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Returns {@code true} if the specified option ID belongs to protected Passkey credential storage
     * and must be excluded from public/plain JSON exports.
     */
    public static boolean isProtectedOptionId(final String id) {
        return Option.ID_C_VOLO_PASSKEY_AUTH.equals(id)
                || (id != null && id.startsWith(Option.ID_C_VOLO_PASSKEY_AUTH + "."));
    }

    /**
     * Clears the in-memory credential snapshot so the next read reloads from {@link OptionDocuments}.
     */
    public synchronized void reloadFromStorage() {
        metadataDirty.set(false);
        cachedCredentials = null;
    }

    /**
     * Flushes deferred authentication metadata ({@code signCount}, {@code lastUsedAt}) to persistent storage if dirty.
     */
    public synchronized void flushIfDirty() {
        if (!metadataDirty.compareAndSet(true, false)) {
            return;
        }
        final List<StoredCredential> current = cachedCredentials;
        if (current == null) {
            return;
        }
        try {
            persistCredentials(current);
        } catch (final Exception e) {
            metadataDirty.set(true);
            LOGGER.log(Level.WARN, "Flushes passkey credential metadata failed", e);
        }
    }

    /**
     * Derives the WebAuthn Relying Party ID (lowercase host) from {@link Latkes#getServePath()}.
     */
    public static String currentRpId() {
        final URI serveUri = URI.create(Latkes.getServePath());
        final String host = serveUri.getHost();
        if (StringUtils.isBlank(host)) {
            throw new IllegalStateException("无法解析当前站点域名（servePath），请检查配置。");
        }
        return host.toLowerCase(Locale.ROOT);
    }

    /**
     * Derives the expected WebAuthn ceremony origin ({@code scheme://authority}) from {@link Latkes#getServePath()}.
     */
    public static String currentExpectedOrigin() {
        final URI serveUri = URI.create(Latkes.getServePath());
        final String scheme = serveUri.getScheme();
        final String rawAuthority = serveUri.getRawAuthority();
        if (StringUtils.isBlank(scheme) || StringUtils.isBlank(rawAuthority)) {
            throw new IllegalStateException("无法解析当前站点 Origin（servePath），请检查配置。");
        }
        return scheme.toLowerCase(Locale.ROOT) + "://" + rawAuthority.toLowerCase(Locale.ROOT);
    }

    /**
     * Lists public metadata for all Passkey credentials bound to the specified user.
     */
    public JSONArray listCredentialsForUser(final String userId) {
        final JSONArray result = new JSONArray();
        if (StringUtils.isBlank(userId)) {
            return result;
        }
        for (final StoredCredential cred : loadCredentials()) {
            if (userId.equals(cred.userId())) {
                result.put(cred.toPublicJson());
            }
        }
        return result;
    }

    /**
     * Returns {@code true} if at least one Passkey credential has been registered on this blog.
     */
    public boolean hasAnyCredentials() {
        return !loadCredentials().isEmpty();
    }

    /**
     * Starts a WebAuthn Passkey registration ceremony using the blog's configured RP ID and origin.
     */
    public JSONObject startRegistration(final JSONObject adminUser, final String deviceName) {
        return startRegistration(adminUser, deviceName, currentRpId(), currentExpectedOrigin());
    }

    /**
     * Starts a WebAuthn Passkey registration ceremony with explicit {@code rpId} and {@code expectedOrigin}.
     */
    public synchronized JSONObject startRegistration(final JSONObject adminUser,
                                                     final String deviceName,
                                                     final String rpId,
                                                     final String expectedOrigin) {
        if (adminUser == null) {
            throw new IllegalArgumentException("无法识别当前管理员账号。");
        }
        final String userId = adminUser.optString(Keys.OBJECT_ID).trim();
        final String userName = adminUser.optString(User.USER_NAME).trim();
        if (userId.isEmpty() || userName.isEmpty()) {
            throw new IllegalArgumentException("无法识别当前管理员账号。");
        }
        final String normalizedRpId = validateRpId(rpId);
        final String normalizedOrigin = validateOrigin(expectedOrigin);

        final List<StoredCredential> existing = loadCredentials();
        final JSONArray excludeCredentials = new JSONArray();
        int userCount = 0;
        for (final StoredCredential cred : existing) {
            if (userId.equals(cred.userId())) {
                userCount++;
                excludeCredentials.put(new JSONObject()
                        .put("type", "public-key")
                        .put("id", cred.credentialId()));
            }
        }
        if (userCount >= MAX_CREDENTIALS_PER_USER) {
            throw new IllegalArgumentException("当前账号绑定的通行密钥数量已达上限（最多 " + MAX_CREDENTIALS_PER_USER + " 个）。");
        }

        final long now = clock.getAsLong();
        pruneChallenges(now);

        final byte[] challengeBytes = new byte[32];
        RANDOM.nextBytes(challengeBytes);
        final String challengeId = randomToken();
        storeChallenge(new ChallengeEntry(
                challengeId,
                "register",
                challengeBytes,
                userId,
                normalizedRpId,
                normalizedOrigin,
                now + CHALLENGE_TTL_MS
        ));
        final String cleanedName = sanitizeDeviceName(deviceName);
        if (!cleanedName.isEmpty()) {
            pendingDeviceNames.put(challengeId, cleanedName);
        }

        final JSONArray pubKeyCredParams = new JSONArray()
                .put(new JSONObject().put("type", "public-key").put("alg", ALG_ES256))
                .put(new JSONObject().put("type", "public-key").put("alg", ALG_RS256));

        return new JSONObject()
                .put("challengeId", challengeId)
                .put("challenge", encodeBase64Url(challengeBytes))
                .put("rp", new JSONObject()
                        .put("id", normalizedRpId)
                        .put("name", resolveRpName()))
                .put("user", new JSONObject()
                        .put("id", encodeBase64Url(userId.getBytes(StandardCharsets.UTF_8)))
                        .put("name", userName)
                        .put("displayName", userName))
                .put("pubKeyCredParams", pubKeyCredParams)
                .put("timeout", 60000)
                .put("attestation", "none")
                .put("authenticatorSelection", new JSONObject()
                        .put("residentKey", "preferred")
                        .put("userVerification", "preferred"))
                .put("excludeCredentials", excludeCredentials);
    }

    /**
     * Completes a WebAuthn Passkey registration ceremony, verifying {@code clientDataJSON},
     * {@code attestationObject}, {@code rpIdHash}, {@code UP}/{@code AT} flags, and COSE public key.
     */
    public synchronized JSONObject finishRegistration(final JSONObject adminUser, final JSONObject payload) throws Exception {
        if (adminUser == null || payload == null) {
            throw new IllegalArgumentException("通行密钥绑定请求无效。");
        }
        final String userId = adminUser.optString(Keys.OBJECT_ID).trim();
        if (userId.isEmpty()) {
            throw new IllegalArgumentException("无法识别当前管理员账号。");
        }

        final long now = clock.getAsLong();
        pruneChallenges(now);

        final JSONObject credentialObj = payload.optJSONObject("credential") != null
                ? payload.getJSONObject("credential") : payload;
        final JSONObject responseObj = credentialObj.optJSONObject("response") != null
                ? credentialObj.getJSONObject("response") : payload;

        final byte[] clientDataBytes = decodeBase64Url(responseObj.optString("clientDataJSON"), "clientDataJSON");
        final JSONObject clientData = parseClientDataJson(clientDataBytes);

        final ChallengeEntry challengeEntry = consumeChallenge(payload, credentialObj, clientData, "register", now);
        final String rememberedName = pendingDeviceNames.remove(challengeEntry.challengeId());
        if (!userId.equals(challengeEntry.userId())) {
            throw new IllegalArgumentException("通行密钥绑定会话与当前账号不匹配，请重试。");
        }

        verifyClientData(clientData, "webauthn.create", challengeEntry);

        final byte[] attestationBytes = decodeBase64Url(responseObj.optString("attestationObject"), "attestationObject");
        final Object decodedAttestation = decodeCbor(attestationBytes);
        if (!(decodedAttestation instanceof Map<?, ?> attMap)) {
            throw new IllegalArgumentException("attestationObject 格式无效。");
        }
        final Object authDataObj = attMap.get("authData");
        if (!(authDataObj instanceof byte[] authData) || authData.length < 57) {
            throw new IllegalArgumentException("authenticatorData 数据长度或格式无效。");
        }

        verifyRpIdHash(authData, challengeEntry.rpId());
        final int flags = authData[32] & 0xFF;
        if ((flags & FLAG_USER_PRESENT) == 0) {
            throw new IllegalArgumentException("未检测到用户在线确认（UP 标志位缺失）。");
        }
        if ((flags & FLAG_ATTESTED_CREDENTIAL_DATA) == 0) {
            throw new IllegalArgumentException("注册响应缺少凭证公钥数据（AT 标志位缺失）。");
        }

        final long signCount = readUint32BE(authData, 33);
        // 37..52 is 16-byte AAGUID
        final int credIdLen = ((authData[53] & 0xFF) << 8) | (authData[54] & 0xFF);
        if (credIdLen < 1 || credIdLen > 1023 || 55 + credIdLen >= authData.length) {
            throw new IllegalArgumentException("通行密钥凭证 ID 长度无效。");
        }
        final byte[] credentialIdBytes = Arrays.copyOfRange(authData, 55, 55 + credIdLen);
        final String credentialId = encodeBase64Url(credentialIdBytes);

        final String outerId = firstNonBlank(
                credentialObj.optString("id"),
                credentialObj.optString("rawId"),
                payload.optString("credentialId")
        );
        if (!outerId.isEmpty()) {
            final byte[] outerIdBytes = decodeBase64Url(outerId, "credential.id");
            if (!MessageDigest.isEqual(outerIdBytes, credentialIdBytes)) {
                throw new IllegalArgumentException("通行密钥凭证 ID 不一致。");
            }
        }

        final CborDecoder keyDecoder = new CborDecoder(authData, 55 + credIdLen);
        final Object coseKeyObj = keyDecoder.readItem(0);
        if ((flags & FLAG_EXTENSION_DATA) != 0 && keyDecoder.remaining() > 0) {
            final Object extObj = keyDecoder.readItem(0);
            if (!(extObj instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("authenticatorData 扩展字段格式无效。");
            }
        }
        if (keyDecoder.remaining() != 0) {
            throw new IllegalArgumentException("authenticatorData 包含未预期的尾部数据。");
        }
        if (!(coseKeyObj instanceof Map<?, ?> coseMap)) {
            throw new IllegalArgumentException("COSE 公钥格式无效。");
        }

        final ParsedCoseKey parsedKey = parseCosePublicKey(coseMap);
        final String spkiBase64 = Base64.getEncoder().encodeToString(parsedKey.publicKey().getEncoded());

        final List<StoredCredential> current = loadCredentials();
        int userCount = 0;
        for (final StoredCredential existing : current) {
            if (existing.credentialId().equals(credentialId)) {
                throw new IllegalArgumentException("该通行密钥已绑定，请勿重复添加。");
            }
            if (userId.equals(existing.userId())) {
                userCount++;
            }
        }
        if (userCount >= MAX_CREDENTIALS_PER_USER) {
            throw new IllegalArgumentException("当前账号绑定的通行密钥数量已达上限（最多 " + MAX_CREDENTIALS_PER_USER + " 个）。");
        }

        String finalName = sanitizeDeviceName(payload.optString("name"));
        if (finalName.isEmpty() && rememberedName != null) {
            finalName = rememberedName;
        }
        if (finalName.isEmpty()) {
            finalName = "通行密钥 " + (userCount + 1);
        }

        final StoredCredential created = new StoredCredential(
                randomHex(12),
                userId,
                credentialId,
                spkiBase64,
                parsedKey.alg(),
                signCount,
                finalName,
                now,
                0L,
                parsedKey.publicKey()
        );

        final List<StoredCredential> updated = new ArrayList<>(current.size() + 1);
        updated.addAll(current);
        updated.add(created);
        persistCredentials(updated);

        return new JSONObject()
                .put("credential", created.toPublicJson())
                .put("credentials", listCredentialsForUser(userId));
    }

    /**
     * Removes a bound Passkey credential by its internal ID for the specified user.
     */
    public synchronized JSONArray removeCredential(final String userId, final String id) throws Exception {
        if (StringUtils.isBlank(userId) || StringUtils.isBlank(id)) {
            throw new IllegalArgumentException("请指定要移除的通行密钥。");
        }
        final String targetId = id.trim();
        final List<StoredCredential> current = loadCredentials();
        final List<StoredCredential> updated = new ArrayList<>(current.size());
        boolean removed = false;
        for (final StoredCredential cred : current) {
            if (userId.equals(cred.userId()) && targetId.equals(cred.id())) {
                removed = true;
            } else {
                updated.add(cred);
            }
        }
        if (!removed) {
            throw new IllegalArgumentException("未找到指定的通行密钥。");
        }
        persistCredentials(updated);
        return listCredentialsForUser(userId);
    }

    /**
     * Starts a WebAuthn Passkey login assertion ceremony using the blog's configured RP ID and origin.
     */
    public JSONObject startAuthentication(final String optionalUsername) {
        return startAuthentication(optionalUsername, currentRpId(), currentExpectedOrigin());
    }

    /**
     * Starts a WebAuthn Passkey login assertion ceremony with explicit {@code rpId} and {@code expectedOrigin}.
     */
    public synchronized JSONObject startAuthentication(final String optionalUsername,
                                                       final String rpId,
                                                       final String expectedOrigin) {
        final List<StoredCredential> allCredentials = loadCredentials();
        if (allCredentials.isEmpty()) {
            throw new IllegalStateException("当前尚未绑定任何通行密钥，请先使用密码登录后在“安全 → 2FA 验证”中添加。");
        }
        final String normalizedRpId = validateRpId(rpId);
        final String normalizedOrigin = validateOrigin(expectedOrigin);

        final JSONArray allowCredentials = new JSONArray();
        String scopedUserId = "";
        if (StringUtils.isNotBlank(optionalUsername) && optionalUsername.trim().length() <= 128) {
            final JSONObject targetUser = findUserByName(optionalUsername.trim());
            if (isEligibleConsoleUser(targetUser)) {
                final String uid = targetUser.optString(Keys.OBJECT_ID);
                for (final StoredCredential cred : allCredentials) {
                    if (uid.equals(cred.userId())) {
                        allowCredentials.put(new JSONObject()
                                .put("type", "public-key")
                                .put("id", cred.credentialId()));
                    }
                }
                if (allowCredentials.length() > 0) {
                    scopedUserId = uid;
                }
            }
        }

        final long now = clock.getAsLong();
        pruneChallenges(now);

        final byte[] challengeBytes = new byte[32];
        RANDOM.nextBytes(challengeBytes);
        final String challengeId = randomToken();
        storeChallenge(new ChallengeEntry(
                challengeId,
                "login",
                challengeBytes,
                scopedUserId,
                normalizedRpId,
                normalizedOrigin,
                now + CHALLENGE_TTL_MS
        ));

        return new JSONObject()
                .put("challengeId", challengeId)
                .put("challenge", encodeBase64Url(challengeBytes))
                .put("rpId", normalizedRpId)
                .put("timeout", 60000)
                .put("userVerification", "preferred")
                .put("allowCredentials", allowCredentials);
    }

    /**
     * Completes a WebAuthn Passkey login assertion ceremony, verifying challenge, origin, {@code rpIdHash},
     * {@code UP} flag, cryptographic signature, clone counter, and user role/status.
     *
     * @return authenticated console user JSON object
     */
    public synchronized JSONObject finishAuthentication(final JSONObject payload) throws Exception {
        if (payload == null) {
            throw new IllegalArgumentException("通行密钥登录请求无效。");
        }
        final long now = clock.getAsLong();
        pruneChallenges(now);

        final JSONObject credentialObj = payload.optJSONObject("credential") != null
                ? payload.getJSONObject("credential") : payload;
        final JSONObject responseObj = credentialObj.optJSONObject("response") != null
                ? credentialObj.getJSONObject("response") : payload;

        final byte[] clientDataBytes = decodeBase64Url(responseObj.optString("clientDataJSON"), "clientDataJSON");
        final JSONObject clientData = parseClientDataJson(clientDataBytes);

        final ChallengeEntry challengeEntry = consumeChallenge(payload, credentialObj, clientData, "login", now);
        pendingDeviceNames.remove(challengeEntry.challengeId());

        verifyClientData(clientData, "webauthn.get", challengeEntry);

        final String rawCredId = firstNonBlank(
                credentialObj.optString("id"),
                credentialObj.optString("credentialId"),
                credentialObj.optString("rawId"),
                payload.optString("id"),
                payload.optString("credentialId")
        );
        final byte[] credIdBytes = decodeBase64Url(rawCredId, "credential.id");
        final String normalizedCredId = encodeBase64Url(credIdBytes);

        final List<StoredCredential> allCredentials = loadCredentials();
        int matchedIndex = -1;
        StoredCredential matchedCred = null;
        for (int i = 0; i < allCredentials.size(); i++) {
            final StoredCredential candidate = allCredentials.get(i);
            final byte[] candidateBytes = Base64.getUrlDecoder().decode(candidate.credentialId());
            if (MessageDigest.isEqual(candidateBytes, credIdBytes) || normalizedCredId.equals(candidate.credentialId())) {
                matchedIndex = i;
                matchedCred = candidate;
                break;
            }
        }
        if (matchedCred == null) {
            throw new IllegalArgumentException("未找到匹配的通行密钥，请重试或使用密码登录。");
        }

        if (!challengeEntry.userId().isEmpty() && !challengeEntry.userId().equals(matchedCred.userId())) {
            throw new IllegalArgumentException("通行密钥与指定登录账号不匹配。");
        }

        final String rawUserHandle = responseObj.optString("userHandle");
        if (StringUtils.isNotBlank(rawUserHandle)) {
            final byte[] handleBytes = decodeBase64Url(rawUserHandle, "userHandle");
            if (handleBytes.length > 0) {
                final String handleUserId = new String(handleBytes, StandardCharsets.UTF_8);
                if (!matchedCred.userId().equals(handleUserId)) {
                    throw new IllegalArgumentException("通行密钥用户标识不匹配。");
                }
            }
        }

        final JSONObject user = findUserById(matchedCred.userId());
        if (!isEligibleConsoleUser(user)) {
            throw new IllegalArgumentException("该账号不存在、已被禁用或无后台登录权限。");
        }

        final byte[] authData = decodeBase64Url(responseObj.optString("authenticatorData"), "authenticatorData");
        if (authData.length < 37) {
            throw new IllegalArgumentException("authenticatorData 长度无效。");
        }
        verifyRpIdHash(authData, challengeEntry.rpId());

        final int flags = authData[32] & 0xFF;
        if ((flags & FLAG_USER_PRESENT) == 0) {
            throw new IllegalArgumentException("未检测到用户在线确认（UP 标志位缺失）。");
        }

        final byte[] signatureBytes = decodeBase64Url(responseObj.optString("signature"), "signature");
        if (signatureBytes.length == 0 || signatureBytes.length > 1024) {
            throw new IllegalArgumentException("通行密钥签名长度无效。");
        }

        if (!verifyAssertionSignature(matchedCred, authData, clientDataBytes, signatureBytes)) {
            throw new IllegalArgumentException("通行密钥签名校验失败。");
        }

        final long newSignCount = readUint32BE(authData, 33);
        final long storedSignCount = matchedCred.signCount();
        if (storedSignCount > 0L || newSignCount > 0L) {
            if (storedSignCount > 0L && newSignCount <= storedSignCount) {
                throw new IllegalArgumentException("通行密钥签名计数器异常，疑似克隆凭证，已拒绝登录。");
            }
        }

        final long updatedSignCount = (storedSignCount > 0L || newSignCount > 0L) ? newSignCount : 0L;
        final StoredCredential updatedCred = new StoredCredential(
                matchedCred.id(),
                matchedCred.userId(),
                matchedCred.credentialId(),
                matchedCred.publicKeySpki(),
                matchedCred.alg(),
                updatedSignCount,
                matchedCred.name(),
                matchedCred.createdAt(),
                now,
                matchedCred.cachedPublicKey
        );
        final List<StoredCredential> nextList = new ArrayList<>(allCredentials);
        nextList.set(matchedIndex, updatedCred);
        cachedCredentials = List.copyOf(nextList);
        metadataDirty.set(true);

        return Solos.clone(user);
    }

    private ChallengeEntry consumeChallenge(final JSONObject payload,
                                            final JSONObject credentialObj,
                                            final JSONObject clientData,
                                            final String expectedPurpose,
                                            final long now) {
        final String challengeId = firstNonBlank(
                payload.optString("challengeId"),
                credentialObj.optString("challengeId")
        );
        ChallengeEntry entry = null;
        if (!challengeId.isEmpty()) {
            entry = challenges.remove(challengeId);
        } else {
            final String clientChallenge = clientData.optString("challenge");
            if (StringUtils.isNotBlank(clientChallenge)) {
                final byte[] decoded = decodeBase64Url(clientChallenge, "challenge");
                String matchedKey = null;
                for (final Map.Entry<String, ChallengeEntry> item : challenges.entrySet()) {
                    if (expectedPurpose.equals(item.getValue().purpose())
                            && MessageDigest.isEqual(decoded, item.getValue().challengeBytes())) {
                        matchedKey = item.getKey();
                        break;
                    }
                }
                if (matchedKey != null) {
                    entry = challenges.remove(matchedKey);
                }
            }
        }
        if (entry == null || entry.expiresAt() <= now || !expectedPurpose.equals(entry.purpose())) {
            throw new IllegalArgumentException("通行密钥验证会话不存在或已过期，请刷新后重试。");
        }
        return entry;
    }

    private static JSONObject parseClientDataJson(final byte[] clientDataBytes) {
        if (clientDataBytes.length == 0 || clientDataBytes.length > 16_384) {
            throw new IllegalArgumentException("clientDataJSON 长度无效。");
        }
        try {
            return new JSONObject(new String(clientDataBytes, StandardCharsets.UTF_8));
        } catch (final Exception e) {
            throw new IllegalArgumentException("clientDataJSON 解析失败。");
        }
    }

    private static void verifyClientData(final JSONObject clientData,
                                         final String expectedType,
                                         final ChallengeEntry challengeEntry) {
        if (!expectedType.equals(clientData.optString("type"))) {
            throw new IllegalArgumentException("WebAuthn 验证类型不匹配。");
        }
        final byte[] actualChallenge = decodeBase64Url(clientData.optString("challenge"), "clientDataJSON.challenge");
        if (!MessageDigest.isEqual(actualChallenge, challengeEntry.challengeBytes())) {
            throw new IllegalArgumentException("WebAuthn Challenge 校验失败。");
        }
        final String actualOrigin = validateOrigin(clientData.optString("origin"));
        if (!challengeEntry.expectedOrigin().equals(actualOrigin)) {
            throw new IllegalArgumentException("WebAuthn Origin 不匹配。");
        }
    }

    private static void verifyRpIdHash(final byte[] authData, final String expectedRpId) {
        final byte[] expectedHash = sha256(expectedRpId.getBytes(StandardCharsets.UTF_8));
        final byte[] actualHash = Arrays.copyOfRange(authData, 0, 32);
        if (!MessageDigest.isEqual(actualHash, expectedHash)) {
            throw new IllegalArgumentException("WebAuthn RP ID 哈希不匹配。");
        }
    }

    private static ParsedCoseKey parseCosePublicKey(final Map<?, ?> coseMap) throws GeneralSecurityException {
        final long kty = cborAsLong(cborMapGet(coseMap, 1L), "COSE kty");
        final long alg = cborAsLong(cborMapGet(coseMap, 3L), "COSE alg");

        if (kty == 2L && alg == ALG_ES256) {
            final long crv = cborAsLong(cborMapGet(coseMap, -1L), "COSE EC crv");
            if (crv != 1L) {
                throw new IllegalArgumentException("仅支持 P-256 (secp256r1) 椭圆曲线通行密钥。");
            }
            final byte[] x = cborAsBytes(cborMapGet(coseMap, -2L), "COSE EC x");
            final byte[] y = cborAsBytes(cborMapGet(coseMap, -3L), "COSE EC y");
            if (x.length != 32 || y.length != 32) {
                throw new IllegalArgumentException("EC P-256 公钥坐标长度必须为 32 字节。");
            }

            final AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            final ECParameterSpec ecSpec = params.getParameterSpec(ECParameterSpec.class);
            final EllipticCurve curve = ecSpec.getCurve();
            final BigInteger p = ((ECFieldFp) curve.getField()).getP();
            final BigInteger xInt = new BigInteger(1, x);
            final BigInteger yInt = new BigInteger(1, y);
            if (xInt.signum() < 0 || xInt.compareTo(p) >= 0 || yInt.signum() < 0 || yInt.compareTo(p) >= 0) {
                throw new IllegalArgumentException("无效的 EC 公钥坐标范围。");
            }
            final BigInteger lhs = yInt.modPow(BigInteger.TWO, p);
            final BigInteger rhs = xInt.modPow(BigInteger.valueOf(3), p)
                    .add(curve.getA().multiply(xInt))
                    .add(curve.getB())
                    .mod(p);
            if (!lhs.equals(rhs)) {
                throw new IllegalArgumentException("EC 公钥坐标不在 P-256 曲线上。");
            }

            final ECPoint point = new ECPoint(xInt, yInt);
            final PublicKey publicKey = KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, ecSpec));
            return new ParsedCoseKey(publicKey, ALG_ES256);
        }

        if (kty == 3L && alg == ALG_RS256) {
            final byte[] n = cborAsBytes(cborMapGet(coseMap, -1L), "COSE RSA n");
            final byte[] e = cborAsBytes(cborMapGet(coseMap, -2L), "COSE RSA e");
            if (n.length < 256 || n.length > 1024 || e.length < 1 || e.length > 16) {
                throw new IllegalArgumentException("RSA 公钥长度无效（需至少 2048 位）。");
            }
            final BigInteger modulus = new BigInteger(1, n);
            final BigInteger exponent = new BigInteger(1, e);
            if (modulus.bitLength() < 2048 || exponent.compareTo(BigInteger.valueOf(3)) < 0 || !exponent.testBit(0)) {
                throw new IllegalArgumentException("RSA 公钥参数不安全或无效。");
            }
            final PublicKey publicKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(modulus, exponent));
            return new ParsedCoseKey(publicKey, ALG_RS256);
        }

        throw new IllegalArgumentException("不支持的通行密钥算法（仅支持 ES256 与 RS256）。");
    }

    private static boolean verifyAssertionSignature(final StoredCredential credential,
                                                    final byte[] authData,
                                                    final byte[] clientDataBytes,
                                                    final byte[] signatureBytes) {
        try {
            final String sigAlgorithm;
            if (credential.alg() == ALG_ES256) {
                sigAlgorithm = "SHA256withECDSA";
            } else if (credential.alg() == ALG_RS256) {
                sigAlgorithm = "SHA256withRSA";
            } else {
                return false;
            }
            final PublicKey publicKey = credential.resolvePublicKey();
            final byte[] clientDataHash = sha256(clientDataBytes);
            final Signature verifier = Signature.getInstance(sigAlgorithm);
            verifier.initVerify(publicKey);
            verifier.update(authData);
            verifier.update(clientDataHash);
            return verifier.verify(signatureBytes);
        } catch (final Exception e) {
            LOGGER.log(Level.DEBUG, "Passkey signature verification failed", e);
            return false;
        }
    }

    private List<StoredCredential> loadCredentials() {
        final List<StoredCredential> cached = cachedCredentials;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (cachedCredentials == null) {
                final List<StoredCredential> parsed = new ArrayList<>();
                if (optionQueryService != null) {
                    final String raw = OptionDocuments.read(
                            optionQueryService,
                            OPTION_ID,
                            "{\"credentials\":[]}"
                    );
                    final JSONObject doc = new JSONObject(raw);
                    final JSONArray arr = doc.optJSONArray("credentials");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            final JSONObject item = arr.optJSONObject(i);
                            if (item == null) {
                                continue;
                            }
                            final String id = item.optString("id").trim();
                            final String userId = item.optString("userId").trim();
                            final String credentialId = item.optString("credentialId").trim();
                            final String publicKeySpki = item.optString("publicKeySpki").trim();
                            final int alg = item.optInt("alg", 0);
                            if (id.isEmpty() || userId.isEmpty() || credentialId.isEmpty() || publicKeySpki.isEmpty()) {
                                continue;
                            }
                            if (alg != ALG_ES256 && alg != ALG_RS256) {
                                continue;
                            }
                            parsed.add(new StoredCredential(
                                    id,
                                    userId,
                                    credentialId,
                                    publicKeySpki,
                                    alg,
                                    Math.max(0L, item.optLong("signCount", 0L)),
                                    sanitizeDeviceNameOrDefault(item.optString("name"), "通行密钥"),
                                    Math.max(0L, item.optLong("createdAt", 0L)),
                                    Math.max(0L, item.optLong("lastUsedAt", 0L))
                            ));
                        }
                    }
                }
                cachedCredentials = List.copyOf(parsed);
            }
            return cachedCredentials;
        }
    }

    private synchronized void persistCredentials(final List<StoredCredential> next) throws Exception {
        final List<StoredCredential> immutable = List.copyOf(next);
        if (optionMgmtService != null) {
            final JSONArray arr = new JSONArray();
            for (final StoredCredential cred : immutable) {
                arr.put(cred.toStorageJson());
            }
            final JSONObject doc = new JSONObject()
                    .put("version", 1)
                    .put("updatedAt", clock.getAsLong())
                    .put("credentials", arr);
            optionMgmtService.storeDocument(OPTION_ID, OPTION_CATEGORY, doc.toString());
        }
        cachedCredentials = immutable;
        metadataDirty.set(false);
    }

    private JSONObject findUserById(final String userId) {
        if (StringUtils.isBlank(userId)) {
            return null;
        }
        try {
            if (userRepository != null) {
                return userRepository.get(userId);
            }
            if (userQueryService != null) {
                final JSONObject wrapper = userQueryService.getUser(userId);
                return wrapper == null ? null : wrapper.optJSONObject(User.USER);
            }
        } catch (final Exception e) {
            LOGGER.log(Level.WARN, "Finds user by id for passkey auth failed", e);
        }
        return null;
    }

    private JSONObject findUserByName(final String username) {
        if (StringUtils.isBlank(username)) {
            return null;
        }
        try {
            if (userRepository != null) {
                return userRepository.getByUserName(username);
            }
            if (userQueryService != null) {
                return userQueryService.getUserByName(username);
            }
        } catch (final Exception e) {
            LOGGER.log(Level.WARN, "Finds user by username for passkey auth failed", e);
        }
        return null;
    }

    private static boolean isEligibleConsoleUser(final JSONObject user) {
        if (user == null) {
            return false;
        }
        if (user.optInt(UserExt.USER_STATUS, UserExt.USER_STATUS_C_VALID) != UserExt.USER_STATUS_C_VALID) {
            return false;
        }
        final String role = user.optString(User.USER_ROLE);
        if (StringUtils.isBlank(role) || Role.VISITOR_ROLE.equals(role)) {
            return false;
        }
        return Role.ADMIN_ROLE.equals(role) || Role.DEFAULT_ROLE.equals(role);
    }

    private String resolveRpName() {
        if (optionQueryService != null) {
            final JSONObject titleOpt = optionQueryService.getOptionById(Option.ID_C_BLOG_TITLE);
            if (titleOpt != null) {
                final String cleaned = titleOpt.optString(Option.OPTION_VALUE)
                        .replaceAll("\\p{Cntrl}", "")
                        .strip();
                if (!cleaned.isEmpty()) {
                    return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
                }
            }
        }
        return "Volo";
    }

    private void storeChallenge(final ChallengeEntry entry) {
        pruneChallenges(clock.getAsLong());
        final var iterator = challenges.keySet().iterator();
        while (challenges.size() >= MAX_CHALLENGES && iterator.hasNext()) {
            final String oldest = iterator.next();
            iterator.remove();
            pendingDeviceNames.remove(oldest);
        }
        challenges.put(entry.challengeId(), entry);
    }

    private void pruneChallenges(final long now) {
        final var iterator = challenges.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<String, ChallengeEntry> item = iterator.next();
            if (item.getValue().expiresAt() <= now) {
                pendingDeviceNames.remove(item.getKey());
                iterator.remove();
            }
        }
    }

    private static String validateRpId(final String rpId) {
        if (StringUtils.isBlank(rpId)) {
            throw new IllegalArgumentException("RP ID 不能为空。");
        }
        final String normalized = rpId.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 253 || normalized.indexOf('/') >= 0 || normalized.indexOf(':') >= 0) {
            throw new IllegalArgumentException("RP ID 格式无效。");
        }
        return normalized;
    }

    private static String validateOrigin(final String origin) {
        if (StringUtils.isBlank(origin)) {
            throw new IllegalArgumentException("Origin 不能为空。");
        }
        final URI uri;
        try {
            uri = URI.create(origin.trim());
        } catch (final Exception e) {
            throw new IllegalArgumentException("Origin 格式无效。");
        }
        final String scheme = uri.getScheme();
        final String authority = uri.getRawAuthority();
        if (StringUtils.isBlank(scheme) || StringUtils.isBlank(authority)) {
            throw new IllegalArgumentException("Origin 缺少协议或主机名。");
        }
        return scheme.toLowerCase(Locale.ROOT) + "://" + authority.toLowerCase(Locale.ROOT);
    }

    private static String sanitizeDeviceName(final String raw) {
        if (raw == null) {
            return "";
        }
        final String cleaned = raw.replaceAll("\\p{Cntrl}", "").strip();
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }

    private static String sanitizeDeviceNameOrDefault(final String raw, final String fallback) {
        final String cleaned = sanitizeDeviceName(raw);
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    private static String firstNonBlank(final String... candidates) {
        for (final String c : candidates) {
            if (StringUtils.isNotBlank(c)) {
                return c.trim();
            }
        }
        return "";
    }

    public static String encodeBase64Url(final byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static byte[] decodeBase64Url(final String input, final String fieldName) {
        if (StringUtils.isBlank(input) || input.length() > 131_072) {
            throw new IllegalArgumentException("缺少有效的 " + fieldName + " 参数。");
        }
        String normalized = input.trim().replace('+', '-').replace('/', '_');
        int end = normalized.length();
        while (end > 0 && normalized.charAt(end - 1) == '=') {
            end--;
        }
        normalized = normalized.substring(0, end);
        if (normalized.isEmpty()) {
            return new byte[0];
        }
        try {
            return Base64.getUrlDecoder().decode(normalized);
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException(fieldName + " Base64URL 解码失败。");
        }
    }

    private static long readUint32BE(final byte[] bytes, final int offset) {
        return ((bytes[offset] & 0xFFL) << 24)
                | ((bytes[offset + 1] & 0xFFL) << 16)
                | ((bytes[offset + 2] & 0xFFL) << 8)
                | (bytes[offset + 3] & 0xFFL);
    }

    private static byte[] sha256(final byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (final GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String randomToken() {
        final byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return encodeBase64Url(bytes);
    }

    private static String randomHex(final int byteCount) {
        final byte[] bytes = new byte[byteCount];
        RANDOM.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }

    private static Object cborMapGet(final Map<?, ?> map, final long key) {
        final Object direct = map.get(key);
        if (direct != null) {
            return direct;
        }
        if (key >= Integer.MIN_VALUE && key <= Integer.MAX_VALUE) {
            return map.get((int) key);
        }
        return null;
    }

    private static long cborAsLong(final Object value, final String label) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalArgumentException(label + " 缺失或类型无效。");
    }

    private static byte[] cborAsBytes(final Object value, final String label) {
        if (value instanceof byte[] bytes) {
            return bytes;
        }
        throw new IllegalArgumentException(label + " 缺失或类型无效。");
    }

    /**
     * Decodes a complete definite-length RFC 8949 CBOR item from {@code data} (bounded to 64 KB).
     */
    public static Object decodeCbor(final byte[] data) {
        if (data == null || data.length == 0 || data.length > MAX_CBOR_BYTES) {
            throw new IllegalArgumentException("CBOR 数据为空或超出 64KB 限制。");
        }
        final CborDecoder decoder = new CborDecoder(data, 0);
        final Object item = decoder.readItem(0);
        if (decoder.remaining() != 0) {
            throw new IllegalArgumentException("CBOR 数据包含多余尾部字节。");
        }
        return item;
    }

    /**
     * Minimal, DoS-hardened definite-length RFC 8949 CBOR decoder supporting major types 0..7.
     */
    static final class CborDecoder {
        private static final int MAX_DEPTH = 16;
        private final byte[] data;
        private int pos;

        CborDecoder(final byte[] data, final int offset) {
            if (data == null || data.length > MAX_CBOR_BYTES || offset < 0 || offset > data.length) {
                throw new IllegalArgumentException("CBOR 输入越界或超出限制。");
            }
            this.data = data;
            this.pos = offset;
        }

        int remaining() {
            return data.length - pos;
        }

        Object readItem(final int depth) {
            if (depth > MAX_DEPTH) {
                throw new IllegalArgumentException("CBOR 嵌套层级过深。");
            }
            if (pos >= data.length) {
                throw new IllegalArgumentException("CBOR 数据意外截断。");
            }
            final int initial = data[pos++] & 0xFF;
            final int major = initial >>> 5;
            final int ai = initial & 0x1F;

            return switch (major) {
                case 0 -> readArgument(ai);
                case 1 -> {
                    final long arg = readArgument(ai);
                    if (arg < 0L) {
                        throw new IllegalArgumentException("CBOR 负整数溢出。");
                    }
                    yield -1L - arg;
                }
                case 2 -> {
                    final int len = readBoundedLength(ai, remaining());
                    final byte[] slice = Arrays.copyOfRange(data, pos, pos + len);
                    pos += len;
                    yield slice;
                }
                case 3 -> {
                    final int len = readBoundedLength(ai, remaining());
                    final String text = new String(data, pos, len, StandardCharsets.UTF_8);
                    pos += len;
                    yield text;
                }
                case 4 -> {
                    final int len = readBoundedLength(ai, remaining());
                    final List<Object> list = new ArrayList<>(Math.min(len, 64));
                    for (int i = 0; i < len; i++) {
                        list.add(readItem(depth + 1));
                    }
                    yield list;
                }
                case 5 -> {
                    final int len = readBoundedLength(ai, remaining() / 2);
                    final Map<Object, Object> map = new LinkedHashMap<>(Math.min(len * 2, 64));
                    for (int i = 0; i < len; i++) {
                        final Object k = readItem(depth + 1);
                        final Object v = readItem(depth + 1);
                        map.put(k, v);
                    }
                    yield map;
                }
                case 6 -> {
                    readArgument(ai); // semantic tag number
                    yield readItem(depth + 1);
                }
                case 7 -> readSimpleOrFloat(ai);
                default -> throw new IllegalArgumentException("不支持的 CBOR 主类型: " + major);
            };
        }

        private long readArgument(final int ai) {
            if (ai < 24) {
                return ai;
            }
            return switch (ai) {
                case 24 -> {
                    ensureAvailable(1);
                    yield data[pos++] & 0xFFL;
                }
                case 25 -> {
                    ensureAvailable(2);
                    final long v = ((data[pos] & 0xFFL) << 8) | (data[pos + 1] & 0xFFL);
                    pos += 2;
                    yield v;
                }
                case 26 -> {
                    ensureAvailable(4);
                    final long v = ((data[pos] & 0xFFL) << 24)
                            | ((data[pos + 1] & 0xFFL) << 16)
                            | ((data[pos + 2] & 0xFFL) << 8)
                            | (data[pos + 3] & 0xFFL);
                    pos += 4;
                    yield v;
                }
                case 27 -> {
                    ensureAvailable(8);
                    long v = 0L;
                    for (int i = 0; i < 8; i++) {
                        v = (v << 8) | (data[pos + i] & 0xFFL);
                    }
                    pos += 8;
                    if (v < 0L) {
                        throw new IllegalArgumentException("CBOR 64 位无符号整数超出范围。");
                    }
                    yield v;
                }
                default -> throw new IllegalArgumentException("不支持的 CBOR 长度标识: " + ai);
            };
        }

        private int readBoundedLength(final int ai, final int maxAllowed) {
            final long len = readArgument(ai);
            if (len < 0L || len > maxAllowed) {
                throw new IllegalArgumentException("CBOR 元素长度超出可用缓冲区限制。");
            }
            return (int) len;
        }

        private Object readSimpleOrFloat(final int ai) {
            if (ai < 20) {
                return ai;
            }
            return switch (ai) {
                case 20 -> Boolean.FALSE;
                case 21 -> Boolean.TRUE;
                case 22, 23 -> null;
                case 24 -> {
                    ensureAvailable(1);
                    yield data[pos++] & 0xFF;
                }
                case 25 -> {
                    ensureAvailable(2);
                    pos += 2;
                    yield 0.0f;
                }
                case 26 -> {
                    ensureAvailable(4);
                    final int bits = ((data[pos] & 0xFF) << 24)
                            | ((data[pos + 1] & 0xFF) << 16)
                            | ((data[pos + 2] & 0xFF) << 8)
                            | (data[pos + 3] & 0xFF);
                    pos += 4;
                    yield Float.intBitsToFloat(bits);
                }
                case 27 -> {
                    ensureAvailable(8);
                    long bits = 0L;
                    for (int i = 0; i < 8; i++) {
                        bits = (bits << 8) | (data[pos + i] & 0xFFL);
                    }
                    pos += 8;
                    yield Double.longBitsToDouble(bits);
                }
                default -> throw new IllegalArgumentException("不支持的 CBOR 简单值标识: " + ai);
            };
        }

        private void ensureAvailable(final int needed) {
            if (pos + needed > data.length) {
                throw new IllegalArgumentException("CBOR 数据意外截断。");
            }
        }
    }
}
