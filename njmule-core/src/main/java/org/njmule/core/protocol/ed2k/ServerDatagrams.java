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
import org.njmule.core.protocol.tag.TagType;
import org.njmule.core.search.SearchExpr;
import org.njmule.core.search.SearchTreeCodec;

/**
 * UDP messages between a client and eD2K servers (server TCP port + 4, or the server's obfuscated UDP port). Every
 * decoder checks the length before reading, including datagrams too short to hold a header.
 */
public final class ServerDatagrams {

    // Server UDP flags, from the status answer.
    public static final int UDP_EXT_GETSOURCES = 0x001;
    public static final int UDP_EXT_GETFILES = 0x002;
    public static final int UDP_NEWTAGS = 0x008;
    public static final int UDP_UNICODE = 0x010;
    public static final int UDP_EXT_GETSOURCES2 = 0x020;
    public static final int UDP_LARGEFILES = 0x100;
    public static final int UDP_OBFUSCATION = 0x200;
    public static final int UDP_TCP_OBFUSCATION = 0x400;

    /** Bytes of a status answer up to the file counts, the shortest one accepted. */
    public static final int MIN_STATUS = 12;

    /** Bytes of file ids at most in one source request. */
    public static final int MAX_SOURCE_REQUEST = 510;

    private static final long MAX_32_BIT_SIZE = 0xFFFF_FFFFL;
    // The low half of a description challenge, read first as the length of the old answer's name: no valid length, so
    // the answer with the challenge is told from the old one.
    private static final int DESCRIPTION_MARK = 0xF0FF;
    private static final int MAX_DESCRIPTION_TAGS = 32;

    // Prefix of the third search request: one tag announcing that new tags and 64-bit sizes are
    // understood in the answer.
    private static final int SEARCH_FLAG_NEWTAGS_LARGEFILES = 0x01;

    private ServerDatagrams() {}

