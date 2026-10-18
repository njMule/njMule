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

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.NjMuleInfo;
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
import org.njmule.core.util.Ipv6;

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
    public static final int MISC1_UDP_VERSION_SHIFT = 24;
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

    // Feature bits of the newer clients' options tag.
    public static final int MOD_MISC_EXTENDED_SX = 1;
    public static final int MOD_MISC_UTP_TRAVERSAL = 1 << 1;
    public static final int MOD_MISC_IPV6 = 1 << 2;

    /** The client id of a client with no IPv4 address, reached over IPv6 only. */
    public static final long NO_IPV4 = 0xFFFFFFFFL;

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
                new Tag.IntTag(TagName.id(ClientTag.EMULE_VERSION), Integer.toUnsignedLong(ClientVersion.njMule())),
                // Clients show it after the software their version tag names: njMule and its full version.
                new Tag.StringTag(TagName.id(ClientTag.MOD_VERSION), NjMuleInfo.NAME + " " + NjMuleInfo.version()),
                new Tag.IntTag(TagName.id(ClientTag.MOD_MISCOPTIONS), MOD_MISC_IPV6));
        return new Hello(userHash, clientId, tcpPort, tags, serverIp, serverPort);
    }

    /**
     * This greeting with our public IPv6 address, at which clients may reach us, and the extended source exchange,
     * which carries the IPv6 addresses of sources.
     */
    public Hello withIpv6(Inet6Address ours) {
        TagName options = TagName.id(ClientTag.MOD_MISCOPTIONS);
        List<Tag> more = new ArrayList<>(this.tags.size() + 1);
        for (Tag tag : this.tags) {
            more.add(
                    tag.name().equals(options) && tag instanceof Tag.IntTag bits
                            ? new Tag.IntTag(options, bits.value() | MOD_MISC_IPV6 | MOD_MISC_EXTENDED_SX)
                            : tag);
        }
        more.add(new Tag.HashTag(TagName.id(ClientTag.MOD_IP_V6), Hash16.of(ours.getAddress())));
        return new Hello(this.userHash, this.clientId, this.tcpPort, more, this.serverIp, this.serverPort);
    }

    /** This greeting saying that we reach firewalled clients, and are reached, through routers over uTP. */
    public Hello withUtpTraversal() {
        TagName options = TagName.id(ClientTag.MOD_MISCOPTIONS);
        List<Tag> more = new ArrayList<>(this.tags.size());
        for (Tag tag : this.tags) {
            more.add(
                    tag.name().equals(options) && tag instanceof Tag.IntTag bits
                            ? new Tag.IntTag(options, bits.value() | MOD_MISC_UTP_TRAVERSAL)
                            : tag);
        }
        return new Hello(this.userHash, this.clientId, this.tcpPort, more, this.serverIp, this.serverPort);
    }

    /** This greeting telling the client the address we see it at, so that it learns its own. */
    public Hello toPeerAt(InetAddress seen) {
        Tag tag = seen instanceof Inet6Address
                ? new Tag.HashTag(TagName.id(ClientTag.MOD_YOUR_IP), Hash16.of(seen.getAddress()))
                : new Tag.IntTag(TagName.id(ClientTag.MOD_YOUR_IP), Ipv4.toEd2k(Ipv4.fromBytes(seen.getAddress())));
        return with(tag);
    }

    private Hello with(Tag tag) {
        List<Tag> more = new ArrayList<>(this.tags.size() + 1);
        more.addAll(this.tags);
        more.add(tag);
        return new Hello(this.userHash, this.clientId, this.tcpPort, more, this.serverIp, this.serverPort);
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

    /** The version of the UDP messages between clients it takes, 0 for none; from 3 up it takes re-asks. */
    public int udpVersion() {
        return miscOptions1() >>> MISC1_UDP_VERSION_SHIFT & 0xF;
    }

    public int kadPort() {
        return (int) (Tags.intValue(this.tags, ClientTag.EMULE_UDPPORTS).orElse(0) >>> 16 & 0xFFFF);
    }

    /** The Kad version of the client's node, 0 when it runs none. */
    public int kadVersion() {
        return miscOptions2() & 0x0F;
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
        return (miscOptions2() & MISC2_DIRECT_UDP_CALLBACK) != 0;
    }

    /** Whether the client exchanges AICH hashes and recovery data. */
    public boolean supportsAich() {
        return (miscOptions1() >>> MISC1_AICH_SHIFT & 7) > 0;
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

    /** What the client tells of its software besides its version, such as the name of a modified client. */
    public Optional<String> modVersion() {
        return Tags.stringValue(this.tags, ClientTag.MOD_VERSION);
    }

    /** The feature bits of newer clients, 0 when it sends none. */
    public int modMiscOptions() {
        return (int) Tags.intValue(this.tags, ClientTag.MOD_MISCOPTIONS).orElse(0);
    }

    /** Whether the client exchanges sources the extended way, IPv6 addresses included. */
    public boolean supportsExtendedSourceExchange() {
        return (modMiscOptions() & MOD_MISC_EXTENDED_SX) != 0;
    }

    /** The public IPv6 address the client gives, if it gives one peers on the Internet can reach. */
    public Optional<Inet6Address> ipv6() {
        return Tags.hashValue(this.tags, ClientTag.MOD_IP_V6).flatMap(hash -> Ipv6.publicOf(hash.toByteArray()));
    }

    /** Whether the client has an IPv4 address: one reached over IPv6 only says it has none. */
    public boolean hasIpv4() {
        return this.clientId != NO_IPV4;
    }
}
