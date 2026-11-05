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
package org.b3log.solo.util;

import freemarker.cache.MruCacheStorage;
import freemarker.template.Configuration;
import freemarker.core.TemplateClassResolver;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;
import org.apache.commons.lang3.StringUtils;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.ioc.BeanManager;
import org.apache.logging.log4j.Level;
import org.b3log.solo.logging.Logger;
import org.b3log.latke.service.LangPropsService;
import org.b3log.latke.service.ServiceException;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.util.Locales;
import org.b3log.latke.util.Stopwatchs;
import org.b3log.solo.SoloApplication;
import org.b3log.solo.model.Option;

import org.b3log.solo.web.WebResources;
import org.b3log.latke.http.Request;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skin utilities.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 0.3.1
 */
public final class Skins {

    /**
     * FreeMarker configuration.
     */
    public static final Configuration TEMPLATE_CFG;
    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(Skins.class);
    private static final int MAX_LANG_MAP_ENTRIES = 64;
    /**
     * Properties map.
     */
    private static final ConcurrentHashMap<String, Map<String, String>> LANG_MAP = new ConcurrentHashMap<>();
    private static volatile Set<String> cachedSkinDirNames;
    private static volatile Path cachedSkinWebRoot;

    static {
        TEMPLATE_CFG = new Configuration(Configuration.VERSION_2_3_35);
        TEMPLATE_CFG.setNewBuiltinClassResolver(TemplateClassResolver.ALLOWS_NOTHING_RESOLVER);
        TEMPLATE_CFG.setAPIBuiltinEnabled(false);
        TEMPLATE_CFG.setDefaultEncoding("UTF-8");
        final WebResources servletContext = SoloApplication.getWebResources();
        try {
            TEMPLATE_CFG.setDirectoryForTemplateLoading(servletContext.root().toFile());
        } catch (java.io.IOException e) {
            throw new ExceptionInInitializerError(e);
        }
        TEMPLATE_CFG.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        TEMPLATE_CFG.setLogTemplateExceptions(false);
        TEMPLATE_CFG.setCacheStorage(new MruCacheStorage(64, 192));
        applyTemplateUpdateDelay();
    }

    private static void applyTemplateUpdateDelay() {
        try {
            if (Latkes.RuntimeMode.PRODUCTION == Latkes.getRuntimeMode()) {
                TEMPLATE_CFG.setTemplateUpdateDelayMilliseconds(3_600_000L);
            }
        } catch (final RuntimeException ignored) {
        }
    }

    /**
     * Private constructor.
     */
    private Skins() {
    }

    /**
     * Invalidates the cached set of skin directory names.
     */
    public static void invalidateSkinDirNames() {
        cachedSkinDirNames = null;
        cachedSkinWebRoot = null;
    }

    /**
     * Clears FreeMarker template cache, skin language bundle cache, and cached skin directory names.
     */
    public static void clearTemplateCache() {
        TEMPLATE_CFG.clearTemplateCache();
        LANG_MAP.clear();
        cachedSkinDirNames = null;
        cachedSkinWebRoot = null;
        applyTemplateUpdateDelay();
    }

    /**
     * Gets a template with the specified template name.
     *
     * @param templateName the specified template name
     * @return template, returns {@code null} if not found
     */
    public static Template getTemplate(final String templateName) {
        try {
            return Skins.TEMPLATE_CFG.getTemplate(templateName);
        } catch (final IOException e) {
            LOGGER.log(Level.ERROR, "Gets console template [" + templateName + "] failed", e);

            return null;
        }
    }

    /**
     * Gets a skins template with the specified request and template name.
     *
     * @param context      the specified request context
     * @param templateName the specified template name
     * @return template, returns {@code null} if not found
     */
    public static Template getSkinTemplate(final RequestContext context, final String templateName) {
        String templateDirName = (String) context.attr(Keys.TEMPLATE_DIR_NAME);
        if (StringUtils.isBlank(templateDirName)) {
            templateDirName = Option.DefaultPreference.DEFAULT_SKIN_DIR_NAME;
        }

        try {
            return Skins.TEMPLATE_CFG.getTemplate("skins/" + templateDirName + "/" + templateName);
        } catch (final IOException e) {
            return null;
        }
    }

