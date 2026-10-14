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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.config.Preferences;

class PreferencesWhitelistTest {

    @TempDir
    Path dir;

    private String whitelist;
    private Preferences loaded;
    private RuntimeException failure;

    @Test
    void shouldKeepAWhitelistOfSeveralLines() throws Exception {
        this.givenTheWhitelist("# friends\n1.2.3.4\n10.0.0.0/8\n5.6.7.0 - 5.6.7.255 , 100 , office");

        this.whenThePreferencesAreSavedAndLoaded();

        this.thenTheLoadedWhitelistIs("# friends\n1.2.3.4\n10.0.0.0/8\n5.6.7.0 - 5.6.7.255 , 100 , office");
    }

    @Test
    void shouldKeepNoWhitelistWhenTheFileHoldsAWrongOne() throws Exception {
        this.givenAPreferencesFileWith(
                "security.ipFilterWhitelist=1.2.3.4\\nnot an address\nsecurity.ipFilterLevel=99\n");

        this.whenThePreferencesAreLoaded();

        this.thenTheLoadedWhitelistIs("");
        this.thenTheLoadedFilterLevelIs(99);
    }

    @Test
    void shouldRefuseAWhitelistWithAWrongEntry() {
        this.givenTheWhitelist("1.2.3.4\n1.2.3.0/40");

        this.whenThePreferencesAreBuilt();

        this.thenTheFailureNames("1.2.3.0/40");
    }

    @Test
    void shouldRefuseAWhitelistTooLong() {
        this.givenTheWhitelist("1.2.3.4\n".repeat(Preferences.MAX_IP_FILTER_WHITELIST / 8 + 1));

        this.whenThePreferencesAreBuilt();

        this.thenTheFailureNames("at most " + Preferences.MAX_IP_FILTER_WHITELIST);
    }

    private void givenTheWhitelist(String text) {
        this.whitelist = text;
    }

    private void givenAPreferencesFileWith(String content) throws Exception {
        Files.writeString(this.dir.resolve(PreferencesCodec.FILE_NAME), content, StandardCharsets.UTF_8);
    }

    private void whenThePreferencesAreSavedAndLoaded() throws Exception {
        Path file = this.dir.resolve(PreferencesCodec.FILE_NAME);
        PreferencesCodec.save(
                file,
                Preferences.defaults().toBuilder()
                        .ipFilterWhitelist(this.whitelist)
                        .build());
        this.loaded = PreferencesCodec.load(file);
    }

    private void whenThePreferencesAreLoaded() throws Exception {
        this.loaded = PreferencesCodec.load(this.dir.resolve(PreferencesCodec.FILE_NAME));
    }

    private void whenThePreferencesAreBuilt() {
        try {
            Preferences.defaults().toBuilder().ipFilterWhitelist(this.whitelist).build();
        } catch (IllegalArgumentException e) {
            this.failure = e;
        }
    }

    private void thenTheLoadedWhitelistIs(String expected) {
        assertThat(this.loaded.ipFilterWhitelist()).isEqualTo(expected);
    }

    private void thenTheLoadedFilterLevelIs(int expected) {
        assertThat(this.loaded.ipFilterLevel()).isEqualTo(expected);
    }

    private void thenTheFailureNames(String text) {
        assertThat(this.failure).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(text);
    }
}
