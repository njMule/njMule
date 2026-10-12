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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.njmule.core.protocol.packet.Opcodes.Client;
import org.njmule.core.protocol.packet.Opcodes.Extended;
import org.njmule.core.protocol.packet.Opcodes.Kad;
import org.njmule.core.protocol.packet.Opcodes.Server;

class OpcodesTest {

    @Test
    void looksUpOpcodesPerProtocol() {
        assertThat(Client.of(0x01)).contains(Client.HELLO);
        assertThat(Extended.of(0x01)).contains(Extended.EMULEINFO);
        assertThat(Server.of(0x01)).contains(Server.LOGINREQUEST);
        assertThat(Kad.of(0x3B)).contains(Kad.SEARCH_RES);
        assertThat(Client.of(0x02)).isEmpty();
        assertThat(Client.of(-1)).isEmpty();
        assertThat(Client.of(256)).isEmpty();
    }

    @Test
    void handshakeAcceptsOnlySmallGreetings() {
        assertThat(FrameLimits.CLIENT_HANDSHAKE.maxPayload(Protocol.EDONKEY, Client.HELLO.code()))
                .isEqualTo(FrameLimits.HANDSHAKE_MAX);
        assertThat(
                        FrameLimits.CLIENT_HANDSHAKE.maxPayload(
                                Protocol.EDONKEY, Client.SENDINGPART.code()))
                .isEqualTo(FrameLimit.REJECT);
        assertThat(FrameLimits.CLIENT_HANDSHAKE.maxPayload(Protocol.PACKED, Client.HELLO.code()))
                .isEqualTo(FrameLimit.REJECT);
    }

    @Test
    void identifiedClientsGetPerPacketCaps() {
        assertThat(FrameLimits.CLIENT.maxPayload(Protocol.EMULE, Extended.COMPRESSEDPART.code()))
                .isEqualTo(FrameLimits.DATA_MAX);
        assertThat(
                        FrameLimits.CLIENT.maxPayload(
                                Protocol.EDONKEY, Client.ASKSHAREDFILESANSWER.code()))
                .isEqualTo(FrameLimits.SHARED_FILES_MAX);
        assertThat(FrameLimits.CLIENT.maxPayload(Protocol.EDONKEY, Client.MESSAGE.code()))
                .isEqualTo(FrameLimits.CLIENT_MAX);
    }
}
