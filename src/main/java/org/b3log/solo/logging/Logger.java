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
package org.b3log.solo.logging;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.message.MessageFormatMessage;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.spi.ExtendedLogger;

/** Preserves the application's indexed log messages while using Log4j 2. */
public final class Logger {
    private static final String FQCN = Logger.class.getName();
    private final ExtendedLogger delegate;
    private Logger(final Class<?> type) { delegate = (ExtendedLogger) LogManager.getLogger(type); }
    public static Logger getLogger(final Class<?> type) { return new Logger(type); }
    public org.apache.logging.log4j.Logger unwrap() { return delegate; }
    public void log(final Level level, final String message, final Object... args) {
        delegate.logIfEnabled(FQCN, level, null, args.length == 0 ? new SimpleMessage(message) : new MessageFormatMessage(message, args), null);
    }
    public void log(final Level level, final String message, final Throwable error) { delegate.logIfEnabled(FQCN, level, null, (org.apache.logging.log4j.message.Message) new SimpleMessage(message), error); }
    public void debug(final Object message) { log(Level.DEBUG, String.valueOf(message)); }
    public void info(final Object message) { log(Level.INFO, String.valueOf(message)); }
    public void warn(final Object message) { log(Level.WARN, String.valueOf(message)); }
    public void error(final Object message) { log(Level.ERROR, String.valueOf(message)); }
    public void error(final String message, final Throwable error) { log(Level.ERROR, message, error); }
    public void trace(final Object message) { log(Level.TRACE, String.valueOf(message)); }
    public boolean isDebugEnabled() { return delegate.isDebugEnabled(); }
    public boolean isTraceEnabled() { return delegate.isTraceEnabled(); }
}
