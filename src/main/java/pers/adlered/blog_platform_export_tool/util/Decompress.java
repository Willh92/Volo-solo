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
package pers.adlered.blog_platform_export_tool.util;

import org.b3log.solo.bolo.tool.ZipFilterUtils;
import org.b3log.solo.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <h3>BlogPlatformExportTool</h3>
 * <p>智能解压 RAR 及 ZIP</p>
 *
 * @author : https://github.com/adlered
 * @date : 2020-05-14
 **/
public class Decompress {

    private static final Logger LOGGER = Logger.getLogger(Decompress.class);
    private static final ThreadLocal<String> THREAD_LOCAL_PATH = new ThreadLocal<>();

    @Deprecated
    public static volatile String path;

    private final String targetPath;

    public Decompress(final String path) {
        this.targetPath = path;
        THREAD_LOCAL_PATH.set(path);
        Decompress.path = path;
    }

    public String getTargetPath() {
        return targetPath;
    }

    public static String currentPath() {
        final String local = THREAD_LOCAL_PATH.get();
        return local != null ? local : path;
    }

    public static void clearCurrentPath() {
        THREAD_LOCAL_PATH.remove();
    }

    /**
     * Unpacks supported archives inside {@code targetDirectory} and returns {@code targetDirectory} explicitly.
     */
    public static String unpackDirectory(final String targetDirectory) throws IOException {
        final Decompress decompress = new Decompress(targetDirectory);
        decompress.run();
        return targetDirectory;
    }

    public void run() throws IOException {
        if (targetPath == null || targetPath.isBlank()) {
            return;
        }
        final List<String> unzipFiles = analyze();
        final File destDir = new File(targetPath);
        for (final String name : unzipFiles) {
            final File archiveFile = new File(destDir, name);
            LOGGER.debug("Unpacking " + archiveFile.getAbsolutePath());
            if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                ZipFilterUtils.unpackFilteredZip(archiveFile, destDir,
                        entry -> entry.toLowerCase(Locale.ROOT).endsWith(".xml") ? entry : null);
            }
        }
        delete(unzipFiles);
    }

    private List<String> analyze() {
        final List<String> result = new ArrayList<>();
        if (targetPath == null || targetPath.isBlank()) {
            return result;
        }
        final File dir = new File(targetPath);
        final File[] files = dir.listFiles();
        if (files == null) {
            return result;
        }
        for (final File f : files) {
            final String filename = f.getName();
            if (filename.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                result.add(filename);
            }
        }
        return result;
    }

    private void delete(final List<String> unzipFiles) {
        final File destDir = new File(targetPath);
        final File dsStore = new File(destDir, ".DS_Store");
        if (dsStore.exists()) {
            unzipFiles.add(dsStore.getName());
        }
        for (final String name : unzipFiles) {
            final File file = new File(destDir, name);
            final boolean removed = file.delete();
            LOGGER.debug("Removed " + name + ": " + removed);
        }
    }
}