    /** Server load and capabilities; fields a server does not send are 0. */
    public record Status(
            long challenge,
            long users,
            long files,
            long maxUsers,
            long softFiles,
            long hardFiles,
            int udpFlags,
            long lowIdUsers,
            int obfuscatedUdpPort,
            int obfuscatedTcpPort,
            long udpKey) {

        public boolean has(int flag) {
            return (this.udpFlags & flag) != 0;
        }

        /** The full answer, with every optional field. */
        public Packet encode() {
            PacketWriter out = new PacketWriter()
                    .u32(this.challenge)
                    .u32(this.users)
                    .u32(this.files)
                    .u32(this.maxUsers)
                    .u32(this.softFiles)
                    .u32(this.hardFiles)
                    .u32(Integer.toUnsignedLong(this.udpFlags))
                    .u32(this.lowIdUsers)
                    .u16(this.obfuscatedUdpPort)
                    .u16(this.obfuscatedTcpPort)
                    .u32(this.udpKey);
            return Packet.of(Protocol.EDONKEY, Opcodes.ServerUdp.GLOBSERVSTATRES.code(), out);
        }

        public static Status decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            if (in.remaining() < MIN_STATUS) {
                throw new DecodeException("server status of " + in.remaining() + " bytes");
            }
            long challenge = in.u32();
            long users = in.u32();
            long files = in.u32();
            long maxUsers = in.remaining() >= 4 ? in.u32() : 0;
            long soft = 0;
            long hard = 0;
            if (in.remaining() >= 8) {
                soft = in.u32();
                hard = in.u32();
            }
            int flags = in.remaining() >= 4 ? (int) in.u32() : 0;
            long lowId = in.remaining() >= 4 ? in.u32() : 0;
            int udpPort = 0;
            int tcpPort = 0;
            long key = 0;
            if (in.remaining() >= 8) {
                udpPort = in.u16();
                tcpPort = in.u16();
                key = in.u32();
            }
            return new Status(challenge, users, files, maxUsers, soft, hard, flags, lowId, udpPort, tcpPort, key);
        }
    }

    /** A file whose sources a server is asked for. */
    public record SourceQuery(Hash16 file, long size) {}

    /**
     * The source requests for {@code files} to a server with {@code udpFlags}, one datagram each: several files per
     * request and their sizes only where the server takes them, files above 4 GB only for a server that takes those.
     * Each request holds at most {@link #MAX_SOURCE_REQUEST} bytes of file ids, so that it fits a 576-byte datagram.
     */
    public static List<Packet> sourceRequests(List<SourceQuery> files, int udpFlags) {
        boolean several = (udpFlags & UDP_EXT_GETSOURCES) != 0;
        boolean sized = (udpFlags & UDP_EXT_GETSOURCES2) != 0;
        boolean takesLarge = sized && (udpFlags & UDP_LARGEFILES) != 0;
        int opcode = (sized ? Opcodes.ServerUdp.GLOBGETSOURCES2 : Opcodes.ServerUdp.GLOBGETSOURCES).code();
        List<Packet> requests = new ArrayList<>();
        PacketWriter out = new PacketWriter();
        for (SourceQuery query : files) {
            boolean large = query.size() > MAX_32_BIT_SIZE;
            if (large && !takesLarge) {
                continue;
            }
            int bytes = Hash16.LENGTH + (sized ? 4 : 0) + (large ? 8 : 0);
            if (out.size() > 0 && (!several || out.size() + bytes > MAX_SOURCE_REQUEST)) {
                requests.add(Packet.of(Protocol.EDONKEY, opcode, out));
                out = new PacketWriter();
            }
            out.hash16(query.file());
            if (large) {
                out.u32(0).u64(query.size());
            } else if (sized) {
                out.u32(query.size());
            }
        }
        if (out.size() > 0) {
            requests.add(Packet.of(Protocol.EDONKEY, opcode, out));
        }
        return requests;
    }

    /**
     * The sources of a source answer. One datagram may carry the sources of several files, each after its own {@code E3
     * 9B} header; parsing stops at anything else, and a file cut short is dropped with what follows it. Only a first
     * file that cannot be read fails the whole answer.
     */
    public static List<ServerMessages.FoundSources> decodeFoundSources(Packet packet) throws DecodeException {
        PacketReader in = packet.reader();
        List<ServerMessages.FoundSources> found = new ArrayList<>();
        found.add(ServerMessages.FoundSources.read(in, false));
        while (in.remaining() >= 2 + Hash16.LENGTH + 1) {
            int protocol = in.u8();
            int opcode = in.u8();
            if (protocol != Protocol.EDONKEY || opcode != Opcodes.ServerUdp.GLOBFOUNDSOURCES.code()) {
                break;
            }
            try {
                found.add(ServerMessages.FoundSources.read(in, false));
            } catch (DecodeException e) {
                break;
            }
        }
        return found;
    }

    /** A source answer carrying the sources of {@code found} (at least one file) in one datagram. */
    public static Packet foundSources(List<ServerMessages.FoundSources> found) {
        if (found.isEmpty()) {
            throw new IllegalArgumentException("a source answer carries at least one file");
        }
        PacketWriter out = found.getFirst().write(new PacketWriter(), false);
        for (ServerMessages.FoundSources file : found.subList(1, found.size())) {
            out.u8(Protocol.EDONKEY).u8(Opcodes.ServerUdp.GLOBFOUNDSOURCES.code());
            file.write(out, false);
        }
        return Packet.of(Protocol.EDONKEY, Opcodes.ServerUdp.GLOBFOUNDSOURCES.code(), out);
    }

    /** A server's description: the challenge of the request it answers, and its name, description and version tags. */
    public record Description(long challenge, List<Tag> tags) {
        public Description {
            tags = List.copyOf(tags);
        }

        public Packet encode() {
            PacketWriter out = new PacketWriter().u32(this.challenge).u32(this.tags.size());
            TagCodec.writeEd2kList(out, this.tags, Ed2kTagFormat.NEW, StringEncoding.UTF8);
            return Packet.of(Protocol.EDONKEY, Opcodes.ServerUdp.SERVER_DESC_RES.code(), out);
        }

        /**
         * Decodes the answer to a request with a challenge; the old answer, which cannot be tied to one, is refused.
         */
        public static Description decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            if (in.remaining() < 8) {
                throw new DecodeException("server description of " + in.remaining() + " bytes");
            }
            long challenge = in.u32();
            if ((challenge & 0xFFFF) != DESCRIPTION_MARK) {
                throw new DecodeException("server description without a challenge");
            }
            return new Description(challenge, TagCodec.readEd2kList(in, in.u32(), MAX_DESCRIPTION_TAGS));
        }
    }

    /** The challenge of a description request, from 16 random bits. */
    public static long descriptionChallenge(int random) {
        return (long) (random & 0xFFFF) << 16 | DESCRIPTION_MARK;
    }

    public static Packet descriptionRequest(long challenge) {
        return Packet.of(Protocol.EDONKEY, Opcodes.ServerUdp.SERVER_DESC_REQ.code(), new PacketWriter().u32(challenge));
    }

    public static Packet statusRequest(long challenge) {
        return Packet.of(Protocol.EDONKEY, Opcodes.ServerUdp.GLOBSERVSTATREQ.code(), new PacketWriter().u32(challenge));
    }

    /**
     * The global search request for a server with {@code udpFlags}, or empty when the server cannot take the expression
     * (64-bit sizes it does not understand).
     */
    public static Optional<Packet> searchRequest(SearchExpr expression, int udpFlags) {
        boolean largeFiles = (udpFlags & UDP_LARGEFILES) != 0;
        boolean extended = (udpFlags & UDP_EXT_GETFILES) != 0;
        SearchExpr tree = SearchTreeCodec.forServer(expression);
        PacketWriter out = new PacketWriter();
        if (largeFiles && extended) {
            out.u32(1)
                    .u8(TagType.UINT8 | TagType.NEW_ED2K_FLAG)
                    .u8(ClientTag.SERVER_UDPSEARCH_FLAGS)
                    .u8(SEARCH_FLAG_NEWTAGS_LARGEFILES);
            SearchTreeCodec.write(out, tree, true);
            return Optional.of(Packet.of(Protocol.EDONKEY, Opcodes.ServerUdp.GLOBSEARCHREQ3.code(), out));
        }
        if (needs64Bit(tree)) {
            return Optional.empty();
        }
        SearchTreeCodec.write(out, tree, false);
        Opcodes.ServerUdp opcode = extended ? Opcodes.ServerUdp.GLOBSEARCHREQ2 : Opcodes.ServerUdp.GLOBSEARCHREQ;
        return Optional.of(Packet.of(Protocol.EDONKEY, opcode.code(), out));
    }

    /**
     * The results of a global search answer. One datagram may carry several results, each after its own {@code E3 99}
     * header; parsing stops at anything else, and a result cut short is dropped with what follows it. Only a first
     * result that cannot be read fails the whole answer.
     */
    public static List<FileEntry> decodeSearchResults(Packet packet) throws DecodeException {
        PacketReader in = packet.reader();
        List<FileEntry> files = new ArrayList<>();
        files.add(FileEntry.read(in));
        while (in.remaining() >= 2 + FileEntry.MIN_SIZE) {
            int protocol = in.u8();
            int opcode = in.u8();
            if (protocol != Protocol.EDONKEY || opcode != Opcodes.ServerUdp.GLOBSEARCHRES.code()) {
                break;
            }
            try {
                files.add(FileEntry.read(in));
            } catch (DecodeException e) {
                break;
            }
        }
        return files;
    }

    /** A global search answer carrying {@code files} (at least one) in one datagram. */
    public static Packet searchResults(List<FileEntry> files) {
        if (files.isEmpty()) {
            throw new IllegalArgumentException("a search answer carries at least one file");
        }
        PacketWriter out = new PacketWriter();
        files.getFirst().write(out);
        for (FileEntry file : files.subList(1, files.size())) {
            out.u8(Protocol.EDONKEY).u8(Opcodes.ServerUdp.GLOBSEARCHRES.code());
            file.write(out);
        }
        return Packet.of(Protocol.EDONKEY, Opcodes.ServerUdp.GLOBSEARCHRES.code(), out);
    }

    private static boolean needs64Bit(SearchExpr tree) {
        PacketWriter narrow = new PacketWriter();
        PacketWriter wide = new PacketWriter();
        SearchTreeCodec.write(narrow, tree, false);
        SearchTreeCodec.write(wide, tree, true);
        return narrow.size() != wide.size();
    }
}
