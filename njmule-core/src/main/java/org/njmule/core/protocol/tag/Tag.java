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

import java.util.Objects;
import org.njmule.core.api.Hash16;
import org.njmule.core.util.Bytes;

/**
 * A typed eD2K/Kad tag ({@code Packets.cpp:443-710}, {@code kademlia/io/DataIO.cpp}). Integers of
 * every width are one type: the encoders pick the width.
 */
public sealed interface Tag {

    TagName name();

    static Tag of(int id, long value) {
        return new IntTag(TagName.id(id), value);
    }

    static Tag of(int id, String value) {
        return new StringTag(TagName.id(id), value);
    }

    /** An unsigned integer of up to 64 bits (eMule UINT8/16/32/64). */
    record IntTag(TagName name, long value) implements Tag {
        public IntTag {
            Objects.requireNonNull(name, "name");
        }

        /** The value, checked to be a non-negative 32-bit quantity. */
        public long u32() {
            if ((this.value & 0xFFFF_FFFF_0000_0000L) != 0) {
                throw new IllegalStateException(this.name + " exceeds 32 bits: " + this.value);
            }
            return this.value;
        }
    }

    record StringTag(TagName name, String value) implements Tag {
        public StringTag {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
        }
    }

    record HashTag(TagName name, Hash16 value) implements Tag {
        public HashTag {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
        }
    }

    record FloatTag(TagName name, float value) implements Tag {
        public FloatTag {
            Objects.requireNonNull(name, "name");
        }
    }

    record BoolTag(TagName name, boolean value) implements Tag {
        public BoolTag {
            Objects.requireNonNull(name, "name");
        }
    }

    /** eD2K BOOLARRAY: {@code bitCount} bits followed by {@code bitCount / 8 + 1} bytes. */
    record BoolArrayTag(TagName name, int bitCount, Bytes value) implements Tag {
        public BoolArrayTag {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            if (bitCount < 0 || bitCount > 0xFFFF || value.length() != bitCount / 8 + 1) {
                throw new IllegalArgumentException(
                        "bool array of "
                                + bitCount
                                + " bits needs "
                                + (bitCount / 8 + 1)
                                + " bytes");
            }
        }
    }

    /** eD2K BLOB: a length-prefixed (u32) byte string. */
    record BlobTag(TagName name, Bytes value) implements Tag {
        public BlobTag {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
        }
    }

    /** BSOB: a short (u8 length) byte string, used by Kad. */
    record BsobTag(TagName name, Bytes value) implements Tag {
        public BsobTag {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            if (value.length() > 0xFF) {
                throw new IllegalArgumentException("BSOB longer than 255 bytes: " + value.length());
            }
        }
    }
}
