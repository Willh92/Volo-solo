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

import org.b3log.latke.event.Event;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class FollowArticleRefresherTest {
    @Test(timeOut = 5000) public void aStalledFeedDoesNotHoldTheEventListener() throws Exception {
        final var worker = Executors.newSingleThreadExecutor();
        final var started = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        try {
            final var listener = new FollowArticleRefresher(worker, data -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            listener.action(new Event<>(EventTypes.FOLLOW_ARTICLE_REFRESH, new JSONObject().put("followTitle", "Test")));
            Assert.assertTrue(started.await(2, TimeUnit.SECONDS));
            Assert.assertEquals(release.getCount(), 1L, "The listener returned while the network worker was still blocked");
        } finally {
            release.countDown();
            worker.shutdownNow();
            Assert.assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test public void queuedWorkKeepsItsOwnSnapshot() {
        final var task = new AtomicReference<Runnable>();
        final var refreshed = new AtomicReference<JSONObject>();
        final var listener = new FollowArticleRefresher(task::set, refreshed::set);
        final var source = new JSONObject().put("followTitle", "Before edit");
        listener.action(new Event<>(EventTypes.FOLLOW_ARTICLE_REFRESH, source));
        source.put("followTitle", "After edit");
        task.get().run();
        Assert.assertEquals(refreshed.get().getString("followTitle"), "Before edit");
    }
}
