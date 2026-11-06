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
package org.b3log.solo.processor.console;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.FileUtils;
import org.b3log.latke.http.Response;
import org.b3log.solo.web.NettyFileSender;
import org.owasp.encoder.Encode;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.event.Event;
import org.b3log.latke.event.EventManager;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.ioc.Singleton;
import org.apache.logging.log4j.Level;
import org.b3log.solo.logging.Logger;
import org.b3log.latke.model.Plugin;
import org.b3log.latke.model.User;
import org.b3log.latke.plugin.ViewLoadEventData;
import org.b3log.latke.repository.RepositoryException;
import org.b3log.latke.service.LangPropsService;
import org.b3log.latke.http.RequestContext;
import org.b3log.solo.web.annotation.Before;
import org.b3log.latke.http.renderer.AbstractFreeMarkerRenderer;
import org.b3log.solo.SoloApplication;
import org.b3log.solo.bolo.Global;
import org.b3log.solo.model.Common;
import org.b3log.solo.model.Option;
import org.b3log.solo.model.UserExt;
import org.b3log.solo.repository.ArchiveDateRepository;
import org.b3log.solo.repository.CategoryRepository;
import org.b3log.solo.service.DataModelService;
import org.b3log.solo.service.ExportService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.service.UserQueryService;
import org.b3log.solo.util.Markdowns;
import org.b3log.solo.util.AdminPaths;
import org.b3log.solo.util.PluginUtil;
import org.b3log.solo.util.Solos;
import org.json.JSONObject;

/**
 * Admin console render processing.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 0.4.1
 */
@Singleton
@Before(ConsoleAuthAdvice.class)
public class AdminConsole {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(AdminConsole.class);

    /**
     * Language service.
     */
    @Inject
    private LangPropsService langPropsService;

    /**
     * Option query service.
     */
    @Inject
    private OptionQueryService optionQueryService;

    /**
     * User query service.
     */
    @Inject
    private UserQueryService userQueryService;

    /**
     * Export service.
     */
    @Inject
    private ExportService exportService;

    /**
     * DataModelService.
     */
    @Inject
    private DataModelService dataModelService;

    /**
     * Event manager.
     */
    @Inject
    private EventManager eventManager;

    /**
     * Category repository.
     */
    @Inject
    private CategoryRepository categoryRepository;

    /**
     * Archive Date Repository.
     */
    @Inject
    private ArchiveDateRepository archiveDateRepository;

