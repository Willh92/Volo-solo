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

import org.b3log.solo.bolo.waf.WAF;
import org.b3log.solo.bolo.waf.WAFstorage;
import org.testng.Assert;
import org.testng.annotations.Test;
import pers.adlered.simplecurrentlimiter.main.SimpleCurrentLimiter;

public class WafResourceTest {
    @Test public void proxyClientsHaveSeparateBudgetsAndSpoofedHeadersCannotResetThem() throws Exception {
        LatkeTestSupport.configure();
        final boolean oldPower = WAF.POWER;
        final SimpleCurrentLimiter oldLimiter = WAFstorage.currentLimiter;
        final io.netty.handler.codec.http.DefaultFullHttpRequest http = new io.netty.handler.codec.http.DefaultFullHttpRequest(
                io.netty.handler.codec.http.HttpVersion.HTTP_1_1, io.netty.handler.codec.http.HttpMethod.GET, "/");
        try {
            WAFstorage.currentLimiter = new SimpleCurrentLimiter(180, 1);
            WAF.POWER = true;
            final org.b3log.latke.http.Request proxy = new org.b3log.latke.http.Request(null, http) {
                @Override public String getRemoteAddr() { return "127.0.0.1"; }
            };
            final org.b3log.latke.http.Request direct = new org.b3log.latke.http.Request(null, http) {
                @Override public String getRemoteAddr() { return "192.0.2.22"; }
            };
            http.headers().set("X-Real-IP", "198.51.100.2");
            Assert.assertTrue(WAF.in(org.b3log.solo.util.LoginThrottle.clientAddress(proxy), "/first.html"));
            Assert.assertFalse(WAF.in(org.b3log.solo.util.LoginThrottle.clientAddress(proxy), "/second.html"));
            http.headers().set("X-Real-IP", "198.51.100.3");
            Assert.assertTrue(WAF.in(org.b3log.solo.util.LoginThrottle.clientAddress(proxy), "/first.html"), "A different proxy client needs a separate budget");
            Assert.assertTrue(WAF.in(org.b3log.solo.util.LoginThrottle.clientAddress(direct), "/first.html"));
            http.headers().set("X-Real-IP", "198.51.100.4");
            Assert.assertFalse(WAF.in(org.b3log.solo.util.LoginThrottle.clientAddress(direct), "/second.html"), "An untrusted peer cannot reset its budget by spoofing headers");
            http.headers().remove("X-Real-IP");
            Assert.assertTrue(WAF.in(org.b3log.solo.util.LoginThrottle.clientAddress(proxy), "/first.html"));
            Assert.assertFalse(WAF.in(org.b3log.solo.util.LoginThrottle.clientAddress(proxy), "/second.html"), "A missing proxy header must not bypass protection through loopback");
        } finally {
            http.release();
            WAF.POWER = oldPower;
            WAFstorage.currentLimiter = oldLimiter;
            LatkeTestSupport.configure();
        }
    }

    @Test public void loadingAssetsDoesNotUseThePageBudget() throws Exception {
        LatkeTestSupport.configure();
        final boolean oldPower = WAF.POWER;
        final SimpleCurrentLimiter oldLimiter = WAFstorage.currentLimiter;
        try {
            WAFstorage.currentLimiter = new SimpleCurrentLimiter(180, 1);
            WAF.POWER = true;
            for (int repeat = 0; repeat < 3; repeat++) {
                for (final String asset : new String[]{"/js/common.min.js", "/scss/appearance.css",
                        "/skins/bolo-Jane/fonts/icomoon.woff", "/sw.js", "/service-worker.js"}) {
                    Assert.assertTrue(WAF.in("192.0.2.100", asset), asset);
                }
            }
            Assert.assertTrue(WAF.in("192.0.2.100", "/article.html"));
            Assert.assertFalse(WAF.in("192.0.2.100", "/another.html"), "Actual page visits must still be limited");
            Assert.assertTrue(WAF.in("192.0.2.100", "/js/lib/vditor/dist/method.min.js"), "Assets must load even when the page budget is exhausted");
        } finally {
            WAF.POWER = oldPower;
            WAFstorage.currentLimiter = oldLimiter;
        }
    }
}
