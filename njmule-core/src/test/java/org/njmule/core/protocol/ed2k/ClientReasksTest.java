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

import java.util.BitSet;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.util.Ipv4;

/** The UDP re-asks between clients, byte by byte, as each UDP version carries them. */
class ClientReasksTest {

    private static final Hash16 FILE = Hash16.parseHex("00112233445566778899aabbccddeeff");
    private static final String FILE_HEX = "00112233445566778899aabbccddeeff";
    private static final ClientMessages.PartStatus FIRST_AND_THIRD = new ClientMessages.PartStatus(3, bits(0, 2));
    private static final Hash16 BUDDY_ID = Hash16.parseHex("ffeeddccbbaa99887766554433221100");
    private static final String BUDDY_ID_HEX = "ffeeddccbbaa99887766554433221100";

    private Packet packet;
    private ClientReasks.Ping ping;
    private ClientReasks.Ack ack;
    private ClientReasks.ThroughBuddy throughBuddy;
    private ClientReasks.Relayed relayed;
    private Exception exception;

    @Test
    void shouldGiveTheFileOurPartsAndTheCompleteSourcesInAPingOfVersion4() {
        whenThePingIsMadeForVersion(new ClientReasks.Ping(FILE, FIRST_AND_THIRD, 5), 4);

        thenThePacketIs(Opcodes.ClientUdp.REASKFILEPING, FILE_HEX + "0300" + "05" + "0500");
    }

    @Test
    void shouldGiveOnlyTheFileInAPingOfVersion2() {
        whenThePingIsMadeForVersion(new ClientReasks.Ping(FILE, FIRST_AND_THIRD, 5), 2);

        thenThePacketIs(Opcodes.ClientUdp.REASKFILEPING, FILE_HEX);
    }

    @Test
    void shouldReadBackAPingOfVersion4() throws Exception {
        givenThePingForVersion(new ClientReasks.Ping(FILE, FIRST_AND_THIRD, 5), 4);

        whenThePingIsReadAsVersion(4);

        thenThePingIs(new ClientReasks.Ping(FILE, FIRST_AND_THIRD, 5));
    }

    @Test
    void shouldGiveTheUploaderPartsAndTheRankInAnAnswerOfVersion4() {
        whenTheAnswerIsMadeForVersion(new ClientReasks.Ack(Optional.of(ClientMessages.PartStatus.COMPLETE), 12), 4);

        thenThePacketIs(Opcodes.ClientUdp.REASKACK, "0000" + "0c00");
    }

    @Test
    void shouldReadTheRankOfAnAnswerOfVersion3() throws Exception {
        givenTheAnswerForVersion(new ClientReasks.Ack(Optional.of(FIRST_AND_THIRD), 300), 3);

        whenTheAnswerIsReadAsVersion(3);

        thenTheAnswerIs(new ClientReasks.Ack(Optional.empty(), 300));
    }

    @Test
    void shouldPutTheBuddyIdBeforeAPingSentThroughTheBuddy() {
        whenThePingIsMadeThroughTheBuddyForVersion(new ClientReasks.Ping(FILE, FIRST_AND_THIRD, 2), 3);

        thenThePacketIs(Opcodes.ClientUdp.REASKCALLBACKUDP, BUDDY_ID_HEX + FILE_HEX + "0200");
    }

    @Test
    void shouldReadTheBuddyIdAndThePingOfAPingThroughTheBuddy() throws Exception {
        givenThePingThroughTheBuddyForVersion(new ClientReasks.Ping(FILE, FIRST_AND_THIRD, 2), 3);

        whenThePingThroughTheBuddyIsRead();

        thenTheBuddyIdIs(BUDDY_ID);
        thenTheCarriedPingIs(new ClientReasks.Ping(FILE, FIRST_AND_THIRD, 2).encode(3));
    }

    @Test
    void shouldRefuseAPingThroughTheBuddyWithoutItsFile() {
        givenAPingThroughTheBuddyOf(BUDDY_ID_HEX + "0011223344556677");

        whenThePingThroughTheBuddyIsRead();

        thenADecodeExceptionOccurred();
    }

    @Test
    void shouldGiveTheRequesterAddressBeforeTheRelayedPing() {
        whenTheRelayedPingIsMadeFor(Ipv4.of(1, 2, 3, 4), 4672, new ClientReasks.Ping(FILE, FIRST_AND_THIRD, 2));

        thenThePacketIs(Opcodes.Extended.REASKCALLBACKTCP, "01020304" + "4012" + FILE_HEX + "0300" + "05" + "0200");
    }

