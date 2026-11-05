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

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.repository.RepositoryException;
import org.b3log.latke.service.annotation.Service;
import org.b3log.latke.util.Strings;
import org.b3log.solo.logging.Logger;
import org.b3log.solo.repository.ArticleRepository;
import org.b3log.solo.repository.PageRepository;

/**
 * Permalink query service.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 0.6.1
 */
@Service
public class PermalinkQueryService {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(PermalinkQueryService.class);

    /**
     * Page repository.
     */
    @Inject
    private PageRepository pageRepository;

    /**
     * Article repository.
     */
    @Inject
    private ArticleRepository articleRepository;

    /**
     * Exact built-in routes reserved from permalink resolution.
     */
    public static final Set<String> RESERVED_EXACT_LINKS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "/", "/search", "/manifest.json", "/opensearch.xml",
            "/categories.html", "/archives.html", "/links.html", "/follows.html",
            "/dynamic.html", "/bookshelf.html", "/books", "/follows", "/usite", "/timeaxis",
            "/change-skin", "/login", "/logout", "/register", "/start", "/kill-browser", "/captcha",
            "/favicon", "/favicon.ico", "/robots.txt", "/rss.xml", "/atom.xml", "/sitemap.xml",
            "/sw.js", "/service-worker.js",
            "/article", "/articles", "/tags.html", "/tags", "/page", "/articles/random",
            "/article/comments", "/add-article-from-symphony-comment.do", "/page/comments",
            "/get-article-content", "/blog/info", "/blog/articles-tags",
            "/admin-index.do", "/admin-article.do", "/admin-article-list.do",
            "/admin-link-list.do", "/admin-follow-list.do", "/admin-preference.do", "/admin-file-list.do",
            "/admin-page-list.do", "/admin-others.do", "/admin-draft-list.do", "/admin-user-list.do",
            "/admin-plugin-list.do", "/admin-main.do", "/admin-about.do", "/admin-label"
    )));

    /**
     * Multi-segment reserved route prefixes.
     */
    private static final String[] RESERVED_PREFIXES = new String[] {
            "/articles/", "/tags/", "/archives/", "/authors/", "/category/",
            "/console/", "/admin/", "/oauth/", "/api/", "/apis/", "/plugins/", "/skins/",
            "/images/", "/image/", "/js/", "/scss/", "/github-stats/"
    };

    /**
     * Scanner probe prefixes rejected before hitting SQL.
     */
    private static final String[] SCANNER_PROBE_PREFIXES = new String[] {
            "/.env", "/.git"
    };

    /**
     * Scanner probe suffixes rejected before hitting SQL.
     */
    private static final String[] SCANNER_PROBE_SUFFIXES = new String[] {
            ".php", ".asp", ".aspx", ".jsp", ".cgi"
    };

    /**
     * Reserved permalinks (kept for API compatibility).
     */
    public static final String[] RESERVED_LINKS = RESERVED_EXACT_LINKS.toArray(new String[0]);

    private static final Pattern DEFAULT_ARTICLE_PERMALINK_PATTERN =
            Pattern.compile("/articles/(?:\\d{4}/\\d{2}/\\d{2}/)?\\d+\\.html");

    /**
     * Checks whether the specified article permalink matches the system generated
     * format pattern ("/articles/yyyy/MM/dd/${articleId}.html") or ("/articles/${articleId}.html").
     *
     * @param permalink the specified permalink
     * @return {@code true} if matches, returns {@code false} otherwise
     */
    public static boolean matchDefaultArticlePermalinkFormat(final String permalink) {
        if (null == permalink) {
            return false;
        }

        final Matcher matcher = DEFAULT_ARTICLE_PERMALINK_PATTERN.matcher(permalink);

        return matcher.matches();
    }

    /**
     * Checks whether the specified permalink is a
     * {@link #invalidArticlePermalinkFormat(java.lang.String) invalid article
     * permalink format} and {@link #invalidPagePermalinkFormat(java.lang.String)
     * invalid page permalink format}.
     *
     * @param permalink the specified permalink
     * @return {@code true} if invalid, returns {@code false} otherwise
     */
    public static boolean invalidPermalinkFormat(final String permalink) {
        return invalidArticlePermalinkFormat(permalink) && invalidPagePermalinkFormat(permalink);
    }

    /**
     * Checks whether the specified article permalink is invalid on format.
     *
     * @param permalink the specified article permalink
     * @return {@code true} if invalid, returns {@code false} otherwise
     */
    public static boolean invalidArticlePermalinkFormat(final String permalink) {
        if (StringUtils.isBlank(permalink)) {
            return true;
        }

        if (matchDefaultArticlePermalinkFormat(permalink)) {
            return false;
        }

        return invalidUserDefinedPermalinkFormat(permalink);
    }

    /**
     * Checks whether the specified page permalink is invalid on format.
     *
     * @param permalink the specified page permalink
     * @return {@code true} if invalid, returns {@code false} otherwise
     */
    public static boolean invalidPagePermalinkFormat(final String permalink) {
        if (StringUtils.isBlank(permalink)) {
            return true;
        }

        return invalidUserDefinedPermalinkFormat(permalink);
    }

    /**
     * Checks whether the specified user-defined permalink is invalid on format.
     *
     * @param permalink the specified user-defined permalink
     * @return {@code true} if invalid, returns {@code false} otherwise
     */
    private static boolean invalidUserDefinedPermalinkFormat(final String permalink) {
        if (StringUtils.isBlank(permalink)) {
            return true;
        }

        if ('/' != permalink.charAt(0) || permalink.length() <= 1 || permalink.length() > 255) {
            return true;
        }

        if (isScannerProbePath(permalink)) {
            return true;
        }

        if (isReservedLink(permalink)) {
            return true;
        }

        if (Strings.isNumeric(permalink.substring(1))) {
            // Conflict with pagination
            return true;
        }

        int slashCnt = 0;

        for (int i = 0; i < permalink.length(); i++) {
            final char c = permalink.charAt(i);
            if ('/' == c) {
                slashCnt++;
                if (slashCnt > 1) {
                    return true;
                }
            } else if (!isValidPermalinkChar(c)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isScannerProbePath(final String permalink) {
        final String lower = permalink.toLowerCase(Locale.ROOT);
        for (final String prefix : SCANNER_PROBE_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        for (final String suffix : SCANNER_PROBE_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isValidPermalinkChar(final char c) {
        if (c <= 0x20 || c == 0x7F || Character.isWhitespace(c)) {
            return false;
        }
        return '?' != c && '#' != c && '\\' != c && '<' != c && '>' != c && '"' != c;
    }

    /**
     * Determines whether the specified request URI is a reserved link.
     *
     * @param requestURI the specified request URI
     * @return {@code true} if it is a reserved link, returns {@code false} otherwise
     */
    private static boolean isReservedLink(final String requestURI) {
        if (null == requestURI) {
            return false;
        }
        if (org.b3log.solo.util.AdminPaths.reserved(requestURI)) {
            return true;
        }
        if (RESERVED_EXACT_LINKS.contains(requestURI)) {
            return true;
        }
        for (final String prefix : RESERVED_PREFIXES) {
            if (requestURI.startsWith(prefix)) {
                if ("/articles/".equals(prefix) && matchDefaultArticlePermalinkFormat(requestURI)) {
                    return false;
                }
                return true;
            }
        }

        return false;
    }

    /**
     * Determines whether the specified permalink exists.
     *
     * @param permalink the specified permalink
     * @return {@code true} if exists, returns {@code false} otherwise
     */
    public boolean exist(final String permalink) {
        try {
            return isReservedLink(permalink) || null != articleRepository.getByPermalink(permalink)
                    || null != pageRepository.getByPermalink(permalink) || permalink.endsWith(".ftl");
        } catch (final RepositoryException e) {
            LOGGER.log(Level.ERROR, "Determines whether the permalink[" + permalink + "] exists failed, returns true",
                    e);

            return true;
        }
    }
}
