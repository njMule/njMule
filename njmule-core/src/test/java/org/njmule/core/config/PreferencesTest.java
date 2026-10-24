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
package org.njmule.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PreferencesTest {

    @TempDir
    Path dir;

    private Preferences preferences;
    private ObfuscationMode obfuscation;
    private Throwable exception;

    @Test
    void shouldHaveValidDefaultsWithTheNjMulePorts() {
        whenTheDefaultsAreTaken();

        thenTheTcpPortIs(4662);
        thenTheUdpPortIs(4672);
        thenTheObfuscationIs(ObfuscationMode.REQUESTED);
        thenTheRouterForwardsThePorts();
        thenTheyBuildBackToTheSame();
    }

    @Test
    void shouldChangeEveryFieldThroughTheBuilder() {
        whenEveryFieldIsChangedThroughTheBuilder();

        thenTheNickIs("Tester");
        thenUdpIsOff();
        thenTheMaxSourcesPerFileAre(40);
        thenTheEffectiveIncomingDirectoryIs("in");
        thenTheEffectiveTempDirectoryIs("tmp");
    }

    @ParameterizedTest
    @CsvSource({"REQUESTED, NONE, true", "REQUIRED, NONE, false", "SUPPORTED, SOCKS, false"})
    void shouldMeetFirewalledClientsThroughRoutersOnlyWithoutProxyNorRequiredObfuscation(
            ObfuscationMode mode, ProxySettings.Type proxy, boolean traversed) {
        whenTheObfuscationAndProxyAre(mode, proxy);

        thenRoutersAreTraversed(traversed);
    }

    @Test
    void shouldUseTheTempDirectoryOfTheProfileByDefault() {
        whenTheDefaultsAreTaken();

        thenTheEffectiveTempDirectoryIsTheOneOfTheProfile();
    }

    @ParameterizedTest
    @ValueSource(ints = {0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069})
    void shouldNotLetANickReverseOrIsolateTheTextAroundIt(int codePoint) {
        whenTheNickIsSet("abc" + Character.toString(codePoint) + "def");

        thenTheNickIsRefusedBecauseOf(codePoint);
    }

    @Test
    void shouldKeepAJoinerInANick() {
        whenTheNickIsSet("Nicolò 名前 a" + Character.toString(0x200D) + "b");

        thenTheNickIs("Nicolò 名前 a" + Character.toString(0x200D) + "b");
    }

    @Test
    void shouldRejectABlankNick() {
        whenTheNickIsSet(" ");

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectAControlCharacterInANick() {
        whenTheNickIsSet("a\u0001b");

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectATooLongNick() {
        whenTheNickIsSet("x".repeat(51));

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectAnInvalidTcpPort() {
        whenTheTcpPortIsSet(0);

        thenTheValueIsRefusedNaming("tcpPort");
    }

    @Test
    void shouldRejectAnInvalidUdpPort() {
        whenTheUdpPortIsSet(70_000);

        thenTheValueIsRefusedNaming("udpPort");
    }

    @Test
    void shouldRejectAnInvalidMaximumOfConnections() {
        whenTheMaxConnectionsAreSet(0);

        thenTheValueIsRefusedNaming("maxConnections");
    }

    @Test
    void shouldRejectAnUnknownLogLevel() {
        whenTheLogLevelIsSet("LOUD");

        thenTheValueIsRefusedNaming("logLevel");
    }

    @Test
    void shouldRejectARelativeTempDirectory() {
        whenTheTempDirectoryIsSet(Path.of("rel"));

        thenTheValueIsRefusedNaming("tempDir");
    }

    @ParameterizedTest
    @CsvSource({
        "false, true, true, DISABLED",
        "true, false, false, SUPPORTED",
        "true, true, false, REQUESTED",
        "true, false, true, REQUIRED"
    })
    void shouldMapObfuscationFlags(boolean supported, boolean requested, boolean required, ObfuscationMode expected) {
        whenTheObfuscationFlagsAreMapped(supported, requested, required);

        thenTheMappedObfuscationIs(expected);
    }

    private void whenTheDefaultsAreTaken() {
        this.preferences = Preferences.defaults();
    }

    private void whenEveryFieldIsChangedThroughTheBuilder() {
        this.preferences = Preferences.defaults().toBuilder()
                .nick("  Tester  ")
                .tcpPort(1)
                .udpPort(65_535)
                .udpEnabled(false)
                .ed2kEnabled(false)
                .kadEnabled(false)
                .autoConnect(false)
                .reconnect(false)
                .maxUploadKiBps(10)
                .maxDownloadKiBps(20)
                .maxConnections(30)
                .maxSourcesPerFile(40)
                .obfuscation(ObfuscationMode.REQUIRED)
                .secureIdent(false)
                .creditSystem(false)
                .incomingDir(Optional.of(this.dir.resolve("in")))
                .tempDir(Optional.of(this.dir.resolve("tmp")))
                .logLevel("DEBUG")
                .build();
    }

    private void whenTheNickIsSet(String nick) {
        this.exception = catchThrowable(() ->
                this.preferences = Preferences.defaults().toBuilder().nick(nick).build());
    }

    private void whenTheTcpPortIsSet(int port) {
        this.exception = catchThrowable(() -> this.preferences =
                Preferences.defaults().toBuilder().tcpPort(port).build());
    }

    private void whenTheUdpPortIsSet(int port) {
        this.exception = catchThrowable(() -> this.preferences =
                Preferences.defaults().toBuilder().udpPort(port).build());
    }

    private void whenTheMaxConnectionsAreSet(int maxConnections) {
        this.exception = catchThrowable(() -> this.preferences = Preferences.defaults().toBuilder()
                .maxConnections(maxConnections)
                .build());
    }

    private void whenTheLogLevelIsSet(String logLevel) {
        this.exception = catchThrowable(() -> this.preferences =
                Preferences.defaults().toBuilder().logLevel(logLevel).build());
    }

    private void whenTheTempDirectoryIsSet(Path tempDir) {
        this.exception = catchThrowable(() -> this.preferences =
                Preferences.defaults().toBuilder().tempDir(Optional.of(tempDir)).build());
    }

    private void whenTheObfuscationFlagsAreMapped(boolean supported, boolean requested, boolean required) {
        this.obfuscation = ObfuscationMode.fromFlags(supported, requested, required);
    }

    private void whenTheObfuscationAndProxyAre(ObfuscationMode mode, ProxySettings.Type proxy) {
        this.preferences = Preferences.defaults().toBuilder()
                .obfuscation(mode)
                .proxy(new ProxySettings(proxy, proxy == ProxySettings.Type.NONE ? "" : "proxy.example", 1080, "", ""))
                .build();
    }

    private void thenRoutersAreTraversed(boolean expected) {
        assertThat(this.preferences.routerTraversal()).isEqualTo(expected);
    }

    private void thenTheTcpPortIs(int expected) {
        assertThat(this.preferences.tcpPort()).isEqualTo(expected);
    }

    private void thenTheUdpPortIs(int expected) {
        assertThat(this.preferences.udpPort()).isEqualTo(expected);
    }

    private void thenTheObfuscationIs(ObfuscationMode expected) {
        assertThat(this.preferences.obfuscation()).isEqualTo(expected);
    }

    private void thenTheRouterForwardsThePorts() {
        assertThat(this.preferences.upnp())
                .as("the router forwards our ports unless turned off")
                .isTrue();
    }

    private void thenTheyBuildBackToTheSame() {
        assertThat(this.preferences.toBuilder().build()).isEqualTo(this.preferences);
    }

    private void thenTheNickIs(String expected) {
        assertThat(this.exception).isNull();
        assertThat(this.preferences.nick()).isEqualTo(expected);
    }

    private void thenUdpIsOff() {
        assertThat(this.preferences.udpEnabled()).isFalse();
    }

    private void thenTheMaxSourcesPerFileAre(int expected) {
        assertThat(this.preferences.maxSourcesPerFile()).isEqualTo(expected);
    }

    private void thenTheEffectiveIncomingDirectoryIs(String name) {
        assertThat(this.preferences.effectiveIncomingDir(AppDirectories.underRoot(this.dir, false)))
                .isEqualTo(this.dir.resolve(name));
    }

    private void thenTheEffectiveTempDirectoryIs(String name) {
        assertThat(this.preferences.effectiveTempDir(AppDirectories.underRoot(this.dir, false)))
                .isEqualTo(this.dir.resolve(name));
    }

    private void thenTheEffectiveTempDirectoryIsTheOneOfTheProfile() {
        AppDirectories dirs = AppDirectories.underRoot(this.dir, false);
        assertThat(this.preferences.effectiveTempDir(dirs)).isEqualTo(dirs.temp());
    }

    private void thenTheNickIsRefusedBecauseOf(int codePoint) {
        assertThat(this.exception).as("U+%04X", codePoint).isInstanceOf(IllegalArgumentException.class);
    }

    private void thenTheValueIsRefusedNaming(String field) {
        assertThat(this.exception).hasMessageContaining(field);
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private void thenTheMappedObfuscationIs(ObfuscationMode expected) {
        assertThat(this.obfuscation).isEqualTo(expected);
    }
}
