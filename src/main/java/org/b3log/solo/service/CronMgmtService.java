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

import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.Level;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.repository.jdbc.JdbcRepository;
import org.b3log.latke.service.annotation.Service;
import org.b3log.latke.util.Stopwatchs;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.model.Option;
import org.json.JSONObject;

/**
 * Cron management service.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 2.9.7
 */
@Service
public class CronMgmtService {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(CronMgmtService.class);

    /**
     * Fast local cron scheduler (60s view/trend flushes, scheduled publish, online visitor cleanup).
     */
    private static volatile ScheduledExecutorService SCHEDULED_EXECUTOR_SERVICE = newFastScheduler();

    /**
     * Isolated slow background executor for daily network sync and backup jobs so they never stall 60s local flushes.
     */
    private static volatile ThreadPoolExecutor SLOW_CRON_EXECUTOR = newSlowExecutor();

    private static volatile boolean started;

    /**
     * User query service.
     */
    @Inject
    private UserQueryService userQueryService;

    /**
     * Article management service.
     */
    @Inject
    private ArticleMgmtService articleMgmtService;

    /**
     * Statistic management service.
     */
    @Inject
    private StatisticMgmtService statisticMgmtService;

    /**
     * Option query service.
     */
    @Inject
    private OptionQueryService optionQueryService;

    /**
     * Export service.
     */
    @Inject
    private ExportService exportService;

    /**
     * User management service.
     */
    @Inject
    private UserMgmtService userMgmtService;

    @Inject
    private CommentMgmtService commentMgmtService;

    @Inject
    private FollowService followService;

    @Inject
    private LocalRollingBackupService localRollingBackupService;

    @Inject
    private AnalyticsTrendService analyticsTrendService;

    @Inject
    private PasskeyService passkeyService;

    private static ScheduledExecutorService newFastScheduler() {
        return Executors.newScheduledThreadPool(1, r -> {
            final Thread t = new Thread(r, "volo-cron-fast");
            t.setDaemon(true);
            return t;
        });
    }

