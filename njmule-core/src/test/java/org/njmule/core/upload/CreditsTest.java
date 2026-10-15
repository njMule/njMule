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
package org.njmule.core.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Hash16;
import org.njmule.core.util.Bytes;

class CreditsTest {

    private static final long MIB = 1024 * 1024;
    private static final Hash16 PEER = Hash16.parseHex("11".repeat(16));
    private static final Hash16 OTHER = Hash16.parseHex("22".repeat(16));
    private static final long NOW = 1_000_000;

    @TempDir
    Path dir;

    private Credits credits;
    private double ratio;

    @Test
    void shouldGiveAClientThatGaveUsNothingARatioOfOne() {
        givenTheCreditsLoadedAt(NOW);

        whenTheRatioIsComputedFor(PEER, true);

        thenTheRatioIs(1.0);
    }

    @Test
    void shouldKeepTheRatioAtOneBelowOneMebibyteReceived() {
        givenTheCreditsLoadedAt(NOW);
        givenWeReceivedFrom(PEER, MIB / 2);

        whenTheRatioIsComputedFor(PEER, true);

        thenTheRatioIs(1.0, "below 1 MiB");
    }

    // Nothing uploaded and 8 MiB received: the linear ramp is the lowest term.
    @Test
    void shouldRaiseTheRatioAlongTheRampWithWhatAClientGaveUs() {
        givenTheCreditsLoadedAt(NOW);
        givenWeReceivedFrom(PEER, MIB / 2, 7 * MIB + MIB / 2);

        whenTheRatioIsComputedFor(PEER, true);

        thenTheRatioIsCloseTo((8.0 * MIB - MIB) / (9_646_899 - MIB) * 2.34 + 1);
    }

    @Test
    void shouldGiveARatioOfOneToAClientWhoseIdentityIsInDoubt() {
        givenTheCreditsLoadedAt(NOW);
        givenWeReceivedFrom(PEER, MIB / 2, 7 * MIB + MIB / 2);

        whenTheRatioIsComputedFor(PEER, false);

        thenTheRatioIs(1.0, "identity in doubt");
    }

    // 100 MiB received: sqrt(100 + 2) caps the ratio of 10.
    @Test
    void shouldCapTheRatioAtTen() {
        givenTheCreditsLoadedAt(NOW);
        givenWeReceivedFrom(PEER, MIB / 2, 7 * MIB + MIB / 2, 92 * MIB);

        whenTheRatioIsComputedFor(PEER, true);

        thenTheRatioIs(10.0);
    }

    @Test
    void shouldLowerTheRatioWithWhatWeSentTheClient() {
        givenTheCreditsLoadedAt(NOW);
        givenWeReceivedFrom(PEER, MIB / 2, 7 * MIB + MIB / 2, 92 * MIB);
        givenWeSentTo(PEER, 50 * MIB);

        whenTheRatioIsComputedFor(PEER, true);

        thenTheRatioIs(4.0);
    }

    @Test
    void shouldBringTheRatioBackToOneWhenWeSentTheClientFarMore() {
        givenTheCreditsLoadedAt(NOW);
        givenWeReceivedFrom(PEER, MIB / 2, 7 * MIB + MIB / 2, 92 * MIB);
        givenWeSentTo(PEER, 50 * MIB, 1000 * MIB);

        whenTheRatioIsComputedFor(PEER, true);

        thenTheRatioIs(1.0);
    }

    // Pinning the first key wipes what the hash gathered before.
    @Test
    void shouldWipeWhatAClientGaveBeforeItsFirstKeyIsPinned() {
        givenTheCreditsLoadedAt(NOW);
        givenWeReceivedFrom(PEER, 5 * MIB);
        givenWeSentTo(PEER, MIB);

        whenTheClientIsIdentifiedBy(PEER, new byte[] {1, 2, 3});

        thenWeHaveReceivedFrom(PEER, 1);
    }

