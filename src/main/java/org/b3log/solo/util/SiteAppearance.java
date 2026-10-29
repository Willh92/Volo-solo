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
package org.b3log.solo.util;

import java.util.Set;
import org.b3log.solo.bolo.prop.Options;

/** Validated site-wide accent. Values are palette identifiers, never arbitrary CSS. */
public final class SiteAppearance {
    public static final String OPTION = "appearanceAccent";
    private static final Set<String> ACCENTS = Set.of("forest", "ocean", "teal", "amber", "plum", "rose");
    private SiteAppearance() { }
    public static boolean valid(final String value) { return ACCENTS.contains(value == null ? "" : value); }
    public static String current() {
        final String value = Options.get(OPTION);
        return valid(value) ? value : "forest";
    }
    public static String color() {
        return switch (current()) {
            case "ocean" -> "#2d6583";
            case "teal" -> "#286c70";
            case "amber" -> "#855c27";
            case "plum" -> "#70517d";
            case "rose" -> "#87525f";
            default -> "#246b51";
        };
    }
}
