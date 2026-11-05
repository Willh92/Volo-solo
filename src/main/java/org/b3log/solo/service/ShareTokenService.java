/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.service;

import org.apache.commons.lang3.StringUtils;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.service.ServiceException;
import org.b3log.latke.service.annotation.Service;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Option;
import org.json.JSONObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Stateless HMAC-SHA256 expiring preview share token service ({@code ?preview=<exp>.<sig>}).
 * Allows time-limited read access to draft or password-protected articles without exposing passwords.
 *
 * @author <a href="https://github.com/Willh92">Willh92</a>
 * @since 3.0.0
 */
@Service
public class ShareTokenService {

    private static final Logger LOGGER = Logger.getLogger(ShareTokenService.class);

    public static final String PREVIEW_PARAM = "preview";
    public static final String PREVIEW_ACCESS_ATTR = "voloPreviewTokenAccess";
    public static final String OPTION_ID_SECRET = "voloShareSecret";
    private static final String OPTION_CATEGORY_SECRET = "voloInternal";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Inject
    private OptionQueryService optionQueryService;

    @Inject
    private OptionMgmtService optionMgmtService;

    private volatile String cachedSecret;

    public long resolveTtlSeconds(final String durationKey) {
        if (durationKey == null) {
            return 24L * 3600L;
        }
        return switch (durationKey.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "7d" -> 7L * 24L * 3600L;
            case "30d" -> 30L * 24L * 3600L;
            default -> 24L * 3600L;
        };
    }

    /**
     * Generates an expiring preview token for the specified article.
     */
    public String generateToken(final JSONObject article, final long ttlSeconds) throws ServiceException {
        if (article == null) {
            throw new IllegalArgumentException("Article must not be null");
        }
        final String articleId = article.optString(Keys.OBJECT_ID);
        if (StringUtils.isBlank(articleId)) {
            throw new IllegalArgumentException("Article id must not be blank");
        }
        final long clampedTtl = Math.max(60L, Math.min(ttlSeconds, 90L * 24L * 3600L));
        final long expEpochSec = (System.currentTimeMillis() / 1000L) + clampedTtl;
        final String secret = getOrCreateSecret();
        final String sig = sign(secret, articleId, expEpochSec, article.optString(Article.ARTICLE_VIEW_PWD, ""));
        return expEpochSec + "." + sig;
    }

    /**
     * Builds the full shareable URL for the given article and token.
     */
    public String buildShareUrl(final JSONObject article, final String token) {
        String permalink = article.optString(Article.ARTICLE_PERMALINK, "");
        if (!permalink.startsWith("/")) {
            permalink = "/" + permalink;
        }
        final String sep = permalink.contains("?") ? "&" : "?";
        return Latkes.getServePath() + permalink + sep + PREVIEW_PARAM + "="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    /**
     * Rotates the HMAC signing secret, immediately revoking all previously issued preview links.
     */
    public synchronized void rotateSecret() throws ServiceException {
        final byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        final String nextSecret = HexFormat.of().formatHex(bytes);
        final JSONObject opt = new JSONObject()
                .put(Keys.OBJECT_ID, OPTION_ID_SECRET)
                .put(Option.OPTION_CATEGORY, OPTION_CATEGORY_SECRET)
                .put(Option.OPTION_VALUE, nextSecret);
        optionMgmtService.addOrUpdateOption(opt);
        cachedSecret = nextSecret;
    }

    /**
     * Checks whether the current request carries a valid, non-expired preview token for the given article.
     */
    public boolean hasValidPreviewToken(final RequestContext context, final JSONObject article) {
        if (context == null || article == null) {
            return false;
        }
        if (Boolean.TRUE.equals(context.attr(PREVIEW_ACCESS_ATTR))) {
            return true;
        }
        final String rawToken = context.param(PREVIEW_PARAM);
        if (StringUtils.isBlank(rawToken)) {
            return false;
        }
        final boolean valid = verifyToken(article, rawToken.trim());
        if (valid) {
            context.attr(PREVIEW_ACCESS_ATTR, Boolean.TRUE);
        }
        return valid;
    }

    public boolean verifyToken(final JSONObject article, final String token) {
        if (article == null || StringUtils.isBlank(token) || token.length() > 160) {
            return false;
        }
        final int dot = token.indexOf('.');
        if (dot <= 0 || dot >= token.length() - 1) {
            return false;
        }
        final String expPart = token.substring(0, dot);
        final String sigPart = token.substring(dot + 1);
        if (!expPart.matches("\\d{1,15}") || !sigPart.matches("[0-9a-fA-F]{64}")) {
            return false;
        }
        final long expEpochSec;
        try {
            expEpochSec = Long.parseLong(expPart);
        } catch (final NumberFormatException e) {
            return false;
        }
        final long nowEpochSec = System.currentTimeMillis() / 1000L;
        if (nowEpochSec > expEpochSec) {
            return false;
        }
        final String secret = peekSecret();
        if (StringUtils.isBlank(secret)) {
            return false;
        }
        final String articleId = article.optString(Keys.OBJECT_ID);
        final String expectedSig = sign(secret, articleId, expEpochSec, article.optString(Article.ARTICLE_VIEW_PWD, ""));
        return MessageDigest.isEqual(
                expectedSig.getBytes(StandardCharsets.UTF_8),
                sigPart.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    private String peekSecret() {
        final String local = cachedSecret;
        if (StringUtils.isNotBlank(local)) {
            return local;
        }
        final JSONObject opt = optionQueryService == null ? null : optionQueryService.getOptionById(OPTION_ID_SECRET);
        if (opt == null) {
            return "";
        }
        final String stored = opt.optString(Option.OPTION_VALUE, "");
        if (StringUtils.isNotBlank(stored)) {
            cachedSecret = stored;
        }
        return stored;
    }

    private synchronized String getOrCreateSecret() throws ServiceException {
        final String existing = peekSecret();
        if (StringUtils.isNotBlank(existing)) {
            return existing;
        }
        rotateSecret();
        return cachedSecret;
    }

    private static String sign(final String secret, final String articleId, final long expEpochSec, final String viewPwd) {
        try {
            final String pwdDigest = HiddenContentService.sha256Hex(viewPwd == null ? "" : viewPwd);
            final String payload = articleId + ":" + expEpochSec + ":" + pwdDigest;
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (final Exception e) {
            LOGGER.error("Signs preview token failed", e);
            throw new IllegalStateException("Cannot sign preview token", e);
        }
    }
}
