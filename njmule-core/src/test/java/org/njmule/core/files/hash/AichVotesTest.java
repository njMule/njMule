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

import org.junit.jupiter.api.Test;
import org.njmule.core.api.Hash20;
import org.njmule.core.util.Ipv4;

class AichVotesTest {

    private static final Hash20 GOOD = hash(1);
    private static final Hash20 BAD = hash(2);

    @Test
    void tenNetworksAgreeingMakeAHashTrusted() {
        AichVotes votes = new AichVotes();
        for (int i = 0; i < 9; i++) {
            assertThat(votes.announced(Ipv4.of(10 + i, 1, 1, 1), GOOD)).isEmpty();
        }
        assertThat(votes.announced(Ipv4.of(30, 1, 1, 1), GOOD)).contains(GOOD);
    }

    @Test
    void oneNetworkCountsOnceAndForItsFirstHash() {
        AichVotes votes = new AichVotes();
        for (int i = 0; i < 20; i++) {
            votes.announced(Ipv4.of(10, 1, 1, i), GOOD);
        }
        votes.announced(Ipv4.of(10, 1, 2, 1), BAD);
        assertThat(votes.trusted()).as("all from one /20").isEmpty();
    }

    @Test
    void aMinorityOfOtherHashesKeepsTheTrust() {
        AichVotes votes = new AichVotes();
        for (int i = 0; i < 12; i++) {
            votes.announced(Ipv4.of(40 + i, 1, 1, 1), GOOD);
        }
        votes.announced(Ipv4.of(90, 1, 1, 1), BAD);
        assertThat(votes.trusted()).as("12 of 13 is 92%").contains(GOOD);
        votes.announced(Ipv4.of(91, 1, 1, 1), BAD);
        assertThat(votes.trusted()).as("12 of 14 is too few").isEmpty();
    }

    private static Hash20 hash(int seed) {
        byte[] b = new byte[Hash20.LENGTH];
        b[0] = (byte) seed;
        return Hash20.of(b);
    }
}
