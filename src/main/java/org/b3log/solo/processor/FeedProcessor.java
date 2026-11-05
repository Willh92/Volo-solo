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
package org.b3log.solo.processor;

import com.vdurmont.emoji.EmojiParser;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.http.HttpMethod;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.http.renderer.AtomRenderer;
import org.b3log.latke.http.renderer.RssRenderer;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.ioc.Singleton;
import org.b3log.latke.model.Role;
import org.b3log.latke.model.User;
import org.b3log.latke.repository.CompositeFilter;
import org.b3log.latke.repository.CompositeFilterOperator;
import org.b3log.latke.repository.Filter;
import org.b3log.latke.repository.FilterOperator;
import org.b3log.latke.repository.PropertyFilter;
import org.b3log.latke.repository.Query;
import org.b3log.latke.repository.SortDirection;
import org.b3log.latke.service.ServiceException;
import org.b3log.latke.util.Locales;
import org.b3log.solo.SoloApplication;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Option;
import org.b3log.solo.model.atom.Category;
import org.b3log.solo.model.atom.Entry;
import org.b3log.solo.model.atom.Feed;
import org.b3log.solo.model.rss.Channel;
import org.b3log.solo.model.rss.Item;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.service.ArticleQueryService;
import org.b3log.solo.service.HiddenContentService;
import org.b3log.solo.service.OptionQueryService;
import org.b3log.solo.service.SeriesAndBookshelfService;
import org.b3log.solo.service.SyndicationCacheService;
import org.b3log.solo.util.Markdowns;
import org.b3log.solo.util.QueryResults;
import org.b3log.solo.util.ReadingStatsUtil;
import org.b3log.solo.web.HttpCompression;
import org.b3log.solo.web.PublicResourceHandler;
import org.b3log.solo.web.annotation.RequestProcessing;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Feed (Atom/RSS) processor.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @author <a href="https://github.com/feroozkhanchintu">feroozkhanchintu</a>
 * @author <a href="https://github.com/nanolikeyou">nanolikeyou</a>
 * @since 0.3.1
 */
@Singleton
public class FeedProcessor {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(FeedProcessor.class);

    private static final String ATOM_CONTENT_TYPE = "application/atom+xml; charset=UTF-8";
    private static final String RSS_CONTENT_TYPE = "application/rss+xml; charset=UTF-8";
    private static final byte[] EMPTY_BYTES = new byte[0];

    /**
     * Article query service.
     */
    @Inject
    private ArticleQueryService articleQueryService;

    /**
     * Article repository.
     */
    @Inject
    private ArticleRepository articleRepository;

    /**
     * Option query service.
     */
    @Inject
    private OptionQueryService optionQueryService;

    @Inject
    private SyndicationCacheService syndicationCacheService;

    /**
     * Blog articles Atom output.
     *
     * @param context the specified context
     */
    @RequestProcessing(value = "/atom.xml", method = {HttpMethod.GET, HttpMethod.HEAD})
    public void blogArticlesAtom(final RequestContext context) {
        try {
            final SyndicationCacheService.Snapshot snapshot = syndicationCacheService != null
                    ? syndicationCacheService.getOrCompute(SyndicationCacheService.KEY_ATOM, this::buildAtomXml)
                    : SyndicationCacheService.Snapshot.of(buildAtomXml());

            HttpCompression.ensureVaryAcceptEncoding(context.getResponse());
            if (SyndicationCacheService.tryServeNotModified(context, snapshot, ATOM_CONTENT_TYPE)) {
                return;
            }

            final AtomRenderer renderer = new AtomRenderer();
            renderer.setContent(snapshot.xml());
            context.setRenderer(renderer);
            if ("HEAD".equalsIgnoreCase(context.method())) {
                context.getResponse().sendBytes(EMPTY_BYTES);
            } else if (PublicResourceHandler.acceptsGzip(context.header("Accept-Encoding"))) {
                HttpCompression.sendText(context, snapshot.xml(), ATOM_CONTENT_TYPE);
            } else {
                renderer.render(context);
            }
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Get blog article feed error", e);

            context.sendError(503);
        }
    }

