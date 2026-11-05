/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.service;

import org.apache.commons.lang3.StringUtils;
import org.b3log.latke.Keys;
import org.b3log.latke.http.Request;
import org.b3log.latke.http.RequestContext;
import org.b3log.latke.http.Session;
import org.b3log.latke.service.annotation.Service;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Common;
import org.b3log.solo.util.Markdowns;
import org.b3log.solo.util.Solos;
import org.json.JSONObject;
import org.owasp.encoder.Encode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-side partial hidden content processor ({@code :::lock ... :::}).
 * Ensures locked blocks never leak through HTML, Markdown cache, abstracts, cover images, search, or RSS/Atom feeds.
 *
 * @author <a href="https://github.com/Willh92">Willh92</a>
 * @since 3.0.0
 */
@Service
public class HiddenContentService {

    public static final String LOCK_TYPE_USER = "user";
    public static final String LOCK_TYPE_PWD = "pwd";

    private static final Pattern ATTR_PATTERN = Pattern.compile("([a-zA-Z]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s:]+))");
    private static final Pattern CODE_FENCE_PATTERN = Pattern.compile("^\\s*(`{3,}|~{3,})");
    private static final Pattern LOCK_OPEN_PATTERN = Pattern.compile("^\\s*:::lock(?:\\s+(.*?))?(?:\\s*:::)?\\s*$");
    private static final Pattern LOCK_CLOSE_PATTERN = Pattern.compile("^\\s*:::\\s*$");

    public record LockedBlock(int index, String type, String password, String hint, String innerMarkdown) {
    }

    public record UnlockAttemptResult(boolean found, boolean matched, String passwordDigest, String html) {
    }

    private record ParseResult(String markdownWithTokens, String tokenPrefix, List<LockedBlock> blocks) {
    }

    public static boolean containsLockSyntax(final String markdown) {
        return markdown != null && markdown.contains(":::lock");
    }

    public static String sessionBlockKey(final String articleId, final int blockIndex) {
        return "_block:" + articleId + ":" + blockIndex;
    }

    public static String sha256Hex(final String value) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (final Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Strips all {@code :::lock ... :::} blocks and bookshelf shortcodes completely for excerpts, plain-text abstracts, cover image extraction, and search indexing.
     */
    public static String stripForExcerptOrImage(final String rawMarkdown) {
        return SeriesAndBookshelfService.stripForExcerpt(replaceLockedBlocks(rawMarkdown, ""));
    }

    /**
     * Replaces all {@code :::lock ... :::} blocks with a safe notice for RSS/Atom feeds.
     */
    public static String stripForFeed(final String rawMarkdown) {
        return replaceLockedBlocks(rawMarkdown, "\n\n> 此处包含受保护的隐藏内容，请前往原文查看。\n\n");
    }

    /**
     * Verifies whether a keyword matches public (unlocked) fields of an article so SQL LIKE queries cannot act as an oracle on locked content.
     */
    public static boolean matchesPublicSearch(final JSONObject article, final String keyword) {
        if (article == null || StringUtils.isBlank(keyword)) {
            return false;
        }
        final String needle = keyword.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return false;
        }
        final String title = article.optString(Article.ARTICLE_TITLE, "").toLowerCase(Locale.ROOT);
        if (title.contains(needle)) {
            return true;
        }
        final String tags = article.optString(Article.ARTICLE_TAGS_REF, "").toLowerCase(Locale.ROOT);
        if (tags.contains(needle)) {
            return true;
        }
        if (StringUtils.isNotBlank(article.optString(Article.ARTICLE_VIEW_PWD))) {
            return false;
        }
        final String publicAbstract = stripForExcerptOrImage(article.optString(Article.ARTICLE_ABSTRACT, "")).toLowerCase(Locale.ROOT);
        if (publicAbstract.contains(needle)) {
            return true;
        }
        final String publicBody = stripForExcerptOrImage(article.optString(Article.ARTICLE_CONTENT, "")).toLowerCase(Locale.ROOT);
        return publicBody.contains(needle);
    }

    private static String replaceLockedBlocks(final String rawMarkdown, final String replacement) {
        if (!containsLockSyntax(rawMarkdown)) {
            return rawMarkdown == null ? "" : rawMarkdown;
        }
        final ParseResult parsed = parseBlocks(rawMarkdown);
        if (parsed.blocks().isEmpty()) {
            return rawMarkdown;
        }
        String result = parsed.markdownWithTokens();
        for (final LockedBlock block : parsed.blocks()) {
            final String token = tokenFor(parsed.tokenPrefix(), block.index());
            result = result.replace(token, replacement);
        }
        return result;
    }

