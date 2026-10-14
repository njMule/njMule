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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.io.StringEncoding;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.tag.ClientTag;
import org.njmule.core.protocol.tag.Ed2kTagFormat;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagCodec;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.protocol.tag.Tags;
import org.njmule.core.util.Ipv4;

/**
 * The greeting two clients exchange first (OP_HELLO, then OP_HELLOANSWER): {@code [<16>]<user hash 16><client id
 * u32><tcp port u16><tag count u32><tags><server ip u32><server port u16>}, where only OP_HELLO starts with the hash
 * size. The tags announce name, versions, ports and features.
 */
public record Hello(Hash16 userHash, long clientId, int tcpPort, List<Tag> tags, int serverIp, int serverPort) {

    // Feature bits of the first options tag.
    /** The AICH version, in bits 29-31: 1 for recovery data of parts. */
    public static final int MISC1_AICH_SHIFT = 29;

    public static final int MISC1_UNICODE = 1 << 28;
    public static final int MISC1_DATA_COMPRESSION = 1 << 20;
    public static final int MISC1_NO_VIEW_SHARED_FILES = 1 << 2;

    // Feature bits of the second options tag.
    public static final int MISC2_LARGE_FILES = 1 << 4;
    public static final int MISC2_SOURCE_EXCHANGE2 = 1 << 10;
    public static final int MISC2_SUPPORTS_CAPTCHA = 1 << 11;
    public static final int MISC2_SUPPORTS_CRYPT = 1 << 7;
    public static final int MISC2_REQUESTS_CRYPT = 1 << 8;
    public static final int MISC2_REQUIRES_CRYPT = 1 << 9;
    /** TCP-firewalled on Kad with an open UDP port: reached by a direct UDP callback. */
    public static final int MISC2_DIRECT_UDP_CALLBACK = 1 << 12;

    private static final int HASH_SIZE = 16;
    private static final int MAX_TAGS = 64;

    public Hello {
        Objects.requireNonNull(userHash, "userHash");
        tags = List.copyOf(tags);
    }

    /** Our greeting; integer tags stay within 32 bits, which every client reads. */
    public static Hello of(
            Hash16 userHash,
            long clientId,
            int tcpPort,
            String nick,
            int udpPort,
            int kadPort,
            int miscOptions1,
            int miscOptions2,
            int serverIp,
            int serverPort) {
        List<Tag> tags = List.of(
                new Tag.StringTag(TagName.id(ClientTag.NAME), nick),
                new Tag.IntTag(TagName.id(ClientTag.VERSION), ClientVersion.EDONKEY),
                new Tag.IntTag(
                        TagName.id(ClientTag.EMULE_UDPPORTS), (long) (kadPort & 0xFFFF) << 16 | (udpPort & 0xFFFF)),
                new Tag.IntTag(TagName.id(ClientTag.EMULE_MISCOPTIONS1), Integer.toUnsignedLong(miscOptions1)),
                new Tag.IntTag(TagName.id(ClientTag.EMULE_MISCOPTIONS2), Integer.toUnsignedLong(miscOptions2)),
                new Tag.IntTag(TagName.id(ClientTag.EMULE_VERSION), Integer.toUnsignedLong(ClientVersion.njMule())));
        return new Hello(userHash, clientId, tcpPort, tags, serverIp, serverPort);
    }

    /**
     * This greeting with the address of our Kad buddy ({@code Ipv4} form) and its UDP port, after the UDP ports as
     * clients expect them.
     */
    public Hello withBuddy(int buddyIp, int buddyUdpPort) {
        List<Tag> withBuddy = new ArrayList<>(this.tags.size() + 2);
        for (Tag tag : this.tags) {
            withBuddy.add(tag);
            if (tag.name().equals(TagName.id(ClientTag.EMULE_UDPPORTS))) {
                withBuddy.add(new Tag.IntTag(TagName.id(ClientTag.EMULE_BUDDYIP), Ipv4.toEd2k(buddyIp)));
                withBuddy.add(new Tag.IntTag(TagName.id(ClientTag.EMULE_BUDDYUDP), buddyUdpPort & 0xFFFF));
            }
        }
        return new Hello(this.userHash, this.clientId, this.tcpPort, withBuddy, this.serverIp, this.serverPort);
    }

