/*
 * Volo - A blogging system forked from Bolo.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.web;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Lifecycle and filesystem boundary for Latke's native Unix domain socket listener. */
public final class UnixSocketSupport implements AutoCloseable {
    private static final AtomicReference<UnixSocketSupport> ACTIVE = new AtomicReference<>();
    private final Path path;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Set<PosixFilePermission> permissions;
    private Object socketKey;
    private boolean closed;
    private boolean started;

    private UnixSocketSupport(final Path path, final FileChannel channel, final FileLock lock,
                              final Set<PosixFilePermission> permissions) {
        this.path = path;
        this.lockChannel = channel;
        this.lock = lock;
        this.permissions = Set.copyOf(permissions);
    }

    public static boolean isActive() { return ACTIVE.get() != null; }

    /** The service creates a private runtime directory; the unprivileged JVM owns its socket. */
    public static UnixSocketSupport open(final String configured) throws IOException {
        if (configured == null || configured.isBlank()) return null;
        final String[] parts = configured.split(",", -1);
        if (parts.length > 2) throw new IOException("listenSocket must be an absolute path optionally followed by ,MODE");
        final String configuredMode = parts.length == 2 ? parts[1].trim() : "";
        final String modeText = configuredMode.isEmpty() ? "0660" : configuredMode;
        if (!modeText.matches("0?[0-7]{3}")) {
            throw new IOException("listenSocket mode must contain 3 octal digits, optionally prefixed with 0: " + modeText);
        }
        final int modeBits = Integer.parseInt(modeText, 8);
        final StringBuilder modeString = new StringBuilder(9);
        for (int i = 0; i < 9; i++) {
            modeString.append((modeBits & (1 << (8 - i))) == 0 ? '-' : "rwxrwxrwx".charAt(i));
        }
        final Set<PosixFilePermission> socketPermissions = PosixFilePermissions.fromString(modeString.toString());
        final String pathText = parts[0].trim();
        if (pathText.isEmpty()) throw new IOException("listenSocket requires an absolute socket path before ,MODE");
        final Path requested = Path.of(pathText).normalize();
        if (!requested.isAbsolute() || requested.getParent() == null || requested.getFileName() == null) {
            throw new IOException("listenSocket must be an absolute file path");
        }
        final Path parent = requested.getParent().toRealPath();
        final Path socket = parent.resolve(requested.getFileName());
        if (socket.toString().getBytes(StandardCharsets.UTF_8).length > 100) {
            throw new IOException("listenSocket path must be at most 100 UTF-8 bytes");
        }
        if (!Files.getFileStore(parent).supportsFileAttributeView("posix")) {
            throw new IOException("Unix socket mode requires a POSIX filesystem; use TCP on this platform");
        }
        final PosixFileAttributes directory = Files.readAttributes(parent, PosixFileAttributes.class);
        final var currentUser = parent.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        final Set<PosixFilePermission> mode = directory.permissions();
        if (!directory.isDirectory() || !directory.owner().equals(currentUser)
                || !mode.containsAll(PosixFilePermissions.fromString("rwx------"))
                || mode.contains(PosixFilePermission.GROUP_WRITE)
                || mode.contains(PosixFilePermission.OTHERS_READ) || mode.contains(PosixFilePermission.OTHERS_WRITE)
                || mode.contains(PosixFilePermission.OTHERS_EXECUTE)) {
            throw new IOException("Socket directory must be owned by the Java user with mode 0700 or 0750: " + parent);
        }
        final Path lockPath = socket.resolveSibling(socket.getFileName() + ".lock");
        if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            final PosixFileAttributes attributes = Files.readAttributes(lockPath, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || !attributes.owner().equals(currentUser)
                    || !PosixFilePermissions.toString(attributes.permissions()).equals("rw-------")) {
                throw new IOException("Refusing an unsafe socket lock: " + lockPath);
            }
        }
        final FileChannel channel = FileChannel.open(lockPath,
                Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            final FileLock lock;
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException e) { throw new IOException("Socket listener is already starting or running", e); }
            if (lock == null) throw new IOException("Socket listener is already starting or running");
            final UnixSocketSupport support = new UnixSocketSupport(socket, channel, lock, socketPermissions);
            if (Files.exists(socket, LinkOption.NOFOLLOW_LINKS)) {
                support.close();
                throw new IOException("Socket path already exists; stop its owner before removing a stale socket: " + socket);
            }
            return support;
        } catch (IOException | RuntimeException e) { channel.close(); throw e; }
    }

    /** Keep Latke's HTTP pipeline and native transport; no second TCP listener is opened. */
    public void listen(final Consumer<String> bind, final Runnable shutdown) throws Exception {
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread server = new Thread(() -> {
            try { bind.accept(path.toString()); }
            catch (Throwable e) { failure.set(e); }
        }, "volo-unix-listener");
        synchronized (this) {
            if (closed || !ACTIVE.compareAndSet(null, this)) throw new IOException("Unix listener is closed or already active");
            started = true;
            server.start();
        }
        try {
            final long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
            while (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                if (!server.isAlive() || System.nanoTime() > deadline) throw new IOException("Unix socket did not bind", failure.get());
                Thread.sleep(10);
            }
            final BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isOther() || attributes.fileKey() == null) throw new IOException("Listener did not create a Unix socket");
            synchronized (this) {
                if (closed) throw new IOException("Unix listener stopped during startup");
                socketKey = attributes.fileKey();
                Files.setAttribute(path, "posix:group", Files.readAttributes(path.getParent(), PosixFileAttributes.class).group());
                Files.setPosixFilePermissions(path, permissions);
            }
            server.join();
            if (failure.get() != null) throw new IOException("Unix socket listener failed", failure.get());
        } finally {
            if (server.isAlive()) shutdown.run();
            server.join(10000);
            close();
        }
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        ACTIVE.compareAndSet(this, null);
        try {
            if (started && Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                final BasicFileAttributes current = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (current.isOther() && (socketKey == null || socketKey.equals(current.fileKey()))) Files.delete(path);
            }
        } finally { try { lock.release(); } finally { lockChannel.close(); } }
        // Keep the lock inode: unlinking it would let a concurrent process lock another inode.
    }
}
