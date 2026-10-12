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
package org.njmule.core.protocol.packet;

import java.util.Arrays;
import java.util.HexFormat;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;

/** An immutable packet: protocol byte, opcode and payload. */
public final class Packet {

    private final int protocol;
    private final int opcode;
    private final byte[] payload;

    private Packet(int protocol, int opcode, byte[] payload) {
        if ((protocol & ~0xFF) != 0 || (opcode & ~0xFF) != 0) {
            throw new IllegalArgumentException("protocol and opcode are bytes");
        }
        this.protocol = protocol;
        this.opcode = opcode;
        this.payload = payload;
    }

    public static Packet of(int protocol, int opcode, byte[] payload) {
        return new Packet(protocol, opcode, payload.clone());
    }

    public static Packet of(int protocol, int opcode, PacketWriter payload) {
        return new Packet(protocol, opcode, payload.toByteArray());
    }

    public static Packet empty(int protocol, int opcode) {
        return new Packet(protocol, opcode, new byte[0]);
    }

    /** Takes ownership of a freshly decoded array. */
    static Packet adopt(int protocol, int opcode, byte[] payload) {
        return new Packet(protocol, opcode, payload);
    }

    public int protocol() {
        return this.protocol;
    }

    public int opcode() {
        return this.opcode;
    }

    public int length() {
        return this.payload.length;
    }

    /** A reader over the payload (no copy). */
    public PacketReader reader() {
        return PacketReader.of(this.payload);
    }

    public byte[] payload() {
        return this.payload.clone();
    }

    void copyPayload(byte[] target, int offset) {
        System.arraycopy(this.payload, 0, target, offset, this.payload.length);
    }

    byte[] payloadArray() {
        return this.payload;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Packet other
                && this.protocol == other.protocol
                && this.opcode == other.opcode
                && Arrays.equals(this.payload, other.payload);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * this.protocol + this.opcode) + Arrays.hashCode(this.payload);
    }

    @Override
    public String toString() {
        int shown = Math.min(this.payload.length, 32);
        return String.format(
                "Packet[0x%02X/0x%02X, %d bytes: %s%s]",
                this.protocol,
                this.opcode,
                this.payload.length,
                HexFormat.of().formatHex(this.payload, 0, shown),
                shown < this.payload.length ? "…" : "");
    }
}
