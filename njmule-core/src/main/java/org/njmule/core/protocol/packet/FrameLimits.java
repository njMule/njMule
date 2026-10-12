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
package org.njmule.core.protocol.packet;

import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.protocol.packet.Opcodes.Client;
import org.njmule.core.protocol.packet.Opcodes.Extended;

/**
 * Frame size limits per connection state. Before the hello exchange a peer may only greet, in a
 * small frame, so an unauthenticated connection cannot make us allocate much; afterwards the caps
 * follow what each packet can legitimately carry.
 */
public final class FrameLimits {

    /** Hello and hello answer. */
    public static final int HANDSHAKE_MAX = 4 * 1024;

    /** Any other packet between clients. */
    public static final int CLIENT_MAX = 512 * 1024;

    /** Hash sets of the largest files (MD4 and AICH part hashes). */
    public static final int HASHSET_MAX = 2 * 1024 * 1024;

    /** Shared file lists. */
    public static final int SHARED_FILES_MAX = 4 * 1024 * 1024;

    /** Any packet from a server (search results, source and server lists). */
    public static final int SERVER_MAX = 1024 * 1024;

    /** File data: hash, 64-bit offsets and one block. */
    public static final int DATA_MAX = 16 + 8 + 8 + Ed2k.EMBLOCKSIZE;

    /** Until hello and hello answer are exchanged. */
    public static final FrameLimit CLIENT_HANDSHAKE =
            (protocol, opcode) ->
                    protocol == Protocol.EDONKEY
                                    && (opcode == Client.HELLO.code()
                                            || opcode == Client.HELLOANSWER.code())
                            ? HANDSHAKE_MAX
                            : FrameLimit.REJECT;

    /** An identified client. */
    public static final FrameLimit CLIENT =
            (protocol, opcode) -> {
                if (protocol == Protocol.EDONKEY) {
                    if (opcode == Client.SENDINGPART.code()) {
                        return DATA_MAX;
                    }
                    if (opcode == Client.ASKSHAREDFILESANSWER.code()
                            || opcode == Client.ASKSHAREDFILESDIRANS.code()) {
                        return SHARED_FILES_MAX;
                    }
                    if (opcode == Client.HASHSETANSWER.code()) {
                        return HASHSET_MAX;
                    }
                } else if (protocol == Protocol.EMULE) {
                    if (opcode == Extended.SENDINGPART_I64.code()
                            || opcode == Extended.COMPRESSEDPART.code()
                            || opcode == Extended.COMPRESSEDPART_I64.code()) {
                        return DATA_MAX;
                    }
                    if (opcode == Extended.HASHSETANSWER2.code()) {
                        return HASHSET_MAX;
                    }
                }
                return CLIENT_MAX;
            };

    /** A server connection. */
    public static final FrameLimit SERVER = FrameLimit.fixed(SERVER_MAX);

    private FrameLimits() {}
}
