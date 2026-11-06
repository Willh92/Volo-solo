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
package org.b3log.solo.service;

import org.b3log.latke.ioc.Inject;
import org.apache.logging.log4j.Level;
import org.b3log.solo.logging.Logger;
import org.b3log.latke.repository.RepositoryException;
import org.b3log.latke.repository.Transaction;
import org.b3log.latke.service.LangPropsService;
import org.b3log.latke.service.ServiceException;
import org.b3log.latke.service.annotation.Service;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.util.URLs;
import org.b3log.solo.cache.StatisticCache;
import org.b3log.solo.model.Option;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.repository.OptionRepository;
import org.b3log.solo.util.Solos;
import org.json.JSONArray;
import org.json.JSONObject;

import org.b3log.latke.http.Cookie;
import org.b3log.latke.http.Request;
import org.b3log.latke.http.Response;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Statistic management service.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 0.5.0
 */
@Service
public class StatisticMgmtService {

    /**
     * Online visitor cache.
     * <p>
     * &lt;ip, recentTime&gt;
     * </p>
     */
    public static final Map<String, Long> ONLINE_VISITORS = new ConcurrentHashMap<>();
    public static final int MAX_ONLINE_VISITORS = 10_000;

    private static final String ATTR_VISITED_CHECKED = "bolo.visitedChecked";
    private static final String ATTR_HAS_BEEN_SERVED = "bolo.hasBeenServed";

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(StatisticMgmtService.class);

    /**
     * Online visitor expiration in 5 minutes.
     */
    private static final int ONLINE_VISITOR_EXPIRATION = 300000;

    /**
     * Cookie expiry of "visited".
     */
    private static final int COOKIE_EXPIRY = 60 * 60 * 24; // 24 hours

    /**
     * Option repository.
     */
    @Inject
    private OptionRepository optionRepository;

    /**
     * Language service.
     */
    @Inject
    private LangPropsService langPropsService;

    /**
     * Article repository.
     */
    @Inject
    private ArticleRepository articleRepository;

    /**
     * Statistic cache.
     */
    @Inject
    private StatisticCache statisticCache;

    /**
     * Zero-PII daily analytics trend service.
     */
    @Inject
    private AnalyticsTrendService analyticsTrendService;

    private final LongAdder pendingBlogViewDelta = new LongAdder();
    private final Object viewFlushLock = new Object();

    /**
     * Removes the expired online visitor.
     */
    public static void removeExpiredOnlineVisitor() {
        final long currentTimeMillis = System.currentTimeMillis();

        final Iterator<Map.Entry<String, Long>> iterator = ONLINE_VISITORS.entrySet().iterator();

        while (iterator.hasNext()) {
            final Map.Entry<String, Long> onlineVisitor = iterator.next();

            if (currentTimeMillis > (onlineVisitor.getValue() + ONLINE_VISITOR_EXPIRATION)) {
                iterator.remove();
                if (LOGGER.isTraceEnabled()) {
                    LOGGER.log(Level.TRACE, "Removed online visitor[ip={0}]", onlineVisitor.getKey());
                }
            }
        }

        if (LOGGER.isDebugEnabled()) {
            LOGGER.log(Level.DEBUG, "Current online visitor count [{0}]", ONLINE_VISITORS.size());
        }
    }

