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

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.njmule.core.protocol.packet.Opcodes.Client;
import org.njmule.core.protocol.packet.Opcodes.Extended;
import org.njmule.core.protocol.packet.Opcodes.Kad;
import org.njmule.core.protocol.packet.Opcodes.Server;

class OpcodesTest {

    private Optional<?> found;
    private FrameLimit limits;
    private int maxPayload;

    @Test
    void shouldLookUpAClientOpcode() {
        whenAClientOpcodeIsLookedUp(0x01);

        thenTheOpcodeFoundIs(Client.HELLO);
    }

    @Test
    void shouldLookUpAnExtendedOpcode() {
        whenAnExtendedOpcodeIsLookedUp(0x01);

        thenTheOpcodeFoundIs(Extended.EMULEINFO);
    }

    @Test
    void shouldLookUpAServerOpcode() {
        whenAServerOpcodeIsLookedUp(0x01);

        thenTheOpcodeFoundIs(Server.LOGINREQUEST);
    }

    @Test
    void shouldLookUpAKadOpcode() {
        whenAKadOpcodeIsLookedUp(0x3B);

        thenTheOpcodeFoundIs(Kad.SEARCH_RES);
    }

    @ParameterizedTest
    @ValueSource(ints = {0x02, -1, 256})
    void shouldFindNoClientOpcodeForAnUnknownCode(int code) {
        whenAClientOpcodeIsLookedUp(code);

        thenNoOpcodeIsFound();
    }

    @Test
    void shouldAcceptASmallHelloDuringTheHandshake() {
        givenTheLimits(FrameLimits.CLIENT_HANDSHAKE);

        whenTheMaxPayloadIsAskedFor(Protocol.EDONKEY, Client.HELLO.code());

        thenTheMaxPayloadIs(FrameLimits.HANDSHAKE_MAX);
    }

    @Test
    void shouldRejectDataDuringTheHandshake() {
        givenTheLimits(FrameLimits.CLIENT_HANDSHAKE);

        whenTheMaxPayloadIsAskedFor(Protocol.EDONKEY, Client.SENDINGPART.code());

        thenTheMaxPayloadIs(FrameLimit.REJECT);
    }

    @Test
    void shouldRejectAPackedHelloDuringTheHandshake() {
        givenTheLimits(FrameLimits.CLIENT_HANDSHAKE);

        whenTheMaxPayloadIsAskedFor(Protocol.PACKED, Client.HELLO.code());

        thenTheMaxPayloadIs(FrameLimit.REJECT);
    }

    @Test
    void shouldCapTheDataOfAnIdentifiedClient() {
        givenTheLimits(FrameLimits.CLIENT);

        whenTheMaxPayloadIsAskedFor(Protocol.EMULE, Extended.COMPRESSEDPART.code());

        thenTheMaxPayloadIs(FrameLimits.DATA_MAX);
    }

    @Test
    void shouldCapTheSharedFilesAnswerOfAnIdentifiedClient() {
        givenTheLimits(FrameLimits.CLIENT);

        whenTheMaxPayloadIsAskedFor(Protocol.EDONKEY, Client.ASKSHAREDFILESANSWER.code());

        thenTheMaxPayloadIs(FrameLimits.SHARED_FILES_MAX);
    }

    @Test
    void shouldCapOtherMessagesOfAnIdentifiedClient() {
        givenTheLimits(FrameLimits.CLIENT);

        whenTheMaxPayloadIsAskedFor(Protocol.EDONKEY, Client.MESSAGE.code());

        thenTheMaxPayloadIs(FrameLimits.CLIENT_MAX);
    }

    private void givenTheLimits(FrameLimit frameLimits) {
        this.limits = frameLimits;
    }

    private void whenAClientOpcodeIsLookedUp(int code) {
        this.found = Client.of(code);
    }

    private void whenAnExtendedOpcodeIsLookedUp(int code) {
        this.found = Extended.of(code);
    }

    private void whenAServerOpcodeIsLookedUp(int code) {
        this.found = Server.of(code);
    }

    private void whenAKadOpcodeIsLookedUp(int code) {
        this.found = Kad.of(code);
    }

    private void whenTheMaxPayloadIsAskedFor(int protocol, int opcode) {
        this.maxPayload = this.limits.maxPayload(protocol, opcode);
    }

    private void thenTheOpcodeFoundIs(Opcodes.Opcode opcode) {
        assertThat(this.found).isEqualTo(Optional.of(opcode));
    }

    private void thenNoOpcodeIsFound() {
        assertThat(this.found).isEmpty();
    }

    private void thenTheMaxPayloadIs(int expected) {
        assertThat(this.maxPayload).isEqualTo(expected);
    }
}
