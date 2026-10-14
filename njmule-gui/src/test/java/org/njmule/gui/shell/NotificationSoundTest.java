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
package org.njmule.gui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.PartMap;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(120)
class NotificationSoundTest {

    private static final Hash16 HASH = Hash16.parseHex("00112233445566778899aabbccddeeff");

    @TempDir
    Path dir;

    private TestGui test;
    private MainWindow window;

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldPlayTheSoundWhenADownloadCompletes() throws Exception {
        this.givenTheWindow();
        this.givenTheSound(true, Optional.of(this.ding()));

        this.whenADownloadCompletes();

        this.thenThePlayedSoundsAre(this.ding());
    }

    @Test
    void shouldPlayTheSoundWhenAMessageArrivesOnAnotherScreen() throws Exception {
        this.givenTheWindow();
        this.givenTheSound(true, Optional.of(this.ding()));
        this.givenTheScreenShows("transfers");

        this.whenAMessageArrives();

        this.thenThePlayedSoundsAre(this.ding());
    }

    @Test
    void shouldPlayNothingWhileTheSoundIsOff() throws Exception {
        this.givenTheWindow();
        this.givenTheSound(false, Optional.of(this.ding()));

        this.whenADownloadCompletes();

        this.thenNoSoundWasPlayed();
    }

    @Test
    void shouldPlayNothingWithoutASoundFile() throws Exception {
        this.givenTheWindow();
        this.givenTheSound(true, Optional.empty());

        this.whenADownloadCompletes();

        this.thenNoSoundWasPlayed();
    }

    private void givenTheWindow() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        this.window = FxTesting.onFx(() -> new MainWindow(this.test.gui));
        this.test.show(this.window.root());
    }

    private void givenTheSound(boolean on, Optional<Path> file) throws Exception {
        this.test
                .engine
                .updatePreferences(p -> p.toBuilder()
                        .notificationSound(on)
                        .notificationSoundFile(file)
                        .build())
                .get(10, TimeUnit.SECONDS);
        FxTesting.await("the model has the sound", () -> {
            var shown = this.test.model.preferencesProperty().get();
            return shown != null
                    && shown.notificationSound() == on
                    && shown.notificationSoundFile().equals(file);
        });
    }

    private void givenTheScreenShows(String screen) throws Exception {
        FxTesting.onFx(() -> this.window.show(screen));
    }

    private void whenADownloadCompletes() throws Exception {
        DownloadInfo download = new DownloadInfo(
                HASH,
                "song.ogg",
                10,
                10,
                DownloadInfo.Status.COMPLETE,
                DownloadPriority.NORMAL,
                0,
                0,
                0,
                0,
                PartMap.EMPTY);
        FxTesting.onFx(() ->
                this.test.model.handle(new EngineEvent.DownloadCompleted(download, this.dir.resolve("song.ogg"))));
    }

    private void whenAMessageArrives() throws Exception {
        FxTesting.onFx(() -> this.test.model.handle(
                new EngineEvent.MessageReceived("192.0.2.9:4662", HASH, Optional.of("ann"), "hello")));
    }

    private void thenThePlayedSoundsAre(Path... expected) throws Exception {
        FxTesting.onFx(() -> {});
        assertThat(this.test.played).containsExactly(expected);
    }

    private void thenNoSoundWasPlayed() throws Exception {
        FxTesting.onFx(() -> {});
        assertThat(this.test.played).isEmpty();
    }

    private Path ding() {
        return this.dir.resolve("ding.wav").toAbsolutePath();
    }
}
