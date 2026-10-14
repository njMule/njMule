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
package org.njmule.core.protocol.kad;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import org.njmule.core.kad.UInt128;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagCodec;

/**
 * Kad 2 messages. Ids are 128-bit numbers written as four little-endian words, most significant first; addresses are
 * host-order numbers written little-endian.
 */
public final class KadMessages {

    /** The Kad version we speak. */
    public static final int VERSION = 0x0A;

    /** Contacts per bootstrap answer at most. */
    public static final int MAX_BOOTSTRAP_CONTACTS = 20;

    /** Contacts per lookup answer at most. */
    public static final int MAX_LOOKUP_CONTACTS = 31;

    /** Results per keyword publish packet at most. */
    public static final int MAX_PUBLISHED_FILES = 50;

    private static final int CONTACT_SIZE = 16 + 4 + 2 + 2 + 1;
    private static final int MAX_RESULTS = 1_000;

    private KadMessages() {}

    /** A contact as packets carry it. */
    public record Contact(UInt128 id, int ip, int udpPort, int tcpPort, int version) {
        public Contact {
            Objects.requireNonNull(id, "id");
        }

        static Contact read(PacketReader in) throws DecodeException {
            UInt128 id = UInt128.read(in);
            int ip = (int) in.u32();
            int udp = in.u16();
            int tcp = in.u16();
            int version = in.u8();
            return new Contact(id, ip, udp, tcp, version);
        }

        void write(PacketWriter out) {
            this.id.write(out);
            out.u32(Integer.toUnsignedLong(this.ip))
                    .u16(this.udpPort)
                    .u16(this.tcpPort)
                    .u8(this.version);
        }
    }

    /** What a node says of itself in a hello or a bootstrap answer. */
    public record Self(UInt128 id, int tcpPort, int version) {
        public Self {
            Objects.requireNonNull(id, "id");
        }

        static Self read(PacketReader in) throws DecodeException {
            return new Self(UInt128.read(in), in.u16(), in.u8());
        }

        void write(PacketWriter out) {
            this.id.write(out);
            out.u16(this.tcpPort).u8(this.version);
        }
    }

    public static Packet bootstrapRequest() {
        return Packet.empty(Protocol.KAD, Opcodes.Kad.BOOTSTRAP_REQ.code());
    }

    /** KADEMLIA2_BOOTSTRAP_RES: the sender, then some of its contacts. */
    public record BootstrapAnswer(Self sender, List<Contact> contacts) {
        public BootstrapAnswer {
            Objects.requireNonNull(sender, "sender");
            contacts = List.copyOf(contacts);
        }

        public Packet encode() {
            PacketWriter out = new PacketWriter();
            this.sender.write(out);
            out.count16(this.contacts.size());
            this.contacts.forEach(c -> c.write(out));
            return Packet.of(Protocol.KAD, Opcodes.Kad.BOOTSTRAP_RES.code(), out);
        }

