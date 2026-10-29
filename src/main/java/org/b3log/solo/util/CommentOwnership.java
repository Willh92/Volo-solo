/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.json.JSONObject;

/** A browser-held random key plus the original trusted source IP is required for guest writes. */
public final class CommentOwnership {
    public static final String KEY_HASH = "commentGuestKeyHash";
    private CommentOwnership() { }

    public static String hash(final String key) {
        if (key == null || !key.matches("[a-f0-9]{64}")) return "";
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static boolean owns(final JSONObject comment, final String address, final String key) {
        if (comment == null || comment.optBoolean("commentDeleted")) return false;
        final String current = IpAddresses.canonical(address), original = IpAddresses.canonical(comment.optString(IpAddresses.COMMENT_IP));
        final String expected = comment.optString(KEY_HASH), actual = hash(key);
        return !current.isEmpty() && current.equals(original) && !expected.isEmpty() && !actual.isEmpty()
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
    }
}
