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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.util.Bytes;
import org.njmule.core.util.Ipv4;

/**
 * Traversal of the routers of two firewalled clients, as newer clients do it. The requester asks the target's Kad buddy
 * for a rendezvous (a re-ask through the buddy whose file hash is zero, carrying OP_RENDEZVOUS); the buddy passes it on
 * over TCP with the requester's address as it sees it; both sides send hole punches to each other's address, and a uTP
 * connection opens through the holes, uTP or QUIC, inside frames of the reserved UDP protocol 0xB2 on the client UDP
 * port.
 */
public final class NatTraversalMessages {

    /** The frame types of protocol 0xB2. */
    public static final int FRAME_UTP = 0x00;

    public static final int FRAME_QUIC = 0x01;
    public static final int FRAME_CAPS = 0x02;
    public static final int FRAME_CAPS_ACK = 0x03;
    public static final int FRAME_KEY = 0xFF;

    /** Connect options: the client reaches others through their routers over uTP. */
    public static final int OPTION_UTP = 0x80;

    /** Connect options: the client reaches others through their routers over QUIC. */
    public static final int OPTION_QUIC = 0x40;

    /** Connect options of a rendezvous request: the buddy may tell the requester the target's address. */
    public static final int OPTION_HINT_WANTED = 0x20;

    /** The transport a rendezvous asks for. */
    public static final int TRANSPORT_UTP = 1;

    public static final int TRANSPORT_QUIC = 2;

    private static final int CAPS_MAGIC = 0x43514145;
    private static final int CAPS_VERSION = 1;
    private static final int CAPS_LENGTH = 54;
    // An IPv6 address follows in a buddy's forward.
    private static final long IPV6_MARK = 0xFFFFFFFFL;

    private NatTraversalMessages() {}

    /** The empty datagram that opens a hole in the sender's router towards the receiver. */
    public static Packet holePunch() {
        return Packet.empty(Protocol.EMULE, Opcodes.ClientUdp.HOLEPUNCH.code());
    }

    /** A uTP packet in its frame. */
    public static Packet utpFrame(byte[] utp) {
        return Packet.of(Protocol.UDP_RESERVED_2, FRAME_UTP, utp);
    }

    /** A QUIC datagram in its frame. */
    public static Packet quicFrame(byte[] quic) {
        return Packet.of(Protocol.UDP_RESERVED_2, FRAME_QUIC, quic);
    }

    /**
     * What each side of a QUIC connection writes first on its stream: "EAQN1", its user hash and the hash it expects
     * the other side to have (zero when unknown).
     */
    public record QuicProof(Hash16 sender, Hash16 receiver) {
        public static final int LENGTH = 37;

        private static final byte[] MAGIC = {'E', 'A', 'Q', 'N', '1'};

        public QuicProof {
            Objects.requireNonNull(sender, "sender");
            Objects.requireNonNull(receiver, "receiver");
        }

        public byte[] encode() {
            return new PacketWriter()
                    .bytes(MAGIC)
                    .hash16(this.sender)
                    .hash16(this.receiver)
                    .toByteArray();
        }

        public static QuicProof decode(byte[] proof) throws DecodeException {
            PacketReader in = PacketReader.of(proof);
            if (proof.length != LENGTH || !Arrays.equals(in.bytes(MAGIC.length), MAGIC)) {
                throw new DecodeException("not a QUIC proof");
            }
            return new QuicProof(in.hash16(), in.hash16());
        }
    }

