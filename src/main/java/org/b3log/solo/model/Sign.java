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

import org.owasp.encoder.Encode;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class defines all sign model relevant keys.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 */
public final class Sign {

    /**
     * Key of sign.
     */
    public static final String SIGN = "sign";

    /**
     * Key of signs.
     */
    public static final String SIGNS = "signs";

    /**
     * Key of sign HTML.
     */
    public static final String SIGN_HTML = "signHTML";

    private static final Pattern VARIABLES = Pattern.compile("\\{(title|author|url|blog)\\}");

    /** Expands plain-text variables without allowing their values to become HTML or new placeholders. */
    public static String render(final String template, final String title, final String author,
            final String url, final String blog) {
        final Map<String, String> values = Map.of("title", title, "author", author, "url", url, "blog", blog);
        final Matcher matcher = VARIABLES.matcher(template);
        final StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(Encode.forHtml(values.get(matcher.group(1)))));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Private constructor.
     */
    private Sign() {
    }
}
