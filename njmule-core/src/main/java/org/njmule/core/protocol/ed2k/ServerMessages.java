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
import org.njmule.core.protocol.tag.ServerTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagCodec;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.protocol.tag.Tags;
import org.njmule.core.search.SearchExpr;
import org.njmule.core.search.SearchTreeCodec;
import org.njmule.core.util.Ipv4;

/**
 * Messages between a client and an eD2K server over TCP. IP addresses travel as the four bytes in network order; client
 * ids below {@link #LOW_ID_LIMIT} are LowIDs (firewalled clients).
 */
public final class ServerMessages {

    /** Client ids below this value are LowIDs. */
    public static final long LOW_ID_LIMIT = 0x0100_0000L;

    // Capabilities announced at login.
    public static final int CAP_ZLIB = 0x0001;
    public static final int CAP_NEWTAGS = 0x0008;
    public static final int CAP_UNICODE = 0x0010;
    public static final int CAP_LARGEFILES = 0x0100;
    public static final int CAP_SUPPORTS_CRYPT = 0x0200;
    public static final int CAP_REQUESTS_CRYPT = 0x0400;
    public static final int CAP_REQUIRES_CRYPT = 0x0800;

    // Server flags sent with the id.
    public static final int TCP_COMPRESSION = 0x0001;
    public static final int TCP_NEWTAGS = 0x0008;
    public static final int TCP_UNICODE = 0x0010;
    public static final int TCP_RELATED_SEARCH = 0x0040;
    public static final int TCP_TYPE_TAG_INTEGER = 0x0080;
    public static final int TCP_LARGEFILES = 0x0100;
    public static final int TCP_OBFUSCATION = 0x0400;

    /** Search results accepted per packet. */
    public static final int MAX_RESULTS = 1_000;

    private static final int MAX_SERVER_LIST = 255;
    private static final int MAX_SOURCES = 255;
    private static final int CALLBACK_WITH_HASH = 4 + 2 + 1 + 16;

    private ServerMessages() {}

    /** The login request, the first message after connecting. */
    public record Login(Hash16 userHash, long clientId, int tcpPort, String nick, int capabilities) {
        public Login {
            Objects.requireNonNull(userHash, "userHash");
            Objects.requireNonNull(nick, "nick");
        }

        public Packet encode() {
            PacketWriter out = new PacketWriter();
            out.hash16(this.userHash).u32(this.clientId).u16(this.tcpPort);
            List<Tag> tags = List.of(
                    new Tag.StringTag(TagName.id(ClientTag.NAME), this.nick),
                    new Tag.IntTag(TagName.id(ClientTag.VERSION), ClientVersion.EDONKEY),
                    new Tag.IntTag(TagName.id(ClientTag.SERVER_FLAGS), this.capabilities),
                    new Tag.IntTag(
                            TagName.id(ClientTag.EMULE_VERSION), Integer.toUnsignedLong(ClientVersion.njMule())));
            out.u32(tags.size());
            TagCodec.writeEd2kList(out, tags, Ed2kTagFormat.OLD, StringEncoding.UTF8);
            return Packet.of(Protocol.EDONKEY, Opcodes.Server.LOGINREQUEST.code(), out);
        }

