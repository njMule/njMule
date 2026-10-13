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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
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

    private static Clock at(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }
}