    /**
     * Shows administrator index with the specified context.
     *
     * @param context the specified context
     */
    public void showAdminIndex(final RequestContext context) {
        final String templateName = "admin-index.ftl";
        final AbstractFreeMarkerRenderer renderer = new ConsoleRenderer(context, templateName);
        final Map<String, String> langs = langPropsService.getAll(Latkes.getLocale());
        final Map<String, Object> dataModel = renderer.getDataModel();
        AdminPaths.fill(dataModel);
        dataModel.putAll(langs);
        final JSONObject currentUser = Solos.getCurrentUser(context.getRequest(), context.getResponse());
        final String userName = currentUser.optString(User.USER_NAME);
        dataModel.put(User.USER_NAME, userName);
        final String roleName = currentUser.optString(User.USER_ROLE);
        dataModel.put(User.USER_ROLE, roleName);
        final String userAvatar = currentUser.optString(UserExt.USER_AVATAR);
        dataModel.put(Common.GRAVATAR, userAvatar);

        try {
            final JSONObject preference = optionQueryService.getPreference();
            // 支持配置编辑器模式 https://github.com/88250/solo/issues/95
            String editorMode = preference.optString(Option.ID_C_EDITOR_MODE);
            if (StringUtils.isBlank(editorMode)) {
                editorMode = "sv";
            }
            dataModel.put(Option.ID_C_EDITOR_MODE, editorMode);

            dataModel.put(Option.ID_C_LOCALE_STRING, preference.getString(Option.ID_C_LOCALE_STRING));
            dataModel.put(Option.ID_C_BLOG_TITLE, preference.getString(Option.ID_C_BLOG_TITLE));
            dataModel.put(Option.ID_C_BLOG_SUBTITLE, preference.getString(Option.ID_C_BLOG_SUBTITLE));
            dataModel.put(Common.VERSION, SoloApplication.VERSION);
            dataModel.put(Common.BOLO_VERSION, SoloApplication.BOLO_VERSION);
            dataModel.put(Common.STATIC_RESOURCE_VERSION, Latkes.getStaticResourceVersion());
            dataModel.put(Common.B3LOG_ENABLED, "" + PluginUtil.b3logPluginEnabled());
            dataModel.put(Common.FISHPI_ENABLED, "" + PluginUtil.fishpiPluginEnabled());
            dataModel.put(Common.YEAR, String.valueOf(Calendar.getInstance().get(Calendar.YEAR)));
            dataModel.put(Option.ID_C_ARTICLE_LIST_DISPLAY_COUNT,
                    preference.getInt(Option.ID_C_ARTICLE_LIST_DISPLAY_COUNT));
            dataModel.put(Option.ID_C_ARTICLE_LIST_PAGINATION_WINDOW_SIZE,
                    preference.getInt(Option.ID_C_ARTICLE_LIST_PAGINATION_WINDOW_SIZE));
            final JSONObject skin = optionQueryService.getSkin();
            dataModel.put(Option.CATEGORY_C_SKIN, skin.optString(Option.ID_C_SKIN_DIR_NAME));
            Keys.fillRuntime(dataModel);
            dataModelService.fillMinified(dataModel);
            dataModel.put(Common.LUTE_AVAILABLE, Markdowns.LUTE_AVAILABLE);
            // 内置 HTTPS+CDN 文件存储 https://github.com/b3log/solo/issues/12556
            dataModel.put(Common.UPLOAD_TOKEN, "");
            dataModel.put(Common.UPLOAD_URL, "");
            dataModel.put(Common.UPLOAD_MSG, langPropsService.get("getUploadTokenErrLabel"));
            final JSONObject upload = Solos.getUploadToken(context);
            if (null != upload) {
                dataModel.put(Common.UPLOAD_TOKEN, upload.optString(Common.UPLOAD_TOKEN));
                dataModel.put(Common.UPLOAD_URL, upload.optString(Common.UPLOAD_URL));
                dataModel.put(Common.UPLOAD_MSG, upload.optString(Common.UPLOAD_MSG));
            }

            dataModelService.fillCharts(context, dataModel, preference);
            dataModelService.fillFaviconURL(dataModel, preference);
            dataModelService.fillUsite(dataModel);
            dataModelService.fillCommon(context, dataModel, preference);
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Admin index render failed", e);
        }

        fireFreeMarkerActionEvent(templateName, dataModel);
    }

