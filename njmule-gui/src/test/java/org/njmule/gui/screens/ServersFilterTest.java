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
package org.njmule.gui.screens;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.njmule.core.api.ServerInfo;

class ServersFilterTest {

    private static final ServerInfo SUNRISE = new ServerInfo(
            "176.123.5.89:4725",
            "eMule Sunrise",
            "Sunrise server",
            0,
            0,
            0,
            0,
            ServerInfo.Priority.NORMAL,
            40,
            0,
            0,
            0,
            "17.15 (lugdunum)",
            true,
            false);

    private Predicate<ServerInfo> filter;
    private String text;
    private List<URI> addresses;
    private String number;

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "SUNRISE", "sunrise 176.123"})
    void shouldKeepAServerWhenEveryWordMatchesSomewhereInAnyCase(String words) {
        whenTheServersAreFilteredBy(words);

        thenTheServerIsKept();
    }

    @Test
    void shouldKeepAServerWhoseVersionMatches() {
        whenTheServersAreFilteredBy("lugdunum");

        thenTheServerIsKept();
    }

    @Test
    void shouldDropAServerWhenAWordMatchesNowhere() {
        whenTheServersAreFilteredBy("sunrise donkey");

        thenTheServerIsDropped();
    }

    @Test
    void shouldTakeTheWebAddressesOfTheServerListLines() {
        givenTheServerListLines(
                "http://a.example/server.met\n  \nftp://x.example/y\nnot an address\nhttps://b.example/s.met");

        whenTheAddressesAreRead();

        thenTheAddressesAre("http://a.example/server.met", "https://b.example/s.met");
    }

    @ParameterizedTest
    @CsvSource({"1.2.3.4, 16909060", "255.255.255.255, 4294967295"})
    void shouldShowOurKadAddressAsTheNumberItsIdIsMadeOf(String ip, String expected) {
        whenTheAddressIsTurnedIntoANumber(ip);

        thenTheNumberIs(expected);
    }

    private void givenTheServerListLines(String lines) {
        this.text = lines;
    }

    private void whenTheServersAreFilteredBy(String words) {
        this.filter = ServersScreen.matching(words);
    }

    private void whenTheAddressesAreRead() {
        this.addresses = SettingsScreen.addresses(this.text);
    }

    private void whenTheAddressIsTurnedIntoANumber(String ip) {
        this.number = MyInfoPane.number(ip);
    }

    private void thenTheServerIsKept() {
        assertThat(this.filter).accepts(SUNRISE);
    }

    private void thenTheServerIsDropped() {
        assertThat(this.filter).rejects(SUNRISE);
    }

    private void thenTheAddressesAre(String... expected) {
        assertThat(this.addresses).extracting(URI::toString).containsExactly(expected);
    }

    private void thenTheNumberIs(String expected) {
        assertThat(this.number).isEqualTo(expected);
    }
}
