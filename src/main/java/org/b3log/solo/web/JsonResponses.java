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
package org.b3log.solo.web;

import org.b3log.latke.http.RequestContext;
import org.b3log.latke.http.renderer.JsonRenderer;
import org.b3log.solo.model.Common;
import org.json.JSONObject;

/** Initializes a JSON response before using Latke's field setters, preserving Bolo's sc contract. */
public final class JsonResponses {
    private JsonResponses() { }
    public static RequestContext json(final RequestContext context) {
        if (!(context.getRenderer() instanceof JsonRenderer)) {
            context.renderJSON(new JSONObject().put(Common.STATUS_CODE, false));
        }
        return context;
    }
}
