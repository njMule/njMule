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
import java.util.Objects;
import java.util.Optional;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.io.StringEncoding;
import org.njmule.core.protocol.tag.Ed2kTagFormat;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagCodec;
import org.njmule.core.protocol.tag.Tags;
import org.njmule.core.util.Ipv4;

/**
 * Friends, {@code emfriends.met}: {@code <0x0E><u32 count>} then {@code <user hash 16><last IP
 * 4><last port 2><last seen u32><last chatted u32><u32 tag count><tags>}; the name (id 1) is an
 * old-encoding tag and the Kad id (id 2) a new-encoding hash tag.
 */
public final class FriendsMet {

    public static final int HEADER = 0x0E;
    public static final int NAME = 0x01;
    public static final int KAD_ID = 0x02;
    private static final int MAX_FRIENDS = 100_000;

    public record Friend(
            Hash16 userHash,
            int lastIp,
            int lastPort,
            long lastSeen,
            long lastChatted,
            List<Tag> tags) {
        public Friend {
            Objects.requireNonNull(userHash, "userHash");
            tags = List.copyOf(tags);
        }

        public Optional<String> name() {
            return Tags.stringValue(this.tags, NAME);
        }

        public Optional<Hash16> kadId() {
            return Tags.hashValue(this.tags, KAD_ID).filter(h -> !h.isZero());
        }
    }

    private FriendsMet() {}

    public static List<Friend> read(byte[] data) throws DecodeException {
        PacketReader in = PacketReader.of(data);
        MetCodecs.header(in, "emfriends.met", HEADER);
        int count = in.count(in.u32(), 34, MAX_FRIENDS);
        List<Friend> friends = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Hash16 hash = in.hash16();
            int ip = Ipv4.fromEd2k(in.u32());
            int port = in.u16();
            long lastSeen = in.u32();
            long lastChatted = in.u32();
            friends.add(new Friend(hash, ip, port, lastSeen, lastChatted, MetCodecs.readTags(in)));
        }
        return List.copyOf(friends);
    }

    public static byte[] write(List<Friend> friends) {
        PacketWriter out = new PacketWriter();
        out.u8(HEADER).u32(friends.size());
        for (Friend f : friends) {
            out.hash16(f.userHash())
                    .u32(Ipv4.toEd2k(f.lastIp()))
                    .u16(f.lastPort())
                    .u32(f.lastSeen())
                    .u32(f.lastChatted())
                    .u32(f.tags().size());
            for (Tag tag : f.tags()) {
                Ed2kTagFormat format =
                        tag.name().isId() && tag.name().id() == KAD_ID
                                ? Ed2kTagFormat.NEW
                                : Ed2kTagFormat.OLD;
                TagCodec.writeEd2k(out, tag, format, StringEncoding.UTF8_BOM_IF_NEEDED);
            }
        }
        return out.toByteArray();
    }
}
