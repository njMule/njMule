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

import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.Hash16;
import org.njmule.core.kad.UInt128;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.tag.ClientTag;
import org.njmule.core.util.Ipv4;

/** The messages of firewalled clients, and the buddy tags of the hello, byte by byte. */
class LowIdMessagesTest {

    private static final UInt128 BUDDY = UInt128.parseHex("0102030405060708090A0B0C0D0E0F10");
    private static final UInt128 FILE = UInt128.parseHex("A0A1A2A3A4A5A6A7A8A9AAABACADAEAF");
    private static final Hash16 USER = Hash16.parseHex("00112233445566778899aabbccddeeff");

    /** Reads a packet back. */
    @FunctionalInterface
    private interface Decoder {
        Object decode(Packet packet) throws DecodeException;
    }

    private Packet packet;
    private Hello hello;

    @Test
    void aRelayedCallbackNamesTheBuddyTheFileAndTheRequesterInKadLayout() throws Exception {
        whenEncoded(new LowIdMessages.Callback(BUDDY, FILE, Ipv4.of(1, 2, 3, 4), 4662));

        thenThePacketIs(
                Protocol.EMULE,
                Opcodes.Extended.CALLBACK.code(),
                "04030201080706050c0b0a09100f0e0d" + "a3a2a1a0a7a6a5a4abaaa9a8afaeadac" + "04030201" + "3612");
        thenItReadsBackAs(
                LowIdMessages.Callback::decode, new LowIdMessages.Callback(BUDDY, FILE, Ipv4.of(1, 2, 3, 4), 4662));
    }

    @Test
    void aDirectCallbackRequestGivesOurPortHashAndConnectOptions() throws Exception {
        whenEncoded(new LowIdMessages.DirectCallbackRequest(4662, USER, 0x03));

        thenThePacketIs(
                Protocol.EMULE,
                Opcodes.ClientUdp.DIRECTCALLBACKREQ.code(),
                "3612" + "00112233445566778899aabbccddeeff" + "03");
        thenItReadsBackAs(
                LowIdMessages.DirectCallbackRequest::decode, new LowIdMessages.DirectCallbackRequest(4662, USER, 0x03));
    }

    @Test
    void aUdpCheckRequestGivesBothPortsAndTheKey() throws Exception {
        whenEncoded(new LowIdMessages.UdpCheckRequest(4672, 5000, 0x01020304));

        thenThePacketIs(Protocol.EMULE, Opcodes.Extended.FWCHECKUDPREQ.code(), "4012" + "8813" + "04030201");
        thenItReadsBackAs(
                LowIdMessages.UdpCheckRequest::decode, new LowIdMessages.UdpCheckRequest(4672, 5000, 0x01020304));
    }

    @Test
    void aBuddyPingCarriesNothing() {
        whenEncoded(LowIdMessages.buddyPing());

        thenThePacketIs(Protocol.EMULE, Opcodes.Extended.BUDDYPING.code(), "");
    }

    @Test
    void aBuddyPongCarriesNothing() {
        whenEncoded(LowIdMessages.buddyPong());

        thenThePacketIs(Protocol.EMULE, Opcodes.Extended.BUDDYPONG.code(), "");
    }

    @Test
    void aHelloWithABuddyNamesItRightAfterTheUdpPorts() throws Exception {
        givenOurHello(Hello.MISC2_DIRECT_UDP_CALLBACK);

        whenOurBuddyIsAdded(Ipv4.of(1, 2, 3, 4), 4672);

        thenTheTagsAre(
                ClientTag.NAME,
                ClientTag.VERSION,
                ClientTag.EMULE_UDPPORTS,
                ClientTag.EMULE_BUDDYIP,
                ClientTag.EMULE_BUDDYUDP,
                ClientTag.EMULE_MISCOPTIONS1,
                ClientTag.EMULE_MISCOPTIONS2,
                ClientTag.EMULE_VERSION);
        thenTheBuddyReadIs(Ipv4.of(1, 2, 3, 4), 4672);
        thenDirectCallbacksAreTaken(true);
    }

    @Test
    void aHelloWithoutABuddyNamesNone() throws Exception {
        givenOurHello(0);

        whenTheHelloTravels();

        thenTheBuddyReadIs(0, 0);
        thenDirectCallbacksAreTaken(false);
    }

    private void givenOurHello(int misc2) {
        this.hello = Hello.of(Hash16.parseHex("ab".repeat(16)), 1, 4662, "nick", 4672, 4672, 0, misc2, 0, 0);
    }

    private void whenOurBuddyIsAdded(int ip, int port) throws Exception {
        this.hello = Hello.decode(this.hello.withBuddy(ip, port).encode(true));
    }

    private void whenTheHelloTravels() throws Exception {
        this.hello = Hello.decode(this.hello.encode(false));
    }

    private void thenDirectCallbacksAreTaken(boolean taken) {
        assertThat(this.hello.supportsDirectCallback()).isEqualTo(taken);
    }

    private void whenEncoded(LowIdMessages.Callback callback) {
        this.packet = callback.encode();
    }

    private void whenEncoded(LowIdMessages.DirectCallbackRequest request) {
        this.packet = request.encode();
    }

    private void whenEncoded(LowIdMessages.UdpCheckRequest request) {
        this.packet = request.encode();
    }

    private void whenEncoded(Packet built) {
        this.packet = built;
    }

    private void thenThePacketIs(int protocol, int opcode, String payload) {
        assertThat(this.packet.protocol()).isEqualTo(protocol);
        assertThat(this.packet.opcode()).isEqualTo(opcode);
        assertThat(HexFormat.of().formatHex(this.packet.payload())).isEqualTo(payload);
    }

    private void thenItReadsBackAs(Decoder decoder, Object expected) throws DecodeException {
        assertThat(decoder.decode(this.packet)).isEqualTo(expected);
    }

    private void thenTheTagsAre(int... ids) {
        List<Integer> names = this.hello.tags().stream().map(t -> t.name().id()).toList();
        assertThat(names).containsExactlyElementsOf(IntStream.of(ids).boxed().toList());
    }

    private void thenTheBuddyReadIs(int ip, int port) {
        assertThat(this.hello.buddyIp()).isEqualTo(ip);
        assertThat(this.hello.buddyUdpPort()).isEqualTo(port);
    }
}
