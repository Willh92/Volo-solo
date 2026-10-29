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
import org.b3log.solo.bolo.tool.FixSizeLinkedList;
import java.text.SimpleDateFormat;
import java.util.*;
@Plugin(name="Ram", category="Core", elementType="appender", printObject=true)
public final class RamAppender extends AbstractAppender {
    private static final FixSizeLinkedList<Map<String,Object>> list = new FixSizeLinkedList<>(100);
    private static long id;
    private RamAppender(final String name) { super(name, null, PatternLayout.createDefaultLayout(), true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY); }
    @PluginFactory public static RamAppender create(@PluginAttribute("name") final String name) { return new RamAppender(name); }
    public static synchronized FixSizeLinkedList<Map<String,Object>> getList() {
        final FixSizeLinkedList<Map<String,Object>> copy = new FixSizeLinkedList<>(100); copy.addAll(list); return copy;
    }
    @Override public void append(final LogEvent event) {
        final Map<String,Object> map = new HashMap<>();
        map.put("name", event.getLoggerName());
        map.put("date", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss:SS").format(new Date(event.getTimeMillis())));
        map.put("level", event.getLevel().toString());
        map.put("message", event.getMessage().getFormattedMessage());
        final StackTraceElement source = event.getSource();
        map.put("methodName", source == null ? "" : source.getMethodName());
        map.put("lineNumber", source == null ? "" : String.valueOf(source.getLineNumber()));
        map.put("throwable", null);
        if (event.getThrown() != null) {
            final Throwable error = event.getThrown();
            final Map<String,Object> detail = new HashMap<>();
            detail.put("message", error.getMessage()); detail.put("class", error.getClass().getName()); detail.put("stackTrace", error.getStackTrace());
            map.put("throwable", detail);
        }
        synchronized (RamAppender.class) { map.put("id", id); id = id == Long.MAX_VALUE ? 0 : id + 1; list.add(map); }
    }
}
