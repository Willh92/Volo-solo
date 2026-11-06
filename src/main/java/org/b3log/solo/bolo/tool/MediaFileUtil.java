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
package org.b3log.solo.bolo.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 判断文件的类型  视频 音频  图片
 */
public class MediaFileUtil {
    // Audio
    public static final int FILE_TYPE_MP3 = 1;
    public static final int FILE_TYPE_M4A = 2;
    public static final int FILE_TYPE_WAV = 3;
    public static final int FILE_TYPE_AMR = 4;
    public static final int FILE_TYPE_AWB = 5;
    public static final int FILE_TYPE_WMA = 6;
    public static final int FILE_TYPE_OGG = 7;
    // MIDI
    public static final int FILE_TYPE_MID = 11;
    public static final int FILE_TYPE_SMF = 12;
    public static final int FILE_TYPE_IMY = 13;
    // Video
    public static final int FILE_TYPE_MP4 = 21;
    public static final int FILE_TYPE_M4V = 22;
    public static final int FILE_TYPE_3GPP = 23;
    public static final int FILE_TYPE_3GPP2 = 24;
    public static final int FILE_TYPE_WMV = 25;
    // Image
    public static final int FILE_TYPE_JPEG = 31;
    public static final int FILE_TYPE_GIF = 32;
    public static final int FILE_TYPE_PNG = 33;
    public static final int FILE_TYPE_BMP = 34;
    public static final int FILE_TYPE_WBMP = 35;
    public static final int FILE_TYPE_WEBP = 36;
    public static final int FILE_TYPE_AVIF = 37;
    // Playlist
    public static final int FILE_TYPE_M3U = 41;
    public static final int FILE_TYPE_PLS = 42;
    public static final int FILE_TYPE_WPL = 43;
    public static final String UNKNOWN_STRING = "<unknown>";
    private static final int FIRST_AUDIO_FILE_TYPE = FILE_TYPE_MP3;
    private static final int LAST_AUDIO_FILE_TYPE = FILE_TYPE_OGG;
    private static final int FIRST_MIDI_FILE_TYPE = FILE_TYPE_MID;
    private static final int LAST_MIDI_FILE_TYPE = FILE_TYPE_IMY;
    private static final int FIRST_VIDEO_FILE_TYPE = FILE_TYPE_MP4;
    private static final int LAST_VIDEO_FILE_TYPE = FILE_TYPE_WMV;
    private static final int FIRST_IMAGE_FILE_TYPE = FILE_TYPE_JPEG;
    private static final int LAST_IMAGE_FILE_TYPE = FILE_TYPE_AVIF;
    private static final int FIRST_PLAYLIST_FILE_TYPE = FILE_TYPE_M3U;
    private static final int LAST_PLAYLIST_FILE_TYPE = FILE_TYPE_WPL;
    private static final Map<String, MediaFileType> sFileTypeMap;
    private static final Map<String, Integer> sMimeTypeMap;
    public static final String sFileExtensions;

    static {
        final Map<String, MediaFileType> fileTypes = new LinkedHashMap<>();
        final Map<String, Integer> mimeTypes = new LinkedHashMap<>();

        addFileType(fileTypes, mimeTypes, "MP3", FILE_TYPE_MP3, "audio/mpeg");
        addFileType(fileTypes, mimeTypes, "M4A", FILE_TYPE_M4A, "audio/mp4");
        addFileType(fileTypes, mimeTypes, "WAV", FILE_TYPE_WAV, "audio/x-wav");
        addFileType(fileTypes, mimeTypes, "AMR", FILE_TYPE_AMR, "audio/amr");
        addFileType(fileTypes, mimeTypes, "AWB", FILE_TYPE_AWB, "audio/amr-wb");
        addFileType(fileTypes, mimeTypes, "WMA", FILE_TYPE_WMA, "audio/x-ms-wma");
        addFileType(fileTypes, mimeTypes, "OGG", FILE_TYPE_OGG, "application/ogg");

        addFileType(fileTypes, mimeTypes, "MID", FILE_TYPE_MID, "audio/midi");
        addFileType(fileTypes, mimeTypes, "XMF", FILE_TYPE_MID, "audio/midi");
        addFileType(fileTypes, mimeTypes, "RTTTL", FILE_TYPE_MID, "audio/midi");
        addFileType(fileTypes, mimeTypes, "SMF", FILE_TYPE_SMF, "audio/sp-midi");
        addFileType(fileTypes, mimeTypes, "IMY", FILE_TYPE_IMY, "audio/imelody");

        addFileType(fileTypes, mimeTypes, "MP4", FILE_TYPE_MP4, "video/mp4");
        addFileType(fileTypes, mimeTypes, "M4V", FILE_TYPE_M4V, "video/mp4");
        addFileType(fileTypes, mimeTypes, "3GP", FILE_TYPE_3GPP, "video/3gpp");
        addFileType(fileTypes, mimeTypes, "3GPP", FILE_TYPE_3GPP, "video/3gpp");
        addFileType(fileTypes, mimeTypes, "3G2", FILE_TYPE_3GPP2, "video/3gpp2");
        addFileType(fileTypes, mimeTypes, "3GPP2", FILE_TYPE_3GPP2, "video/3gpp2");
        addFileType(fileTypes, mimeTypes, "WMV", FILE_TYPE_WMV, "video/x-ms-wmv");

        addFileType(fileTypes, mimeTypes, "JPG", FILE_TYPE_JPEG, "image/jpeg");
        addFileType(fileTypes, mimeTypes, "JPEG", FILE_TYPE_JPEG, "image/jpeg");
        addFileType(fileTypes, mimeTypes, "GIF", FILE_TYPE_GIF, "image/gif");
        addFileType(fileTypes, mimeTypes, "PNG", FILE_TYPE_PNG, "image/png");
        addFileType(fileTypes, mimeTypes, "BMP", FILE_TYPE_BMP, "image/x-ms-bmp");
        addFileType(fileTypes, mimeTypes, "WBMP", FILE_TYPE_WBMP, "image/vnd.wap.wbmp");
        addFileType(fileTypes, mimeTypes, "WEBP", FILE_TYPE_WEBP, "image/webp");
        addFileType(fileTypes, mimeTypes, "AVIF", FILE_TYPE_AVIF, "image/avif");

        addFileType(fileTypes, mimeTypes, "M3U", FILE_TYPE_M3U, "audio/x-mpegurl");
        addFileType(fileTypes, mimeTypes, "PLS", FILE_TYPE_PLS, "audio/x-scpls");
        addFileType(fileTypes, mimeTypes, "WPL", FILE_TYPE_WPL, "application/vnd.ms-wpl");

        sFileTypeMap = Collections.unmodifiableMap(fileTypes);
        sMimeTypeMap = Collections.unmodifiableMap(mimeTypes);
        sFileExtensions = String.join(",", sFileTypeMap.keySet());
    }

