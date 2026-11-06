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

import org.b3log.latke.Keys;
import org.b3log.latke.ioc.Inject;
import org.apache.logging.log4j.Level;
import org.b3log.solo.cache.PublicViewCache;
import org.b3log.solo.logging.Logger;
import org.b3log.latke.repository.RepositoryException;
import org.b3log.latke.repository.Transaction;
import org.b3log.latke.service.LangPropsService;
import org.b3log.latke.service.ServiceException;
import org.b3log.latke.service.annotation.Service;
import org.b3log.solo.model.Page;
import org.b3log.solo.repository.CommentRepository;
import org.b3log.solo.repository.PageRepository;
import org.b3log.solo.util.OutboundUrls;
import org.b3log.solo.util.Solos;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Page management service.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @author <a href="http://vanessa.b3log.org">Vanessa</a>
 * @since 0.4.0
 */
@Service
public class PageMgmtService {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(PageMgmtService.class);

    /**
     * Page repository.
     */
    @Inject
    private PageRepository pageRepository;

    /**
     * Comment repository.
     */
    @Inject
    private CommentRepository commentRepository;

    /**
     * User query service.
     */
    @Inject
    private UserQueryService userQueryService;

    /**
     * Language service.
     */
    @Inject
    private LangPropsService langPropsService;

    /**
     * Permalink query service.
     */
    @Inject
    private PermalinkQueryService permalinkQueryService;

    /**
     * Statistic management service.
     */
    @Inject
    private StatisticMgmtService statisticMgmtService;

    /**
     * Statistic query service.
     */
    @Inject
    private StatisticQueryService statisticQueryService;

    /**
     * Option query service.
     */
    @Inject
    private OptionQueryService optionQueryService;

    /**
     * Option management service.
     */
    @Inject
    private OptionMgmtService optionMgmtService;

    @Inject
    private SyndicationCacheService syndicationCacheService;

    @Inject
    private PublicViewCache publicViewCache;

    private void invalidateSyndicationAndPublicCaches() {
        org.b3log.solo.processor.ErrorProcessor.clearCache();
        if (null != syndicationCacheService) {
            syndicationCacheService.invalidate();
        }
        if (null != publicViewCache) {
            publicViewCache.invalidateCommon();
        }
    }

    private static void rollbackQuietly(final Transaction transaction) {
        if (null != transaction && transaction.isActive()) {
            try {
                transaction.rollback();
            } catch (final Exception rollbackErr) {
                LOGGER.log(Level.WARN, "Rolls back page transaction failed", rollbackErr);
            }
        }
    }

