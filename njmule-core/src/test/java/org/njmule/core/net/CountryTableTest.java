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
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CountryTableTest {

    // As DB-IP writes it: IPv4 and IPv6 ranges, and ZZ for the unknown.
    private static final String CSV = """
            1.0.0.0,1.0.0.255,AU
            2.16.0.0,2.16.0.255,IT
            2.16.1.0,2.16.1.255,IT
            5.5.5.0,5.5.5.255,ZZ
            203.0.113.0,203.0.113.255,FR
            255.255.255.0,255.255.255.255,US
            2001:db8::,2001:db8::ffff,DE
            """;

    private CountryTable table;
    private Optional<String> found;
    private Throwable exception;

    @ParameterizedTest
    @CsvSource({
        "1.0.0.7:4662, au",
        "2.16.1.9:4662, it",
        "203.0.113.200:51000, fr",
        "255.255.255.255:1, us",
        "5.5.5.5:4662, ''",
        "9.9.9.9:4662, ''",
        "'[2001:db8::1]:4662', ''"
    })
    void shouldTellTheCountryOfAClientAddress(String address, String country) throws Exception {
        givenTheTable(CSV);

        whenLookedUp(address);

        thenTheCountryIs(country);
    }

    @Test
    void shouldJoinTheRangesNextToEachOtherOfOneCountry() throws Exception {
        givenTheTable(CSV);

        whenWrittenAndReadBack();

        thenTheRangesAre(4);
    }

    @Test
    void shouldRefuseADatabaseWithoutAnIpv4Range() {
        whenParsed("2001:db8::,2001:db8::ffff,DE\n");

        thenExceptionOccurred(IOException.class);
    }

    private void givenTheTable(String csv) throws IOException {
        this.table = CountryTable.parse(csv.getBytes(StandardCharsets.US_ASCII));
    }

    private void whenLookedUp(String address) {
        this.found = this.table.countryOf(address);
    }

    private void whenWrittenAndReadBack() throws IOException {
        this.table = CountryTable.read(this.table.write());
    }

    private void whenParsed(String csv) {
        this.exception = catchThrowable(() -> CountryTable.parse(csv.getBytes(StandardCharsets.US_ASCII)));
    }

    private void thenTheCountryIs(String country) {
        assertThat(this.found).isEqualTo(country.isEmpty() ? Optional.empty() : Optional.of(country));
    }

    private void thenTheRangesAre(int ranges) {
        assertThat(this.table.size()).isEqualTo(ranges);
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).isInstanceOf(type);
    }
}
