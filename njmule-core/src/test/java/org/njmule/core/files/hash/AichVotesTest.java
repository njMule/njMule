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
package org.njmule.core.files.hash;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.Hash20;
import org.njmule.core.util.Ipv4;

class AichVotesTest {

    private static final Hash20 GOOD = hash(1);
    private static final Hash20 BAD = hash(2);

    private AichVotes votes;
    private Optional<Hash20> announcement;

    @Test
    void shouldTrustAHashOnceTenNetworksAgree() {
        givenNoVotes();
        givenNetworksAnnounceWithoutMakingItTrusted(10, 9, GOOD);

        whenAHostAnnounces(Ipv4.of(30, 1, 1, 1), GOOD);

        thenTheAnnouncementMakesTrusted(GOOD);
    }

    @Test
    void shouldCountOneNetworkOnceAndForItsFirstHash() {
        givenNoVotes();
        givenHostsOfOneNetworkAnnounce(20, GOOD);

        whenAHostAnnounces(Ipv4.of(10, 1, 2, 1), BAD);

        thenNoHashIsTrusted("all from one /20");
    }

    @Test
    void shouldKeepTheTrustWhileOtherHashesAreAMinority() {
        givenNoVotes();
        givenNetworksAnnounce(40, 12, GOOD);

        whenAHostAnnounces(Ipv4.of(90, 1, 1, 1), BAD);

        thenTheTrustedHashIs(GOOD, "12 of 13 is 92%");
    }

    @Test
    void shouldDropTheTrustWhenOtherHashesAreTooMany() {
        givenNoVotes();
        givenNetworksAnnounce(40, 12, GOOD);
        givenAHostAnnounces(Ipv4.of(90, 1, 1, 1), BAD);

        whenAHostAnnounces(Ipv4.of(91, 1, 1, 1), BAD);

        thenNoHashIsTrusted("12 of 14 is too few");
    }

    private void givenNoVotes() {
        this.votes = new AichVotes();
    }

    private void givenNetworksAnnounceWithoutMakingItTrusted(int firstNetwork, int networks, Hash20 hash) {
        for (int i = 0; i < networks; i++) {
            assertThat(this.votes.announced(Ipv4.of(firstNetwork + i, 1, 1, 1), hash))
                    .isEmpty();
        }
    }

    private void givenNetworksAnnounce(int firstNetwork, int networks, Hash20 hash) {
        for (int i = 0; i < networks; i++) {
            this.votes.announced(Ipv4.of(firstNetwork + i, 1, 1, 1), hash);
        }
    }

    private void givenHostsOfOneNetworkAnnounce(int hosts, Hash20 hash) {
        for (int i = 0; i < hosts; i++) {
            this.votes.announced(Ipv4.of(10, 1, 1, i), hash);
        }
    }

    private void givenAHostAnnounces(int ip, Hash20 hash) {
        this.votes.announced(ip, hash);
    }

    private void whenAHostAnnounces(int ip, Hash20 hash) {
        this.announcement = this.votes.announced(ip, hash);
    }

    private void thenTheAnnouncementMakesTrusted(Hash20 hash) {
        assertThat(this.announcement).contains(hash);
    }

    private void thenTheTrustedHashIs(Hash20 hash, String why) {
        assertThat(this.votes.trusted()).as(why).contains(hash);
    }

    private void thenNoHashIsTrusted(String why) {
        assertThat(this.votes.trusted()).as(why).isEmpty();
    }

    private static Hash20 hash(int seed) {
        byte[] b = new byte[Hash20.LENGTH];
        b[0] = (byte) seed;
        return Hash20.of(b);
    }
}