    /**
     * Checks whether a page permalink is a safe root-relative path or an {@code http(s)} URL,
     * rejecting empty strings, control characters, path traversal, and dangerous URI schemes.
     */
    public static boolean isSafePagePermalink(final String raw) {
        if (null == raw) {
            return false;
        }
        final String trimmed = raw.strip();
        if (trimmed.isEmpty() || trimmed.length() > 255) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            final char c = trimmed.charAt(i);
            if (c <= 0x20 || c == 0x7f || c == '\\' || c == '<' || c == '>' || c == '"') {
                return false;
            }
        }
        final String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("javascript:")
                || lower.startsWith("data:")
                || lower.startsWith("vbscript:")
                || lower.startsWith("file:")
                || lower.startsWith("blob:")) {
            return false;
        }
        if (trimmed.startsWith("/")) {
            return !trimmed.startsWith("//") && !trimmed.contains("..");
        }
        return OutboundUrls.isHttpOrHttps(trimmed);
    }

    private static void validateAndNormalizePage(final JSONObject page) throws ServiceException {
        if (null == page) {
            throw new ServiceException("页面数据不能为空。");
        }
        final String title = page.optString(Page.PAGE_TITLE).trim();
        if (title.isEmpty() || title.length() > 255) {
            throw new ServiceException("请填写不超过 255 个字符的页面标题。");
        }
        final String permalink = page.optString(Page.PAGE_PERMALINK).trim();
        if (!isSafePagePermalink(permalink)) {
            throw new ServiceException("页面链接格式无效，仅支持以 / 开头的站内路径或 HTTP(S) 链接。");
        }
        final String icon = page.optString(Page.PAGE_ICON).trim();
        if (icon.length() > 255 || !OutboundUrls.isOptionalSafeIconOrAvatar(icon)) {
            throw new ServiceException("页面图标地址无效，仅支持以 / 开头的站内路径或 HTTP(S) 链接。");
        }
        final String openTarget = page.optString(Page.PAGE_OPEN_TARGET, "_self").trim();
        if (openTarget.length() > 32) {
            throw new ServiceException("页面打开方式参数无效。");
        }
        page.put(Page.PAGE_TITLE, title);
        page.put(Page.PAGE_PERMALINK, permalink);
        page.put(Page.PAGE_ICON, icon);
        page.put(Page.PAGE_OPEN_TARGET, openTarget.isEmpty() ? "_self" : openTarget);
    }

    /**
     * Updates a page by the specified request json object.
     *
     * @param requestJSONObject the specified request json object
     * @throws ServiceException service exception
     */
    public void updatePage(final JSONObject requestJSONObject) throws ServiceException {
        if (null == requestJSONObject) {
            throw new ServiceException("请求数据不能为空。");
        }
        final JSONObject page = requestJSONObject.optJSONObject(Page.PAGE);
        validateAndNormalizePage(page);
        final String pageId = page.optString(Keys.OBJECT_ID).trim();
        if (pageId.isEmpty()) {
            throw new ServiceException("页面编号不能为空。");
        }

        final Transaction transaction = pageRepository.beginTransaction();
        try {
            final JSONObject oldPage = pageRepository.get(pageId);
            if (null == oldPage) {
                throw new ServiceException("页面不存在。");
            }
            final JSONObject newPage = Solos.clone(page);
            newPage.put(Keys.OBJECT_ID, pageId);
            newPage.put(Page.PAGE_ORDER, oldPage.optInt(Page.PAGE_ORDER));

            pageRepository.update(pageId, newPage);
            transaction.commit();
            invalidateSyndicationAndPublicCaches();

            LOGGER.log(Level.DEBUG, "Updated a page[id={0}]", pageId);
        } catch (final ServiceException e) {
            rollbackQuietly(transaction);
            throw e;
        } catch (final Exception e) {
            rollbackQuietly(transaction);
            LOGGER.log(Level.ERROR, e.getMessage(), e);

            throw new ServiceException(e);
        } catch (final Error err) {
            rollbackQuietly(transaction);
            throw err;
        }
    }

    /**
     * Removes a page specified by the given page id.
     *
     * @param pageId the given page id
     * @throws ServiceException service exception
     */
    public void removePage(final String pageId) throws ServiceException {
        if (null == pageId || pageId.isBlank()) {
            throw new ServiceException("页面编号不能为空。");
        }
        final Transaction transaction = pageRepository.beginTransaction();
        try {
            pageRepository.remove(pageId);
            if (null != commentRepository) {
                commentRepository.removeComments(pageId);
            }
            transaction.commit();
            invalidateSyndicationAndPublicCaches();
        } catch (final Exception e) {
            rollbackQuietly(transaction);
            if (e instanceof ServiceException se) {
                throw se;
            }

            LOGGER.log(Level.ERROR, "Removes a page[id=" + pageId + "] failed", e);

            throw new ServiceException(e);
        } catch (final Error err) {
            rollbackQuietly(transaction);
            throw err;
        }
    }

    /**
     * Adds a page with the specified request json object.
     *
     * @param requestJSONObject the specified request json object
     * @return generated page id
     * @throws ServiceException if permalink format checks failed or persists failed
     */
    public String addPage(final JSONObject requestJSONObject) throws ServiceException {
        if (null == requestJSONObject) {
            throw new ServiceException("请求数据不能为空。");
        }
        final JSONObject page = requestJSONObject.optJSONObject(Page.PAGE);
        validateAndNormalizePage(page);

        final Transaction transaction = pageRepository.beginTransaction();
        try {
            final int maxOrder = pageRepository.getMaxOrder();
            page.put(Page.PAGE_ORDER, maxOrder + 1);

            final String ret = pageRepository.add(page);
            transaction.commit();
            invalidateSyndicationAndPublicCaches();

            return ret;
        } catch (final Exception e) {
            rollbackQuietly(transaction);
            if (e instanceof ServiceException se) {
                throw se;
            }
            LOGGER.log(Level.ERROR, e.getMessage(), e);

            throw new ServiceException(e);
        } catch (final Error err) {
            rollbackQuietly(transaction);
            throw err;
        }
    }

    /**
     * Changes the order of a page specified by the given page id with the specified direction.
     *
     * @param pageId    the given page id
     * @param direction the specified direction, "up"/"down"
     * @throws ServiceException service exception
     */
    public void changeOrder(final String pageId, final String direction) throws ServiceException {
        if (null == pageId || pageId.isBlank()) {
            throw new ServiceException("页面编号不能为空。");
        }
        if (!"up".equals(direction) && !"down".equals(direction)) {
            throw new ServiceException("排序方向不正确。");
        }
        final Transaction transaction = pageRepository.beginTransaction();
        try {
            final JSONObject srcPage = pageRepository.get(pageId);
            if (null == srcPage) {
                throw new ServiceException("页面不存在。");
            }
            final int srcPageOrder = srcPage.optInt(Page.PAGE_ORDER);

            final JSONObject targetPage;
            if ("up".equals(direction)) {
                targetPage = pageRepository.getUpper(pageId);
            } else { // Down
                targetPage = pageRepository.getUnder(pageId);
            }

            if (null == targetPage) {
                rollbackQuietly(transaction);

                LOGGER.log(Level.WARN, "Cant not find the target page of source page[order={0}]", srcPageOrder);
                return;
            }

            // Swaps
            srcPage.put(Page.PAGE_ORDER, targetPage.optInt(Page.PAGE_ORDER));
            targetPage.put(Page.PAGE_ORDER, srcPageOrder);
            pageRepository.update(srcPage.getString(Keys.OBJECT_ID), srcPage, Page.PAGE_ORDER);
            pageRepository.update(targetPage.getString(Keys.OBJECT_ID), targetPage, Page.PAGE_ORDER);
            transaction.commit();
            invalidateSyndicationAndPublicCaches();
        } catch (final ServiceException e) {
            rollbackQuietly(transaction);
            throw e;
        } catch (final Exception e) {
            rollbackQuietly(transaction);

            LOGGER.log(Level.ERROR, "Changes page's order failed", e);

            throw new ServiceException(e);
        } catch (final Error err) {
            rollbackQuietly(transaction);
            throw err;
        }
    }
}
