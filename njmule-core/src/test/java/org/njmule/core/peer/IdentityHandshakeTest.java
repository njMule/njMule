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

import java.net.Inet4Address;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.Hash16;
import org.njmule.core.crypto.Rsa;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.security.SecureIdent;

class IdentityHandshakeTest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Hash16 HASH_A = Hash16.parseHex("aa".repeat(16));
    private static final Hash16 HASH_B = Hash16.parseHex("bb".repeat(16));
    private static final Inet4Address IP_A = Inet4Address.ofLiteral("10.0.0.1");
    private static final Inet4Address IP_B = Inet4Address.ofLiteral("10.0.0.2");

    private final IdentityHandshake.Identity a =
            IdentityHandshake.Identity.of(Rsa.generate(Rsa.NETWORK_KEY_BITS, RANDOM));
    private final IdentityHandshake.Identity b =
            IdentityHandshake.Identity.of(Rsa.generate(Rsa.NETWORK_KEY_BITS, RANDOM));
    private final List<String> results = new ArrayList<>();

    @Test
    void strangersExchangeKeysAndSignatures() throws DecodeException {
        this.run(3, 3, Optional.empty(), Optional.empty());

        assertThat(this.results).containsExactlyInAnyOrder("A identified " + HASH_B, "B identified " + HASH_A);
    }

    @Test
    void aPinnedKeyNeedsOnlyTheSignature() throws DecodeException {
        this.run(3, 3, Optional.of(this.b.publicKey()), Optional.of(this.a.publicKey()));

        assertThat(this.results).containsExactlyInAnyOrder("A identified " + HASH_B, "B identified " + HASH_A);
    }

    @Test
    void aDifferentKeyThanThePinnedOneFails() throws DecodeException {
        byte[] stranger = IdentityHandshake.Identity.of(Rsa.generate(Rsa.NETWORK_KEY_BITS, RANDOM))
                .publicKey();

        this.run(3, 3, Optional.of(stranger), Optional.empty());

        // A signs over the key it pinned, which is not B's: both checks fail.
        assertThat(this.results).containsExactlyInAnyOrder("A failed " + HASH_B, "B failed " + HASH_A);
    }

    @Test
    void peersWithoutVersionOneGetAddressBoundSignatures() throws DecodeException {
        // A only takes version 2, so B binds its signature to an address A can check.
        this.run(2, 3, Optional.empty(), Optional.empty());

        assertThat(this.results).containsExactlyInAnyOrder("A identified " + HASH_B, "B identified " + HASH_A);
    }

    @Test
    void peersThatDoNotTakePartAreLeftAlone() throws DecodeException {
        Deque<Packet> sent = new ArrayDeque<>();
        IdentityHandshake handshake = new IdentityHandshake(
                this.a,
                hello(HASH_B, 0),
                IP_B,
                Optional.empty(),
                Optional.empty(),
                Optional.of(IP_A),
                sent::add,
                this.listener("A"));
        handshake.start(RANDOM);

        assertThat(handshake.isSupported()).isFalse();
        assertThat(sent).isEmpty();
    }

    @Test
    void peersTakingBothVersionsGetASignatureBoundToOurAddressWhenWeKnowIt() throws DecodeException {
        List<Packet> fromA = new ArrayList<>();
        List<Packet> fromB = new ArrayList<>();

        this.run(3, 3, Optional.of(IP_A), Optional.of(IP_B), fromA, fromB);

        assertThat(this.results).containsExactlyInAnyOrder("A identified " + HASH_B, "B identified " + HASH_A);
        assertThat(signatureKind(fromA)).contains(SecureIdent.IpKind.LOCAL_CLIENT.code());
        assertThat(signatureKind(fromB)).contains(SecureIdent.IpKind.LOCAL_CLIENT.code());
    }

    @Test
    void withoutOurPublicAddressPeersTakingVersionOneGetIt() throws DecodeException {
        List<Packet> fromA = new ArrayList<>();
        List<Packet> fromB = new ArrayList<>();

        // A verifier without a HighID may not know the address a signature would be bound to.
        this.run(3, 3, Optional.empty(), Optional.empty(), fromA, fromB);

        assertThat(this.results).containsExactlyInAnyOrder("A identified " + HASH_B, "B identified " + HASH_A);
        assertThat(signatureKind(fromA)).isEmpty();
        assertThat(signatureKind(fromB)).isEmpty();
    }

    @Test
    void aSignatureBoundToOurAddressIsCheckedWithTheLocalOneWithoutAPublicOne() throws DecodeException {
        List<Packet> fromA = new ArrayList<>();
        List<Packet> fromB = new ArrayList<>();

        // A takes version 2 only, and neither knows its public address: B binds to A's address as it sees it.
        this.run(2, 3, Optional.empty(), Optional.empty(), fromA, fromB);

        assertThat(signatureKind(fromB)).contains(SecureIdent.IpKind.REMOTE_CLIENT.code());
        assertThat(this.results).contains("A identified " + HASH_B);
    }

    @Test
    void aSignatureBoundToNoAddressIsCheckedAsSuch() throws DecodeException {
        List<Packet> toB = new ArrayList<>();
        IdentityHandshake atA = new IdentityHandshake(
                this.a,
                hello(HASH_B, 3),
                IP_B,
                Optional.empty(),
                Optional.of(IP_A),
                Optional.of(IP_A),
                toB::add,
                this.listener("A"));
        atA.start(RANDOM);
        PacketReader state = toB.getFirst().reader();
        state.u8();
        int challenge = (int) state.u32();
        byte[] key = this.b.publicKey();
        byte[] signature = SecureIdent.sign(
                this.b.key(),
                this.a.publicKey(),
                challenge,
                Optional.of(new SecureIdent.IpBinding(Inet4Address.ofLiteral("0.0.0.0"), SecureIdent.IpKind.NONE)));

        atA.received(Packet.of(
                Protocol.EMULE,
                Opcodes.Extended.PUBLICKEY.code(),
                new PacketWriter().u8(key.length).bytes(key)));
        atA.received(Packet.of(
                Protocol.EMULE,
                Opcodes.Extended.SIGNATURE.code(),
                new PacketWriter().u8(signature.length).bytes(signature).u8(SecureIdent.IpKind.NONE.code())));

        assertThat(this.results).containsExactly("A identified " + HASH_B);
    }

    // The address kind of the signature among {@code sent}, empty for a version 1 one.
    private static Optional<Integer> signatureKind(List<Packet> sent) throws DecodeException {
        for (Packet p : sent) {
            if (p.opcode() == Opcodes.Extended.SIGNATURE.code()) {
                PacketReader in = p.reader();
                in.skip(in.u8());
                return in.hasRemaining() ? Optional.of(in.u8()) : Optional.empty();
            }
        }
        throw new AssertionError("no signature");
    }

    // A and B on 10.0.0.1 and 10.0.0.2, both with HighIDs, delivering each other's packets.
    private void run(int versionsA, int versionsB, Optional<byte[]> aPinsB, Optional<byte[]> bPinsA)
            throws DecodeException {
        this.run(
                versionsA,
                versionsB,
                aPinsB,
                bPinsA,
                Optional.of(IP_A),
                Optional.of(IP_B),
                new ArrayList<>(),
                new ArrayList<>());
    }

    // A and B on local addresses 10.0.0.1 and 10.0.0.2, public as given, delivering each other's packets.
    private void run(
            int versionsA,
            int versionsB,
            Optional<Inet4Address> publicA,
            Optional<Inet4Address> publicB,
            List<Packet> fromA,
            List<Packet> fromB)
            throws DecodeException {
        this.run(versionsA, versionsB, Optional.empty(), Optional.empty(), publicA, publicB, fromA, fromB);
    }

    private void run(
            int versionsA,
            int versionsB,
            Optional<byte[]> aPinsB,
            Optional<byte[]> bPinsA,
            Optional<Inet4Address> publicA,
            Optional<Inet4Address> publicB,
            List<Packet> fromA,
            List<Packet> fromB)
            throws DecodeException {
        Deque<Packet> toA = new ArrayDeque<>();
        Deque<Packet> toB = new ArrayDeque<>();
        IdentityHandshake atA = new IdentityHandshake(
                this.a,
                hello(HASH_B, versionsB),
                IP_B,
                aPinsB,
                publicA,
                Optional.of(IP_A),
                p -> {
                    fromA.add(p);
                    toB.add(p);
                },
                this.listener("A"));
        IdentityHandshake atB = new IdentityHandshake(
                this.b,
                hello(HASH_A, versionsA),
                IP_A,
                bPinsA,
                publicB,
                Optional.of(IP_B),
                p -> {
                    fromB.add(p);
                    toA.add(p);
                },
                this.listener("B"));
        atA.start(RANDOM);
        atB.start(RANDOM);
        for (int round = 0; round < 10 && !(toA.isEmpty() && toB.isEmpty()); round++) {
            while (!toA.isEmpty()) {
                assertThat(atA.received(toA.poll())).isTrue();
            }
            while (!toB.isEmpty()) {
                assertThat(atB.received(toB.poll())).isTrue();
            }
        }
    }

    private IdentityHandshake.Listener listener(String side) {
        return new IdentityHandshake.Listener() {
            @Override
            public void identified(Hash16 userHash, byte[] publicKey) {
                IdentityHandshakeTest.this.results.add(side + " identified " + userHash);
            }

            @Override
            public void failed(Hash16 userHash) {
                IdentityHandshakeTest.this.results.add(side + " failed " + userHash);
            }
        };
    }

    private static Hello hello(Hash16 hash, int versions) {
        return Hello.of(hash, 0, 4662, "peer", 0, 0, versions << 16, 0, 0, 0);
    }
}
