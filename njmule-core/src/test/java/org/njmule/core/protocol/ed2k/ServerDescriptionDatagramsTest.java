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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.io.StringEncoding;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.tag.ServerTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;

/** The description a server gives over UDP, asked for with a challenge. */
class ServerDescriptionDatagramsTest {

    private static final List<Tag> TAGS = List.of(
            new Tag.StringTag(TagName.id(ServerTag.SERVERNAME), "Name"),
            new Tag.StringTag(TagName.id(ServerTag.DESCRIPTION), "Text"));

    private Packet packet;
    private ServerDatagrams.Description read;
    private Throwable exception;

    @Test
    void shouldAskWithAChallengeWhoseLowHalfIsNoValidStringLength() {
        whenTheRequestIsMadeWithRandomBits(0xABCD_1234);

        thenThePacketIs(Opcodes.ServerUdp.SERVER_DESC_REQ, "fff03412");
    }

    @Test
    void shouldReadBackTheChallengeAndTheTagsOfADescription() {
        givenTheDescription(new ServerDatagrams.Description(ServerDatagrams.descriptionChallenge(0x1234), TAGS));

        whenTheDescriptionIsRead();

        thenTheDescriptionReadIs(new ServerDatagrams.Description(0x1234_F0FFL, TAGS));
    }

    @Test
    void shouldRefuseTheOldDescriptionThatCarriesNoChallenge() {
        givenTheOldDescription("Name", "Text");

        whenTheDescriptionIsRead();

        thenTheDescriptionIsRefused();
    }

    private void givenTheDescription(ServerDatagrams.Description description) {
        this.packet = description.encode();
    }

    private void givenTheOldDescription(String name, String description) {
        this.packet = Packet.of(
                Protocol.EDONKEY,
                Opcodes.ServerUdp.SERVER_DESC_RES.code(),
                new PacketWriter().string16(name, StringEncoding.UTF8).string16(description, StringEncoding.UTF8));
    }

    private void whenTheRequestIsMadeWithRandomBits(int random) {
        this.packet = ServerDatagrams.descriptionRequest(ServerDatagrams.descriptionChallenge(random));
    }

    private void whenTheDescriptionIsRead() {
        this.exception = catchThrowable(() -> this.read = ServerDatagrams.Description.decode(this.packet));
    }

    private void thenThePacketIs(Opcodes.ServerUdp opcode, String payload) {
        assertThat(this.packet.protocol()).isEqualTo(Protocol.EDONKEY);
        assertThat(this.packet.opcode()).isEqualTo(opcode.code());
        assertThat(HexFormat.of().formatHex(this.packet.payload())).isEqualTo(payload);
    }

    private void thenTheDescriptionReadIs(ServerDatagrams.Description expected) {
        assertThat(this.exception).isNull();
        assertThat(this.read).isEqualTo(expected);
    }

    private void thenTheDescriptionIsRefused() {
        assertThat(this.exception).isInstanceOf(DecodeException.class);
    }
}
