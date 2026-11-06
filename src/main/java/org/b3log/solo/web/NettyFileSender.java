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
package org.b3log.solo.web;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultFileRegion;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.cookie.CookieHeaderNames;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.ssl.SslHandler;
import org.apache.logging.log4j.Level;
import org.b3log.latke.http.Cookie;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.http.Response;
import org.b3log.solo.logging.Logger;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Zero-copy / bounded-chunk Netty file response streamer for Latke {@link RequestContext}.
 *
 * <p>Uses {@link DefaultFileRegion} ({@code sendfile}) with zero heap and zero {@code DirectByteBuf} allocation
 * on cleartext HTTP channels, falls back to backpressure-aware 64 KiB chunk pumping when {@link SslHandler} is
 * in the pipeline, and falls back to bounded in-memory byte reads during unit tests where Netty {@code ctx == null}.</p>
 */
public final class NettyFileSender {

    private static final Logger LOGGER = Logger.getLogger(NettyFileSender.class);

    /** Chunk size (64 KiB) when TLS {@link SslHandler} requires user-space encryption. */
    public static final int SSL_CHUNK_BYTES = 64 * 1024;

    /** Maximum slice size (64 MiB) for the unit-test fallback when {@code nettyCtx == null}. */
    public static final long MAX_FALLBACK_BYTES = 64L * 1024L * 1024L;

    private static final byte[] EMPTY_BYTES = new byte[0];

    private static final Field RESPONSE_CTX_FIELD;
    private static final Field RESPONSE_RES_FIELD;
    private static final Field RESPONSE_COMMITTED_FIELD;
    private static final Field RESPONSE_KEEP_ALIVE_FIELD;
    private static final Field COOKIE_DELEGATE_FIELD;

    static {
        Field ctxF = null;
        Field resF = null;
        Field comF = null;
        Field kaF = null;
        Field cookieF = null;
        try {
            ctxF = Response.class.getDeclaredField("ctx");
            ctxF.setAccessible(true);
            resF = Response.class.getDeclaredField("res");
            resF.setAccessible(true);
            comF = Response.class.getDeclaredField("commited");
            comF.setAccessible(true);
            kaF = Response.class.getDeclaredField("keepAlive");
            kaF.setAccessible(true);
        } catch (final ReflectiveOperationException ignored) {
        }
        try {
            cookieF = Cookie.class.getDeclaredField("cookie");
            cookieF.setAccessible(true);
        } catch (final ReflectiveOperationException ignored) {
        }
        RESPONSE_CTX_FIELD = ctxF;
        RESPONSE_RES_FIELD = resF;
        RESPONSE_COMMITTED_FIELD = comF;
        RESPONSE_KEEP_ALIVE_FIELD = kaF;
        COOKIE_DELEGATE_FIELD = cookieF;
    }

    private NettyFileSender() {
    }

