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
import org.b3log.latke.http.Cookie;
import org.b3log.latke.http.Response;
import org.b3log.solo.util.Solos;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.testng.annotations.BeforeClass;

public class LogoutSecurityTest {
    @BeforeClass public void configure() throws Exception { LatkeTestSupport.configure(); }
    @Test
    public void expiresAuthCookieWithoutNullOrDuplicateValues() {
        final DefaultFullHttpResponse raw = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        try {
            final Response response = new Response(null, raw);
            Solos.logout(null, response);
            final Cookie expired = response.getCookies().iterator().next();
            final Cookie echoed = new Cookie(expired.getName(), "old-cookie");
            echoed.setPath("/");
            response.addCookie(echoed);
            Solos.logout(null, response);
            Assert.assertEquals(response.getCookies().size(), 1);
            final Cookie cookie = response.getCookies().iterator().next();
            Assert.assertEquals(cookie.getValue(), "");
            Assert.assertEquals(cookie.getMaxAge(), 0L);
            Assert.assertEquals(cookie.getPath(), "/");
        } finally {
            raw.release();
        }
    }
}