    private String buildAtomXml() throws Exception {
        final Feed feed = new Feed();
        final JSONObject preference = optionQueryService.getPreference();
        final String blogTitle = preference.getString(Option.ID_C_BLOG_TITLE);
        final String blogSubtitle = preference.getString(Option.ID_C_BLOG_SUBTITLE);
        final int outputCnt = preference.getInt(Option.ID_C_FEED_OUTPUT_CNT);
        final boolean isFullContent = "fullContent".equals(preference.optString(Option.ID_C_FEED_OUTPUT_MODE));
        feed.setTitle(blogTitle);
        feed.setSubtitle(blogSubtitle);
        feed.setUpdated(new Date());
        feed.setAuthor(blogTitle);
        feed.setLink(Latkes.getServePath() + "/atom.xml");
        feed.setId(Latkes.getServePath() + "/");

        final List<Filter> filters = new ArrayList<>();
        filters.add(new PropertyFilter(Article.ARTICLE_STATUS, FilterOperator.EQUAL, Article.ARTICLE_STATUS_C_PUBLISHED));
        filters.add(new PropertyFilter(Article.ARTICLE_VIEW_PWD, FilterOperator.EQUAL, ""));
        final Query query = new Query()
                .setPage(1, outputCnt)
                .setFilter(new CompositeFilter(CompositeFilterOperator.AND, filters))
                .addSort(Article.ARTICLE_UPDATED, SortDirection.DESCENDING)
                .setPageCount(1);
        if (isFullContent) {
            query.select(Keys.OBJECT_ID,
                    Article.ARTICLE_TITLE,
                    Article.ARTICLE_CONTENT,
                    Article.ARTICLE_UPDATED,
                    Article.ARTICLE_PERMALINK,
                    Article.ARTICLE_AUTHOR_ID,
                    Article.ARTICLE_TAGS_REF,
                    Article.ARTICLE_STATUS,
                    Article.ARTICLE_VIEW_PWD);
        } else {
            query.select(Keys.OBJECT_ID,
                    Article.ARTICLE_TITLE,
                    Article.ARTICLE_ABSTRACT,
                    Article.ARTICLE_UPDATED,
                    Article.ARTICLE_PERMALINK,
                    Article.ARTICLE_AUTHOR_ID,
                    Article.ARTICLE_TAGS_REF,
                    Article.ARTICLE_STATUS,
                    Article.ARTICLE_VIEW_PWD);
        }
        final JSONObject articleResult = articleRepository.get(query);
        final JSONArray articles = QueryResults.array(articleResult);
        for (int i = 0; i < articles.length(); i++) {
            final JSONObject article = articles.optJSONObject(i);
            if (article == null
                    || article.optInt(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED) != Article.ARTICLE_STATUS_C_PUBLISHED
                    || StringUtils.isNotBlank(article.optString(Article.ARTICLE_VIEW_PWD))) {
                continue;
            }
            final Entry entry = getEntry(articles, isFullContent, i);
            feed.addEntry(entry);
        }

        return feed.toString();
    }

    private Entry getEntry(final JSONArray articles, final boolean isFullContent, int i)
            throws JSONException, ServiceException {
        final JSONObject article = articles.getJSONObject(i);
        final Entry ret = new Entry();
        final String title = article.getString(Article.ARTICLE_TITLE);
        ret.setTitle(title);
        final JSONObject author = articleQueryService.getAuthor(article);
        String summary = isFullContent ? article.getString(Article.ARTICLE_CONTENT)
                : article.optString(Article.ARTICLE_ABSTRACT);
        summary = SeriesAndBookshelfService.stripForFeed(
                HiddenContentService.stripForFeed(summary));
        summary = Markdowns.toHTML(EmojiParser.parseToAliases(summary));
        if (!Role.ADMIN_ROLE.equals(author.optString(User.USER_ROLE))) {
            summary = Markdowns.clean(summary);
        }
        ret.setSummary(summary);
        final long updated = article.getLong(Article.ARTICLE_UPDATED);
        ret.setUpdated(new Date(updated));
        final String link = Latkes.getServePath() + article.getString(Article.ARTICLE_PERMALINK);
        ret.setLink(link);
        ret.setId(link);
        final String authorName = author.getString(User.USER_NAME);
        ret.setAuthor(authorName);
        final String tagsString = article.getString(Article.ARTICLE_TAGS_REF);
        final String[] tagStrings = tagsString.split(",");
        for (final String tagString : tagStrings) {
            final Category catetory = new Category();
            ret.addCatetory(catetory);
            catetory.setTerm(tagString);
        }

        return ret;
    }

