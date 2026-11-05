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
    private volatile Path realRoot;
    public WebResources(final Path root) { this.root = root.toAbsolutePath().normalize(); }
    public Path root() { return root; }
    public Path realRoot() throws IOException {
        final Path cached = realRoot;
        if (cached != null) {
            return cached;
        }
        if (!Files.exists(root)) {
            return root;
        }
        final Path resolved = root.toRealPath();
        realRoot = resolved;
        return resolved;
    }
    public Path resolve(final String name) {
        if (name == null) {
            throw new IllegalArgumentException("Resource name must not be null");
        }
        final int len = name.length();
        int start = 0;
        while (start < len && name.charAt(start) == '/') {
            start++;
        }
        if (start == len) {
            return root;
        }
        int depth = 0;
        boolean needsNormalize = false;
        int segStart = start;
        for (int i = start; i <= len; i++) {
            final char c = i < len ? name.charAt(i) : '/';
            if (c == '\0' || c == '\\' || c == ':') {
                throw new IllegalArgumentException("Resource escapes public directory");
            }
            if (c == '/') {
                final int segLen = i - segStart;
                if (segLen == 0) {
                    needsNormalize = true;
                } else if (segLen == 1 && name.charAt(segStart) == '.') {
                    needsNormalize = true;
                } else if (segLen == 2 && name.charAt(segStart) == '.' && name.charAt(segStart + 1) == '.') {
                    if (--depth < 0) {
                        throw new IllegalArgumentException("Resource escapes public directory");
                    }
                    needsNormalize = true;
                } else {
                    depth++;
                }
                segStart = i + 1;
            }
        }
        final String relative = start == 0 ? name : name.substring(start);
        Path file;
        try {
            file = root.resolve(relative);
        } catch (final InvalidPathException e) {
            throw new IllegalArgumentException("Resource escapes public directory", e);
        }
        if (needsNormalize) {
            file = file.normalize();
        }
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("Resource escapes public directory");
        }
        return file;
    }
    public String getRealPath(final String name) { return resolve(name).toString(); }
    public URL getResource(final String name) throws java.net.MalformedURLException { return resolve(name).toUri().toURL(); }
    public InputStream getResourceAsStream(final String name) {
        try {
            final Path file = resolve(name);
            if (file == null || !Files.isRegularFile(file)) {
                return null;
            }
            return Files.newInputStream(file);
        } catch (final IOException | IllegalArgumentException e) {
            return null;
        }
    }
    public Set<String> getResourcePaths(final String name) {
        final Set<String> paths = new TreeSet<>();
        try (var files = Files.list(resolve(name))) {
            files.forEach(file -> paths.add("/" + root.relativize(file).toString().replace('\\','/') + (Files.isDirectory(file) ? "/" : "")));
        } catch (IOException e) { return Collections.emptySet(); }
        return paths;
    }
}