    /**
     * Computes a compact 11-character unpadded Base64URL token (64-bit SHA-256 prefix) for a request URI.
     * <p>
     * Uses only URL-safe ASCII characters ({@code A-Za-z0-9_-}) requiring zero percent-encoding expansion,
     * keeping 64 visited entries well under 1 KiB even for long CJK permalinks.
     * </p>
     *
     * @param requestURI request URI
     * @return 11-character Base64URL digest token
     */
    public static String hashVisitedToken(final String requestURI) {
        final String normalized = null == requestURI ? "/" : requestURI;
        try {
            final byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            final byte[] prefix = java.util.Arrays.copyOf(digest, 8);
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(prefix);
        } catch (final java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /**
     * Determines whether the specified request has been served.
     * <p>
     * Memoizes the parsed {@code visited} cookie outcome per {@link Request} so repeated invocations within
     * the same request lifecycle (e.g. {@code ArticleProcessor.showArticle} + {@code incBlogViewCount}) decode
     * and parse the cookie at most once.
     * </p>
     *
     * @param context  the specified request context
     * @param response the specified response
     * @return {@code true} if the specified request has been served, returns {@code false} otherwise
     */
    public static boolean hasBeenServed(final RequestContext context, final Response response) {
        final Request request = null == context ? null : context.getRequest();
        if (null == request) {
            return false;
        }
        final Object memoServed = request.getAttribute(ATTR_HAS_BEEN_SERVED);
        if (memoServed instanceof Boolean served) {
            return served;
        }

        final String requestURI = null == request.getRequestURI() ? "/" : request.getRequestURI();
        final String visitedToken = hashVisitedToken(requestURI);
        final var rawCookies = request.getCookies();
        boolean needToCreate = true;
        boolean needToAppend = true;
        JSONArray cookieJSONArray = null;

        try {
            if (null != rawCookies) {
                for (final Cookie cookie : rawCookies) {
                    if (null == cookie || !"visited".equals(cookie.getName())) {
                        continue;
                    }

                    final String value = URLs.decode(cookie.getValue());
                    cookieJSONArray = new JSONArray(value);
                    needToCreate = false;
                    request.setAttribute(ATTR_VISITED_CHECKED, cookieJSONArray.length() > 0);

                    for (int j = 0; j < cookieJSONArray.length(); j++) {
                        final String visitedURL = cookieJSONArray.optString(j);
                        if (visitedToken.equals(visitedURL) || requestURI.equals(visitedURL)) {
                            needToAppend = false;
                            request.setAttribute(ATTR_HAS_BEEN_SERVED, Boolean.TRUE);
                            return true;
                        }
                    }
                    break;
                }
            }

            if (needToCreate) {
                request.setAttribute(ATTR_VISITED_CHECKED, Boolean.FALSE);
                final JSONArray initial = new JSONArray().put(visitedToken);
                final Cookie c = new Cookie("visited", URLs.encode(initial.toString()));
                c.setMaxAge(COOKIE_EXPIRY);
                c.setPath("/");
                c.setHttpOnly(true);
                c.setSameSite("Lax");
                writeVisitedCookie(response, c);
            } else if (needToAppend) {
                final JSONArray compacted = new JSONArray();
                final int startIdx = Math.max(0, cookieJSONArray.length() - 63);
                for (int j = startIdx; j < cookieJSONArray.length(); j++) {
                    final String existing = cookieJSONArray.optString(j);
                    if (existing.isEmpty()) {
                        continue;
                    }
                    compacted.put(existing.startsWith("/") ? hashVisitedToken(existing) : existing);
                }
                compacted.put(visitedToken);

                final Cookie c = new Cookie("visited", URLs.encode(compacted.toString()));
                c.setMaxAge(COOKIE_EXPIRY);
                c.setPath("/");
                c.setHttpOnly(true);
                c.setSameSite("Lax");
                writeVisitedCookie(response, c);
            }
        } catch (final Exception e) {
            LOGGER.log(Level.WARN, "Parses cookie failed, clears the cookie[name=visited]");
            request.setAttribute(ATTR_VISITED_CHECKED, Boolean.FALSE);

            final Cookie c = new Cookie("visited", "");
            c.setMaxAge(0);
            c.setPath("/");
            writeVisitedCookie(response, c);
        }

        request.setAttribute(ATTR_HAS_BEEN_SERVED, Boolean.FALSE);
        return false;
    }

    private static void writeVisitedCookie(final Response response, final Cookie cookie) {
        if (null == response) {
            return;
        }
        response.getCookies().removeIf(existing -> "visited".equals(existing.getName()));
        response.addCookie(cookie);
    }

    private static boolean hasValidVisitedCookie(final Request request) {
        if (null == request) {
            return false;
        }
        final Object memoValid = request.getAttribute(ATTR_VISITED_CHECKED);
        if (memoValid instanceof Boolean valid) {
            return valid;
        }
        boolean result = false;
        if (null != request.getCookies()) {
            for (final Cookie cookie : request.getCookies()) {
                if (null != cookie && "visited".equals(cookie.getName())) {
                    final String rawVal = cookie.getValue();
                    if (null != rawVal && !rawVal.isBlank()) {
                        try {
                            final JSONArray arr = new JSONArray(URLs.decode(rawVal));
                            result = arr.length() > 0;
                        } catch (final Exception ignored) {
                            result = false;
                        }
                    }
                    break;
                }
            }
        }
        request.setAttribute(ATTR_VISITED_CHECKED, result);
        return result;
    }

    /**
     * Blog statistic view count +1 (lock-free write-behind).
     * <p>
     * If it is a search engine bot made the specified request, will NOT increment blog statistic view count.
     * </p>
     *
     * @param context  the specified request context
     * @param response the specified response
     * @throws ServiceException service exception
     */
    public void incBlogViewCount(final RequestContext context, final Response response) throws ServiceException {
        if (Solos.isBot(context.getRequest())) {
            return;
        }

        if (hasBeenServed(context, response)) {
            return;
        }
        final boolean isFirstVisit = !hasValidVisitedCookie(context.getRequest());

        if (null != analyticsTrendService) {
            analyticsTrendService.recordSiteVisit(context, isFirstVisit);
        }

        pendingBlogViewDelta.increment();
    }

    /**
     * Returns the un-flushed write-behind blog view delta.
     *
     * @return non-negative pending blog view delta
     */
    public long getPendingBlogViewDelta() {
        return Math.max(0L, pendingBlogViewDelta.sum());
    }

    /**
     * Resets in-memory write-behind blog view delta (used when restoring a backup).
     */
    public void resetPendingViewDeltas() {
        synchronized (viewFlushLock) {
            pendingBlogViewDelta.reset();
        }
    }

    /**
     * Atomically drains accumulated write-behind blog views and persists them to
     * {@link Option#ID_C_STATISTIC_BLOG_VIEW_COUNT} in a single transaction, restoring the delta on failure.
     */
    public void flushPendingViewCounts() {
        if (null == optionRepository) {
            return;
        }
        synchronized (viewFlushLock) {
            final long delta = pendingBlogViewDelta.sumThenReset();
            if (delta <= 0L) {
                return;
            }
            final Transaction transaction = optionRepository.beginTransaction();
            try {
                final JSONObject statistic = optionRepository.get(Option.ID_C_STATISTIC_BLOG_VIEW_COUNT);
                if (null == statistic) {
                    if (null != transaction && transaction.isActive()) {
                        transaction.rollback();
                    }
                    return;
                }
                final long current = Math.max(0L, statistic.optLong(Option.OPTION_VALUE, 0L));
                statistic.put(Option.OPTION_VALUE, String.valueOf(current + delta));
                updateStatistic(Option.ID_C_STATISTIC_BLOG_VIEW_COUNT, statistic);
                transaction.commit();
            } catch (final Exception e) {
                if (null != transaction && transaction.isActive()) {
                    transaction.rollback();
                }
                optionRepository.evictOption(Option.ID_C_STATISTIC_BLOG_VIEW_COUNT);
                if (null != statisticCache) {
                    statisticCache.clearStatistic();
                }
                pendingBlogViewDelta.add(delta);
                LOGGER.log(Level.ERROR, "Flushes pending blog view count failed", e);
            }
        }
    }

    /**
     * Refreshes online visitor count for the specified request.
     *
     * @param request the specified request
     */
    public void onlineVisitorCount(final Request request) {
        if (Solos.isBot(request)) {
            return;
        }

        final String remoteAddr = org.b3log.solo.util.LoginThrottle.clientAddress(request);
        if (!ONLINE_VISITORS.containsKey(remoteAddr) && ONLINE_VISITORS.size() >= MAX_ONLINE_VISITORS) {
            removeExpiredOnlineVisitor();
            if (ONLINE_VISITORS.size() >= MAX_ONLINE_VISITORS) {
                return;
            }
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.log(Level.DEBUG, "Current request [IP={0}]", remoteAddr);
        }
        ONLINE_VISITORS.put(remoteAddr, System.currentTimeMillis());
        if (LOGGER.isDebugEnabled()) {
            LOGGER.log(Level.DEBUG, "Current online visitor count [{0}]", ONLINE_VISITORS.size());
        }
    }

    /**
     * Updates the statistic with the specified statistic.
     *
     * @param id        the specified statistic id
     * @param statistic the specified statistic
     * @throws RepositoryException repository exception
     */
    private void updateStatistic(final String id, final JSONObject statistic) throws RepositoryException {
        optionRepository.update(id, statistic);
        if (null != statisticCache) {
            statisticCache.clearStatistic();
        }
    }
}
