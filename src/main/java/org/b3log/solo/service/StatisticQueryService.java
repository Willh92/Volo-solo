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
import org.b3log.latke.repository.FilterOperator;
import org.b3log.latke.repository.PropertyFilter;
import org.b3log.latke.repository.Query;
import org.b3log.latke.service.annotation.Service;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Option;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.repository.CommentRepository;
import org.b3log.solo.util.QueryResults;
import org.b3log.solo.util.Solos;
import org.json.JSONObject;

/**
 * Statistic query service.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 0.5.0
 */
@Service
public class StatisticQueryService {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(StatisticQueryService.class);

    /**
     * Option query service.
     */
    @Inject
    private OptionQueryService optionQueryService;

    /**
     * Article repository.
     */
    @Inject
    private ArticleRepository articleRepository;

    /**
     * Comment repository.
     */
    @Inject
    private CommentRepository commentRepository;

    @Inject
    private org.b3log.solo.cache.StatisticCache statisticCache;

    @Inject
    private StatisticMgmtService statisticMgmtService;

    /**
     * Gets the online visitor count.
     *
     * @return online visitor count
     */
    public static int getOnlineVisitorCount() {
        return StatisticMgmtService.ONLINE_VISITORS.size();
    }

    /**
     * Gets the real-time blog view count including any un-flushed write-behind delta.
     *
     * @return blog view count
     */
    public long getBlogViewCount() {
        final long pending = null == statisticMgmtService ? 0L : statisticMgmtService.getPendingBlogViewDelta();
        if (null == optionQueryService) {
            return pending;
        }
        try {
            final JSONObject opt = optionQueryService.getOptionById(Option.ID_C_STATISTIC_BLOG_VIEW_COUNT);
            final long base = null == opt ? 0L : Math.max(0L, opt.optLong(Option.OPTION_VALUE, 0L));
            return base + pending;
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Gets blog view count failed", e);
            return pending;
        }
    }

    /**
     * Counts approved, non-deleted comments belonging to published articles only.
     *
     * @return published blog comment count
     * @throws org.b3log.latke.repository.RepositoryException repository exception
     */
    public long getPublishedBlogCommentCount() throws org.b3log.latke.repository.RepositoryException {
        if (null == commentRepository) {
            return 0L;
        }
        if (org.b3log.latke.Latkes.RuntimeDatabase.NONE != org.b3log.latke.Latkes.getRuntimeDatabase()) {
            final java.util.List<JSONObject> rows;
            if (null != articleRepository) {
                final String sql = "SELECT COUNT(*) AS C FROM " + commentRepository.getName() + " AS c"
                        + " INNER JOIN " + articleRepository.getName() + " AS a ON c.commentOnId = a.oId"
                        + " WHERE a.articleStatus = ? AND c.commentStatus = ? AND (c.commentDeleted IS NULL OR c.commentDeleted = ?)";
                rows = commentRepository.select(sql,
                        Article.ARTICLE_STATUS_C_PUBLISHED,
                        org.b3log.solo.model.Comment.COMMENT_STATUS_C_APPROVED,
                        false);
            } else {
                final String sql = "SELECT COUNT(*) AS C FROM " + commentRepository.getName() + " AS c"
                        + " WHERE c.commentStatus = ? AND (c.commentDeleted IS NULL OR c.commentDeleted = ?)";
                rows = commentRepository.select(sql,
                        org.b3log.solo.model.Comment.COMMENT_STATUS_C_APPROVED,
                        false);
            }
            if (null == rows || rows.isEmpty()) {
                return 0L;
            }
            return extractCountAlias(rows.get(0));
        }
        return commentRepository.count(new Query().setFilter(
                org.b3log.latke.repository.CompositeFilterOperator.and(
                        new PropertyFilter("commentDeleted", FilterOperator.EQUAL, false),
                        new PropertyFilter(org.b3log.solo.model.Comment.COMMENT_STATUS, FilterOperator.EQUAL,
                                org.b3log.solo.model.Comment.COMMENT_STATUS_C_APPROVED))));
    }

    private static long extractCountAlias(final JSONObject row) {
        if (null == row) {
            return 0L;
        }
        final long direct = QueryResults.optLongIgnoreCase(row, "C", -1L);
        if (direct >= 0L) {
            return direct;
        }
        for (final String key : row.keySet()) {
            if ("c".equalsIgnoreCase(key) || key.toLowerCase(java.util.Locale.ROOT).contains("count")) {
                return Math.max(0L, row.optLong(key, 0L));
            }
        }
        return 0L;
    }

    /**
     * Gets the statistic.
     *
     * @return statistic, returns {@code null} if not found
     */
    public JSONObject getStatistic() {
        try {
            final JSONObject ret = optionQueryService.getOptions(Option.CATEGORY_C_STATISTIC);
            if (null == ret) {
                return null;
            }
            final long pendingDelta = null == statisticMgmtService ? 0L : statisticMgmtService.getPendingBlogViewDelta();
            if (pendingDelta > 0L) {
                final long baseViewCount = Math.max(0L, ret.optLong(Option.ID_C_STATISTIC_BLOG_VIEW_COUNT, 0L));
                ret.put(Option.ID_C_STATISTIC_BLOG_VIEW_COUNT, String.valueOf(baseViewCount + pendingDelta));
            }
            org.b3log.solo.cache.StatisticCache.Counts counts = null == statisticCache ? null : statisticCache.getCounts();
            if (null == counts) {
                final long publishedArticleCount = articleRepository.count(new Query().setFilter(
                        new PropertyFilter(Article.ARTICLE_STATUS, FilterOperator.EQUAL, Article.ARTICLE_STATUS_C_PUBLISHED)));
                final long commentCount = getPublishedBlogCommentCount();
                counts = new org.b3log.solo.cache.StatisticCache.Counts(publishedArticleCount, commentCount);
                if (null != statisticCache) {
                    statisticCache.putCounts(publishedArticleCount, commentCount);
                }
            }
            ret.put(Option.ID_T_STATISTIC_PUBLISHED_ARTICLE_COUNT, counts.publishedArticleCount());
            ret.put(Option.ID_T_STATISTIC_PUBLISHED_BLOG_COMMENT_COUNT, counts.commentCount());

            return ret;
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Gets statistic failed", e);

            return null;
        }
    }
}
