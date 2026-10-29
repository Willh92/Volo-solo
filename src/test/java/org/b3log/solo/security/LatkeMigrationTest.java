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
package org.b3log.solo.security;

import io.netty.handler.codec.http.*;
import org.b3log.latke.Keys;
import org.b3log.latke.http.Request;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.http.Response;
import org.b3log.latke.http.renderer.JsonRenderer;
import org.b3log.solo.util.QueryResults;
import org.b3log.solo.web.JsonResponses;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.Test;
import java.util.List;

public class LatkeMigrationTest {
    @Test
    public void readsNativeLatkeQueryRowsWithoutLosingObjects() {
        final JSONObject row = new JSONObject().put("oId", "article-1");
        final JSONObject result = new JSONObject().put(Keys.RESULTS, (Object) List.of(row));
        Assert.assertNull(result.optJSONArray(Keys.RESULTS));
        Assert.assertSame(QueryResults.array(result).getJSONObject(0), row);
        Assert.assertEquals(QueryResults.array(new JSONObject().put(Keys.RESULTS, (Object) List.of())).length(), 0);
    }

    @Test
    public void initializesAndPreservesJsonResponseFields() {
        final DefaultFullHttpRequest http = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/test");
        try {
            final RequestContext context = new RequestContext(new Request(null, http),
                    new Response(null, new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)));
            JsonResponses.json(context).renderCode(200);
            JsonResponses.json(context).renderMsg("成功");
            final JSONObject result = ((JsonRenderer) context.getRenderer()).getJSONObject();
            Assert.assertEquals(result.getInt("code"), 200);
            Assert.assertEquals(result.getString("msg"), "成功");
        } finally {
            http.release();
        }
    }
}
