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
import org.b3log.latke.model.Role;
import org.b3log.latke.model.User;
import org.b3log.latke.repository.AbstractRepository;
import org.b3log.latke.repository.FilterOperator;
import org.b3log.latke.repository.PropertyFilter;
import org.b3log.latke.repository.Query;
import org.b3log.latke.repository.RepositoryException;
import org.b3log.latke.repository.annotation.Repository;
import org.b3log.solo.cache.UserCache;
import org.b3log.solo.model.UserExt;
import org.b3log.solo.util.Solos;
import org.json.JSONObject;

import java.util.Locale;

/**
 * User repository.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 0.3.1
 */
@Repository
public class UserRepository extends AbstractRepository {

    /**
     * User cache.
     */
    @Inject
    private UserCache userCache;

    /**
     * Public constructor.
     */
    public UserRepository() {
        super(User.USER);
    }

    @Override
    public String add(final JSONObject user) throws RepositoryException {
        if (user != null) {
            if (!user.has(UserExt.USER_STATUS)) {
                user.put(UserExt.USER_STATUS, UserExt.USER_STATUS_C_VALID);
            }
            if (!user.has(UserExt.USER_EMAIL_VERIFIED)) {
                user.put(UserExt.USER_EMAIL_VERIFIED, false);
            }
            if (!user.has(UserExt.USER_EMAIL)) {
                user.put(UserExt.USER_EMAIL, "");
            }
            if (!user.has(UserExt.USER_PASSWORD_HASH)) {
                user.put(UserExt.USER_PASSWORD_HASH, "");
            }
            if (!user.has(UserExt.USER_CREATED)) {
                user.put(UserExt.USER_CREATED, System.currentTimeMillis());
            }
        }
        final String ret = super.add(user);
        if (null != userCache && null != ret) {
            if (null != user) {
                final JSONObject cached = Solos.clone(user);
                cached.put(Keys.OBJECT_ID, ret);
                userCache.putUser(cached);
                if (Role.ADMIN_ROLE.equals(cached.optString(User.USER_ROLE))) {
                    userCache.putAdmin(cached);
                }
            } else {
                userCache.clearMissingUser(ret);
            }
        }
        return ret;
    }

    @Override
    public void remove(final String id) throws RepositoryException {
        super.remove(id);

        if (null != userCache) {
            userCache.removeUser(id);
        }
    }

    @Override
    public JSONObject get(final String id) throws RepositoryException {
        if (null == id || id.isEmpty()) {
            return null;
        }
        if (null != userCache) {
            final JSONObject cached = userCache.getUser(id);
            if (null != cached) {
                return cached;
            }
            if (userCache.isMissingUser(id)) {
                return null;
            }
        }

        final JSONObject ret = super.get(id);
        if (null == ret) {
            if (null != userCache) {
                userCache.markMissingUser(id);
            }
            return null;
        }

        if (null != userCache) {
            userCache.putUser(ret);
        }

        return Solos.clone(ret);
    }

    @Override
    public void update(final String id, final JSONObject user, final String... propertyNames) throws RepositoryException {
        super.update(id, user, propertyNames);

        user.put(Keys.OBJECT_ID, id);
        if (null != userCache) {
            userCache.putUser(user);

            if (Role.ADMIN_ROLE.equals(user.optString(User.USER_ROLE))) {
                userCache.putAdmin(user);
            }
        }
    }

    /**
     * Gets a user by the specified username.
     *
     * @param userName the specified username
     * @return user, returns {@code null} if not found
     * @throws RepositoryException repository exception
     */
    public JSONObject getByUserName(final String userName) throws RepositoryException {
        return getFirst(new Query()
                .setFilter(new PropertyFilter(User.USER_NAME, FilterOperator.EQUAL, userName))
                .setPage(1, 1)
                .setPageCount(1));
    }

    /**
     * Gets a user by normalized email address.
     */
    public JSONObject getByEmail(final String email) throws RepositoryException {
        if (email == null || email.isBlank()) {
            return null;
        }
        final String normalized = email.strip().toLowerCase(Locale.ROOT);
        return getFirst(new Query()
                .setFilter(new PropertyFilter(UserExt.USER_EMAIL, FilterOperator.EQUAL, normalized))
                .setPage(1, 1)
                .setPageCount(1));
    }

    /**
     * Gets the administrator user.
     *
     * @return administrator user, returns {@code null} if not found or error
     * @throws RepositoryException repository exception
     */
    public JSONObject getAdmin() throws RepositoryException {
        if (null != userCache) {
            final JSONObject cached = userCache.getAdmin();
            if (null != cached) {
                return cached;
            }
        }

        final JSONObject ret = getFirst(new Query()
                .setFilter(new PropertyFilter(User.USER_ROLE, FilterOperator.EQUAL, Role.ADMIN_ROLE))
                .setPage(1, 1)
                .setPageCount(1));
        if (null == ret) {
            return null;
        }

        if (null != userCache) {
            userCache.putAdmin(ret);
        }

        return Solos.clone(ret);
    }
}
