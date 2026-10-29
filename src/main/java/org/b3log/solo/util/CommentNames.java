/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.util;

import org.b3log.solo.model.Comment;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;

/** Legacy nicknames are display data, never HTML. Stored historical rows remain unchanged. */
public final class CommentNames {
    private CommentNames() { }
    private static String decoded(final String value) {
        String text = value == null ? "" : value;
        for (int i = 0; i < 2; i++) text = Parser.unescapeEntities(text, false);
        return text;
    }
    public static boolean hasMarkup(final String value) {
        return !Jsoup.parseBodyFragment(decoded(value)).body().children().isEmpty();
    }
    public static String display(final String value) {
        final String text = Jsoup.parseBodyFragment(decoded(value)).text().replaceAll("[\\p{Cc}\\p{Cf}]", "").strip();
        if (text.isEmpty()) return "访客";
        return new String(text.codePoints().limit(20).toArray(), 0, Math.min(20, text.codePointCount(0, text.length())));
    }
    public static void apply(final JSONObject comment) {
        comment.put(Comment.COMMENT_NAME, display(comment.optString(Comment.COMMENT_NAME)));
        if (!comment.optString(Comment.COMMENT_ORIGINAL_COMMENT_NAME).isBlank()) {
            comment.put(Comment.COMMENT_ORIGINAL_COMMENT_NAME, display(comment.optString(Comment.COMMENT_ORIGINAL_COMMENT_NAME)));
        }
        if (comment.has(Comment.COMMENT_URL)) comment.put(Comment.COMMENT_URL, homepage(comment.optString(Comment.COMMENT_URL)));
    }
    public static String homepage(final String value) {
        if (value == null || value.isBlank()) return "";
        try {
            final java.net.URI url = java.net.URI.create(value);
            return ("http".equalsIgnoreCase(url.getScheme()) || "https".equalsIgnoreCase(url.getScheme()))
                    && url.getRawAuthority() != null ? value : "";
        } catch (IllegalArgumentException invalid) { return ""; }
    }
}
