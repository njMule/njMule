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

import com.google.common.net.InetAddresses;
import java.net.InetSocketAddress;
import java.util.HexFormat;
import java.util.Optional;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.util.Ipv4;

class NatTraversalMessagesTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final Hash16 A = Hash16.parseHex("aa".repeat(16));
    private static final Hash16 B = Hash16.parseHex("bb".repeat(16));
    private static final Hash16 FILE = Hash16.parseHex("dd".repeat(16));

    private Packet packet;
    private NatTraversalMessages.Caps caps;
    private Optional<NatTraversalMessages.Forward> forward;
    private NatTraversalMessages.Rendezvous rendezvous;
    private byte[] proofBytes;
    private NatTraversalMessages.QuicProof proof;
    private Throwable exception;

    @Test
    void shouldWriteARendezvousThroughTheBuddyAsAReAskWithAZeroFileHash() {
        whenARendezvousIsWrittenFor(Hash16.parseHex("cc".repeat(16)));

        thenThePacketIs(
                Protocol.EMULE,
                Opcodes.ClientUdp.REASKCALLBACKUDP.code(),
                "cc".repeat(16) + "00".repeat(16) + "a0" + "aa".repeat(16) + "a1" + "dd".repeat(16) + "c6336414"
                        + "4012" + "01");
    }

    @Test
    void shouldReadARendezvousPassedOnByTheBuddyWithTheRequestersAddress() throws DecodeException {
        givenAForward("0a000001" + "4012" + "00".repeat(16) + "a0" + "aa".repeat(16) + "80" + "dd".repeat(16));

        whenTheForwardIsRead();

        thenTheForwardComesFrom("10.0.0.1", 4672);
        thenItCarriesARendezvousFrom(A, 0x80, FILE);
    }

    @Test
    void shouldReadAnIpv6RequesterInAForward() throws DecodeException {
        givenAForward("ffffffff" + "20010db8000000000000000000000001" + "4012" + "00".repeat(16) + "a0"
                + "aa".repeat(16) + "80");

        whenTheForwardIsRead();

        thenTheForwardComesFrom("2001:db8::1", 4672);
    }

    @Test
    void shouldTakeAForwardWithAFileHashForAReAsk() throws DecodeException {
        givenAForward("0a000001" + "4012" + "dd".repeat(16) + "0000");

        whenTheForwardIsRead();

        thenItIsNoForward();
    }

    @Test
    void shouldWriteCapabilitiesInFiftyFourBytesAfterTheirMagic() {
        whenCapabilitiesAreWritten(new NatTraversalMessages.Caps(false, 0x81, A, B, FILE));

        thenThePacketIs(
                Protocol.UDP_RESERVED_2,
                NatTraversalMessages.FRAME_CAPS,
                "45415143" + "01" + "81" + "aa".repeat(16) + "bb".repeat(16) + "dd".repeat(16));
    }

    @Test
    void shouldReadACapabilityAnswer() throws DecodeException {
        givenThePacket(Packet.of(
                Protocol.UDP_RESERVED_2,
                NatTraversalMessages.FRAME_CAPS_ACK,
                HEX.parseHex("45415143" + "01" + "80" + "bb".repeat(16) + "aa".repeat(16) + "00".repeat(16))));

        whenTheCapabilitiesAreRead();

        thenTheCapabilitiesAre(new NatTraversalMessages.Caps(true, 0x80, B, A, Hash16.ZERO));
    }

    @Test
    void shouldCarryAQuicDatagramInFrameOne() {
        whenAQuicDatagramIsFramed(HEX.parseHex("c0000000"));

        thenThePacketIs(Protocol.UDP_RESERVED_2, NatTraversalMessages.FRAME_QUIC, "c0000000");
    }

    @Test
    void shouldWriteAQuicProofAsItsMagicAndBothHashes() {
        whenAQuicProofIsWritten(new NatTraversalMessages.QuicProof(A, Hash16.ZERO));

        thenTheProofBytesAre("4541514e31" + "aa".repeat(16) + "00".repeat(16));
    }

    @Test
    void shouldReadAQuicProof() {
        givenTheProofBytes("4541514e31" + "bb".repeat(16) + "aa".repeat(16));

        whenTheQuicProofIsRead();

        thenTheProofIs(new NatTraversalMessages.QuicProof(B, A));
    }

    @Test
    void shouldRejectAQuicProofWithAnotherMagic() {
        givenTheProofBytes("4541514e32" + "bb".repeat(16) + "aa".repeat(16));

        whenTheQuicProofIsRead();

        thenTheProofIsRejected();
    }

    @Test
    void shouldRejectAShortQuicProof() {
        givenTheProofBytes("4541514e31" + "bb".repeat(16));

        whenTheQuicProofIsRead();

        thenTheProofIsRejected();
    }

    @Property
    void shouldOnlyFailWithDecodeExceptionOnAGarbageForward(@ForAll @Size(max = 80) byte[] data) {
        givenAForward(HEX.formatHex(data));

        whenTheForwardIsRead();

        thenAnyFailureIsADecodeException();
    }

    private void givenAForward(String hex) {
        this.packet = Packet.of(Protocol.EMULE, Opcodes.Extended.REASKCALLBACKTCP.code(), HEX.parseHex(hex));
    }

    private void givenThePacket(Packet p) {
        this.packet = p;
    }

    private void givenTheProofBytes(String hex) {
        this.proofBytes = HEX.parseHex(hex);
    }

    private void whenARendezvousIsWrittenFor(Hash16 buddyId) {
        this.packet = new NatTraversalMessages.Rendezvous(
                        A, 0xA1, FILE, Ipv4.parse("198.51.100.20"), 4672, NatTraversalMessages.TRANSPORT_UTP)
                .encodeThroughBuddy(buddyId);
    }

    private void whenTheForwardIsRead() {
        Packet received = this.packet;
        this.exception = catchThrowable(() -> {
            this.forward = NatTraversalMessages.Forward.decode(received);
            if (this.forward.isPresent()) {
                this.rendezvous = NatTraversalMessages.Rendezvous.decode(
                        this.forward.get().payload());
            }
        });
    }

    private void whenCapabilitiesAreWritten(NatTraversalMessages.Caps written) {
        this.packet = written.encode();
    }

    private void whenAQuicDatagramIsFramed(byte[] datagram) {
        this.packet = NatTraversalMessages.quicFrame(datagram);
    }

    private void whenAQuicProofIsWritten(NatTraversalMessages.QuicProof written) {
        this.proofBytes = written.encode();
    }

    private void whenTheQuicProofIsRead() {
        byte[] read = this.proofBytes;
        this.exception = catchThrowable(() -> this.proof = NatTraversalMessages.QuicProof.decode(read));
    }

    private void whenTheCapabilitiesAreRead() throws DecodeException {
        this.caps = NatTraversalMessages.Caps.decode(this.packet);
    }

    private void thenThePacketIs(int protocol, int opcode, String payloadHex) {
        assertThat(this.packet.protocol()).isEqualTo(protocol);
        assertThat(this.packet.opcode()).isEqualTo(opcode);
        assertThat(HEX.formatHex(this.packet.payload())).isEqualTo(payloadHex);
    }

    private void thenTheForwardComesFrom(String ip, int port) {
        assertThat(this.exception).isNull();
        assertThat(this.forward)
                .hasValueSatisfying(f ->
                        assertThat(f.requester()).isEqualTo(new InetSocketAddress(InetAddresses.forString(ip), port)));
    }

    private void thenItCarriesARendezvousFrom(Hash16 requester, int options, Hash16 file) {
        assertThat(this.forward.orElseThrow().opcode()).isEqualTo(Opcodes.ClientUdp.RENDEZVOUS.code());
        assertThat(this.rendezvous.requester()).isEqualTo(requester);
        assertThat(this.rendezvous.options()).isEqualTo(options);
        assertThat(this.rendezvous.file()).isEqualTo(file);
        assertThat(this.rendezvous.requesterIp()).isZero();
    }

    private void thenItIsNoForward() {
        assertThat(this.exception).isNull();
        assertThat(this.forward).isEmpty();
    }

    private void thenTheCapabilitiesAre(NatTraversalMessages.Caps expected) {
        assertThat(this.caps).isEqualTo(expected);
    }

    private void thenTheProofBytesAre(String hex) {
        assertThat(HEX.formatHex(this.proofBytes)).isEqualTo(hex);
    }

    private void thenTheProofIs(NatTraversalMessages.QuicProof expected) {
        assertThat(this.exception).isNull();
        assertThat(this.proof).isEqualTo(expected);
    }

    private void thenTheProofIsRejected() {
        assertThat(this.exception).isInstanceOf(DecodeException.class);
    }

    private void thenAnyFailureIsADecodeException() {
        if (this.exception != null) {
            assertThat(this.exception).isInstanceOf(DecodeException.class);
        }
    }
}
