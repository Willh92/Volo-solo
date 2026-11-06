/*
 * Volo - A modern, secure, and lightweight blogging system.
 * Copyright (c) 2026-present, https://github.com/Willh92/Volo-solo
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.b3log.solo.util;

import org.apache.commons.lang3.StringUtils;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Option;
import org.json.JSONObject;
import org.owasp.encoder.Encode;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Computes word counts, estimated reading minutes, outdated technical article banners,
 * and single-episode podcast audio player widgets across all Volo skins.
 *
 * @author <a href="https://github.com/Willh92">Willh92</a>
 * @since 3.0.0
 */
public final class ReadingStatsUtil {

    private static final int WORDS_PER_MINUTE = 350;
    private static final long MILLIS_PER_DAY = 86_400_000L;
    private static final Pattern LATIN_WORD_PATTERN = Pattern.compile("[A-Za-z0-9_]+(?:[-'][A-Za-z0-9_]+)*");
    private static final Pattern CODE_FENCE_CONTENT = Pattern.compile("(?s)```.*?```|~~~.*?~~~");
    private static final Pattern MARKDOWN_IMAGE_PATTERN = Pattern.compile("!\\[[^\\]]*\\]\\([^)]*\\)");
    private static final Pattern MARKDOWN_LINK_PATTERN = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");

    private ReadingStatsUtil() {
    }

    public record Stats(int wordCount, int readingMinutes) { }

    /**
     * Calculates word count (CJK characters + Latin words) on public/sanitized Markdown.
     */
    public static Stats compute(final String rawMarkdown) {
        final String clean = Article.sanitizePublicMarkdown(rawMarkdown);
        if (StringUtils.isBlank(clean)) {
            return new Stats(0, 1);
        }
        // Keep code words counted at half weight so long code blocks don't wildly inflate reading time
        final String withoutImages = MARKDOWN_IMAGE_PATTERN.matcher(clean).replaceAll(" ");
        final String withoutLinks = MARKDOWN_LINK_PATTERN.matcher(withoutImages).replaceAll("$1");
        final String proseOnly = CODE_FENCE_CONTENT.matcher(withoutLinks).replaceAll(" ");

        int cjkCount = 0;
        for (int i = 0; i < proseOnly.length(); i++) {
            final char c = proseOnly.charAt(i);
            final Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                    || block == Character.UnicodeBlock.HIRAGANA
                    || block == Character.UnicodeBlock.KATAKANA
                    || block == Character.UnicodeBlock.HANGUL_SYLLABLES) {
                cjkCount++;
            }
        }
        int latinWords = 0;
        final Matcher matcher = LATIN_WORD_PATTERN.matcher(proseOnly);
        while (matcher.find()) {
            latinWords++;
        }

        // Count code block lines lightly
        int codeTokens = 0;
        final Matcher codeMatcher = CODE_FENCE_CONTENT.matcher(withoutLinks);
        while (codeMatcher.find()) {
            codeTokens += Math.max(1, codeMatcher.group().length() / 12);
        }

