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
/*
 * Bolo - A stable and beautiful blogging system based in Solo.
 * Copyright (c) 2025, https://github.com/gakkiyomi
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
package org.b3log.solo.bolo.tool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.util.zip.ZipEntry;

import org.zeroturnaround.zip.NameMapper;
import org.zeroturnaround.zip.ZipEntryCallback;
import org.zeroturnaround.zip.ZipUtil;

public class ZipFilterUtils {

    public static void unpackFilteredZip(File zipFile, File outputDir, NameMapper mapper) throws IOException {
        Files.createDirectories(outputDir.toPath());
        final Path root = outputDir.getCanonicalFile().toPath();
        final long[] expandedSize = {org.apache.commons.io.FileUtils.sizeOfDirectory(outputDir)};
        final int[] entryCount = {0};
        ZipUtil.iterate(zipFile, new ZipEntryCallback() {
            @Override
            public void process(InputStream in, ZipEntry zipEntry) throws IOException {
                String rawName = zipEntry.getName();
                if (++entryCount[0] > 10000) {
                    throw new IOException("Too many ZIP entries");
                }
                if (zipEntry.isDirectory() && (".".equals(rawName) || "./".equals(rawName))) {
                    return;
                }
                // Validate before filtering as well as after any caller-supplied mapping.
                resolveEntry(root, rawName);

                // 跳过 __MACOSX 或隐藏文件
                if (rawName.startsWith("__MACOSX/") || rawName.contains("/__MACOSX/")
                        || rawName.endsWith(".DS_Store") || rawName.matches(".*(^|/)\\.[^/]+")) {
                    return;
                }

                // 名称映射（可为 null 跳过）
                String name = (mapper != null) ? mapper.map(rawName) : rawName;
                if (name == null)
                    return;

                final Path destination = resolveEntry(root, name);
                File outFile = destination.toFile();

                if (zipEntry.isDirectory()) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    try (OutputStream out = new FileOutputStream(outFile)) {
                        byte[] buffer = new byte[4096];
                        int len;
                        while ((len = in.read(buffer)) != -1) {
                            expandedSize[0] += len;
                            if (expandedSize[0] > 250L * 1024 * 1024) {
                                throw new IOException("Expanded ZIP exceeds the import limit");
                            }
                            out.write(buffer, 0, len);
                        }
                    }
                }
            }
        });
    }

    private static Path resolveEntry(final Path root, final String name) throws IOException {
        if (name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains(":")
                || name.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new IOException("Invalid ZIP entry path");
        }
        for (final String part : name.split("/")) {
            if ("..".equals(part) || part.endsWith(" ") || (part.endsWith(".") && !".".equals(part))) {
                throw new IOException("ZIP entry traverses its destination");
            }
        }
        final Path destination = root.resolve(name).normalize();
        if (!destination.startsWith(root) || destination.equals(root)
                || !destination.toFile().getCanonicalFile().toPath().startsWith(root)) {
            throw new IOException("ZIP entry escapes its destination");
        }
        for (Path current = destination; current != null && !current.equals(root); current = current.getParent()) {
            if (Files.isSymbolicLink(current) || (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(current) && !current.equals(destination))) {
                throw new IOException("ZIP entry crosses a link or non-directory");
            }
        }
        return destination;
    }
}