    /** OP_HELLO, or OP_HELLOANSWER when {@code answer}. */
    public Packet encode(boolean answer) {
        PacketWriter out = new PacketWriter();
        if (!answer) {
            out.u8(HASH_SIZE);
        }
        out.hash16(this.userHash).u32(this.clientId).u16(this.tcpPort).u32(this.tags.size());
        TagCodec.writeEd2kList(out, this.tags, Ed2kTagFormat.OLD, StringEncoding.UTF8);
        out.u32(Ipv4.toEd2k(this.serverIp)).u16(this.serverPort);
        Opcodes.Client opcode = answer ? Opcodes.Client.HELLOANSWER : Opcodes.Client.HELLO;
        return Packet.of(Protocol.EDONKEY, opcode.code(), out);
    }

    /** Decodes OP_HELLO or OP_HELLOANSWER; bytes after the server address are ignored. */
    public static Hello decode(Packet packet) throws DecodeException {
        PacketReader in = packet.reader();
        if (packet.opcode() == Opcodes.Client.HELLO.code()) {
            in.u8();
        }
        Hash16 hash = in.hash16();
        long clientId = in.u32();
        int port = in.u16();
        List<Tag> tags = TagCodec.readEd2kList(in, in.u32(), MAX_TAGS);
        int serverIp = Ipv4.fromEd2k(in.u32());
        int serverPort = in.u16();
        return new Hello(hash, clientId, port, tags, serverIp, serverPort);
    }

    public Optional<String> nick() {
        return Tags.stringValue(this.tags, ClientTag.NAME);
    }

    /** The listening port: a port tag, when present, overrides the header. */
    public int listenPort() {
        return (int) (Tags.intValue(this.tags, ClientTag.PORT).orElse(this.tcpPort) & 0xFFFF);
    }

    public int udpPort() {
        return (int) (Tags.intValue(this.tags, ClientTag.EMULE_UDPPORTS).orElse(0) & 0xFFFF);
    }

    public int kadPort() {
        return (int) (Tags.intValue(this.tags, ClientTag.EMULE_UDPPORTS).orElse(0) >>> 16 & 0xFFFF);
    }

    /** The address ({@code Ipv4} form) of the Kad buddy of a firewalled client, 0 when it names none. */
    public int buddyIp() {
        return Ipv4.fromEd2k(Tags.intValue(this.tags, ClientTag.EMULE_BUDDYIP).orElse(0));
    }

    /** The UDP port of the client's Kad buddy, 0 when it names none. */
    public int buddyUdpPort() {
        return (int) (Tags.intValue(this.tags, ClientTag.EMULE_BUDDYUDP).orElse(0) & 0xFFFF);
    }

    /** Whether the client, firewalled, takes a direct UDP callback on its Kad port. */
    public boolean supportsDirectCallback() {
        return (this.miscOptions2() & MISC2_DIRECT_UDP_CALLBACK) != 0;
    }

    /** Whether the client exchanges AICH hashes and recovery data. */
    public boolean supportsAich() {
        return (this.miscOptions1() >>> MISC1_AICH_SHIFT & 7) > 0;
    }

    public int miscOptions1() {
        return (int) Tags.intValue(this.tags, ClientTag.EMULE_MISCOPTIONS1).orElse(0);
    }

    public int miscOptions2() {
        return (int) Tags.intValue(this.tags, ClientTag.EMULE_MISCOPTIONS2).orElse(0);
    }

    /** The extended version tag; only clients speaking the extended protocol send it. */
    public OptionalLong emuleVersion() {
        return Tags.intValue(this.tags, ClientTag.EMULE_VERSION);
    }
}