    /**
     * Renders Markdown while enforcing partial hidden content rules for the active viewer.
     */
    public String renderMarkdown(final String articleId, final String rawMarkdown, final boolean adminAuthor,
                                 final RequestContext context, final boolean editorPreview) {
        if (StringUtils.isEmpty(rawMarkdown)) {
            return "";
        }
        if (!containsLockSyntax(rawMarkdown)) {
            String html = Markdowns.toHTML(rawMarkdown);
            return adminAuthor ? html : Markdowns.clean(html);
        }

        final ParseResult parsed = parseBlocks(rawMarkdown);
        if (parsed.blocks().isEmpty()) {
            String html = Markdowns.toHTML(rawMarkdown);
            return adminAuthor ? html : Markdowns.clean(html);
        }

        String outerHtml = Markdowns.toHTML(parsed.markdownWithTokens());
        if (!adminAuthor) {
            outerHtml = Markdowns.clean(outerHtml);
        }

        final boolean adminOrAuthorViewer = editorPreview || (context != null && Solos.isAdminOrAuthorLoggedIn(context));
        final boolean loggedInViewer = adminOrAuthorViewer || (context != null && Solos.isLoggedIn(context));
        boolean anyViewerSpecificState = false;

        for (final LockedBlock block : parsed.blocks()) {
            final boolean unlocked;
            if (adminOrAuthorViewer) {
                unlocked = true;
            } else if (LOCK_TYPE_PWD.equals(block.type())) {
                unlocked = isPwdBlockUnlockedInSession(context, articleId, block.index(), block.password());
            } else {
                unlocked = loggedInViewer;
            }
            if (unlocked) {
                anyViewerSpecificState = true;
            }
            final String replacementHtml = unlocked
                    ? renderUnlockedBlockHtml(block, adminAuthor, editorPreview)
                    : renderLockedCardHtml(articleId, block);
            final String token = tokenFor(parsed.tokenPrefix(), block.index());
            outerHtml = replaceTokenInHtml(outerHtml, token, replacementHtml);
        }

        if (!editorPreview && context != null && (anyViewerSpecificState || loggedInViewer)) {
            context.setHeader("Cache-Control", "private, no-store");
        }
        return outerHtml;
    }

    /**
     * Validates a password attempt against a specific locked block and returns rendered HTML on success.
     */
    public UnlockAttemptResult verifyAndRenderPwdBlock(final String rawMarkdown, final int blockIndex,
                                                       final String candidatePassword, final boolean adminAuthor) {
        if (!containsLockSyntax(rawMarkdown) || blockIndex < 0 || candidatePassword == null) {
            return new UnlockAttemptResult(false, false, "", "");
        }
        final ParseResult parsed = parseBlocks(rawMarkdown);
        if (blockIndex >= parsed.blocks().size()) {
            return new UnlockAttemptResult(false, false, "", "");
        }
        final LockedBlock block = parsed.blocks().get(blockIndex);
        if (!LOCK_TYPE_PWD.equals(block.type()) || block.password().isEmpty()) {
            return new UnlockAttemptResult(false, false, "", "");
        }
        final String expectedDigest = sha256Hex(block.password());
        final String actualDigest = sha256Hex(candidatePassword);
        final boolean matched = MessageDigest.isEqual(
                expectedDigest.getBytes(StandardCharsets.UTF_8),
                actualDigest.getBytes(StandardCharsets.UTF_8));
        if (!matched) {
            return new UnlockAttemptResult(true, false, "", "");
        }
        final String html = renderUnlockedBlockHtml(block, adminAuthor, false);
        return new UnlockAttemptResult(true, true, expectedDigest, html);
    }

    private static boolean isPwdBlockUnlockedInSession(final RequestContext context, final String articleId,
                                                       final int blockIndex, final String expectedPassword) {
        if (context == null || StringUtils.isBlank(articleId) || StringUtils.isEmpty(expectedPassword)) {
            return false;
        }
        final Request request = context.getRequest();
        if (request == null) {
            return false;
        }
        final Session session = request.getSession();
        if (session == null) {
            return false;
        }
        final Map<String, String> viewPwds = Solos.viewPasswords(session);
        if (viewPwds == null) {
            return false;
        }
        final String storedDigest = viewPwds.get(sessionBlockKey(articleId, blockIndex));
        if (StringUtils.isBlank(storedDigest)) {
            return false;
        }
        final String expectedDigest = sha256Hex(expectedPassword);
        return MessageDigest.isEqual(
                expectedDigest.getBytes(StandardCharsets.UTF_8),
                storedDigest.getBytes(StandardCharsets.UTF_8));
    }

