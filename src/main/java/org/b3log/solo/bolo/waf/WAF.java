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
package org.b3log.solo.bolo.waf;

import org.b3log.latke.Latkes;
import org.b3log.solo.bolo.pic.PicUploadProcessor;
import org.b3log.solo.bolo.prop.Options;
import org.b3log.solo.model.Option;
import pers.adlered.simplecurrentlimiter.main.SimpleCurrentLimiter;

/**
 * <h3>bolo-solo</h3>
 * <p>Web Application Firewall.</p>
 *
 * @author : https://github.com/adlered
 * @date : 2020-05-31
 **/
public class WAF {
    public static volatile boolean POWER = false;
    private static final long WARN_INTERVAL_MS = 10_000L;
    private static final int MAX_WARN_TRACKED_IPS = 2048;
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> LAST_WARN_AT =
            new java.util.concurrent.ConcurrentHashMap<>(256);
    private static final java.util.concurrent.atomic.AtomicBoolean PRUNING_WARN =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private static boolean shouldLogWarn(final String requestIP) {
        final String key = requestIP == null ? "" : requestIP;
        final long now = System.currentTimeMillis();
        final Long prev = LAST_WARN_AT.get(key);
        if (prev != null && now - prev < WARN_INTERVAL_MS) {
            return false;
        }
        if (prev == null && LAST_WARN_AT.size() >= MAX_WARN_TRACKED_IPS) {
            if (PRUNING_WARN.compareAndSet(false, true)) {
                try {
                    LAST_WARN_AT.entrySet().removeIf(e -> now - e.getValue() >= WARN_INTERVAL_MS);
                    if (LAST_WARN_AT.size() >= MAX_WARN_TRACKED_IPS) {
                        final java.util.Iterator<String> it = LAST_WARN_AT.keySet().iterator();
                        int evicted = 0;
                        while (evicted < 64 && it.hasNext()) {
                            it.next();
                            it.remove();
                            evicted++;
                        }
                    }
                } finally {
                    PRUNING_WARN.set(false);
                }
            } else if (LAST_WARN_AT.size() >= MAX_WARN_TRACKED_IPS * 2) {
                return false;
            }
        }
        final Long raced = LAST_WARN_AT.put(key, now);
        return raced == null || now - raced >= WARN_INTERVAL_MS;
    }

    public static synchronized void on() {
        if (!POWER) {


            int second;
            int times;

            try {
                second = Integer.parseInt(Options.get(Option.ID_C_WAF_CURRENT_LIMIT_SECOND));
                times = Integer.parseInt(Options.get(Option.ID_C_WAF_CURRENT_LIMIT_TIMES));
            } catch (Exception e) {
                second = 180;
                times = 180;
            }

            WAFstorage.currentLimiter = new SimpleCurrentLimiter(second, times);
            POWER = true;
        }
    }

    public static synchronized void off() {
        if (POWER) {
            POWER = false;

            WAFstorage.currentLimiter = null;
            LAST_WARN_AT.clear();
        }
    }

    public static synchronized void restart() {
        off();
        on();
    }

    public static synchronized void set() {
        try {
            String wafPower = Options.get(Option.ID_C_WAF_POWER);
            if ("false".equalsIgnoreCase(wafPower)) {
                WAF.off();
            } else {
                WAF.restart();
            }
        } catch (Exception e) {
            WAF.restart();
        }
    }

    private static boolean isSafeLocalImageRequest(final String requestURL) {
        if (requestURL == null || !requestURL.startsWith("/image/")) {
            return false;
        }
        final String rawName = requestURL.substring("/image/".length());
        final int q = rawName.indexOf('?');
        final String filename = q >= 0 ? rawName.substring(0, q) : rawName;
        return PicUploadProcessor.safeImageContentType(filename) != null;
    }

    public static boolean in(String requestIP, String requestURL) {
        // 防火墙未开启直接放行
        if (!WAF.POWER) {
            return true;
        }

        final String contextPath = Latkes.getContextPath();
        if (!contextPath.isEmpty() && requestURL.startsWith(contextPath)) {
            requestURL = requestURL.substring(contextPath.length());
        }
        if (requestURL.isEmpty()) {
            requestURL = "/";
        }

        if (!(
                requestURL.startsWith("/articles/random") ||
                        requestURL.startsWith("/manifest.json") ||
                        requestURL.endsWith("/relevant/articles") ||
                        requestURL.startsWith("/opensearch.xml") ||
                        requestURL.startsWith("/waf/denied") ||
                        requestURL.startsWith("/admin") ||
                        requestURL.startsWith("/console") ||
                        requestURL.startsWith("/plugins") ||
                        requestURL.startsWith("/bolo") ||
                        requestURL.startsWith("/logout") ||
                        requestURL.startsWith("/start") ||
                        requestURL.startsWith("/favicon.ico") ||
                        // Loading one page may need dozens of scripts, styles and font files.
                        // PublicResourceHandler still checks methods, extensions and filesystem containment.
                        requestURL.startsWith("/js/") ||
                        requestURL.startsWith("/css/") ||
                        requestURL.startsWith("/scss/") ||
                        requestURL.startsWith("/skins/") ||
                        requestURL.equals("/sw.js") ||
                        requestURL.equals("/service-worker.js") ||
                        requestURL.equals("/robots.txt") ||
                        requestURL.startsWith("/error") ||
                        requestURL.startsWith("/images") ||
                        requestURL.startsWith("/article/commentSync/getList") ||
                        requestURL.startsWith("/PBC/status") ||
                        requestURL.startsWith("/favicon") ||
                        requestURL.startsWith("/oauth/bolo/login") ||
                        isSafeLocalImageRequest(requestURL)
        )) {

            final SimpleCurrentLimiter limiter = WAFstorage.currentLimiter;

            if (limiter == null || limiter.access(requestIP)) {
                WAFlogger.logTrace(requestIP, requestURL);

                return true;
            } else {
                if (shouldLogWarn(requestIP)) {
                    WAFlogger.logWarn("Illegal access from " + requestIP + " was denied to " + requestURL);
                }

                return false;
            }
        }

        return true;
    }

}
