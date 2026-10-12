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

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import org.njmule.core.protocol.io.DecodeException;

/**
 * TCP framing: {@code <protocol u8><length u32><opcode u8><payload>}, where the length counts the
 * opcode byte. Frames are read in blocking style, one virtual thread per connection.
 */
public final class TcpFraming {

    public static final int HEADER_SIZE = 6;

    private TcpFraming() {}

    public static byte[] encode(Packet packet) {
        int length = packet.length();
        byte[] out = new byte[HEADER_SIZE + length];
        out[0] = (byte) packet.protocol();
        long counted = length + 1L;
        out[1] = (byte) counted;
        out[2] = (byte) (counted >>> 8);
        out[3] = (byte) (counted >>> 16);
        out[4] = (byte) (counted >>> 24);
        out[5] = (byte) packet.opcode();
        packet.copyPayload(out, HEADER_SIZE);
        return out;
    }

    /**
     * Reads one frame. A clean end of stream before the first header byte throws {@link
     * EOFException}; a truncated frame, an unknown protocol byte or a payload beyond {@code limit}
     * throws {@link DecodeException}.
     */
    public static Packet read(InputStream in, FrameLimit limit) throws IOException {
        byte[] header = new byte[HEADER_SIZE];
        int first = in.read();
        if (first < 0) {
            throw new EOFException("connection closed");
        }
        header[0] = (byte) first;
        readFully(in, header, 1, HEADER_SIZE - 1);
        int protocol = first;
        if (!Protocol.isTcp(protocol)) {
            throw new DecodeException(String.format("unknown protocol byte 0x%02X", protocol));
        }
        long counted =
                (header[1] & 0xFFL)
                        | (header[2] & 0xFFL) << 8
                        | (header[3] & 0xFFL) << 16
                        | (header[4] & 0xFFL) << 24;
        int opcode = header[5] & 0xFF;
        if (counted == 0) {
            throw new DecodeException("frame length 0 cannot hold the opcode");
        }
        long length = counted - 1;
        int max = limit.maxPayload(protocol, opcode);
        if (max < 0) {
            throw new DecodeException(
                    String.format("opcode 0x%02X/0x%02X not allowed here", protocol, opcode));
        }
        if (length > max) {
            throw new DecodeException(
                    String.format(
                            "frame 0x%02X/0x%02X of %d bytes exceeds %d",
                            protocol, opcode, length, max));
        }
        byte[] payload = new byte[(int) length];
        readFully(in, payload, 0, payload.length);
        return Packet.adopt(protocol, opcode, payload);
    }

    private static void readFully(InputStream in, byte[] buffer, int offset, int length)
            throws IOException {
        int n = in.readNBytes(buffer, offset, length);
        if (n < length) {
            throw new DecodeException("connection closed inside a frame");
        }
    }
}
