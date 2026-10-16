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
package org.njmule.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.njmule.core.persist.format.IpFilterDat;

/** IP filter lists joined into one filter, and fetched again once the filter is old enough. */
class IpFilterListsTest {

    private static final IpFilterDat.Range FIRST = new IpFilterDat.Range(0x0102_0300, 0x0102_03FF, 100, "first");
    private static final IpFilterDat.Range SECOND = new IpFilterDat.Range(0x0506_0700, 0x0506_07FF, 100, "second");
    private static final long NOW = 1_760_000_000_000L;

    private final List<URI> lists = new ArrayList<>();
    private final List<CompletableFuture<List<IpFilterDat.Range>>> fetched = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private List<IpFilterDat.Range> joined;
    private Throwable exception;
    private boolean due;

    @Test
    void shouldJoinTheListsThatCouldBeReadAndTellTheOthers() {
        givenTheList("https://one.example/ipfilter.zip", CompletableFuture.completedFuture(List.of(FIRST)));
        givenTheList("https://two.example/level1.p2p", CompletableFuture.failedFuture(new IOException("offline")));
        givenTheList("https://three.example/empty.dat", CompletableFuture.completedFuture(List.of()));
        givenTheList("https://four.example/guarding.p2p", CompletableFuture.completedFuture(List.of(SECOND)));

        whenTheListsAreJoined();

        thenTheRangesAre(FIRST, SECOND);
        thenTheWarningsName("two.example", "three.example");
    }

    @Test
    void shouldFailWithTheFirstReasonWhenNoListCouldBeRead() {
        givenTheList("https://one.example/ipfilter.zip", CompletableFuture.failedFuture(new IOException("offline")));
        givenTheList("https://two.example/empty.dat", CompletableFuture.completedFuture(List.of()));

        whenTheListsAreJoined();

        thenTheFailureSays("offline");
    }

    @ParameterizedTest(name = "lists {0}, days {1}, filter {2} days old -> {3}")
    @CsvSource({
        "0, 7, 30, false",
        "1, 0, 30, false",
        "1, 7, -1, true",
        "1, 7, 6, false",
        "1, 7, 7, true",
        "2, 1, 2, true"
    })
    void shouldFetchTheListsAgainOnceTheFilterIsAsOldAsTheySay(int lists, int days, int age, boolean expected) {
        whenItIsAskedWhetherTheListsAreDue(lists, days, age);

        thenTheyAreDue(expected);
    }

    private void givenTheList(String address, CompletableFuture<List<IpFilterDat.Range>> outcome) {
        this.lists.add(URI.create(address));
        this.fetched.add(outcome);
    }

    private void whenTheListsAreJoined() {
        this.exception =
                catchThrowable(() -> this.joined = Engine.joined(this.lists, this.fetched, this.warnings::add));
    }

    // An age below zero stands for no filter yet.
    private void whenItIsAskedWhetherTheListsAreDue(int lists, int days, int age) {
        List<URI> addresses = new ArrayList<>();
        for (int i = 0; i < lists; i++) {
            addresses.add(URI.create("https://list" + i + ".example/ipfilter.zip"));
        }
        OptionalLong filterDate = age < 0
                ? OptionalLong.empty()
                : OptionalLong.of(NOW - Duration.ofDays(age).toMillis());
        this.due = Engine.isIpFilterDue(filterDate, NOW, days, addresses);
    }

    private void thenTheRangesAre(IpFilterDat.Range... ranges) {
        assertThat(this.exception).isNull();
        assertThat(this.joined).containsExactly(ranges);
    }

    private void thenTheWarningsName(String... hosts) {
        assertThat(this.warnings).hasSize(hosts.length);
        for (int i = 0; i < hosts.length; i++) {
            assertThat(this.warnings.get(i)).contains(hosts[i]);
        }
    }

    private void thenTheFailureSays(String message) {
        assertThat(this.exception).isInstanceOf(CompletionException.class).hasRootCauseMessage(message);
    }

    private void thenTheyAreDue(boolean expected) {
        assertThat(this.due).isEqualTo(expected);
    }
}
