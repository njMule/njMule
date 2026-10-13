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
package org.njmule.core.persist.format;

import java.util.Objects;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.util.Bytes;

/**
 * {@code preferences.dat}, 61 bytes: {@code <version 0x14><user hash 16><main window placement 44>}. Only the user hash
 * matters to njMule; the window placement is kept as opaque bytes.
 */
public final class PreferencesDat {

    public static final int VERSION = 0x14;

    public record Contents(int version, Hash16 userHash, Bytes windowPlacement) {
        public Contents {
            Objects.requireNonNull(userHash, "userHash");
            Objects.requireNonNull(windowPlacement, "windowPlacement");
        }
    }

    private PreferencesDat() {}

    public static Contents read(byte[] data) throws DecodeException {
        PacketReader in = PacketReader.of(data);
        int version = in.u8();
        Hash16 hash = in.hash16();
        return new Contents(version, hash, Bytes.of(in.rest()));
    }

    public static byte[] write(Contents contents) {
        return new PacketWriter()
                .u8(contents.version())
                .hash16(contents.userHash())
                .bytes(contents.windowPlacement().toByteArray())
                .toByteArray();
    }

    /**
     * Whether the hash carries the client marker bytes ({@code [5] = 14}, {@code [14] = 111}) set by the clients that
     * generate user hashes this way.
     */
    public static boolean isMarkedUserHash(Hash16 hash) {
        return hash.get(5) == 14 && hash.get(14) == 111;
    }
}
