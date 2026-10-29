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
package org.b3log.solo.web;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.*;

/** Filesystem resources rooted in the deployed public directory. */
public final class WebResources {
    private final Path root;
    public WebResources(final Path root) { this.root = root.toAbsolutePath().normalize(); }
    public Path root() { return root; }
    public Path resolve(final String name) {
        final Path file = root.resolve(name.replaceFirst("^/+", "")).normalize();
        if (!file.startsWith(root)) throw new IllegalArgumentException("Resource escapes public directory");
        return file;
    }
    public String getRealPath(final String name) { return resolve(name).toString(); }
    public URL getResource(final String name) throws java.net.MalformedURLException { return resolve(name).toUri().toURL(); }
    public InputStream getResourceAsStream(final String name) {
        try { return Files.newInputStream(resolve(name)); } catch (IOException e) { return null; }
    }
    public Set<String> getResourcePaths(final String name) {
        final Set<String> paths = new TreeSet<>();
        try (var files = Files.list(resolve(name))) {
            files.forEach(file -> paths.add("/" + root.relativize(file).toString().replace('\\','/') + (Files.isDirectory(file) ? "/" : "")));
        } catch (IOException e) { return Collections.emptySet(); }
        return paths;
    }
}
