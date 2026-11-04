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

import org.b3log.latke.model.Plugin;
import org.b3log.latke.repository.AbstractRepository;
import org.b3log.latke.repository.annotation.Repository;

/**
 * Plugin repository.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 0.3.1
 */
@Repository
public class PluginRepository extends AbstractRepository {

    /**
     * Public constructor.
     */
    private final java.util.Map<String, org.json.JSONObject> cache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> missingIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public PluginRepository() {
        super(Plugin.PLUGIN);
    }

    @Override
    public String add(final org.json.JSONObject plugin) throws org.b3log.latke.repository.RepositoryException {
        final String id = super.add(plugin);
        plugin.put(org.b3log.latke.Keys.OBJECT_ID, id);
        missingIds.remove(id);
        cache.put(id, org.b3log.solo.util.Solos.clone(plugin));
        return id;
    }

    @Override
    public org.json.JSONObject get(final String id) throws org.b3log.latke.repository.RepositoryException {
        final org.json.JSONObject cached = cache.get(id);
        if (null != cached) {
            return org.b3log.solo.util.Solos.clone(cached);
        }
        if (null != id && missingIds.contains(id)) {
            return null;
        }
        final org.json.JSONObject loaded = super.get(id);
        if (null == loaded) {
            if (null != id && id.length() <= 128 && missingIds.size() < 256) {
                missingIds.add(id);
            }
            return null;
        }
        cache.put(id, org.b3log.solo.util.Solos.clone(loaded));
        return loaded;
    }

    @Override
    public void update(final String id, final org.json.JSONObject plugin, final String... propertyNames)
            throws org.b3log.latke.repository.RepositoryException {
        super.update(id, plugin, propertyNames);
        plugin.put(org.b3log.latke.Keys.OBJECT_ID, id);
        missingIds.remove(id);
        cache.put(id, org.b3log.solo.util.Solos.clone(plugin));
    }

    @Override
    public void remove(final String id) throws org.b3log.latke.repository.RepositoryException {
        super.remove(id);
        cache.remove(id);
        if (null != id && id.length() <= 128 && missingIds.size() < 256) {
            missingIds.add(id);
        }
    }
}
