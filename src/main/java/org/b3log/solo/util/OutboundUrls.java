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

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.DefaultRoutePlanner;
import org.apache.http.impl.conn.DefaultSchemePortResolver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Validates external HTTP(S) URLs and prevents Server-Side Request Forgery (SSRF)
 * to loopback, private, link-local, or cloud-metadata endpoints.
 */
public final class OutboundUrls {

    private static final int MAX_URL_LENGTH = 2048;
    private static final int MAX_REDIRECTS = 3;

    private record ValidatedTarget(URI uri, String host, InetAddress[] addresses) { }
    private static final ThreadLocal<ValidatedTarget> PINNED_TARGET = new ThreadLocal<>();
    private static final CloseableHttpClient SAFE_HTTP_CLIENT = HttpClients.custom()
            .setDnsResolver(host -> {
                final ValidatedTarget pinned = PINNED_TARGET.get();
                final String normalized = stripBrackets(host);
                if (pinned != null && pinned.host().equalsIgnoreCase(normalized)) {
                    return pinned.addresses();
                }
                throw new UnknownHostException("Unpinned outbound host: " + host);
            })
            .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
            .disableRedirectHandling()
            .disableCookieManagement()
            .disableAuthCaching()
            .disableAutomaticRetries()
            .setMaxConnTotal(32)
            .setMaxConnPerRoute(8)
            .evictIdleConnections(30L, TimeUnit.SECONDS)
            .build();

    private OutboundUrls() {
    }

    private static String stripBrackets(final String rawHost) {
        if (rawHost == null) {
            return "";
        }
        return rawHost.startsWith("[") && rawHost.endsWith("]")
                ? rawHost.substring(1, rawHost.length() - 1)
                : rawHost;
    }

