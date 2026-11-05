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
package org.b3log.solo.cache;

import org.b3log.latke.Keys;
import org.b3log.latke.ioc.Singleton;
import org.b3log.latke.model.Role;
import org.b3log.solo.util.Solos;
import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * User cache.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 2.3.0
 */
@Singleton
public class UserCache {

    /**
     * Maximum number of cached users in memory (prevents unbounded growth when reader registration is open).
     */
    public static final int MAX_ENTRIES = 512;

    /**
     * Bounded LRU Id -> User cache.
     */
    private final Map<String, JSONObject> idCache = Collections.synchronizedMap(
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(final Map.Entry<String, JSONObject> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    /**
     * Admin user.
     */
    private final Map<String, JSONObject> adminCache = new ConcurrentHashMap<>();

    /**
     * Gets the admin user.
     *
     * @return admin user
     */
    public JSONObject getAdmin() {
        final JSONObject admin = adminCache.get(Role.ADMIN_ROLE);
        return null == admin ? null : Solos.clone(admin);
    }

    /**
     * Adds or updates the admin user.
     *
     * @param admin the specified admin user
     */
    public void putAdmin(final JSONObject admin) {
        if (null == admin) {
            return;
        }
        adminCache.put(Role.ADMIN_ROLE, Solos.clone(admin));
    }

    /**
     * Gets a user by the specified user id.
     *
     * @param userId the specified user id
     * @return user, returns {@code null} if not found
     */
    public JSONObject getUser(final String userId) {
        if (null == userId) {
            return null;
        }
        final JSONObject user = idCache.get(userId);
        if (null == user) {
            return null;
        }

        return Solos.clone(user);
    }

    /**
     * Adds or updates the specified user.
     *
     * @param user the specified user
     */
    public void putUser(final JSONObject user) {
        if (null == user) {
            return;
        }
        final String id = user.optString(Keys.OBJECT_ID);
        if (!id.isEmpty()) {
            idCache.put(id, Solos.clone(user));
        }
    }

    /**
     * Removes a user by the specified user id.
     *
     * @param id the specified user id
     */
    public void removeUser(final String id) {
        if (null == id) {
            return;
        }
        final JSONObject user = idCache.remove(id);
        final JSONObject admin = adminCache.get(Role.ADMIN_ROLE);
        if ((null != user && Role.ADMIN_ROLE.equals(user.optString(org.b3log.latke.model.User.USER_ROLE)))
                || (null != admin && id.equals(admin.optString(Keys.OBJECT_ID)))) {
            adminCache.remove(Role.ADMIN_ROLE);
        }
    }

    /**
     * Returns current number of cached user entries.
     */
    public int size() {
        return idCache.size();
    }

    /**
     * Clears all cached data.
     */
    public void clear() {
        idCache.clear();
        adminCache.clear();
    }
}
