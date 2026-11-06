/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.web;

import io.netty.util.concurrent.FastThreadLocalThread;
import org.apache.commons.lang3.StringUtils;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.http.Response;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Dynamic HTTP GZIP response compression helper for HTML, JSON, RSS/Atom, and Sitemap payloads.
 */
public final class HttpCompression {

    /**
     * Minimum UTF-8 payload byte threshold before dynamic GZIP compression is applied.
     */
    public static final int MIN_GZIP_BYTES = 1024;

    private static final int MAX_STREAMING_CHARS = 2_000_000;
    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final byte[] GZIP_HEADER = {
            0x1f, (byte) 0x8b, Deflater.DEFLATED, 0, 0, 0, 0, 0, 0, (byte) 0xff
    };

    private static final ThreadLocal<GzipState> GZIP_STATE = ThreadLocal.withInitial(GzipState::new);

    private static final class GzipState {
        final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        final CRC32 crc32 = new CRC32();
        final byte[] utf8Buf = new byte[8192];
        final byte[] deflateBuf = new byte[4096];
    }

    private static boolean isReusableEventLoopThread() {
        return Thread.currentThread() instanceof FastThreadLocalThread;
    }

    private HttpCompression() {
    }

    /**
     * Determines whether {@code text} is eligible for GZIP representation negotiation on {@code context}
     * (used for representation-specific {@code ETag} calculation across both {@code GET} and {@code HEAD}).
     */
    public static boolean willCompress(final RequestContext context, final String text) {
        if (context == null || text == null || context.getRequest() == null) {
            return false;
        }
        if (!PublicResourceHandler.acceptsGzip(context.header("Accept-Encoding"))) {
            return false;
        }
        final Response response = context.getResponse();
        if (response == null || StringUtils.isNotBlank(response.getHeader("Content-Encoding"))) {
            return false;
        }
        if (text.length() * 3 < MIN_GZIP_BYTES) {
            return false;
        }
        return text.length() >= MIN_GZIP_BYTES || utf8EncodedLength(text) >= MIN_GZIP_BYTES;
    }

    /**
     * Determines whether the response for {@code context} should be GZIP-compressed.
     *
     * @param context      the current HTTP request context
     * @param payloadBytes UTF-8 byte length of the uncompressed body
     * @return {@code true} if dynamic GZIP compression should be applied
     */
    public static boolean shouldGzip(final RequestContext context, final int payloadBytes) {
        if (context == null || payloadBytes < MIN_GZIP_BYTES) {
            return false;
        }
        if ("HEAD".equalsIgnoreCase(context.method())) {
            return false;
        }
        if (context.getRequest() == null || !PublicResourceHandler.acceptsGzip(context.header("Accept-Encoding"))) {
            return false;
        }
        final Response response = context.getResponse();
        return response != null && StringUtils.isBlank(response.getHeader("Content-Encoding"));
    }