    private static void addFileType(final Map<String, MediaFileType> fileTypes,
                                    final Map<String, Integer> mimeTypes,
                                    final String extension,
                                    final int fileType,
                                    final String mimeType) {
        fileTypes.put(extension, new MediaFileType(fileType, mimeType));
        mimeTypes.put(mimeType, Integer.valueOf(fileType));
    }

    public static boolean isAudioFileType(final int fileType) {
        return ((fileType >= FIRST_AUDIO_FILE_TYPE &&
                fileType <= LAST_AUDIO_FILE_TYPE) ||
                (fileType >= FIRST_MIDI_FILE_TYPE &&
                        fileType <= LAST_MIDI_FILE_TYPE));
    }

    public static boolean isVideoFileType(final int fileType) {
        return (fileType >= FIRST_VIDEO_FILE_TYPE &&
                fileType <= LAST_VIDEO_FILE_TYPE);
    }

    public static boolean isImageFileType(final int fileType) {
        return (fileType >= FIRST_IMAGE_FILE_TYPE &&
                fileType <= LAST_IMAGE_FILE_TYPE);
    }

    public static boolean isPlayListFileType(final int fileType) {
        return (fileType >= FIRST_PLAYLIST_FILE_TYPE &&
                fileType <= LAST_PLAYLIST_FILE_TYPE);
    }

    public static MediaFileType getFileType(final String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        String clean = path;
        final int queryIdx = clean.indexOf('?');
        if (queryIdx >= 0) {
            clean = clean.substring(0, queryIdx);
        }
        final int fragmentIdx = clean.indexOf('#');
        if (fragmentIdx >= 0) {
            clean = clean.substring(0, fragmentIdx);
        }
        final int lastSlash = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\'));
        final int lastDot = clean.lastIndexOf('.');
        if (lastDot < 0 || lastDot < lastSlash || lastDot == clean.length() - 1) {
            return null;
        }
        return sFileTypeMap.get(clean.substring(lastDot + 1).toUpperCase(Locale.ROOT));
    }

    //根据视频文件路径判断文件类型
    public static boolean isVideoFileType(final String path) {
        final MediaFileType type = getFileType(path);
        if (null != type) {
            return isVideoFileType(type.fileType);
        }
        return false;
    }

    //根据音频文件路径判断文件类型
    public static boolean isAudioFileType(final String path) {
        final MediaFileType type = getFileType(path);
        if (null != type) {
            return isAudioFileType(type.fileType);
        }
        return false;
    }

    //根据mime类型查看文件类型
    public static int getFileTypeForMimeType(final String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return 0;
        }
        final int semi = mimeType.indexOf(';');
        final String normalized = (semi >= 0 ? mimeType.substring(0, semi) : mimeType).strip().toLowerCase(Locale.ROOT);
        final Integer value = sMimeTypeMap.get(normalized);
        return (value == null ? 0 : value.intValue());
    }

    //根据图片文件路径判断文件类型
    public static boolean isImageFileType(final String path) {
        final MediaFileType type = getFileType(path);
        if (null != type) {
            return isImageFileType(type.fileType);
        }
        return false;
    }

    public static String getSuffixByFileType(final MediaFileType mediaFileType) {
        if (mediaFileType == null) {
            return "png";
        }
        for (final Map.Entry<String, MediaFileType> entry : sFileTypeMap.entrySet()) {
            if (entry.getValue() == mediaFileType) {
                return entry.getKey().toLowerCase(Locale.ROOT);
            }
        }
        return "png";
    }

    //静态内部类
    public static class MediaFileType {

        public int fileType;
        public String mimeType;

        public MediaFileType(final int fileType, final String mimeType) {
            this.fileType = fileType;
            this.mimeType = mimeType;
        }
    }
}
