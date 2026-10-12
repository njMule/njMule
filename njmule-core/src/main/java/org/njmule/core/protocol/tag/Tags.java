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

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.tag.Tag.BlobTag;
import org.njmule.core.protocol.tag.Tag.HashTag;
import org.njmule.core.protocol.tag.Tag.IntTag;
import org.njmule.core.protocol.tag.Tag.StringTag;
import org.njmule.core.util.Bytes;

/** Typed lookups in a tag list; the first tag with the name and the expected type wins. */
public final class Tags {

    private Tags() {}

    public static Optional<Tag> find(List<Tag> tags, TagName name) {
        for (Tag tag : tags) {
            if (tag.name().equals(name)) {
                return Optional.of(tag);
            }
        }
        return Optional.empty();
    }

    public static OptionalLong intValue(List<Tag> tags, int id) {
        return intValue(tags, TagName.id(id));
    }

    public static OptionalLong intValue(List<Tag> tags, TagName name) {
        for (Tag tag : tags) {
            if (tag instanceof IntTag t && t.name().equals(name)) {
                return OptionalLong.of(t.value());
            }
        }
        return OptionalLong.empty();
    }

    public static Optional<String> stringValue(List<Tag> tags, int id) {
        TagName name = TagName.id(id);
        for (Tag tag : tags) {
            if (tag instanceof StringTag t && t.name().equals(name)) {
                return Optional.of(t.value());
            }
        }
        return Optional.empty();
    }

    public static Optional<Hash16> hashValue(List<Tag> tags, int id) {
        TagName name = TagName.id(id);
        for (Tag tag : tags) {
            if (tag instanceof HashTag t && t.name().equals(name)) {
                return Optional.of(t.value());
            }
        }
        return Optional.empty();
    }

    public static Optional<Bytes> blobValue(List<Tag> tags, int id) {
        TagName name = TagName.id(id);
        for (Tag tag : tags) {
            if (tag instanceof BlobTag t && t.name().equals(name)) {
                return Optional.of(t.value());
            }
        }
        return Optional.empty();
    }
}
