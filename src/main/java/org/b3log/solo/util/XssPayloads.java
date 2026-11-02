/* SPDX-License-Identifier: AGPL-3.0-or-later */
package org.b3log.solo.util;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.jsoup.parser.Parser;

/** High-confidence executable payloads in plain-text metadata, not a substitute for output escaping. */
public final class XssPayloads {
    private static final Pattern TAG = Pattern.compile("(?is)<\\s*(?:script|iframe|object|embed)\\b|<[^>]{0,2000}\\bon[a-z]{3,24}\\s*=|[\\\"'<>]\\s*on[a-z]{3,24}\\s*=");
    private static final Pattern URI = Pattern.compile("(?is)^(?:javascript|vbscript):|^data:(?:text/html|image/svg\\+xml)");
    private XssPayloads() { }
    public static boolean executable(final String input) {
        if (input == null || input.isBlank()) return false;
        String value = input.length() > 8192 ? input.substring(0, 8192) : input;
        for (int i = 0; i < 2; i++) {
            value = Parser.unescapeEntities(value, false);
            try { value = URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8); }
            catch (IllegalArgumentException malformed) { break; }
        }
        return TAG.matcher(value).find() || URI.matcher(value.replaceAll("[\\p{Cntrl}\\s]+", "")).find();
    }
    /** Markdown code samples are permitted; executable HTML outside code is rejected. */
    public static boolean markdown(final String value) {
        if (value == null) return false;
        return executable(value.replaceAll("(?ms)^\\s*(`{3,}|~{3,})[^\\n]*\\n.*?^\\s*\\1\\s*$", "")
                .replaceAll("(`+)[^`\\r\\n]*\\1", ""));
    }
}
