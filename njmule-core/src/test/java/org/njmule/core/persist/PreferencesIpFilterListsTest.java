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
package org.njmule.core.persist;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.config.Preferences;

/** The IP filter lists and how often they are fetched again, as kept in the preferences. */
class PreferencesIpFilterListsTest {

    @TempDir
    Path dir;

    private String lists = "";
    private int days = Preferences.DEFAULT_IP_FILTER_UPDATE_DAYS;
    private Preferences loaded;
    private RuntimeException failure;

    @Test
    void shouldKeepTheListsAndHowOftenTheyAreFetched() throws Exception {
        givenTheLists(" https://one.example/ipfilter.zip\n\nHTTP://two.example/level1.p2p.gz ");
        givenTheUpdateEvery(3);

        whenThePreferencesAreSavedAndLoaded();

        thenTheListAddressesAre("https://one.example/ipfilter.zip", "HTTP://two.example/level1.p2p.gz");
        thenTheUpdateIsEvery(3);
    }

    @Test
    void shouldFetchTheListsAgainEveryWeekByDefault() {
        whenThePreferencesAreBuilt();

        thenTheFailureIsNone();
        thenTheUpdateIsEvery(7);
    }

    @Test
    void shouldRefuseAListThatIsNoWebAddress() {
        givenTheLists("https://one.example/ipfilter.zip\nftp://two.example/ipfilter.dat");

        whenThePreferencesAreBuilt();

        thenTheFailureNames("ftp://two.example/ipfilter.dat");
    }

    @Test
    void shouldRefuseMoreListsThanAFilterTakes() {
        givenTheLists("https://one.example/ipfilter.zip\n".repeat(Preferences.MAX_IP_FILTER_LISTS + 1));

        whenThePreferencesAreBuilt();

        thenTheFailureNames("at most " + Preferences.MAX_IP_FILTER_LISTS);
    }

    @Test
    void shouldRefuseAnUpdateRarerThanAYear() {
        givenTheUpdateEvery(Preferences.MAX_IP_FILTER_UPDATE_DAYS + 1);

        whenThePreferencesAreBuilt();

        thenTheFailureNames("ipFilterUpdateDays");
    }

    private void givenTheLists(String text) {
        this.lists = text;
    }

    private void givenTheUpdateEvery(int value) {
        this.days = value;
    }

    private void whenThePreferencesAreSavedAndLoaded() throws Exception {
        Path file = this.dir.resolve(PreferencesCodec.FILE_NAME);
        PreferencesCodec.save(file, built());
        this.loaded = PreferencesCodec.load(file);
    }

    private void whenThePreferencesAreBuilt() {
        try {
            this.loaded = built();
        } catch (IllegalArgumentException e) {
            this.failure = e;
        }
    }

    private void thenTheListAddressesAre(String... addresses) {
        assertThat(this.loaded.ipFilterListAddresses())
                .extracting(URI::toString)
                .containsExactly(addresses);
    }

    private void thenTheUpdateIsEvery(int expected) {
        assertThat(this.loaded.ipFilterUpdateDays()).isEqualTo(expected);
    }

    private void thenTheFailureIsNone() {
        assertThat(this.failure).isNull();
    }

    private void thenTheFailureNames(String text) {
        assertThat(this.failure).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(text);
    }

    private Preferences built() {
        return Preferences.defaults().toBuilder()
                .ipFilterLists(this.lists)
                .ipFilterUpdateDays(this.days)
                .build();
    }
}
