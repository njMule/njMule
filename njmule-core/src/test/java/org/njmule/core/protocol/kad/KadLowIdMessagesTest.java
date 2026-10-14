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
package org.njmule.core.protocol.kad;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.njmule.core.kad.UInt128;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;

/** The Kad messages of buddies, callbacks and the UDP firewall test, byte by byte. */
class KadLowIdMessagesTest {

    private static final UInt128 ID = UInt128.parseHex("0102030405060708090A0B0C0D0E0F10");
    private static final UInt128 HASH = UInt128.parseHex("A0A1A2A3A4A5A6A7A8A9AAABACADAEAF");
    private static final String ID_WIRE = "04030201080706050c0b0a09100f0e0d";
    private static final String HASH_WIRE = "a3a2a1a0a7a6a5a4abaaa9a8afaeadac";

    private Packet packet;
    private Object decoded;
    private Exception failure;

    @Test
    void aBuddyRequestGivesTheBuddyIdTheUserHashAndThePort() throws Exception {
        this.whenSent(new KadMessages.FindBuddy(ID, HASH, 4662).encode());

        this.thenThePacketIs(Opcodes.Kad.FINDBUDDY_REQ, ID_WIRE + HASH_WIRE + "3612");
        this.thenItReadsBackAs(KadMessages.FindBuddy::decode, new KadMessages.FindBuddy(ID, HASH, 4662));
    }

    @Test
    void aBuddyOfferCarriesTheConnectOptionsWhenGiven() throws Exception {
        this.whenSent(new KadMessages.BuddyOffer(ID, HASH, 4662, OptionalInt.of(3)).encode());

        this.thenThePacketIs(Opcodes.Kad.FINDBUDDY_RES, ID_WIRE + HASH_WIRE + "3612" + "03");
        this.thenItReadsBackAs(
                KadMessages.BuddyOffer::decode, new KadMessages.BuddyOffer(ID, HASH, 4662, OptionalInt.of(3)));
    }

    @Test
    void aBuddyOfferOfAnOlderNodeHasNoConnectOptions() throws Exception {
        this.whenSent(new KadMessages.BuddyOffer(ID, HASH, 4662, OptionalInt.empty()).encode());

        this.thenThePacketIs(Opcodes.Kad.FINDBUDDY_RES, ID_WIRE + HASH_WIRE + "3612");
        this.thenItReadsBackAs(
                KadMessages.BuddyOffer::decode, new KadMessages.BuddyOffer(ID, HASH, 4662, OptionalInt.empty()));
    }

    @Test
    void aCallbackRequestGivesTheBuddyIdTheFileAndOurPort() throws Exception {
        this.whenSent(new KadMessages.CallbackRequest(ID, HASH, 4662).encode());

        this.thenThePacketIs(Opcodes.Kad.CALLBACK_REQ, ID_WIRE + HASH_WIRE + "3612");
        this.thenItReadsBackAs(KadMessages.CallbackRequest::decode, new KadMessages.CallbackRequest(ID, HASH, 4662));
    }

    @Test
    void theUdpTestDatagramGivesTheErrorAndThePort() throws Exception {
        this.whenSent(new KadMessages.FirewallUdp(1, 4672).encode());

        this.thenThePacketIs(Opcodes.Kad.FIREWALLUDP, "01" + "4012");
        this.thenItReadsBackAs(KadMessages.FirewallUdp::decode, new KadMessages.FirewallUdp(1, 4672));
    }

    @Test
    void aCallbackRequestTooShortIsRefused() {
        this.whenACallbackRequestIsRead(new byte[33]);

        this.thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void aBuddyRequestTooShortIsRefused() {
        this.whenABuddyRequestIsRead(new byte[33]);

        this.thenExceptionOccurred(DecodeException.class);
    }

    private void whenSent(Packet sent) {
        this.packet = sent;
    }

    private void whenACallbackRequestIsRead(byte[] payload) {
        try {
            this.decoded = KadMessages.CallbackRequest.decode(
                    Packet.of(Protocol.KAD, Opcodes.Kad.CALLBACK_REQ.code(), payload));
        } catch (DecodeException e) {
            this.failure = e;
        }
    }

    private void whenABuddyRequestIsRead(byte[] payload) {
        try {
            this.decoded =
                    KadMessages.FindBuddy.decode(Packet.of(Protocol.KAD, Opcodes.Kad.FINDBUDDY_REQ.code(), payload));
        } catch (DecodeException e) {
            this.failure = e;
        }
    }

    private void thenThePacketIs(Opcodes.Kad opcode, String payload) {
        assertThat(this.packet.protocol()).isEqualTo(Protocol.KAD);
        assertThat(this.packet.opcode()).isEqualTo(opcode.code());
        assertThat(HexFormat.of().formatHex(this.packet.payload())).isEqualTo(payload);
    }

    private void thenItReadsBackAs(Decoder decoder, Object expected) throws DecodeException {
        assertThat(decoder.decode(this.packet)).isEqualTo(expected);
    }

    /** Reads a packet back. */
    @FunctionalInterface
    private interface Decoder {
        Object decode(Packet packet) throws DecodeException;
    }

    private void thenExceptionOccurred(Class<? extends Exception> type) {
        assertThat(this.decoded).isNull();
        assertThat(this.failure).isInstanceOf(type);
    }
}
