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

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import org.njmule.core.protocol.io.DecodeException;

/**
 * zlib packing of whole packets (protocols {@code 0xD4} and {@code 0xE5}). Inflating always has an output cap, so a
 * small "zip bomb" cannot exhaust memory.
 */
public final class Packing {

    /** Inflated payload cap for packets from clients. */
    public static final int MAX_CLIENT_UNPACKED = 50_000;

    /** Inflated payload cap for packets from servers (search results, source lists). */
    public static final int MAX_SERVER_UNPACKED = 250_000;

    private Packing() {}

    /** Packs with the best compression when that makes the packet smaller, else returns it. */
    public static Packet pack(Packet packet) {
        int protocol = switch (packet.protocol()) {
            case Protocol.EMULE, Protocol.EDONKEY -> Protocol.PACKED;
            case Protocol.KAD -> Protocol.KAD_PACKED;
            default -> throw new IllegalArgumentException("cannot pack protocol " + packet.protocol());
        };
        byte[] packed = deflate(packet.payloadArray(), Deflater.BEST_COMPRESSION);
        return packed.length < packet.length() ? Packet.adopt(protocol, packet.opcode(), packed) : packet;
    }

    /** Unpacks to {@code 0xC5} (eD2K) or {@code 0xE4} (Kad); other packets pass unchanged. */
    public static Packet unpack(Packet packet, int maxUnpacked) throws DecodeException {
        return switch (packet.protocol()) {
            case Protocol.PACKED ->
                Packet.adopt(Protocol.EMULE, packet.opcode(), inflate(packet.payloadArray(), maxUnpacked));
            case Protocol.KAD_PACKED ->
                Packet.adopt(Protocol.KAD, packet.opcode(), inflate(packet.payloadArray(), maxUnpacked));
            default -> packet;
        };
    }

    /** Unpacks a packet from a server: inflated {@code 0xD4} frames carry eDonkey messages. */
    public static Packet unpackFromServer(Packet packet) throws DecodeException {
        if (packet.protocol() != Protocol.PACKED) {
            return packet;
        }
        return Packet.adopt(Protocol.EDONKEY, packet.opcode(), inflate(packet.payloadArray(), MAX_SERVER_UNPACKED));
    }

    public static byte[] deflate(byte[] data, int level) {
        try (Deflater deflater = new Deflater(level)) {
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 2 + 64);
            byte[] chunk = new byte[8192];
            while (!deflater.finished()) {
                int n = deflater.deflate(chunk);
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** Inflates a zlib stream that must end within {@code maxSize} bytes of output. */
    public static byte[] inflate(byte[] data, int maxSize) throws DecodeException {
        try (Inflater inflater = new Inflater()) {
            inflater.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxSize, 8192));
            byte[] chunk = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(chunk, 0, Math.min(chunk.length, maxSize - out.size() + 1));
                if (n == 0 && !inflater.finished()) {
                    throw new DecodeException("truncated or unsupported zlib data");
                }
                out.write(chunk, 0, n);
                if (out.size() > maxSize) {
                    throw new DecodeException("inflated data exceeds " + maxSize + " bytes");
                }
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new DecodeException("corrupt zlib data", e);
        }
    }
}
