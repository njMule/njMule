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
import java.util.Optional;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.util.Ipv4;

/**
 * Re-asks over UDP between clients: a client waiting in an uploader's queue asks again for its file without connecting,
 * and learns its place. What a message carries depends on the UDP version the other client announced in its hello.
 */
public final class ClientReasks {

    /** The UDP version we announce: pings and answers carry the part statuses and the complete source count. */
    public static final int UDP_VERSION = 4;

    private ClientReasks() {}

    /**
     * OP_REASKFILEPING: {@code file} asked for again; from UDP version 4 with the asker's parts of it, from version 3
     * with the number of complete sources it knows.
     */
    public record Ping(Hash16 file, ClientMessages.PartStatus parts, int completeSources) {
        public Ping {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(parts, "parts");
        }

        /** The ping for a client of {@code udpVersion}. */
        public Packet encode(int udpVersion) {
            PacketWriter out = new PacketWriter().hash16(this.file);
            if (udpVersion > 3) {
                this.parts.write(out);
            }
            if (udpVersion > 2) {
                out.u16(Math.min(this.completeSources, 0xFFFF));
            }
            return Packet.of(Protocol.EMULE, Opcodes.ClientUdp.REASKFILEPING.code(), out);
        }

        /**
         * The ping for a firewalled client of {@code udpVersion}, sent to its buddy, which knows it by {@code buddyId}.
         */
        public Packet encodeThroughBuddy(Hash16 buddyId, int udpVersion) {
            return new ThroughBuddy(buddyId, encode(udpVersion)).encode();
        }

        /** Reads a ping from a client of {@code udpVersion}; what it does not send is a complete file and no count. */
        public static Ping decode(Packet packet, int udpVersion) throws DecodeException {
            PacketReader in = packet.reader();
            Hash16 file = in.hash16();
            ClientMessages.PartStatus parts =
                    udpVersion > 3 ? ClientMessages.PartStatus.read(in) : ClientMessages.PartStatus.COMPLETE;
            int complete = udpVersion > 2 && in.remaining() >= 2 ? in.u16() : 0;
            return new Ping(file, parts, complete);
        }

        /** The file a ping asks for, whoever sent it. */
        public static Hash16 file(Packet packet) throws DecodeException {
            return packet.reader().hash16();
        }
    }

    /**
     * OP_REASKCALLBACKUDP: a ping for a firewalled client, sent to the buddy that knows it by {@code buddyId}
     * (big-endian bytes of the Kad id).
     */
    public record ThroughBuddy(Hash16 buddyId, Packet ping) {
        public ThroughBuddy {
            Objects.requireNonNull(buddyId, "buddyId");
            Objects.requireNonNull(ping, "ping");
        }

        public Packet encode() {
            PacketWriter out = new PacketWriter().hash16(this.buddyId).bytes(this.ping.payload());
            return Packet.of(Protocol.EMULE, Opcodes.ClientUdp.REASKCALLBACKUDP.code(), out);
        }

        public static ThroughBuddy decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            return new ThroughBuddy(in.hash16(), restAsPing(in));
        }
    }

    /**
     * OP_REASKCALLBACKTCP: a buddy passes on to the firewalled client it serves the ping of the client at
     * {@code requesterIp} ({@code Ipv4} form) and UDP {@code requesterPort}, where the answer goes.
     */
    public record Relayed(int requesterIp, int requesterPort, Packet ping) {
        public Relayed {
            Objects.requireNonNull(ping, "ping");
        }

        public Packet encode() {
            PacketWriter out = new PacketWriter()
                    .u32(Ipv4.toEd2k(this.requesterIp))
                    .u16(this.requesterPort)
                    .bytes(this.ping.payload());
            return Packet.of(Protocol.EMULE, Opcodes.Extended.REASKCALLBACKTCP.code(), out);
        }

        public static Relayed decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            return new Relayed(Ipv4.fromEd2k(in.u32()), in.u16(), restAsPing(in));
        }
    }

    // The rest of a message is a ping, which names its file at least.
    private static Packet restAsPing(PacketReader in) throws DecodeException {
        if (in.remaining() < Hash16.LENGTH) {
            throw new DecodeException("a re-ask without its file");
        }
        return Packet.of(Protocol.EMULE, Opcodes.ClientUdp.REASKFILEPING.code(), in.rest());
    }

    /** OP_REASKACK: the asker's place in the queue; from UDP version 4 with the uploader's parts of the file. */
    public record Ack(Optional<ClientMessages.PartStatus> parts, int rank) {
        public Ack {
            Objects.requireNonNull(parts, "parts");
        }

        /** The answer for a client of {@code udpVersion}. */
        public Packet encode(int udpVersion) {
            PacketWriter out = new PacketWriter();
            if (udpVersion > 3) {
                this.parts.orElse(ClientMessages.PartStatus.COMPLETE).write(out);
            }
            out.u16(Math.min(this.rank, 0xFFFF));
            return Packet.of(Protocol.EMULE, Opcodes.ClientUdp.REASKACK.code(), out);
        }

        /** Reads the answer of a client of {@code udpVersion}. */
        public static Ack decode(Packet packet, int udpVersion) throws DecodeException {
            PacketReader in = packet.reader();
            Optional<ClientMessages.PartStatus> parts =
                    udpVersion > 3 ? Optional.of(ClientMessages.PartStatus.read(in)) : Optional.empty();
            return new Ack(parts, in.u16());
        }
    }

    /** OP_QUEUEFULL: the uploader's queue takes no one more. */
    public static Packet queueFull() {
        return Packet.empty(Protocol.EMULE, Opcodes.ClientUdp.QUEUEFULL.code());
    }

    /** OP_FILENOTFOUND: the uploader does not share the file. */
    public static Packet fileNotFound() {
        return Packet.empty(Protocol.EMULE, Opcodes.ClientUdp.FILENOTFOUND.code());
    }
}
