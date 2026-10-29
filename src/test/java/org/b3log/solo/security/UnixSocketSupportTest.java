/*
 * Volo - A blogging system forked from Bolo.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.security;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.b3log.solo.util.LoginThrottle;
import org.b3log.solo.web.UnixSocketSupport;
import org.testng.Assert;
import org.testng.annotations.Test;

public class UnixSocketSupportTest {
    @Test public void invalidSocketModesAreRejectedBeforeCreatingFiles() throws Exception {
        final Path directory = Files.createTempDirectory("volo-socket-mode-");
        final Path socket = directory.resolve("app.sock");
        try {
            for (String mode : new String[]{"88", "0880", "7777", "-660", "0x660", "0660,0600"}) {
                Assert.expectThrows(IOException.class, () -> UnixSocketSupport.open(socket + "," + mode));
                Assert.assertFalse(Files.exists(directory.resolve("app.sock.lock")));
                Assert.assertFalse(Files.exists(socket));
            }
            Assert.expectThrows(IOException.class, () -> UnixSocketSupport.open(",0660"));
        } finally { Files.delete(directory); }
    }

    @Test public void configuredSocketModesAndMissingModesAreApplied() throws Exception {
        final Path directory = Files.createTempDirectory("volo-socket-mode-");
        final Path socket = directory.resolve("app.sock");
        try {
            if (!Files.getFileStore(directory).supportsFileAttributeView("posix")) return;
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-x---"));
            final String[] suffixes = {"", ",", ",  ", ",0660", ",660", ",0600", ",0640", ",0666", ",0000"};
            final String[] modes = {"rw-rw----", "rw-rw----", "rw-rw----", "rw-rw----", "rw-rw----",
                    "rw-------", "rw-r-----", "rw-rw-rw-", "---------"};
            for (int index = 0; index < suffixes.length; index++) {
                final CompletableFuture<Void> stopped = new CompletableFuture<>();
                try (var listener = UnixSocketSupport.open(socket + suffixes[index]);
                     var channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
                    final CompletableFuture<Void> running = CompletableFuture.runAsync(() -> {
                        try {
                            listener.listen(path -> {
                                try { channel.bind(UnixDomainSocketAddress.of(path)); stopped.join(); }
                                catch (IOException e) { throw new RuntimeException(e); }
                            }, () -> stopped.complete(null));
                        } catch (Exception e) { throw new RuntimeException(e); }
                    });
                    try {
                        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        while ((!Files.exists(socket) || !PosixFilePermissions.toString(Files.getPosixFilePermissions(socket)).equals(modes[index]))
                                && System.nanoTime() < deadline) Thread.sleep(10);
                        Assert.assertEquals(PosixFilePermissions.toString(Files.getPosixFilePermissions(socket)), modes[index]);
                        Assert.assertEquals(PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.resolve("app.sock.lock"))), "rw-------");
                    } finally { stopped.complete(null); }
                    running.get(5, TimeUnit.SECONDS);
                } finally { stopped.complete(null); }
                Assert.assertFalse(Files.exists(socket));
            }
        } finally { Files.deleteIfExists(socket); Files.deleteIfExists(directory.resolve("app.sock.lock")); Files.delete(directory); }
    }

    @Test public void tcpDefaultAndAbsolutePathValidation() throws Exception {
        Assert.assertNull(UnixSocketSupport.open(" "));
        Assert.expectThrows(IOException.class, () -> UnixSocketSupport.open("relative.sock"));
        Assert.assertFalse(UnixSocketSupport.isActive());
    }

    @Test public void unsafeDirectoriesAndExistingFilesAreNotModified() throws Exception {
        final Path directory = Files.createTempDirectory("volo-socket-");
        final Path socket = directory.resolve("app.sock");
        try {
            if (!Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                Assert.expectThrows(IOException.class, () -> UnixSocketSupport.open(socket.toString()));
                return;
            }
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwxrwx"));
            Assert.expectThrows(IOException.class, () -> UnixSocketSupport.open(socket.toString()));
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-x---"));
            Files.writeString(socket, "must not be removed");
            Assert.expectThrows(IOException.class, () -> UnixSocketSupport.open(socket.toString()));
            Assert.assertEquals(Files.readString(socket), "must not be removed");
            Files.delete(socket);
            Files.createSymbolicLink(socket, Path.of("missing-target"));
            Assert.expectThrows(IOException.class, () -> UnixSocketSupport.open(socket.toString()));
            Assert.assertTrue(Files.isSymbolicLink(socket));
            Files.delete(socket);
            try (var stale = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
                stale.bind(UnixDomainSocketAddress.of(socket));
            }
            Assert.expectThrows(IOException.class, () -> UnixSocketSupport.open(socket.toString()));
            Assert.assertTrue(Files.readAttributes(socket, java.nio.file.attribute.BasicFileAttributes.class).isOther(),
                    "An existing socket must not be automatically unlinked");
        } finally {
            Files.deleteIfExists(socket); Files.deleteIfExists(directory.resolve("app.sock.lock")); Files.delete(directory);
        }
    }

    @Test public void unixLifecycleLockPermissionsProxyTrustAndRestart() throws Exception {
        final Path directory = Files.createTempDirectory("volo-socket-");
        final Path socket = directory.resolve("app.sock");
        try {
            if (!Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                Assert.expectThrows(IOException.class, () -> UnixSocketSupport.open(socket.toString()));
                return;
            }
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-x---"));
            LatkeTestSupport.configure();
            for (int restart = 0; restart < 2; restart++) {
                try (var listener = UnixSocketSupport.open(socket.toString());
                     var channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
                    Assert.expectThrows(IOException.class, () -> UnixSocketSupport.open(socket.toString()));
                    final CompletableFuture<Void> stopped = new CompletableFuture<>();
                    final CompletableFuture<Void> running = CompletableFuture.runAsync(() -> {
                        try {
                            listener.listen(path -> {
                                try { channel.bind(UnixDomainSocketAddress.of(path)); stopped.join(); }
                                catch (IOException e) { throw new RuntimeException(e); }
                            }, () -> stopped.complete(null));
                        } catch (Exception e) { throw new RuntimeException(e); }
                    });
                    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while ((!Files.exists(socket) || !PosixFilePermissions.toString(Files.getPosixFilePermissions(socket)).equals("rw-rw----"))
                            && System.nanoTime() < deadline) Thread.sleep(10);
                    Assert.assertEquals(PosixFilePermissions.toString(Files.getPosixFilePermissions(socket)), "rw-rw----");
                    Assert.assertTrue(UnixSocketSupport.isActive());
                    final var http = new io.netty.handler.codec.http.DefaultFullHttpRequest(
                            io.netty.handler.codec.http.HttpVersion.HTTP_1_1, io.netty.handler.codec.http.HttpMethod.GET, "/");
                    try {
                        http.headers().set("X-Real-IP", "198.51.100.8");
                        final var request = new org.b3log.latke.http.Request(null, http);
                        Assert.assertEquals(LoginThrottle.clientAddress(request), "unix", "TCP proxy settings do not implicitly trust Unix peers");
                        final var properties = new java.util.Properties(); properties.setProperty("loginTrustedProxies", "unix");
                        org.b3log.latke.Latkes.setLocalProps(properties);
                        Assert.assertEquals(LoginThrottle.clientAddress(request), "198.51.100.8");
                        http.headers().set("X-Real-IP", "198.51.100.8,203.0.113.1");
                        Assert.assertEquals(LoginThrottle.clientAddress(request), "unix", "Malformed lists cannot create new identities");
                    } finally { http.release(); LatkeTestSupport.configure(); }
                    stopped.complete(null); running.get(5, TimeUnit.SECONDS);
                }
                Assert.assertFalse(Files.exists(socket)); Assert.assertFalse(UnixSocketSupport.isActive());
            }
        } finally {
            Files.deleteIfExists(socket); Files.deleteIfExists(directory.resolve("app.sock.lock")); Files.delete(directory);
        }
    }
}
