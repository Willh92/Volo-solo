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
import org.b3log.latke.http.Request;
import org.b3log.latke.util.StaticResources;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.testng.annotations.BeforeClass;

public class PluginRouteSecurityTest {
    @BeforeClass public void configure() throws Exception { LatkeTestSupport.configure(); }
    @Test
    public void pluginApiRequestsKeepCookieParsing() {
        for (final String uri : new String[]{"/plugins/kanbanniang/assets/list", "/plugins/kanbanniang/assets/model", "/plugins/kanbanniang/assets/absoluteRandomModel"}) {
            final DefaultFullHttpRequest raw = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
            try {
                Assert.assertFalse(StaticResources.isStatic(new Request(null, raw)), uri);
            } finally {
                raw.release();
            }
        }
        final DefaultFullHttpRequest raw = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/plugins/kanbanniang/fallback.css");
        try {
            Assert.assertTrue(StaticResources.isStatic(new Request(null, raw)));
        } finally {
            raw.release();
        }
    }
}
