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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.config.NightHours;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.config.Preferences;
import org.njmule.core.config.ProxySettings;
import org.njmule.core.config.UpdateChannel;
import org.njmule.core.config.UpdateInstallTime;
import org.njmule.core.config.UpdateMode;

class PreferencesCodecTest {

    @TempDir
    Path dir;

    private Path file;
    private Preferences preferences;
    private String text;
    private Preferences loaded;
    private HeldFile held;
    private IOException exception;
    private String firstOutput;
    private String secondOutput;
    private String propertyValue;
    private String loadedValue;

    @BeforeEach
    void setUp() {
        this.file = this.dir.resolve(PreferencesCodec.FILE_NAME);
    }

    @AfterEach
    void releaseTheFile() throws IOException {
        if (this.held != null) {
            this.held.close();
        }
    }

    @Test
    void shouldRoundTripThroughTheFile() throws IOException {
        givenPreferencesUnlikeTheDefaults();

        whenThePreferencesAreSaved();

        thenTheFileLoadsThePreferencesBack();
        thenTheFileContains("net.tcpPort=12345");
        thenTheFileContains("update.mode=INSTALL");
        thenTheFileContains("update.installAt=EXIT_OR_NIGHT");
        thenTheFileContains("update.nightHours=01\\:00-04\\:30");
        thenTheFileDoesNotContain("#Mon");
    }

    @Test
    void shouldGiveTheDefaultsForAMissingFile() throws IOException {
        givenNoFileNamed("none");

        whenTheFileIsLoaded();

        thenTheLoadedPreferencesAre(Preferences.defaults());
    }

    @Test
    void shouldKeepTheDefaultsOfInvalidValuesAndApplyTheValidOnes() throws IOException {
        givenTheText("""
                net.tcpPort=99999
                net.udpPort=5000
                net.kad.enabled=maybe
                net.ed2k.enabled=off
                security.obfuscation=supported
                log.level=nope
                unknown.key=1
                dirs.temp=
                """);

        whenTheTextIsParsed();

        thenTheTcpPortIs(Preferences.defaults().tcpPort());
        thenTheUdpPortIs(5000);
        thenKadKeepsItsDefault();
        thenEd2kIsOff();
        thenTheObfuscationIs(ObfuscationMode.SUPPORTED);
        thenTheLogLevelKeepsItsDefault();
        thenThereIsNoTempDirectory();
    }

    @Test
    void shouldNotLetAByteOrderMarkHideTheFirstKey() throws IOException {
        givenTheFile("﻿net.tcpPort=12345\nnet.udpPort=5000\n");

        whenTheFileIsLoaded();

        thenTheTcpPortIs(12_345);
    }

    @Test
    void shouldLoseOnlyTheLineOfAMalformedEscape() throws IOException {
        givenTheFile("user.nick=bad\\u12\nnet.tcpPort=12345\nchat.filter=a\\\n  b\\u00e9\n");

        whenTheFileIsLoaded();

        thenTheNickKeepsItsDefault();
        thenTheTcpPortIs(12_345);
        thenTheMessageFilterIs("abé");
    }

    @Test
    void shouldKeepTheKeysOfANewerVersionThroughASave() throws IOException {
        givenTheFile("net.tcpPort=12345\nfuture.option=on: yes\n");

        whenTheFileIsLoadedAndSavedWithTheUdpPort(5000);

        thenTheFileHolds("future.option", "on: yes");
        thenTheFileHolds("net.udpPort", "5000");
        thenTheFileStillLoadsTheTcpPort(12_345);
    }

    @Test
    void shouldNotTakeFromTheOlderBackupThePreferencesHeldByAnotherProgram() throws IOException {
        givenPreferencesSavedWithTheTcpPort(1111);
        givenPreferencesSavedWithTheTcpPort(2222);
        givenTheFileIsHeldByAnotherProgram();

        whenTheFileIsLoaded();

        // Loaded from the backup, they would be saved over the newer file at the start.
        thenExceptionOccurred(IOException.class);
    }

    @Test
    void shouldLoadTheNewerPreferencesOnceTheOtherProgramLetsThemGo() throws IOException {
        givenPreferencesSavedWithTheTcpPort(1111);
        givenPreferencesSavedWithTheTcpPort(2222);
        givenALoadFailedWhileTheFileWasHeldByAnotherProgram();

        whenTheFileIsLoaded();

        thenTheTcpPortIs(2222);
    }

