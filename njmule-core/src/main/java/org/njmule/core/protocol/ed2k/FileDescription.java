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

import org.njmule.core.api.FileComment;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.Ed2kStrings;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.io.StringEncoding;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;

/**
 * A client's comment on the file a downloader asked for: {@code <rating u8><u32 length><text>}, sent after the file
 * name to clients that take comments.
 */
public final class FileDescription {

    // UTF-8 may take four bytes a character.
    private static final int MAX_BYTES = FileComment.MAX_LENGTH * 4;

    private FileDescription() {}

    public static Packet encode(FileComment comment) {
        byte[] text = Ed2kStrings.encode(comment.text(), StringEncoding.UTF8);
        PacketWriter out =
                new PacketWriter().u8(comment.rating()).u32(text.length).bytes(text);
        return Packet.of(Protocol.EMULE, Opcodes.Extended.FILEDESC.code(), out);
    }

    /** The comment, made fit: a longer text is cut, a rating out of range kept within it. */
    public static FileComment decode(Packet packet) throws DecodeException {
        PacketReader in = packet.reader();
        int rating = in.u8();
        long length = in.u32();
        if (length > in.remaining()) {
            throw new DecodeException("a comment of " + length + " bytes in " + in.remaining());
        }
        byte[] text = in.bytes((int) Math.min(length, MAX_BYTES));
        return FileComment.of(Ed2kStrings.decode(text), rating);
    }

    /** Whether the client of {@code hello} takes comments. */
    public static boolean accepted(Hello hello) {
        return (hello.miscOptions1() >>> 4 & 0x0F) != 0;
    }
}
