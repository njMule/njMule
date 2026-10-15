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

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.njmule.core.persist.format.IpFilterDat;
import org.njmule.core.util.Ipv4;

class IpWhitelistTest {

    private String text;
    private List<IpFilterDat.Range> ranges;
    private Optional<String> invalid;
    private IpFilter filter;

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "1.2.3.4                                 | 1.2.3.4   | 1.2.3.4",
                "001.002.003.004                         | 1.2.3.4   | 1.2.3.4",
                "10.20.30.40/24                          | 10.20.30.0 | 10.20.30.255",
                "10.20.30.40 / 32                        | 10.20.30.40 | 10.20.30.40",
                "200.1.2.3/1                             | 128.0.0.0 | 255.255.255.255",
                "8.8.8.8/0                               | 0.0.0.0   | 255.255.255.255",
                "1.2.3.0 - 1.2.3.255 , 000 , a/b network | 1.2.3.0   | 1.2.3.255",
                "5.6.7.8-5.6.7.9                         | 5.6.7.8   | 5.6.7.9",
                "Some Org:9.9.9.0-9.9.9.127              | 9.9.9.0   | 9.9.9.127"
            })
    void shouldReadAnAddressABlockOrARangeOfIpFilterDat(String entry, String start, String end) {
        givenTheWhitelist(entry);

        whenItsRangesAreRead();

        thenTheRangesAre(range(start, end));
    }

    @Test
    void shouldLeaveOutEmptyLinesAndComments() {
        givenTheWhitelist("# my friends\n\n  1.2.3.4  \r\n// office\n5.6.7.0/31\n");

        whenItsRangesAreRead();

        thenTheRangesAre(range("1.2.3.4", "1.2.3.4"), range("5.6.7.0", "5.6.7.1"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "1.2.3",
                "256.1.1.1",
                "1.2.3.4/33",
                "1.2.3.4/x",
                "1.2.3.4/",
                "/24",
                "1.2.3.4/024",
                "1.2.3.4 friend",
                "1.2.3.9 - 1.2.3.0",
                "::1",
                "friend.example.org"
            })
    void shouldNameAnEntryThatIsNoAddressBlockOrRange(String entry) {
        givenTheWhitelist("1.2.3.4\n" + entry + "\n5.6.7.8");

        whenItsFirstInvalidEntryIsLookedFor();

        thenTheInvalidEntryIs(entry);
    }

    @Test
    void shouldFindNothingWrongInAGoodWhitelist() {
        givenTheWhitelist("1.2.3.4\n10.0.0.0/8");

        whenItsFirstInvalidEntryIsLookedFor();

        thenNoEntryIsInvalid();
    }

    @Test
    void shouldLetThroughTheAllowedAddressesOfABlockedRange() {
        givenAFilterBlocking(range("1.2.3.0", "1.2.3.255"));
        givenTheWhitelist("1.2.3.4\n1.2.3.128/25");

        whenTheFilterAllowsTheWhitelist();

        thenTheFilterLetsThrough("1.2.3.4", "1.2.3.128", "1.2.3.255", "9.9.9.9");
        thenTheFilterBlocks("1.2.3.3", "1.2.3.5", "1.2.3.127");
    }

    @Test
    void shouldForgetTheAddressesOfAnEarlierWhitelist() {
        givenAFilterBlocking(range("1.2.3.0", "1.2.3.255"));
        givenTheFilterAllowed("1.2.3.4");
        givenTheWhitelist("1.2.3.5");

        whenTheFilterAllowsTheWhitelist();

        thenTheFilterBlocks("1.2.3.4");
        thenTheFilterLetsThrough("1.2.3.5");
    }

    private void givenTheWhitelist(String whitelist) {
        this.text = whitelist;
    }

    private void givenAFilterBlocking(IpFilterDat.Range blocked) {
        this.filter = IpFilter.of(List.of(blocked), IpFilter.DEFAULT_LEVEL);
    }

    private void givenTheFilterAllowed(String whitelist) {
        this.filter = this.filter.allowing(IpWhitelist.ranges(whitelist));
    }

    private void whenItsRangesAreRead() {
        this.ranges = IpWhitelist.ranges(this.text);
    }

    private void whenItsFirstInvalidEntryIsLookedFor() {
        this.invalid = IpWhitelist.firstInvalidEntry(this.text);
    }

    private void whenTheFilterAllowsTheWhitelist() {
        this.filter = this.filter.allowing(IpWhitelist.ranges(this.text));
    }

    private void thenTheRangesAre(IpFilterDat.Range... expected) {
        assertThat(this.ranges)
                .extracting(r -> Ipv4.format(r.start()) + "-" + Ipv4.format(r.end()))
                .containsExactly(List.of(expected).stream()
                        .map(r -> Ipv4.format(r.start()) + "-" + Ipv4.format(r.end()))
                        .toArray(String[]::new));
    }

    private void thenTheInvalidEntryIs(String expected) {
        assertThat(this.invalid).contains(expected.strip());
    }

    private void thenNoEntryIsInvalid() {
        assertThat(this.invalid).isEmpty();
    }

    private void thenTheFilterLetsThrough(String... addresses) {
        for (String address : addresses) {
            assertThat(this.filter.blocks(Ipv4.parse(address))).as(address).isFalse();
        }
    }

    private void thenTheFilterBlocks(String... addresses) {
        for (String address : addresses) {
            assertThat(this.filter.blocks(Ipv4.parse(address))).as(address).isTrue();
        }
    }

    private static IpFilterDat.Range range(String start, String end) {
        return new IpFilterDat.Range(Ipv4.parse(start.strip()), Ipv4.parse(end.strip()), 0, "");
    }
}
