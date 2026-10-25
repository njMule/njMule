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
package org.njmule.core.peer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Hash16;

class ClientHistoryTest {

    private static final Hash16 ANN = Hash16.parseHex("a1".repeat(16));
    private static final Hash16 BOB = Hash16.parseHex("b2".repeat(16));
    private static final Instant START = Instant.parse("2026-10-01T10:00:00Z");

    @TempDir
    Path dir;

    private ClientHistory history;
    private List<ClientHistory.Entry> clients = List.of();

    @Test
    void shouldKeepWhenAClientWasFirstMetAndWhatItSaidLast() {
        givenAnEmptyHistory();
        givenMet(ANN, "ann", "eMule v0.70b", Optional.of("it"), START);
        givenMet(ANN, "ann2", "eMule v0.72a", Optional.empty(), START.plus(Duration.ofDays(3)));

        whenListed(START.plus(Duration.ofDays(3)));

        thenTheClientsAre(new ClientHistory.Entry(
                ANN,
                "ann2",
                "eMule v0.72a",
                START.getEpochSecond(),
                START.plus(Duration.ofDays(3)).getEpochSecond(),
                Optional.of("it")));
    }

    @Test
    void shouldForgetAClientNotMetForNinetyDays() {
        givenAnEmptyHistory();
        givenMet(ANN, "ann", "eMule", Optional.empty(), START);
        givenMet(BOB, "bob", "aMule", Optional.empty(), START.plus(Duration.ofDays(10)));

        whenListed(START.plus(ClientHistory.KEPT).plusSeconds(1));

        thenTheNicksAre("bob");
    }

    @Test
    void shouldReadBackWhatItSaved() {
        givenAnEmptyHistory();
        givenMet(ANN, "ann", "eMule", Optional.of("fr"), START);
        givenMet(BOB, "bob", "aMule", Optional.empty(), START.plusSeconds(60));
        givenSaved();

        whenLoadedAndListed(START.plusSeconds(120));

        thenTheNicksAre("bob", "ann");
    }

    @Test
    void shouldKeepNoAddress() throws Exception {
        givenAnEmptyHistory();
        givenMet(ANN, "ann", "eMule", Optional.of("fr"), START);

        whenSaved();

        thenTheFileHoldsNo("4662", "192.0.2.");
    }

    private void givenAnEmptyHistory() {
        this.history = ClientHistory.load(this.dir, START);
    }

    private void givenMet(Hash16 who, String nick, String software, Optional<String> country, Instant when) {
        this.history.seen(who, nick, software, country, when);
    }

    private void givenSaved() {
        this.history.save();
    }

    private void whenListed(Instant now) {
        this.clients = this.history.clients(now);
    }

    private void whenLoadedAndListed(Instant now) {
        this.clients = ClientHistory.load(this.dir, now).clients(now);
    }

    private void whenSaved() {
        this.history.save();
    }

    private void thenTheClientsAre(ClientHistory.Entry... expected) {
        assertThat(this.clients).containsExactly(expected);
    }

    private void thenTheNicksAre(String... nicks) {
        assertThat(this.clients).extracting(ClientHistory.Entry::nick).containsExactly(nicks);
    }

    private void thenTheFileHoldsNo(String... parts) throws Exception {
        String text = Files.readString(this.dir.resolve(ClientHistory.FILE_NAME), StandardCharsets.UTF_8);
        assertThat(text).contains("ann").doesNotContain(parts);
    }
}
