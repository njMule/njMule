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

import java.net.InetAddress;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.Hash16;

class RequestTrackerTest {

    private static final Duration MIN_ASK = Duration.ofMinutes(10);
    private static final Duration WINDOW = Duration.ofHours(4);
    private static final Hash16 FILE = Hash16.parseHex("11".repeat(16));
    private static final RequestTracker.Key CLIENT =
            new RequestTracker.Key(InetAddress.getLoopbackAddress(), Hash16.parseHex("22".repeat(16)));

    private final RequestTracker tracker = new RequestTracker(MIN_ASK, WINDOW);
    private long now = 1_000_000_000L;
    private int count;

    @Test
    void aReAskJustShortOfTheShortestTimeIsNotCounted() {
        this.givenTheClientAskedForTheFile();

        this.whenItAsksAgainAfter(MIN_ASK.minusSeconds(5));

        this.thenTheCountIs(0);
    }

    @Test
    void idleSlotsOutsideTheWindowNoLongerCount() {
        this.givenIdleSlots(3);

        this.whenItLeavesASlotIdleAfter(WINDOW);

        this.thenTheCountIs(1);
    }

    @Test
    void aClientNotHeardOfWithinTheWindowIsForgotten() {
        this.givenIdleSlots(2);

        this.whenTheTrackerIsPurgedAfter(WINDOW);

        this.thenNoClientIsKept();
    }

    private void givenTheClientAskedForTheFile() {
        this.tracker.asked(CLIENT, FILE, this.now);
    }

    private void givenIdleSlots(int slots) {
        for (int i = 0; i < slots; i++) {
            this.now += Duration.ofMinutes(1).toNanos();
            this.tracker.idleSlot(CLIENT, this.now);
        }
    }

    private void whenItAsksAgainAfter(Duration wait) {
        this.now += wait.toNanos();
        this.count = this.tracker.asked(CLIENT, FILE, this.now);
    }

    private void whenItLeavesASlotIdleAfter(Duration wait) {
        this.now += wait.toNanos();
        this.count = this.tracker.idleSlot(CLIENT, this.now);
    }

    private void whenTheTrackerIsPurgedAfter(Duration wait) {
        this.now += wait.toNanos();
        this.tracker.purge(this.now);
    }

    private void thenTheCountIs(int expected) {
        assertThat(this.count).isEqualTo(expected);
    }

    private void thenNoClientIsKept() {
        assertThat(this.tracker.size()).isZero();
    }
}