        final int total = cjkCount + latinWords + codeTokens;
        final int minutes = Math.max(1, (int) Math.ceil(total / (double) WORDS_PER_MINUTE));
        return new Stats(total, minutes);
    }

    /**
     * Checks whether an audio URL is a safe {@code http(s)} URL or clean site-relative path.
     */
    public static boolean isSafeAudioUrl(final String rawUrl) {
        if (StringUtils.isBlank(rawUrl)) {
            return false;
        }
        final String url = rawUrl.trim();
        if (url.startsWith("/") && !url.startsWith("//") && !url.contains("..") && !url.contains("\\")) {
            return true;
        }
        return OutboundUrls.isHttpOrHttps(url);
    }

    /**
     * Infers standard RSS enclosure MIME type from an audio URL.
     */
    public static String inferAudioMimeType(final String url) {
        if (StringUtils.isBlank(url)) {
            return "audio/mpeg";
        }
        final String path = StringUtils.substringBefore(url.toLowerCase(Locale.ROOT), "?");
        if (path.endsWith(".m4a") || path.endsWith(".mp4") || path.endsWith(".aac")) {
            return "audio/mp4";
        }
        if (path.endsWith(".ogg") || path.endsWith(".oga") || path.endsWith(".opus")) {
            return "audio/ogg";
        }
        if (path.endsWith(".wav")) {
            return "audio/wav";
        }
        if (path.endsWith(".webm")) {
            return "audio/webm";
        }
        return "audio/mpeg";
    }

    /**
     * Renders reading stats, outdated technical article notice, and single-episode podcast player HTML.
     */
    public static String buildArticleHeaderWidgets(final JSONObject rawArticle, final String rawMarkdown,
                                                   final JSONObject preference) {
        if (rawArticle == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(512);

        final boolean enableStats = preference == null
                || preference.optBoolean(Option.ID_C_ENABLE_READING_STATS, true);
        if (enableStats) {
            final Stats stats = compute(rawMarkdown);
            if (stats.wordCount() >= 15) {
                sb.append("<div class=\"volo-reading-meta\" aria-label=\"阅读统计\">")
                        .append("<span class=\"volo-reading-meta__item\">全文约 <strong>")
                        .append(stats.wordCount()).append("</strong> 字</span>")
                        .append("<span class=\"volo-reading-meta__sep\" aria-hidden=\"true\">&middot;</span>")
                        .append("<span class=\"volo-reading-meta__item\">预计阅读 <strong>")
                        .append(stats.readingMinutes()).append("</strong> 分钟</span>")
                        .append("</div>");
            }
        }

        final int outdatedDays = preference == null
                ? Option.DefaultPreference.DEFAULT_ARTICLE_OUTDATED_DAYS
                : preference.optInt(Option.ID_C_ARTICLE_OUTDATED_DAYS, Option.DefaultPreference.DEFAULT_ARTICLE_OUTDATED_DAYS);
        if (outdatedDays > 0) {
            final long lastTouched = Math.max(
                    rawArticle.optLong(Article.ARTICLE_UPDATED, 0L),
                    rawArticle.optLong(Article.ARTICLE_CREATED, 0L));
            if (lastTouched > 0) {
                final long elapsedDays = (System.currentTimeMillis() - lastTouched) / MILLIS_PER_DAY;
                if (elapsedDays >= outdatedDays) {
                    sb.append("<div class=\"volo-outdated-notice\" role=\"note\">")
                            .append("<strong>时效提示：</strong>本文最后更新于 <span>").append(elapsedDays)
                            .append("</span> 天前，文中涉及的技术版本、依赖接口或部署细节可能已随演进发生变化，阅读时请结合最新官方文档甄别。")
                            .append("</div>");
                }
            }
        }

        final String audioUrl = StringUtils.trimToEmpty(rawArticle.optString(Article.ARTICLE_AUDIO_URL));
        if (isSafeAudioUrl(audioUrl)) {
            final int durationSec = Math.max(0, rawArticle.optInt(Article.ARTICLE_AUDIO_DURATION, 0));
            final String durationBadge = durationSec > 0
                    ? String.format(Locale.ROOT, "%02d:%02d", durationSec / 60, durationSec % 60)
                    : "";
            sb.append("<div class=\"volo-audio-player\" data-volo-audio-player=\"true\">")
                    .append("<div class=\"volo-audio-player__head\">")
                    .append("<span class=\"volo-audio-player__label\">&#127897;&#65039; 播客音频导听</span>");
            if (!durationBadge.isEmpty()) {
                sb.append("<span class=\"volo-audio-player__duration\">").append(Encode.forHtml(durationBadge)).append("</span>");
            }
            sb.append("<button type=\"button\" class=\"volo-audio-player__speed\" data-volo-audio-speed=\"1\" title=\"切换播放倍速\">1.0x</button>")
                    .append("</div>")
                    .append("<audio class=\"volo-audio-player__el\" controls preload=\"metadata\" src=\"")
                    .append(Encode.forHtmlAttribute(audioUrl)).append("\"></audio>")
                    .append("</div>");
        }

        return sb.toString();
    }
}
