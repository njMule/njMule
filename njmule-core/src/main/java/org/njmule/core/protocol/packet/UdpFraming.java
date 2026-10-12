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
import org.njmule.core.protocol.io.DecodeException;

/** UDP framing: {@code <protocol u8><opcode u8><payload>}, one packet per datagram. */
public final class UdpFraming {

    public static final int HEADER_SIZE = 2;

    private UdpFraming() {}

    public static byte[] encode(Packet packet) {
        byte[] out = new byte[HEADER_SIZE + packet.length()];
        out[0] = (byte) packet.protocol();
        out[1] = (byte) packet.opcode();
        packet.copyPayload(out, HEADER_SIZE);
        return out;
    }

    /** Decodes a received datagram; shorter than a header is malformed, not an underflow. */
    public static Packet decode(byte[] datagram, int offset, int length) throws DecodeException {
        if (length < HEADER_SIZE) {
            throw new DecodeException("datagram of " + length + " bytes has no header");
        }
        return Packet.adopt(
                datagram[offset] & 0xFF,
                datagram[offset + 1] & 0xFF,
                Arrays.copyOfRange(datagram, offset + HEADER_SIZE, offset + length));
    }
}