    /**
     * Fills the specified data model with the current skin's (WebRoot/skins/${skinDirName}/lang/lang_xx_XX.properties)
     * and core language (WebRoot/WEB-INF/classes/lang_xx_XX.properties) configurations.
     *
     * @param localeString       the specified locale string
     * @param currentSkinDirName the specified current skin directory name
     * @param dataModel          the specified data model
     * @throws ServiceException service exception
     */
    public static void fillLangs(final String localeString, String currentSkinDirName, final Map<String, Object> dataModel)
            throws ServiceException {
        Stopwatchs.start("Fill Skin Langs");

        try {
            // Fills the core language configurations
            final BeanManager beanManager = BeanManager.getInstance();
            final LangPropsService langPropsService = beanManager.getReference(LangPropsService.class);
            dataModel.putAll(langPropsService.getAll(Latkes.getLocale()));

            if (StringUtils.isBlank(currentSkinDirName)) {
                currentSkinDirName = Option.DefaultPreference.DEFAULT_SKIN_DIR_NAME;
            }
            final String langName = currentSkinDirName + "." + localeString;
            Map<String, String> langs = LANG_MAP.get(langName);
            if (null == langs) {
                final Map<String, String> loaded = new HashMap<>();
                final String language = Locales.getLanguage(localeString);
                final String country = Locales.getCountry(localeString);
                final WebResources servletContext = SoloApplication.getWebResources();
                try (final InputStream inputStream = servletContext.getResourceAsStream(
                        "/skins/" + currentSkinDirName + "/lang/lang_" + language + '_' + country + ".properties")) {
                    if (null != inputStream) {
                        LOGGER.log(Level.DEBUG, "Loading skin [dirName={0}, locale={1}]", currentSkinDirName, localeString);
                        final Properties props = new Properties();
                        props.load(inputStream);
                        final Set<Object> keys = props.keySet();
                        for (final Object key : keys) {
                            String val = props.getProperty((String) key);
                            val = replaceVars(val);
                            loaded.put((String) key, val);
                        }

                        langs = Collections.unmodifiableMap(loaded);
                        LOGGER.log(Level.DEBUG, "Loaded skin [dirName={0}, locale={1}, keyCount={2}]", currentSkinDirName, localeString, langs.size());
                    } else {
                        langs = Collections.emptyMap();
                    }
                }
                if (LANG_MAP.size() >= MAX_LANG_MAP_ENTRIES) {
                    final Iterator<String> it = LANG_MAP.keySet().iterator();
                    if (it.hasNext()) {
                        LANG_MAP.remove(it.next());
                    }
                }
                LANG_MAP.put(langName, langs);
            }

            if (!langs.isEmpty()) {
                dataModel.putAll(langs); // Fills the current skin's language configurations
            }
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Fills skin langs failed", e);

            throw new ServiceException(e);
        } finally {
            Stopwatchs.end();
        }
    }

    /**
     * Gets all skin directory names. Scans the /skins/ directory, using the subdirectory of it as the skin directory
     * name, for example,
     * <pre>
     * ${Web root}/skins/
     *     <b>default</b>/
     *     <b>mobile</b>/
     *     <b>classic</b>/
     * </pre>.
     *
     * @return a set of skin name, returns an empty set if not found
     */
    public static Set<String> getSkinDirNames() {
        final WebResources servletContext = SoloApplication.getWebResources();
        final Path root = servletContext.root();
        final Set<String> cached = cachedSkinDirNames;
        if (null != cached && Objects.equals(root, cachedSkinWebRoot)) {
            return cached;
        }

        final Set<String> ret = new HashSet<>();
        final Set<String> resourcePaths = servletContext.getResourcePaths("/skins");
        for (final String path : resourcePaths) {
            final Path p = Paths.get(path);
            final Path file = p.getFileName();
            final String fileName = file.toString();
            if (fileName.startsWith(".") || fileName.endsWith(".md")) {
                continue;
            }

            ret.add(fileName);
        }

        final Set<String> immutable = Collections.unmodifiableSet(ret);
        cachedSkinWebRoot = root;
        cachedSkinDirNames = immutable;
        return immutable;
    }

    /**
     * Gets skin directory name from the specified request.
     * Refers to <a href="https://github.com/b3log/solo/issues/12060">前台皮肤切换</a> for more details.
     *
     * @param context the specified request context
     * @return directory name, or {@code null} if not found
     */
    public static String getSkinDirName(final RequestContext context) {
        return getSkinDirNameFromQuery(context.getRequest());
    }

    /**
     * Gets a validated preview skin from the request query. Previewing a skin
     * never changes the configured skin or subsequent visits without the query.
     *
     * @param request the specified request
     * @return directory name, or {@code null} if not found
     */
    public static String getSkinDirNameFromQuery(final Request request) {
        final String skin = request.getParameter(Option.CATEGORY_C_SKIN);
        return StringUtils.isNotBlank(skin) && getSkinDirNames().contains(skin) ? skin : null;
    }

    /**
     * Replaces all variables of the specified language value.
     *
     * <p>
     * Variables:
     * <ul>
     * <li>${servePath}</li>
     * <li>${staticServePath}</li>
     * </ul>
     * </p>
     *
     * @param langValue the specified language value
     * @return replaced value
     */
    private static String replaceVars(final String langValue) {
        String ret = StringUtils.replace(langValue, "${servePath}", Latkes.getServePath());
        ret = StringUtils.replace(ret, "${staticServePath}", Latkes.getStaticServePath());

        return ret;
    }
}
