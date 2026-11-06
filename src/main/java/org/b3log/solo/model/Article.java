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
package org.b3log.solo.model;

import org.apache.commons.lang3.StringUtils;
import org.b3log.solo.util.Images;
import org.b3log.solo.util.Markdowns;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

/**
 * This class defines all article model relevant keys.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 0.3.1
 */
public final class Article {

    /**
     * Article.
     */
    public static final String ARTICLE = "article";

    /**
     * Articles.
     */
    public static final String ARTICLES = "articles";

    /**
     * Key of title.
     */
    public static final String ARTICLE_TITLE = "articleTitle";

    /**
     * Key of abstract.
     */
    public static final String ARTICLE_ABSTRACT = "articleAbstract";

    /**
     * Key of abstract text.
     */
    public static final String ARTICLE_ABSTRACT_TEXT = "articleAbstractText";

    /**
     * Key of content.
     */
    public static final String ARTICLE_CONTENT = "articleContent";

    /**
     * Key of created at.
     */
    public static final String ARTICLE_CREATED = "articleCreated";

    /**
     * Key of create date.
     */
    public static final String ARTICLE_T_CREATE_DATE = "articleCreateDate";

    /**
     * Key of create time.
     */
    public static final String ARTICLE_CREATE_TIME = "articleCreateTime";

    /**
     * Key of updated at.
     */
    public static final String ARTICLE_UPDATED = "articleUpdated";

    /**
     * Key of update date.
     */
    public static final String ARTICLE_T_UPDATE_DATE = "articleUpdateDate";

    /**
     * Key of update time.
     */
    public static final String ARTICLE_UPDATE_TIME = "articleUpdateTime";

    /**
     * Key of tags.
     */
    public static final String ARTICLE_TAGS_REF = "articleTags";

    /**
     * Key of categories.
     */
    public static final String CATEGORY_REF = "category";

    /**
     * Key of comment count.
     */
    public static final String ARTICLE_COMMENT_COUNT = "articleCommentCount";

    /**
     * Key of view count.
     */
    public static final String ARTICLE_VIEW_COUNT = "articleViewCount";

    /**
     * Key of comments.
     */
    public static final String ARTICLE_COMMENTS_REF = "articleComments";

    /**
     * Key of sign id.
     */
    public static final String ARTICLE_SIGN_ID = "articleSignId";

    /**
     * Key of permalink.
     */
    public static final String ARTICLE_PERMALINK = "articlePermalink";

    /**
     * Key of put top.
     */
    public static final String ARTICLE_PUT_TOP = "articlePutTop";

    /**
     * Key of author id.
     */
    public static final String ARTICLE_AUTHOR_ID = "articleAuthorId";

    /**
     * Key of random double.
     */
    public static final String ARTICLE_RANDOM_DOUBLE = "articleRandomDouble";

    /**
     * Key of comment-able.
     */
    public static final String ARTICLE_COMMENTABLE = "articleCommentable";

    /**
     * Key of view password.
     */
    public static final String ARTICLE_VIEW_PWD = "articleViewPwd";

    /**
     * Key of article image1 URL. https://github.com/b3log/solo/issues/12670
     */
    public static final String ARTICLE_IMG1_URL = "articleImg1URL";
    /** First content image for presentation, without the legacy random-image fallback. */
    public static final String ARTICLE_COVER_URL = "articleCoverURL";

    /**
     * Key of article status.
     */
    public static final String ARTICLE_STATUS = "articleStatus";

    /**
     * Key of scheduled auto-publish epoch millis (0 = not scheduled; kept as draft while > 0).
     */
    public static final String ARTICLE_SCHEDULE_TIME = "articleScheduleTime";

    /**
     * Key of optional podcast/episode audio URL.
     */
    public static final String ARTICLE_AUDIO_URL = "articleAudioURL";

    /**
     * Key of optional podcast/episode audio duration in seconds.
     */
    public static final String ARTICLE_AUDIO_DURATION = "articleAudioDuration";

    /**
     * Key of optional podcast/episode audio file size in bytes.
     */
    public static final String ARTICLE_AUDIO_SIZE = "articleAudioSize";

    //// Status constants

    /**
     * Article status - published.
     */
    public static final int ARTICLE_STATUS_C_PUBLISHED = 0;

    /**
     * Article status - draft.
     */
    public static final int ARTICLE_STATUS_C_DRAFT = 1;

    //// Transient ////
    /**
     * Key of article ToC.
     */
    public static final String ARTICLE_T_TOC = "articleToC";

    //// Other constants
    /**
     * Width of article first image.
     */
    public static final int ARTICLE_THUMB_IMG_WIDTH = 1280;
    /**
     * Height of article first image.
     */
    public static final int ARTICLE_THUMB_IMG_HEIGHT = 720;
    /**
     * Article abstract length.
     */
    public static final int ARTICLE_ABSTRACT_LENGTH = 500;

