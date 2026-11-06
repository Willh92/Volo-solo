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
package org.b3log.solo.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import org.b3log.latke.Latkes;

final class LatkeTestSupport {
    static void configure() throws Exception {
        Latkes.setLocale(Locale.SIMPLIFIED_CHINESE);
        final Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("src/test/resources/latke.properties"))) { properties.load(reader); }
        Latkes.setLatkeProps(properties);
        final Properties local = new Properties();
        local.setProperty("cookieHttpOnly", "true");
        local.setProperty("loginTrustedProxies", "127.0.0.1,::1");
        Latkes.setLocalProps(local);
        Latkes.setContextPath("");
    }
}