        /** The server side of {@link #encode}. */
        public static Login decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            Hash16 hash = in.hash16();
            long clientId = in.u32();
            int port = in.u16();
            List<Tag> tags = TagCodec.readEd2kList(in, in.u32(), FileEntry.MAX_TAGS);
            return new Login(
                    hash, clientId, port, Tags.stringValue(tags, ClientTag.NAME).orElse(""), (int)
                            Tags.intValue(tags, ClientTag.SERVER_FLAGS).orElse(0));
        }
    }

    /** The id the server assigned, its flags and, from newer servers, our address as it sees it (0 when absent). */
    public record IdChange(long clientId, int serverFlags, int reportedIp) {
        public boolean isLowId() {
            return this.clientId < LOW_ID_LIMIT;
        }

        /** Our public address: the id itself for a HighID, else what the server reported. */
        public int publicIp() {
            return isLowId() ? this.reportedIp : Ipv4.fromEd2k(this.clientId);
        }

        public boolean has(int flag) {
            return (this.serverFlags & flag) != 0;
        }

        public Packet encode() {
            PacketWriter out = new PacketWriter()
                    .u32(this.clientId)
                    .u32(Integer.toUnsignedLong(this.serverFlags))
                    .u32(0)
                    .u32(this.reportedIp == 0 ? 0 : Ipv4.toEd2k(this.reportedIp));
            return Packet.of(Protocol.EDONKEY, Opcodes.Server.IDCHANGE.code(), out);
        }

        public static IdChange decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            long id = in.u32();
            int flags = in.remaining() >= 4 ? (int) in.u32() : 0;
            int ip = 0;
            if (in.remaining() >= 8) {
                in.skip(4);
                long reported = in.u32();
                ip = reported < LOW_ID_LIMIT ? 0 : Ipv4.fromEd2k(reported);
            }
            return new IdChange(id, flags, ip);
        }
    }

    /** Users and files on the server. */
    public record ServerStatus(long users, long files) {
        public Packet encode() {
            return Packet.of(
                    Protocol.EDONKEY,
                    Opcodes.Server.SERVERSTATUS.code(),
                    new PacketWriter().u32(this.users).u32(this.files));
        }

        /** Empty for a packet too short to hold both counts. */
        public static Optional<ServerStatus> decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            if (in.remaining() < 8) {
                return Optional.empty();
            }
            return Optional.of(new ServerStatus(in.u32(), in.u32()));
        }
    }

    /** The server's own description. */
    public record ServerIdent(Hash16 hash, int ip, int port, Optional<String> name, Optional<String> description) {
        public Packet encode() {
            List<Tag> tags = new ArrayList<>();
            this.name.ifPresent(n -> tags.add(new Tag.StringTag(TagName.id(ServerTag.SERVERNAME), n)));
            this.description.ifPresent(d -> tags.add(new Tag.StringTag(TagName.id(ServerTag.DESCRIPTION), d)));
            PacketWriter out = new PacketWriter()
                    .hash16(this.hash)
                    .u32(Ipv4.toEd2k(this.ip))
                    .u16(this.port)
                    .u32(tags.size());
            TagCodec.writeEd2kList(out, tags, Ed2kTagFormat.NEW, StringEncoding.UTF8);
            return Packet.of(Protocol.EDONKEY, Opcodes.Server.SERVERIDENT.code(), out);
        }

        public static ServerIdent decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            Hash16 hash = in.hash16();
            int ip = Ipv4.fromEd2k(in.u32());
            int port = in.u16();
            List<Tag> tags = TagCodec.readEd2kList(in, in.u32(), FileEntry.MAX_TAGS);
            return new ServerIdent(
                    hash,
                    ip,
                    port,
                    Tags.stringValue(tags, ServerTag.SERVERNAME),
                    Tags.stringValue(tags, ServerTag.DESCRIPTION));
        }
    }

    /** An address in a server list or a callback request. */
    public record Endpoint(int ip, int port) {}

    /** Search results; {@code more} when the server can send more after QUERY_MORE_RESULT. */
    public record SearchResults(List<FileEntry> files, boolean more) {
        public SearchResults {
            files = List.copyOf(files);
        }

        public Packet encode() {
            PacketWriter out = new PacketWriter().u32(this.files.size());
            for (FileEntry file : this.files) {
                file.write(out);
            }
            out.u8(this.more ? 1 : 0);
            return Packet.of(Protocol.EDONKEY, Opcodes.Server.SEARCHRESULT.code(), out);
        }

        /** Decodes an answer; one cut short keeps the files before the cut, provided there is one. */
        public static SearchResults decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            int count = in.count(in.u32(), 0, MAX_RESULTS);
            List<FileEntry> files = new ArrayList<>(Math.min(count, in.remaining() / FileEntry.MIN_SIZE));
            for (int i = 0; i < count; i++) {
                try {
                    files.add(FileEntry.read(in));
                } catch (DecodeException e) {
                    if (files.isEmpty()) {
                        throw e;
                    }
                    return new SearchResults(files, false);
                }
            }
            // A single trailing byte is the "more results" flag.
            boolean more = in.remaining() == 1 && in.u8() == 1;
            return new SearchResults(files, more);
        }
    }

    /** A LowID client asked through the server that we connect to it. */
    public record CallbackRequested(Endpoint endpoint, int cryptOptions, Optional<Hash16> userHash) {
        public static CallbackRequested decode(Packet packet) throws DecodeException {
            PacketReader in = packet.reader();
            Endpoint endpoint = new Endpoint(Ipv4.fromEd2k(in.u32()), in.u16());
            if (packet.length() < CALLBACK_WITH_HASH) {
                return new CallbackRequested(endpoint, 0, Optional.empty());
            }
            int crypt = in.u8();
            return new CallbackRequested(endpoint, crypt, Optional.of(in.hash16()));
        }
    }

    /**
     * A source for a file: a client id (its IP for a HighID), its port and, from servers that know them, its connect
     * options and user hash.
     */
    public record Source(long clientId, int port, int cryptOptions, Optional<Hash16> userHash) {
        /** Connect options flag: the user hash follows. */
        public static final int HAS_USER_HASH = 0x80;

        public boolean isLowId() {
            return this.clientId < LOW_ID_LIMIT;
        }
    }

    /** The sources a server knows for one file. */
    public record FoundSources(Hash16 file, List<Source> sources) {
        public FoundSources {
            sources = List.copyOf(sources);
        }

        /** Decodes OP_FOUNDSOURCES, or with {@code obfuscated} OP_FOUNDSOURCES_OBFU. */
        public static FoundSources decode(Packet packet) throws DecodeException {
            return read(packet.reader(), packet.opcode() == Opcodes.Server.FOUNDSOURCES_OBFU.code());
        }

        /** Reads one file's sources; {@code obfuscated} sources carry crypt options and maybe a user hash. */
        static FoundSources read(PacketReader in, boolean obfuscated) throws DecodeException {
            Hash16 file = in.hash16();
            int count = in.count(in.u8(), obfuscated ? 7 : 6, MAX_SOURCES);
            List<Source> sources = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                long id = in.u32();
                int port = in.u16();
                int crypt = 0;
                Optional<Hash16> hash = Optional.empty();
                if (obfuscated) {
                    crypt = in.u8();
                    if ((crypt & Source.HAS_USER_HASH) != 0) {
                        hash = Optional.of(in.hash16());
                    }
                }
                sources.add(new Source(id, port, crypt, hash));
            }
            return new FoundSources(file, sources);
        }

        /** OP_FOUNDSOURCES_OBFU, as servers answer clients that support obfuscation. */
        public Packet encode() {
            return Packet.of(
                    Protocol.EDONKEY, Opcodes.Server.FOUNDSOURCES_OBFU.code(), write(new PacketWriter(), true));
        }

        PacketWriter write(PacketWriter out, boolean obfuscated) {
            out.hash16(this.file).count8(this.sources.size());
            for (Source source : this.sources) {
                out.u32(source.clientId()).u16(source.port());
                if (obfuscated) {
                    out.u8(source.cryptOptions());
                    source.userHash().ifPresent(out::hash16);
                }
            }
            return out;
        }
    }

    /**
     * Asks for the sources of a file; files above 4 GB use the 64-bit form, which only servers with large file support
     * understand.
     */
    public static Packet getSources(Hash16 file, long size, boolean obfuscated) {
        PacketWriter out = new PacketWriter().hash16(file);
        if (size > 0xFFFF_FFFFL) {
            out.u32(0).u64(size);
        } else {
            out.u32(size);
        }
        Opcodes.Server opcode = obfuscated ? Opcodes.Server.GETSOURCES_OBFU : Opcodes.Server.GETSOURCES;
        return Packet.of(Protocol.EDONKEY, opcode.code(), out);
    }

    /** Lines of a server message: the text is split on line breaks and blank lines dropped. */
    public static List<String> decodeMessage(Packet packet) throws DecodeException {
        String text = packet.reader().string16();
        List<String> lines = new ArrayList<>();
        for (String line : text.split("[\\r\\n]+", -1)) {
            String stripped = line.strip();
            if (!stripped.isEmpty()) {
                lines.add(stripped);
            }
        }
        return lines;
    }

    public static Packet message(String text) {
        return Packet.of(
                Protocol.EDONKEY,
                Opcodes.Server.SERVERMESSAGE.code(),
                new PacketWriter().string16(text, StringEncoding.UTF8));
    }

    public static Packet serverList(List<Endpoint> servers) {
        PacketWriter out = new PacketWriter().count8(servers.size());
        for (Endpoint server : servers) {
            out.u32(Ipv4.toEd2k(server.ip())).u16(server.port());
        }
        return Packet.of(Protocol.EDONKEY, Opcodes.Server.SERVERLIST.code(), out);
    }

    public static List<Endpoint> decodeServerList(Packet packet) throws DecodeException {
        PacketReader in = packet.reader();
        int count = in.count(in.u8(), 6, MAX_SERVER_LIST);
        List<Endpoint> servers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            servers.add(new Endpoint(Ipv4.fromEd2k(in.u32()), in.u16()));
        }
        return servers;
    }

    /** A search: the expression tree, 64-bit sizes only for servers that support them. */
    public static Packet searchRequest(SearchExpr expression, boolean largeFiles) {
        PacketWriter out = new PacketWriter();
        SearchTreeCodec.write(out, SearchTreeCodec.forServer(expression), largeFiles);
        return Packet.of(Protocol.EDONKEY, Opcodes.Server.SEARCHREQUEST.code(), out);
    }

    public static Packet queryMoreResults() {
        return Packet.empty(Protocol.EDONKEY, Opcodes.Server.QUERY_MORE_RESULT.code());
    }

    public static Packet getServerList() {
        return Packet.empty(Protocol.EDONKEY, Opcodes.Server.GETSERVERLIST.code());
    }

    /** Asks the server to have a LowID client connect to us. */
    public static Packet callbackRequest(long lowId) {
        return Packet.of(Protocol.EDONKEY, Opcodes.Server.CALLBACKREQUEST.code(), new PacketWriter().u32(lowId));
    }

    /** Our shared files, with new tags for servers that announced them. */
    public static Packet offerFiles(List<FileEntry> files, boolean newTags) {
        PacketWriter out = new PacketWriter().u32(files.size());
        Ed2kTagFormat format = newTags ? Ed2kTagFormat.NEW : Ed2kTagFormat.OLD;
        for (FileEntry file : files) {
            file.write(out, format);
        }
        return Packet.of(Protocol.EDONKEY, Opcodes.Server.OFFERFILES.code(), out);
    }

    /** An empty file offer: keeps an idle connection alive. */
    public static Packet keepAlive() {
        return Packet.of(Protocol.EDONKEY, Opcodes.Server.OFFERFILES.code(), new PacketWriter().u32(0));
    }
}