    /**
     * OP_RENDEZVOUS: who asks, its connect options, the file it wants (zero for none), and its public IPv4 address
     * ({@code Ipv4} form) and UDP port when it knows them (0 otherwise).
     */
    public record Rendezvous(
            Hash16 requester, int options, Hash16 file, int requesterIp, int requesterPort, int transport) {
        public Rendezvous {
            Objects.requireNonNull(requester, "requester");
            Objects.requireNonNull(file, "file");
        }

        /** The request to the target's buddy {@code buddyId}, as a re-ask through it with a zero file hash. */
        public Packet encodeThroughBuddy(Hash16 buddyId) {
            PacketWriter out = new PacketWriter()
                    .hash16(buddyId)
                    .hash16(Hash16.ZERO)
                    .u8(Opcodes.ClientUdp.RENDEZVOUS.code())
                    .hash16(this.requester)
                    .u8(this.options)
                    .hash16(this.file);
            if (this.requesterIp != 0 && this.requesterPort != 0) {
                out.u32(Ipv4.toEd2k(this.requesterIp)).u16(this.requesterPort).u8(this.transport);
            }
            return Packet.of(Protocol.EMULE, Opcodes.ClientUdp.REASKCALLBACKUDP.code(), out);
        }

        /** Reads the payload after OP_RENDEZVOUS; the fields after the options may be missing. */
        public static Rendezvous decode(Bytes payload) throws DecodeException {
            PacketReader in = PacketReader.of(payload.toByteArray());
            Hash16 requester = in.hash16();
            int options = in.u8();
            Hash16 file = in.remaining() >= Hash16.LENGTH ? in.hash16() : Hash16.ZERO;
            int ip = 0;
            int port = 0;
            if (in.remaining() >= 6) {
                ip = Ipv4.fromEd2k(in.u32());
                port = in.u16();
            }
            int transport = in.remaining() >= 1 ? in.u8() : 0;
            return new Rendezvous(requester, options, file, ip, port, transport);
        }
    }

    /**
     * What a buddy passes on over TCP that is not a re-ask: the requester's address as the buddy saw it, and the packet
     * it carries, by its opcode.
     */
    public record Forward(InetSocketAddress requester, int opcode, Bytes payload) {
        public Forward {
            Objects.requireNonNull(requester, "requester");
            Objects.requireNonNull(payload, "payload");
        }

        /**
         * Reads an OP_REASKCALLBACKTCP, {@code <IPv4 | 0xFFFFFFFF IPv6><port u16><zero hash><opcode><payload>}; empty
         * for a re-ask, whose file hash is not zero.
         */
        public static Optional<Forward> decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            long first = in.u32();
            InetAddress address;
            try {
                address = first == IPV6_MARK
                        ? InetAddress.getByAddress(in.bytes(16))
                        : InetAddress.getByAddress(Ipv4.toBytes(Ipv4.fromEd2k(first)));
            } catch (UnknownHostException e) {
                throw new DecodeException("an unreadable address");
            }
            int port = in.u16();
            if (in.remaining() < Hash16.LENGTH + 1 || !in.hash16().isZero()) {
                return Optional.empty();
            }
            int opcode = in.u8();
            return Optional.of(new Forward(new InetSocketAddress(address, port), opcode, Bytes.of(in.rest())));
        }
    }

    /**
     * The capabilities a client tells another before choosing a transport, or its answer: its connect options, its user
     * hash, the hash it expects the receiver to have (zero when unknown) and the file between them (zero for none).
     */
    public record Caps(boolean answer, int options, Hash16 sender, Hash16 expected, Hash16 file) {
        public Caps {
            Objects.requireNonNull(sender, "sender");
            Objects.requireNonNull(expected, "expected");
            Objects.requireNonNull(file, "file");
        }

        public Packet encode() {
            PacketWriter out = new PacketWriter()
                    .u32(CAPS_MAGIC)
                    .u8(CAPS_VERSION)
                    .u8(this.options)
                    .hash16(this.sender)
                    .hash16(this.expected)
                    .hash16(this.file);
            return Packet.of(Protocol.UDP_RESERVED_2, this.answer ? FRAME_CAPS_ACK : FRAME_CAPS, out);
        }

        public static Caps decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            if (in.remaining() < CAPS_LENGTH || in.u32() != CAPS_MAGIC || in.u8() != CAPS_VERSION) {
                throw new DecodeException("not a capability frame of version 1");
            }
            int options = in.u8();
            return new Caps(packet.opcode() == FRAME_CAPS_ACK, options, in.hash16(), in.hash16(), in.hash16());
        }
    }
}