    @Test
    void shouldWriteASortedAndStableOutput() throws IOException {
        givenTheDefaultsFormattedOnce();

        whenTheDefaultsAreFormattedAgain();

        thenBothOutputsAreEqual();
        thenTheLinesAfterTheFirstAreSorted();
    }

    @Test
    void shouldReadWindowsLineEndsAndPaddedValues() throws IOException {
        givenTheText("net.tcpPort = 12345 \r\nnet.kad.enabled=false");

        whenTheTextIsParsed();

        thenTheTcpPortIs(12_345);
        thenKadIsOff();
    }

    @Property
    void shouldKeepTheProxyCredentialsThroughTheFile(@ForAll("text") String user, @ForAll("text") String password)
            throws IOException {
        givenPreferencesWithTheProxyCredentials(user, password);

        whenThePreferencesAreFormattedAndParsed();

        thenTheProxyIsKept();
    }

    // No lone surrogate: UTF-8 cannot keep one.
    @Provide
    Arbitrary<String> text() {
        return Arbitraries.strings()
                .withCharRange('\u0000', '퟿')
                .withCharRange('', '�')
                .ofMaxLength(40);
    }

    @Property
    void shouldLoadEscapedValuesBackUnchanged(@ForAll String value) throws IOException {
        whenTheEscapedValueIsLoadedAsAProperty(value);

        thenTheValueIsLoadedUnchanged();
    }

    private void givenPreferencesUnlikeTheDefaults() {
        this.preferences = Preferences.defaults().toBuilder()
                .nick("Ünïcødé = : # nick")
                .tcpPort(12_345)
                .kadEnabled(false)
                .obfuscation(ObfuscationMode.REQUIRED)
                .incomingDir(Optional.of(this.dir.resolve("in dir")))
                .logLevel("DEBUG")
                .upnp(false)
                .downloadCapacityKiBps(4096)
                .uploadCapacityKiBps(512)
                .minFreeSpaceMiB(2048)
                .proxy(new ProxySettings(ProxySettings.Type.SOCKS, "proxy.lan", 9050, "me", "pw"))
                .nameCleanups("dvdrip|sponsored")
                .searchSpamFilter(false)
                .cleanUpNewNames(true)
                .updateMode(UpdateMode.INSTALL)
                .updateInstallTime(UpdateInstallTime.EXIT_OR_NIGHT)
                .nightHours(NightHours.parse("01:00-04:30"))
                .updateUrl(Optional.of(URI.create("http://192.168.1.2:8000/njmule")))
                .updateChannel(
                        Preferences.defaults().updateChannel() == UpdateChannel.STABLE
                                ? UpdateChannel.PRERELEASE
                                : UpdateChannel.STABLE)
                .build();
    }

    private void givenPreferencesWithTheProxyCredentials(String user, String password) {
        this.preferences = Preferences.defaults().toBuilder()
                .proxy(new ProxySettings(ProxySettings.Type.SOCKS, "proxy.lan", 1080, user, password))
                .build();
    }

    private void givenPreferencesSavedWithTheTcpPort(int port) throws IOException {
        PreferencesCodec.save(
                this.file, Preferences.defaults().toBuilder().tcpPort(port).build());
    }

    private void givenNoFileNamed(String name) {
        this.file = this.dir.resolve(name);
    }

    private void givenTheFile(String content) throws IOException {
        Files.write(this.file, content.getBytes(StandardCharsets.UTF_8));
    }

    private void givenTheText(String content) {
        this.text = content;
    }

    private void givenTheFileIsHeldByAnotherProgram() throws IOException {
        this.held = HeldFile.hold(this.file);
    }

    private void givenALoadFailedWhileTheFileWasHeldByAnotherProgram() throws IOException {
        try (HeldFile _ = HeldFile.hold(this.file)) {
            assertThatThrownBy(() -> PreferencesCodec.load(this.file)).isInstanceOf(IOException.class);
        }
    }

    private void givenTheDefaultsFormattedOnce() throws IOException {
        StringWriter out = new StringWriter();
        PreferencesCodec.format(Preferences.defaults(), out);
        this.firstOutput = out.toString();
    }

    private void whenThePreferencesAreSaved() throws IOException {
        PreferencesCodec.save(this.file, this.preferences);
    }

    private void whenTheFileIsLoaded() {
        try {
            this.loaded = PreferencesCodec.load(this.file);
        } catch (IOException e) {
            this.exception = e;
        }
    }

