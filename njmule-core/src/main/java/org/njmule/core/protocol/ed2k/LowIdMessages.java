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
package org.njmule.core.protocol.ed2k;

import java.util.Objects;
import org.njmule.core.api.Hash16;
import org.njmule.core.kad.UInt128;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;

/**
 * The client messages that keep a firewalled Kad client reachable: the link to its buddy, the callbacks that ask it to
 * connect, and the UDP firewall test. Ids and file hashes travel in the Kad id layout.
 */
public final class LowIdMessages {

    private LowIdMessages() {}

    /** OP_BUDDYPING: the firewalled side keeps the link to its buddy alive. */
    public static Packet buddyPing() {
        return Packet.empty(Protocol.EMULE, Opcodes.Extended.BUDDYPING.code());
    }

    /** OP_BUDDYPONG: the buddy's answer. */
    public static Packet buddyPong() {
        return Packet.empty(Protocol.EMULE, Opcodes.Extended.BUDDYPONG.code());
    }

    /**
     * OP_CALLBACK, from a buddy to the firewalled client it serves: the client at {@code requesterIp} ({@code Ipv4}
     * form) listening on {@code requesterPort} wants {@code file}.
     */
    public record Callback(UInt128 buddyId, UInt128 file, int requesterIp, int requesterPort) {
        public Callback {
            Objects.requireNonNull(buddyId, "buddyId");
            Objects.requireNonNull(file, "file");
        }

        public Packet encode() {
            PacketWriter out = new PacketWriter();
            this.buddyId.write(out);
            this.file.write(out);
            out.u32(Integer.toUnsignedLong(this.requesterIp)).u16(this.requesterPort);
            return Packet.of(Protocol.EMULE, Opcodes.Extended.CALLBACK.code(), out);
        }

        public static Callback decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            return new Callback(UInt128.read(in), UInt128.read(in), (int) in.u32(), in.u16());
        }
    }

    /**
     * OP_DIRECTCALLBACKREQ, over UDP to the Kad port of a firewalled client whose UDP port is open: connect to our
     * {@code tcpPort}; the connect options say whether to obfuscate.
     */
    public record DirectCallbackRequest(int tcpPort, Hash16 userHash, int connectOptions) {
        public DirectCallbackRequest {
            Objects.requireNonNull(userHash, "userHash");
        }

        public Packet encode() {
            return Packet.of(
                    Protocol.EMULE,
                    Opcodes.ClientUdp.DIRECTCALLBACKREQ.code(),
                    new PacketWriter().u16(this.tcpPort).hash16(this.userHash).u8(this.connectOptions));
        }

        public static DirectCallbackRequest decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            return new DirectCallbackRequest(in.u16(), in.hash16(), in.u8());
        }
    }

    /**
     * OP_FWCHECKUDPREQ: a client testing its UDP port asks us to send a datagram to its internal and external port,
     * obfuscated with {@code senderKey}.
     */
    public record UdpCheckRequest(int internalPort, int externalPort, int senderKey) {
        public Packet encode() {
            return Packet.of(
                    Protocol.EMULE,
                    Opcodes.Extended.FWCHECKUDPREQ.code(),
                    new PacketWriter()
                            .u16(this.internalPort)
                            .u16(this.externalPort)
                            .u32(Integer.toUnsignedLong(this.senderKey)));
        }

        public static UdpCheckRequest decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            return new UdpCheckRequest(in.u16(), in.u16(), (int) in.u32());
        }
    }
}
