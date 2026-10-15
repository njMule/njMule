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
package org.njmule.core.persist.format;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.njmule.core.persist.format.IpFilterDat.Range;
import org.njmule.core.security.IpFilter;
import org.njmule.core.util.Ipv4;

class IpFilterLevelsTest {

    private String file;
    private List<Range> ranges;

    @Test
    void aBlockedRangeInsideAnAllowedOneStaysBlocked() {
        givenAFilterList("""
                10.0.0.0 - 10.0.0.255 , 200 , allowed
                10.0.0.100 - 10.0.1.255 , 000 , blocked
                """);

        whenTheListIsRead();

        thenTheFilterBlocks("10.0.0.150", "10.0.1.10");
        thenTheFilterLetsThrough("10.0.0.50");
    }

    @Test
    void aBlockedRangeDoesNotGrowOverAnAllowedOne() {
        givenAFilterList("""
                10.0.0.0 - 10.0.0.255 , 000 , blocked
                10.0.0.200 - 10.0.3.255 , 200 , allowed
                """);

        whenTheListIsRead();

        thenTheFilterBlocks("10.0.0.10", "10.0.0.220");
        thenTheFilterLetsThrough("10.0.2.1");
    }

    @Test
    void overlappingRangesOfOneLevelBecomeOne() {
        givenAFilterList("""
                10.0.0.100 - 10.0.1.255 , 000 , second
                10.0.0.0 - 10.0.0.255 , 000 , first
                10.0.0.50 - 10.0.0.60 , 200 , apart
                """);

        whenTheListIsRead();

        thenTheRangesAre(
                new Range(Ipv4.parse("10.0.0.0"), Ipv4.parse("10.0.1.255"), 0, "first"),
                new Range(Ipv4.parse("10.0.0.50"), Ipv4.parse("10.0.0.60"), 200, "apart"));
    }

    private void givenAFilterList(String text) {
        this.file = text;
    }

    private void whenTheListIsRead() {
        this.ranges = IpFilterDat.read(this.file.getBytes(StandardCharsets.US_ASCII), "ipfilter.dat");
    }

    private void thenTheFilterBlocks(String... addresses) {
        IpFilter filter = IpFilter.of(this.ranges, IpFilter.DEFAULT_LEVEL);
        for (String address : addresses) {
            assertThat(filter.blocks(Ipv4.parse(address))).as(address).isTrue();
        }
    }

    private void thenTheFilterLetsThrough(String address) {
        assertThat(IpFilter.of(this.ranges, IpFilter.DEFAULT_LEVEL).blocks(Ipv4.parse(address)))
                .as(address)
                .isFalse();
    }

    private void thenTheRangesAre(Range... expected) {
        assertThat(this.ranges).containsExactly(expected);
    }
}
