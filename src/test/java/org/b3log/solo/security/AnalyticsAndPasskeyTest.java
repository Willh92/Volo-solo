/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.security;

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.b3log.latke.Keys;
import org.b3log.latke.http.Request;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.model.Role;
import org.b3log.latke.model.User;
import org.b3log.latke.repository.Transaction;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Option;
import org.b3log.solo.model.UserExt;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.repository.OptionRepository;
import org.b3log.solo.repository.UserRepository;
import org.b3log.solo.service.AnalyticsTrendService;
import org.b3log.solo.service.OptionMgmtService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.service.PasskeyService;
import org.b3log.solo.service.UserQueryService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class AnalyticsAndPasskeyTest {

    @BeforeClass
    public void configure() throws Exception {
        LatkeTestSupport.configure();
        final var local = new java.util.Properties();
        local.setProperty("runtimeDatabase", "NONE");
        org.b3log.latke.Latkes.setLocalProps(local);
    }

    @AfterClass
    public void restore() throws Exception {
        LatkeTestSupport.configure();
    }

    @Test
    public void analyticsTrendAggregatesZeroPiiDailyBucketsAndPersistsCleanly() throws Exception {
        final AtomicLong now = new AtomicLong(1_730_700_000_000L);
        final Map<String, JSONObject> optionStore = new HashMap<>();
        final OptionRepository optionRepo = inMemoryOptionRepo(optionStore);

        final OptionQueryService optionQuery = new OptionQueryService();
        setField(optionQuery, "optionRepository", optionRepo);
        final OptionMgmtService optionMgmt = new OptionMgmtService();
        setField(optionMgmt, "optionRepository", optionRepo);

        final AnalyticsTrendService analytics = new AnalyticsTrendService(now::get);
        setField(analytics, "optionQueryService", optionQuery);
        setField(analytics, "optionMgmtService", optionMgmt);
        setField(analytics, "articleRepository", new ArticleRepository() {
            @Override
            public JSONObject get(final String id) {
                if ("art-1".equals(id)) {
                    return new JSONObject()
                            .put(Keys.OBJECT_ID, "art-1")
                            .put(Article.ARTICLE_TITLE, "Zero-PII Analytics Design")
                            .put(Article.ARTICLE_PERMALINK, "/articles/analytics.html")
                            .put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED);
                }
                return null;
            }
        });

        // Day 1: 2 PVs (1 UV) from www.github.com + 1 PV from self-referer (must be ignored)
        analytics.recordSiteVisit(requestWithReferer("https://www.github.com/Willh92/Volo-solo"), true);
        analytics.recordSiteVisit(requestWithReferer("https://github.com/trending"), false);
        analytics.recordSiteVisit(requestWithReferer("http://localhost:8080/articles/analytics.html"), false);
        analytics.recordArticleView("art-1");
        analytics.recordArticleView("art-1");

        // Advance 1 day -> 1 PV + 1 UV from news.ycombinator.com
        now.addAndGet(86_400_000L);
        analytics.recordSiteVisit(requestWithReferer("https://news.ycombinator.com/item?id=1"), true);
        analytics.recordArticleView("art-1");

        analytics.flushIfDirty();
        Assert.assertTrue(optionStore.containsKey(Option.ID_C_VOLO_DAILY_STATS));

        // Reload from storage into fresh in-memory state and query 7-day trends
        analytics.reloadFromStorage();
        final JSONObject trends = analytics.queryTrends(7);
        Assert.assertEquals(trends.getInt("days"), 7);
        Assert.assertEquals(trends.getLong("totalPv"), 4L);
        Assert.assertEquals(trends.getLong("totalUv"), 2L);

        final JSONArray topArticles = trends.getJSONArray("topArticles");
        Assert.assertEquals(topArticles.length(), 1);
        Assert.assertEquals(topArticles.getJSONObject(0).getString("oId"), "art-1");
        Assert.assertEquals(topArticles.getJSONObject(0).getLong("views"), 3L);

        final JSONArray topReferers = trends.getJSONArray("topReferers");
        Assert.assertEquals(topReferers.length(), 2);
        Assert.assertEquals(topReferers.getJSONObject(0).getString("domain"), "github.com",
                "www. prefix must normalize to canonical domain and self-referer must be excluded");
        Assert.assertEquals(topReferers.getJSONObject(0).getLong("count"), 2L);
    }

    @Test
    public void passkeyWebAuthnEs256CeremonyVerifiesAndRejectsReplayOriginAndClones() throws Exception {
        final AtomicLong now = new AtomicLong(1_730_800_000_000L);
        final Map<String, JSONObject> optionStore = new HashMap<>();
        final OptionRepository optionRepo = inMemoryOptionRepo(optionStore);

        final OptionQueryService optionQuery = new OptionQueryService();
        setField(optionQuery, "optionRepository", optionRepo);
        final OptionMgmtService optionMgmt = new OptionMgmtService();
        setField(optionMgmt, "optionRepository", optionRepo);

        final JSONObject adminUser = new JSONObject()
                .put(Keys.OBJECT_ID, "u-admin-1")
                .put(User.USER_NAME, "voloadmin")
                .put(User.USER_EMAIL, "admin@example.com")
                .put(User.USER_ROLE, Role.ADMIN_ROLE)
                .put(UserExt.USER_STATUS, UserExt.USER_STATUS_C_VALID);

        final PasskeyService passkeyService = new PasskeyService(now::get);
        setField(passkeyService, "optionQueryService", optionQuery);
        setField(passkeyService, "optionMgmtService", optionMgmt);
        setField(passkeyService, "userRepository", new UserRepository() {
            @Override
            public JSONObject get(final String id) {
                return "u-admin-1".equals(id) ? new JSONObject(adminUser.toString()) : null;
            }
            @Override
            public JSONObject getByUserName(final String name) {
                return "voloadmin".equals(name) ? new JSONObject(adminUser.toString()) : null;
            }
        });
        setField(passkeyService, "userQueryService", new UserQueryService() {
            @Override
            public JSONObject getUserByName(final String name) {
                return "voloadmin".equals(name) ? new JSONObject(adminUser.toString()) : null;
            }
        });

        final String rpId = "blog.example.com";
        final String origin = "https://blog.example.com";

        // Generate real EC P-256 authenticator key pair
        final KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        final KeyPair kp = kpg.generateKeyPair();
        final byte[] credentialIdBytes = "cred-macbook-touch-id-01".getBytes(StandardCharsets.UTF_8);
        final String credentialIdB64u = b64u(credentialIdBytes);

        // 1. Registration ceremony
        final JSONObject regOpts = passkeyService.startRegistration(adminUser, "MacBook Touch ID", rpId, origin);
        final String regChallengeId = regOpts.getString("challengeId");
        final String regChallenge = regOpts.getString("challenge");

        final byte[] regClientData = new JSONObject()
                .put("type", "webauthn.create")
                .put("challenge", regChallenge)
                .put("origin", origin)
                .toString().getBytes(StandardCharsets.UTF_8);
        final byte[] attestationObj = buildNoneAttestationObject(
                rpId, 1, credentialIdBytes, (ECPublicKey) kp.getPublic());

        final JSONObject regPayload = new JSONObject()
                .put("challengeId", regChallengeId)
                .put("name", "MacBook Touch ID")
                .put("credential", new JSONObject()
                        .put("id", credentialIdB64u)
                        .put("rawId", credentialIdB64u)
                        .put("type", "public-key")
                        .put("response", new JSONObject()
                                .put("clientDataJSON", b64u(regClientData))
                                .put("attestationObject", b64u(attestationObj))));

        final JSONObject regDone = passkeyService.finishRegistration(adminUser, regPayload);
        Assert.assertEquals(regDone.getJSONArray("credentials").length(), 1);
        Assert.assertTrue(passkeyService.hasAnyCredentials());

        // 2. Passwordless authentication ceremony (signCount advances 1 -> 2)
        final JSONObject authOpts = passkeyService.startAuthentication("voloadmin", rpId, origin);
        final JSONObject validLoginPayload = buildSignedAssertionPayload(
                authOpts.getString("challengeId"),
                authOpts.getString("challenge"),
                origin,
                rpId,
                2,
                credentialIdB64u,
                kp
        );
        final JSONObject loggedInUser = passkeyService.finishAuthentication(validLoginPayload);
        Assert.assertEquals(loggedInUser.getString(Keys.OBJECT_ID), "u-admin-1");

        // 3. Replaying the exact same login assertion must fail (challenge already consumed)
        Assert.expectThrows(IllegalArgumentException.class,
                () -> passkeyService.finishAuthentication(validLoginPayload));

        // 4. Phishing / wrong origin must fail
        final JSONObject phishingOpts = passkeyService.startAuthentication("voloadmin", rpId, origin);
        final JSONObject phishingPayload = buildSignedAssertionPayload(
                phishingOpts.getString("challengeId"),
                phishingOpts.getString("challenge"),
                "https://evil-phish.example.com",
                rpId,
                3,
                credentialIdB64u,
                kp
        );
        Assert.expectThrows(IllegalArgumentException.class,
                () -> passkeyService.finishAuthentication(phishingPayload));

        // 5. Cloned authenticator (signCount = 2 <= storedSignCount = 2) must be rejected!
        final JSONObject cloneOpts = passkeyService.startAuthentication("voloadmin", rpId, origin);
        final JSONObject clonePayload = buildSignedAssertionPayload(
                cloneOpts.getString("challengeId"),
                cloneOpts.getString("challenge"),
                origin,
                rpId,
                2,
                credentialIdB64u,
                kp
        );
        Assert.expectThrows(IllegalArgumentException.class,
                () -> passkeyService.finishAuthentication(clonePayload));
    }

    private static JSONObject buildSignedAssertionPayload(final String challengeId,
                                                          final String challengeB64u,
                                                          final String clientOrigin,
                                                          final String rpId,
                                                          final int signCount,
                                                          final String credentialIdB64u,
                                                          final KeyPair kp) throws Exception {
        final byte[] clientData = new JSONObject()
                .put("type", "webauthn.get")
                .put("challenge", challengeB64u)
                .put("origin", clientOrigin)
                .toString().getBytes(StandardCharsets.UTF_8);

        final byte[] rpIdHash = MessageDigest.getInstance("SHA-256")
                .digest(rpId.getBytes(StandardCharsets.UTF_8));
        final ByteBuffer authBuf = ByteBuffer.allocate(37);
        authBuf.put(rpIdHash);
        authBuf.put((byte) 0x05); // UP | UV
        authBuf.putInt(signCount);
        final byte[] authData = authBuf.array();

        final byte[] clientDataHash = MessageDigest.getInstance("SHA-256").digest(clientData);
        final ByteArrayOutputStream signedData = new ByteArrayOutputStream(authData.length + 32);
        signedData.write(authData);
        signedData.write(clientDataHash);

        final Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(kp.getPrivate());
        sig.update(signedData.toByteArray());
        final byte[] derSignature = sig.sign();

        return new JSONObject()
                .put("challengeId", challengeId)
                .put("credential", new JSONObject()
                        .put("id", credentialIdB64u)
                        .put("rawId", credentialIdB64u)
                        .put("type", "public-key")
                        .put("response", new JSONObject()
                                .put("clientDataJSON", b64u(clientData))
                                .put("authenticatorData", b64u(authData))
                                .put("signature", b64u(derSignature))));
    }

    private static byte[] buildNoneAttestationObject(final String rpId,
                                                     final int signCount,
                                                     final byte[] credId,
                                                     final ECPublicKey pub) throws Exception {
        final byte[] rpIdHash = MessageDigest.getInstance("SHA-256")
                .digest(rpId.getBytes(StandardCharsets.UTF_8));
        final byte[] x = toFixed32(pub.getW().getAffineX());
        final byte[] y = toFixed32(pub.getW().getAffineY());

        // Encode COSE_Key map(5): {1: 2, 3: -7, -1: 1, -2: bstr(32), -3: bstr(32)}
        final ByteArrayOutputStream cose = new ByteArrayOutputStream();
        cose.write(0xA5); // map(5)
        cose.write(0x01); cose.write(0x02); // 1: 2 (EC2)
        cose.write(0x03); cose.write(0x26); // 3: -7 (ES256 -> -1 - 6 = 0x26)
        cose.write(0x20); cose.write(0x01); // -1: 1 (P-256)
        cose.write(0x21); writeCborBytes(cose, x); // -2: x
        cose.write(0x22); writeCborBytes(cose, y); // -3: y
        final byte[] coseBytes = cose.toByteArray();

        final ByteBuffer authData = ByteBuffer.allocate(32 + 1 + 4 + 16 + 2 + credId.length + coseBytes.length);
        authData.put(rpIdHash);
        authData.put((byte) 0x45); // UP (0x01) | UV (0x04) | AT (0x40)
        authData.putInt(signCount);
        authData.put(new byte[16]); // AAGUID
        authData.putShort((short) credId.length);
        authData.put(credId);
        authData.put(coseBytes);

        // Encode attestationObject map(3): {"fmt": "none", "attStmt": {}, "authData": bstr}
        final ByteArrayOutputStream att = new ByteArrayOutputStream();
        att.write(0xA3); // map(3)
        writeCborText(att, "fmt");
        writeCborText(att, "none");
        writeCborText(att, "attStmt");
        att.write(0xA0); // map(0)
        writeCborText(att, "authData");
        writeCborBytes(att, authData.array());
        return att.toByteArray();
    }

    private static byte[] toFixed32(final BigInteger coord) {
        final byte[] raw = coord.toByteArray();
        if (raw.length == 32) {
            return raw;
        }
        if (raw.length > 32) {
            return Arrays.copyOfRange(raw, raw.length - 32, raw.length);
        }
        final byte[] padded = new byte[32];
        System.arraycopy(raw, 0, padded, 32 - raw.length, raw.length);
        return padded;
    }

    private static void writeCborText(final ByteArrayOutputStream out, final String s) throws Exception {
        final byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeCborHeader(out, 3, b.length);
        out.write(b);
    }

    private static void writeCborBytes(final ByteArrayOutputStream out, final byte[] b) throws Exception {
        writeCborHeader(out, 2, b.length);
        out.write(b);
    }

    private static void writeCborHeader(final ByteArrayOutputStream out, final int major, final int len) {
        final int prefix = major << 5;
        if (len < 24) {
            out.write(prefix | len);
        } else if (len < 256) {
            out.write(prefix | 24);
            out.write(len);
        } else {
            out.write(prefix | 25);
            out.write((len >>> 8) & 0xFF);
            out.write(len & 0xFF);
        }
    }

    private static String b64u(final byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static RequestContext requestWithReferer(final String referer) {
        final DefaultFullHttpRequest raw = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        if (referer != null) {
            raw.headers().set(HttpHeaderNames.REFERER, referer);
        }
        final DefaultFullHttpResponse rawResp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, io.netty.handler.codec.http.HttpResponseStatus.OK);
        return new RequestContext(new Request(null, raw), new org.b3log.latke.http.Response(null, rawResp));
    }

    private static OptionRepository inMemoryOptionRepo(final Map<String, JSONObject> store) {
        return new OptionRepository() {
            @Override
            public Transaction beginTransaction() {
                return new Transaction() {
                    private boolean active = true;
                    @Override public String getId() { return "tx"; }
                    @Override public void commit() { active = false; }
                    @Override public void rollback() { active = false; }
                    @Override public boolean isActive() { return active; }
                };
            }
            @Override
            public JSONObject get(final String id) {
                final JSONObject row = store.get(id);
                return row == null ? null : new JSONObject(row.toString());
            }
            @Override
            public String add(final JSONObject o) {
                final String id = o.getString(Keys.OBJECT_ID);
                store.put(id, new JSONObject(o.toString()));
                return id;
            }
            @Override
            public void update(final String id, final JSONObject o, final String... names) {
                store.put(id, new JSONObject(o.toString()));
            }
            @Override
            public void remove(final String id) {
                store.remove(id);
            }
        };
    }

    private static void setField(final Object target, final String name, final Object value) throws Exception {
        final Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