    /**
     * Private constructor.
     */
    private Article() {
    }

    /** Extracts the first safe image URL from rendered HTML without making network requests. */
    private static String firstSafeImageUrl(final String html) {
        if (html == null || !StringUtils.containsIgnoreCase(html, "<img")) {
            return "";
        }
        for (final org.jsoup.nodes.Element image : Jsoup.parseBodyFragment(html).select("img[src]")) {
            final String source = image.attr("src").trim();
            if (source.isBlank() || source.contains("\\") || source.chars().anyMatch(Character::isISOControl)) continue;
            try {
                final java.net.URI uri = java.net.URI.create(source);
                final String scheme = uri.getScheme();
                if (scheme != null && !scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) continue;
                if (scheme == null && source.startsWith("//")) continue;
                if (source.startsWith("#") || source.startsWith("?") || uri.getPath() == null || uri.getPath().isEmpty()
                        || uri.getPath().toLowerCase(java.util.Locale.ROOT).endsWith(".ico")) continue;
                return source;
            } catch (IllegalArgumentException ignored) {
                // Malformed or non-image URLs do not become cover sources.
            }
        }
        return "";
    }

    /** Finds a safe image in rendered article HTML without making network requests. */
    public static String getArticleCoverURL(final String html) {
        final String source = firstSafeImageUrl(html);
        return source.isEmpty() ? "" : Images.imageSize(source, ARTICLE_THUMB_IMG_WIDTH, ARTICLE_THUMB_IMG_HEIGHT);
    }

    /**
     * Gets the first image URL of the specified article.
     *
     * @param article the specified article
     * @return the first image URL, returns {@code ""} if not found
     */
    public static String sanitizePublicMarkdown(final String markdown) {
        final String unlocked = org.b3log.solo.service.HiddenContentService.stripForExcerptOrImage(markdown);
        return unlocked.replaceAll("(?m)^\\s*:::(?:bookshelf|book\\b[^\\r\\n]*|)\\s*$", "");
    }

    public static String getArticleImg1URL(final JSONObject article) {
        if (null == article || StringUtils.isNotBlank(article.optString(Article.ARTICLE_VIEW_PWD))) {
            return Images.imageSize(Images.randImage(), ARTICLE_THUMB_IMG_WIDTH, ARTICLE_THUMB_IMG_HEIGHT);
        }
        final String summary = sanitizePublicMarkdown(article.optString(Article.ARTICLE_ABSTRACT));
        final String body = sanitizePublicMarkdown(article.optString(Article.ARTICLE_CONTENT));
        final String html = Markdowns.toHTML(summary + "\n\n" + body);
        final String source = firstSafeImageUrl(html);
        return Images.imageSize(source.isEmpty() ? Images.randImage() : source, ARTICLE_THUMB_IMG_WIDTH, ARTICLE_THUMB_IMG_HEIGHT);
    }

    /**
     * Gets the first image URL of the specified article without setting size.
     *
     * @param article the specified article
     * @return the first image URL, returns {@code ""} if not found
     */
    public static String getArticleImg1URLWithoutSetSize(final JSONObject article) {
        if (null == article || StringUtils.isNotBlank(article.optString(Article.ARTICLE_VIEW_PWD))) {
            return Images.randImage();
        }
        final String summary = sanitizePublicMarkdown(article.optString(Article.ARTICLE_ABSTRACT));
        final String body = sanitizePublicMarkdown(article.optString(Article.ARTICLE_CONTENT));
        final String combined = summary + "\n\n" + body;
        String source = firstSafeImageUrl(combined);
        if (source.isEmpty()) {
            source = firstSafeImageUrl(Markdowns.toHTML(combined));
        }
        return source.isEmpty()
                ? Images.imageSize(Images.randImage(), ARTICLE_THUMB_IMG_WIDTH, ARTICLE_THUMB_IMG_HEIGHT)
                : source;
    }

    /**
     * Gets the abstract plain text of the specified content.
     *
     * @param content the specified content
     * @return the abstract plain text
     */
    public static String getAbstractText(final String content) {
        final String ret = Jsoup.parseBodyFragment(Markdowns.toHTML(sanitizePublicMarkdown(content))).text();
        if (ret.length() > ARTICLE_ABSTRACT_LENGTH) {
            return ret.substring(0, ARTICLE_ABSTRACT_LENGTH) + "....";
        }

        return ret;
    }

    /**
     * Gets the abstract plain text of the specified article.
     *
     * @param article the specified article
     * @return the abstract plain text
     */
    public static String getAbstractText(final JSONObject article) {
        if (null == article || StringUtils.isNotBlank(article.optString(Article.ARTICLE_VIEW_PWD))) {
            return "";
        }

        String content = article.optString(Article.ARTICLE_ABSTRACT);
        if (StringUtils.isBlank(content)) {
            content = article.optString(Article.ARTICLE_CONTENT);
        }

        return getAbstractText(content);
    }
}
