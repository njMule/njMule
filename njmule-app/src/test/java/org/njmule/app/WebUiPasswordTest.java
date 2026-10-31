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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.njmule.http.Passwords;
import org.njmule.http.WebUiSettings;
import org.slf4j.LoggerFactory;

class WebUiPasswordTest {

    private static final String PASSWORD = "long enough";

    @TempDir
    Path dir;

    private Path file;
    private char[] password;
    private boolean set;
    private Throwable exception;
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(WebUiPassword.class);

    @AfterEach
    void stopListeningToTheLog() {
        this.logger.detachAppender(this.logged);
    }

    @ParameterizedTest
    @ValueSource(strings = {PASSWORD, PASSWORD + "\n", PASSWORD + "\r\n", PASSWORD + "\nnext line", "﻿" + PASSWORD})
    void shouldReadTheFirstLineOfThePasswordFile(String content) throws IOException {
        givenThePasswordFile(content);

        whenThePasswordIsRead();

        thenThePasswordIs(PASSWORD);
    }

    @Test
    void shouldKeepTheSpacesOfThePassword() throws IOException {
        givenThePasswordFile("  spaced out  \n");

        whenThePasswordIsRead();

        thenThePasswordIs("  spaced out  ");
    }

    @Test
    void shouldRefuseAPasswordFileThatDoesNotExist() {
        givenAPasswordFileThatDoesNotExist();

        whenThePasswordIsRead();

        thenExceptionOccurred("does not exist");
    }

    @Test
    void shouldRefuseAPasswordTooShortWithoutTellingIt() throws IOException {
        givenThePasswordFile("shhh!\n");

        whenThePasswordIsRead();

        thenExceptionOccurred("at least " + Passwords.MIN_LENGTH + " characters");
        thenTheMessageDoesNotContain("shhh!");
    }

    @Test
    void shouldRefuseAPasswordFileTooLong() throws IOException {
        givenThePasswordFile("x".repeat(WebUiPassword.MAX_FILE_BYTES + 1));

        whenThePasswordIsRead();

        thenExceptionOccurred("longer than " + WebUiPassword.MAX_FILE_BYTES + " bytes");
    }

    @Test
    void shouldRefuseAPasswordFileThatIsNotUtf8() throws IOException {
        givenThePasswordFileBytes(new byte[] {'l', 'o', 'n', 'g', (byte) 0xFF, 'e', 'n', 'o', 'u', 'g', 'h'});

        whenThePasswordIsRead();

        thenExceptionOccurred("not UTF-8");
    }

    @Test
    void shouldTurnTheWebInterfaceOnWithThePasswordOfTheFile() throws IOException {
        givenThePasswordFile(PASSWORD + "\n");

        whenTheCommandLineSetsThePasswordFromTheFile("--webui-lan");

        thenTheWebInterfaceIsOnWithThePassword(PASSWORD);
        thenTheWebInterfaceIsOpenToOtherComputers();
    }

    // What the web interface set for itself survives the next start of a container.
    @Test
    void shouldKeepTheSettingsTheCommandLineDoesNotGiveAtEachStart() throws IOException {
        givenTheWebInterfaceSettings(new WebUiSettings(true, 8080, true, "pbkdf2-sha512$1$AA$AA", true));
        givenThePasswordFile(PASSWORD);

        whenTheCommandLineSetsThePasswordFromTheFile();

        thenTheWebInterfaceIsOnWithThePassword(PASSWORD);
        thenTheSavedSettingsAre(8080, true, true);
    }

    @Test
    void shouldSetTheWebInterfaceUpAnewWithThePasswordCommand() throws IOException {
        givenTheWebInterfaceSettings(
                new WebUiSettings(true, 8080, true, "pbkdf2-sha512$1$AA$AA", true, List.of("old.example")));
        givenThePasswordFile(PASSWORD);

        whenThePasswordCommandSetsThePasswordFromTheFile();

        thenTheWebInterfaceIsOnWithThePassword(PASSWORD);
        thenTheSavedSettingsAre(8080, false, true);
        thenTheSavedHostNamesAre();
    }

    @Test
    void shouldTakeTheHostNamesFromTheCommandLineInPlaceOfTheSavedOnes() throws IOException {
        givenTheWebInterfaceSettings(
                new WebUiSettings(true, 8080, true, "pbkdf2-sha512$1$AA$AA", false, List.of("old.example")));
        givenThePasswordFile(PASSWORD);

        whenTheCommandLineSetsThePasswordFromTheFile("--webui-host", "nas.local", "--webui-host", "njmule.example.com");

        thenTheSavedHostNamesAre("nas.local", "njmule.example.com");
    }

