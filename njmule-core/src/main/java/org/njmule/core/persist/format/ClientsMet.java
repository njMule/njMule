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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.util.Bytes;

/**
 * Upload and download credits per user hash, {@code clients.met}: {@code <0x12><u32 count>} then 119-byte records
 * {@code <user hash 16><up lo u32><down lo u32><last seen u32><up hi u32><down hi u32><reserved u16><key size
 * u8><public key 80>}. Version 0x11 records have 38 bytes and no key.
 */
public final class ClientsMet {

    public static final int VERSION = 0x12;
    private static final int VERSION_OLD = 0x11;
    private static final int MAX_KEY = 80;
    private static final int RECORD = 119;
    private static final int RECORD_OLD = 38;
    private static final int MAX_CLIENTS = 2_000_000;

    /** {@code publicKey} is empty when the peer never identified itself. */
    public record Credit(Hash16 userHash, long uploaded, long downloaded, long lastSeen, Bytes publicKey) {
        public Credit {
            Objects.requireNonNull(userHash, "userHash");
            Objects.requireNonNull(publicKey, "publicKey");
            if (publicKey.length() > MAX_KEY) {
                throw new IllegalArgumentException("public keys have at most 80 bytes");
            }
        }
    }

    private ClientsMet() {}

    public static List<Credit> read(byte[] data) throws DecodeException {
        PacketReader in = PacketReader.of(data);
        int version = MetCodecs.header(in, "clients.met", VERSION, VERSION_OLD);
        int size = version == VERSION ? RECORD : RECORD_OLD;
        int count = in.count(in.u32(), size, MAX_CLIENTS);
        List<Credit> credits = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Hash16 hash = in.hash16();
            long upLo = in.u32();
            long downLo = in.u32();
            long lastSeen = in.u32();
            long upHi = in.u32();
            long downHi = in.u32();
            in.u16();
            Bytes key = Bytes.EMPTY;
            if (version == VERSION) {
                int keySize = in.u8();
                byte[] keyArea = in.bytes(MAX_KEY);
                if (keySize > MAX_KEY) {
                    throw new DecodeException("clients.met: public key of " + keySize + " bytes");
                }
                key = Bytes.of(keyArea, 0, keySize);
            }
            credits.add(new Credit(hash, upHi << 32 | upLo, downHi << 32 | downLo, lastSeen, key));
        }
        return List.copyOf(credits);
    }

    public static byte[] write(List<Credit> credits) {
        PacketWriter out = new PacketWriter();
        out.u8(VERSION).u32(credits.size());
        for (Credit c : credits) {
            out.hash16(c.userHash())
                    .u32(c.uploaded())
                    .u32(c.downloaded())
                    .u32(c.lastSeen())
                    .u32(c.uploaded() >>> 32)
                    .u32(c.downloaded() >>> 32)
                    .u16(0)
                    .u8(c.publicKey().length());
            byte[] key = new byte[MAX_KEY];
            c.publicKey().copyTo(key, 0);
            out.bytes(key);
        }
        return out.toByteArray();
    }
}