    /**
     * Blog articles RSS output.
     *
     * @param context the specified context
     */
    @RequestProcessing(value = "/rss.xml", method = {HttpMethod.GET, HttpMethod.HEAD})
    public void blogArticlesRSS(final RequestContext context) {
        try {
            final JSONObject preference = optionQueryService.getPreference();
            if (null == preference) {
                context.sendError(404);

                return;
            }

            final SyndicationCacheService.Snapshot snapshot = syndicationCacheService != null
                    ? syndicationCacheService.getOrCompute(SyndicationCacheService.KEY_RSS, () -> buildRssXml(preference))
                    : SyndicationCacheService.Snapshot.of(buildRssXml(preference));

            HttpCompression.ensureVaryAcceptEncoding(context.getResponse());
            if (SyndicationCacheService.tryServeNotModified(context, snapshot, RSS_CONTENT_TYPE)) {
                return;
            }

            final RssRenderer renderer = new RssRenderer();
            renderer.setContent(snapshot.xml());
            context.setRenderer(renderer);
            if ("HEAD".equalsIgnoreCase(context.method())) {
                context.getResponse().sendBytes(EMPTY_BYTES);
            } else if (PublicResourceHandler.acceptsGzip(context.header("Accept-Encoding"))) {
                HttpCompression.sendText(context, snapshot.xml(), RSS_CONTENT_TYPE);
            } else {
                renderer.render(context);
            }
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Get blog article rss error", e);

            context.sendError(503);
        }
    }

    private String buildRssXml(final JSONObject preference) throws Exception {
        final Channel channel = new Channel();
        final String blogTitle = preference.getString(Option.ID_C_BLOG_TITLE);
        final String blogSubtitle = preference.getString(Option.ID_C_BLOG_SUBTITLE);
        final int outputCnt = preference.getInt(Option.ID_C_FEED_OUTPUT_CNT);
        final boolean isFullContent = "fullContent".equals(preference.optString(Option.ID_C_FEED_OUTPUT_MODE));

        channel.setTitle(blogTitle);
        channel.setLastBuildDate(new Date());
        channel.setLink(Latkes.getServePath());
        channel.setAtomLink(Latkes.getServePath() + "/rss.xml");
        channel.setGenerator("Volo, v" + SoloApplication.VERSION + ", https://github.com/Willh92/Volo-solo");
        final String localeString = preference.getString(Option.ID_C_LOCALE_STRING);
        final String country = Locales.getCountry(localeString).toLowerCase();
        final String language = Locales.getLanguage(localeString).toLowerCase();
        channel.setLanguage(language + '-' + country);
        channel.setDescription(blogSubtitle);

        final List<Filter> filters = new ArrayList<>();
        filters.add(new PropertyFilter(Article.ARTICLE_STATUS, FilterOperator.EQUAL, Article.ARTICLE_STATUS_C_PUBLISHED));
        filters.add(new PropertyFilter(Article.ARTICLE_VIEW_PWD, FilterOperator.EQUAL, ""));
        final Query query = new Query()
                .setPageCount(1)
                .setPage(1, outputCnt)
                .setFilter(new CompositeFilter(CompositeFilterOperator.AND, filters))
                .addSort(Article.ARTICLE_UPDATED, SortDirection.DESCENDING);
        if (isFullContent) {
            query.select(Keys.OBJECT_ID,
                    Article.ARTICLE_TITLE,
                    Article.ARTICLE_CONTENT,
                    Article.ARTICLE_UPDATED,
                    Article.ARTICLE_PERMALINK,
                    Article.ARTICLE_AUTHOR_ID,
                    Article.ARTICLE_TAGS_REF,
                    Article.ARTICLE_AUDIO_URL,
                    Article.ARTICLE_AUDIO_SIZE,
                    Article.ARTICLE_STATUS,
                    Article.ARTICLE_VIEW_PWD);
        } else {
            query.select(Keys.OBJECT_ID,
                    Article.ARTICLE_TITLE,
                    Article.ARTICLE_ABSTRACT,
                    Article.ARTICLE_UPDATED,
                    Article.ARTICLE_PERMALINK,
                    Article.ARTICLE_AUTHOR_ID,
                    Article.ARTICLE_TAGS_REF,
                    Article.ARTICLE_AUDIO_URL,
                    Article.ARTICLE_AUDIO_SIZE,
                    Article.ARTICLE_STATUS,
                    Article.ARTICLE_VIEW_PWD);
        }
        final JSONObject articleResult = articleRepository.get(query);
        final JSONArray articles = QueryResults.array(articleResult);
        for (int i = 0; i < articles.length(); i++) {
            final JSONObject article = articles.optJSONObject(i);
            if (article == null
                    || article.optInt(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED) != Article.ARTICLE_STATUS_C_PUBLISHED
                    || StringUtils.isNotBlank(article.optString(Article.ARTICLE_VIEW_PWD))) {
                continue;
            }
            final Item item = getItem(articles, isFullContent, i);
            channel.addItem(item);
        }

        return channel.toString();
    }