    /**
     * Shows administrator functions with the specified context.
     *
     * @param context the specified context
     */
    public void showAdminFunctions(final RequestContext context) {
        final String requestURI = context.requestURI();
        final String func = StringUtils.substringBeforeLast(StringUtils.substringAfterLast(requestURI, "/"), ".");
        final String templateName = func + ".ftl";
        final AbstractFreeMarkerRenderer renderer = new ConsoleRenderer(context, templateName);

        final Locale locale = Latkes.getLocale();
        final Map<String, String> langs = langPropsService.getAll(locale);
        final Map<String, Object> dataModel = renderer.getDataModel();

        // 使用 MySQL 时不启用 SQL 导出功能 https://github.com/b3log/solo/issues/12806
        dataModel.put("supportExport", Latkes.RuntimeDatabase.H2 == Latkes.getRuntimeDatabase());
        dataModel.putAll(langs);
        Keys.fillRuntime(dataModel);
        dataModel.put(Option.ID_C_LOCALE_STRING, locale.toString());
        dataModel.put(Option.ID_C_VERSION, SoloApplication.VERSION);

        if ("admin-article".equals(func) || "admin-preference".equals(func)) {
            try {
                // Bolo categories
                final List<JSONObject> categories = categoryRepository.getMostUsedCategories(Integer.MAX_VALUE);
                dataModel.put("categories", categories);
            } catch (final RepositoryException e) {
                LOGGER.log(Level.ERROR, "Loads categories for admin function [" + func + "] failed", e);
                dataModel.put("categories", Collections.emptyList());
            }
        } else {
            dataModel.put("categories", Collections.emptyList());
        }

        // Bolo version
        dataModel.put(Common.BOLO_VERSION, SoloApplication.BOLO_VERSION);
        // 链滴域名设定
        dataModel.put("hacpaiDomain", Global.HACPAI_DOMAIN);
        dataModel.put("fishpiDomain", Global.FISH_PI_DOMAIN);
        dataModel.put(Common.B3LOG_ENABLED, PluginUtil.b3logPluginEnabled());
        dataModel.put(Common.FISHPI_ENABLED, PluginUtil.fishpiPluginEnabled());
        fireFreeMarkerActionEvent(templateName, dataModel);
    }

    /**
     * Shows administrator preference function with the specified context.
     *
     * @param context the specified context
     */
    public void showAdminPreferenceFunction(final RequestContext context) {
        final String templateName = "admin-preference.ftl";
        final AbstractFreeMarkerRenderer renderer = new ConsoleRenderer(context, templateName);

        final Locale locale = Latkes.getLocale();
        final Map<String, String> langs = langPropsService.getAll(locale);
        final Map<String, Object> dataModel = renderer.getDataModel();
        dataModel.putAll(langs);
        dataModel.put(Option.ID_C_LOCALE_STRING, locale.toString());

        final JSONObject preference = optionQueryService.getPreference();
        final StringBuilder timeZoneIdOptions = new StringBuilder();
        final String selectedTimeZoneId = preference.optString(Option.ID_C_TIME_ZONE_ID);
        final String[] availableIDs = TimeZone.getAvailableIDs();
        for (int i = 0; i < availableIDs.length; i++) {
            final String id = availableIDs[i];
            final String escapedAttr = Encode.forHtmlAttribute(id);
            final String escapedLabel = Encode.forHtml(id);
            final String option;

            if (id.equals(selectedTimeZoneId)) {
                option = "<option value=\"" + escapedAttr + "\" selected=\"true\">" + escapedLabel + "</option>";
            } else {
                option = "<option value=\"" + escapedAttr + "\">" + escapedLabel + "</option>";
            }

            timeZoneIdOptions.append(option);
        }

        dataModel.put("timeZoneIdOptions", timeZoneIdOptions.toString());

        // 链滴域名设定
        dataModel.put("hacpaiDomain", Global.HACPAI_DOMAIN);
        // 摸鱼派域名设定
        dataModel.put("fishpiDomain", Global.FISH_PI_DOMAIN);
        dataModel.put(Common.B3LOG_ENABLED, PluginUtil.b3logPluginEnabled());
        dataModel.put(Common.FISHPI_ENABLED, PluginUtil.fishpiPluginEnabled());
        dataModel.put("securityClientAddress", org.b3log.solo.util.LoginThrottle.clientAddress(context.getRequest()));
        dataModel.put("securityPeerAddress", org.b3log.solo.util.LoginThrottle.peerAddress(context.getRequest()));

        fireFreeMarkerActionEvent(templateName, dataModel);
    }

    public void showAdminAccess(final RequestContext context) { showAdminSecurityFunction(context, "access"); }
    public void showAdminTotp(final RequestContext context) { showAdminSecurityFunction(context, "totp"); }
    public void showAdminIpBans(final RequestContext context) { showAdminSecurityFunction(context, "ip"); }
    public void showAdminGeoRules(final RequestContext context) { showAdminSecurityFunction(context, "geo"); }
    public void showAdminCommentProtection(final RequestContext context) { showAdminSecurityFunction(context, "comments"); }

