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
package org.njmule.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The network an automatic search goes to, by what is connected and the server connected to. */
class SearchRequestAutomaticTest {

    private boolean serverConnected;
    private boolean kadConnected;
    private Optional<ServerInfo> server = Optional.empty();
    private int knownServers = 10;
    private Optional<SearchRequest.Scope> scope;

    @Test
    void shouldSearchNowhereWithoutANetwork() {
        whenAnAutomaticSearchStarts();

        thenItGoesNowhere();
    }

    @Test
    void shouldSearchKadAlone() {
        givenKadConnected();

        whenAnAutomaticSearchStarts();

        thenItGoesTo(SearchRequest.Scope.KAD);
    }

    @Test
    void shouldSearchTheServerAloneEvenUnknown() {
        givenAServerConnected();

        whenAnAutomaticSearchStarts();

        thenItGoesTo(SearchRequest.Scope.SERVER);
    }

    @Test
    void shouldPreferKadToAnOrdinaryServer() {
        givenAServerConnected();
        givenKadConnected();
        givenTheServerHas(10_000, 1_000_000, false);

        whenAnAutomaticSearchStarts();

        thenItGoesTo(SearchRequest.Scope.KAD);
    }

    @Test
    void shouldStayOnAStaticServer() {
        givenAServerConnected();
        givenKadConnected();
        givenTheServerHas(10, 10, true);

        whenAnAutomaticSearchStarts();

        thenItGoesTo(SearchRequest.Scope.SERVER);
    }

    @ParameterizedTest
    @CsvSource({
        "40001, 5000001, 39, SERVER",
        "40000, 5000001, 39, KAD",
        "1999999, 5000001, 39, SERVER",
        "2000000, 5000001, 39, KAD",
        "40001, 5000000, 39, KAD",
        "40001, 5000001, 40, KAD"
    })
    void shouldStayOnABigServerOnlyInAShortList(long users, long files, int servers, SearchRequest.Scope expected) {
        givenAServerConnected();
        givenKadConnected();
        givenTheServerHas(users, files, false);
        givenTheListHoldsServers(servers);

        whenAnAutomaticSearchStarts();

        thenItGoesTo(expected);
    }

    private void givenAServerConnected() {
        this.serverConnected = true;
    }

    private void givenKadConnected() {
        this.kadConnected = true;
    }

    private void givenTheServerHas(long users, long files, boolean isStatic) {
        this.server = Optional.of(new ServerInfo(
                "81.2.3.4:4661",
                "Server",
                "",
                users,
                files,
                0,
                0,
                ServerInfo.Priority.NORMAL,
                0,
                0,
                0,
                0,
                "",
                false,
                isStatic));
    }

    private void givenTheListHoldsServers(int count) {
        this.knownServers = count;
    }

    private void whenAnAutomaticSearchStarts() {
        this.scope = SearchRequest.automatic(this.serverConnected, this.kadConnected, this.server, this.knownServers);
    }

    private void thenItGoesNowhere() {
        assertThat(this.scope).isEmpty();
    }

    private void thenItGoesTo(SearchRequest.Scope expected) {
        assertThat(this.scope).hasValue(expected);
    }
}
