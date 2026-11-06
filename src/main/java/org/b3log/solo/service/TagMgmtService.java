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
package org.b3log.solo.service;

import org.apache.logging.log4j.Level;
import org.b3log.latke.Keys;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.repository.Transaction;
import org.b3log.latke.service.ServiceException;
import org.b3log.latke.service.annotation.Service;
import org.b3log.solo.cache.PublicViewCache;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.repository.CategoryTagRepository;
import org.b3log.solo.repository.TagArticleRepository;
import org.b3log.solo.repository.TagRepository;
import org.json.JSONObject;

import java.util.List;

/**
 * Tag management service.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 0.4.0
 */
@Service
public class TagMgmtService {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(TagMgmtService.class);

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
     * Category-tag repository.
     */
    @Inject
    private CategoryTagRepository categoryTagRepository;

    /**
     * Tag-Article repository.
     */
    @Inject
    private TagArticleRepository tagArticleRepository;

    @Inject
    private PublicViewCache publicViewCache;

    @Inject
    private InstantSearchService instantSearchService;

    @Inject
    private SyndicationCacheService syndicationCacheService;

    /**
     * Removes all unused tags using a single {@code NOT EXISTS} query without N+1 per-tag count lookups.
     *
     * @throws ServiceException if get tags failed, or remove failed
     */
    public void removeUnusedTags() throws ServiceException {
        final Transaction transaction = tagRepository.beginTransaction();
        boolean removed = false;

        try {
            final List<JSONObject> unusedTags = tagRepository.getUnusedTags();
            for (final JSONObject tag : unusedTags) {
                final String tagId = tag.optString(Keys.OBJECT_ID);
                if (!tagId.isEmpty()) {
                    categoryTagRepository.removeByTagId(tagId);
                    tagRepository.remove(tagId);
                    removed = true;
                }
            }

            transaction.commit();
            if (removed) {
                if (null != publicViewCache) {
                    publicViewCache.invalidateAll();
                }
                if (null != instantSearchService) {
                    instantSearchService.invalidate();
                }
                if (null != syndicationCacheService) {
                    syndicationCacheService.invalidate();
                }
            }
        } catch (final Exception e) {
            if (transaction.isActive()) {
                transaction.rollback();
            }

            LOGGER.log(Level.ERROR, "Removes unused tags failed", e);

            throw new ServiceException(e);
        }
    }
}
