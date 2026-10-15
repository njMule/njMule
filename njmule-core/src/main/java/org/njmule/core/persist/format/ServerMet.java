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
package org.njmule.core.persist.format;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.tag.ServerTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.protocol.tag.Tags;
import org.njmule.core.util.Ipv4;

/**
 * The server list, {@code server.met}: {@code <0xE0><u32 count>} then {@code <IP 4><port 2><u32 tag count><tags>} per
 * server. The IP is in network order; 0 with an ST_DYNIP tag means a host name. Unknown tags are kept, so that
 * rewriting a file loses nothing.
 */
public final class ServerMet {

    public static final int HEADER = 0xE0;
    private static final int OLD_HEADER = 0x0E;
    private static final int MAX_SERVERS = 100_000;

    /** One server; {@code ip} is in {@link Ipv4} form. */
    public record Server(int ip, int port, List<Tag> tags) {
        public Server {
            if (port < 0 || port > 0xFFFF) {
                throw new IllegalArgumentException("invalid port " + port);
            }
            tags = List.copyOf(tags);
        }

        /** This server with {@code tag} replacing any tag of the same name. */
        public Server with(Tag tag) {
            List<Tag> updated = new ArrayList<>(this.tags.size() + 1);
            for (Tag t : this.tags) {
                if (!t.name().equals(tag.name())) {
                    updated.add(t);
                }
            }
            updated.add(tag);
            return new Server(this.ip, this.port, updated);
        }

        public Optional<String> name() {
            return Tags.stringValue(this.tags, ServerTag.SERVERNAME);
        }

        public Optional<String> description() {
            return Tags.stringValue(this.tags, ServerTag.DESCRIPTION);
        }

        public Optional<String> dynamicIp() {
            return Tags.stringValue(this.tags, ServerTag.DYNIP).filter(s -> !s.isEmpty());
        }

        public OptionalLong preference() {
            return Tags.intValue(this.tags, ServerTag.PREFERENCE);
        }

        public OptionalLong failedCount() {
            return Tags.intValue(this.tags, ServerTag.FAIL);
        }

        public OptionalLong users() {
            return Tags.intValue(this.tags, TagName.of(ServerTag.USERS));
        }

        public OptionalLong files() {
            return Tags.intValue(this.tags, TagName.of(ServerTag.FILES));
        }

        public OptionalLong maxUsers() {
            return Tags.intValue(this.tags, ServerTag.MAXUSERS);
        }

        public OptionalLong udpFlags() {
            return Tags.intValue(this.tags, ServerTag.UDPFLAGS);
        }

        public OptionalLong udpKey() {
            return Tags.intValue(this.tags, ServerTag.UDPKEY);
        }

        /** Our address when the server gave its UDP key, in eD2K byte order: the key holds for it only. */
        public OptionalLong udpKeyIp() {
            return Tags.intValue(this.tags, ServerTag.UDPKEYIP);
        }

        public OptionalLong obfuscatedTcpPort() {
            return Tags.intValue(this.tags, ServerTag.TCPPORTOBFUSCATION);
        }

        public OptionalLong obfuscatedUdpPort() {
            return Tags.intValue(this.tags, ServerTag.UDPPORTOBFUSCATION);
        }

        /** How long the server took to answer a status request, in milliseconds. */
        public OptionalLong ping() {
            return Tags.intValue(this.tags, ServerTag.PING);
        }

        /** The number of files a client should offer at most. */
        public OptionalLong softFiles() {
            return Tags.intValue(this.tags, ServerTag.SOFTFILES);
        }

        /** The number of files beyond which the server drops a client. */
        public OptionalLong hardFiles() {
            return Tags.intValue(this.tags, ServerTag.HARDFILES);
        }

        public OptionalLong lowIdUsers() {
            return Tags.intValue(this.tags, ServerTag.LOWIDUSERS);
        }

        /** The server software's version, kept as text or as {@code major << 16 | minor}. */
        public Optional<String> version() {
            Optional<String> text = Tags.stringValue(this.tags, ServerTag.VERSION);
            if (text.isPresent()) {
                return text;
            }
            OptionalLong number = Tags.intValue(this.tags, ServerTag.VERSION);
            return number.isPresent()
                    ? Optional.of(version(number.getAsLong() >>> 16, number.getAsLong() & 0xFFFF))
                    : Optional.empty();
        }

        /** {@code major.minor} with two minor digits, as servers number their releases ("17.05"). */
        public static String version(long major, long minor) {
            return major + "." + (minor < 10 ? "0" : "") + minor;
        }

        /** When the server was last asked for its status, in epoch seconds. */
        public OptionalLong lastPing() {
            return Tags.intValue(this.tags, ServerTag.LASTPING);
        }
    }

    private ServerMet() {}

    public static List<Server> read(byte[] data) throws DecodeException {
        PacketReader in = PacketReader.of(data);
        MetCodecs.header(in, "server.met", HEADER, OLD_HEADER);
        int count = in.count(in.u32(), 10, MAX_SERVERS);
        List<Server> servers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int ip = Ipv4.fromEd2k(in.u32());
            int port = in.u16();
            servers.add(new Server(ip, port, MetCodecs.readTags(in)));
        }
        return List.copyOf(servers);
    }

    public static byte[] write(List<Server> servers) {
        PacketWriter out = new PacketWriter();
        out.u8(HEADER).u32(servers.size());
        for (Server s : servers) {
            out.u32(Ipv4.toEd2k(s.ip())).u16(s.port());
            MetCodecs.writeTags(out, s.tags());
        }
        return out.toByteArray();
    }
}
