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
import org.njmule.core.kad.UInt128;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;

/**
 * {@code preferencesKad.dat}, 23 bytes: {@code <last IP u32><u16 0><Kad id 16><u8 0>}. A zero id is invalid (old
 * versions stored it) and must be replaced by a random one.
 */
public final class PreferencesKadDat {

    public record Contents(int lastIp, UInt128 kadId) {
        public Contents {
            Objects.requireNonNull(kadId, "kadId");
        }
    }

    private PreferencesKadDat() {}

    public static Contents read(byte[] data) throws DecodeException {
        PacketReader in = PacketReader.of(data);
        int ip = in.i32();
        in.u16();
        return new Contents(ip, UInt128.read(in));
    }

    public static byte[] write(Contents contents) {
        PacketWriter out = new PacketWriter(23);
        out.u32(contents.lastIp() & 0xFFFF_FFFFL).u16(0);
        contents.kadId().write(out);
        out.u8(0);
        return out.toByteArray();
    }
}