    /**
     * Sends {@code [offset, offset + length)} from {@code file} to {@code context}'s HTTP response.
     *
     * @param context    active Latke request context
     * @param file       source regular file path
     * @param offset     non-negative start byte offset
     * @param length     non-negative byte count to transfer
     * @param onComplete optional callback invoked once after transfer completes or aborts
     * @throws IOException if pre-commit file access or unit-test fallback reading fails
     */
    public static void sendFile(final RequestContext context,
                                final Path file,
                                final long offset,
                                final long length,
                                final Runnable onComplete) throws IOException {
        if (context == null || file == null) {
            throw new IllegalArgumentException("context and file must not be null");
        }
        if (offset < 0L || length < 0L) {
            throw new IllegalArgumentException("Invalid file slice: offset=" + offset + ", length=" + length);
        }

        final Response response = context.getResponse();
        ChannelHandlerContext nettyCtx = null;
        HttpResponse nettyRes = null;
        boolean keepAlive = false;
        if (response != null
                && RESPONSE_CTX_FIELD != null
                && RESPONSE_RES_FIELD != null
                && RESPONSE_COMMITTED_FIELD != null
                && RESPONSE_KEEP_ALIVE_FIELD != null) {
            try {
                nettyCtx = (ChannelHandlerContext) RESPONSE_CTX_FIELD.get(response);
                nettyRes = (HttpResponse) RESPONSE_RES_FIELD.get(response);
                keepAlive = RESPONSE_KEEP_ALIVE_FIELD.getBoolean(response);
            } catch (final IllegalAccessException ignored) {
                nettyCtx = null;
            }
        }

        if (nettyCtx != null && nettyRes != null) {
            final AtomicBoolean completed = new AtomicBoolean(false);
            final Runnable finishOnce = () -> {
                if (completed.compareAndSet(false, true)) {
                    safeRun(onComplete);
                }
            };
            boolean asyncHandedOff = false;
            FileChannel sslChannel = null;
            try {
                final boolean sslPresent = nettyCtx.pipeline().get(SslHandler.class) != null;
                if (length > 0L) {
                    if (sslPresent) {
                        sslChannel = FileChannel.open(file, StandardOpenOption.READ);
                        final long fileSize = sslChannel.size();
                        if (offset + length > fileSize) {
                            throw new IOException("Requested file slice exceeds file size: " + fileSize);
                        }
                        sslChannel.position(offset);
                    } else {
                        final long fileSize = Files.size(file);
                        if (offset + length > fileSize) {
                            throw new IOException("Requested file slice exceeds file size: " + fileSize);
                        }
                    }
                }

                encodeCookies(response, nettyRes);
                nettyRes.headers().set(HttpHeaderNames.CONTENT_LENGTH, Long.toString(length));
                if (keepAlive) {
                    nettyRes.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                }
                try {
                    RESPONSE_COMMITTED_FIELD.setBoolean(response, true);
                } catch (final IllegalAccessException ignored) {
                }

                final DefaultHttpResponse headerFrame = new DefaultHttpResponse(
                        nettyRes.protocolVersion(), nettyRes.status(), nettyRes.headers());
                nettyCtx.write(headerFrame);

                final boolean closeAfter = !keepAlive;
                if (length == 0L) {
                    final ChannelFuture last = nettyCtx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                    asyncHandedOff = true;
                    last.addListener((ChannelFuture future) -> {
                        finishOnce.run();
                        if (closeAfter || !future.isSuccess()) {
                            future.channel().close();
                        }
                    });
                    return;
                }

                if (!sslPresent) {
                    final ChannelFuture bodyFuture = nettyCtx.write(new DefaultFileRegion(file.toFile(), offset, length));
                    bodyFuture.addListener((ChannelFuture future) -> {
                        if (!future.isSuccess()) {
                            finishOnce.run();
                            future.channel().close();
                        }
                    });
                    final ChannelFuture last = nettyCtx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                    asyncHandedOff = true;
                    last.addListener((ChannelFuture future) -> {
                        finishOnce.run();
                        if (closeAfter || !future.isSuccess()) {
                            future.channel().close();
                        }
                    });
                    return;
                }

                final SslChunkPump pump = new SslChunkPump(nettyCtx, sslChannel, length, keepAlive, finishOnce);
                asyncHandedOff = true;
                nettyCtx.channel().closeFuture().addListener(f -> pump.abort());
                if (nettyCtx.executor().inEventLoop()) {
                    pump.pumpNext();
                } else {
                    nettyCtx.executor().execute(pump::pumpNext);
                }
                return;
            } finally {
                if (!asyncHandedOff) {
                    closeQuietly(sslChannel);
                    finishOnce.run();
                }
            }
        }

        // Unit-test fallback when no Netty ChannelHandlerContext is attached (ctx == null).
        try {
            if (length > MAX_FALLBACK_BYTES) {
                throw new IOException("File slice too large for in-memory test response buffer: " + length);
            }
            final byte[] payload = readSlice(file, offset, (int) length);
            context.setHeader("Content-Length", Long.toString(payload.length));
            if (response != null) {
                response.sendBytes(payload);
            }
        } finally {
            safeRun(onComplete);
        }
    }

