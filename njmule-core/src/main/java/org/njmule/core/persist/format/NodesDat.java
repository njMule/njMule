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
import org.njmule.core.kad.UInt128;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;

/**
 * Kad contacts, {@code nodes.dat}. Version 0: {@code <count ≠ 0>} then 25-byte records ending with
 * a contact type; version 1: {@code <0><1><count>} with a Kad version instead; version 2 (written):
 * {@code <0><2><count>}, 34-byte records adding the UDP key and the verified flag; version 3:
 * {@code <0><3><edition>}, edition 1 being a bootstrap list of 25-byte records. IPs are stored as
 * little-endian numbers.
 */
public final class NodesDat {

    /** A contact's obfuscation key, valid only while our own IP is {@code ip}. */
    public record UdpKey(int key, int ip) {
        public static final UdpKey NONE = new UdpKey(0, 0);
    }

    public record Contact(
            UInt128 id,
            int ip,
            int udpPort,
            int tcpPort,
            int version,
            int type,
            UdpKey udpKey,
            boolean verified) {

        public Contact {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(udpKey, "udpKey");
        }
    }

    /** What was read: the file version, whether it is a bootstrap-only list, the contacts. */
    public record Contents(int fileVersion, boolean bootstrap, List<Contact> contacts) {
        public Contents {
            contacts = List.copyOf(contacts);
        }
    }

    /** Contacts eMule writes at most; reading is limited by the file size. */
    public static final int MAX_WRITTEN = 200;

    private static final int MAX_CONTACTS = 100_000;

    private NodesDat() {}

    public static Contents read(byte[] data) throws DecodeException {
        PacketReader in = PacketReader.of(data);
        long count = in.u32();
        int version = 0;
        if (count == 0) {
            if (in.remaining() < 4) {
                return new Contents(0, false, List.of());
            }
            version = (int) in.u32();
            if (version == 3 && in.u32() == 1) {
                return new Contents(3, true, records(in, (int) in.u32(), 1, true));
            }
            if (version < 1 || version > 3) {
                return new Contents(version, false, List.of());
            }
            count = in.u32();
        }
        return new Contents(version, false, records(in, count, version, false));
    }

    /** Writes version 2, as eMule does. */
    public static byte[] write(List<Contact> contacts) {
        PacketWriter out = new PacketWriter();
        out.u32(0).u32(2).u32(contacts.size());
        for (Contact c : contacts) {
            c.id().write(out);
            out.u32(c.ip() & 0xFFFF_FFFFL).u16(c.udpPort()).u16(c.tcpPort()).u8(c.version());
            out.u32(c.udpKey().key() & 0xFFFF_FFFFL).u32(c.udpKey().ip() & 0xFFFF_FFFFL);
            out.u8(c.verified() ? 1 : 0);
        }
        return out.toByteArray();
    }

    private static List<Contact> records(PacketReader in, long count, int version, boolean exact)
            throws DecodeException {
        int size = version >= 2 && !exact ? 34 : 25;
        int n = in.count(count, size, MAX_CONTACTS);
        if (exact && (long) n * size != in.remaining()) {
            throw new DecodeException("bootstrap nodes.dat has trailing data");
        }
        List<Contact> contacts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            UInt128 id = UInt128.read(in);
            int ip = in.i32();
            int udp = in.u16();
            int tcp = in.u16();
            int last = in.u8();
            UdpKey key = UdpKey.NONE;
            boolean verified = false;
            if (size == 34) {
                key = new UdpKey(in.i32(), in.i32());
                verified = in.u8() != 0;
            }
            contacts.add(
                    version == 0
                            ? new Contact(id, ip, udp, tcp, 0, last, key, verified)
                            : new Contact(id, ip, udp, tcp, last, 0, key, verified));
        }
        return contacts;
    }
}
