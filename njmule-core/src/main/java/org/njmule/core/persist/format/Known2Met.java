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
import org.njmule.core.api.Hash20;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;

/**
 * AICH recovery hash sets, {@code known2_64.met}: {@code <0x02>} then, appended per file, {@code <master hash 20><u32
 * count><leaf hash 20 × count>}. The older {@code known2.met} has no header and u16 counts.
 */
public final class Known2Met {

    public static final int VERSION = 0x02;

    /** The leaves of one file's AICH tree, as rebuilt by {@code AichTree.fromBlockHashes}. */
    public record Entry(Hash20 masterHash, List<Hash20> blockHashes) {
        public Entry {
            Objects.requireNonNull(masterHash, "masterHash");
            blockHashes = List.copyOf(blockHashes);
        }
    }

    private Known2Met() {}

    public static List<Entry> read(byte[] data) throws DecodeException {
        PacketReader in = PacketReader.of(data);
        MetCodecs.header(in, "known2_64.met", VERSION);
        List<Entry> entries = new ArrayList<>();
        entries(in, true, entries);
        return List.copyOf(entries);
    }

    /** The entries of a damaged {@code known2_64.met} that come before the damage; none without its header. */
    public static List<Entry> salvage(byte[] data) {
        List<Entry> entries = new ArrayList<>();
        try {
            PacketReader in = PacketReader.of(data);
            MetCodecs.header(in, "known2_64.met", VERSION);
            entries(in, true, entries);
        } catch (DecodeException e) {
            // What follows the damage is lost.
        }
        return List.copyOf(entries);
    }

    /** Reads the pre-64-bit {@code known2.met}. */
    public static List<Entry> readOld(byte[] data) throws DecodeException {
        List<Entry> entries = new ArrayList<>();
        entries(PacketReader.of(data), false, entries);
        return List.copyOf(entries);
    }

    public static byte[] write(List<Entry> entries) {
        PacketWriter out = new PacketWriter();
        out.u8(VERSION);
        for (Entry e : entries) {
            out.hash20(e.masterHash()).u32(e.blockHashes().size());
            for (Hash20 h : e.blockHashes()) {
                out.hash20(h);
            }
        }
        return out.toByteArray();
    }

    private static void entries(PacketReader in, boolean wideCounts, List<Entry> entries) throws DecodeException {
        while (in.hasRemaining()) {
            Hash20 master = in.hash20();
            long count = wideCounts ? in.u32() : in.u16();
            int n = in.count(count, Hash20.LENGTH, Integer.MAX_VALUE / Hash20.LENGTH);
            List<Hash20> leaves = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                leaves.add(in.hash20());
            }
            entries.add(new Entry(master, leaves));
        }
    }
}
