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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
import org.njmule.core.util.Ipv6;

/**
 * Source exchange, second form: clients ask each other for more sources of a file. Answers of version 1 and 2 carry
 * eD2K ids, versions 3 and 4 the address in host order; version 2 adds the user hash and version 4 the connect options.
 * Between clients that both announce it, the extended form of version 1 gives each source tags instead, its IPv6
 * address among them, but no user hash.
 */
public final class SourceExchange {

    /** The highest version we speak. */
    public static final int VERSION = 4;

    /** The version of the extended form. */
    public static final int EXTENDED_VERSION = 1;

    /** Sources per answer at most. */
    public static final int MAX_SOURCES = 501;

    // Tags of one source of an extended answer at most: a handful are defined.
    private static final int MAX_SOURCE_TAGS = 16;

    private SourceExchange() {}

    /**
     * A source; {@code ip} is in host order, zero for a LowID with {@code lowId} set or, with {@link Hello#NO_IPV4},
     * for a source at its IPv6 address alone.
     */
    public record Source(
            int ip,
            long lowId,
            int port,
            int serverIp,
            int serverPort,
            Optional<Hash16> userHash,
            int cryptOptions,
            Optional<Inet6Address> ipv6) {
        public Source {
            Objects.requireNonNull(userHash, "userHash");
            Objects.requireNonNull(ipv6, "ipv6");
        }

        /** A source without an IPv6 address. */
        public Source(
                int ip,
                long lowId,
                int port,
                int serverIp,
                int serverPort,
                Optional<Hash16> userHash,
                int cryptOptions) {
            this(ip, lowId, port, serverIp, serverPort, userHash, cryptOptions, Optional.empty());
        }

        /** A source reached at its IPv6 address alone. */
        public static Source ipv6Only(Inet6Address address, int port, Optional<Hash16> userHash) {
            return new Source(0, Hello.NO_IPV4, port, 0, 0, userHash, 0, Optional.of(address));
        }

        public boolean isLowId() {
            return this.ip == 0 && this.lowId != Hello.NO_IPV4;
        }

        public boolean isIpv6Only() {
            return this.ip == 0 && this.lowId == Hello.NO_IPV4;
        }
    }

    /** An answer with the sources of {@code file}. */
    public record Answer(int version, Hash16 file, List<Source> sources) {
        public Answer {
            if (version < 1 || version > VERSION) {
                throw new IllegalArgumentException("source exchange version " + version);
            }
            Objects.requireNonNull(file, "file");
            sources = List.copyOf(sources);
        }

        /** The answer of {@link #version()}; sources at an IPv6 address alone are left out. */
        public Packet encode() {
            List<Source> sent =
                    this.sources.stream().filter(s -> !s.isIpv6Only()).toList();
            if (sent.size() > MAX_SOURCES) {
                throw new IllegalArgumentException(
                        this.sources.size() + " sources exceed the " + MAX_SOURCES + " of an answer");
            }
            PacketWriter out = new PacketWriter().u8(this.version).hash16(this.file);
            out.u16(sent.size());
            for (Source s : sent) {
                long id;
                if (s.isLowId()) {
                    id = s.lowId();
                } else if (this.version >= 3) {
                    id = Integer.toUnsignedLong(s.ip());
                } else {
                    id = Ipv4.toEd2k(s.ip());
                }
                out.u32(id).u16(s.port()).u32(Ipv4.toEd2k(s.serverIp())).u16(s.serverPort());
                if (this.version >= 2) {
                    out.hash16(s.userHash().orElse(Hash16.ZERO));
                }
                if (this.version >= 4) {
                    out.u8(s.cryptOptions());
                }
            }
            return Packet.of(Protocol.EMULE, Opcodes.Extended.ANSWERSOURCES2.code(), out);
        }

        /** The extended answer: per source its eD2K id, port and tags, for its server and its IPv6 address. */
        public Packet encodeExtended() {
            if (this.sources.size() > MAX_SOURCES) {
                throw new IllegalArgumentException(
                        this.sources.size() + " sources exceed the " + MAX_SOURCES + " of an answer");
            }
            PacketWriter out = new PacketWriter().u8(EXTENDED_VERSION).hash16(this.file);
            out.u16(this.sources.size());
            for (Source s : this.sources) {
                long id = s.isIpv6Only() || s.isLowId() ? s.lowId() : Ipv4.toEd2k(s.ip());
                out.u32(id).u16(s.port());
                List<Tag> tags = new ArrayList<>(3);
                if (s.serverIp() != 0) {
                    tags.add(Tag.of(ClientTag.EMULE_SERVERIP, Ipv4.toEd2k(s.serverIp())));
                    tags.add(Tag.of(ClientTag.EMULE_SERVERTCP, s.serverPort()));
                }
                s.ipv6()
                        .ifPresent(a ->
                                tags.add(new Tag.HashTag(TagName.id(ClientTag.MOD_IP_V6), Hash16.of(a.getAddress()))));
                out.u8(tags.size());
                TagCodec.writeEd2kList(out, tags, Ed2kTagFormat.NEW, StringEncoding.UTF8);
            }
            return Packet.of(Protocol.EMULE, Opcodes.Extended.ANSWERSOURCES2.code(), out);
        }

