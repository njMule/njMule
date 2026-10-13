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
package org.njmule.core.peer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;

class PeerLinkTest {

    @Test
    void answersOfAnUploaderGoToTheDownloadAndRequestsOfADownloaderToTheUpload() {
        assertThat(PeerLink.sideOf(basic(Opcodes.Client.FILESTATUS))).isEqualTo(PeerLink.Side.DOWNLOAD);
        assertThat(PeerLink.sideOf(basic(Opcodes.Client.ACCEPTUPLOADREQ))).isEqualTo(PeerLink.Side.DOWNLOAD);
        assertThat(PeerLink.sideOf(basic(Opcodes.Client.SENDINGPART))).isEqualTo(PeerLink.Side.DOWNLOAD);
        assertThat(PeerLink.sideOf(extended(Opcodes.Extended.COMPRESSEDPART_I64)))
                .isEqualTo(PeerLink.Side.DOWNLOAD);
        assertThat(PeerLink.sideOf(extended(Opcodes.Extended.ANSWERSOURCES2))).isEqualTo(PeerLink.Side.DOWNLOAD);

        assertThat(PeerLink.sideOf(basic(Opcodes.Client.STARTUPLOADREQ))).isEqualTo(PeerLink.Side.UPLOAD);
        assertThat(PeerLink.sideOf(basic(Opcodes.Client.SETREQFILEID))).isEqualTo(PeerLink.Side.UPLOAD);
        assertThat(PeerLink.sideOf(basic(Opcodes.Client.CANCELTRANSFER))).isEqualTo(PeerLink.Side.UPLOAD);
        assertThat(PeerLink.sideOf(extended(Opcodes.Extended.REQUESTPARTS_I64))).isEqualTo(PeerLink.Side.UPLOAD);
        assertThat(PeerLink.sideOf(extended(Opcodes.Extended.REQUESTSOURCES2))).isEqualTo(PeerLink.Side.UPLOAD);
        // A new hello and chat go to the side that answers them.
        assertThat(PeerLink.sideOf(basic(Opcodes.Client.HELLO))).isEqualTo(PeerLink.Side.UPLOAD);
        assertThat(PeerLink.sideOf(basic(Opcodes.Client.MESSAGE))).isEqualTo(PeerLink.Side.UPLOAD);
        assertThat(PeerLink.sideOf(Packet.empty(Protocol.EDONKEY, 0xFF))).isEqualTo(PeerLink.Side.UPLOAD);
    }

    private static Packet basic(Opcodes.Client opcode) {
        return Packet.empty(Protocol.EDONKEY, opcode.code());
    }

    private static Packet extended(Opcodes.Extended opcode) {
        return Packet.empty(Protocol.EMULE, opcode.code());
    }
}