    private void showAdminSecurityFunction(final RequestContext context, final String section) {
        if (!Solos.isAdminLoggedIn(context)) { org.b3log.solo.util.AdminRequests.reject(context, 403, "仅管理员可以访问安全设置。"); return; }
        final String templateName = "admin-security.ftl";
        final AbstractFreeMarkerRenderer renderer = new ConsoleRenderer(context, templateName);
        final Map<String, Object> dataModel = renderer.getDataModel();
        dataModel.put("securitySection", section);
        dataModel.putAll(langPropsService.getAll(Latkes.getLocale()));
        dataModel.put("adminPanelPath", AdminPaths.panel());
        dataModel.put("adminLoginPath", AdminPaths.login());
        dataModel.put("securityClientAddress", org.b3log.solo.util.LoginThrottle.clientAddress(context.getRequest()));
        dataModel.put("securityPeerAddress", org.b3log.solo.util.LoginThrottle.peerAddress(context.getRequest()));
        fireFreeMarkerActionEvent(templateName, dataModel);
    }

    /**
     * Exports data as SQL zip file.
     *
     * @param context the specified request context
     */
    public void exportSQL(final RequestContext context) {
        final Response response = context.getResponse();
        response.setHeader("Cache-Control", "no-store");

        if (!Solos.isAdminLoggedIn(context)) {
            context.sendError(401);

            return;
        }

        final String date = DateFormatUtils.format(new Date(), "yyyyMMddHHmmss");
        Path tempDir = null;
        boolean handedOff = false;
        try {
            tempDir = Files.createTempDirectory("volo-export-sql-");
            final Path dirToDelete = tempDir;
            final AtomicBoolean cleaned = new AtomicBoolean(false);
            final Runnable cleanup = () -> {
                if (cleaned.compareAndSet(false, true)) {
                    FileUtils.deleteQuietly(dirToDelete.toFile());
                }
            };
            final String fileName = "solo-sql-" + date + ".zip";
            final Path zipFile = tempDir.resolve(fileName);
            if (!exportService.exportSQLToZipFile(zipFile)) {
                cleanup.run();
                context.sendError(500);
                return;
            }
            sendZipArchiveResponse(context, zipFile, fileName, cleanup);
            handedOff = true;
        } catch (final Exception e) {
            if (tempDir != null) {
                FileUtils.deleteQuietly(tempDir.toFile());
            }
            LOGGER.log(Level.ERROR, "Export SQL failed", e);
            if (response == null || !response.isCommitted()) {
                context.sendError(500);
            }
        } finally {
            if (!handedOff && tempDir != null) {
                FileUtils.deleteQuietly(tempDir.toFile());
            }
        }
    }

