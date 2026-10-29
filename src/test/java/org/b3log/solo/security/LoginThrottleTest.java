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
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.b3log.latke.http.Request;
import org.b3log.solo.util.LoginThrottle;
import org.testng.Assert;
import org.testng.annotations.Test;

public class LoginThrottleTest {
    @Test public void concurrentAttemptsCannotOvershootTheAccountLimit() {
        final LoginThrottle limiter = new LoginThrottle();
        final long accepted = java.util.stream.IntStream.range(0, 64).parallel()
                .filter(attempt -> limiter.acquire("192.0.2.1", "admin").retryAfter() == 0).count();
        Assert.assertEquals(accepted, 5L);
    }

    @Test public void fillingTheCacheDoesNotEvictAnActiveLockout() {
        final LoginThrottle limiter = new LoginThrottle();
        for (int attempt = 0; attempt < 5; attempt++) limiter.acquire("198.51.100.1", "admin");
        for (int address = 0; address < 10_001; address++) limiter.acquire("192.0." + address / 256 + "." + address % 256, "other");
        Assert.assertTrue(limiter.acquire("198.51.100.1", "admin").retryAfter() > 0);
    }

    @Test public void fifthAttemptBlocksFurtherTriesUntilExpiry() {
        final AtomicLong now = new AtomicLong();
        final LoginThrottle limiter = new LoginThrottle(now::get);
        for (int attempt = 0; attempt < 5; attempt++) Assert.assertEquals(limiter.acquire("192.0.2.1", "admin").retryAfter(), 0);
        Assert.assertEquals(limiter.acquire("192.0.2.1", "ADMIN").retryAfter(), 600);
        now.set(Duration.ofSeconds(123).toNanos());
        Assert.assertEquals(limiter.acquire("192.0.2.1", "admin").retryAfter(), 477);
        Assert.assertEquals(limiter.acquire("192.0.2.2", "admin").retryAfter(), 0);
        now.set(Duration.ofMinutes(10).toNanos());
        Assert.assertEquals(limiter.acquire("192.0.2.1", "admin").retryAfter(), 0);
    }

    @Test public void rotatingUsernamesAndIpv6AddressesCannotResetLimits() {
        final LoginThrottle limiter = new LoginThrottle();
        for (int attempt = 0; attempt < 20; attempt++) Assert.assertEquals(limiter.acquire("2001:db8::" + Integer.toHexString(attempt + 1), "user" + attempt).retryAfter(), 0);
        Assert.assertTrue(limiter.acquire("2001:db8::ffff", "different").retryAfter() > 0);
    }

    @Test public void successfulLoginClearsAccountWindowWithoutResettingSourceBudget() {
        final LoginThrottle limiter = new LoginThrottle();
        for (int attempt = 0; attempt < 4; attempt++) limiter.acquire("192.0.2.1", "admin");
        limiter.succeeded(limiter.acquire("192.0.2.1", "admin"));
        Assert.assertEquals(limiter.acquire("192.0.2.1", "admin").retryAfter(), 0);
        for (int attempt = 0; attempt < 14; attempt++) limiter.acquire("192.0.2.1", "other" + attempt);
        Assert.assertTrue(limiter.acquire("192.0.2.1", "admin").retryAfter() > 0);
    }

    @Test public void onlyTrustedPeersMaySupplyOneValidatedRealIp() throws Exception {
        LatkeTestSupport.configure();
        final DefaultFullHttpRequest http = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        try {
            http.headers().set("X-Real-IP", "198.51.100.2").set("X-Forwarded-For", "203.0.113.3");
            final Request direct = new Request(null, http) { @Override public String getRemoteAddr() { return "192.0.2.10"; } };
            final Request proxy = new Request(null, http) { @Override public String getRemoteAddr() { return "127.0.0.1"; } };
            Assert.assertEquals(LoginThrottle.clientAddress(direct), "192.0.2.10");
            Assert.assertEquals(LoginThrottle.clientAddress(proxy), "198.51.100.2");
            http.headers().set("X-Real-IP", "198.51.100.2,203.0.113.3");
            Assert.assertEquals(LoginThrottle.clientAddress(proxy), "127.0.0.1");
            org.b3log.latke.Latkes.setLocalProps(new java.util.Properties());
            http.headers().set("X-Real-IP", "198.51.100.2");
            Assert.assertEquals(LoginThrottle.clientAddress(proxy), "127.0.0.1", "Old configurations must not implicitly trust forwarded headers");
            final var unixOnly = new java.util.Properties(); unixOnly.setProperty("loginTrustedProxies", "unix");
            org.b3log.latke.Latkes.setLocalProps(unixOnly);
            Assert.assertEquals(LoginThrottle.clientAddress(proxy), "127.0.0.1", "Unix trust must never grant header trust to TCP connections");
        } finally { http.release(); LatkeTestSupport.configure(); }
    }
}
