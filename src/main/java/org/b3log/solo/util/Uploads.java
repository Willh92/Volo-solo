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
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Uploads are staged outside the public directory under server-generated names. */
public final class Uploads {
    public static final long MAX_FILE_SIZE = 50L * 1024 * 1024;
    private static final long MAX_IMAGE_PIXELS = 40_000_000L;
    private static final int MAX_AVIF_META_BYTES = 2 * 1024 * 1024;

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
        if (file == null || !file.isFile() || file.length() < 12) {
            throw new IOException("Invalid image");
        }
        boolean decodedByImageIO = false;
        try (javax.imageio.stream.ImageInputStream input = ImageIO.createImageInputStream(file)) {
            if (input == null) throw new IOException("Invalid image");
            final Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(input);
            if (readers.hasNext()) {
                final javax.imageio.ImageReader reader = readers.next();
                try {
                    reader.setInput(input);
                    final long width = reader.getWidth(0);
                    final long height = reader.getHeight(0);
                    enforceDimensions(width, height);
                    decodedByImageIO = true;
                } finally {
                    reader.dispose();
                }
            }
        }
        if (!decodedByImageIO) {
            validateWebpOrAvif(file);
        }
    }

    private static void enforceDimensions(final long width, final long height) throws IOException {
        if (width <= 0 || height <= 0) {
            throw new IOException("Invalid image");
        }
        if (width > MAX_IMAGE_PIXELS || height > MAX_IMAGE_PIXELS || width * height > MAX_IMAGE_PIXELS) {
            throw new IOException("Image dimensions exceed upload limit");
        }
    }

    private static void validateWebpOrAvif(final File file) throws IOException {
        final byte[] head = new byte[32];
        final int headLen;
        try (InputStream in = new FileInputStream(file)) {
            headLen = in.readNBytes(head, 0, head.length);
        }
        if (headLen >= 21 && isAscii(head, 0, "RIFF") && isAscii(head, 8, "WEBP")) {
            validateWebpHeader(head, headLen, file.length());
            return;
        }
        if (headLen >= 16 && isAscii(head, 4, "ftyp")) {
            validateAvifContainer(file);
            return;
        }
        throw new IOException("Invalid image");
    }

    private static void validateWebpHeader(final byte[] b, final int len, final long fileLength) throws IOException {
        if (fileLength < 20) {
            throw new IOException("Invalid image");
        }
        final long chunkSize = Integer.toUnsignedLong(readIntLE(b, 16));
        if (20L + chunkSize > fileLength) {
            throw new IOException("Invalid image");
        }
        if (isAscii(b, 12, "VP8 ")) {
            if (len < 30 || chunkSize < 10) {
                throw new IOException("Invalid image");
            }
            // Keyframe bit (bit 0 of frame tag) must be 0, followed by VP8 start code 0x9D 0x01 0x2A
            if ((b[20] & 0x01) != 0
                    || (b[23] & 0xFF) != 0x9D
                    || (b[24] & 0xFF) != 0x01
                    || (b[25] & 0xFF) != 0x2A) {
                throw new IOException("Invalid image");
            }
            final int width = ((b[26] & 0xFF) | ((b[27] & 0xFF) << 8)) & 0x3FFF;
            final int height = ((b[28] & 0xFF) | ((b[29] & 0xFF) << 8)) & 0x3FFF;
            enforceDimensions(width, height);
            return;
        }
        if (isAscii(b, 12, "VP8L")) {
            if (len < 25 || chunkSize < 5 || (b[20] & 0xFF) != 0x2F) {
                throw new IOException("Invalid image");
            }
            final int bits = readIntLE(b, 21);
            final int width = (bits & 0x3FFF) + 1;
            final int height = ((bits >>> 14) & 0x3FFF) + 1;
            enforceDimensions(width, height);
            return;
        }
        if (isAscii(b, 12, "VP8X")) {
            if (len < 30 || chunkSize < 10) {
                throw new IOException("Invalid image");
            }
            final int width = ((b[24] & 0xFF) | ((b[25] & 0xFF) << 8) | ((b[26] & 0xFF) << 16)) + 1;
            final int height = ((b[27] & 0xFF) | ((b[28] & 0xFF) << 8) | ((b[29] & 0xFF) << 16)) + 1;
            enforceDimensions(width, height);
            return;
        }
        throw new IOException("Invalid image");
    }

    private static void validateAvifContainer(final File file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            final long fileLen = raf.length();
            if (fileLen < 24) {
                throw new IOException("Invalid image");
            }
            long pos = 0L;
            boolean ftypVerified = false;
            byte[] metaPayload = null;
            int boxCount = 0;
            final byte[] hdr = new byte[16];

            while (pos + 8 <= fileLen) {
                if (++boxCount > 256) {
                    throw new IOException("Invalid image");
                }
                raf.seek(pos);
                raf.readFully(hdr, 0, 8);
                final long size32 = Integer.toUnsignedLong(readIntBE(hdr, 0));
                final String type = new String(hdr, 4, 4, StandardCharsets.ISO_8859_1);
                final int headerLen;
                final long boxSize;
                if (size32 == 1L) {
                    if (pos + 16 > fileLen) {
                        throw new IOException("Invalid image");
                    }
                    raf.readFully(hdr, 8, 8);
                    boxSize = readLongBE(hdr, 8);
                    headerLen = 16;
                    if (boxSize < 16L) {
                        throw new IOException("Invalid image");
                    }
                } else if (size32 == 0L) {
                    boxSize = fileLen - pos;
                    headerLen = 8;
                } else if (size32 >= 8L) {
                    boxSize = size32;
                    headerLen = 8;
                } else {
                    throw new IOException("Invalid image");
                }

                if (boxSize > fileLen - pos) {
                    throw new IOException("Invalid image");
                }
                final long payloadLen = boxSize - headerLen;

                if (pos == 0L) {
                    if (!"ftyp".equals(type) || payloadLen < 8L || payloadLen > 4096L) {
                        throw new IOException("Invalid image");
                    }
                    final byte[] ftyp = new byte[(int) payloadLen];
                    raf.readFully(ftyp);
                    if (!containsAvifBrand(ftyp)) {
                        throw new IOException("Invalid image");
                    }
                    ftypVerified = true;
                } else if ("meta".equals(type)) {
                    if (payloadLen < 12L || payloadLen > MAX_AVIF_META_BYTES) {
                        throw new IOException("Invalid image");
                    }
                    metaPayload = new byte[(int) payloadLen];
                    raf.readFully(metaPayload);
                    break;
                }
                pos += boxSize;
            }

            if (!ftypVerified || metaPayload == null) {
                throw new IOException("Invalid image");
            }
            // ISO/IEC 14496-12 'meta' is a FullBox: skip 4-byte version+flags before child boxes.
            if (!scanAvifMetaForIspe(metaPayload, 4, metaPayload.length)) {
                throw new IOException("Invalid image");
            }
        }
    }

    private static boolean containsAvifBrand(final byte[] ftyp) {
        if (isAscii(ftyp, 0, "avif") || isAscii(ftyp, 0, "avis")) {
            return true;
        }
        for (int offset = 8; offset + 4 <= ftyp.length; offset += 4) {
            if (isAscii(ftyp, offset, "avif") || isAscii(ftyp, offset, "avis")) {
                return true;
            }
        }
        return false;
    }

    private static boolean scanAvifMetaForIspe(final byte[] buf, final int start, final int end) throws IOException {
        int offset = start;
        while (offset + 8 <= end) {
            final BoxSlice box = readBoxSlice(buf, offset, end);
            if ("iprp".equals(box.type)) {
                return scanAvifIprp(buf, box.payloadStart, box.boxEnd);
            }
            offset = box.boxEnd;
        }
        return false;
    }

    private static boolean scanAvifIprp(final byte[] buf, final int start, final int end) throws IOException {
        int offset = start;
        while (offset + 8 <= end) {
            final BoxSlice box = readBoxSlice(buf, offset, end);
            if ("ipco".equals(box.type)) {
                return scanAvifIpco(buf, box.payloadStart, box.boxEnd);
            }
            offset = box.boxEnd;
        }
        return false;
    }

    private static boolean scanAvifIpco(final byte[] buf, final int start, final int end) throws IOException {
        int offset = start;
        boolean foundIspe = false;
        while (offset + 8 <= end) {
            final BoxSlice box = readBoxSlice(buf, offset, end);
            if ("ispe".equals(box.type)) {
                // 'ispe' FullBox payload: 4 bytes version/flags + 4 bytes width + 4 bytes height
                if (box.boxEnd - box.payloadStart < 12) {
                    throw new IOException("Invalid image");
                }
                final long width = Integer.toUnsignedLong(readIntBE(buf, box.payloadStart + 4));
                final long height = Integer.toUnsignedLong(readIntBE(buf, box.payloadStart + 8));
                enforceDimensions(width, height);
                foundIspe = true;
            }
            offset = box.boxEnd;
        }
        return foundIspe;
    }

    private record BoxSlice(String type, int payloadStart, int boxEnd) { }

    private static BoxSlice readBoxSlice(final byte[] buf, final int offset, final int limit) throws IOException {
        if (offset + 8 > limit) {
            throw new IOException("Invalid image");
        }
        final long size32 = Integer.toUnsignedLong(readIntBE(buf, offset));
        final String type = new String(buf, offset + 4, 4, StandardCharsets.ISO_8859_1);
        final int headerLen;
        final long boxSize;
        if (size32 == 1L) {
            if (offset + 16 > limit) {
                throw new IOException("Invalid image");
            }
            boxSize = readLongBE(buf, offset + 8);
            headerLen = 16;
            if (boxSize < 16L) {
                throw new IOException("Invalid image");
            }
        } else if (size32 == 0L) {
            boxSize = limit - offset;
            headerLen = 8;
        } else if (size32 >= 8L) {
            boxSize = size32;
            headerLen = 8;
        } else {
            throw new IOException("Invalid image");
        }
        if (boxSize > limit - offset) {
            throw new IOException("Invalid image");
        }
        return new BoxSlice(type, offset + headerLen, (int) (offset + boxSize));
    }

    private static boolean isAscii(final byte[] b, final int offset, final String expected4) {
        return offset + 4 <= b.length
                && b[offset] == (byte) expected4.charAt(0)
                && b[offset + 1] == (byte) expected4.charAt(1)
                && b[offset + 2] == (byte) expected4.charAt(2)
                && b[offset + 3] == (byte) expected4.charAt(3);
    }

    private static int readIntLE(final byte[] b, final int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    private static int readIntBE(final byte[] b, final int off) {
        return ((b[off] & 0xFF) << 24)
                | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }

    private static long readLongBE(final byte[] b, final int off) {
        return ((long) readIntBE(b, off) << 32) | Integer.toUnsignedLong(readIntBE(b, off + 4));
    }
}