    @Test
    void shouldKeepCreditsAndTheFirstKeyAcrossARestart() {
        givenTheCreditsLoadedAt(NOW);
        givenWeReceivedFrom(PEER, 5 * MIB);
        givenWeSentTo(PEER, MIB);
        givenTheClientIdentifiedBy(PEER, new byte[] {1, 2, 3});
        givenTheClientIdentifiedBy(PEER, new byte[] {9});
        givenWeReceivedFrom(PEER, 5 * MIB - 1);
        givenWeSentTo(PEER, MIB - 1);
        givenWeReceivedFrom(OTHER, 0);
        givenTheCreditsSaved();

        whenTheCreditsAreLoadedAt(NOW + 3600);

        thenWeHaveReceivedFrom(PEER, 5 * MIB);
        thenWeHaveSentTo(PEER, MIB);
        thenTheKeyOfIs(PEER, new byte[] {1, 2, 3});
        thenNothingIsKnownOf(OTHER);
    }

    @Test
    void shouldForgetClientsNotSeenForTooLong() {
        givenTheCreditsLoadedAt(NOW);
        givenWeReceivedFrom(PEER, 5 * MIB);
        givenWeSentTo(PEER, MIB);
        givenTheClientIdentifiedBy(PEER, new byte[] {1, 2, 3});
        givenTheClientIdentifiedBy(PEER, new byte[] {9});
        givenWeReceivedFrom(PEER, 5 * MIB - 1);
        givenWeSentTo(PEER, MIB - 1);
        givenWeReceivedFrom(OTHER, 0);
        givenTheCreditsSaved();

        whenTheCreditsAreLoadedAt(NOW + Credits.EXPIRY.toSeconds() + 1);

        thenNothingIsKnownOf(PEER);
    }

    private void givenTheCreditsLoadedAt(long epochSecond) {
        this.credits = Credits.load(this.dir, clock(epochSecond));
    }

    private void givenWeReceivedFrom(Hash16 client, long... amounts) {
        for (long bytes : amounts) {
            this.credits.downloaded(client, bytes);
        }
    }

    private void givenWeSentTo(Hash16 client, long... amounts) {
        for (long bytes : amounts) {
            this.credits.uploaded(client, bytes);
        }
    }

    private void givenTheClientIdentifiedBy(Hash16 client, byte[] key) {
        this.credits.identified(client, Bytes.of(key));
    }

    private void givenTheCreditsSaved() {
        this.credits.save();
    }

    private void whenTheRatioIsComputedFor(Hash16 client, boolean trusted) {
        this.ratio = this.credits.ratio(client, trusted);
    }

    private void whenTheClientIsIdentifiedBy(Hash16 client, byte[] key) {
        this.credits.identified(client, Bytes.of(key));
    }

    private void whenTheCreditsAreLoadedAt(long epochSecond) {
        this.credits = Credits.load(this.dir, clock(epochSecond));
    }

    private void thenTheRatioIs(double expected) {
        assertThat(this.ratio).isEqualTo(expected);
    }

    private void thenTheRatioIs(double expected, String description) {
        assertThat(this.ratio).as(description).isEqualTo(expected);
    }

    private void thenTheRatioIsCloseTo(double expected) {
        assertThat(this.ratio).isCloseTo(expected, within(1e-9));
    }

    private void thenWeHaveReceivedFrom(Hash16 client, long expected) {
        assertThat(this.credits.get(client).orElseThrow().downloaded()).isEqualTo(expected);
    }

    private void thenWeHaveSentTo(Hash16 client, long expected) {
        assertThat(this.credits.get(client).orElseThrow().uploaded()).isEqualTo(expected);
    }

    private void thenTheKeyOfIs(Hash16 client, byte[] expected) {
        assertThat(this.credits.get(client).orElseThrow().publicKey()).isEqualTo(Bytes.of(expected));
    }

    private void thenNothingIsKnownOf(Hash16 client) {
        assertThat(this.credits.get(client)).isEmpty();
    }

    private static Clock clock(long epochSecond) {
        return Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC);
    }
}
