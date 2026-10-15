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
package org.njmule.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.ImportOptions;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.persist.EmuleImporter;

/** Every import of an eMule profile leaves a manifest of the files it read, their SHA-256 and what became of them. */
@Timeout(60)
class EngineImportManifestTest {

    private static final byte[] PREFERENCES = "[eMule]\r\nNick=Tester\r\n".getBytes(StandardCharsets.US_ASCII);

    @TempDir
    Path dir;

    private AppDirectories dirs;
    private Path emule;
    private NjMuleEngine engine;

    @AfterEach
    void close() {
        if (this.engine != null) {
            this.engine.close();
        }
    }

    @Test
    void shouldListEveryFileReadWithItsSha256AndOutcome() throws Exception {
        givenAnEmuleProfileWithItsPreferences();
        givenNjMuleRunning();
        whenTheProfileIsImported(false);
        thenTheManifestSays("IMPORTED\t" + PREFERENCES.length + "\t" + sha256(PREFERENCES) + "\tpreferences.ini\t");
        thenTheManifestSays("MISSING\t\t\tserver.met\t");
    }

    @Test
    void shouldLeaveNoManifestAfterADryRun() throws Exception {
        givenAnEmuleProfileWithItsPreferences();
        givenNjMuleRunning();
        whenTheProfileIsImported(true);
        thenThereIsNoManifest();
    }

    private void givenAnEmuleProfileWithItsPreferences() throws IOException {
        this.emule = Files.createDirectories(this.dir.resolve("emule"));
        Files.write(this.emule.resolve("preferences.ini"), PREFERENCES);
    }

    private void givenNjMuleRunning() throws IOException {
        this.dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), false);
        Files.createDirectories(this.dirs.config());
        try (ServerSocket probe = new ServerSocket(0)) {
            Files.writeString(
                    this.dirs.config().resolve("njmule.properties"),
                    "net.tcpPort=" + probe.getLocalPort() + "\nnet.ed2k.enabled=false\nnet.kad.enabled=false\n");
        }
        this.engine = Engine.start(new EngineConfig(this.dirs), () -> false);
    }

    private void whenTheProfileIsImported(boolean dryRun) throws Exception {
        this.engine
                .importFromEmule(this.emule, new ImportOptions(dryRun, false, false))
                .get(30, TimeUnit.SECONDS);
    }

    private void thenTheManifestSays(String line) throws IOException {
        assertThat(manifests())
                .singleElement()
                .satisfies(manifest -> assertThat(Files.readAllLines(manifest, StandardCharsets.UTF_8))
                        .anyMatch(l -> l.startsWith(line)));
    }

    private void thenThereIsNoManifest() throws IOException {
        assertThat(manifests()).isEmpty();
    }

    private List<Path> manifests() throws IOException {
        Path folder = this.dirs.config().resolve(EmuleImporter.MANIFESTS);
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(f -> f.toString().endsWith(".txt")).toList();
        }
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
