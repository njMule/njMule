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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.config.Preferences;
import org.njmule.core.config.ProxySettings;

class PreferencesCodecTest {

    @TempDir
    Path dir;

    @Test
    void roundTripsThroughTheFile() throws IOException {
        Path file = this.dir.resolve(PreferencesCodec.FILE_NAME);
        Preferences p = Preferences.defaults().toBuilder()
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
                .build();

        PreferencesCodec.save(file, p);

        assertThat(PreferencesCodec.load(file)).isEqualTo(p);
        assertThat(Files.readString(file)).contains("net.tcpPort=12345").doesNotContain("#Mon");
    }

    @Test
    void missingFileMeansDefaults() throws IOException {
        assertThat(PreferencesCodec.load(this.dir.resolve("none"))).isEqualTo(Preferences.defaults());
    }

    @Test
    void invalidValuesKeepTheirDefaultsAndValidOnesApply() throws IOException {
        String text = """
                net.tcpPort=99999
                net.udpPort=5000
                net.kad.enabled=maybe
                net.ed2k.enabled=off
                security.obfuscation=supported
                log.level=nope
                unknown.key=1
                dirs.temp=
                """;

        Preferences p = PreferencesCodec.parse(new StringReader(text));

        Preferences d = Preferences.defaults();
        assertThat(p.tcpPort()).isEqualTo(d.tcpPort());
        assertThat(p.udpPort()).isEqualTo(5000);
        assertThat(p.kadEnabled()).isEqualTo(d.kadEnabled());
        assertThat(p.ed2kEnabled()).isFalse();
        assertThat(p.obfuscation()).isEqualTo(ObfuscationMode.SUPPORTED);
        assertThat(p.logLevel()).isEqualTo(d.logLevel());
        assertThat(p.tempDir()).isEmpty();
    }

    @Test
    void aByteOrderMarkDoesNotHideTheFirstKey() throws IOException {
        Path file = this.dir.resolve(PreferencesCodec.FILE_NAME);
        Files.write(file, ("﻿net.tcpPort=12345\nnet.udpPort=5000\n").getBytes(StandardCharsets.UTF_8));

        assertThat(PreferencesCodec.load(file).tcpPort()).isEqualTo(12_345);
    }

    @Test
    void aMalformedEscapeCostsOnlyItsLine() throws IOException {
        Path file = this.dir.resolve(PreferencesCodec.FILE_NAME);
        Files.writeString(file, "user.nick=bad\\u12\nnet.tcpPort=12345\nchat.filter=a\\\n  b\\u00e9\n");

        Preferences p = PreferencesCodec.load(file);

        assertThat(p.nick()).isEqualTo(Preferences.defaults().nick());
        assertThat(p.tcpPort()).isEqualTo(12_345);
        assertThat(p.messageFilter()).isEqualTo("abé");
    }

    @Test
    void keysOfANewerVersionSurviveASave() throws IOException {
        Path file = this.dir.resolve(PreferencesCodec.FILE_NAME);
        Files.writeString(file, "net.tcpPort=12345\nfuture.option=on: yes\n");

        PreferencesCodec.save(
                file, PreferencesCodec.load(file).toBuilder().udpPort(5000).build());

        Properties saved = new Properties();
        try (Reader in = Files.newBufferedReader(file)) {
            saved.load(in);
        }
        assertThat(saved).containsEntry("future.option", "on: yes").containsEntry("net.udpPort", "5000");
        assertThat(PreferencesCodec.load(file).tcpPort()).isEqualTo(12_345);
    }

    @Test
    void preferencesHeldByAnotherProgramAreNotTakenFromTheOlderBackup() throws Exception {
        Path file = this.dir.resolve(PreferencesCodec.FILE_NAME);
        PreferencesCodec.save(
                file, Preferences.defaults().toBuilder().tcpPort(1111).build());
        PreferencesCodec.save(
                file, Preferences.defaults().toBuilder().tcpPort(2222).build());

        try (HeldFile _ = HeldFile.hold(file)) {
            // Loaded from the backup, they would be saved over the newer file at the start.
            assertThatThrownBy(() -> PreferencesCodec.load(file)).isInstanceOf(IOException.class);
        }

        assertThat(PreferencesCodec.load(file).tcpPort()).isEqualTo(2222);
    }

    @Test
    void outputIsSortedAndStable() throws IOException {
        StringWriter a = new StringWriter();
        StringWriter b = new StringWriter();
        PreferencesCodec.format(Preferences.defaults(), a);
        PreferencesCodec.format(Preferences.defaults(), b);

        assertThat(a.toString()).isEqualTo(b.toString());
        assertThat(a.toString().lines().skip(1).toList()).isSorted();
    }

    @Test
    void windowsLineEndsAndPaddedValuesAreRead() throws IOException {
        Preferences p = PreferencesCodec.parse(new StringReader("net.tcpPort = 12345 \r\nnet.kad.enabled=false"));

        assertThat(p.tcpPort()).isEqualTo(12_345);
        assertThat(p.kadEnabled()).isFalse();
    }

    @Property
    void proxyCredentialsSurviveTheFile(@ForAll("text") String user, @ForAll("text") String password)
            throws IOException {
        Preferences p = Preferences.defaults().toBuilder()
                .proxy(new ProxySettings(ProxySettings.Type.SOCKS, "proxy.lan", 1080, user, password))
                .build();
        StringWriter file = new StringWriter();
        PreferencesCodec.format(p, file);

        assertThat(PreferencesCodec.parse(new StringReader(file.toString())).proxy())
                .isEqualTo(p.proxy());
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
    void escapedValuesLoadBackUnchanged(@ForAll String value) throws IOException {
        Properties props = new Properties();
        props.load(new StringReader("k=" + PreferencesCodec.escape(value)));
        assertThat(props.getProperty("k")).isEqualTo(value);
    }
}
