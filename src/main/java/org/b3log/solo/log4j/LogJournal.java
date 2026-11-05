/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.log4j;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bounded, local history for the administrator's log viewer. No request controls its paths. */
public final class LogJournal implements Closeable {
    public static final long FILE_BYTES = 2 * 1024 * 1024;
    public static final int FILE_COUNT = 5;
    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");
    private static final Path TEMP_ROOT = resolveTempRoot();

    private final Path directory;
    private final long fileBytes;
    private final int fileCount;
    private final boolean keepStreamOpen;
    private boolean available = true;
    private boolean pathsChecked = false;
    private BufferedOutputStream out;
    private long activeBytes = -1L;

    public LogJournal(final Path directory) {
        this(directory, FILE_BYTES, FILE_COUNT, false);
    }

    LogJournal(final Path directory, final long fileBytes, final int fileCount) {
        this(directory, fileBytes, fileCount, true);
    }

    private LogJournal(final Path directory, final long fileBytes, final int fileCount, final boolean testInstance) {
        this.directory = directory.toAbsolutePath().normalize();
        this.fileBytes = fileBytes;
        this.fileCount = fileCount;
        // On Windows NTFS, open file handles inside temp directories prevent directory deletion until closed.
        this.keepStreamOpen = !WINDOWS || (!testInstance && (TEMP_ROOT == null || !this.directory.startsWith(TEMP_ROOT)));
    }

    private static Path resolveTempRoot() {
        try {
            final String tmp = System.getProperty("java.io.tmpdir", "");
            return tmp.isBlank() ? null : Path.of(tmp).toAbsolutePath().normalize();
        } catch (final RuntimeException ignored) {
            return null;
        }
    }

    private Path file(final int index) { return directory.resolve("admin-events." + index + ".jsonl"); }

    private void checkPaths() throws IOException {
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unsafe log directory");
        for (int i = 0; i < fileCount; i++) {
            final Path file = file(i);
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Unsafe log file");
            }
        }
    }

    private void closeStream() {
        if (out != null) {
            try {
                out.close();
            } catch (final IOException ignored) {
            } finally {
                out = null;
            }
        }
    }

    @Override
    public synchronized void close() {
        closeStream();
        pathsChecked = false;
        activeBytes = -1L;
    }

    public synchronized void append(final Map<String, Object> entry) {
        if (!available) return;
        try {
            if (!pathsChecked) {
                checkPaths();
                pathsChecked = true;
            }
            final byte[] bytes = (new JSONObject(entry) + "\n").getBytes(StandardCharsets.UTF_8);
            if (bytes.length > fileBytes) return;
            final Path activeFile = file(0);
            if (activeBytes < 0L) {
                activeBytes = Files.exists(activeFile, LinkOption.NOFOLLOW_LINKS) ? Files.size(activeFile) : 0L;
            }
            if (activeBytes > 0L && activeBytes + bytes.length > fileBytes) {
                closeStream();
                for (int i = fileCount - 1; i > 0; i--) {
                    final Path prev = file(i - 1);
                    if (Files.exists(prev, LinkOption.NOFOLLOW_LINKS)) {
                        Files.move(prev, file(i), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                activeBytes = 0L;
            }
            if (out == null) {
                out = new BufferedOutputStream(
                        Files.newOutputStream(activeFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS),
                        8192);
            }
            out.write(bytes);
            out.flush();
            activeBytes += bytes.length;
            if (!keepStreamOpen) {
                closeStream();
            }
        } catch (IOException | RuntimeException failure) {
            closeStream();
            available = false;
            // Do not recurse into this appender when storage is unavailable.
            System.err.println("Volo log history is unavailable; recent in-memory logs remain accessible.");
        }
    }

    public synchronized boolean isAvailable() { return available; }

    public synchronized JSONObject page(final int requestedPage, final int pageSize, final long through) throws IOException {
        if (out != null) {
            out.flush();
        }
        checkPaths();
        pathsChecked = true;

        final int safePageSize = Math.max(1, pageSize);
        final int reqPage = Math.max(1, requestedPage);
        final long maxPossibleTotal = Math.max(1024L, (fileBytes * fileCount) / 2L + 16L);
        final long maxPossiblePages = Math.max(1L, (maxPossibleTotal + safePageSize - 1) / safePageSize);
        final long tailCap = reqPage <= maxPossiblePages ? (long) reqPage * safePageSize : 0L;

        final List<String> headLines = new ArrayList<>(Math.min(safePageSize, 128));
        final ArrayDeque<String> tailLines = tailCap > 0L
                ? new ArrayDeque<>(Math.min((int) Math.min(tailCap, 1024L), 1024)) : null;
        long total = 0L;

        for (int i = fileCount - 1; i >= 0; i--) {
            final Path f = file(i);
            if (!Files.exists(f, LinkOption.NOFOLLOW_LINKS) || Files.size(f) > fileBytes) continue;
            // Only read files owned by this journal, and never follow a symlink.
            try (BufferedReader reader = new BufferedReader(java.nio.channels.Channels.newReader(
                    Files.newByteChannel(f, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.length() > 32768) continue;
                    try {
                        final JSONObject entry = new JSONObject(line);
                        if (entry.optLong("id", Long.MAX_VALUE) <= through) {
                            if (headLines.size() < safePageSize) {
                                headLines.add(line);
                            }
                            if (tailLines != null) {
                                if (tailLines.size() >= tailCap) {
                                    tailLines.pollFirst();
                                }
                                tailLines.addLast(line);
                            }
                            total++;
                        }
                    } catch (org.json.JSONException ignored) { /* A partial final write must not hide older records. */ }
                }
            }
        }

        final int pages = Math.max(1, (int) ((total + safePageSize - 1) / safePageSize));
        final int page = Math.min(reqPage, pages);
        final long start = Math.max(0L, total - (long) page * safePageSize);
        final long end = Math.max(0L, total - (long) (page - 1) * safePageSize);
        final List<Map<String, Object>> rows = new ArrayList<>((int) Math.max(0L, end - start));

        if (end > start) {
            if (reqPage > pages) {
                final int limit = (int) Math.min(end, headLines.size());
                for (int idx = 0; idx < limit; idx++) {
                    rows.add(new JSONObject(headLines.get(idx)).toMap());
                }
            } else if (tailLines != null) {
                long cur = total - tailLines.size();
                for (final String raw : tailLines) {
                    if (cur >= start && cur < end) {
                        rows.add(new JSONObject(raw).toMap());
                    }
                    cur++;
                    if (cur >= end) break;
                }
            }
            Collections.reverse(rows);
        }

        return new JSONObject().put("result", rows).put("page", page).put("pageCount", pages)
                .put("pageSize", pageSize).put("total", total).put("through", through).put("persistent", true);
    }
}
