/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.service;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;
import org.b3log.latke.Keys;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.repository.Query;
import org.b3log.latke.repository.Transaction;
import org.b3log.latke.service.annotation.Service;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.model.Link;
import org.b3log.solo.model.Option;
import org.b3log.solo.repository.LinkRepository;
import org.b3log.solo.repository.OptionRepository;
import org.b3log.solo.util.OutboundUrls;
import org.b3log.solo.util.QueryResults;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded-concurrency SSRF-safe broken link health checker for friend links.
 *
 * @author <a href="https://github.com/Willh92">Willh92</a>
 * @since 3.0.0
 */
@Service
public class LinkHealthService {

    private static final Logger LOGGER = Logger.getLogger(LinkHealthService.class);
    private static final String USER_AGENT = "Volo-LinkChecker/3.0 (+https://github.com/Willh92/Volo-solo)";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final int MAX_OPTION_CHARS = 9800;

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger(1);
    private static final ExecutorService PROBE_POOL = Executors.newFixedThreadPool(4, runnable -> {
        final Thread thread = new Thread(runnable, "volo-link-probe-" + THREAD_SEQ.getAndIncrement());
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    private LinkRepository linkRepository;

    @Inject
    private OptionRepository optionRepository;

    /**
     * Reads cached link health map keyed by link {@code oId}.
     */
    public JSONObject getHealthMap() {
        try {
            final JSONObject opt = optionRepository.get(Option.ID_C_VOLO_LINK_HEALTH);
            if (opt == null) {
                return new JSONObject();
            }
            final String raw = opt.optString(Option.OPTION_VALUE, "{}");
            return StringUtils.isBlank(raw) ? new JSONObject() : new JSONObject(raw);
        } catch (final Exception e) {
            LOGGER.log(Level.WARN, "Failed to read cached link health map", e);
            return new JSONObject();
        }
    }

    /**
     * Attaches cached health metadata onto a link object if available.
     */
    public void attachHealth(final JSONObject link, final JSONObject healthMap) {
        if (link == null || healthMap == null) {
            return;
        }
        final String id = link.optString(Keys.OBJECT_ID);
        final JSONObject entry = healthMap.optJSONObject(id);
        if (entry != null) {
            link.put("linkHealth", entry);
        }
    }

    /**
     * Probes all friend links concurrently via SSRF-safe {@link OutboundUrls#probeUrl} and persists the summary.
     */
    public synchronized JSONObject checkAllLinks() {
        final long checkedAt = System.currentTimeMillis();
        final JSONObject healthMap = new JSONObject();
        int total = 0;
        int healthy = 0;
        int unhealthy = 0;

        try {
            final JSONArray links = QueryResults.array(linkRepository.get(new Query()));
            total = links.length();
            final List<Callable< linkProbeOutcome >> tasks = new ArrayList<>(total);
            for (int i = 0; i < total; i++) {
                final JSONObject link = links.optJSONObject(i);
                if (link == null) {
                    continue;
                }
                final String id = link.optString(Keys.OBJECT_ID);
                final String url = StringUtils.trimToEmpty(link.optString(Link.LINK_ADDRESS));
                tasks.add(() -> {
                    final OutboundUrls.ProbeResult res = OutboundUrls.probeUrl(
                            url, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, USER_AGENT, false);
                    final JSONObject item = new JSONObject()
                            .put("ok", res.ok())
                            .put("status", res.status())
                            .put("latencyMs", res.latencyMs())
                            .put("checkedAt", checkedAt);
                    if (StringUtils.isNotBlank(res.error())) {
                        item.put("error", StringUtils.abbreviate(res.error(), 60));
                    }
                    return new linkProbeOutcome(id, res.ok(), item);
                });
            }

            final List<Future<linkProbeOutcome>> futures = PROBE_POOL.invokeAll(tasks, 45L, TimeUnit.SECONDS);
            for (int i = 0; i < futures.size(); i++) {
                final Future<linkProbeOutcome> future = futures.get(i);
                if (future.isCancelled()) {
                    unhealthy++;
                    continue;
                }
                try {
                    final linkProbeOutcome out = future.get();
                    if (out.ok()) {
                        healthy++;
                    } else {
                        unhealthy++;
                    }
                    if (healthMap.toString().length() < MAX_OPTION_CHARS) {
                        healthMap.put(out.id(), out.entry());
                    }
                } catch (final Exception e) {
                    unhealthy++;
                }
            }

            saveHealthMap(healthMap);
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Broken link health check failed", e);
        }

        return new JSONObject()
                .put("total", total)
                .put("healthy", healthy)
                .put("unhealthy", unhealthy)
                .put("checkedAt", checkedAt)
                .put("healthMap", healthMap);
    }

    private void saveHealthMap(final JSONObject healthMap) {
        final Transaction tx = optionRepository.beginTransaction();
        try {
            JSONObject opt = optionRepository.get(Option.ID_C_VOLO_LINK_HEALTH);
            if (opt == null) {
                opt = new JSONObject();
                opt.put(Keys.OBJECT_ID, Option.ID_C_VOLO_LINK_HEALTH);
                opt.put(Option.OPTION_CATEGORY, "linkHealth");
                opt.put(Option.OPTION_VALUE, healthMap.toString());
                optionRepository.add(opt);
            } else {
                opt.put(Option.OPTION_VALUE, healthMap.toString());
                optionRepository.update(Option.ID_C_VOLO_LINK_HEALTH, opt);
            }
            tx.commit();
        } catch (final Exception e) {
            if (tx.isActive()) {
                tx.rollback();
            }
            LOGGER.log(Level.WARN, "Failed to persist link health map", e);
        }
    }

    private record linkProbeOutcome(String id, boolean ok, JSONObject entry) { }
}
