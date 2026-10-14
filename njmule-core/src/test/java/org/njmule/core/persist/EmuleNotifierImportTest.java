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
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.config.Preferences;
import org.njmule.core.persist.format.IniFile;

class EmuleNotifierImportTest {

    @TempDir
    Path dir;

    private Preferences before = Preferences.defaults();
    private final StringBuilder emulePreferences = new StringBuilder("[eMule]\n");
    private Preferences imported;

    @Test
    void shouldTakeTheSoundFileEmulePlaysWithItsNotifications() {
        this.givenEmuleSays("NotifierUseSound", "1");
        this.givenEmuleSays("NotifierSoundPath", this.dir.resolve("ding.wav").toString());

        this.whenTheEmulePreferencesAreImported();

        this.thenTheSoundIsOn(true);
        this.thenTheSoundFileIs(Optional.of(this.dir.resolve("ding.wav")));
    }

    @Test
    void shouldKeepTheSoundOffWhenEmuleSpeaksInstead() {
        this.givenOurSoundIsOn();
        this.givenEmuleSays("NotifierUseSound", "2");

        this.whenTheEmulePreferencesAreImported();

        this.thenTheSoundIsOn(false);
    }

    @Test
    void shouldLeaveOutARelativeSoundFile() {
        this.givenEmuleSays("NotifierUseSound", "1");
        this.givenEmuleSays("NotifierSoundPath", "sounds\\ding.wav");

        this.whenTheEmulePreferencesAreImported();

        this.thenTheSoundIsOn(true);
        this.thenTheSoundFileIs(Optional.empty());
    }

    @Test
    void shouldKeepOurSoundWhenEmuleSaysNothingOfIt() {
        this.givenOurSoundIsOn();

        this.whenTheEmulePreferencesAreImported();

        this.thenTheSoundIsOn(true);
    }

    private void givenEmuleSays(String key, String value) {
        this.emulePreferences.append(key).append('=').append(value).append('\n');
    }

    private void givenOurSoundIsOn() {
        this.before = this.before.toBuilder().notificationSound(true).build();
    }

    private void whenTheEmulePreferencesAreImported() {
        IniFile ini = IniFile.parse(this.emulePreferences.toString().getBytes(StandardCharsets.UTF_8));
        this.imported = EmuleImporter.mapPreferences(ini, this.before);
    }

    private void thenTheSoundIsOn(boolean expected) {
        assertThat(this.imported.notificationSound()).isEqualTo(expected);
    }

    private void thenTheSoundFileIs(Optional<Path> expected) {
        assertThat(this.imported.notificationSoundFile()).isEqualTo(expected);
    }
}