    private void whenTheTextIsParsed() throws IOException {
        this.loaded = PreferencesCodec.parse(new StringReader(this.text));
    }

    private void whenTheFileIsLoadedAndSavedWithTheUdpPort(int port) throws IOException {
        PreferencesCodec.save(
                this.file,
                PreferencesCodec.load(this.file).toBuilder().udpPort(port).build());
    }

    private void whenTheDefaultsAreFormattedAgain() throws IOException {
        StringWriter out = new StringWriter();
        PreferencesCodec.format(Preferences.defaults(), out);
        this.secondOutput = out.toString();
    }

    private void whenThePreferencesAreFormattedAndParsed() throws IOException {
        StringWriter out = new StringWriter();
        PreferencesCodec.format(this.preferences, out);
        this.loaded = PreferencesCodec.parse(new StringReader(out.toString()));
    }

    private void whenTheEscapedValueIsLoadedAsAProperty(String original) throws IOException {
        this.propertyValue = original;
        Properties props = new Properties();
        StringBuilder line = new StringBuilder();
        PropertyLines.append(line, "k", original);
        props.load(new StringReader(line.toString()));
        this.loadedValue = props.getProperty("k");
    }

    private void thenTheFileLoadsThePreferencesBack() throws IOException {
        assertThat(PreferencesCodec.load(this.file)).isEqualTo(this.preferences);
    }

    private void thenTheFileContains(String part) throws IOException {
        assertThat(Files.readString(this.file)).contains(part);
    }

    private void thenTheFileDoesNotContain(String part) throws IOException {
        assertThat(Files.readString(this.file)).doesNotContain(part);
    }

    private void thenTheFileHolds(String key, String value) throws IOException {
        Properties saved = new Properties();
        try (Reader in = Files.newBufferedReader(this.file)) {
            saved.load(in);
        }
        assertThat(saved).containsEntry(key, value);
    }

    private void thenTheFileStillLoadsTheTcpPort(int port) throws IOException {
        assertThat(PreferencesCodec.load(this.file).tcpPort()).isEqualTo(port);
    }

    private void thenTheLoadedPreferencesAre(Preferences expected) {
        assertThat(this.exception).isNull();
        assertThat(this.loaded).isEqualTo(expected);
    }

    private void thenTheTcpPortIs(int port) {
        assertThat(this.exception).isNull();
        assertThat(this.loaded.tcpPort()).isEqualTo(port);
    }

    private void thenTheUdpPortIs(int port) {
        assertThat(this.loaded.udpPort()).isEqualTo(port);
    }

    private void thenKadKeepsItsDefault() {
        assertThat(this.loaded.kadEnabled()).isEqualTo(Preferences.defaults().kadEnabled());
    }

    private void thenKadIsOff() {
        assertThat(this.loaded.kadEnabled()).isFalse();
    }

    private void thenEd2kIsOff() {
        assertThat(this.loaded.ed2kEnabled()).isFalse();
    }

    private void thenTheObfuscationIs(ObfuscationMode mode) {
        assertThat(this.loaded.obfuscation()).isEqualTo(mode);
    }

    private void thenTheLogLevelKeepsItsDefault() {
        assertThat(this.loaded.logLevel()).isEqualTo(Preferences.defaults().logLevel());
    }

    private void thenThereIsNoTempDirectory() {
        assertThat(this.loaded.tempDir()).isEmpty();
    }

    private void thenTheNickKeepsItsDefault() {
        assertThat(this.loaded.nick()).isEqualTo(Preferences.defaults().nick());
    }

    private void thenTheMessageFilterIs(String filter) {
        assertThat(this.loaded.messageFilter()).isEqualTo(filter);
    }

    private void thenTheProxyIsKept() {
        assertThat(this.loaded.proxy()).isEqualTo(this.preferences.proxy());
    }

    private void thenBothOutputsAreEqual() {
        assertThat(this.firstOutput).isEqualTo(this.secondOutput);
    }

    private void thenTheLinesAfterTheFirstAreSorted() {
        assertThat(this.firstOutput.lines().skip(1).toList()).isSorted();
    }

    private void thenTheValueIsLoadedUnchanged() {
        assertThat(this.loadedValue).isEqualTo(this.propertyValue);
    }

    private void thenExceptionOccurred(Class<? extends Exception> type) {
        assertThat(this.exception).isInstanceOf(type);
    }
}
