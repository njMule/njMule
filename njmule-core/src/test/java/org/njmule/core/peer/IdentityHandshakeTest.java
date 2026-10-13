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
import org.njmule.core.protocol.packet.Packet;

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
                this.a, hello(HASH_B, 0), IP_B, Optional.empty(), Optional.empty(), sent::add, this.listener("A"));
        handshake.start(RANDOM);

        assertThat(handshake.isSupported()).isFalse();
        assertThat(sent).isEmpty();
    }

    // A and B on 10.0.0.1 and 10.0.0.2, both with HighIDs, delivering each other's packets.
    private void run(int versionsA, int versionsB, Optional<byte[]> aPinsB, Optional<byte[]> bPinsA)
            throws DecodeException {
        Deque<Packet> toA = new ArrayDeque<>();
        Deque<Packet> toB = new ArrayDeque<>();
        IdentityHandshake atA = new IdentityHandshake(
                this.a, hello(HASH_B, versionsB), IP_B, aPinsB, Optional.of(IP_A), toB::add, this.listener("A"));
        IdentityHandshake atB = new IdentityHandshake(
                this.b, hello(HASH_A, versionsA), IP_A, bPinsA, Optional.of(IP_B), toA::add, this.listener("B"));
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
