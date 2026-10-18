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
import com.google.common.net.InetAddresses;
import java.net.InetAddress;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.njmule.core.files.hash.Ed2k;

class CorruptionBlackBoxTest {

    private static final InetAddress A = InetAddresses.forString("192.0.2.1");
    private static final InetAddress B = InetAddresses.forString("192.0.2.2");
    private static final InetAddress HOME = InetAddresses.forString("2001:db8:1:2::1");
    private static final InetAddress SAME_HOME = InetAddresses.forString("2001:db8:1:2::99");
    private static final long PART = Ed2k.PARTSIZE;
    private static final long BLOCK = Ed2k.EMBLOCKSIZE;

    private final CorruptionBlackBox box = new CorruptionBlackBox();
    private Set<InetAddress> guilty;

    @Test
    void shouldTakeTheAddressesOfAnIpv6Slash64AsOneSource() {
        givenSentAPartThatFailed(HOME, 0);
        givenSentAPartThatFailed(SAME_HOME, 0);
        givenSent(HOME, 0, PART / 2);
        givenSent(SAME_HOME, PART / 2, PART);

        whenThePartFails(0, PART);

        thenTheGuiltyAre(InetAddresses.forString("2001:db8:1:2::"));
    }

    @Test
    void shouldForgiveASourceThatAloneSentOnePartThatFailed() {
        givenSent(A, 0, PART);

        whenThePartFails(0, PART);

        thenTheGuiltyAre();
    }

    @Test
    void shouldBlameASourceThatAloneSentAPartThatFailedThreeTimes() {
        givenSentAPartThatFailed(A, 0);
        givenSentAPartThatFailed(A, 0);
        givenSent(A, 0, PART);

        whenThePartFails(0, PART);

        thenTheGuiltyAre(A);
    }

    @Test
    void shouldNotBlameForThreeFailuresASourceWhoseOtherPartsPassed() {
        givenSentPartsThatPassed(A, 0, 7);
        givenSentAPartThatFailed(A, 7);
        givenSentAPartThatFailed(A, 8);
        givenSent(A, 9 * PART, 10 * PART);

        whenThePartFails(9 * PART, 10 * PART);

        thenTheGuiltyAre();
    }

    @Test
    void shouldBlameNoneOfTheSeveralSourcesThatSentAFailedPart() {
        givenSent(A, 0, PART / 2);
        givenSent(B, PART / 2, PART);

        whenThePartFails(0, PART);

        thenTheGuiltyAre();
    }

    @Test
    void shouldBlameNoSourceForAPartWithDataFromNoKnownSource() {
        givenSent(A, BLOCK, PART);

        whenThePartFails(0, PART);

        thenTheGuiltyAre();
    }

    @Test
    void shouldBlameOnlyTheSourceOfTheBadBlockThroughRecoveryData() {
        givenSent(A, 0, 2 * BLOCK);
        givenSent(B, 2 * BLOCK, 3 * BLOCK);
        givenThePartFailed(0, 3 * BLOCK);

        whenRecoveryDataProves(
                List.of(Range.closedOpen(0L, BLOCK), Range.closedOpen(BLOCK, 2 * BLOCK)),
                List.of(Range.closedOpen(2 * BLOCK, 3 * BLOCK)));

        thenTheGuiltyAre(B);
    }

    private void givenSent(InetAddress ip, long start, long end) {
        this.box.received(start, end, ip);
    }

    private void givenSentPartsThatPassed(InetAddress ip, int first, int count) {
        for (int part = first; part < first + count; part++) {
            this.box.received(part * PART, (part + 1) * PART, ip);
            this.box.passed(part * PART, (part + 1) * PART);
        }
    }

    private void givenSentAPartThatFailed(InetAddress ip, int part) {
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

    private void thenTheGuiltyAre(InetAddress... ips) {
        assertThat(this.guilty).containsExactlyInAnyOrder(ips);
    }
}