    private static void encodeCookies(final Response response, final HttpResponse nettyRes) {
        final Set<Cookie> cookies = response.getCookies();
        if (cookies == null || cookies.isEmpty()) {
            return;
        }
        for (final Cookie latkeCookie : cookies) {
            if (latkeCookie == null) {
                continue;
            }
            io.netty.handler.codec.http.cookie.Cookie nettyCookie = null;
            if (COOKIE_DELEGATE_FIELD != null) {
                try {
                    nettyCookie = (io.netty.handler.codec.http.cookie.Cookie) COOKIE_DELEGATE_FIELD.get(latkeCookie);
                } catch (final IllegalAccessException ignored) {
                }
            }
            if (nettyCookie == null) {
                final DefaultCookie fallback = new DefaultCookie(latkeCookie.getName(), latkeCookie.getValue());
                if (latkeCookie.getDomain() != null) {
                    fallback.setDomain(latkeCookie.getDomain());
                }
                if (latkeCookie.getPath() != null) {
                    fallback.setPath(latkeCookie.getPath());
                }
                fallback.setMaxAge(latkeCookie.getMaxAge());
                fallback.setHttpOnly(latkeCookie.isHttpOnly());
                fallback.setSecure(latkeCookie.isSecure());
                fallback.setWrap(latkeCookie.isWrap());
                try {
                    final String sameSite = latkeCookie.getSameSite();
                    if (sameSite != null && !sameSite.isEmpty()) {
                        fallback.setSameSite(CookieHeaderNames.SameSite.valueOf(sameSite));
                    }
                } catch (final Exception ignored) {
                }
                nettyCookie = fallback;
            }
            nettyRes.headers().add(HttpHeaderNames.SET_COOKIE, ServerCookieEncoder.STRICT.encode(nettyCookie));
        }
    }

    private static byte[] readSlice(final Path file, final long offset, final int length) throws IOException {
        try (SeekableByteChannel ch = Files.newByteChannel(file, StandardOpenOption.READ)) {
            final long fileSize = ch.size();
            if (offset + length > fileSize) {
                throw new IOException("Requested file slice exceeds file size: " + fileSize);
            }
            if (length <= 0) {
                return EMPTY_BYTES;
            }
            final byte[] data = new byte[length];
            final ByteBuffer buf = ByteBuffer.wrap(data);
            ch.position(offset);
            while (buf.hasRemaining()) {
                final int n = ch.read(buf);
                if (n < 0) {
                    break;
                }
            }
            if (buf.hasRemaining()) {
                return Arrays.copyOf(data, buf.position());
            }
            return data;
        }
    }

    private static void closeQuietly(final AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (final Exception ignored) {
            }
        }
    }

    private static void safeRun(final Runnable callback) {
        if (callback == null) {
            return;
        }
        try {
            callback.run();
        } catch (final Throwable t) {
            LOGGER.log(Level.WARN, "NettyFileSender onComplete callback failed", t);
        }
    }

    private static final class SslChunkPump implements ChannelFutureListener {
        private final ChannelHandlerContext ctx;
        private final FileChannel fileChannel;
        private final boolean keepAlive;
        private final Runnable finishOnce;
        private final AtomicBoolean done = new AtomicBoolean(false);
        private long remaining;

        SslChunkPump(final ChannelHandlerContext ctx,
                     final FileChannel fileChannel,
                     final long length,
                     final boolean keepAlive,
                     final Runnable finishOnce) {
            this.ctx = ctx;
            this.fileChannel = fileChannel;
            this.remaining = length;
            this.keepAlive = keepAlive;
            this.finishOnce = finishOnce;
        }

        @Override
        public void operationComplete(final ChannelFuture future) {
            if (!future.isSuccess()) {
                abort();
                return;
            }
            pumpNext();
        }

        void pumpNext() {
            if (done.get()) {
                return;
            }
            final Channel channel = ctx.channel();
            if (!channel.isActive()) {
                abort();
                return;
            }
            if (remaining <= 0L) {
                completeEof();
                return;
            }
            if (!channel.isWritable()) {
                ctx.executor().schedule(this::pumpNext, 5L, TimeUnit.MILLISECONDS);
                return;
            }

            final int toRead = (int) Math.min(SSL_CHUNK_BYTES, remaining);
            final ByteBuf buf = ctx.alloc().buffer(toRead);
            boolean transferredToNetty = false;
            try {
                int totalRead = 0;
                while (totalRead < toRead) {
                    final int n = buf.writeBytes(fileChannel, toRead - totalRead);
                    if (n <= 0) {
                        break;
                    }
                    totalRead += n;
                }
                if (totalRead <= 0) {
                    abort();
                    return;
                }
                remaining -= totalRead;
                transferredToNetty = true;
                ctx.writeAndFlush(new DefaultHttpContent(buf)).addListener(this);
            } catch (final Throwable t) {
                abort();
            } finally {
                if (!transferredToNetty) {
                    buf.release();
                }
            }
        }

        private void completeEof() {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            closeQuietly(fileChannel);
            ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener((ChannelFuture lastFuture) -> {
                finishOnce.run();
                if (!keepAlive || !lastFuture.isSuccess()) {
                    lastFuture.channel().close();
                }
            });
        }

        void abort() {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            closeQuietly(fileChannel);
            finishOnce.run();
            ctx.close();
        }
    }
}