    private static ThreadPoolExecutor newSlowExecutor() {
        final ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(16),
                r -> {
                    final Thread t = new Thread(r, "volo-cron-slow");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.DiscardPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static void submitSlowJob(final Runnable job) {
        final ThreadPoolExecutor pool = SLOW_CRON_EXECUTOR;
        if (null == pool || pool.isShutdown()) {
            return;
        }
        pool.execute(() -> {
            try {
                job.run();
            } catch (final Throwable e) {
                LOGGER.log(Level.ERROR, "Executes slow cron failed", e);
            } finally {
                try {
                    JdbcRepository.dispose();
                } finally {
                    Stopwatchs.release();
                }
            }
        });
    }

    /**
     * Start all cron tasks.
     */
    public synchronized void start() {
        if (started) {
            stop();
        }
        if (null == SCHEDULED_EXECUTOR_SERVICE || SCHEDULED_EXECUTOR_SERVICE.isShutdown()) {
            SCHEDULED_EXECUTOR_SERVICE = newFastScheduler();
        }
        if (null == SLOW_CRON_EXECUTOR || SLOW_CRON_EXECUTOR.isShutdown()) {
            SLOW_CRON_EXECUTOR = newSlowExecutor();
        }
        started = true;
        long delay = 2000;

        SCHEDULED_EXECUTOR_SERVICE.scheduleAtFixedRate(() -> {
            try {
                StatisticMgmtService.removeExpiredOnlineVisitor();
            } catch (final Exception e) {
                LOGGER.log(Level.ERROR, "Executes cron failed", e);
            } finally {
                try {
                    JdbcRepository.dispose();
                } finally {
                    Stopwatchs.release();
                }
            }
        }, delay, 1000 * 60 * 10, TimeUnit.MILLISECONDS);
        delay += 2000;

        SCHEDULED_EXECUTOR_SERVICE.scheduleAtFixedRate(() -> {
            try {
                try {
                    if (null != articleMgmtService) {
                        articleMgmtService.publishDueScheduledArticles();
                    }
                } catch (final Exception e) {
                    LOGGER.log(Level.ERROR, "Executes scheduled article publish cron failed", e);
                }
                try {
                    if (null != statisticMgmtService) {
                        statisticMgmtService.flushPendingViewCounts();
                    }
                } catch (final Exception e) {
                    LOGGER.log(Level.ERROR, "Executes blog view count flush cron failed", e);
                }
                try {
                    if (null != articleMgmtService) {
                        articleMgmtService.flushPendingViewCounts();
                    }
                } catch (final Exception e) {
                    LOGGER.log(Level.ERROR, "Executes article view count flush cron failed", e);
                }
                try {
                    if (null != analyticsTrendService) {
                        analyticsTrendService.flushIfDirty();
                    }
                } catch (final Exception e) {
                    LOGGER.log(Level.ERROR, "Executes analytics trends flush cron failed", e);
                }
                try {
                    if (null != passkeyService) {
                        passkeyService.flushIfDirty();
                    }
                } catch (final Exception e) {
                    LOGGER.log(Level.ERROR, "Executes passkey metadata flush cron failed", e);
                }
            } finally {
                try {
                    JdbcRepository.dispose();
                } finally {
                    Stopwatchs.release();
                }
            }
        }, delay, 1000 * 60, TimeUnit.MILLISECONDS);
        delay += 2000;

        SCHEDULED_EXECUTOR_SERVICE.scheduleAtFixedRate(() -> submitSlowJob(this::synchronizeGitHub),
                delay, 1000L * 60 * 60 * 24, TimeUnit.MILLISECONDS);
        delay += 2000;

        SCHEDULED_EXECUTOR_SERVICE.scheduleAtFixedRate(() -> submitSlowJob(() -> {
            if (!org.b3log.solo.util.PluginUtil.fishpiPluginEnabled()) {
                return;
            }
            final String fishKey = userQueryService.getFishKey();
            if (fishKey == null || "".equals(fishKey)) {
                return;
            }
            commentMgmtService.syncAllArticleCommentFromFishPI();
        }), delay, 1000L * 60 * 60 * 24, TimeUnit.MILLISECONDS);
        delay += 2000;

        SCHEDULED_EXECUTOR_SERVICE.scheduleAtFixedRate(() -> submitSlowJob(() -> followService.syncAllFollowArticles()),
                delay, 1000L * 60 * 60 * 24, TimeUnit.MILLISECONDS);
        delay += 2000;

        SCHEDULED_EXECUTOR_SERVICE.scheduleAtFixedRate(() -> submitSlowJob(() -> {
            if (!org.b3log.solo.util.PluginUtil.b3logPluginEnabled()) {
                return;
            }
            exportService.exportHacPai(false);
        }), delay + 1000L * 60 * 10, 1000L * 60 * 60 * 24, TimeUnit.MILLISECONDS);

        SCHEDULED_EXECUTOR_SERVICE.scheduleAtFixedRate(() -> submitSlowJob(() -> {
            if (null != localRollingBackupService) {
                localRollingBackupService.runScheduledBackupIfEnabled();
            }
        }), delay + 1000L * 60 * 15, 1000L * 60 * 60 * 24, TimeUnit.MILLISECONDS);
    }

    /**
     * Read current preferences for each GitHub synchronization run.
     */
    public void synchronizeGitHub() {
        final JSONObject current = optionQueryService.getPreference();
        if (current == null) {
            return;
        }
        final String username = current.optString(Option.ID_C_MY_GITHUB_ID).strip();
        if (current.optBoolean(Option.ID_C_ENABLE_AUTO_FLUSH_GITHUB) && !username.isEmpty()) {
            articleMgmtService.refreshGitHub(username);
        }
        exportService.exportGitHub(current.optBoolean(Option.ID_C_ENABLE_AUTO_FLUSH_BLOG_TO_GITHUB_PROFILE));
    }

    /** Stop all cron tasks. */
    public synchronized void stop() {
        started = false;
        if (null != SCHEDULED_EXECUTOR_SERVICE) {
            SCHEDULED_EXECUTOR_SERVICE.shutdown();
        }
        if (null != SLOW_CRON_EXECUTOR) {
            SLOW_CRON_EXECUTOR.shutdown();
        }
    }

    /**
     * Restart cron tasks.
     */
    public synchronized void restart() {
        stop();
        SCHEDULED_EXECUTOR_SERVICE = newFastScheduler();
        SLOW_CRON_EXECUTOR = newSlowExecutor();
        start();
    }
}
