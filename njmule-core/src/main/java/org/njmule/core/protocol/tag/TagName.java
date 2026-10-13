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

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * A tag name as raw bytes (held as an ISO-8859-1 string). A one-byte name is a numeric id; longer names (e.g.
 * {@code "\x09" + "12"} for gaps) are strings.
 */
public record TagName(String raw) {

    public TagName {
        Objects.requireNonNull(raw, "raw");
        if (raw.length() > 0xFFFF) {
            throw new IllegalArgumentException("tag name too long: " + raw.length());
        }
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) > 0xFF) {
                throw new IllegalArgumentException("tag names are byte strings: " + raw);
            }
        }
    }

    public static TagName id(int id) {
        if (id < 0 || id > 0xFF) {
            throw new IllegalArgumentException("tag id out of range: " + id);
        }
        return new TagName(String.valueOf((char) id));
    }

    public static TagName of(String name) {
        return new TagName(name);
    }

    static TagName ofBytes(byte[] raw) {
        return new TagName(new String(raw, StandardCharsets.ISO_8859_1));
    }

    public boolean isId() {
        return this.raw.length() == 1;
    }

    public int id() {
        if (!this.isId()) {
            throw new IllegalStateException("not a numeric tag id: " + this);
        }
        return this.raw.charAt(0);
    }

    public byte[] toBytes() {
        return this.raw.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Override
    public String toString() {
        if (this.isId()) {
            return String.format("0x%02X", (int) this.raw.charAt(0));
        }
        StringBuilder out = new StringBuilder(this.raw.length());
        for (int i = 0; i < this.raw.length(); i++) {
            char c = this.raw.charAt(i);
            if (c >= 0x20 && c < 0x7F) {
                out.append(c);
            } else {
                out.append(String.format("\\x%02X", (int) c));
            }
        }
        return out.toString();
    }
}