    @Test
    void shouldReadBackARelayedPing() throws Exception {
        givenTheRelayedPingFor(Ipv4.of(1, 2, 3, 4), 4672, new ClientReasks.Ping(FILE, FIRST_AND_THIRD, 2));

        whenTheRelayedPingIsRead();

        thenTheRelayedPingIs(new ClientReasks.Relayed(
                Ipv4.of(1, 2, 3, 4), 4672, new ClientReasks.Ping(FILE, FIRST_AND_THIRD, 2).encode(4)));
    }

    private void givenThePingThroughTheBuddyForVersion(ClientReasks.Ping sent, int udpVersion) {
        this.packet = sent.encodeThroughBuddy(BUDDY_ID, udpVersion);
    }

    private void givenAPingThroughTheBuddyOf(String payload) {
        this.packet = Packet.of(
                Protocol.EMULE,
                Opcodes.ClientUdp.REASKCALLBACKUDP.code(),
                HexFormat.of().parseHex(payload));
    }

    private void givenTheRelayedPingFor(int ip, int port, ClientReasks.Ping sent) {
        this.packet = new ClientReasks.Relayed(ip, port, sent.encode(4)).encode();
    }

    private void whenThePingIsMadeThroughTheBuddyForVersion(ClientReasks.Ping sent, int udpVersion) {
        this.packet = sent.encodeThroughBuddy(BUDDY_ID, udpVersion);
    }

    private void whenThePingThroughTheBuddyIsRead() {
        try {
            this.throughBuddy = ClientReasks.ThroughBuddy.decode(this.packet);
        } catch (DecodeException e) {
            this.exception = e;
        }
    }

    private void whenTheRelayedPingIsMadeFor(int ip, int port, ClientReasks.Ping sent) {
        this.packet = new ClientReasks.Relayed(ip, port, sent.encode(4)).encode();
    }

    private void whenTheRelayedPingIsRead() throws Exception {
        this.relayed = ClientReasks.Relayed.decode(this.packet);
    }

    private void thenTheBuddyIdIs(Hash16 expected) {
        assertThat(this.throughBuddy.buddyId()).isEqualTo(expected);
    }

    private void thenTheCarriedPingIs(Packet expected) {
        assertThat(this.throughBuddy.ping()).isEqualTo(expected);
    }

    private void thenADecodeExceptionOccurred() {
        assertThat(this.exception).isInstanceOf(DecodeException.class);
    }

    private void thenTheRelayedPingIs(ClientReasks.Relayed expected) {
        assertThat(this.relayed).isEqualTo(expected);
    }

    private void givenThePingForVersion(ClientReasks.Ping sent, int udpVersion) {
        this.packet = sent.encode(udpVersion);
    }

    private void givenTheAnswerForVersion(ClientReasks.Ack sent, int udpVersion) {
        this.packet = sent.encode(udpVersion);
    }

    private void whenThePingIsMadeForVersion(ClientReasks.Ping sent, int udpVersion) {
        this.packet = sent.encode(udpVersion);
    }

    private void whenTheAnswerIsMadeForVersion(ClientReasks.Ack sent, int udpVersion) {
        this.packet = sent.encode(udpVersion);
    }

    private void whenThePingIsReadAsVersion(int udpVersion) throws Exception {
        this.ping = ClientReasks.Ping.decode(this.packet, udpVersion);
    }

    private void whenTheAnswerIsReadAsVersion(int udpVersion) throws Exception {
        this.ack = ClientReasks.Ack.decode(this.packet, udpVersion);
    }

    private void thenThePacketIs(Opcodes.ClientUdp opcode, String payload) {
        thenThePacketHas(opcode.code(), payload);
    }

    private void thenThePacketIs(Opcodes.Extended opcode, String payload) {
        thenThePacketHas(opcode.code(), payload);
    }

    private void thenThePacketHas(int opcode, String payload) {
        assertThat(this.packet.protocol()).isEqualTo(Protocol.EMULE);
        assertThat(this.packet.opcode()).isEqualTo(opcode);
        assertThat(HexFormat.of().formatHex(this.packet.payload())).isEqualTo(payload);
    }

    private void thenThePingIs(ClientReasks.Ping expected) {
        assertThat(this.ping).isEqualTo(expected);
    }

    private void thenTheAnswerIs(ClientReasks.Ack expected) {
        assertThat(this.ack).isEqualTo(expected);
    }

    private static BitSet bits(int... set) {
        BitSet bits = new BitSet();
        for (int bit : set) {
            bits.set(bit);
        }
        return bits;
    }
}
