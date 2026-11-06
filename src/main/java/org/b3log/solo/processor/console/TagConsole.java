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
package org.b3log.solo.processor.console;

import org.apache.logging.log4j.Level;
import org.b3log.latke.Keys;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.http.renderer.JsonRenderer;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.ioc.Singleton;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.model.Common;
import org.b3log.solo.model.Tag;
import org.b3log.solo.repository.TagRepository;
import org.b3log.solo.service.TagQueryService;
import org.b3log.solo.web.annotation.Before;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Tag console request processing.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 0.4.0
 */
@Singleton
public class TagConsole {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(TagConsole.class);

    /**
     * Tag query service.
     */
    @Inject
    private TagQueryService tagQueryService;

    /**
     * Tag repository.
     */
    @Inject
    private TagRepository tagRepository;

    /**
     * Gets all tags.
     * <p>
     * Renders the response with a json object, for example,
     * <pre>
     * {
     *     "sc": boolean,
     *     "tags": [
     *         {"tagTitle": "", ....},
     *         ....
     *     ]
     * }
     * </pre>
     * </p>
     *
     * @param context the specified request context
     */
    @Before(ConsoleAuthAdvice.class)
    public void getTags(final RequestContext context) {
        final JsonRenderer renderer = new JsonRenderer();
        context.setRenderer(renderer);
        final JSONObject jsonObject = new JSONObject();
        renderer.setJSONObject(jsonObject);

        try {
            jsonObject.put(Tag.TAGS, tagQueryService.getTags());
            jsonObject.put(org.b3log.solo.model.Common.STATUS_CODE, true);
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Gets tags failed", e);

            jsonObject.put(org.b3log.solo.model.Common.STATUS_CODE, false);
        }
    }

    /**
     * Gets all unused tags.
     * <p>
     * Renders the response with a json object, for example,
     * <pre>
     * {
     *     "sc": boolean,
     *     "unusedTags": [
     *         {"tagTitle": "", ....},
     *         ....
     *     ]
     * }
     * </pre>
     * </p>
     *
     * @param context the specified request context
     */
    @Before(ConsoleAdminAuthAdvice.class)
    public void getUnusedTags(final RequestContext context) {
        final JsonRenderer renderer = new JsonRenderer();
        context.setRenderer(renderer);
        final JSONObject jsonObject = new JSONObject();
        renderer.setJSONObject(jsonObject);

        try {
            final List<JSONObject> unusedTags;
            if (null != tagRepository) {
                unusedTags = tagRepository.getUnusedTags();
            } else {
                unusedTags = new ArrayList<>();
                final List<JSONObject> tags = tagQueryService.getTags();
                for (final JSONObject tag : tags) {
                    final String tagId = tag.optString(Keys.OBJECT_ID);
                    if (1 > tagQueryService.getArticleCount(tagId)) {
                        unusedTags.add(tag);
                    }
                }
            }
            jsonObject.put(Common.UNUSED_TAGS, unusedTags);
            jsonObject.put(org.b3log.solo.model.Common.STATUS_CODE, true);
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Gets unused tags failed", e);

            jsonObject.put(Common.UNUSED_TAGS, new ArrayList<>());
            jsonObject.put(org.b3log.solo.model.Common.STATUS_CODE, false);
        }
    }
}
