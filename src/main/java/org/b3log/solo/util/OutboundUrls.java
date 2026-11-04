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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

/**
 * Validates external HTTP(S) URLs and prevents Server-Side Request Forgery (SSRF)
 * to loopback, private, link-local, or cloud-metadata endpoints.
 */
public final class OutboundUrls {

    private static final int MAX_URL_LENGTH = 2048;
    private static final int MAX_REDIRECTS = 3;

    private OutboundUrls() {
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
    public static URI requirePublicHttpUri(final String raw, final boolean allowLoopback) throws IOException {
        if (!isHttpOrHttps(raw)) {
            throw new IOException("Only valid http:// or https:// URLs are allowed");
        }
        final URI uri = URI.create(raw.strip());
        final String rawHost = uri.getHost();
        final String host = rawHost.startsWith("[") && rawHost.endsWith("]")
                ? rawHost.substring(1, rawHost.length() - 1)
                : rawHost;
        final InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0) {
            throw new IOException("Host did not resolve to any address");
        }
        for (final InetAddress address : addresses) {
            if (!isAllowedRemoteAddress(address, allowLoopback)) {
                throw new IOException("Target address is restricted");
            }
        }
        return uri;
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
            // IPv6 Unique Local Address fc00::/7
            if ((bytes[0] & 0xfe) == 0xfc) {
                return false;
            }
            // IPv4-mapped IPv6 ::ffff:x.x.x.x
            boolean v4Mapped = true;
            for (int i = 0; i < 10; i++) {
                if (bytes[i] != 0) {
                    v4Mapped = false;
                    break;
                }
            }
            if (v4Mapped && (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff) {
                final byte[] v4 = new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]};
                return isPublicIpv4(v4, allowLoopback);
            }
        }
        return true;
    }

    private static boolean isPublicIpv4(final byte[] b, final boolean allowLoopback) {
        final int b0 = b[0] & 0xff;
        final int b1 = b[1] & 0xff;
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
     * Downloads an external resource with strict public-address validation on every redirect hop and a hard byte cap.
     */
    public static byte[] fetchBytes(final String rawUrl, final int connectTimeoutMs, final int readTimeoutMs,
                                    final int maxBytes, final String acceptHeader, final String userAgent,
                                    final boolean allowLoopback) throws IOException {
        URI current = requirePublicHttpUri(rawUrl, allowLoopback);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            final HttpURLConnection connection = (HttpURLConnection) current.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            if (acceptHeader != null && !acceptHeader.isBlank()) {
                connection.setRequestProperty("Accept", acceptHeader);
            }
            if (userAgent != null && !userAgent.isBlank()) {
                connection.setRequestProperty("User-Agent", userAgent);
            }
            try {
                final int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    if (hop == MAX_REDIRECTS) {
                        throw new IOException("Too many redirects");
                    }
                    final String location = connection.getHeaderField("Location");
                    if (location == null || location.isBlank()) {
                        throw new IOException("Redirect missing Location header");
                    }
                    final URI next = current.resolve(location.strip());
                    current = requirePublicHttpUri(next.toString(), allowLoopback);
                    continue;
                }
                if (status < 200 || status >= 300) {
                    throw new IOException("Unexpected HTTP status " + status);
                }
                final long declaredLength = connection.getContentLengthLong();
                if (declaredLength > maxBytes) {
                    throw new IOException("Remote response exceeds maximum allowed size");
                }
                try (InputStream in = connection.getInputStream();
                     ByteArrayOutputStream out = new ByteArrayOutputStream(
                             declaredLength > 0 ? (int) Math.min(declaredLength, 65536) : 8192)) {
                    final byte[] buffer = new byte[8192];
                    int total = 0;
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        total += read;
                        if (total > maxBytes) {
                            throw new IOException("Remote response exceeds maximum allowed size");
                        }
                        out.write(buffer, 0, read);
                    }
                    return out.toByteArray();
                }
            } finally {
                connection.disconnect();
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
