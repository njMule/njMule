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

    @TempDir
    Path dir;

    @Test
    void theRatioGrowsWithWhatAClientGaveUs() {
        Credits credits = Credits.load(this.dir, clock(1_000_000));
        assertThat(credits.ratio(PEER, true)).isEqualTo(1.0);

        credits.downloaded(PEER, MIB / 2);
        assertThat(credits.ratio(PEER, true)).as("below 1 MiB").isEqualTo(1.0);

        credits.downloaded(PEER, 7 * MIB + MIB / 2);
        // Nothing uploaded and 8 MiB received: the linear ramp is the lowest term.
        double ramp = (8.0 * MIB - MIB) / (9_646_899 - MIB) * 2.34 + 1;
        assertThat(credits.ratio(PEER, true)).isCloseTo(ramp, within(1e-9));
        assertThat(credits.ratio(PEER, false)).as("identity in doubt").isEqualTo(1.0);

        credits.downloaded(PEER, 92 * MIB);
        // 100 MiB received: sqrt(100 + 2) caps the ratio of 10.
        assertThat(credits.ratio(PEER, true)).isEqualTo(10.0);
        credits.uploaded(PEER, 50 * MIB);
        assertThat(credits.ratio(PEER, true)).isEqualTo(4.0);
        credits.uploaded(PEER, 1000 * MIB);
        assertThat(credits.ratio(PEER, true)).isEqualTo(1.0);
    }

    @Test
    void keepsCreditsAndKeysAcrossRestartsAndForgetsOldClients() {
        Credits first = Credits.load(this.dir, clock(1_000_000));
        first.downloaded(PEER, 5 * MIB);
        first.uploaded(PEER, MIB);
        first.identified(PEER, Bytes.of(new byte[] {1, 2, 3}));
        first.identified(PEER, Bytes.of(new byte[] {9}));
        // Pinning the first key wipes what the hash gathered before.
        assertThat(first.get(PEER).orElseThrow().downloaded()).isEqualTo(1);
        first.downloaded(PEER, 5 * MIB - 1);
        first.uploaded(PEER, MIB - 1);
        first.downloaded(OTHER, 0);
        first.save();

        Credits second = Credits.load(this.dir, clock(1_000_000 + 3600));
        Credits.Credit credit = second.get(PEER).orElseThrow();
        assertThat(credit.downloaded()).isEqualTo(5 * MIB);
        assertThat(credit.uploaded()).isEqualTo(MIB);
        assertThat(credit.publicKey()).isEqualTo(Bytes.of(new byte[] {1, 2, 3}));
        assertThat(second.get(OTHER)).isEmpty();

        long later = 1_000_000 + Credits.EXPIRY.toSeconds() + 1;
        assertThat(Credits.load(this.dir, clock(later)).get(PEER)).isEmpty();
    }

    private static Clock clock(long epochSecond) {
        return Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC);
    }
}