        /** Decodes an extended answer; tags it does not know are skipped. */
        public static Answer decodeExtended(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            int version = in.u8();
            if (version != EXTENDED_VERSION) {
                throw new DecodeException("extended source exchange version " + version);
            }
            Hash16 file = in.hash16();
            int count = in.u16();
            if (count > MAX_SOURCES) {
                throw new DecodeException(count + " sources in one answer");
            }
            List<Source> sources = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                long id = in.u32();
                int port = in.u16();
                List<Tag> tags = TagCodec.readEd2kList(in, in.u8(), MAX_SOURCE_TAGS);
                int serverIp = Ipv4.fromEd2k(
                        Tags.intValue(tags, ClientTag.EMULE_SERVERIP).orElse(0));
                int serverPort =
                        (int) (Tags.intValue(tags, ClientTag.EMULE_SERVERTCP).orElse(0) & 0xFFFF);
                Optional<Inet6Address> ipv6 =
                        Tags.hashValue(tags, ClientTag.MOD_IP_V6).flatMap(hash -> Ipv6.publicOf(hash.toByteArray()));
                boolean noIpv4 = id == Hello.NO_IPV4 || id < ServerMessages.LOW_ID_LIMIT;
                int ip = noIpv4 ? 0 : Ipv4.fromEd2k(id);
                sources.add(new Source(ip, noIpv4 ? id : 0, port, serverIp, serverPort, Optional.empty(), 0, ipv6));
            }
            return new Answer(EXTENDED_VERSION, file, sources);
        }

        public static Answer decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            int version = in.u8();
            if (version < 1 || version > VERSION) {
                throw new DecodeException("source exchange version " + version);
            }
            Hash16 file = in.hash16();
            int count = in.u16();
            int size = entrySize(version);
            if (count > MAX_SOURCES || (long) count * size != in.remaining()) {
                throw new DecodeException(count + " sources do not fill the answer");
            }
            List<Source> sources = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                long id = in.u32();
                int port = in.u16();
                int serverIp = Ipv4.fromEd2k(in.u32());
                int serverPort = in.u16();
                Optional<Hash16> hash = version >= 2 ? Optional.of(in.hash16()) : Optional.empty();
                int crypt = version >= 4 ? in.u8() : 0;
                boolean low = id < ServerMessages.LOW_ID_LIMIT;
                // Some clients send a LowID in versions 3 and 4 with its bytes swapped, which reads as
                // an address ending in .0, where no client is reachable.
                if (!low && version >= 3 && (id & 0xFF) == 0) {
                    low = true;
                    id = Integer.toUnsignedLong(Integer.reverseBytes((int) id));
                }
                int ip = low ? 0 : version >= 3 ? (int) id : Ipv4.fromEd2k(id);
                sources.add(new Source(ip, low ? id : 0, port, serverIp, serverPort, hash, crypt));
            }
            return new Answer(version, file, sources);
        }
    }

    /** OP_REQUESTSOURCES2: the version we take, no options, then the file. */
    public static Packet request(Hash16 file) {
        return request(file, VERSION);
    }

    /**
     * As {@link #request(Hash16)}, for answers of {@code version}: {@link #EXTENDED_VERSION} asks for the extended
     * form.
     */
    public static Packet request(Hash16 file, int version) {
        return Packet.of(
                Protocol.EMULE,
                Opcodes.Extended.REQUESTSOURCES2.code(),
                new PacketWriter().u8(version).u16(0).hash16(file));
    }

    /** A request: the version the asker takes, and the file. */
    public record Request(int version, Hash16 file) {
        public static Request decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            int version = in.u8();
            in.u16();
            return new Request(version, in.hash16());
        }
    }

    private static int entrySize(int version) {
        return switch (version) {
            case 1 -> 12;
            case 2, 3 -> 28;
            default -> 29;
        };
    }
}
