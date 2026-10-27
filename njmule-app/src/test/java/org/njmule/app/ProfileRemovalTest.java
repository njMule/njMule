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
package org.njmule.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.config.AppDirectories;

class ProfileRemovalTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private AppDirectories profile;
    private String answer = "y";
    private final PretendAutostart autostart = new PretendAutostart(false);
    private int code;
    private FileChannel running;

    @AfterEach
    void stop() throws IOException {
        if (this.running != null) {
            this.running.close();
        }
    }

    @Test
    void shouldDeleteTheProfileAndKeepTheCompletedDownloads() throws Exception {
        givenAProfileWithADownload();

        whenRemoved("--remove-profile", "--yes");

        thenTheExitCodeIs(Main.EXIT_OK);
        thenTheProfileIsGone();
        thenTheDownloadStays();
    }

    @Test
    void shouldKeepTheFolderOfTheDownloadsTheUserChoseInsideTheProfile() throws Exception {
        givenAProfileWithADownload();
        givenTheDownloadsGoInsideTheProfile();

        whenRemoved("--remove-profile", "--yes");

        thenTheExitCodeIs(Main.EXIT_OK);
        thenTheChosenDownloadsStay();
    }

    @Test
    void shouldStopStartingAtLoginBeforeDeletingTheProfile() throws Exception {
        givenAProfileWithADownload();
        givenItStartsAtLogin();

        whenRemoved("--remove-profile", "--yes");

        thenTheExitCodeIs(Main.EXIT_OK);
        thenTheProfileIsGone();
        thenItStartsAtLogin(false);
    }

    @Test
    void shouldDeleteNothingWhenTheStartAtLoginCannotBeTurnedOff() throws Exception {
        givenAProfileWithADownload();
        givenItStartsAtLogin();
        givenTheStartAtLoginCannotBeTurnedOff();

        whenRemoved("--remove-profile", "--yes");

        thenTheExitCodeIs(Main.EXIT_FAILURE);
        thenTheErrorsSay("Nothing was deleted.");
        thenTheProfileStays();
    }

    @Test
    void shouldDeleteAfterAYes() throws Exception {
        givenAProfileWithADownload();

        whenRemoved("--remove-profile");

        thenTheProfileIsGone();
    }

    @Test
    void shouldDeleteNothingWithoutAYes() throws Exception {
        givenAProfileWithADownload();
        givenTheAnswer("n");

        whenRemoved("--remove-profile");

        thenTheExitCodeIs(Main.EXIT_OK);
        thenTheProfileStays();
    }

    @Test
    void shouldLeaveAProfileNjmuleRunsOn() throws Exception {
        givenAProfileWithADownload();
        givenNjmuleRunsOnIt();

        whenRemoved("--remove-profile", "--yes");

        thenTheExitCodeIs(Main.EXIT_FAILURE);
        thenTheProfileStays();
    }

    private void givenAProfileWithADownload() throws IOException {
        this.profile = AppDirectories.resolve(Optional.of(this.dir));
        this.profile.createAll();
        Files.createDirectories(this.profile.downloads());
        Files.writeString(this.profile.config().resolve("preferences.ini"), "nick=Ann");
        Files.writeString(this.profile.downloads().resolve("talk.bin"), "data");
    }

    // The completed downloads go to a folder of the data, which would go with it.
    private void givenTheDownloadsGoInsideTheProfile() throws IOException {
        Path chosen = Files.createDirectories(this.profile.data().resolve("Incoming"));
        Files.writeString(chosen.resolve("film.mkv"), "film");
        Properties preferences = new Properties();
        preferences.setProperty("dirs.incoming", chosen.toString());
        try (Writer out = Files.newBufferedWriter(this.profile.config().resolve("njmule.properties"))) {
            preferences.store(out, null);
        }
    }

    // The engine's lock of the profile, held as by a running njMule.
    private void givenNjmuleRunsOnIt() throws IOException {
        this.running = FileChannel.open(
                this.profile.config().resolve("njmule.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        this.running.lock();
    }

    private void givenItStartsAtLogin() {
        this.autostart.turnOn();
    }

    private void givenTheStartAtLoginCannotBeTurnedOff() {
        this.autostart.refusesToTurnOff = true;
    }

    private void givenTheAnswer(String typed) {
        this.answer = typed;
    }

    private void whenRemoved(String... options) throws Exception {
        String[] args = new String[options.length + 2];
        args[0] = "--config-dir";
        args[1] = this.dir.toString();
        System.arraycopy(options, 0, args, 2, options.length);
        this.code = ProfileRemoval.run(
                LaunchOptions.parse(args),
                new PrintStream(this.out, true, StandardCharsets.UTF_8),
                new PrintStream(this.err, true, StandardCharsets.UTF_8),
                prompt -> this.answer,
                config -> Optional.empty(),
                config -> Optional.of(this.autostart));
    }

    private void thenItStartsAtLogin(boolean expected) {
        assertThat(this.autostart.isOn()).isEqualTo(expected);
    }

    private void thenTheExitCodeIs(int expected) {
        assertThat(this.code).isEqualTo(expected);
    }

    private void thenTheErrorsSay(String text) {
        assertThat(this.err.toString(StandardCharsets.UTF_8)).contains(text);
    }

    private void thenTheProfileIsGone() {
        assertThat(this.profile.config()).doesNotExist();
        assertThat(this.profile.data()).doesNotExist();
        assertThat(this.profile.logs()).doesNotExist();
    }

    private void thenTheProfileStays() {
        assertThat(this.profile.config().resolve("preferences.ini")).exists();
    }

    private void thenTheChosenDownloadsStay() {
        assertThat(this.profile.data().resolve("Incoming").resolve("film.mkv")).hasContent("film");
        assertThat(this.out.toString(StandardCharsets.UTF_8))
                .contains(this.profile.data().resolve("Incoming").toString());
    }

    private void thenTheDownloadStays() {
        assertThat(this.profile.downloads().resolve("talk.bin")).hasContent("data");
    }
}
