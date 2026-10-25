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
package org.njmule.core.net;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The counters are shared by the whole process, so the test looks at what they grew by. */
class TrafficTest {

    private long overheadBefore;
    private long receivedBefore;

    @Test
    void shouldCountAsOverheadTheBytesSentBesidesFileData() {
        givenTheCountersRead();

        whenSent(100, false);

        thenTheOverheadSentGrewBy(100);
    }

    @Test
    void shouldLeaveFileDataOutOfTheOverheadSent() {
        givenTheCountersRead();

        whenSent(10_240, true);

        thenTheOverheadSentGrewByLessThan(10_240);
    }

    @Test
    void shouldCountAllTheBytesReceived() {
        givenTheCountersRead();

        whenReceived(1_500);

        thenTheReceivedBytesGrewBy(1_500);
    }

    private void givenTheCountersRead() {
        this.overheadBefore = Traffic.overheadSent();
        this.receivedBefore = Traffic.received();
    }

    private void whenSent(int bytes, boolean fileData) {
        Traffic.sent(bytes, fileData);
    }

    private void whenReceived(int bytes) {
        Traffic.received(bytes);
    }

    private void thenTheOverheadSentGrewBy(long bytes) {
        assertThat(Traffic.overheadSent() - this.overheadBefore).isGreaterThanOrEqualTo(bytes);
    }

    private void thenTheOverheadSentGrewByLessThan(long bytes) {
        assertThat(Traffic.overheadSent() - this.overheadBefore).isLessThan(bytes);
    }

    private void thenTheReceivedBytesGrewBy(long bytes) {
        assertThat(Traffic.received() - this.receivedBefore).isGreaterThanOrEqualTo(bytes);
    }
}