    private static String renderUnlockedBlockHtml(final LockedBlock block, final boolean adminAuthor, final boolean editorPreview) {
        String innerHtml = Markdowns.toHTML(block.innerMarkdown());
        if (!adminAuthor) {
            innerHtml = Markdowns.clean(innerHtml);
        }
        final String badgeLabel;
        if (editorPreview) {
            badgeLabel = LOCK_TYPE_PWD.equals(block.type())
                    ? "隐藏内容预览（前台需输入访问口令解锁）"
                    : "隐藏内容预览（前台需注册登录可见）";
        } else {
            badgeLabel = LOCK_TYPE_PWD.equals(block.type())
                    ? "已解锁口令保护内容"
                    : "已解锁登录用户专享内容";
        }
        return "<div class=\"volo-unlocked-block\" data-lock-type=\"" + Encode.forHtmlAttribute(block.type()) + "\">"
                + "<div class=\"volo-unlocked-block__badge\">" + Encode.forHtml(badgeLabel) + "</div>"
                + "<div class=\"volo-unlocked-block__body\">" + innerHtml + "</div>"
                + "</div>";
    }

    private static String renderLockedCardHtml(final String articleId, final LockedBlock block) {
        final String safeArticleId = Encode.forHtmlAttribute(StringUtils.defaultString(articleId));
        if (LOCK_TYPE_PWD.equals(block.type())) {
            final String hint = StringUtils.isNotBlank(block.hint())
                    ? block.hint()
                    : "请输入访问口令解锁本段隐藏内容。";
            return "<div class=\"volo-lock-card volo-lock-card--pwd\" data-article-id=\"" + safeArticleId
                    + "\" data-block-index=\"" + block.index() + "\">"
                    + "<div class=\"volo-lock-card__header\">"
                    + "<span class=\"volo-lock-card__icon\" aria-hidden=\"true\">&#128272;</span>"
                    + "<strong class=\"volo-lock-card__title\">本段内容受访问口令保护</strong>"
                    + "</div>"
                    + "<p class=\"volo-lock-card__hint\">" + Encode.forHtml(hint) + "</p>"
                    + "<div class=\"volo-lock-card__form\" role=\"group\" aria-label=\"口令解锁\">"
                    + "<input type=\"password\" class=\"volo-lock-input\" placeholder=\"请输入访问口令\" autocomplete=\"off\" maxlength=\"128\" />"
                    + "<button type=\"button\" class=\"volo-lock-btn\" data-volo-unlock-submit=\"true\">解锁查看</button>"
                    + "</div>"
                    + "<div class=\"volo-lock-card__msg\" role=\"alert\" aria-live=\"polite\"></div>"
                    + "</div>";
        }
        final String hint = StringUtils.isNotBlank(block.hint())
                ? block.hint()
                : "登录或注册本站读者账号后即可免费阅读本段完整内容。";
        return "<div class=\"volo-lock-card volo-lock-card--user\" data-article-id=\"" + safeArticleId
                + "\" data-block-index=\"" + block.index() + "\">"
                + "<div class=\"volo-lock-card__header\">"
                + "<span class=\"volo-lock-card__icon\" aria-hidden=\"true\">&#128274;</span>"
                + "<strong class=\"volo-lock-card__title\">本段内容仅对登录读者可见</strong>"
                + "</div>"
                + "<p class=\"volo-lock-card__hint\">" + Encode.forHtml(hint) + "</p>"
                + "<div class=\"volo-lock-card__actions\">"
                + "<button type=\"button\" class=\"volo-lock-btn\" data-volo-auth-trigger=\"login\">立即登录</button>"
                + "<button type=\"button\" class=\"volo-lock-btn volo-lock-btn--ghost\" data-volo-auth-trigger=\"register\">注册账号</button>"
                + "</div>"
                + "</div>";
    }

    private static String tokenFor(final String prefix, final int index) {
        return "VOLOLOCKSLOT" + prefix + "IDX" + index + "END";
    }

