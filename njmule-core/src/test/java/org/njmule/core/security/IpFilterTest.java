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
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;
import org.njmule.core.persist.format.IpFilterDat;
import org.njmule.core.persist.format.IpFilterDat.Range;
import org.njmule.core.util.Ipv4;

class IpFilterTest {

    // Addresses 0.0.0.1 to 0.0.0.200, few enough to check every one.
    private static final int LAST_SMALL_ADDRESS = 200;

    private List<Range> ranges;
    private byte[] dat;
    private IpFilter filter;

    @Test
    void shouldBlockOnlyRangesBelowTheLevel() {
        givenTheRanges(
                range("10.0.0.0", "10.255.255.255", 0, "private"),
                range("20.0.0.0", "20.0.0.255", 127, "allowed"),
                range("200.1.2.0", "200.1.2.255", 100, "high"));

        whenTheFilterIsMadeAtTheLevel(IpFilter.DEFAULT_LEVEL);

        thenItBlocks("10.1.2.3");
        thenItMatches("10.255.255.255", "private");
        thenItLetsThrough("11.0.0.0");
        thenItLetsThrough("20.0.0.1");
        thenItBlocks("200.1.2.0");
        thenItLetsThrough("200.1.3.0");
        thenItHoldsRanges(2);
    }

    @Test
    void shouldLetLaterRangesWinWhereTheyOverlap() {
        givenTheRanges(
                range("1.0.0.10", "1.0.0.20", 0, "b"),
                range("1.0.0.0", "1.0.0.15", 0, "a"),
                range("1.0.0.21", "1.0.0.30", 50, "c"),
                range("1.0.0.40", "1.0.0.50", 0, "d"));

        whenTheFilterIsMadeAtTheLevel(IpFilter.DEFAULT_LEVEL);

        thenItHoldsRanges(4);
        thenItMatches("1.0.0.12", "a");
        thenItMatches("1.0.0.17", "b");
        thenItMatches("1.0.0.30", "c");
        thenItLetsThrough("1.0.0.35");
        thenItMatches("1.0.0.45", "d");
    }

    @Property
    void shouldGiveEveryAddressTheLastListedBlockingRangeThatCoversIt(@ForAll("smallRanges") List<Range> ranges) {
        givenTheRanges(ranges);

        whenTheFilterIsMadeAtTheLevel(IpFilter.DEFAULT_LEVEL);

        thenEveryAddressMatchesTheLastBlockingRangeCoveringIt();
        thenItHoldsOneRangePerRunOfAddressesWonByTheSameRange();
    }

    @Test
    void shouldNeverBlockTheZeroAddress() {
        givenTheRanges(range("0.0.0.0", "255.255.255.255", 0, "all"));

        whenTheFilterIsMadeAtTheLevel(127);

        thenItLetsThrough("0.0.0.0");
        thenItBlocks("255.255.255.255");
    }

    @Test
    void shouldNeverBlockWithAnEmptyList() {
        whenTheFilterIsEmpty();

        thenItLetsThrough("1.2.3.4");
    }

    @Test
    void shouldHoldTheEndsOfRangesAcrossTheSignBitAndUpToTheLastAddress() {
        givenTheRanges(
                range("9.9.9.9", "9.9.9.9", 0, "one"),
                range("127.255.255.250", "128.0.0.5", 0, "across"),
                range("255.255.255.0", "255.255.255.255", 0, "top"),
                range("50.0.0.0", "50.0.0.9", 127, "at the level"),
                range("60.0.0.0", "60.0.0.9", 0, "left"),
                range("60.0.0.10", "60.0.0.19", 0, "right"));

        whenTheFilterIsMadeAtTheLevel(127);

        thenItMatches("9.9.9.9", "one");
        thenItLetsThrough("9.9.9.8");
        thenItLetsThrough("9.9.9.10");
        thenItMatches("127.255.255.255", "across");
        thenItMatches("128.0.0.0", "across");
        thenItLetsThrough("128.0.0.6");
        thenItMatches("255.255.255.255", "top");
        thenItLetsThrough("50.0.0.5", "a level equal to the filter's lets through");
        thenItMatches("60.0.0.9", "left");
        thenItMatches("60.0.0.10", "right");
        thenItHoldsRanges(5);
    }

