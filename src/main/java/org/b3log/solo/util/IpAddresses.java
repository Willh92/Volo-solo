/*
 * Volo - A blogging system forked from Bolo.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.util;

import io.netty.util.NetUtil;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import org.json.JSONObject;

/** Literal-only IP parsing and the boundary between stored addresses and public comments. */
public final class IpAddresses {
    public static final String COMMENT_IP = "commentClientIP";
    public static final String PUBLIC_IP = "commentIPMasked";
    private IpAddresses() { }

    public static byte[] bytes(final String value) {
        if (value == null || value.isBlank() || value.indexOf('%') >= 0) return null;
        final byte[] parsed = NetUtil.createByteArrayFromIpAddressString(value.trim());
        if (parsed != null && parsed.length == 16) {
            boolean mapped = parsed[10] == (byte) 255 && parsed[11] == (byte) 255;
            for (int i = 0; i < 10; i++) mapped &= parsed[i] == 0;
            if (mapped) return Arrays.copyOfRange(parsed, 12, 16);
        }
        return parsed;
    }

    public static String canonical(final String value) {
        final byte[] bytes = bytes(value);
        if (bytes == null) return "";
        try { return InetAddress.getByAddress(bytes).getHostAddress(); }
        catch (UnknownHostException impossible) { throw new IllegalArgumentException(impossible); }
    }

    public static String mask(final String value) {
        final byte[] bytes = bytes(value);
        if (bytes == null) return "";
        if (bytes.length == 4) return (bytes[0] & 255) + "." + (bytes[1] & 255) + ".*.*";
        return Integer.toHexString(((bytes[0] & 255) << 8) | (bytes[1] & 255)) + ":"
                + Integer.toHexString(((bytes[2] & 255) << 8) | (bytes[3] & 255)) + ":*:*:*:*:*:*";
    }

    public static void hidePrivateAddress(final JSONObject comment) {
        final String raw = comment.optBoolean("commentDeleted") ? "" : comment.optString(COMMENT_IP);
        comment.remove(COMMENT_IP);
        comment.remove("commentNotifyEmail");
        comment.remove("commentEmail");
        comment.remove(CommentOwnership.KEY_HASH);
        comment.put(PUBLIC_IP, mask(raw));
        comment.put("commentRegion", raw.isBlank() ? "" : IpGeo.region(raw));
    }
}