    private static String replaceTokenInHtml(final String html, final String token, final String replacementHtml) {
        final String pWrapped = "<p>" + token + "</p>";
        if (html.contains(pWrapped)) {
            return html.replace(pWrapped, replacementHtml);
        }
        return html.replace(token, replacementHtml);
    }

    static ParseResult parseBlocks(final String rawMarkdown) {
        final String tokenPrefix = deterministicTokenPrefix(rawMarkdown);
        final List<LockedBlock> blocks = new ArrayList<>();
        final StringBuilder out = new StringBuilder(rawMarkdown.length());
        final String[] lines = rawMarkdown.split("\r?\n", -1);

        boolean inCodeFence = false;
        char fenceChar = 0;
        int fenceLen = 0;

        boolean inLock = false;
        String lockType = LOCK_TYPE_USER;
        String lockPwd = "";
        String lockHint = "";
        final StringBuilder innerBuf = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i];
            final Matcher fenceMatcher = CODE_FENCE_PATTERN.matcher(line);
            if (fenceMatcher.find()) {
                final String ticks = fenceMatcher.group(1);
                if (!inCodeFence) {
                    inCodeFence = true;
                    fenceChar = ticks.charAt(0);
                    fenceLen = ticks.length();
                } else if (ticks.charAt(0) == fenceChar && ticks.length() >= fenceLen && line.trim().equals(ticks)) {
                    inCodeFence = false;
                }
            }

            if (!inCodeFence && !inLock) {
                final Matcher openMatcher = LOCK_OPEN_PATTERN.matcher(line);
                if (openMatcher.matches()) {
                    inLock = true;
                    lockType = LOCK_TYPE_USER;
                    lockPwd = "";
                    lockHint = "";
                    innerBuf.setLength(0);
                    final String attrStr = openMatcher.group(1);
                    if (StringUtils.isNotBlank(attrStr)) {
                        final Matcher attrMatcher = ATTR_PATTERN.matcher(attrStr);
                        while (attrMatcher.find()) {
                            final String key = attrMatcher.group(1).toLowerCase(Locale.ROOT);
                            final String val = firstNonNull(attrMatcher.group(2), attrMatcher.group(3), attrMatcher.group(4));
                            if ("type".equals(key)) {
                                lockType = LOCK_TYPE_PWD.equalsIgnoreCase(val) ? LOCK_TYPE_PWD : LOCK_TYPE_USER;
                            } else if ("pwd".equals(key) || "password".equals(key)) {
                                lockPwd = val;
                                if (!val.isEmpty()) {
                                    lockType = LOCK_TYPE_PWD;
                                }
                            } else if ("hint".equals(key) || "title".equals(key)) {
                                lockHint = val;
                            }
                        }
                    }
                    continue;
                }
            } else if (!inCodeFence && inLock) {
                if (LOCK_CLOSE_PATTERN.matcher(line).matches()) {
                    final int idx = blocks.size();
                    blocks.add(new LockedBlock(idx, lockType, lockPwd, lockHint, innerBuf.toString()));
                    if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                        out.append('\n');
                    }
                    out.append('\n').append(tokenFor(tokenPrefix, idx)).append("\n\n");
                    inLock = false;
                    continue;
                }
            }

            if (inLock) {
                if (innerBuf.length() > 0) {
                    innerBuf.append('\n');
                }
                innerBuf.append(line);
            } else {
                out.append(line);
                if (i < lines.length - 1) {
                    out.append('\n');
                }
            }
        }

        // Fail closed: if an author forgets closing :::, keep trailing content locked instead of leaking it.
        if (inLock) {
            final int idx = blocks.size();
            blocks.add(new LockedBlock(idx, lockType, lockPwd, lockHint, innerBuf.toString()));
            if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                out.append('\n');
            }
            out.append('\n').append(tokenFor(tokenPrefix, idx)).append("\n\n");
        }

        return new ParseResult(out.toString(), tokenPrefix, blocks);
    }

    private static String deterministicTokenPrefix(final String rawMarkdown) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((rawMarkdown == null ? "" : rawMarkdown).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 24).toUpperCase(Locale.ROOT);
        } catch (final Exception e) {
            return UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        }
    }

    private static String firstNonNull(final String a, final String b, final String c) {
        if (a != null) {
            return a;
        }
        if (b != null) {
            return b;
        }
        return c == null ? "" : c;
    }
}
