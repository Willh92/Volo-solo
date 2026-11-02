/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.log4j;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Bounded, local history for the administrator's log viewer. No request controls its paths. */
public final class LogJournal {
    public static final long FILE_BYTES = 2 * 1024 * 1024;
    public static final int FILE_COUNT = 5;
    private final Path directory;
    private final long fileBytes;
    private final int fileCount;
    private boolean available = true;

    public LogJournal(final Path directory) {
        this(directory, FILE_BYTES, FILE_COUNT);
    }

    LogJournal(final Path directory, final long fileBytes, final int fileCount) {
        this.directory = directory.toAbsolutePath().normalize();
        this.fileBytes = fileBytes;
        this.fileCount = fileCount;
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

    public synchronized void append(final Map<String, Object> entry) {
        if (!available) return;
        try {
            checkPaths();
            final byte[] bytes = (new JSONObject(entry) + "\n").getBytes(StandardCharsets.UTF_8);
            if (bytes.length > fileBytes) return;
            if (Files.exists(file(0)) && Files.size(file(0)) + bytes.length > fileBytes) {
                for (int i = fileCount - 1; i > 0; i--) {
                    if (Files.exists(file(i - 1))) Files.move(file(i - 1), file(i), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.write(file(0), bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | RuntimeException failure) {
            available = false;
            // Do not recurse into this appender when storage is unavailable.
            System.err.println("Volo log history is unavailable; recent in-memory logs remain accessible.");
        }
    }

    public synchronized boolean isAvailable() { return available; }

    public synchronized JSONObject page(final int requestedPage, final int pageSize, final long through) throws IOException {
        checkPaths();
        final long[] total = {0};
        forEach(through, entry -> total[0]++);
        final int pages = Math.max(1, (int) ((total[0] + pageSize - 1) / pageSize));
        final int page = Math.min(Math.max(1, requestedPage), pages);
        final long start = Math.max(0, total[0] - (long) page * pageSize);
        final long end = total[0] - (long) (page - 1) * pageSize;
        final List<Map<String, Object>> rows = new ArrayList<>();
        final long[] index = {0};
        forEach(through, entry -> { if (index[0] >= start && index[0] < end) rows.add(entry.toMap()); index[0]++; });
        Collections.reverse(rows);
        return new JSONObject().put("result", rows).put("page", page).put("pageCount", pages)
                .put("pageSize", pageSize).put("total", total[0]).put("through", through).put("persistent", true);
    }

    private void forEach(final long through, final java.util.function.Consumer<JSONObject> action) throws IOException {
        for (int i = fileCount - 1; i >= 0; i--) {
            if (!Files.exists(file(i), LinkOption.NOFOLLOW_LINKS) || Files.size(file(i)) > fileBytes) continue;
            // Only read files owned by this journal, and never follow a symlink.
            try (BufferedReader reader = new BufferedReader(java.nio.channels.Channels.newReader(
                    Files.newByteChannel(file(i), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.length() > 32768) continue;
                    try {
                        final JSONObject entry = new JSONObject(line);
                        if (entry.optLong("id", Long.MAX_VALUE) <= through) action.accept(entry);
                    } catch (org.json.JSONException ignored) { /* A partial final write must not hide older records. */ }
                }
            }
        }
    }
}