    /**
     * Compresses {@code raw} using a reusable per-thread {@link Deflater} and RFC 1952 framing.
     *
     * @param raw uncompressed payload bytes
     * @return GZIP-compressed bytes
     */
    public static byte[] gzip(final byte[] raw) {
        if (raw == null || raw.length == 0) {
            return EMPTY_BYTES;
        }
        final boolean reuse = isReusableEventLoopThread();
        final Deflater deflater;
        final CRC32 crc32;
        final byte[] deflateBuf;
        if (reuse) {
            final GzipState state = GZIP_STATE.get();
            deflater = state.deflater;
            crc32 = state.crc32;
            deflateBuf = state.deflateBuf;
            deflater.reset();
            crc32.reset();
        } else {
            deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
            crc32 = new CRC32();
            deflateBuf = new byte[4096];
        }
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream(
                    Math.max(64, Math.min(raw.length, raw.length / 3 + 32)));
            out.write(GZIP_HEADER, 0, GZIP_HEADER.length);
            crc32.update(raw, 0, raw.length);
            deflateChunk(deflater, raw, raw.length, deflateBuf, out);
            deflater.finish();
            while (!deflater.finished()) {
                final int count = deflater.deflate(deflateBuf, 0, deflateBuf.length);
                if (count > 0) {
                    out.write(deflateBuf, 0, count);
                } else if (deflater.needsInput()) {
                    break;
                }
            }
            writeIntLE(out, (int) crc32.getValue());
            writeIntLE(out, raw.length);
            return out.toByteArray();
        } finally {
            if (reuse) {
                deflater.setInput(EMPTY_BYTES);
                deflater.reset();
                crc32.reset();
            } else {
                deflater.end();
            }
        }
    }

    /**
     * Ensures {@code Vary: Accept-Encoding} is present on the HTTP response without duplicating tokens.
     *
     * @param response target HTTP response
     */
    public static void ensureVaryAcceptEncoding(final Response response) {
        if (response == null) {
            return;
        }
        final String existing = response.getHeader("Vary");
        if (existing == null || existing.isEmpty()) {
            response.setHeader("Vary", "Accept-Encoding");
        } else if (!"*".equals(existing.trim()) && !StringUtils.containsIgnoreCase(existing, "Accept-Encoding")) {
            response.setHeader("Vary", existing + ", Accept-Encoding");
        }
    }

    /**
     * Sends {@code text} as UTF-8, applying GZIP compression when {@code Accept-Encoding: gzip} is accepted
     * and the UTF-8 payload is at least {@link #MIN_GZIP_BYTES} bytes, while falling back to
     * {@link RequestContext#sendString(String)} on uncompressed responses for full Latke compatibility.
     *
     * @param context     HTTP request context
     * @param text        response text
     * @param contentType optional {@code Content-Type} header to apply if non-null
     */
    public static void sendText(final RequestContext context, final String text, final String contentType) {
        if (context == null || context.getResponse() == null) {
            return;
        }
        final Response response = context.getResponse();
        if (response.isCommitted()) {
            return;
        }
        if (contentType != null) {
            response.setContentType(contentType);
        }
        final String body = text == null ? "" : text;
        final boolean headRequest = "HEAD".equalsIgnoreCase(context.method());
        // Every Java UTF-16 char encodes to at most 3 UTF-8 bytes (surrogate pairs: 2 chars -> 4 bytes).
        if (body.length() * 3 < MIN_GZIP_BYTES) {
            if (headRequest) {
                context.sendBytes(EMPTY_BYTES);
            } else {
                context.sendString(body);
            }
            return;
        }
        if (headRequest) {
            if (body.length() >= MIN_GZIP_BYTES || utf8EncodedLength(body) >= MIN_GZIP_BYTES) {
                ensureVaryAcceptEncoding(response);
            }
            context.sendBytes(EMPTY_BYTES);
            return;
        }
        final boolean gzipAccepted = context.getRequest() != null
                && PublicResourceHandler.acceptsGzip(context.header("Accept-Encoding"));
        if (!gzipAccepted) {
            if (body.length() >= MIN_GZIP_BYTES || utf8EncodedLength(body) >= MIN_GZIP_BYTES) {
                ensureVaryAcceptEncoding(response);
            }
            context.sendString(body);
            return;
        }
        if (body.length() <= MAX_STREAMING_CHARS) {
            final int utf8Len = utf8EncodedLength(body);
            if (utf8Len < MIN_GZIP_BYTES) {
                context.sendString(body);
                return;
            }
            ensureVaryAcceptEncoding(response);
            if (shouldGzip(context, utf8Len)) {
                final byte[] gz = gzipUtf8(body, utf8Len);
                if (gz.length < utf8Len) {
                    response.setHeader("Content-Encoding", "gzip");
                    context.sendBytes(gz);
                    return;
                }
            }
            context.sendString(body);
            return;
        }
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length >= MIN_GZIP_BYTES) {
            ensureVaryAcceptEncoding(response);
        }
        if (shouldGzip(context, bytes.length)) {
            final byte[] gz = gzip(bytes);
            if (gz.length < bytes.length) {
                response.setHeader("Content-Encoding", "gzip");
                context.sendBytes(gz);
                return;
            }
        }
        context.sendString(body);
    }

    public static int utf8EncodedLength(final CharSequence seq) {
        if (seq == null) {
            return 0;
        }
        final int len = seq.length();
        int bytes = 0;
        for (int i = 0; i < len; i++) {
            final char c = seq.charAt(i);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c)) {
                if (i + 1 < len && Character.isLowSurrogate(seq.charAt(i + 1))) {
                    bytes += 4;
                    i++;
                } else {
                    bytes += 1;
                }
            } else if (Character.isLowSurrogate(c)) {
                bytes += 1;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    private static byte[] gzipUtf8(final String text, final int expectedUtf8Len) {
        final boolean reuse = isReusableEventLoopThread();
        final Deflater deflater;
        final CRC32 crc32;
        final byte[] utf8Buf;
        final byte[] deflateBuf;
        if (reuse) {
            final GzipState state = GZIP_STATE.get();
            deflater = state.deflater;
            crc32 = state.crc32;
            utf8Buf = state.utf8Buf;
            deflateBuf = state.deflateBuf;
            deflater.reset();
            crc32.reset();
        } else {
            deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
            crc32 = new CRC32();
            utf8Buf = new byte[Math.min(8192, Math.max(256, expectedUtf8Len))];
            deflateBuf = new byte[4096];
        }
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream(
                    Math.max(64, Math.min(expectedUtf8Len, expectedUtf8Len / 3 + 32)));
            out.write(GZIP_HEADER, 0, GZIP_HEADER.length);
            int pos = 0;
            int totalBytes = 0;
            final int len = text.length();
            for (int i = 0; i < len; i++) {
                if (pos > utf8Buf.length - 4) {
                    crc32.update(utf8Buf, 0, pos);
                    deflateChunk(deflater, utf8Buf, pos, deflateBuf, out);
                    totalBytes += pos;
                    pos = 0;
                }
                final char c = text.charAt(i);
                if (c < 0x80) {
                    utf8Buf[pos++] = (byte) c;
                } else if (c < 0x800) {
                    utf8Buf[pos++] = (byte) (0xc0 | (c >>> 6));
                    utf8Buf[pos++] = (byte) (0x80 | (c & 0x3f));
                } else if (Character.isHighSurrogate(c)) {
                    if (i + 1 < len && Character.isLowSurrogate(text.charAt(i + 1))) {
                        final int cp = Character.toCodePoint(c, text.charAt(++i));
                        utf8Buf[pos++] = (byte) (0xf0 | (cp >>> 18));
                        utf8Buf[pos++] = (byte) (0x80 | ((cp >>> 12) & 0x3f));
                        utf8Buf[pos++] = (byte) (0x80 | ((cp >>> 6) & 0x3f));
                        utf8Buf[pos++] = (byte) (0x80 | (cp & 0x3f));
                    } else {
                        utf8Buf[pos++] = (byte) '?';
                    }
                } else if (Character.isLowSurrogate(c)) {
                    utf8Buf[pos++] = (byte) '?';
                } else {
                    utf8Buf[pos++] = (byte) (0xe0 | (c >>> 12));
                    utf8Buf[pos++] = (byte) (0x80 | ((c >>> 6) & 0x3f));
                    utf8Buf[pos++] = (byte) (0x80 | (c & 0x3f));
                }
            }
            if (pos > 0) {
                crc32.update(utf8Buf, 0, pos);
                deflateChunk(deflater, utf8Buf, pos, deflateBuf, out);
                totalBytes += pos;
            }
            deflater.finish();
            while (!deflater.finished()) {
                final int count = deflater.deflate(deflateBuf, 0, deflateBuf.length);
                if (count > 0) {
                    out.write(deflateBuf, 0, count);
                } else if (deflater.needsInput()) {
                    break;
                }
            }
            writeIntLE(out, (int) crc32.getValue());
            writeIntLE(out, totalBytes);
            return out.toByteArray();
        } finally {
            if (reuse) {
                deflater.setInput(EMPTY_BYTES);
                deflater.reset();
                crc32.reset();
            } else {
                deflater.end();
            }
        }
    }

    private static void deflateChunk(final Deflater deflater,
                                     final byte[] input,
                                     final int len,
                                     final byte[] deflateBuf,
                                     final ByteArrayOutputStream out) {
        deflater.setInput(input, 0, len);
        while (!deflater.needsInput()) {
            final int count = deflater.deflate(deflateBuf, 0, deflateBuf.length);
            if (count > 0) {
                out.write(deflateBuf, 0, count);
            }
        }
    }

    private static void writeIntLE(final ByteArrayOutputStream out, final int v) {
        out.write(v & 0xff);
        out.write((v >>> 8) & 0xff);
        out.write((v >>> 16) & 0xff);
        out.write((v >>> 24) & 0xff);
    }
}
