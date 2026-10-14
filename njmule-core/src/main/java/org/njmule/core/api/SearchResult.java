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
package org.njmule.core.api;

import java.util.Objects;
import java.util.Optional;

/**
 * A file found by a search; the source counts add up over the servers that reported it.
 *
 * @param media what the file's publishers told of its content, {@link Media#NONE} when nothing
 */
public record SearchResult(
        Hash16 hash, String name, long size, int sources, int completeSources, Optional<String> type, Media media) {

    public SearchResult {
        Objects.requireNonNull(hash, "hash");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(media, "media");
    }

    /** A result without media details. */
    public SearchResult(Hash16 hash, String name, long size, int sources, int completeSources, Optional<String> type) {
        this(hash, name, size, sources, completeSources, type, Media.NONE);
    }

    /** Whether {@code name} and {@code size} make a file that can be downloaded, as {@link #link()} needs. */
    public static boolean isDownloadable(String name, long size) {
        return !name.isBlank() && size > 0 && size <= Ed2kLink.MAX_FILE_SIZE;
    }

    /** The ed2k link of the file; only for a {@linkplain #isDownloadable downloadable} name and size. */
    public Ed2kLink.FileLink link() {
        return Ed2kLink.FileLink.of(this.name, this.size, this.hash);
    }

    /**
     * Media details of a file: empty texts and zeros where they are unknown. Texts come from other users, so they lose
     * their control characters and are cut at {@value #MAX_TEXT} characters.
     *
     * @param length the playing time in seconds
     * @param bitrate in kbit/s
     */
    public record Media(String artist, String album, String title, int length, int bitrate, String codec) {

        public static final Media NONE = new Media("", "", "", 0, 0, "");

        public static final int MAX_TEXT = 256;

        public Media {
            artist = clean(artist);
            album = clean(album);
            title = clean(title);
            codec = clean(codec);
            if (length < 0 || bitrate < 0) {
                throw new IllegalArgumentException("negative length or bitrate");
            }
        }

        /** These details, the unknown ones taken from {@code other}. */
        public Media or(Media other) {
            return new Media(
                    this.artist.isEmpty() ? other.artist : this.artist,
                    this.album.isEmpty() ? other.album : this.album,
                    this.title.isEmpty() ? other.title : this.title,
                    this.length == 0 ? other.length : this.length,
                    this.bitrate == 0 ? other.bitrate : this.bitrate,
                    this.codec.isEmpty() ? other.codec : this.codec);
        }

        private static String clean(String text) {
            Objects.requireNonNull(text, "text");
            StringBuilder out = new StringBuilder(Math.min(text.length(), MAX_TEXT));
            text.strip().codePoints().forEach(c -> {
                if (out.length() + Character.charCount(c) <= MAX_TEXT) {
                    out.appendCodePoint(Character.isISOControl(c) ? ' ' : c);
                }
            });
            return out.toString().strip();
        }
    }
}
