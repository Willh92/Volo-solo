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
package org.b3log.solo.util;

import io.netty.util.NetUtil;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import org.b3log.latke.Latkes;
import org.b3log.latke.http.Request;

/** Bounded, atomic login windows. Proxy headers are accepted only from explicitly trusted peers. */
public final class LoginThrottle {
    private static final long WINDOW = Duration.ofMinutes(10).toNanos();
    private static final int MAX_SOURCES = 10_000;
    private final Map<String, Window> sources = new LinkedHashMap<>();
    private final Map<String, Window> accounts = new LinkedHashMap<>();
    private final LongSupplier clock;

    private static final class Window {
        private final long expires;
        private int attempts;
        private Window(final long expires) { this.expires = expires; }
    }

    public record Attempt(String accountKey, int retryAfter) { }

    public LoginThrottle() { this(System::nanoTime); }
    public LoginThrottle(final LongSupplier clock) { this.clock = clock; }

    public synchronized Attempt acquire(final String address, final String username) {
        final long now = clock.getAsLong();
        prune(sources, now);
        prune(accounts, now);
        final String source = sourceKey(address);
        final String name = username == null ? "" : username.substring(0, Math.min(128, username.length())).trim().toLowerCase(Locale.ROOT);
        final String key = source + '\n' + name;
        final Window ip = sources.get(source);
        final Window account = accounts.get(key);
        long blockedUntil = now;
        if (ip != null && ip.attempts >= 20) blockedUntil = Math.max(blockedUntil, ip.expires);
        if (account != null && account.attempts >= 5) blockedUntil = Math.max(blockedUntil, account.expires);
        if ((ip == null && sources.size() >= MAX_SOURCES) || (account == null && accounts.size() >= MAX_SOURCES)) {
            // Do not evict active limits: flooding random usernames must not reset a previous lockout.
            blockedUntil = Math.max(blockedUntil, now + Duration.ofMinutes(1).toNanos());
        }
        if (blockedUntil > now) return new Attempt(key, (int) Math.max(1, (blockedUntil - now + 999_999_999L) / 1_000_000_000L));
        sources.computeIfAbsent(source, ignored -> new Window(now + WINDOW)).attempts++;
        accounts.computeIfAbsent(key, ignored -> new Window(now + WINDOW)).attempts++;
        return new Attempt(key, 0);
    }

    public synchronized void succeeded(final Attempt attempt) {
        accounts.remove(attempt.accountKey());
    }

    private static void prune(final Map<String, Window> windows, final long now) {
        final var iterator = windows.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().expires > now) break;
            iterator.remove();
        }
    }

    /** Stable source identity; IPv6 privacy addresses in one /64 share a budget. */
    public static String sourceKey(final String address) {
        final String normalized = normalize(address);
        if (normalized == null) return "unknown";
        if (!normalized.contains(":")) return normalized;
        final byte[] bytes = NetUtil.createByteArrayFromIpAddressString(normalized);
        // IPv6 privacy addresses in the same /64 share a limit.
        return Arrays.toString(Arrays.copyOf(bytes, 8));
    }

    /** Shared client identity for login protection and the page-rate WAF. */
    public static String clientAddress(final Request request) {
        final String rawPeer = peerAddress(request);
        final String peer = "unix".equals(rawPeer) && org.b3log.solo.web.UnixSocketSupport.isActive() ? "unix" : normalize(rawPeer);
        if (peer == null) return "unknown";
        final String setting = Latkes.getLocalProperty("loginTrustedProxies");
        final String proxies = setting == null ? "" : setting;
        final boolean trusted = Arrays.stream(proxies.split(","))
                .map(value -> "unix".equals(value.trim()) ? "unix" : normalize(value)).anyMatch(peer::equals);
        if (trusted) {
            final String forwarded = normalize(request.getHeader("X-Real-IP"));
            if (forwarded != null) return forwarded;
        }
        return peer;
    }

    /** Unix trust comes from the active listener, never from a user-supplied HTTP header. */
    public static String peerAddress(final Request request) {
        return org.b3log.solo.web.UnixSocketSupport.isActive() ? "unix" : request.getRemoteAddr();
    }

    private static String normalize(final String value) {
        if (value == null || value.length() > 64) return null;
        final String address = value.trim();
        if (!NetUtil.isValidIpV4Address(address) && !NetUtil.isValidIpV6Address(address)) return null;
        try {
            return InetAddress.getByAddress(NetUtil.createByteArrayFromIpAddressString(address)).getHostAddress();
        } catch (Exception ignored) { return null; }
    }
}
