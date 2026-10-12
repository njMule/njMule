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
package org.njmule.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.njmule.core.persist.format.IpFilterDat;
import org.njmule.core.persist.format.IpFilterDat.Range;
import org.njmule.core.util.Ipv4;

class IpFilterTest {

    @Test
    void blocksOnlyRangesBelowTheLevel() {
        IpFilter filter =
                IpFilter.of(
                        List.of(
                                range("10.0.0.0", "10.255.255.255", 0, "private"),
                                range("20.0.0.0", "20.0.0.255", 127, "allowed"),
                                range("200.1.2.0", "200.1.2.255", 100, "high")),
                        IpFilter.DEFAULT_LEVEL);

        assertThat(filter.blocks(Ipv4.parse("10.1.2.3"))).isTrue();
        assertThat(filter.match(Ipv4.parse("10.255.255.255"))).contains("private");
        assertThat(filter.blocks(Ipv4.parse("11.0.0.0"))).isFalse();
        assertThat(filter.blocks(Ipv4.parse("20.0.0.1"))).isFalse();
        assertThat(filter.blocks(Ipv4.parse("200.1.2.0"))).isTrue();
        assertThat(filter.blocks(Ipv4.parse("200.1.3.0"))).isFalse();
        assertThat(filter.rangeCount()).isEqualTo(2);
    }

    @Test
    void laterRangesWinWhereTheyOverlap() {
        IpFilter filter =
                IpFilter.of(
                        List.of(
                                range("1.0.0.10", "1.0.0.20", 0, "b"),
                                range("1.0.0.0", "1.0.0.15", 0, "a"),
                                range("1.0.0.21", "1.0.0.30", 50, "c"),
                                range("1.0.0.40", "1.0.0.50", 0, "d")),
                        IpFilter.DEFAULT_LEVEL);

        assertThat(filter.rangeCount()).isEqualTo(4);
        assertThat(filter.match(Ipv4.parse("1.0.0.12"))).contains("a");
        assertThat(filter.match(Ipv4.parse("1.0.0.17"))).contains("b");
        assertThat(filter.match(Ipv4.parse("1.0.0.30"))).contains("c");
        assertThat(filter.blocks(Ipv4.parse("1.0.0.35"))).isFalse();
        assertThat(filter.match(Ipv4.parse("1.0.0.45"))).contains("d");
    }

    @Test
    void neverBlocksTheZeroAddressNorAnEmptyList() {
        IpFilter all = IpFilter.of(List.of(range("0.0.0.0", "255.255.255.255", 0, "all")), 127);

        assertThat(all.blocks(0)).isFalse();
        assertThat(all.blocks(Ipv4.parse("255.255.255.255"))).isTrue();
        assertThat(IpFilter.empty().blocks(Ipv4.parse("1.2.3.4"))).isFalse();
    }

    @Test
    void readsAnIpFilterDatFile() {
        byte[] dat =
                ("# comment\n"
                                + "001.002.003.000 - 001.002.003.255 , 000 , test range\n"
                                + "009.009.009.009 - 009.009.009.009 , 200 , harmless\n")
                        .getBytes(StandardCharsets.US_ASCII);

        IpFilter filter = IpFilter.of(IpFilterDat.read(dat, "ipfilter.dat"), 127);

        assertThat(filter.match(Ipv4.parse("1.2.3.4"))).contains("test range");
        assertThat(filter.blocks(Ipv4.parse("9.9.9.9"))).isFalse();
    }

    private static Range range(String start, String end, int level, String description) {
        return new Range(Ipv4.parse(start), Ipv4.parse(end), level, description);
    }
}
