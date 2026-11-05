/*
 * Volo - A lightweight, secure, and modern blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.b3log.solo.bolo.tool.MD5Utils;
import org.b3log.solo.model.UserExt;
import org.json.JSONObject;

/**
 * Salted PBKDF2-HMAC-SHA256 password hashing with constant-time verification and legacy B3Key fallback.
 */
public final class PasswordHashes {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2$sha256$";
    private static final int DEFAULT_ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHashes() {
    }

    /**
     * Hashes a raw password with a fresh 128-bit random salt using PBKDF2-HMAC-SHA256.
     */
    public static String hash(final String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new IllegalArgumentException("Password must not be empty");
        }
        final byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        final byte[] derived = derive(rawPassword.toCharArray(), salt, DEFAULT_ITERATIONS, KEY_BITS);
        final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return PREFIX + DEFAULT_ITERATIONS + "$" + encoder.encodeToString(salt) + "$" + encoder.encodeToString(derived);
    }

    /**
     * Generates a random 32-char hex session credential stored in {@code userB3Key} to bind/revoke login cookies.
     */
    public static String randomSessionKey() {
        final byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * Verifies a raw password against {@code userPasswordHash} if present, otherwise falls back to legacy {@code userB3Key}.
     */
    public static boolean verify(final String rawPassword, final JSONObject user) {
        if (rawPassword == null || rawPassword.isEmpty() || user == null) {
            return false;
        }
        final String pbkdf2 = user.optString(UserExt.USER_PASSWORD_HASH);
        if (pbkdf2 != null && !pbkdf2.isBlank()) {
            return verifyHash(rawPassword, pbkdf2);
        }
        final String legacyB3Key = user.optString(UserExt.USER_B3_KEY);
        if (legacyB3Key == null || legacyB3Key.isEmpty()) {
            return false;
        }
        final String candidate = MD5Utils.stringToMD5Twice(rawPassword);
        return MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8),
                legacyB3Key.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Verifies a raw password against an encoded PBKDF2 hash string in constant time.
     */
    public static boolean verifyHash(final String rawPassword, final String encodedHash) {
        if (rawPassword == null || encodedHash == null || !encodedHash.startsWith(PREFIX)) {
            return false;
        }
        final String[] parts = encodedHash.split("\\$");
        if (parts.length != 5) {
            return false;
        }
        try {
            final int iterations = Integer.parseInt(parts[2]);
            if (iterations < 10_000 || iterations > 1_000_000) {
                return false;
            }
            final Base64.Decoder decoder = Base64.getUrlDecoder();
            final byte[] salt = decoder.decode(parts[3]);
            final byte[] expected = decoder.decode(parts[4]);
            if (salt.length < 8 || expected.length < 16) {
                return false;
            }
            final byte[] actual = derive(rawPassword.toCharArray(), salt, iterations, expected.length * 8);
            return MessageDigest.isEqual(expected, actual);
        } catch (final Exception invalidFormat) {
            return false;
        }
    }

    /**
     * Checks password policy for newly registered reader accounts (8-64 chars, must include letters and digits).
     */
    public static boolean validReaderPassword(final String rawPassword) {
        if (rawPassword == null || rawPassword.length() < 8 || rawPassword.length() > 64) {
            return false;
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < rawPassword.length(); i++) {
            final char c = rawPassword.charAt(i);
            if (Character.isISOControl(c)) {
                return false;
            }
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            }
        }
        return hasLetter && hasDigit;
    }

    private static byte[] derive(final char[] password, final byte[] salt, final int iterations, final int bits) {
        try {
            final KeySpec spec = new PBEKeySpec(password, salt, iterations, bits);
            final SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            return factory.generateSecret(spec).getEncoded();
        } catch (final Exception cryptoError) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 unavailable", cryptoError);
        }
    }
}
