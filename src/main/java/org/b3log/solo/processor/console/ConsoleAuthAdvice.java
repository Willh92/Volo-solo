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

import org.b3log.latke.Keys;
import org.b3log.latke.ioc.Singleton;
import org.b3log.latke.model.Role;
import org.b3log.latke.model.User;
import org.b3log.latke.http.RequestContext;
import org.b3log.solo.util.Solos;
import org.json.JSONObject;

import org.b3log.latke.http.Response;

/**
 * The common auth check before advice for admin console.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 2.9.5
 */
@Singleton
public class ConsoleAuthAdvice implements org.b3log.latke.http.function.Handler {

    @Override
    public void handle(final RequestContext context) {
        context.setHeader("Cache-Control", "no-store");
        final JSONObject currentUser = Solos.getCurrentUser(context.getRequest(), context.getResponse());
        if (null == currentUser) {
            if (org.b3log.solo.util.AdminRequests.rejectPage(context)) return;
            final JSONObject exception401 = new JSONObject();
            exception401.put(Keys.MSG, "Unauthorized to request [" + context.requestURI() + "], please signin");
            exception401.put(org.b3log.solo.model.Common.STATUS_CODE, 401);

            context.setStatus(exception401.getInt(org.b3log.solo.model.Common.STATUS_CODE));
            context.renderJSON(exception401);
            context.abort();
            return;
        }

        final String userRole = currentUser.optString(User.USER_ROLE);
        if (Role.VISITOR_ROLE.equals(userRole)) {
            if (org.b3log.solo.util.AdminRequests.rejectPage(context)) return;
            final JSONObject exception403 = new JSONObject();
            exception403.put(Keys.MSG, "Forbidden to request [" + context.requestURI() + "]");
            exception403.put(org.b3log.solo.model.Common.STATUS_CODE, 403);

            context.setStatus(exception403.getInt(org.b3log.solo.model.Common.STATUS_CODE));
            context.renderJSON(exception403);
            context.abort();
            return;
        }
        context.handle();
    }
}
