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
import org.b3log.latke.http.FileUpload;
import org.b3log.latke.http.Request;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.file.*;
import java.util.*;
/** Uploads are staged outside the public directory under server-generated names. */
public final class Uploads {
    public static final long MAX_FILE_SIZE = 50L * 1024 * 1024;
    private Uploads() { }
    public static List<FileUpload> files(final Request request) throws IOException {
        final List<FileUpload> files = new ArrayList<>();
        for (final String field : List.of("file", "file[]", "files", "files[]")) files.addAll(request.getFileUploads(field));
        if (files.isEmpty() || files.size() > 100) throw new IOException("Invalid upload count");
        long size = 0;
        for (final FileUpload file : files) { size += file.getData().length; if (file.getData().length > MAX_FILE_SIZE || size > 2 * MAX_FILE_SIZE) throw new IOException("Upload exceeds size limit"); }
        return files;
    }
    public static String extension(final String filename, final String... allowed) throws IOException {
        if (filename == null || filename.isEmpty() || filename.contains("/") || filename.contains("\\")
                || filename.contains(":") || filename.endsWith(".") || filename.endsWith(" ")
                || filename.chars().anyMatch(c -> c < 32 || c == 127)) throw new IOException("Invalid upload filename");
        final int dot = filename.lastIndexOf('.');
        final String extension = dot < 1 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (final String candidate : allowed) if (candidate.equals(extension)) return extension;
        throw new IOException("Unsupported upload extension");
    }
    public static File stage(final FileUpload item, final Path directory, final String... allowed) throws IOException {
        return stage(item.getFilename(), new ByteArrayInputStream(item.getData()), directory, allowed);
    }
    public static File stage(final String filename, final InputStream input, final Path directory, final String... allowed) throws IOException {
        final String extension = extension(filename, allowed);
        final Path destination = Files.createTempFile(directory, "upload-", "." + extension);
        try (input; OutputStream output = Files.newOutputStream(destination)) {
            final byte[] buffer = new byte[8192]; long size = 0; int length;
            while ((length = input.read(buffer)) != -1) {
                size += length; if (size > MAX_FILE_SIZE) throw new IOException("Upload exceeds size limit");
                output.write(buffer, 0, length);
            }
            return destination.toFile();
        } catch (IOException | RuntimeException e) { Files.deleteIfExists(destination); throw e; }
    }
    public static void validateImage(final File file) throws IOException {
        try (javax.imageio.stream.ImageInputStream input = ImageIO.createImageInputStream(file)) {
            if (input == null) throw new IOException("Invalid image");
            final Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Invalid image");
            final javax.imageio.ImageReader reader = readers.next();
            try { reader.setInput(input); final long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels <= 0 || pixels > 40_000_000) throw new IOException("Image dimensions exceed upload limit");
            } finally { reader.dispose(); }
        }
    }
}