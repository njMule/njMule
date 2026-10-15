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
package org.njmule.core.download;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.collect.Range;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.util.Ipv4;

class CorruptionBlackBoxTest {

    private static final int A = Ipv4.of(192, 0, 2, 1);
    private static final int B = Ipv4.of(192, 0, 2, 2);
    private static final long PART = Ed2k.PARTSIZE;
    private static final long BLOCK = Ed2k.EMBLOCKSIZE;

    private final CorruptionBlackBox box = new CorruptionBlackBox();
    private Set<Integer> guilty;

    @Test
    void aSourceThatAloneSentOnePartThatFailedIsForgiven() {
        givenSent(A, 0, PART);

        whenThePartFails(0, PART);

        thenTheGuiltyAre();
    }

    @Test
    void aSourceThatAloneSentAPartThatFailedThreeTimesIsGuilty() {
        givenSentAPartThatFailed(A, 0);
        givenSentAPartThatFailed(A, 0);
        givenSent(A, 0, PART);

        whenThePartFails(0, PART);

        thenTheGuiltyAre(A);
    }

    @Test
    void aSourceWhoseOtherPartsPassedIsNotGuiltyOfThreeThatFailed() {
        givenSentPartsThatPassed(A, 0, 7);
        givenSentAPartThatFailed(A, 7);
        givenSentAPartThatFailed(A, 8);
        givenSent(A, 9 * PART, 10 * PART);

        whenThePartFails(9 * PART, 10 * PART);

        thenTheGuiltyAre();
    }

    @Test
    void aPartThatFailedBlamesNoneOfTheSeveralSourcesThatSentIt() {
        givenSent(A, 0, PART / 2);
        givenSent(B, PART / 2, PART);

        whenThePartFails(0, PART);

        thenTheGuiltyAre();
    }

    @Test
    void aPartOfWhichSomeDataCameFromNoKnownSourceBlamesNone() {
        givenSent(A, BLOCK, PART);

        whenThePartFails(0, PART);

        thenTheGuiltyAre();
    }

    @Test
    void recoveryDataBlamesTheSourceOfTheBadBlockOnly() {
        givenSent(A, 0, 2 * BLOCK);
        givenSent(B, 2 * BLOCK, 3 * BLOCK);
        givenThePartFailed(0, 3 * BLOCK);

        whenRecoveryDataProves(
                List.of(Range.closedOpen(0L, BLOCK), Range.closedOpen(BLOCK, 2 * BLOCK)),
                List.of(Range.closedOpen(2 * BLOCK, 3 * BLOCK)));

        thenTheGuiltyAre(B);
    }

    private void givenSent(int ip, long start, long end) {
        this.box.received(start, end, ip);
    }

    private void givenSentPartsThatPassed(int ip, int first, int count) {
        for (int part = first; part < first + count; part++) {
            this.box.received(part * PART, (part + 1) * PART, ip);
            this.box.passed(part * PART, (part + 1) * PART);
        }
    }

    private void givenSentAPartThatFailed(int ip, int part) {
        givenSent(ip, part * PART, (part + 1) * PART);
        givenThePartFailed(part * PART, (part + 1) * PART);
    }

    private void givenThePartFailed(long start, long end) {
        whenThePartFails(start, end);
        thenTheGuiltyAre();
    }

    private void whenThePartFails(long start, long end) {
        this.guilty = this.box.failed(start, end);
    }

    private void whenRecoveryDataProves(List<Range<Long>> good, List<Range<Long>> bad) {
        this.guilty = this.box.recovered(good, bad);
    }

    private void thenTheGuiltyAre(Integer... ips) {
        assertThat(this.guilty).containsExactlyInAnyOrder(ips);
    }
}
