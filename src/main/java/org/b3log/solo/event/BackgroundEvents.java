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
/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.event;

import org.apache.logging.log4j.Level;
import org.b3log.latke.repository.jdbc.JdbcRepository;
import org.b3log.solo.logging.Logger;
import org.json.JSONObject;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Network event work must not run inside Latke's shared, synchronized render-event queue. */
public final class BackgroundEvents {
    private static final Logger LOGGER = Logger.getLogger(BackgroundEvents.class);
    private static final ThreadPoolExecutor FOLLOWS = worker("volo-follow-refresh");
    private static final ThreadPoolExecutor COMMUNITY = worker("volo-community-sync");

    private BackgroundEvents() { }

    private static ThreadPoolExecutor worker(final String name) {
        final ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), task -> {
            final Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        }, (task, executor) -> LOGGER.log(Level.WARN, "Background event queue is full or stopped: {0}", name));
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    public static void follow(final Runnable task) { FOLLOWS.execute(() -> run(task)); }

    public static void community(final JSONObject data, final Consumer<JSONObject> action) {
        final JSONObject snapshot = org.b3log.solo.util.Solos.clone(data);
        COMMUNITY.execute(() -> run(() -> action.accept(snapshot)));
    }

    private static void run(final Runnable task) {
        try {
            task.run();
        } catch (final Exception failure) {
            LOGGER.log(Level.ERROR, "Background event failed", failure);
        } finally {
            try {
                JdbcRepository.dispose();
            } finally {
                org.b3log.latke.util.Stopwatchs.release();
            }
        }
    }

    public static void shutdown() {
        FOLLOWS.shutdownNow();
        COMMUNITY.shutdownNow();
    }
}
