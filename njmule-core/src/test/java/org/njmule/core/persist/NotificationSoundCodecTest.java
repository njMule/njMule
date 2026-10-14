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

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.config.Preferences;

class NotificationSoundCodecTest {

    @TempDir
    Path dir;

    private Preferences preferences = Preferences.defaults();
    private String text;
    private Preferences loaded;

    @Test
    void shouldKeepTheNotificationSoundThroughTheFile() throws IOException {
        this.givenTheSound(true, Optional.of(this.dir.resolve("my sounds").resolve("ding #1.wav")));

        this.whenThePreferencesAreSavedAndLoaded();

        this.thenTheLoadedPreferencesAreTheSaved();
    }

    @Test
    void shouldHaveNoSoundByDefault() throws IOException {
        this.givenTheFile("");

        this.whenTheFileIsLoaded();

        this.thenTheSoundIs(false, Optional.empty());
    }

    @Test
    void shouldLeaveOutARelativeSoundFile() throws IOException {
        this.givenTheFile("notify.sound=true\nnotify.soundFile=ding.wav\n");

        this.whenTheFileIsLoaded();

        this.thenTheSoundIs(true, Optional.empty());
    }

    private void givenTheSound(boolean on, Optional<Path> file) {
        this.preferences = this.preferences.toBuilder()
                .notificationSound(on)
                .notificationSoundFile(file)
                .build();
    }

    private void givenTheFile(String contents) {
        this.text = contents;
    }

    private void whenThePreferencesAreSavedAndLoaded() throws IOException {
        StringWriter file = new StringWriter();
        PreferencesCodec.format(this.preferences, file);
        this.text = file.toString();
        this.whenTheFileIsLoaded();
    }

    private void whenTheFileIsLoaded() throws IOException {
        this.loaded = PreferencesCodec.parse(new StringReader(this.text));
    }

    private void thenTheLoadedPreferencesAreTheSaved() {
        assertThat(this.loaded).isEqualTo(this.preferences);
    }

    private void thenTheSoundIs(boolean on, Optional<Path> file) {
        assertThat(this.loaded.notificationSound()).isEqualTo(on);
        assertThat(this.loaded.notificationSoundFile()).isEqualTo(file);
    }
}
