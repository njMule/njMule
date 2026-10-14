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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigBackupTest {

    @TempDir
    Path dir;

    @Test
    void copiesTheConfigurationAndKeepsTheLatestOnes() throws Exception {
        Path config = Files.createDirectories(this.dir.resolve("config"));
        Files.writeString(config.resolve("njmule.properties"), "net.tcpPort=4662\n");
        Files.write(config.resolve("server.met"), new byte[] {(byte) 0xE0, 0, 0, 0, 0});
        for (String left : List.of("njmule.lock", "handoff", "known2_64.met", "server.met.bak", "known.met.tmp")) {
            Files.writeString(config.resolve(left), "x");
        }
        Path folder = this.dir.resolve("data").resolve(ConfigBackup.FOLDER);

        Path first = ConfigBackup.write(config, folder, 2, at("2026-10-13T20:00:00Z"));
        ConfigBackup.write(config, folder, 2, at("2026-10-14T20:00:00Z"));
        Path last = ConfigBackup.write(config, folder, 2, at("2026-10-15T20:00:00Z"));

        assertThat(ConfigBackup.copies(folder)).hasSize(2).last().isEqualTo(last);
        assertThat(first).doesNotExist();
        assertThat(last.getFileName().toString()).isEqualTo("njmule-config-20261015-200000.zip");
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(last))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                names.add(e.getName());
            }
        }
        assertThat(names).containsExactly("njmule.properties", "server.met");
    }

    @Test
    void theCopyJustWrittenIsNeverPruned() throws Exception {
        Path config = Files.createDirectories(this.dir.resolve("config"));
        Files.writeString(config.resolve("njmule.properties"), "net.tcpPort=4662\n");
        Path folder = this.dir.resolve(ConfigBackup.FOLDER);
        Path mine = Files.writeString(Files.createDirectories(folder).resolve("njmule-config-mine.zip"), "x");

        ConfigBackup.write(config, folder, 1, at("2026-10-15T20:00:00Z"));
        // The clock went back.
        Path last = ConfigBackup.write(config, folder, 1, at("2026-10-14T20:00:00Z"));

        assertThat(last).exists();
        assertThat(ConfigBackup.copies(folder)).containsExactly(last);
        assertThat(mine).as("not one of ours").exists();
    }

    @Test
    void copiesAreNamedInUtc() throws Exception {
        Path config = Files.createDirectories(this.dir.resolve("config"));
        Clock rome = Clock.fixed(Instant.parse("2026-10-25T00:30:00Z"), ZoneId.of("Europe/Rome"));

        Path copy = ConfigBackup.write(config, this.dir.resolve(ConfigBackup.FOLDER), 3, rome);

        assertThat(copy.getFileName().toString()).isEqualTo("njmule-config-20261025-003000.zip");
    }

    @Test
    void copiesAreForTheirOwnerOnly() throws Exception {
        Path config = Files.createDirectories(this.dir.resolve("config"));
        assumeTrue(config.getFileSystem().supportedFileAttributeViews().contains("posix"), "POSIX permissions");

        Path copy = ConfigBackup.write(config, this.dir.resolve(ConfigBackup.FOLDER), 1, at("2026-10-15T20:00:00Z"));

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(copy)))
                .isEqualTo("rw-------");
    }

    @Test
    void atLeastOneCopyIsKept() throws Exception {
        Path config = Files.createDirectories(this.dir.resolve("config"));
        Path folder = this.dir.resolve(ConfigBackup.FOLDER);

        for (int keep : new int[] {0, -1}) {
            assertThatThrownBy(() -> ConfigBackup.write(config, folder, keep, at("2026-10-15T20:00:00Z")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(ConfigBackup.copies(folder)).isEmpty();
    }

    private static Clock at(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }
}
