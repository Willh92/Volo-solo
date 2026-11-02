/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.log4j;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class LogJournalTest {
    @Test
    public void pagesSurviveRestartAndNewEntriesDoNotShiftSnapshot() throws Exception {
        final Path path = Files.createTempDirectory("volo-log-pages-");
        try {
            final LogJournal journal = new LogJournal(path, 20000, 3);
            for (int i = 1; i <= 32; i++) journal.append(Map.of("id", i, "message", "中文\n日志 " + i));
            final JSONObject first = journal.page(1, 10, 32);
            Assert.assertEquals(first.getInt("total"), 32);
            Assert.assertEquals(first.getInt("pageCount"), 4);
            Assert.assertEquals(first.getJSONArray("result").getJSONObject(0).getInt("id"), 32);
            journal.append(Map.of("id", 33, "message", "new"));
            final LogJournal restarted = new LogJournal(path, 20000, 3);
            final JSONObject second = restarted.page(2, 10, 32);
            Assert.assertEquals(second.getJSONArray("result").getJSONObject(0).getInt("id"), 22);
            Assert.assertEquals(second.getInt("total"), 32);
            Assert.assertEquals(restarted.page(99, 10, 33).getInt("page"), 4);
            Assert.assertEquals(restarted.page(4, 10, 32).getJSONArray("result").length(), 2);
        } finally { FileUtils.deleteDirectory(path.toFile()); }
    }

    @Test
    public void rotatesWithinBudgetAndSkipsIncompleteRecords() throws Exception {
        final Path path = Files.createTempDirectory("volo-log-rotate-");
        try {
            final LogJournal journal = new LogJournal(path, 1024, 3);
            for (int i = 1; i <= 40; i++) journal.append(Map.of("id", i, "message", "x".repeat(200)));
            try (var files = Files.list(path)) {
                final var all = files.toList(); Assert.assertEquals(all.size(), 3);
                for (Path file : all) Assert.assertTrue(Files.size(file) <= 1024);
            }
            final JSONObject page = journal.page(1, 100, 40);
            Assert.assertTrue(page.getInt("total") < 40);
            Assert.assertEquals(page.getJSONArray("result").getJSONObject(0).getInt("id"), 40);
            Files.writeString(path.resolve("admin-events.0.jsonl"), "{incomplete\n", java.nio.file.StandardOpenOption.APPEND);
            Assert.assertEquals(journal.page(1, 100, 40).getInt("total"), page.getInt("total"));
        } finally { FileUtils.deleteDirectory(path.toFile()); }
    }

    @Test
    public void storageFailureDoesNotBreakLogging() throws Exception {
        final Path path = Files.createTempFile("volo-log-unwritable-", ".tmp");
        try {
            final LogJournal journal = new LogJournal(path);
            journal.append(Map.of("id", 1, "message", "test"));
            Assert.assertFalse(journal.isAvailable());
            journal.append(Map.of("id", 2, "message", "still safe"));
        } finally { Files.delete(path); }
    }

    @Test
    public void memoryEntriesLimitMessagesAndStackTraces() {
        final RamAppender appender = RamAppender.create("bounded-test");
        final Throwable error = new IllegalStateException("e".repeat(10000));
        appender.append(Log4jLogEvent.newBuilder().setLoggerName("test").setLevel(Level.ERROR)
                .setMessage(new SimpleMessage("x".repeat(100000))).setThrown(error).build());
        final Map<String, Object> entry = RamAppender.getList().getLast();
        Assert.assertTrue(entry.get("message").toString().length() < 4200);
        final JSONObject detail = new JSONObject((Map<?, ?>) entry.get("throwable"));
        Assert.assertTrue(detail.getString("message").length() < 4200);
        Assert.assertTrue(detail.getJSONArray("stackTrace").length() <= 12);
    }
}
