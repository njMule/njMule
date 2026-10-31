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
package org.njmule.webui;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.remote.WebInterface;

/** The web interface's settings as the API changes them in their file. */
class SettingsFileTest {

    private static final String PASSWORD = "long enough";

    @TempDir
    Path dir;

    private SettingsFile file;
    private WebInterface.Settings settings;
    private final List<WebUiSettings> heard = new ArrayList<>();
    private Exception failure;

    @Test
    void shouldKeepTheChangesWithoutTheirPasswordsInTheClear() throws Exception {
        givenTheSettingsFile();

        whenChanged(change(Optional.of(true), Optional.of(PASSWORD), Optional.of(List.of("NAS.local"))));

        thenNoExceptionOccurred();
        thenTheSettingsSay(true, true, List.of("nas.local"));
        thenTheFileHoldsNo(PASSWORD);
    }

    @Test
    void shouldRefuseToTurnTheInterfaceOnWithoutAPassword() throws Exception {
        givenTheSettingsFile();

        whenChanged(change(Optional.of(true), Optional.empty(), Optional.empty()));

        thenExceptionOccurred("needs a password");
        thenTheInterfaceIsOffInTheFile();
        thenNothingWasHeard();
    }

    @Test
    void shouldHandOverTheSettingsSaved() throws Exception {
        givenTheSettingsFile();

        whenChanged(change(Optional.of(true), Optional.of(PASSWORD), Optional.empty()));

        thenTheSettingsHeardAreUsable();
    }

    @Test
    void shouldRefuseAHostThatIsNoName() throws Exception {
        givenTheSettingsFile();

        whenChanged(change(Optional.empty(), Optional.empty(), Optional.of(List.of("http://nas"))));

        thenExceptionOccurred("not a host name");
    }

    @Test
    void shouldRefuseAGuestPasswordThatIsTheAdmins() throws Exception {
        givenTheSettingsFile();
        givenAPassword();

        whenChanged(new WebInterface.Change(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(PASSWORD)));

        thenExceptionOccurred("must differ");
    }

    // Given

    private void givenTheSettingsFile() {
        this.file = new SettingsFile(() -> this.dir, this.heard::add);
    }

    private void givenAPassword() throws Exception {
        this.file.change(change(Optional.empty(), Optional.of(PASSWORD), Optional.empty()));
    }

    // When

    private void whenChanged(WebInterface.Change change) {
        try {
            this.settings = this.file.change(change);
        } catch (Exception e) {
            this.failure = e;
        }
    }

    // Then

    private void thenNoExceptionOccurred() {
        assertThat(this.failure).isNull();
    }

    private void thenNothingWasHeard() {
        assertThat(this.heard).isEmpty();
    }

    private void thenTheSettingsHeardAreUsable() {
        assertThat(this.heard)
                .singleElement()
                .satisfies(s -> assertThat(s.usable()).isTrue());
    }

    private void thenExceptionOccurred(String message) {
        assertThat(this.failure).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
    }

    private void thenTheSettingsSay(boolean enabled, boolean hasPassword, List<String> hosts) {
        assertThat(this.settings.enabled()).isEqualTo(enabled);
        assertThat(this.settings.hasPassword()).isEqualTo(hasPassword);
        assertThat(this.settings.hosts()).isEqualTo(hosts);
        assertThat(this.settings.url()).hasValue("http://127.0.0.1:" + WebUiSettings.DEFAULT_PORT + "/");
    }

    private void thenTheFileHoldsNo(String text) throws Exception {
        assertThat(Files.readString(this.dir.resolve(WebUiSettings.FILE_NAME))).doesNotContain(text);
    }

    private void thenTheInterfaceIsOffInTheFile() {
        assertThat(WebUiSettings.load(this.dir).enabled()).isFalse();
    }

    private static WebInterface.Change change(
            Optional<Boolean> enabled, Optional<String> password, Optional<List<String>> hosts) {
        return new WebInterface.Change(
                enabled,
                Optional.empty(),
                Optional.empty(),
                hosts,
                Optional.empty(),
                Optional.empty(),
                password,
                Optional.empty());
    }
}
