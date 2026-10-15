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
package org.njmule.gui.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

// Only files that are no sound: nothing is ever heard.
class WavPlayerTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @TempDir
    Path dir;

    private WavPlayer player;
    private Path file;
    private long took;
    private Optional<Path> settings;

    @BeforeEach
    void createThePlayer() {
        this.player = new WavPlayer(this.dir.resolve("cache"));
    }

    @Test
    void shouldBeReadyAgainAfterAFileThatIsNoSound() throws IOException {
        givenAFileThatIsNoSound();

        whenTheFileIsPlayed();

        thenThePlayerIsReadyAgain();
    }

    @Test
    void shouldBeReadyAgainAfterAMissingFile() {
        givenAMissingFile();

        whenTheFileIsPlayed();

        thenThePlayerIsReadyAgain();
    }

    @Test
    void shouldNotKeepTheCallerWaiting() throws IOException {
        givenAFileThatIsNoSound();

        whenTheFileIsPlayed();

        thenTheCallReturnedAtOnce();
        thenThePlayerIsReadyAgain();
    }

    @Test
    void shouldGiveTheSoundSystemEmptySettingsWhereThereIsNoJavaHome() throws IOException {
        whenTheSettingsAreAskedFor(null, null);

        thenTheSettingsAreAnEmptyFileInTheCache();
    }

    // An empty value stands for null.
    @ParameterizedTest
    @CsvSource({"/opt/jdk,", ", /etc/sound.properties"})
    void shouldLeaveTheSoundSystemAloneWithAJavaHomeOrNamedSettings(String javaHome, String named) throws IOException {
        whenTheSettingsAreAskedFor(javaHome, named);

        thenNoSettingsAreGiven();
    }

    private void givenAFileThatIsNoSound() throws IOException {
        this.file = Files.writeString(this.dir.resolve("notes.wav"), "not a sound", StandardCharsets.UTF_8);
    }

    private void givenAMissingFile() {
        this.file = this.dir.resolve("missing.wav");
    }

    private void whenTheFileIsPlayed() {
        long start = System.nanoTime();
        this.player.play(this.file);
        this.took = System.nanoTime() - start;
    }

    private void whenTheSettingsAreAskedFor(String javaHome, String named) throws IOException {
        this.settings = WavPlayer.soundSettings(javaHome, named, this.dir.resolve("cache"));
    }

    private void thenTheSettingsAreAnEmptyFileInTheCache() throws IOException {
        assertThat(this.settings).contains(this.dir.resolve("cache").resolve("sound.properties"));
        Properties read = new Properties();
        try (Reader in = Files.newBufferedReader(this.settings.orElseThrow())) {
            read.load(in);
        }
        assertThat(read).isEmpty();
    }

    private void thenNoSettingsAreGiven() {
        assertThat(this.settings).isEmpty();
        assertThat(this.dir.resolve("cache")).doesNotExist();
    }

    private void thenThePlayerIsReadyAgain() {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (this.player.isPlaying() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(10_000_000);
        }
        assertThat(this.player.isPlaying()).isFalse();
    }

    private void thenTheCallReturnedAtOnce() {
        assertThat(Duration.ofNanos(this.took)).isLessThan(Duration.ofSeconds(2));
    }
}
