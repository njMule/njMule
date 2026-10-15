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

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;

class PeerLinkTest {

    private Packet packet;
    private PeerLink.Side side;

    @ParameterizedTest
    @MethodSource("answersOfAnUploader")
    void shouldRouteTheAnswersOfAnUploaderToTheDownload(Packet answer) {
        givenThePacket(answer);

        whenItsSideIsChosen();

        thenTheSideIs(PeerLink.Side.DOWNLOAD);
    }

    @ParameterizedTest
    @MethodSource("requestsOfADownloader")
    void shouldRouteTheRequestsOfADownloaderToTheUpload(Packet request) {
        givenThePacket(request);

        whenItsSideIsChosen();

        thenTheSideIs(PeerLink.Side.UPLOAD);
    }

    // A new hello and chat go to the side that answers them.
    @ParameterizedTest
    @MethodSource("helloChatAndUnknownPackets")
    void shouldRouteAHelloAChatOrAnUnknownPacketToTheUpload(Packet other) {
        givenThePacket(other);

        whenItsSideIsChosen();

        thenTheSideIs(PeerLink.Side.UPLOAD);
    }

    private void givenThePacket(Packet incoming) {
        this.packet = incoming;
    }

    private void whenItsSideIsChosen() {
        this.side = PeerLink.sideOf(this.packet);
    }

    private void thenTheSideIs(PeerLink.Side expected) {
        assertThat(this.side).isEqualTo(expected);
    }

    private static Stream<Packet> answersOfAnUploader() {
        return Stream.of(
                basic(Opcodes.Client.FILESTATUS),
                basic(Opcodes.Client.ACCEPTUPLOADREQ),
                basic(Opcodes.Client.SENDINGPART),
                extended(Opcodes.Extended.COMPRESSEDPART_I64),
                extended(Opcodes.Extended.ANSWERSOURCES2));
    }

    private static Stream<Packet> requestsOfADownloader() {
        return Stream.of(
                basic(Opcodes.Client.STARTUPLOADREQ),
                basic(Opcodes.Client.SETREQFILEID),
                basic(Opcodes.Client.CANCELTRANSFER),
                extended(Opcodes.Extended.REQUESTPARTS_I64),
                extended(Opcodes.Extended.REQUESTSOURCES2));
    }

    private static Stream<Packet> helloChatAndUnknownPackets() {
        return Stream.of(
                basic(Opcodes.Client.HELLO), basic(Opcodes.Client.MESSAGE), Packet.empty(Protocol.EDONKEY, 0xFF));
    }

    private static Packet basic(Opcodes.Client opcode) {
        return Packet.empty(Protocol.EDONKEY, opcode.code());
    }

    private static Packet extended(Opcodes.Extended opcode) {
        return Packet.empty(Protocol.EMULE, opcode.code());
    }
}
