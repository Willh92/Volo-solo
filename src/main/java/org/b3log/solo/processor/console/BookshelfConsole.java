/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.processor.console;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;
import org.b3log.latke.Keys;
import org.b3log.latke.http.HttpMethod;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.http.renderer.JsonRenderer;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.ioc.Singleton;
import org.b3log.latke.service.ServiceException;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.model.Common;
import org.b3log.solo.service.SeriesAndBookshelfService;
import org.b3log.solo.web.annotation.Before;
import org.b3log.solo.web.annotation.RequestProcessing;
import org.json.JSONObject;

/**
 * Admin console endpoints for ordered article series ({@code /console/series/*})
 * and the embedded bookshelf ({@code /console/books/*}).
 *
 * @author <a href="https://github.com/Willh92">Willh92</a>
 * @since 3.0.0
 */
@Singleton
@Before(ConsoleAdminAuthAdvice.class)
public class BookshelfConsole {

    private static final Logger LOGGER = Logger.getLogger(BookshelfConsole.class);

    @Inject
    private SeriesAndBookshelfService seriesAndBookshelfService;

    @RequestProcessing(value = "/console/series", method = HttpMethod.GET)
    public void listSeries(final RequestContext context) {
        final JSONObject ret = new JSONObject()
                .put(Common.STATUS_CODE, true)
                .put("series", seriesAndBookshelfService.getAllSeries());
        renderJson(context, ret);
    }

    @RequestProcessing(value = "/console/series", method = HttpMethod.POST)
    public void saveSeries(final RequestContext context) {
        try {
            final JSONObject req = context.requestJSON();
            final JSONObject saved = seriesAndBookshelfService.saveSeries(req);
            renderJson(context, new JSONObject()
                    .put(Common.STATUS_CODE, true)
                    .put(Keys.MSG, "系列已保存")
                    .put("series", saved));
        } catch (final ServiceException e) {
            renderJson(context, new JSONObject().put(Common.STATUS_CODE, false).put(Keys.MSG, e.getMessage()));
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Saves series failed", e);
            renderJson(context, new JSONObject().put(Common.STATUS_CODE, false).put(Keys.MSG, "保存系列失败"));
        }
    }

    @RequestProcessing(value = "/console/series/{id}", method = HttpMethod.DELETE)
    public void deleteSeries(final RequestContext context) {
        try {
            seriesAndBookshelfService.deleteSeries(context.pathVar("id"));
            renderJson(context, new JSONObject().put(Common.STATUS_CODE, true).put(Keys.MSG, "系列已移除"));
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Deletes series failed", e);
            renderJson(context, new JSONObject().put(Common.STATUS_CODE, false).put(Keys.MSG, "删除系列失败"));
        }
    }

    @RequestProcessing(value = "/console/books", method = HttpMethod.GET)
    public void listBooks(final RequestContext context) {
        final JSONObject ret = new JSONObject()
                .put(Common.STATUS_CODE, true)
                .put("books", seriesAndBookshelfService.getAllBooks())
                .put("series", seriesAndBookshelfService.getAllSeries());
        renderJson(context, ret);
    }

    @RequestProcessing(value = "/console/books/{id}/toc", method = HttpMethod.GET)
    public void getBookToc(final RequestContext context) {
        final String id = StringUtils.trimToEmpty(context.pathVar("id"));
        renderJson(context, new JSONObject()
                .put(Common.STATUS_CODE, true)
                .put("toc", seriesAndBookshelfService.getBookToc(id)));
    }

    @RequestProcessing(value = "/console/books", method = HttpMethod.POST)
    public void saveBook(final RequestContext context) {
        try {
            final JSONObject req = context.requestJSON();
            final JSONObject saved = seriesAndBookshelfService.saveBook(req);
            renderJson(context, new JSONObject()
                    .put(Common.STATUS_CODE, true)
                    .put(Keys.MSG, "书籍配置已保存")
                    .put("book", saved));
        } catch (final ServiceException e) {
            renderJson(context, new JSONObject().put(Common.STATUS_CODE, false).put(Keys.MSG, e.getMessage()));
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Saves book failed", e);
            renderJson(context, new JSONObject().put(Common.STATUS_CODE, false).put(Keys.MSG, "保存书籍失败"));
        }
    }

    @RequestProcessing(value = "/console/books/{id}/sync", method = HttpMethod.POST)
    public void syncBookToc(final RequestContext context) {
        try {
            final String id = StringUtils.trimToEmpty(context.pathVar("id"));
            final JSONObject synced = seriesAndBookshelfService.syncBookToc(id, false);
            renderJson(context, new JSONObject()
                    .put(Common.STATUS_CODE, true)
                    .put(Keys.MSG, "已同步 " + synced.optInt("chapterCount") + " 个章节")
                    .put("book", synced.optJSONObject("book"))
                    .put("toc", synced.optJSONArray("toc")));
        } catch (final ServiceException e) {
            renderJson(context, new JSONObject().put(Common.STATUS_CODE, false).put(Keys.MSG, e.getMessage()));
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Syncs book TOC failed", e);
            renderJson(context, new JSONObject().put(Common.STATUS_CODE, false).put(Keys.MSG, "同步书籍目录失败"));
        }
    }

    @RequestProcessing(value = "/console/books/{id}", method = HttpMethod.DELETE)
    public void deleteBook(final RequestContext context) {
        try {
            seriesAndBookshelfService.deleteBook(context.pathVar("id"));
            renderJson(context, new JSONObject().put(Common.STATUS_CODE, true).put(Keys.MSG, "书籍已删除"));
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Deletes book failed", e);
            renderJson(context, new JSONObject().put(Common.STATUS_CODE, false).put(Keys.MSG, "删除书籍失败"));
        }
    }

    private static void renderJson(final RequestContext context, final JSONObject payload) {
        final JsonRenderer renderer = new JsonRenderer();
        renderer.setJSONObject(payload);
        context.setRenderer(renderer);
    }
}
