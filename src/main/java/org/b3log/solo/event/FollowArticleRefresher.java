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
package org.b3log.solo.event;

import org.b3log.latke.event.AbstractEventListener;
import org.b3log.latke.event.Event;
import org.b3log.latke.ioc.BeanManager;
import org.b3log.latke.ioc.Singleton;
import org.apache.logging.log4j.Level;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.service.FollowService;
import org.json.JSONObject;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 *
 * @author <a href="https://github.com/gakkiyomi">Gakkiyomi (Bolo Commiter)</a>
 * @since 0.0.1
 */
@Singleton
public class FollowArticleRefresher extends AbstractEventListener<JSONObject> {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(FollowArticleRefresher.class);

    private final Executor worker;
    private final Consumer<JSONObject> refresh;

    public FollowArticleRefresher() {
        this(BackgroundEvents::follow, data -> BeanManager.getInstance().getReference(FollowService.class).syncFollowArticles(data));
    }

    FollowArticleRefresher(final Executor worker, final Consumer<JSONObject> refresh) {
        this.worker = worker;
        this.refresh = refresh;
    }

    @Override
    public void action(final Event<JSONObject> event) {
        final JSONObject data = event.getData();
        LOGGER.log(Level.DEBUG, "Processing an event [type={0}] in listener [className={1}]", event.getType(), FollowArticleRefresher.class.getName());
        // Latke serializes event listeners with page-render events. Never fetch a feed while holding that lock.
        final JSONObject snapshot = new JSONObject(data.toString());
        worker.execute(() -> refresh.accept(snapshot));
    }

    /**
     * Gets the event type {@linkplain EventTypes#FOLLOW_ARTICLE_REFRESH}.
     *
     * @return event type
     */
    @Override
    public String getEventType() {
        return EventTypes.FOLLOW_ARTICLE_REFRESH;
    }
}
