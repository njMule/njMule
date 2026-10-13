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
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.tag.ClientTag;
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
     * header; parsing stops at anything else.
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
            files.add(FileEntry.read(in));
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
