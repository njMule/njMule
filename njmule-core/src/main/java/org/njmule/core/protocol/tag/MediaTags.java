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
package org.njmule.core.protocol.tag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.njmule.core.api.SearchResult;
import org.njmule.core.protocol.tag.Tag.IntTag;
import org.njmule.core.protocol.tag.Tag.StringTag;

/**
 * The media details of a file in its tags: the numeric ids of Kad and of newer clients, or the names servers send
 * ({@code Artist}, {@code Album}, {@code Title}, {@code length}, {@code bitrate}, {@code codec}, in any case), where
 * the length may also be a text such as {@code 1:02:03}.
 */
public final class MediaTags {

    private static final String ARTIST = "artist";
    private static final String ALBUM = "album";
    private static final String TITLE = "title";
    private static final String LENGTH = "length";
    private static final String BITRATE = "bitrate";
    private static final String CODEC = "codec";
    // h:m:s, m:s or s at the start, whatever follows; numbers of ten digits or more and longer texts are not lengths.
    private static final Pattern DURATION = Pattern.compile(
            "\\s*(\\d{1,9})(?!\\d)\\s*(?::\\s*(\\d{1,9})(?!\\d)\\s*(?::\\s*(\\d{1,9})(?!\\d))?)?.*", Pattern.DOTALL);
    private static final int MAX_DURATION_TEXT = 64;

    private MediaTags() {}

    public static SearchResult.Media read(List<Tag> tags) {
        if (tags.isEmpty()) {
            return SearchResult.Media.NONE;
        }
        return new SearchResult.Media(
                text(tags, FileTag.MEDIA_ARTIST, ARTIST),
                text(tags, FileTag.MEDIA_ALBUM, ALBUM),
                text(tags, FileTag.MEDIA_TITLE, TITLE),
                length(tags),
                number(tags, FileTag.MEDIA_BITRATE, BITRATE),
                text(tags, FileTag.MEDIA_CODEC, CODEC));
    }

    /** The tags that carry {@code media}, with their numeric ids. */
    public static List<Tag> of(SearchResult.Media media) {
        List<Tag> tags = new ArrayList<>(6);
        addText(tags, FileTag.MEDIA_ARTIST, media.artist());
        addText(tags, FileTag.MEDIA_ALBUM, media.album());
        addText(tags, FileTag.MEDIA_TITLE, media.title());
        if (media.length() > 0) {
            tags.add(Tag.of(FileTag.MEDIA_LENGTH, media.length()));
        }
        if (media.bitrate() > 0) {
            tags.add(Tag.of(FileTag.MEDIA_BITRATE, media.bitrate()));
        }
        addText(tags, FileTag.MEDIA_CODEC, media.codec());
        return tags;
    }

    private static void addText(List<Tag> tags, int id, String value) {
        if (!value.isEmpty()) {
            tags.add(Tag.of(id, value));
        }
    }

    private static String text(List<Tag> tags, int id, String name) {
        TagName tagId = TagName.id(id);
        for (Tag tag : tags) {
            if (tag instanceof StringTag t && !t.value().isBlank() && named(t.name(), tagId, name)) {
                return t.value();
            }
        }
        return "";
    }

    private static int number(List<Tag> tags, int id, String name) {
        TagName tagId = TagName.id(id);
        for (Tag tag : tags) {
            if (tag instanceof IntTag t && t.value() != 0 && named(t.name(), tagId, name)) {
                return clamp(t.value());
            }
        }
        return 0;
    }

    private static int length(List<Tag> tags) {
        TagName tagId = TagName.id(FileTag.MEDIA_LENGTH);
        for (Tag tag : tags) {
            if (!named(tag.name(), tagId, LENGTH)) {
                continue;
            }
            int seconds = switch (tag) {
                case IntTag t -> clamp(t.value());
                case StringTag t -> duration(t.value());
                default -> 0;
            };
            if (seconds > 0) {
                return seconds;
            }
        }
        return 0;
    }

    // Seconds from h:m:s, m:s or s; 0 when the text is none of them.
    static int duration(String text) {
        if (text.length() > MAX_DURATION_TEXT) {
            return 0;
        }
        Matcher m = DURATION.matcher(text);
        if (!m.matches()) {
            return 0;
        }
        long seconds = 0;
        for (int group = 1; group <= 3 && m.group(group) != null; group++) {
            seconds = seconds * 60 + Long.parseLong(m.group(group));
        }
        return clamp(seconds);
    }

    private static boolean named(TagName tag, TagName id, String name) {
        return tag.equals(id) || (!tag.isId() && tag.raw().equalsIgnoreCase(name));
    }

    // Unsigned on the wire: a value with the top bit set is huge, not negative.
    private static int clamp(long value) {
        return value < 0 || value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }
}