        public static BootstrapAnswer decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            Self sender = Self.read(in);
            int count = in.count(in.u16(), CONTACT_SIZE, MAX_BOOTSTRAP_CONTACTS * 10);
            List<Contact> contacts = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                contacts.add(Contact.read(in));
            }
            return new BootstrapAnswer(sender, contacts);
        }
    }

    /** KADEMLIA2_HELLO_REQ and _RES: the sender and a few tags. */
    public record Hello(Self sender, List<Tag> tags) {
        public Hello {
            Objects.requireNonNull(sender, "sender");
            tags = List.copyOf(tags);
        }

        public Packet encode(boolean answer) {
            PacketWriter out = new PacketWriter();
            this.sender.write(out);
            TagCodec.writeKadList(out, this.tags);
            Opcodes.Kad opcode = answer ? Opcodes.Kad.HELLO_RES : Opcodes.Kad.HELLO_REQ;
            return Packet.of(Protocol.KAD, opcode.code(), out);
        }

        public static Hello decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            return new Hello(Self.read(in), TagCodec.readKadList(in));
        }
    }

    /** KADEMLIA2_HELLO_RES_ACK: the sender id and no tags. */
    public static Packet helloAck(UInt128 sender) {
        PacketWriter out = new PacketWriter();
        sender.write(out);
        out.u8(0);
        return Packet.of(Protocol.KAD, Opcodes.Kad.HELLO_RES_ACK.code(), out);
    }

    public static UInt128 decodeHelloAck(Packet packet) throws DecodeException {
        return UInt128.read(packet.reader());
    }

    /** KADEMLIA2_REQ: up to {@code count} contacts close to {@code target}. */
    public record Request(int count, UInt128 target, UInt128 receiver) {
        public Request {
            // The count travels in the low five bits of its byte.
            if (count < 1 || count > MAX_LOOKUP_CONTACTS) {
                throw new IllegalArgumentException(
                        "a lookup asks for 1 to " + MAX_LOOKUP_CONTACTS + " contacts: " + count);
            }
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(receiver, "receiver");
        }

        public Packet encode() {
            PacketWriter out = new PacketWriter().u8(this.count);
            this.target.write(out);
            this.receiver.write(out);
            return Packet.of(Protocol.KAD, Opcodes.Kad.REQ.code(), out);
        }

        public static Request decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            int count = in.u8() & 0x1F;
            if (count == 0) {
                throw new DecodeException("a lookup request asks for no contact");
            }
            return new Request(count, UInt128.read(in), UInt128.read(in));
        }
    }

    /** KADEMLIA2_RES: contacts close to {@code target}. */
    public record Answer(UInt128 target, List<Contact> contacts) {
        public Answer {
            Objects.requireNonNull(target, "target");
            contacts = List.copyOf(contacts);
        }

        public Packet encode() {
            PacketWriter out = new PacketWriter();
            this.target.write(out);
            out.count8(this.contacts.size());
            this.contacts.forEach(c -> c.write(out));
            return Packet.of(Protocol.KAD, Opcodes.Kad.RES.code(), out);
        }

        public static Answer decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            UInt128 target = UInt128.read(in);
            int count = in.u8();
            if (in.remaining() != count * CONTACT_SIZE) {
                throw new DecodeException(count + " contacts do not fill the answer");
            }
            List<Contact> contacts = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                contacts.add(Contact.read(in));
            }
            return new Answer(target, contacts);
        }
    }

    /** KADEMLIA2_SEARCH_KEY_REQ: files under a keyword, with an optional search expression. */
    // A message decoded or about to be sent, never compared.
    @SuppressWarnings("ArrayRecordComponent")
    public record KeywordSearch(UInt128 target, int start, byte[] expression) {
        public KeywordSearch {
            Objects.requireNonNull(target, "target");
            expression = expression.clone();
        }

        @Override
        public byte[] expression() {
            return this.expression.clone();
        }

        public Packet encode() {
            PacketWriter out = new PacketWriter();
            this.target.write(out);
            boolean withExpression = this.expression.length > 0;
            out.u16((this.start & 0x7FFF) | (withExpression ? 0x8000 : 0));
            out.bytes(this.expression);
            return Packet.of(Protocol.KAD, Opcodes.Kad.SEARCH_KEY_REQ.code(), out);
        }

        public static KeywordSearch decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            UInt128 target = UInt128.read(in);
            int start = in.u16();
            byte[] expression = (start & 0x8000) != 0 ? in.rest() : new byte[0];
            return new KeywordSearch(target, start & 0x7FFF, expression);
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof KeywordSearch other
                    && this.target.equals(other.target)
                    && this.start == other.start
                    && Arrays.equals(this.expression, other.expression);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.target, this.start) * 31 + Arrays.hashCode(this.expression);
        }

        @Override
        public String toString() {
            return "KeywordSearch[" + this.target + ", " + this.expression.length + " bytes]";
        }
    }

    /** KADEMLIA2_SEARCH_SOURCE_REQ: sources of a file of {@code size} bytes. */
    public record SourceSearch(UInt128 target, int start, long size) {
        public SourceSearch {
            Objects.requireNonNull(target, "target");
        }

        public Packet encode() {
            PacketWriter out = new PacketWriter();
            this.target.write(out);
            out.u16(this.start & 0x7FFF).u64(this.size);
            return Packet.of(Protocol.KAD, Opcodes.Kad.SEARCH_SOURCE_REQ.code(), out);
        }

        public static SourceSearch decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            return new SourceSearch(UInt128.read(in), in.u16() & 0x7FFF, in.u64());
        }
    }

    /** KADEMLIA2_SEARCH_NOTES_REQ: comments on a file of {@code size} bytes (0: any). */
    public record NotesSearch(UInt128 target, long size) {
        public NotesSearch {
            Objects.requireNonNull(target, "target");
        }

        public Packet encode() {
            PacketWriter out = new PacketWriter();
            this.target.write(out);
            out.u64(this.size);
            return Packet.of(Protocol.KAD, Opcodes.Kad.SEARCH_NOTES_REQ.code(), out);
        }

        public static NotesSearch decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            return new NotesSearch(UInt128.read(in), in.u64());
        }
    }

    /** One entry of a search answer or a keyword publish: an id and its tags. */
    public record Entry(UInt128 id, List<Tag> tags) {
        public Entry {
            Objects.requireNonNull(id, "id");
            tags = List.copyOf(tags);
        }

        static Entry read(PacketReader in) throws DecodeException {
            return new Entry(UInt128.read(in), TagCodec.readKadList(in));
        }

        void write(PacketWriter out) {
            this.id.write(out);
            TagCodec.writeKadList(out, this.tags);
        }

        /** Bytes the entry takes in a packet. */
        int size() {
            PacketWriter out = new PacketWriter();
            this.write(out);
            return out.size();
        }
    }

    /** KADEMLIA2_SEARCH_RES: what the sender holds for {@code target}. */
    public record SearchAnswer(UInt128 sender, UInt128 target, List<Entry> results) {
        public SearchAnswer {
            Objects.requireNonNull(sender, "sender");
            Objects.requireNonNull(target, "target");
            results = List.copyOf(results);
        }

        public Packet encode() {
            PacketWriter out = new PacketWriter();
            this.sender.write(out);
            this.target.write(out);
            out.count16(this.results.size());
            this.results.forEach(r -> r.write(out));
            return Packet.of(Protocol.KAD, Opcodes.Kad.SEARCH_RES.code(), out);
        }

        public static SearchAnswer decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            UInt128 sender = UInt128.read(in);
            UInt128 target = UInt128.read(in);
            int count = in.count(in.u16(), 16 + 1, MAX_RESULTS);
            List<Entry> results = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                results.add(Entry.read(in));
            }
            return new SearchAnswer(sender, target, results);
        }

        /**
         * Answers no larger than {@code maxBytes} each that together carry {@code results}; an entry too large for an
         * answer of its own is left out.
         */
        public static List<Packet> split(UInt128 sender, UInt128 target, List<Entry> results, int maxBytes) {
            List<Packet> packets = new ArrayList<>();
            List<Entry> batch = new ArrayList<>();
            int empty = 2 + 16 + 16 + 2;
            int size = empty;
            for (Entry entry : results) {
                int entrySize = entry.size();
                if (empty + entrySize > maxBytes) {
                    continue;
                }
                if (size + entrySize > maxBytes) {
                    packets.add(new SearchAnswer(sender, target, batch).encode());
                    batch = new ArrayList<>();
                    size = empty;
                }
                batch.add(entry);
                size += entrySize;
            }
            if (!batch.isEmpty()) {
                packets.add(new SearchAnswer(sender, target, batch).encode());
            }
            return packets;
        }
    }

    /** KADEMLIA2_PUBLISH_KEY_REQ: files under a keyword. */
    public record KeywordPublish(UInt128 keyword, List<Entry> files) {
        public KeywordPublish {
            Objects.requireNonNull(keyword, "keyword");
            files = List.copyOf(files);
        }

        public Packet encode() {
            PacketWriter out = new PacketWriter();
            this.keyword.write(out);
            out.count16(this.files.size());
            this.files.forEach(f -> f.write(out));
            return Packet.of(Protocol.KAD, Opcodes.Kad.PUBLISH_KEY_REQ.code(), out);
        }

        public static KeywordPublish decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            UInt128 keyword = UInt128.read(in);
            int count = in.count(in.u16(), 16 + 1, MAX_RESULTS);
            List<Entry> files = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                files.add(Entry.read(in));
            }
            return new KeywordPublish(keyword, files);
        }
    }

    /** KADEMLIA2_PUBLISH_SOURCE_REQ: the publisher has {@code file}. */
    public record SourcePublish(UInt128 file, UInt128 source, List<Tag> tags) {
        public SourcePublish {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(source, "source");
            tags = List.copyOf(tags);
        }

        public Packet encode() {
            PacketWriter out = new PacketWriter();
            this.file.write(out);
            this.source.write(out);
            TagCodec.writeKadList(out, this.tags);
            return Packet.of(Protocol.KAD, Opcodes.Kad.PUBLISH_SOURCE_REQ.code(), out);
        }

        public static SourcePublish decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            return new SourcePublish(UInt128.read(in), UInt128.read(in), TagCodec.readKadList(in));
        }
    }

    /** KADEMLIA2_PUBLISH_NOTES_REQ: the comment and rating on {@code file} of the node {@code publisher}. */
    public record NotesPublish(UInt128 file, UInt128 publisher, List<Tag> tags) {
        public NotesPublish {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(publisher, "publisher");
            tags = List.copyOf(tags);
        }

        public Packet encode() {
            PacketWriter out = new PacketWriter();
            this.file.write(out);
            this.publisher.write(out);
            TagCodec.writeKadList(out, this.tags);
            return Packet.of(Protocol.KAD, Opcodes.Kad.PUBLISH_NOTES_REQ.code(), out);
        }

        public static NotesPublish decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            return new NotesPublish(UInt128.read(in), UInt128.read(in), TagCodec.readKadList(in));
        }
    }

    /** KADEMLIA2_PUBLISH_RES: the publish of {@code target} was stored; load 0..100. */
    public record PublishAnswer(UInt128 target, int load) {
        public PublishAnswer {
            Objects.requireNonNull(target, "target");
        }

        public Packet encode() {
            PacketWriter out = new PacketWriter();
            this.target.write(out);
            out.u8(this.load);
            return Packet.of(Protocol.KAD, Opcodes.Kad.PUBLISH_RES.code(), out);
        }

        public static PublishAnswer decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            return new PublishAnswer(UInt128.read(in), in.u8());
        }
    }

    /** KADEMLIA2_FIREWALLED2_REQ: our TCP port, user hash and connect options. */
    public record FirewallCheck(int tcpPort, UInt128 userHash, int connectOptions) {
        public FirewallCheck {
            Objects.requireNonNull(userHash, "userHash");
        }

        public Packet encode() {
            PacketWriter out = new PacketWriter().u16(this.tcpPort);
            this.userHash.write(out);
            out.u8(this.connectOptions);
            return Packet.of(Protocol.KAD, Opcodes.Kad.FIREWALLED2_REQ.code(), out);
        }

        public static FirewallCheck decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            return new FirewallCheck(in.u16(), UInt128.read(in), in.u8());
        }
    }

    /** KADEMLIA_FIREWALLED_RES: the address the helper sees us at. */
    public static Packet firewallAnswer(int ip) {
        return Packet.of(
                Protocol.KAD, Opcodes.Kad.FIREWALLED_RES.code(), new PacketWriter().u32(Integer.toUnsignedLong(ip)));
    }

    public static int decodeFirewallAnswer(Packet packet) throws DecodeException {
        if (packet.length() != 4) {
            throw new DecodeException("a firewall answer has 4 bytes");
        }
        return (int) packet.reader().u32();
    }

    /**
     * KADEMLIA_FINDBUDDY_REQ: a firewalled node looks for a buddy under {@code buddyId}, the complement of its Kad id,
     * and says how to reach it: its user hash and TCP port.
     */
    public record FindBuddy(UInt128 buddyId, UInt128 userHash, int tcpPort) {
        public FindBuddy {
            Objects.requireNonNull(buddyId, "buddyId");
            Objects.requireNonNull(userHash, "userHash");
        }

        public Packet encode() {
            PacketWriter out = new PacketWriter();
            this.buddyId.write(out);
            this.userHash.write(out);
            out.u16(this.tcpPort);
            return Packet.of(Protocol.KAD, Opcodes.Kad.FINDBUDDY_REQ.code(), out);
        }

        public static FindBuddy decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            return new FindBuddy(UInt128.read(in), UInt128.read(in), in.u16());
        }
    }

    /**
     * KADEMLIA_FINDBUDDY_RES: a node offers to be the buddy of {@code buddyId}; newer nodes add their connect options.
     */
    public record BuddyOffer(UInt128 buddyId, UInt128 userHash, int tcpPort, OptionalInt connectOptions) {
        public BuddyOffer {
            Objects.requireNonNull(buddyId, "buddyId");
            Objects.requireNonNull(userHash, "userHash");
            Objects.requireNonNull(connectOptions, "connectOptions");
        }

        public Packet encode() {
            PacketWriter out = new PacketWriter();
            this.buddyId.write(out);
            this.userHash.write(out);
            out.u16(this.tcpPort);
            this.connectOptions.ifPresent(out::u8);
            return Packet.of(Protocol.KAD, Opcodes.Kad.FINDBUDDY_RES.code(), out);
        }

        public static BuddyOffer decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            UInt128 buddyId = UInt128.read(in);
            UInt128 userHash = UInt128.read(in);
            int port = in.u16();
            return new BuddyOffer(
                    buddyId, userHash, port, in.remaining() > 0 ? OptionalInt.of(in.u8()) : OptionalInt.empty());
        }
    }

    /**
     * KADEMLIA_CALLBACK_REQ, sent plain to the buddy of a firewalled source: the source is asked to connect to our
     * {@code tcpPort} for {@code file}.
     */
    public record CallbackRequest(UInt128 buddyId, UInt128 file, int tcpPort) {
        public CallbackRequest {
            Objects.requireNonNull(buddyId, "buddyId");
            Objects.requireNonNull(file, "file");
        }

        public Packet encode() {
            PacketWriter out = new PacketWriter();
            this.buddyId.write(out);
            this.file.write(out);
            out.u16(this.tcpPort);
            return Packet.of(Protocol.KAD, Opcodes.Kad.CALLBACK_REQ.code(), out);
        }

        public static CallbackRequest decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            return new CallbackRequest(UInt128.read(in), UInt128.read(in), in.u16());
        }
    }

    /**
     * KADEMLIA2_FIREWALLUDP: the datagram a helper of the UDP firewall test sends to {@code port}; {@code error} is
     * non-zero when the helper knew us already, which makes the outcome worthless.
     */
    public record FirewallUdp(int error, int port) {
        public Packet encode() {
            return Packet.of(
                    Protocol.KAD,
                    Opcodes.Kad.FIREWALLUDP.code(),
                    new PacketWriter().u8(this.error).u16(this.port));
        }

        public static FirewallUdp decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            return new FirewallUdp(in.u8(), in.u16());
        }
    }

    public static Packet ping() {
        return Packet.empty(Protocol.KAD, Opcodes.Kad.PING.code());
    }

    /** KADEMLIA2_PONG: the UDP port the answering node saw us on. */
    public static Packet pong(int port) {
        return Packet.of(Protocol.KAD, Opcodes.Kad.PONG.code(), new PacketWriter().u16(port));
    }

    public static int decodePong(Packet packet) throws DecodeException {
        return packet.reader().u16();
    }
}
