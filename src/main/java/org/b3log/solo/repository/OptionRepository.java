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
package org.b3log.solo.repository;

import org.b3log.latke.Keys;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.repository.*;
import org.b3log.latke.repository.annotation.Repository;
import org.b3log.solo.cache.OptionCache;
import org.b3log.solo.model.Option;
import org.json.JSONObject;

import java.util.List;

/**
 * Option repository.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 0.6.0
 */
@Repository
public class OptionRepository extends AbstractRepository {

    /**
     * Option cache.
     */
    @Inject
    private OptionCache optionCache;

    /**
     * Public constructor.
     */
    public OptionRepository() {
        super(Option.OPTION);
    }

    /** Discard a potentially uncommitted cached option after a document transaction. */
    public void evictOption(final String id) { if (optionCache != null) optionCache.evictOption(id); }

    /** Evicts a single option ID without marking it as missing. */
    public void evict(final String id) { if (optionCache != null) optionCache.evict(id); }

    /**
     * Reads a scalar string option from the specified category without cloning the full category snapshot on cache hit.
     *
     * @param category     option category
     * @param key          option key
     * @param defaultValue fallback value when absent
     * @return option value or {@code defaultValue}
     * @throws RepositoryException repository exception
     */
    public String getCategoryOptionString(final String category, final String key, final String defaultValue)
            throws RepositoryException {
        if (null == category || null == key) {
            return defaultValue;
        }
        if (null != optionCache && optionCache.hasCategory(category)) {
            return optionCache.getCategoryString(category, key, defaultValue);
        }
        final JSONObject options = getOptions(category);
        if (null == options) {
            return defaultValue;
        }
        return options.optString(key, defaultValue);
    }

    @Override
    public String add(final JSONObject option) throws RepositoryException {
        final String id = super.add(option);
        option.put(Keys.OBJECT_ID, id);
        if (null != optionCache && !OptionCache.isDocumentChunk(option)) {
            optionCache.putOption(option);
        }
        return id;
    }

    @Override
    public void remove(final String id) throws RepositoryException {
        if (OptionCache.isDocumentChunkId(id)) {
            final JSONObject chunk = super.get(id);
            if (null != chunk) {
                super.remove(id);
            }
            if (null != optionCache) {
                optionCache.evict(id);
            }
            return;
        }

        final JSONObject option = get(id);
        if (null == option) {
            return;
        }

        super.remove(id);
        if (null != optionCache) {
            if (OptionCache.isDocumentChunk(option)) {
                optionCache.evict(id);
            } else {
                optionCache.removeOption(id);
                optionCache.markMissing(id);
            }
        }
    }

    @Override
    public JSONObject get(final String id) throws RepositoryException {
        if (OptionCache.isDocumentChunkId(id)) {
            return super.get(id);
        }
        if (null != optionCache) {
            final JSONObject cached = optionCache.getOption(id);
            if (null != cached) {
                return cached;
            }
            if (optionCache.isMissing(id)) {
                return null;
            }
        }

        final JSONObject ret = super.get(id);
        if (null == ret) {
            if (null != optionCache) {
                optionCache.markMissing(id);
            }
            return null;
        }

        if (OptionCache.isDocumentChunk(ret)) {
            return ret;
        }

        if (null != optionCache) {
            optionCache.putOption(ret);
        }

        return org.b3log.solo.util.Solos.clone(ret);
    }

    @Override
    public void update(final String id, final JSONObject option, final String... propertyNames) throws RepositoryException {
        super.update(id, option, propertyNames);

        option.put(Keys.OBJECT_ID, id);
        if (null != optionCache) {
            if (OptionCache.isDocumentChunk(option)) {
                optionCache.evict(id);
            } else if (null != propertyNames && propertyNames.length > 0) {
                optionCache.removeOption(id);
            } else {
                optionCache.putOption(option);
            }
        }
    }

    /**
     * Gets options with the specified category.
     * <p>
     * All options with the specified category will be merged into one json object as the return value.
     * </p>
     *
     * @param category the specified category
     * @return all options with the specified category, for example,
     * <pre>
     * {
     *     "${optionId}": "${optionValue}",
     *     ....
     * }
     * </pre>, returns {@code null} if not found
     * @throws RepositoryException repository exception
     */
    public JSONObject getOptions(final String category) throws RepositoryException {
        if (OptionCache.CATEGORY_DOCUMENT_CHUNK.equals(category)) {
            return null;
        }
        if (null != optionCache) {
            final JSONObject cached = optionCache.getCategory(category);
            if (null != cached) {
                return cached;
            }
        }

        final JSONObject ret = new JSONObject();
        try {
            final List<JSONObject> options = getList(new Query().setFilter(new PropertyFilter(Option.OPTION_CATEGORY, FilterOperator.EQUAL, category)));
            if (0 == options.size()) {
                return null;
            }

            for (final JSONObject option : options) {
                if (!OptionCache.isDocumentChunk(option)) {
                    ret.put(option.optString(Keys.OBJECT_ID), option.opt(Option.OPTION_VALUE));
                }
            }
            if (ret.isEmpty()) {
                return null;
            }
            if (null != optionCache) {
                optionCache.putCategory(category, ret);
            }

            return ret;
        } catch (final Exception e) {
            throw new RepositoryException(e);
        }
    }
}
