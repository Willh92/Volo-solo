/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.service;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;
import org.b3log.latke.Latkes;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.repository.jdbc.JdbcRepository;
import org.b3log.latke.service.annotation.Service;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.model.Option;
import org.b3log.solo.util.OutboundUrls;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * IndexNow instant search-engine URL notification and key verification service.
 *
 * @author <a href="https://github.com/Willh92">Willh92</a>
 * @since 3.0.0
 */
@Service
public class IndexNowService {

    private static final Logger LOGGER = Logger.getLogger(IndexNowService.class);

    public static final String DEFAULT_ENDPOINT_URL = "https://api.indexnow.org/indexnow";
    public static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 8000;
    private static final int MAX_URLS_PER_BATCH = 10_000;

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger(1);
    private static final ThreadPoolExecutor DISPATCH_POOL = createDispatchPool();

    /**
     * Gracefully shuts down the background IndexNow dispatch thread pool.
     */
    public static void shutdown() {
        DISPATCH_POOL.shutdown();
        try {
            if (!DISPATCH_POOL.awaitTermination(5L, TimeUnit.SECONDS)) {
                DISPATCH_POOL.shutdownNow();
            }
        } catch (final InterruptedException e) {
            DISPATCH_POOL.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Inject
    private OptionQueryService optionQueryService;

    private volatile String endpointUrl = DEFAULT_ENDPOINT_URL;
    private volatile boolean allowLoopbackEndpoint;

    private static ThreadPoolExecutor createDispatchPool() {
        final ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 2, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64),
                runnable -> {
                    final Thread t = new Thread(runnable, "volo-indexnow-" + THREAD_SEQ.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.DiscardOldestPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    void setOptionQueryService(final OptionQueryService optionQueryService) {
        this.optionQueryService = optionQueryService;
    }

    public String getEndpointUrl() {
        return endpointUrl;
    }

    public void setEndpointUrl(final String endpointUrl) {
        this.endpointUrl = StringUtils.isBlank(endpointUrl) ? DEFAULT_ENDPOINT_URL : endpointUrl.trim();
    }

    void setAllowLoopbackEndpoint(final boolean allowLoopbackEndpoint) {
        this.allowLoopbackEndpoint = allowLoopbackEndpoint;
    }

    /**
     * Checks whether {@code key} conforms to the IndexNow key format ({@code 8..64} ASCII alphanumeric or hyphen chars).
     */
    public boolean isValidKey(final String key) {
        return key != null && key.length() <= 64 && KEY_PATTERN.matcher(key).matches();
    }

    /**
     * Verifies a public {@code /{indexNowKey}.txt} request against current preference settings in constant time.
     */
    public boolean verifyKeyRequest(final String candidateKey) {
        if (!isValidKey(candidateKey) || optionQueryService == null) {
            return false;
        }
        final JSONObject pref = optionQueryService.getPreference();
        if (pref == null || !Option.optBooleanSwitch(pref, Option.ID_C_INDEXNOW_ENABLED, false)) {
            return false;
        }
        final String configuredKey = StringUtils.trimToEmpty(pref.optString(Option.ID_C_INDEXNOW_KEY));
        if (!isValidKey(configuredKey)) {
            return false;
        }
        return MessageDigest.isEqual(
                configuredKey.getBytes(StandardCharsets.UTF_8),
                candidateKey.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Asynchronously notifies IndexNow of newly published or updated public URLs.
     * Silently skips when disabled, misconfigured, or running on a local/loopback/private {@code servePath}.
     */
    public void notifyUrlsAsync(final List<String> relativeOrFullUrls) {
        if (relativeOrFullUrls == null || relativeOrFullUrls.isEmpty() || optionQueryService == null) {
            return;
        }
        final JSONObject pref = optionQueryService.getPreference();
        if (pref == null || !Option.optBooleanSwitch(pref, Option.ID_C_INDEXNOW_ENABLED, false)) {
            return;
        }
        final String key = StringUtils.trimToEmpty(pref.optString(Option.ID_C_INDEXNOW_KEY));
        if (!isValidKey(key)) {
            LOGGER.log(Level.DEBUG, "Skipping IndexNow notification: configured key is empty or invalid");
            return;
        }
        final String servePath = Latkes.getServePath();
        if (isLocalOrPrivateServePath(servePath)) {
            LOGGER.log(Level.DEBUG, "Skipping IndexNow notification on local/private servePath [{0}]", servePath);
            return;
        }
        final JSONObject payload = buildPayload(servePath, key, relativeOrFullUrls);
        if (payload == null || DISPATCH_POOL.isShutdown()) {
            return;
        }
        final String targetEndpoint = this.endpointUrl;
        final boolean allowLoopback = this.allowLoopbackEndpoint;
        final String jsonBody = payload.toString();
        final int urlCount = payload.getJSONArray("urlList").length();

        DISPATCH_POOL.execute(() -> {
            try {
                final int status = OutboundUrls.postJson(
                        targetEndpoint, jsonBody, Map.of(), CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, allowLoopback);
                if (status >= 200 && status < 300) {
                    LOGGER.log(Level.DEBUG, "IndexNow notified {0} URL(s), HTTP {1}", urlCount, status);
                } else {
                    LOGGER.log(Level.WARN, "IndexNow endpoint returned HTTP {0}", status);
                }
            } catch (final Exception e) {
                LOGGER.log(Level.WARN, "IndexNow notification failed: " + e.getMessage());
            } finally {
                JdbcRepository.dispose();
            }
        });
    }

    /**
     * Builds the IndexNow JSON payload using {@link Latkes#getServePath()} and the currently configured key.
     */
    public JSONObject buildPayload(final List<String> relativeOrFullUrls) {
        if (optionQueryService == null) {
            return null;
        }
        final JSONObject pref = optionQueryService.getPreference();
        if (pref == null) {
            return null;
        }
        final String key = StringUtils.trimToEmpty(pref.optString(Option.ID_C_INDEXNOW_KEY));
        return buildPayload(Latkes.getServePath(), key, relativeOrFullUrls);
    }

    /**
     * Builds the IndexNow JSON payload {@code {host, key, keyLocation, urlList}} deterministically.
     */
    public JSONObject buildPayload(final String servePath, final String key, final List<String> relativeOrFullUrls) {
        if (StringUtils.isBlank(servePath) || !isValidKey(key)
                || relativeOrFullUrls == null || relativeOrFullUrls.isEmpty()) {
            return null;
        }
        final String base = stripTrailingSlash(servePath.trim());
        final URI baseUri;
        try {
            baseUri = URI.create(base);
        } catch (final IllegalArgumentException e) {
            return null;
        }
        final String scheme = baseUri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            return null;
        }
        final String host = stripIpv6Brackets(baseUri.getHost());
        if (StringUtils.isBlank(host)) {
            return null;
        }

        final Set<String> normalizedUrls = new LinkedHashSet<>();
        for (final String rawUrl : relativeOrFullUrls) {
            if (normalizedUrls.size() >= MAX_URLS_PER_BATCH) {
                break;
            }
            final String candidate = StringUtils.trimToEmpty(rawUrl);
            if (candidate.isEmpty()) {
                continue;
            }
            if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
                try {
                    final URI u = URI.create(candidate);
                    final String uHost = stripIpv6Brackets(u.getHost());
                    if (host.equalsIgnoreCase(uHost)) {
                        normalizedUrls.add(candidate);
                    }
                } catch (final IllegalArgumentException ignored) {
                }
            } else if (!candidate.startsWith("//") && candidate.indexOf(':') < 0) {
                final String rel = candidate.startsWith("/") ? candidate : ("/" + candidate);
                normalizedUrls.add(base + rel);
            }
        }
        if (normalizedUrls.isEmpty()) {
            return null;
        }

        return new JSONObject()
                .put("host", host)
                .put("key", key)
                .put("keyLocation", base + "/" + key + ".txt")
                .put("urlList", new JSONArray(normalizedUrls));
    }

    /**
     * Pure syntactic local/loopback/private host check on {@code servePath} (avoids DNS lookups on public domains).
     */
    public static boolean isLocalOrPrivateServePath(final String servePath) {
        if (StringUtils.isBlank(servePath)) {
            return true;
        }
        final URI uri;
        try {
            uri = URI.create(servePath.trim());
        } catch (final IllegalArgumentException e) {
            return true;
        }
        final String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            return true;
        }
        final String rawHost = stripIpv6Brackets(uri.getHost());
        if (StringUtils.isBlank(rawHost)) {
            return true;
        }
        final String host = rawHost.toLowerCase(Locale.ROOT);
        if ("localhost".equals(host)
                || host.endsWith(".localhost")
                || host.endsWith(".local")
                || host.endsWith(".internal")
                || "0.0.0.0".equals(host)) {
            return true;
        }
        if (!host.contains(".")) {
            if (!host.contains(":")) {
                // Single-label hostname without dots (e.g., intranet container name)
                return true;
            }
        }
        final int[] ipv4 = parseIpv4Literal(host);
        if (ipv4 != null) {
            final int a = ipv4[0];
            final int b = ipv4[1];
            return a == 0
                    || a == 10
                    || a == 127
                    || (a == 169 && b == 254)
                    || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 168)
                    || (a == 100 && b >= 64 && b <= 127)
                    || a >= 224;
        }
        if (host.contains(":")) {
            try {
                final InetAddress addr = InetAddress.getByName(rawHost);
                final byte[] bytes = addr.getAddress();
                if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()
                        || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                        || addr.isMulticastAddress()) {
                    return true;
                }
                if (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc) {
                    return true;
                }
            } catch (final Exception e) {
                return true;
            }
        }
        return false;
    }

    private static int[] parseIpv4Literal(final String host) {
        final String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        final int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            final String p = parts[i];
            if (p.isEmpty() || p.length() > 3) {
                return null;
            }
            for (int j = 0; j < p.length(); j++) {
                if (!Character.isDigit(p.charAt(j))) {
                    return null;
                }
            }
            final int val = Integer.parseInt(p);
            if (val < 0 || val > 255) {
                return null;
            }
            octets[i] = val;
        }
        return octets;
    }

    private static String stripTrailingSlash(final String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }

    private static String stripIpv6Brackets(final String host) {
        if (host == null) {
            return null;
        }
        if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }
}