    /**
     * Checks whether the specified string is a well-formed {@code http://} or {@code https://} URL
     * without embedded credentials or control characters.
     */
    public static boolean isHttpOrHttps(final String raw) {
        if (raw == null) {
            return false;
        }
        final String trimmed = raw.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_URL_LENGTH || hasControlOrWhitespace(trimmed)) {
            return false;
        }
        try {
            final URI uri = new URI(trimmed);
            final String scheme = uri.getScheme();
            if (scheme == null) {
                return false;
            }
            final String lower = scheme.toLowerCase(Locale.ROOT);
            if (!"http".equals(lower) && !"https".equals(lower)) {
                return false;
            }
            if (uri.getRawUserInfo() != null) {
                return false;
            }
            final String host = uri.getHost();
            return host != null && !host.isBlank();
        } catch (final Exception invalid) {
            return false;
        }
    }

    /**
     * Checks whether an optional icon or avatar URL is blank, a safe root-relative path, or an {@code http(s)} URL.
     */
    public static boolean isOptionalSafeIconOrAvatar(final String raw) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        final String trimmed = raw.strip();
        if (trimmed.length() > MAX_URL_LENGTH || hasControlOrWhitespace(trimmed) || trimmed.indexOf('\\') >= 0) {
            return false;
        }
        if (trimmed.startsWith("/") && !trimmed.startsWith("//")) {
            return !trimmed.contains("..");
        }
        return isHttpOrHttps(trimmed);
    }

    /**
     * Parses and resolves an outbound HTTP(S) URI, rejecting private, loopback, link-local,
     * multicast, reserved, and cloud-metadata addresses.
     */
    private static ValidatedTarget resolveValidated(final String raw, final boolean allowLoopback) throws IOException {
        if (!isHttpOrHttps(raw)) {
            throw new IOException("Only valid http:// or https:// URLs are allowed");
        }
        final URI uri = URI.create(raw.strip());
        final String host = stripBrackets(uri.getHost());
        final InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0) {
            throw new IOException("Host did not resolve to any address");
        }
        for (final InetAddress address : addresses) {
            if (!isAllowedRemoteAddress(address, allowLoopback)) {
                throw new IOException("Target address is restricted");
            }
        }
        return new ValidatedTarget(uri, host, addresses);
    }

    public static URI requirePublicHttpUri(final String raw, final boolean allowLoopback) throws IOException {
        return resolveValidated(raw, allowLoopback).uri();
    }

    private static boolean allZero(final byte[] bytes, final int fromInclusive, final int toExclusive) {
        for (int i = fromInclusive; i < toExclusive; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    public static boolean isAllowedRemoteAddress(final InetAddress address, final boolean allowLoopback) {
        if (address == null) {
            return false;
        }
        if (address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        if (address.isLoopbackAddress()) {
            return allowLoopback;
        }
        final byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return isPublicIpv4(bytes, allowLoopback);
        }
        if (bytes.length == 16) {
            final int b0 = bytes[0] & 0xff;
            final int b1 = bytes[1] & 0xff;
            final int b2 = bytes[2] & 0xff;
            final int b3 = bytes[3] & 0xff;
            // IPv6 Unique Local Address fc00::/7
            if ((b0 & 0xfe) == 0xfc) {
                return false;
            }
            // Discard-Only 100::/64
            if (b0 == 0x01 && b1 == 0x00 && allZero(bytes, 2, 8)) {
                return false;
            }
            // Documentation 2001:db8::/32
            if (b0 == 0x20 && b1 == 0x01 && b2 == 0x0d && b3 == 0xb8) {
                return false;
            }
            // Benchmarking 2001:2::/48
            if (b0 == 0x20 && b1 == 0x01 && b2 == 0x00 && b3 == 0x02 && bytes[4] == 0 && bytes[5] == 0) {
                return false;
            }
            // RFC 8215 local-use IPv4/IPv6 translation 64:ff9b:1::/48
            if (b0 == 0x00 && b1 == 0x64 && b2 == 0xff && b3 == 0x9b && bytes[4] == 0x00 && (bytes[5] & 0xff) == 0x01) {
                return false;
            }
            // IPv4-compatible ::/96 and IPv4-mapped ::ffff:0:0/96
            if (allZero(bytes, 0, 10)
                    && ((bytes[10] == 0 && bytes[11] == 0)
                    || ((bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff))) {
                return isPublicIpv4(new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]}, allowLoopback);
            }
            // SIIT IPv4-translated ::ffff:0:0:0/96
            if (allZero(bytes, 0, 8)
                    && (bytes[8] & 0xff) == 0xff && (bytes[9] & 0xff) == 0xff
                    && bytes[10] == 0 && bytes[11] == 0) {
                return isPublicIpv4(new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]}, allowLoopback);
            }
            // NAT64 well-known prefix 64:ff9b::/96
            if (b0 == 0x00 && b1 == 0x64 && b2 == 0xff && b3 == 0x9b && allZero(bytes, 4, 12)) {
                return isPublicIpv4(new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]}, allowLoopback);
            }
            // 6to4 2002::/16 (embeds IPv4 in bytes[2..5])
            if (b0 == 0x20 && b1 == 0x02) {
                return isPublicIpv4(new byte[]{bytes[2], bytes[3], bytes[4], bytes[5]}, allowLoopback);
            }
            // Teredo tunneling 2001:0000::/32
            if (b0 == 0x20 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPublicIpv4(final byte[] b, final boolean allowLoopback) {
        final int b0 = b[0] & 0xff;
        final int b1 = b[1] & 0xff;
        final int b2 = b[2] & 0xff;
        if (b0 == 0) {
            return false;
        }
        if (b0 == 127) {
            return allowLoopback;
        }
        // 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16
        if (b0 == 10 || (b0 == 172 && (b1 & 0xf0) == 16) || (b0 == 192 && b1 == 168) || (b0 == 169 && b1 == 254)) {
            return false;
        }
        // 192.0.0.0/24 IETF protocol assignments & 192.0.2.0/24 TEST-NET-1
        if (b0 == 192 && b1 == 0 && (b2 == 0 || b2 == 2)) {
            return false;
        }
        // 100.64.0.0/10 (Carrier-grade NAT & cloud metadata 100.100.100.200)
        if (b0 == 100 && (b1 & 0xc0) == 64) {
            return false;
        }
        // 198.18.0.0/15 benchmark & 224.0.0.0/3 multicast/reserved
        if ((b0 == 198 && (b1 == 18 || b1 == 19)) || b0 >= 224) {
            return false;
        }
        return true;
    }

    /**
     * Downloads an external resource with pinned DNS resolution, system-proxy bypass,
     * strict public-address validation on every redirect hop, and a hard byte cap.
     */
    public static byte[] fetchBytes(final String rawUrl, final int connectTimeoutMs, final int readTimeoutMs,
                                    final int maxBytes, final String acceptHeader, final String userAgent,
                                    final boolean allowLoopback) throws IOException {
        ValidatedTarget current = resolveValidated(rawUrl, allowLoopback);
        final RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(connectTimeoutMs)
                .setConnectionRequestTimeout(connectTimeoutMs)
                .setSocketTimeout(readTimeoutMs)
                .setRedirectsEnabled(false)
                .build();
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            final HttpGet request = new HttpGet(current.uri());
            request.setConfig(requestConfig);
            if (acceptHeader != null && !acceptHeader.isBlank()) {
                request.setHeader("Accept", acceptHeader);
            }
            if (userAgent != null && !userAgent.isBlank()) {
                request.setHeader("User-Agent", userAgent);
            }
            PINNED_TARGET.set(current);
            try (CloseableHttpResponse response = SAFE_HTTP_CLIENT.execute(request)) {
                final int status = response.getStatusLine().getStatusCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    if (hop == MAX_REDIRECTS) {
                        throw new IOException("Too many redirects");
                    }
                    final Header locationHeader = response.getFirstHeader("Location");
                    final String location = locationHeader == null ? null : locationHeader.getValue();
                    if (location == null || location.isBlank()) {
                        throw new IOException("Redirect missing Location header");
                    }
                    final URI next = current.uri().resolve(location.strip());
                    current = resolveValidated(next.toString(), allowLoopback);
                    continue;
                }
                if (status < 200 || status >= 300) {
                    throw new IOException("Unexpected HTTP status " + status);
                }
                final HttpEntity entity = response.getEntity();
                if (entity == null) {
                    return new byte[0];
                }
                final long declaredLength = entity.getContentLength();
                if (declaredLength > maxBytes) {
                    request.abort();
                    throw new IOException("Remote response exceeds maximum allowed size");
                }
                try (InputStream in = entity.getContent();
                     ByteArrayOutputStream out = new ByteArrayOutputStream(
                             declaredLength > 0 ? (int) Math.min(declaredLength, 65536) : 8192)) {
                    final byte[] buffer = new byte[8192];
                    int total = 0;
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        total += read;
                        if (total > maxBytes) {
                            request.abort();
                            throw new IOException("Remote response exceeds maximum allowed size");
                        }
                        out.write(buffer, 0, read);
                    }
                    return out.toByteArray();
                }
            } finally {
                PINNED_TARGET.remove();
            }
        }
        throw new IOException("Too many redirects");
    }

    public record ProbeResult(boolean ok, int status, long latencyMs, String finalUrl, String error) { }

    /**
     * Probes an external URL using pinned DNS and hop-by-hop SSRF validation (HEAD with GET fallback on 405/403/501).
     */
    public static ProbeResult probeUrl(final String rawUrl, final int connectTimeoutMs, final int readTimeoutMs,
                                       final String userAgent, final boolean allowLoopback) {
        final long start = System.nanoTime();
        try {
            ValidatedTarget current = resolveValidated(rawUrl, allowLoopback);
            final RequestConfig requestConfig = RequestConfig.custom()
                    .setConnectTimeout(connectTimeoutMs)
                    .setConnectionRequestTimeout(connectTimeoutMs)
                    .setSocketTimeout(readTimeoutMs)
                    .setRedirectsEnabled(false)
                    .build();
            boolean useGet = false;
            for (int hop = 0; hop <= MAX_REDIRECTS + 1; hop++) {
                final HttpUriRequest req = useGet ? new HttpGet(current.uri()) : new HttpHead(current.uri());
                if (req instanceof HttpHead h) {
                    h.setConfig(requestConfig);
                } else if (req instanceof HttpGet g) {
                    g.setConfig(requestConfig);
                }
                if (userAgent != null && !userAgent.isBlank()) {
                    req.setHeader("User-Agent", userAgent);
                }
                req.setHeader("Accept", "text/html,application/xhtml+xml,*/*;q=0.8");
                PINNED_TARGET.set(current);
                try (CloseableHttpResponse response = SAFE_HTTP_CLIENT.execute(req)) {
                    final int status = response.getStatusLine().getStatusCode();
                    if (!useGet && (status == 405 || status == 403 || status == 501)) {
                        useGet = true;
                        continue;
                    }
                    if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                        if (hop >= MAX_REDIRECTS) {
                            final long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                            return new ProbeResult(false, status, ms, current.uri().toString(), "重定向次数过多");
                        }
                        final Header loc = response.getFirstHeader("Location");
                        final String locVal = loc == null ? null : loc.getValue();
                        if (locVal == null || locVal.isBlank()) {
                            final long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                            return new ProbeResult(false, status, ms, current.uri().toString(), "重定向缺少 Location");
                        }
                        final URI next = current.uri().resolve(locVal.strip());
                        current = resolveValidated(next.toString(), allowLoopback);
                        if (status == 303) {
                            useGet = true;
                        }
                        continue;
                    }
                    if (useGet) {
                        req.abort();
                    }
                    final long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                    final boolean ok = status >= 200 && status < 400;
                    return new ProbeResult(ok, status, ms, current.uri().toString(), ok ? "" : ("HTTP " + status));
                } finally {
                    PINNED_TARGET.remove();
                }
            }
            final long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            return new ProbeResult(false, 0, ms, rawUrl, "重定向次数过多");
        } catch (final Exception e) {
            final long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            return new ProbeResult(false, 0, ms, rawUrl, e.getMessage() == null ? "连接失败" : e.getMessage());
        }
    }

    /**
     * Sends an SSRF-safe JSON POST request with pinned DNS resolution and hop-by-hop validation.
     */
    public static int postJson(final String rawUrl, final String jsonBody,
                               final java.util.Map<String, String> extraHeaders,
                               final int connectTimeoutMs, final int readTimeoutMs,
                               final boolean allowLoopback) throws IOException {
        ValidatedTarget current = resolveValidated(rawUrl, allowLoopback);
        final RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(connectTimeoutMs)
                .setConnectionRequestTimeout(connectTimeoutMs)
                .setSocketTimeout(readTimeoutMs)
                .setRedirectsEnabled(false)
                .build();
        for (int hop = 0; hop <= 2; hop++) {
            final HttpPost post = new HttpPost(current.uri());
            post.setConfig(requestConfig);
            post.setHeader("User-Agent", "Volo-Webhook/3.0 (+https://github.com/Willh92/Volo-solo)");
            post.setHeader("Content-Type", "application/json; charset=UTF-8");
            if (extraHeaders != null) {
                for (final var entry : extraHeaders.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        post.setHeader(entry.getKey(), entry.getValue());
                    }
                }
            }
            post.setEntity(new StringEntity(jsonBody == null ? "{}" : jsonBody, ContentType.APPLICATION_JSON));
            PINNED_TARGET.set(current);
            try (CloseableHttpResponse response = SAFE_HTTP_CLIENT.execute(post)) {
                final int status = response.getStatusLine().getStatusCode();
                if ((status == 307 || status == 308) && hop < 2) {
                    final Header loc = response.getFirstHeader("Location");
                    if (loc != null && loc.getValue() != null && !loc.getValue().isBlank()) {
                        final URI next = current.uri().resolve(loc.getValue().strip());
                        current = resolveValidated(next.toString(), allowLoopback);
                        EntityUtils.consumeQuietly(response.getEntity());
                        continue;
                    }
                }
                EntityUtils.consumeQuietly(response.getEntity());
                return status;
            } finally {
                PINNED_TARGET.remove();
            }
        }
        throw new IOException("Too many redirects");
    }

    private static boolean hasControlOrWhitespace(final String value) {
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c <= 0x20 || c == 0x7f) {
                return true;
            }
        }
        return false;
    }
}
