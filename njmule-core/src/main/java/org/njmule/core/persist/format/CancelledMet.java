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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.tag.TagCodec;

/**
 * Cancelled downloads, {@code cancelled.met}: {@code <0x0F><version 1><seed u32><u32 count>} then
 * {@code <key 16><u8 tag count><tags>} per file, where the key is {@code MD5(<seed LE><file
 * hash>)}, so that the list does not reveal which files were cancelled. The old 0x0E format stored
 * the plain file hashes and has no version or seed.
 */
public final class CancelledMet {

    private static final int HEADER = 0x0F;
    private static final int OLD_HEADER = 0x0E;
    private static final int VERSION = 0x01;
    private static final int MAX_ENTRIES = 1_000_000;

    /** {@code legacy}: the keys are plain file hashes (old format) and must be re-keyed. */
    public record Contents(int seed, boolean legacy, List<Hash16> keys) {
        public Contents {
            keys = List.copyOf(keys);
        }
    }

    private CancelledMet() {}

    public static Contents read(byte[] data) throws DecodeException {
        PacketReader in = PacketReader.of(data);
        int header = MetCodecs.header(in, "cancelled.met", HEADER, OLD_HEADER);
        int seed = 0;
        if (header == HEADER) {
            int version = in.u8();
            if (version > VERSION) {
                throw new DecodeException("unsupported cancelled.met version " + version);
            }
            seed = in.i32();
        }
        int count = in.count(in.u32(), 17, MAX_ENTRIES);
        List<Hash16> keys = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            keys.add(in.hash16());
            TagCodec.readEd2kList(in, in.u8(), 0xFF);
        }
        return new Contents(seed, header == OLD_HEADER, keys);
    }

    public static byte[] write(int seed, List<Hash16> keys) {
        PacketWriter out = new PacketWriter();
        out.u8(HEADER).u8(VERSION).u32(seed & 0xFFFF_FFFFL).u32(keys.size());
        for (Hash16 key : keys) {
            out.hash16(key).u8(0);
        }
        return out.toByteArray();
    }

    /** The key of a file hash under {@code seed}. */
    public static Hash16 key(int seed, Hash16 fileHash) {
        byte[] material = new byte[4 + Hash16.LENGTH];
        material[0] = (byte) seed;
        material[1] = (byte) (seed >>> 8);
        material[2] = (byte) (seed >>> 16);
        material[3] = (byte) (seed >>> 24);
        fileHash.copyTo(material, 4);
        try {
            return Hash16.of(MessageDigest.getInstance("MD5").digest(material));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is required by the Java platform", e);
        }
    }
}