    @Test
    void shouldReadAnIpFilterDatFile() {
        givenTheIpFilterDat("""
                # comment
                001.002.003.000 - 001.002.003.255 , 000 , test range
                009.009.009.009 - 009.009.009.009 , 200 , harmless
                """);

        whenTheFilterIsReadAtTheLevel(127);

        thenItMatches("1.2.3.4", "test range");
        thenItLetsThrough("9.9.9.9");
    }

    private void givenTheRanges(Range... list) {
        this.ranges = List.of(list);
    }

    private void givenTheRanges(List<Range> list) {
        this.ranges = list;
    }

    private void givenTheIpFilterDat(String text) {
        this.dat = text.getBytes(StandardCharsets.US_ASCII);
    }

    private void whenTheFilterIsMadeAtTheLevel(int level) {
        this.filter = IpFilter.of(this.ranges, level);
    }

    private void whenTheFilterIsEmpty() {
        this.filter = IpFilter.empty();
    }

    private void whenTheFilterIsReadAtTheLevel(int level) {
        this.filter = IpFilter.of(IpFilterDat.read(this.dat, "ipfilter.dat"), level);
    }

    private void thenItBlocks(String address) {
        assertThat(this.filter.blocks(Ipv4.parse(address))).isTrue();
    }

    private void thenItLetsThrough(String address) {
        assertThat(this.filter.blocks(Ipv4.parse(address))).isFalse();
    }

    private void thenItLetsThrough(String address, String why) {
        assertThat(this.filter.blocks(Ipv4.parse(address))).as(why).isFalse();
    }

    private void thenItMatches(String address, String description) {
        assertThat(this.filter.match(Ipv4.parse(address))).contains(description);
    }

    private void thenItHoldsRanges(int count) {
        assertThat(this.filter.rangeCount()).isEqualTo(count);
    }

    private void thenEveryAddressMatchesTheLastBlockingRangeCoveringIt() {
        for (int address = 1; address <= LAST_SMALL_ADDRESS; address++) {
            int winner = lastBlockingRangeCovering(address);
            assertThat(this.filter.match(address))
                    .as("address %d", address)
                    .isEqualTo(
                            winner < 0
                                    ? Optional.empty()
                                    : Optional.of(this.ranges.get(winner).description()));
        }
    }

    private void thenItHoldsOneRangePerRunOfAddressesWonByTheSameRange() {
        int runs = 0;
        int previous = -1;
        for (int address = 1; address <= LAST_SMALL_ADDRESS; address++) {
            int winner = lastBlockingRangeCovering(address);
            if (winner >= 0 && winner != previous) {
                runs++;
            }
            previous = winner;
        }
        assertThat(this.filter.rangeCount()).isEqualTo(runs);
    }

    private int lastBlockingRangeCovering(int address) {
        for (int i = this.ranges.size() - 1; i >= 0; i--) {
            Range r = this.ranges.get(i);
            if (r.level() < IpFilter.DEFAULT_LEVEL && r.contains(address)) {
                return i;
            }
        }
        return -1;
    }

    @Provide
    Arbitrary<List<Range>> smallRanges() {
        return Combinators.combine(
                        Arbitraries.integers().between(1, LAST_SMALL_ADDRESS),
                        Arbitraries.integers().between(0, 40),
                        Arbitraries.of(0, IpFilter.DEFAULT_LEVEL))
                .as((start, length, level) -> new Range(
                        start, Math.min(LAST_SMALL_ADDRESS, start + length), level, start + "+" + length + "@" + level))
                .list()
                .ofMaxSize(12);
    }

    private static Range range(String start, String end, int level, String description) {
        return new Range(Ipv4.parse(start), Ipv4.parse(end), level, description);
    }
}
