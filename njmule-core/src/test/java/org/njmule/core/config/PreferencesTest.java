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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreferencesTest {

    @TempDir
    Path dir;

    @Test
    void defaultsAreValidAndUseNjMulePorts() {
        Preferences p = Preferences.defaults();
        assertThat(p.tcpPort()).isEqualTo(4662);
        assertThat(p.udpPort()).isEqualTo(4672);
        assertThat(p.obfuscation()).isEqualTo(ObfuscationMode.REQUESTED);
        assertThat(p.upnp())
                .as("the router forwards our ports unless turned off")
                .isTrue();
        assertThat(p.toBuilder().build()).isEqualTo(p);
    }

    @Test
    void builderChangesEveryField() {
        Path incoming = this.dir.resolve("in");
        Path temp = this.dir.resolve("tmp");
        Preferences p = Preferences.defaults().toBuilder()
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
                .incomingDir(Optional.of(incoming))
                .tempDir(Optional.of(temp))
                .logLevel("DEBUG")
                .build();

        assertThat(p.nick()).isEqualTo("Tester");
        assertThat(p.udpEnabled()).isFalse();
        assertThat(p.maxSourcesPerFile()).isEqualTo(40);
        AppDirectories dirs = AppDirectories.underRoot(this.dir, false);
        assertThat(p.effectiveIncomingDir(dirs)).isEqualTo(incoming);
        assertThat(p.effectiveTempDir(dirs)).isEqualTo(temp);
        assertThat(Preferences.defaults().effectiveTempDir(dirs)).isEqualTo(dirs.temp());
    }

    @Test
    void rejectsInvalidValues() {
        Preferences.Builder b = Preferences.defaults().toBuilder();
        assertThatThrownBy(() -> b.nick(" ").build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        Preferences.defaults().toBuilder().nick("a\u0001b").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        Preferences.defaults().toBuilder().nick("x".repeat(51)).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Preferences.defaults().toBuilder().tcpPort(0).build())
                .hasMessageContaining("tcpPort");
        assertThatThrownBy(
                        () -> Preferences.defaults().toBuilder().udpPort(70_000).build())
                .hasMessageContaining("udpPort");
        assertThatThrownBy(() ->
                        Preferences.defaults().toBuilder().maxConnections(0).build())
                .hasMessageContaining("maxConnections");
        assertThatThrownBy(() ->
                        Preferences.defaults().toBuilder().logLevel("LOUD").build())
                .hasMessageContaining("logLevel");
        assertThatThrownBy(() -> Preferences.defaults().toBuilder()
                        .tempDir(Optional.of(Path.of("rel")))
                        .build())
                .hasMessageContaining("tempDir");
    }

    @Test
    void mapsObfuscationFlags() {
        assertThat(ObfuscationMode.fromFlags(false, true, true)).isEqualTo(ObfuscationMode.DISABLED);
        assertThat(ObfuscationMode.fromFlags(true, false, false)).isEqualTo(ObfuscationMode.SUPPORTED);
        assertThat(ObfuscationMode.fromFlags(true, true, false)).isEqualTo(ObfuscationMode.REQUESTED);
        assertThat(ObfuscationMode.fromFlags(true, false, true)).isEqualTo(ObfuscationMode.REQUIRED);
    }
}