    private Item getItem(final JSONArray articles, final boolean isFullContent, int i) throws JSONException, ServiceException {
        final JSONObject article = articles.getJSONObject(i);
        final Item ret = new Item();
        String title = article.getString(Article.ARTICLE_TITLE);
        title = EmojiParser.parseToAliases(title);
        ret.setTitle(title);
        String description = isFullContent
                ? article.getString(Article.ARTICLE_CONTENT)
                : article.optString(Article.ARTICLE_ABSTRACT);
        description = SeriesAndBookshelfService.stripForFeed(
                HiddenContentService.stripForFeed(description));
        final JSONObject author = articleQueryService.getAuthor(article);
        description = EmojiParser.parseToAliases(description);
        description = Markdowns.toHTML(description);
        if (!Role.ADMIN_ROLE.equals(author.optString(User.USER_ROLE))) {
            description = Markdowns.clean(description);
        }
        ret.setDescription(description);
        final long pubDate = article.getLong(Article.ARTICLE_UPDATED);
        ret.setPubDate(new Date(pubDate));
        final String link = Latkes.getServePath() + article.getString(Article.ARTICLE_PERMALINK);
        ret.setLink(link);
        ret.setGUID(link);
        final String authorName = author.getString(User.USER_NAME);
        ret.setAuthor(authorName);
        final String rawAudioUrl = StringUtils.trimToEmpty(article.optString(Article.ARTICLE_AUDIO_URL));
        if (ReadingStatsUtil.isSafeAudioUrl(rawAudioUrl)) {
            final String fullAudioUrl = rawAudioUrl.startsWith("/")
                    ? (Latkes.getServePath() + rawAudioUrl)
                    : rawAudioUrl;
            final long audioSize = Math.max(0L, article.optLong(Article.ARTICLE_AUDIO_SIZE, 0L));
            final String audioMime = ReadingStatsUtil.inferAudioMimeType(fullAudioUrl);
            ret.setEnclosure(fullAudioUrl, audioSize, audioMime);
        }
        final String tagsString = article.getString(Article.ARTICLE_TAGS_REF);
        final String[] tagStrings = tagsString.split(",");
        for (final String tagString : tagStrings) {
            final org.b3log.solo.model.rss.Category catetory = new org.b3log.solo.model.rss.Category();
            ret.addCatetory(catetory);
            catetory.setTerm(tagString);
        }

        return ret;
    }
}
