/*
 * njMule - Next generation Java Mule
 * SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
 * the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If
 * not, see <https://www.gnu.org/licenses/>.
 */
package org.njmule.core.files;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The eD2K type of a file, from its extension, as servers index it. */
public final class FileTypes {

    public static final String AUDIO = "Audio";
    public static final String VIDEO = "Video";
    public static final String IMAGE = "Image";

    /** Programs, archives and disc images. */
    public static final String PROGRAM = "Pro";

    public static final String DOCUMENT = "Doc";

    private static final Map<String, Integer> CODES = Map.of(AUDIO, 1, VIDEO, 2, IMAGE, 3, PROGRAM, 4, DOCUMENT, 5);

    private static final Map<String, Set<String>> EXTENSIONS = Map.of(
            AUDIO,
            Set.of(
                    "aac", "ac3", "aiff", "ape", "au", "flac", "m4a", "mid", "midi", "mp2", "mp3", "mpc", "ogg", "opus",
                    "ra", "wav", "wma"),
            VIDEO,
            Set.of(
                    "3gp", "asf", "avi", "divx", "flv", "m2ts", "m4v", "mkv", "mov", "mp4", "mpeg", "mpg", "ogm", "rm",
                    "rmvb", "ts", "vob", "webm", "wmv", "xvid"),
            IMAGE,
            Set.of("bmp", "gif", "ico", "jpeg", "jpg", "png", "psd", "svg", "tif", "tiff", "webp"),
            PROGRAM,
            Set.of(
                    "7z", "ace", "apk", "bat", "bin", "bz2", "cab", "com", "cue", "deb", "dmg", "exe", "gz", "img",
                    "iso", "jar", "mdf", "mds", "msi", "nrg", "rar", "rpm", "tar", "tgz", "xz", "zip"),
            DOCUMENT,
            Set.of(
                    "chm", "csv", "doc", "docx", "epub", "htm", "html", "nfo", "odt", "pdf", "ppt", "pptx", "rtf",
                    "txt", "xls", "xlsx"));

    private FileTypes() {}

    /** The type of a file named {@code name}, if its extension tells. */
    public static Optional<String> of(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Set<String>> type : EXTENSIONS.entrySet()) {
            if (type.getValue().contains(extension)) {
                return Optional.of(type.getKey());
            }
        }
        return Optional.empty();
    }

    /** Whether {@code type} is one of the eD2K file types. */
    public static boolean isKnown(String type) {
        return CODES.containsKey(type);
    }

    /** The number servers with integer type tags use for {@code type}. */
    public static int code(String type) {
        Integer code = CODES.get(type);
        if (code == null) {
            throw new IllegalArgumentException("unknown file type " + type);
        }
        return code;
    }
}
