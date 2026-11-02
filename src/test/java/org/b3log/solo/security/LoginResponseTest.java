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
import org.b3log.latke.model.Role;
import org.b3log.latke.model.User;
import org.b3log.solo.bolo.tool.MD5Utils;
import org.b3log.solo.model.UserExt;
import org.b3log.solo.processor.OAuthProcessor;
import org.b3log.solo.service.InitService;
import org.b3log.solo.service.UpgradeService;
import org.b3log.solo.service.UserQueryService;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class LoginResponseTest {
    @BeforeClass public void configure() throws Exception { LatkeTestSupport.configure(); }

    private void assertLogin(final String username, final String password, final String role,
                             final int expectedStatus, final boolean success) throws Exception {
        final boolean migration = UpgradeService.boloFastMigration;
        final DefaultFullHttpRequest http = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/oauth/bolo/login");
        final DefaultFullHttpResponse rawResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        try {
            UpgradeService.boloFastMigration = false;
            http.headers().set("Accept", "application/json");
            final Request request = new Request(null, http) {
                @Override public String getRemoteAddr() { return "127.0.0.1"; }
            };
            if (username != null) request.setParameter("username", username);
            if (password != null) request.setParameter("password", password);
            final Response response = new Response(null, rawResponse);
            final RequestContext context = new RequestContext(request, response);
            final OAuthProcessor processor = new OAuthProcessor();
            final var init = OAuthProcessor.class.getDeclaredField("initService");
            init.setAccessible(true);
            init.set(processor, new InitService() { @Override public boolean isInited() { return true; } });
            final var users = OAuthProcessor.class.getDeclaredField("userQueryService");
            users.setAccessible(true);
            users.set(processor, new UserQueryService() {
                @Override public JSONObject getUserByName(final String name) {
                    return "test-user".equals(name) ? new JSONObject().put(Keys.OBJECT_ID, "user-1")
                            .put(User.USER_NAME, name).put(User.USER_ROLE, role)
                            .put(UserExt.USER_B3_KEY, MD5Utils.stringToMD5Twice("Password42")) : null;
                }
            });
            CaptchaTestSupport.prepare(processor, request);
            processor.adminLogin(context);
            Assert.assertEquals(rawResponse.status().code(), expectedStatus);
            Assert.assertNull(rawResponse.headers().get("Location"), "AJAX responses must not redirect the document");
            final JSONObject result = ((JsonRenderer) context.getRenderer()).getJSONObject();
            Assert.assertEquals(result.getBoolean("sc"), success);
            if (success) {
                Assert.assertEquals(result.getString("redirect"), "/admin-index.do#main");
                Assert.assertFalse(response.getCookies().isEmpty());
            } else {
                Assert.assertFalse(result.has("redirect"));
            }
        } finally {
            UpgradeService.boloFastMigration = migration;
            http.release();
            rawResponse.release();
        }
    }

    @Test public void wrongPasswordReturnsJsonWithoutRedirect() throws Exception { assertLogin("test-user", "wrong", Role.ADMIN_ROLE, 401, false); }
    @Test public void unknownUserReturnsTheSameFailure() throws Exception { assertLogin("unknown", "wrong", Role.ADMIN_ROLE, 401, false); }
    @Test public void missingFieldsReturnJson() throws Exception { assertLogin(null, null, Role.ADMIN_ROLE, 400, false); }
    @Test public void visitorCannotLoginToConsole() throws Exception { assertLogin("test-user", "Password42", Role.VISITOR_ROLE, 401, false); }
    @Test public void successfulLoginReturnsLocalDestination() throws Exception { assertLogin("test-user", "Password42", Role.ADMIN_ROLE, 200, true); }
}
