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
package org.b3log.solo.rss;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.b3log.latke.Keys;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Common;
import org.b3log.solo.model.Option;
import org.b3log.solo.util.Markdowns;
import org.b3log.solo.util.OutboundUrls;
import org.b3log.solo.util.Solos;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

import com.rometools.rome.feed.synd.SyndEnclosure;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;

public class RssParser {
    private static final int MAX_FEED_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ITEMS_PER_FEED = 50;
    private String rssUrl;
    private String userIcon;
    private String userName;
    private boolean allowLoopback;

    public RssParser(final String rssUrl, final String userIcon, final String userName) {
        this(rssUrl, userIcon, userName, false);
    }

    public RssParser(final String rssUrl, final String userIcon, final String userName, final boolean allowLoopback) {
        this.rssUrl = rssUrl;
        this.userIcon = OutboundUrls.isOptionalSafeIconOrAvatar(userIcon) ? StringUtils.trimToEmpty(userIcon) : "";
        this.userName = plainText(userName);
        this.allowLoopback = allowLoopback;
    }

    private RssParser() {
    }

    private static String plainText(final String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return Jsoup.clean(value, Safelist.none()).trim();
    }

    private static String sanitizeHtml(final String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        return Markdowns.clean(html);
    }

    public List<JSONObject> parse2Article() {
        try {
            final byte[] feedBytes = OutboundUrls.fetchBytes(
                    this.rssUrl,
                    3000,
                    5000,
                    MAX_FEED_BYTES,
                    "application/rss+xml, application/atom+xml, application/xml, text/xml",
                    Solos.USER_AGENT,
                    this.allowLoopback);
            return parseFeedBytes(feedBytes);
        } catch (final Exception e) {
            throw new IllegalStateException("Unable to read RSS/Atom feed: " + e.getMessage(), e);
        }
    }

