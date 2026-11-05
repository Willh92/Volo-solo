/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.service;

import io.netty.util.NetUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.http.Request;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.service.annotation.Service;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Option;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.util.OptionDocuments;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * Privacy-first, zero-PII daily analytics trend service.
 * Aggregates daily PV, UV, article views, and external referer domains in memory
 * and flushes bounded 90-day buckets to {@link Option#ID_C_VOLO_DAILY_STATS}.
 *
 * @author <a href="https://github.com/Willh92">Willh92</a>
 * @since 3.0.0
 */
@Service
public class AnalyticsTrendService {

    private static final Logger LOGGER = Logger.getLogger(AnalyticsTrendService.class);

    public static final int MAX_RETENTION_DAYS = 90;
    public static final int MAX_ARTICLES_PER_DAY = 200;
    public static final int MAX_REFERERS_PER_DAY = 100;
    private static final int BATCH_ARTICLE_PRELOAD_LIMIT = 30;

    private static final DateTimeFormatter YYYY_MM_DD = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);
    private static final DateTimeFormatter MM_DD = DateTimeFormatter.ofPattern("MM-dd", Locale.ROOT);

    static final class DayBucket {
        final LongAdder pv = new LongAdder();
        final LongAdder uv = new LongAdder();
        final ConcurrentHashMap<String, LongAdder> articleViews = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, LongAdder> refererDomains = new ConcurrentHashMap<>();
    }

    private record CachedZone(String rawTzId, ZoneId zoneId) {
    }

    private record CachedToday(long epochSecond, ZoneId zoneId, LocalDate date, String dateKey) {
    }

    private record CachedSelfHost(String rawServePath, String normalizedHost) {
    }

    private record PreloadedArticles(Set<String> queriedIds, Map<String, JSONObject> byId) {
    }

    private static volatile CachedSelfHost cachedSelfHost;

    private final LongSupplier clock;
    private final ConcurrentHashMap<String, DayBucket> buckets = new ConcurrentHashMap<>();
    private final AtomicBoolean loaded = new AtomicBoolean(false);
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final Object loadLock = new Object();
    private final Object flushLock = new Object();

    private volatile CachedZone cachedZone;
    private volatile CachedToday cachedToday;

    @Inject
    private OptionQueryService optionQueryService;

    @Inject
    private OptionMgmtService optionMgmtService;

    @Inject
    private ArticleRepository articleRepository;

    public AnalyticsTrendService() {
        this(System::currentTimeMillis);
    }

    public AnalyticsTrendService(final LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Records a non-bot, unserved site page visit into today's zero-PII bucket.
     *
     * @param context               current HTTP request context (optional)
     * @param isFirstVisitOfSession {@code true} if the visitor had no prior {@code visited} cookie
     */
    public void recordSiteVisit(final RequestContext context, final boolean isFirstVisitOfSession) {
        ensureLoaded();
        final CachedToday today = resolveToday();
        final DayBucket bucket = getOrCreateBucket(today.date, today.dateKey);
        bucket.pv.increment();
        if (isFirstVisitOfSession) {
            bucket.uv.increment();
        }
        final String refererHeader = extractRefererHeader(context);
        if (StringUtils.isNotBlank(refererHeader)) {
            final String domain = extractExternalRefererDomain(refererHeader, safeServePath());
            if (domain != null) {
                incrementBounded(bucket.refererDomains, domain, MAX_REFERERS_PER_DAY, 1L);
            }
        }
        dirty.set(true);
    }

    /**
     * Records an article detail view into today's zero-PII bucket.
     *
     * @param articleId article object ID
     */
    public void recordArticleView(final String articleId) {
        if (StringUtils.isBlank(articleId) || articleId.length() > 64) {
            return;
        }
        ensureLoaded();
        final CachedToday today = resolveToday();
        final DayBucket bucket = getOrCreateBucket(today.date, today.dateKey);
        incrementBounded(bucket.articleViews, articleId.trim(), MAX_ARTICLES_PER_DAY, 1L);
        dirty.set(true);
    }

    /**
     * Flushes in-memory daily buckets to {@link OptionDocuments} if modified since the last flush.
     */
    public void flushIfDirty() {
        if (!dirty.get() || optionMgmtService == null) {
            return;
        }
        ensureLoaded();
        synchronized (flushLock) {
            if (!dirty.compareAndSet(true, false)) {
                return;
            }
            try {
                final LocalDate today = currentDate();
                pruneOldBuckets(today);
                final JSONObject doc = serializeBuckets(clock.getAsLong());
                optionMgmtService.storeDocument(
                        Option.ID_C_VOLO_DAILY_STATS,
                        Option.CATEGORY_C_VOLO_ANALYTICS,
                        doc.toString()
                );
            } catch (final Exception e) {
                dirty.set(true);
                LOGGER.log(Level.WARN, "Flushes daily analytics trends failed", e);
            }
        }
    }

    /**
     * Clears the in-memory daily bucket cache so the next access reloads from {@link OptionDocuments}.
     * Useful immediately after restoring a backup archive.
     */
    public void reloadFromStorage() {
        synchronized (loadLock) {
            synchronized (flushLock) {
                dirty.set(false);
                buckets.clear();
                loaded.set(false);
                cachedZone = null;
                cachedToday = null;
            }
        }
    }

    /**
     * Queries aggregated PV/UV series, top 10 viewed articles, and top 10 external referer domains
     * across the requested trailing window.
     *
     * @param requestedDays window length in days (clamped to {@code 1..90}, defaults to {@code 7} if {@code <= 0})
     * @return trend statistics JSON payload
     */
    public JSONObject queryTrends(final int requestedDays) {
        ensureLoaded();
        final int days = requestedDays <= 0 ? 7 : Math.min(requestedDays, MAX_RETENTION_DAYS);
        final LocalDate today = currentDate();

        final JSONArray labels = new JSONArray();
        final JSONArray dates = new JSONArray();
        final JSONArray pvSeries = new JSONArray();
        final JSONArray uvSeries = new JSONArray();

        long totalPv = 0L;
        long totalUv = 0L;
        final Map<String, Long> articleTotals = new HashMap<>();
        final Map<String, Long> refererTotals = new HashMap<>();

        for (int i = days - 1; i >= 0; i--) {
            final LocalDate date = today.minusDays(i);
            final String key = date.format(YYYY_MM_DD);
            dates.put(key);
            labels.put(date.format(MM_DD));

            final DayBucket bucket = buckets.get(key);
            final long dayPv = bucket == null ? 0L : bucket.pv.sum();
            final long dayUv = bucket == null ? 0L : bucket.uv.sum();
            pvSeries.put(dayPv);
            uvSeries.put(dayUv);
            totalPv += dayPv;
            totalUv += dayUv;

            if (bucket != null) {
                for (final Map.Entry<String, LongAdder> entry : bucket.articleViews.entrySet()) {
                    final long cnt = entry.getValue().sum();
                    if (cnt > 0L) {
                        articleTotals.merge(entry.getKey(), cnt, Long::sum);
                    }
                }
                for (final Map.Entry<String, LongAdder> entry : bucket.refererDomains.entrySet()) {
                    final long cnt = entry.getValue().sum();
                    if (cnt > 0L) {
                        refererTotals.merge(entry.getKey(), cnt, Long::sum);
                    }
                }
            }
        }

        final List<Map.Entry<String, Long>> sortedArticles = new ArrayList<>(articleTotals.entrySet());
        sortedArticles.sort(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey));

        final PreloadedArticles preloaded = preloadTopArticles(sortedArticles);
        final JSONArray topArticles = new JSONArray();
        for (final Map.Entry<String, Long> entry : sortedArticles) {
            if (topArticles.length() >= 10) {
                break;
            }
            final String articleId = entry.getKey();
            final long count = entry.getValue();
            final JSONObject article;
            if (preloaded != null && preloaded.queriedIds.contains(articleId)) {
                final JSONObject row = preloaded.byId.get(articleId);
                article = (row != null
                        && row.optInt(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED)
                        == Article.ARTICLE_STATUS_C_PUBLISHED)
                        ? row
                        : null;
            } else {
                article = resolvePublishedArticle(articleId);
            }
            if (article == null) {
                continue;
            }
            topArticles.put(new JSONObject()
                    .put("articleId", articleId)
                    .put("oId", articleId)
                    .put("title", article.optString(Article.ARTICLE_TITLE))
                    .put("permalink", article.optString(Article.ARTICLE_PERMALINK))
                    .put("count", count)
                    .put("views", count));
        }

        final List<Map.Entry<String, Long>> sortedReferers = new ArrayList<>(refererTotals.entrySet());
        sortedReferers.sort(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey));

        final JSONArray topReferers = new JSONArray();
        final int refererLimit = Math.min(10, sortedReferers.size());
        for (int i = 0; i < refererLimit; i++) {
            final Map.Entry<String, Long> entry = sortedReferers.get(i);
            topReferers.put(new JSONObject()
                    .put("domain", entry.getKey())
                    .put("count", entry.getValue()));
        }

        return new JSONObject()
                .put("days", days)
                .put("dates", dates)
                .put("labels", labels)
                .put("pvSeries", pvSeries)
                .put("uvSeries", uvSeries)
                .put("totalPv", totalPv)
                .put("totalUv", totalUv)
                .put("fallbackTotalTop", false)
                .put("topArticles", topArticles)
                .put("topReferers", topReferers)
                .put("refererDomains", topReferers);
    }

    private PreloadedArticles preloadTopArticles(final List<Map.Entry<String, Long>> sortedArticles) {
        if (articleRepository == null || sortedArticles.isEmpty()
                || Latkes.RuntimeDatabase.NONE == Latkes.getRuntimeDatabase()) {
            return null;
        }
        try {
            final int limit = Math.min(sortedArticles.size(), BATCH_ARTICLE_PRELOAD_LIMIT);
            final Set<String> candidateIds = new LinkedHashSet<>(limit * 2);
            for (int i = 0; i < limit; i++) {
                final String id = sortedArticles.get(i).getKey();
                if (StringUtils.isNotBlank(id)) {
                    candidateIds.add(id);
                }
            }
            if (candidateIds.isEmpty()) {
                return null;
            }
            final String placeholders = String.join(",", Collections.nCopies(candidateIds.size(), "?"));
            final List<JSONObject> rows = articleRepository.select(
                    "SELECT oId, articleStatus, articleTitle, articlePermalink FROM "
                            + articleRepository.getName()
                            + " WHERE oId IN (" + placeholders + ")",
                    candidateIds.toArray()
            );
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            final Map<String, JSONObject> byId = new HashMap<>(rows.size() * 2);
            for (final JSONObject row : rows) {
                if (row != null) {
                    final String id = row.optString(Keys.OBJECT_ID);
                    if (!id.isEmpty()) {
                        byId.put(id, row);
                    }
                }
            }
            return new PreloadedArticles(candidateIds, byId);
        } catch (final Exception e) {
            LOGGER.log(Level.TRACE, "Batch preload of top trend articles fell back to per-article lookup", e);
            return null;
        }
    }

    private JSONObject resolvePublishedArticle(final String articleId) {
        if (articleRepository == null || StringUtils.isBlank(articleId)) {
            return null;
        }
        try {
            final JSONObject article = articleRepository.get(articleId);
            if (article == null) {
                return null;
            }
            if (article.optInt(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED)
                    != Article.ARTICLE_STATUS_C_PUBLISHED) {
                return null;
            }
            return article;
        } catch (final Exception e) {
            LOGGER.log(Level.TRACE, "Resolves article [" + articleId + "] for trends failed", e);
            return null;
        }
    }

    private void ensureLoaded() {
        if (loaded.get()) {
            return;
        }
        synchronized (loadLock) {
            if (loaded.get()) {
                return;
            }
            if (optionQueryService != null) {
                try {
                    final String rawJson = OptionDocuments.read(
                            optionQueryService,
                            Option.ID_C_VOLO_DAILY_STATS,
                            "{\"days\":{}}"
                    );
                    loadFromJson(rawJson, currentDate());
                } catch (final Exception e) {
                    LOGGER.log(Level.WARN, "Loads daily analytics trends failed, starting with clean in-memory state", e);
                }
            }
            loaded.set(true);
        }
    }

    private void loadFromJson(final String rawJson, final LocalDate today) {
        if (StringUtils.isBlank(rawJson)) {
            return;
        }
        final JSONObject root = new JSONObject(rawJson);
        final JSONObject daysObj = root.optJSONObject("days");
        if (daysObj == null) {
            return;
        }
        final LocalDate minDate = today.minusDays(MAX_RETENTION_DAYS - 1L);
        final LocalDate maxDate = today.plusDays(1L);
        for (final String dateKey : daysObj.keySet()) {
            final LocalDate parsed = parseDateQuietly(dateKey);
            if (parsed == null || parsed.isBefore(minDate) || parsed.isAfter(maxDate)) {
                continue;
            }
            final JSONObject item = daysObj.optJSONObject(dateKey);
            if (item == null) {
                continue;
            }
            final DayBucket bucket = buckets.computeIfAbsent(dateKey, k -> new DayBucket());
            final long pv = Math.max(0L, item.optLong("pv", 0L));
            final long uv = Math.max(0L, item.optLong("uv", 0L));
            if (pv > 0L) {
                bucket.pv.add(pv);
            }
            if (uv > 0L) {
                bucket.uv.add(uv);
            }
            final JSONObject articlesObj = item.optJSONObject("articles");
            if (articlesObj != null) {
                for (final String articleId : articlesObj.keySet()) {
                    final long count = Math.max(0L, articlesObj.optLong(articleId, 0L));
                    if (count > 0L && articleId.length() <= 64) {
                        incrementBounded(bucket.articleViews, articleId, MAX_ARTICLES_PER_DAY, count);
                    }
                }
            }
            final JSONObject referersObj = item.optJSONObject("referers");
            if (referersObj != null) {
                for (final String domain : referersObj.keySet()) {
                    final long count = Math.max(0L, referersObj.optLong(domain, 0L));
                    if (count > 0L && domain.length() <= 253) {
                        incrementBounded(bucket.refererDomains, domain, MAX_REFERERS_PER_DAY, count);
                    }
                }
            }
        }
    }

    private JSONObject serializeBuckets(final long nowMillis) {
        final JSONObject daysJson = new JSONObject();
        final List<String> keys = new ArrayList<>(buckets.keySet());
        keys.sort(Comparator.naturalOrder());
        for (final String dateKey : keys) {
            final DayBucket bucket = buckets.get(dateKey);
            if (bucket == null) {
                continue;
            }
            final JSONObject articlesJson = new JSONObject();
            for (final Map.Entry<String, LongAdder> entry : bucket.articleViews.entrySet()) {
                final long cnt = entry.getValue().sum();
                if (cnt > 0L) {
                    articlesJson.put(entry.getKey(), cnt);
                }
            }
            final JSONObject referersJson = new JSONObject();
            for (final Map.Entry<String, LongAdder> entry : bucket.refererDomains.entrySet()) {
                final long cnt = entry.getValue().sum();
                if (cnt > 0L) {
                    referersJson.put(entry.getKey(), cnt);
                }
            }
            daysJson.put(dateKey, new JSONObject()
                    .put("pv", bucket.pv.sum())
                    .put("uv", bucket.uv.sum())
                    .put("articles", articlesJson)
                    .put("referers", referersJson));
        }
        return new JSONObject()
                .put("version", 1)
                .put("updatedAt", nowMillis)
                .put("days", daysJson);
    }

    private DayBucket getOrCreateBucket(final LocalDate today, final String key) {
        DayBucket existing = buckets.get(key);
        if (existing != null) {
            return existing;
        }
        existing = buckets.computeIfAbsent(key, k -> new DayBucket());
        if (buckets.size() > MAX_RETENTION_DAYS) {
            pruneOldBuckets(today);
        }
        return existing;
    }

    private void pruneOldBuckets(final LocalDate today) {
        final LocalDate minDate = today.minusDays(MAX_RETENTION_DAYS - 1L);
        final LocalDate maxDate = today.plusDays(1L);
        final Iterator<String> iterator = buckets.keySet().iterator();
        while (iterator.hasNext()) {
            final String key = iterator.next();
            final LocalDate parsed = parseDateQuietly(key);
            if (parsed == null || parsed.isBefore(minDate) || parsed.isAfter(maxDate)) {
                iterator.remove();
            }
        }
        while (buckets.size() > MAX_RETENTION_DAYS) {
            String oldestKey = null;
            for (final String key : buckets.keySet()) {
                if (oldestKey == null || key.compareTo(oldestKey) < 0) {
                    oldestKey = key;
                }
            }
            if (oldestKey == null) {
                break;
            }
            buckets.remove(oldestKey);
        }
    }

    private CachedToday resolveToday() {
        final long millis = clock.getAsLong();
        final long epochSecond = Math.floorDiv(millis, 1000L);
        final ZoneId zone = resolveZoneId();
        final CachedToday ct = this.cachedToday;
        if (ct != null && ct.epochSecond == epochSecond && ct.zoneId.equals(zone)) {
            return ct;
        }
        final LocalDate date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate();
        final CachedToday updated = new CachedToday(epochSecond, zone, date, date.format(YYYY_MM_DD));
        this.cachedToday = updated;
        return updated;
    }

    private LocalDate currentDate() {
        return resolveToday().date;
    }

    private ZoneId resolveZoneId() {
        if (optionQueryService != null) {
            try {
                final JSONObject tzOpt = optionQueryService.getOptionById(Option.ID_C_TIME_ZONE_ID);
                if (tzOpt != null) {
                    final String rawVal = tzOpt.optString(Option.OPTION_VALUE);
                    final CachedZone cz = this.cachedZone;
                    if (cz != null && rawVal.equals(cz.rawTzId)) {
                        return cz.zoneId;
                    }
                    final String tzId = rawVal.trim();
                    if (!tzId.isEmpty()) {
                        final ZoneId parsed = ZoneId.of(tzId);
                        this.cachedZone = new CachedZone(rawVal, parsed);
                        return parsed;
                    }
                }
            } catch (final Exception ignored) {
            }
        }
        return ZoneId.systemDefault();
    }

    private static LocalDate parseDateQuietly(final String text) {
        if (text == null || text.length() != 10) {
            return null;
        }
        try {
            return LocalDate.parse(text, YYYY_MM_DD);
        } catch (final DateTimeParseException ignored) {
            return null;
        }
    }

    private static void incrementBounded(final ConcurrentHashMap<String, LongAdder> map,
                                         final String key,
                                         final int maxDistinct,
                                         final long delta) {
        if (key == null || key.isEmpty() || delta <= 0L) {
            return;
        }
        LongAdder adder = map.get(key);
        if (adder != null) {
            adder.add(delta);
            return;
        }
        if (map.size() >= maxDistinct) {
            return;
        }
        adder = map.computeIfAbsent(key, k -> map.size() < maxDistinct ? new LongAdder() : null);
        if (adder != null) {
            adder.add(delta);
        }
    }

    private static String extractRefererHeader(final RequestContext context) {
        if (context == null) {
            return null;
        }
        try {
            final String direct = context.header("Referer");
            if (StringUtils.isNotBlank(direct)) {
                return direct;
            }
        } catch (final Exception ignored) {
        }
        final Request request = context.getRequest();
        return request == null ? null : request.getHeader("Referer");
    }

    private static String safeServePath() {
        try {
            return Latkes.getServePath();
        } catch (final Exception ignored) {
            return "";
        }
    }

    private static String resolveSelfHost(final String selfServePath) {
        if (StringUtils.isBlank(selfServePath)) {
            return null;
        }
        final CachedSelfHost cached = cachedSelfHost;
        if (cached != null && selfServePath.equals(cached.rawServePath)) {
            return cached.normalizedHost;
        }
        String normalized = null;
        try {
            normalized = normalizeDomainHost(URI.create(selfServePath.trim()).getHost());
        } catch (final Exception ignored) {
        }
        cachedSelfHost = new CachedSelfHost(selfServePath, normalized);
        return normalized;
    }

    /**
     * Extracts a normalized external domain name from an HTTP {@code Referer} header,
     * returning {@code null} for blank, malformed, non-HTTP(S), IP-literal, single-label, or same-site referers.
     */
    public static String extractExternalRefererDomain(final String refererHeader, final String selfServePath) {
        if (StringUtils.isBlank(refererHeader) || refererHeader.length() > 2048) {
            return null;
        }
        final URI uri;
        try {
            uri = new URI(refererHeader.trim());
        } catch (final Exception ignored) {
            return null;
        }
        final String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            return null;
        }
        final String host = normalizeDomainHost(uri.getHost());
        if (host == null) {
            return null;
        }
        final String selfHost = resolveSelfHost(selfServePath);
        if (host.equals(selfHost)) {
            return null;
        }
        return host;
    }

    private static String normalizeDomainHost(final String rawHost) {
        if (StringUtils.isBlank(rawHost)) {
            return null;
        }
        String host = rawHost.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            return null;
        }
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        while (host.startsWith("www.")) {
            host = host.substring(4);
        }
        if (host.isEmpty() || host.length() > 253 || host.indexOf('.') <= 0 || host.endsWith(".")) {
            return null;
        }
        if (host.indexOf(':') >= 0 || NetUtil.isValidIpV4Address(host) || NetUtil.isValidIpV6Address(host)) {
            return null;
        }
        boolean hasAlpha = false;
        for (int i = 0; i < host.length(); i++) {
            final char c = host.charAt(i);
            if (c >= 'a' && c <= 'z') {
                hasAlpha = true;
            } else if (!((c >= '0' && c <= '9') || c == '.' || c == '-')) {
                return null;
            }
        }
        return hasAlpha ? host : null;
    }
}
