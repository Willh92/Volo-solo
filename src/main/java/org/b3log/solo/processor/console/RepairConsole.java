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

import org.b3log.latke.ioc.Inject;
import org.apache.logging.log4j.Level;
import org.b3log.solo.logging.Logger;
import org.b3log.latke.http.RequestContext;
import org.b3log.solo.web.annotation.Before;
import org.b3log.latke.ioc.Singleton;
import org.b3log.latke.http.renderer.TextHtmlRenderer;
import org.b3log.solo.model.Option;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.repository.TagArticleRepository;
import org.b3log.solo.repository.TagRepository;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.service.PreferenceMgmtService;
import org.b3log.solo.service.StatisticMgmtService;
import org.b3log.solo.service.StatisticQueryService;
import org.json.JSONObject;

/**
 * Provides patches on some special issues.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 0.3.1
 */
@Singleton
@Before(ConsoleAdminAuthAdvice.class)
public class RepairConsole {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(RepairConsole.class);

    /**
     * Option query service.
     */
    @Inject
    private OptionQueryService optionQueryService;

    /**
     * Preference management service.
     */
    @Inject
    private PreferenceMgmtService preferenceMgmtService;

    /**
     * Tag repository.
     */
    @Inject
    private TagRepository tagRepository;

    /**
     * Tag-Article repository.
     */
    @Inject
    private TagArticleRepository tagArticleRepository;

    /**
     * Article repository.
     */
    @Inject
    private ArticleRepository articleRepository;

    /**
     * Statistic query service.
     */
    @Inject
    private StatisticQueryService statisticQueryService;

    /**
     * Statistic management service.
     */
    @Inject
    private StatisticMgmtService statisticMgmtService;

    @Inject
    private org.b3log.solo.cache.PublicViewCache publicViewCache;

    /**
     * Restores the signs of preference to default.
     *
     * @param context the specified context
     */
    public void restoreSigns(final RequestContext context) {
        if (!org.b3log.solo.util.AdminRequests.allowWrite(context)) {
            return;
        }
        final TextHtmlRenderer renderer = new TextHtmlRenderer();
        context.setRenderer(renderer);

        try {
            final JSONObject preference = optionQueryService.getPreference();
            preference.put(Option.ID_C_SIGNS, Option.DefaultPreference.DEFAULT_SIGNS);
            preferenceMgmtService.updatePreference(preference);
            org.b3log.solo.cache.PublicViewCache viewCache = this.publicViewCache;
            if (null == viewCache) {
                try {
                    viewCache = org.b3log.latke.ioc.BeanManager.getInstance()
                            .getReference(org.b3log.solo.cache.PublicViewCache.class);
                } catch (final Exception ignored) {
                }
            }
            if (null != viewCache) {
                viewCache.invalidateAll();
            }

            renderer.setContent("Restore signs succeeded.");
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, e.getMessage(), e);

            renderer.setContent("Restores signs failed.");
        }
    }
}
