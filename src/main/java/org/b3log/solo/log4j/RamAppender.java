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
package org.b3log.solo.log4j;
import org.apache.logging.log4j.core.*;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.plugins.*;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.commons.lang3.time.FastDateFormat;
import org.b3log.solo.bolo.tool.FixSizeLinkedList;
import java.util.*;
@Plugin(name="Ram", category="Core", elementType="appender", printObject=true)
public final class RamAppender extends AbstractAppender {
    private static final FastDateFormat DATE_FORMAT = FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss:SS");
    private static final FixSizeLinkedList<Map<String,Object>> list = new FixSizeLinkedList<>(100);
    private static long id;
    private static String journalBaseDir = "";
    private static LogJournal journal = initJournal();

    private static LogJournal initJournal() {
        final String baseDir = System.getProperty("base.dir", "");
        journalBaseDir = baseDir == null ? "" : baseDir;
        return journalBaseDir.isBlank() ? null : new LogJournal(java.nio.file.Path.of(journalBaseDir, "logs"));
    }

    private static LogJournal currentJournal() {
        final String baseDir = System.getProperty("base.dir", "");
        final String normalized = baseDir == null ? "" : baseDir;
        if (!normalized.equals(journalBaseDir)) {
            if (journal != null) {
                journal.close();
            }
            journalBaseDir = normalized;
            journal = normalized.isBlank() ? null : new LogJournal(java.nio.file.Path.of(normalized, "logs"));
        }
        return journal;
    }

    public static synchronized void close() {
        if (journal != null) {
            journal.close();
        }
    }

    public static String bounded(final String value) {
        if (value == null) return "";
        return value.length() <= 4096 ? value : value.substring(0, 4096) + " …[truncated]";
    }
    public static synchronized org.json.JSONObject page(final int requestedPage, final int pageSize, final long through) {
        final long snapshot = through == Long.MAX_VALUE ? Math.max(id, System.currentTimeMillis()) : through;
        final LogJournal activeJournal = currentJournal();
        if (activeJournal != null && activeJournal.isAvailable()) {
            try { return activeJournal.page(requestedPage, pageSize, snapshot); }
            catch (java.io.IOException | RuntimeException ignored) { /* Fall back to recent memory. */ }
        }
        final List<Map<String,Object>> rows = new ArrayList<>();
        for (Map<String,Object> entry : list) if (((Number) entry.get("id")).longValue() <= snapshot) rows.add(entry);
        Collections.reverse(rows);
        final int pages = Math.max(1, (rows.size() + pageSize - 1) / pageSize);
        final int page = Math.min(Math.max(1, requestedPage), pages), start = (page - 1) * pageSize;
        return new org.json.JSONObject().put("result", rows.subList(start, Math.min(rows.size(), start + pageSize)))
                .put("page", page).put("pageCount", pages).put("pageSize", pageSize).put("total", rows.size())
                .put("through", snapshot).put("persistent", false);
    }
    private RamAppender(final String name) { super(name, null, PatternLayout.createDefaultLayout(), true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY); }
    @PluginFactory public static RamAppender create(@PluginAttribute("name") final String name) { return new RamAppender(name); }
    public static synchronized FixSizeLinkedList<Map<String,Object>> getList() {
        final FixSizeLinkedList<Map<String,Object>> copy = new FixSizeLinkedList<>(100); copy.addAll(list); return copy;
    }
    @Override public void append(final LogEvent event) {
        final Map<String,Object> map = new HashMap<>();
        final String loggerName = event.getLoggerName() == null ? "" : event.getLoggerName();
        map.put("name", loggerName);
        map.put("date", DATE_FORMAT.format(event.getTimeMillis()));
        map.put("level", event.getLevel().toString());
        map.put("message", bounded(event.getMessage() == null ? "" : event.getMessage().getFormattedMessage()));
        final StackTraceElement source = event.getSource();
        map.put("methodName", source == null ? loggerName : source.getMethodName());
        map.put("lineNumber", source == null ? "" : String.valueOf(source.getLineNumber()));
        map.put("throwable", null);
        if (event.getThrown() != null) {
            final Throwable error = event.getThrown();
            final Map<String,Object> detail = new HashMap<>();
            detail.put("message", bounded(error.getMessage())); detail.put("class", error.getClass().getName());
            detail.put("stackTrace", Arrays.stream(error.getStackTrace()).limit(12).map(StackTraceElement::toString).map(frame -> frame.length() > 512 ? frame.substring(0, 512) : frame).toList());
            map.put("throwable", detail);
        }
        synchronized (RamAppender.class) {
            id = Math.max(id + 1, System.currentTimeMillis()); map.put("id", id); list.add(map);
            final LogJournal activeJournal = currentJournal();
            if (activeJournal != null) activeJournal.append(map);
        }
    }
}