    @Test
    void shouldKeepTheSavedHostNamesTheCommandLineDoesNotGive() throws IOException {
        givenTheWebInterfaceSettings(
                new WebUiSettings(true, 8080, true, "pbkdf2-sha512$1$AA$AA", false, List.of("old.example")));
        givenThePasswordFile(PASSWORD);

        whenTheCommandLineSetsThePasswordFromTheFile();

        thenTheSavedHostNamesAre("old.example");
    }

    @Test
    void shouldLogTheFileNotThePassword() throws IOException {
        givenThePasswordFile(PASSWORD);
        givenTheLogIsListenedTo();

        whenTheCommandLineSetsThePasswordFromTheFile();

        thenTheLogNamesTheFileButNotThePassword();
    }

    @Test
    void shouldSetNothingWithoutAPasswordFile() {
        whenTheCommandLineSetsNoPasswordFile();

        thenNothingWasSaved();
    }

    private void givenThePasswordFile(String content) throws IOException {
        givenThePasswordFileBytes(content.getBytes(UTF_8));
    }

    private void givenThePasswordFileBytes(byte[] content) throws IOException {
        this.file = Files.write(this.dir.resolve("webui-password"), content);
    }

    private void givenAPasswordFileThatDoesNotExist() {
        this.file = this.dir.resolve("missing");
    }

    private void givenTheWebInterfaceSettings(WebUiSettings settings) throws IOException {
        settings.save(config());
    }

    private void givenTheLogIsListenedTo() {
        this.logged.start();
        this.logger.addAppender(this.logged);
    }

    private void whenThePasswordIsRead() {
        try {
            this.password = WebUiPassword.read(this.file);
        } catch (LaunchOptions.UsageException e) {
            this.exception = e;
        }
    }

    private void whenTheCommandLineSetsThePasswordFromTheFile(String... options) {
        List<String> args = new ArrayList<>(List.of("--webui-password-file", this.file.toString()));
        args.addAll(List.of(options));
        whenTheCommandLineSetsThePassword(args);
    }

    private void whenThePasswordCommandSetsThePasswordFromTheFile() {
        whenTheCommandLineSetsThePassword(List.of("--webui-password", "--webui-password-file", this.file.toString()));
    }

    private void whenTheCommandLineSetsNoPasswordFile() {
        whenTheCommandLineSetsThePassword(List.of("--config-dir", this.dir.toString()));
    }

    private void whenTheCommandLineSetsThePassword(List<String> args) {
        try {
            this.set = WebUiPassword.setFromFile(LaunchOptions.parse(args.toArray(String[]::new)), config());
        } catch (LaunchOptions.UsageException | IOException e) {
            this.exception = e;
        }
    }

    private void thenThePasswordIs(String expected) {
        assertThat(this.exception).isNull();
        assertThat(this.password).containsExactly(expected.toCharArray());
    }

    private void thenExceptionOccurred(String messagePart) {
        assertThat(this.exception)
                .isInstanceOf(LaunchOptions.UsageException.class)
                .hasMessageContaining(messagePart);
    }

    private void thenTheMessageDoesNotContain(String text) {
        assertThat(this.exception.getMessage()).doesNotContain(text);
    }

    private void thenTheWebInterfaceIsOnWithThePassword(String expected) {
        assertThat(this.exception).isNull();
        WebUiSettings settings = WebUiSettings.load(config());
        assertThat(settings.usable()).as("on, with a password").isTrue();
        assertThat(Passwords.matches(expected.toCharArray(), settings.passwordHash()))
                .as("the password of the file")
                .isTrue();
        assertThat(this.set).as("a password file was given").isTrue();
    }

    private void thenTheWebInterfaceIsOpenToOtherComputers() {
        assertThat(WebUiSettings.load(config()).lan()).isTrue();
    }

    private void thenTheSavedSettingsAre(int port, boolean lan, boolean api) {
        WebUiSettings settings = WebUiSettings.load(config());
        assertThat(settings.port()).isEqualTo(port);
        assertThat(settings.lan()).isEqualTo(lan);
        assertThat(settings.api()).isEqualTo(api);
    }

    private void thenTheSavedHostNamesAre(String... names) {
        assertThat(this.exception).isNull();
        assertThat(WebUiSettings.load(config()).hosts()).containsExactly(names);
    }

    private void thenTheLogNamesTheFileButNotThePassword() {
        assertThat(this.logged.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains(this.file.toString()))
                .noneMatch(message -> message.contains(PASSWORD));
    }

    private void thenNothingWasSaved() {
        assertThat(this.exception).isNull();
        assertThat(this.set).as("a password file was given").isFalse();
        assertThat(config().resolve(WebUiSettings.FILE_NAME)).doesNotExist();
    }

    private Path config() {
        return this.dir.resolve("config");
    }
}
