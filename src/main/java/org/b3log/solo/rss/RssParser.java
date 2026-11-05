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
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.b3log.latke.Keys;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Common;
import org.b3log.solo.model.Option;
import org.b3log.solo.util.Images;
import org.b3log.solo.util.Markdowns;
import org.b3log.solo.util.OutboundUrls;
import org.b3log.solo.util.Solos;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
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
                final Document contentDoc;
                if (null == entry.getContents() || entry.getContents().isEmpty()) {
                    contentDoc = description.isEmpty() ? null : Jsoup.parseBodyFragment(description);
                    final String descText = contentDoc == null ? "" : contentDoc.text();
                    final String abstractText = truncateAbstract(description, descText);
                    article.put(Article.ARTICLE_CONTENT, description);
                    article.put(Article.ARTICLE_ABSTRACT, abstractText);
                    article.put(Article.ARTICLE_ABSTRACT_TEXT, abstractText);
                    if (!isRichContent(contentDoc, descText) && !entryLink.isEmpty()) {
                        article.put("isRss", true);
                        article.put(Article.ARTICLE_PERMALINK, entryLink);
                    }
                } else {
                    final String safeContent = sanitizeHtml(entry.getContents().get(0).getValue());
                    contentDoc = safeContent.isEmpty() ? null : Jsoup.parseBodyFragment(safeContent);
                    article.put(Article.ARTICLE_CONTENT, safeContent);
                    if (entry.getDescription() == null || entry.getDescription().getValue() == null || description.isBlank()) {
                        final String contentText = contentDoc == null ? "" : contentDoc.text();
                        final String abstractText = truncateAbstract(safeContent, contentText);
                        article.put(Article.ARTICLE_ABSTRACT, abstractText);
                        article.put(Article.ARTICLE_ABSTRACT_TEXT, abstractText);
                    } else {
                        final Document descDoc = description.equals(safeContent)
                                ? contentDoc : Jsoup.parseBodyFragment(description);
                        final String descText = descDoc == null ? "" : descDoc.text();
                        final String abstractText = truncateAbstract(description, descText);
                        article.put(Article.ARTICLE_ABSTRACT, abstractText);
                        article.put(Article.ARTICLE_ABSTRACT_TEXT, abstractText);
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
                // 设置文章封面（单次 DOM 提取复用）
                final String firstContentImg = firstSafeImageUrl(contentDoc);
                final Optional<SyndEnclosure> optionalEnclosure = entry.getEnclosures().stream()
                        .filter(enclosure -> enclosure.getType() != null
                                && enclosure.getType().startsWith("image/")
                                && OutboundUrls.isOptionalSafeIconOrAvatar(enclosure.getUrl()))
                        .findFirst();
                if (optionalEnclosure.isPresent()) {
                    article.put(Article.ARTICLE_IMG1_URL, optionalEnclosure.get().getUrl().trim());
                } else {
                    final String contentFirstImageUrl = firstContentImg.isEmpty()
                            ? Images.imageSize(Images.randImage(), Article.ARTICLE_THUMB_IMG_WIDTH, Article.ARTICLE_THUMB_IMG_HEIGHT)
                            : firstContentImg;
                    article.put(Article.ARTICLE_IMG1_URL, contentFirstImageUrl == null ? this.userIcon : contentFirstImageUrl);
                }
                article.put(Article.ARTICLE_COVER_URL, firstContentImg.isEmpty()
                        ? ""
                        : Images.imageSize(firstContentImg, Article.ARTICLE_THUMB_IMG_WIDTH, Article.ARTICLE_THUMB_IMG_HEIGHT));
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

    private static String truncateAbstract(final String rawHtml, final String extractedText) {
        if (rawHtml != null && rawHtml.contains(":::")) {
            return Article.getAbstractText(rawHtml);
        }
        if (extractedText == null || extractedText.isEmpty()) {
            return "";
        }
        return extractedText.length() > Article.ARTICLE_ABSTRACT_LENGTH
                ? extractedText.substring(0, Article.ARTICLE_ABSTRACT_LENGTH) + "...."
                : extractedText;
    }

    private static String firstSafeImageUrl(final Document doc) {
        if (doc == null) {
            return "";
        }
        for (final Element image : doc.select("img[src]")) {
            final String source = image.attr("src").trim();
            if (source.isBlank() || source.contains("\\") || source.chars().anyMatch(Character::isISOControl)) {
                continue;
            }
            try {
                final URI uri = URI.create(source);
                final String scheme = uri.getScheme();
                if (scheme != null && !scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
                    continue;
                }
                if (scheme == null && source.startsWith("//")) {
                    continue;
                }
                if (source.startsWith("#") || source.startsWith("?")
                        || uri.getPath() == null || uri.getPath().isEmpty()
                        || uri.getPath().toLowerCase(Locale.ROOT).endsWith(".ico")) {
                    continue;
                }
                return source;
            } catch (final IllegalArgumentException ignored) {
                // Malformed or non-image URLs do not become cover sources.
            }
        }
        return "";
    }

    /**
     * 判断是否是“富内容”，即内容是否像是正文而不是摘要
     */
    public static boolean isRichContent(final String html) {
        if (html == null || html.trim().isEmpty()) {
            return false;
        }
        return isRichContent(Jsoup.parseBodyFragment(html), null);
    }

    private static boolean isRichContent(final Document doc, final String precomputedText) {
        if (doc == null) {
            return false;
        }
        final String text = precomputedText != null ? precomputedText : doc.text();
        if (text.length() <= 200) {
            return false;
        }
        if (doc.selectFirst("h1,h2,h3") != null) {
            return true;
        }
        if (doc.select("p").size() >= 3) {
            return true;
        }
        return doc.select("img").size() > 1;
    }
}