    public List<JSONObject> parseFeedBytes(final byte[] feedBytes) throws Exception {
        final SyndFeedInput input = new SyndFeedInput();
        input.setAllowDoctypes(false);
        final List<JSONObject> articles = new ArrayList<>();
        try (XmlReader reader = new XmlReader(new ByteArrayInputStream(feedBytes))) {
            final SyndFeed feed = input.build(reader);
            final String rawFeedTitle = plainText(feed.getTitle());
            final String feedTitle = rawFeedTitle.isEmpty() ? this.userName : rawFeedTitle;
            String feedDesc = plainText(feed.getDescription());
            if (feedDesc.isEmpty()) {
                feedDesc = feedTitle;
            }
            Instant now = Instant.now();
            for (final SyndEntry entry : feed.getEntries()) {
                if (articles.size() >= MAX_ITEMS_PER_FEED) {
                    break;
                }
                final String rawTitle = entry.getTitle() == null ? entry.getLink() : entry.getTitle();
                final String title = plainText(rawTitle);
                if (title.isBlank()) {
                    continue;
                }
                final String parsedAuthor = plainText(entry.getAuthor());
                final String author = parsedAuthor.isEmpty() ? this.userName : parsedAuthor;
                final String description = entry.getDescription() == null || entry.getDescription().getValue() == null
                        ? "" : sanitizeHtml(entry.getDescription().getValue());
                final String entryLink = OutboundUrls.isHttpOrHttps(entry.getLink()) ? entry.getLink().trim() : "";
                final String safeObjectId = "rss-" + DigestUtils.sha256Hex(
                        StringUtils.defaultString(this.rssUrl) + "|" + entryLink + "|" + title).substring(0, 24);
                final JSONObject article = new JSONObject();
                article.put(Option.ID_C_BLOG_TITLE, feedTitle);
                article.put(Option.ID_C_BLOG_SUBTITLE, feedDesc);
                article.put(Common.AUTHOR_ID, "");
                article.put(Article.ARTICLE_T_TOC, (Object) Collections.emptyList());
                article.put(Article.ARTICLE_AUTHOR_ID, this.userName);
                article.put(Keys.OBJECT_ID, safeObjectId);
                article.put(Common.AUTHOR_NAME, author);
                article.put(Article.ARTICLE_TITLE, title);
                article.put(Article.ARTICLE_PUT_TOP, false);
                article.put(Option.ID_C_COMMENTABLE, false);
                article.put(Common.ARTICLE_SIGN, new JSONObject().put("signHTML", ""));
                article.put(Article.ARTICLE_TAGS_REF, entry.getCategories().stream()
                        .map(category -> plainText(category.getName()))
                        .filter(name -> !name.isEmpty())
                        .reduce((a, b) -> a + "," + b).orElse(""));
                article.put(Common.HAS_UPDATED, false);
                if (Objects.nonNull(feed.getImage()) && OutboundUrls.isOptionalSafeIconOrAvatar(feed.getImage().getUrl())) {
                    article.put(Common.AUTHOR_THUMBNAIL_URL, feed.getImage().getUrl().trim());
                } else {
                    article.put(Common.AUTHOR_THUMBNAIL_URL, this.userIcon);
                }
                article.put(Article.ARTICLE_PERMALINK,
                        String.format("/follow/%s/article/%s", this.userName, title));
                article.put("isRss", false);
                if (null == entry.getContents() || entry.getContents().isEmpty()) {
                    article.put(Article.ARTICLE_CONTENT, description);
                    article.put(Article.ARTICLE_ABSTRACT, Article.getAbstractText(description));
                    article.put(Article.ARTICLE_ABSTRACT_TEXT, article.getString(Article.ARTICLE_ABSTRACT));
                    if (!isRichContent(description) && !entryLink.isEmpty()) {
                        article.put("isRss", true);
                        article.put(Article.ARTICLE_PERMALINK, entryLink);
                    }
                } else {
                    final String safeContent = sanitizeHtml(entry.getContents().get(0).getValue());
                    article.put(Article.ARTICLE_CONTENT, safeContent);
                    if (entry.getDescription() == null || entry.getDescription().getValue() == null) {
                        article.put(Article.ARTICLE_ABSTRACT, Article.getAbstractText(safeContent));
                        article.put(Article.ARTICLE_ABSTRACT_TEXT, article.getString(Article.ARTICLE_ABSTRACT));
                    } else {
                        article.put(Article.ARTICLE_ABSTRACT, Article.getAbstractText(article));
                        article.put(Article.ARTICLE_ABSTRACT_TEXT, article.getString(Article.ARTICLE_ABSTRACT));
                    }
                }
                Date time;
                if (null == entry.getPublishedDate()) {
                    time = entry.getUpdatedDate();
                } else {
                    time = entry.getPublishedDate();
                }
                if (time == null) {
                    time = new Date(now.toEpochMilli());
                    now = now.minusSeconds(600);
                }
                // 设置文章封面
                final Optional<SyndEnclosure> optionalEnclosure = entry.getEnclosures().stream()
                        .filter(enclosure -> enclosure.getType() != null
                                && enclosure.getType().startsWith("image/")
                                && OutboundUrls.isOptionalSafeIconOrAvatar(enclosure.getUrl()))
                        .findFirst();
                if (optionalEnclosure.isPresent()) {
                    article.put(Article.ARTICLE_IMG1_URL, optionalEnclosure.get().getUrl().trim());
                } else {
                    final String contentFirstImageUrl = Article.getArticleImg1URLWithoutSetSize(article);
                    final String articleCoverUrl = contentFirstImageUrl == null ? this.userIcon : contentFirstImageUrl;
                    article.put(Article.ARTICLE_IMG1_URL, articleCoverUrl);
                }
                article.put(Article.ARTICLE_COVER_URL, Article.getArticleCoverURL(article.optString(Article.ARTICLE_CONTENT)));
                article.put(Article.ARTICLE_CREATED, time.getTime());
                article.put(Article.ARTICLE_UPDATED, article.getLong(Article.ARTICLE_CREATED));
                article.put(Article.ARTICLE_VIEW_PWD, "");
                article.put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED);
                article.put(Common.POST_TO_COMMUNITY, false);
                article.put(Article.ARTICLE_COMMENTABLE, false);
                article.put(Article.ARTICLE_VIEW_COUNT, 0);
                article.put(Article.ARTICLE_COMMENT_COUNT, 0);
                article.put(Article.ARTICLE_T_CREATE_DATE, new Date(article.optLong(Article.ARTICLE_CREATED)));
                article.put(Article.ARTICLE_T_UPDATE_DATE, new Date(article.optLong(Article.ARTICLE_UPDATED)));
                article.put("articleCategory", "");
                articles.add(article);
            }
        }
        return articles;
    }

    /**
     * 判断是否是“富内容”，即内容是否像是正文而不是摘要
     */
    public static boolean isRichContent(final String html) {
        if (html == null || html.trim().isEmpty()) {
            return false;
        }

        final Document doc = Jsoup.parse(html);
        final String text = doc.text();
        final int textLength = text.length();

        final boolean hasLongText = textLength > 200;
        final boolean hasParagraphs = doc.select("p").size() >= 3;
        final boolean hasImages = doc.select("img").size() > 1;
        final boolean hasHeaders = doc.select("h1,h2,h3").size() > 0;

        return hasLongText && (hasParagraphs || hasImages || hasHeaders);
    }
}
