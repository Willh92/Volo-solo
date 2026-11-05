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
package org.b3log.solo.plugin;

import org.apache.commons.lang3.StringUtils;
import org.b3log.latke.event.AbstractEventListener;
import org.b3log.latke.event.Event;
import org.b3log.latke.event.EventManager;
import org.b3log.latke.ioc.BeanManager;
import org.b3log.latke.plugin.AbstractPlugin;
import org.b3log.latke.plugin.NotInteractivePlugin;
import org.b3log.latke.plugin.PluginManager;
import org.b3log.latke.plugin.PluginStatus;
import org.b3log.latke.http.RequestContext;
import org.b3log.solo.event.EventTypes;
import org.b3log.solo.model.Article;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * ToC event handler.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 0.6.7
 */
public class ToCPlugin extends NotInteractivePlugin {

    private static volatile boolean active = false;

    /**
     * Public constructor.
     */
    private final ToCEventHandler listener = new ToCEventHandler();

    @Override
    protected void start() {
        active = true;
        final BeanManager beanManager = BeanManager.getInstance();
        if (null != beanManager) {
            final EventManager eventManager = beanManager.getReference(EventManager.class);
            if (null != eventManager) {
                eventManager.registerListener(listener);
            }
        }
    }

    @Override
    protected void stop() {
        active = false;
        final BeanManager beanManager = BeanManager.getInstance();
        if (null != beanManager) {
            final EventManager eventManager = beanManager.getReference(EventManager.class);
            if (null != eventManager) {
                eventManager.unregisterListener(listener);
            }
        }
    }

    public static boolean isActive() {
        return active;
    }

    public static boolean isPluginActive(final PluginManager pluginManager) {
        if (active) {
            return true;
        }
        if (null == pluginManager) {
            return false;
        }
        for (final AbstractPlugin plugin : pluginManager.getPlugins()) {
            if (plugin instanceof ToCPlugin) {
                return PluginStatus.ENABLED == plugin.getStatus();
            }
        }
        return false;
    }

    @Override
    public void prePlug(RequestContext context) {
    }

    @Override
    public void postPlug(Map<String, Object> dataModel, RequestContext context) {
    }

    public static void processArticleToc(final JSONObject article) {
        if (article == null) {
            return;
        }
        ToCEventHandler.populateArticleToc(article);
    }
}

/**
 * ToC event handler.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @author <a href="http://www.annpeter.cn">Ann Peter</a>
 * @author <a href="http://vanessa.b3log.org">Vanessa</a>
 * @since 0.6.7
 */
class ToCEventHandler extends AbstractEventListener<JSONObject> {

    @Override
    public String getEventType() {
        return EventTypes.BEFORE_RENDER_ARTICLE;
    }

    @Override
    public void action(final Event<JSONObject> event) {
        final JSONObject data = event.getData();
        if (null == data) {
            return;
        }
        final JSONObject article = data.optJSONObject(Article.ARTICLE);
        if (null == article) {
            return;
        }
        populateArticleToc(article);
    }

    static void populateArticleToc(final JSONObject article) {
        final String content = article.optString(Article.ARTICLE_CONTENT);
        if (!StringUtils.containsIgnoreCase(content, "<h")) {
            article.put(Article.ARTICLE_T_TOC, (Object) Collections.emptyList());
            return;
        }
        final Document doc = Jsoup.parse(content, StringUtils.EMPTY, Parser.htmlParser());
        doc.outputSettings().prettyPrint(false);

        final Elements hs = doc.select("h1, h2, h3, h4, h5");
        if (hs.isEmpty()) {
            article.put(Article.ARTICLE_T_TOC, (Object) Collections.emptyList());
            return;
        }
        final List<JSONObject> toc = new ArrayList<>(hs.size());
        int seq = 0;
        for (int i = 0; i < hs.size(); i++) {
            final Element element = hs.get(i);
            if (isInsideVoloWidget(element)) {
                continue;
            }
            final String tagName = element.tagName().toLowerCase();
            final String text = element.text();
            final String id = "b3_solo_" + tagName + "_" + (seq++);
            element.attr("id", id);
            final JSONObject li = new JSONObject().
                    put("className", "toc__" + tagName).
                    put("id", id).
                    put("text", text);
            toc.add(li);
        }
        final Element body = doc.getElementsByTag("body").get(0);
        article.put(Article.ARTICLE_CONTENT, body.html());
        article.put(Article.ARTICLE_T_TOC, (Object) toc);
    }

    private static boolean isInsideVoloWidget(final Element heading) {
        for (Element cur = heading; null != cur; cur = cur.parent()) {
            if (cur.hasClass("volo-bookshelf")
                    || cur.hasClass("volo-book-card")
                    || cur.hasClass("volo-book-reader")
                    || cur.hasClass("volo-series-box")
                    || cur.hasClass("volo-lock-card")) {
                return true;
            }
        }
        return false;
    }
}