    /**
     * Exports data as JSON zip file.
     *
     * @param context the specified request context
     */
    public void exportJSON(final RequestContext context) {
        final Response response = context.getResponse();
        response.setHeader("Cache-Control", "no-store");
        if (!Solos.isAdminLoggedIn(context)) {
            context.sendError(401);

            return;
        }

        final String date = DateFormatUtils.format(new Date(), "yyyyMMddHHmmss");
        Path tempDir = null;
        boolean handedOff = false;
        try {
            tempDir = Files.createTempDirectory("volo-export-json-");
            final Path dirToDelete = tempDir;
            final AtomicBoolean cleaned = new AtomicBoolean(false);
            final Runnable cleanup = () -> {
                if (cleaned.compareAndSet(false, true)) {
                    FileUtils.deleteQuietly(dirToDelete.toFile());
                }
            };
            final String fileName = "solo-json-" + date + ".zip";
            final Path zipFile = tempDir.resolve(fileName);
            try (final OutputStream fos = new BufferedOutputStream(Files.newOutputStream(zipFile));
                 final ZipOutputStream zos = new ZipOutputStream(fos, StandardCharsets.UTF_8)) {
                zos.putNextEntry(new ZipEntry("solo-" + date + ".json"));
                final BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(zos, StandardCharsets.UTF_8), 32768);
                exportService.writeJSONsToStream(writer);
                writer.flush();
                zos.closeEntry();
            }

            sendZipArchiveResponse(context, zipFile, fileName, cleanup);
            handedOff = true;
        } catch (final Exception e) {
            if (tempDir != null) {
                FileUtils.deleteQuietly(tempDir.toFile());
            }
            LOGGER.log(Level.ERROR, "Export failed", e);
            if (response == null || !response.isCommitted()) {
                org.b3log.solo.web.JsonResponses.json(context).renderMsg("Export failed, please check log");
            }
        } finally {
            if (!handedOff && tempDir != null) {
                FileUtils.deleteQuietly(tempDir.toFile());
            }
        }
    }

    /**
     * Exports data as Hexo markdown zip file.
     *
     * @param context the specified request context
     */
    public void exportHexo(final RequestContext context) {
        final Response response = context.getResponse();
        response.setHeader("Cache-Control", "no-store");
        if (!Solos.isAdminLoggedIn(context)) {
            context.sendError(401);

            return;
        }

        final String date = DateFormatUtils.format(new Date(), "yyyyMMddHHmmss");
        Path tempDir = null;
        boolean handedOff = false;
        try {
            tempDir = Files.createTempDirectory("volo-export-hexo-");
            final Path dirToDelete = tempDir;
            final AtomicBoolean cleaned = new AtomicBoolean(false);
            final Runnable cleanup = () -> {
                if (cleaned.compareAndSet(false, true)) {
                    FileUtils.deleteQuietly(dirToDelete.toFile());
                }
            };
            final String fileName = "solo-hexo-" + date + ".zip";
            final Path zipFile = tempDir.resolve(fileName);
            try (final OutputStream fos = new BufferedOutputStream(Files.newOutputStream(zipFile));
                 final ZipOutputStream zos = new ZipOutputStream(fos, StandardCharsets.UTF_8)) {
                exportService.writeHexoMDsToZip(zos);
            }

            sendZipArchiveResponse(context, zipFile, fileName, cleanup);
            handedOff = true;
        } catch (final Exception e) {
            if (tempDir != null) {
                FileUtils.deleteQuietly(tempDir.toFile());
            }
            LOGGER.log(Level.ERROR, "Export failed", e);
            if (response == null || !response.isCommitted()) {
                org.b3log.solo.web.JsonResponses.json(context).renderMsg("Export failed, please check log");
            }
        } finally {
            if (!handedOff && tempDir != null) {
                FileUtils.deleteQuietly(tempDir.toFile());
            }
        }
    }

    private static void sendZipArchiveResponse(final RequestContext context,
                                               final Path zipFile,
                                               final String fileName,
                                               final Runnable cleanup) throws IOException {
        final long size = Files.size(zipFile);
        if (size < 0L) {
            throw new IOException("Invalid export zip size: " + size);
        }
        final Response response = context.getResponse();
        response.setContentType("application/zip");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
        NettyFileSender.sendFile(context, zipFile, 0L, size, cleanup);
    }

    /**
     * Fires FreeMarker action event with the host template name and data model.
     *
     * @param hostTemplateName the specified host template name
     * @param dataModel        the specified data model
     */
    private void fireFreeMarkerActionEvent(final String hostTemplateName, final Map<String, Object> dataModel) {
        final ViewLoadEventData data = new ViewLoadEventData();

        data.setViewName(hostTemplateName);
        data.setDataModel(dataModel);
        eventManager.fireEventSynchronously(new Event<>(Keys.FREEMARKER_ACTION, data));
        if (StringUtils.isBlank((String) dataModel.get(Plugin.PLUGINS))) {
            // There is no plugin for this template, fill ${plugins} with blank.
            dataModel.put(Plugin.PLUGINS, "");
        }
    }
}
