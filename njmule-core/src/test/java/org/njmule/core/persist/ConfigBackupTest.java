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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigBackupTest {

    @TempDir
    Path dir;

    private Path config;
    private Path folder;
    private ZoneId zone = ZoneOffset.UTC;
    private Path firstCopy;
    private Path foreignFile;
    private Path copy;
    private RuntimeException exception;

    @BeforeEach
    void setUp() {
        this.folder = this.dir.resolve(ConfigBackup.FOLDER);
    }

    @Test
    void shouldCopyTheConfigurationAndKeepTheLatestOnes() throws Exception {
        givenTheConfigurationFile("njmule.properties", "net.tcpPort=4662\n");
        givenTheConfigurationFile("server.met", new byte[] {(byte) 0xE0, 0, 0, 0, 0});
        givenAConfigurationWith("njmule.lock", "handoff", "known2_64.met", "server.met.bak", "known.met.tmp");
        givenTheCopiesAreKeptUnder("data");
        givenTheFirstCopyWrittenAt("2026-10-13T20:00:00Z", 2);
        givenACopyWrittenAt("2026-10-14T20:00:00Z", 2);

        whenACopyIsWrittenAt("2026-10-15T20:00:00Z", 2);

        thenTheCopiesAreSoManyAndEndWithTheNewOne(2);
        thenTheFirstCopyIsGone();
        thenTheCopyIsNamed("njmule-config-20261015-200000.zip");
        thenTheCopyHolds("njmule.properties", "server.met");
    }

    @Test
    void shouldNeverPruneTheCopyJustWritten() throws Exception {
        givenTheConfigurationFile("njmule.properties", "net.tcpPort=4662\n");
        givenAFileNotOfOursInTheBackupFolder("njmule-config-mine.zip");
        givenACopyWrittenAt("2026-10-15T20:00:00Z", 1);

        // The clock went back.
        whenACopyIsWrittenAt("2026-10-14T20:00:00Z", 1);

        thenTheCopyExists();
        thenTheOnlyCopyIsTheNewOne();
        thenTheFileNotOfOursIsStillThere();
    }

    @Test
    void shouldNameTheCopiesInUtc() throws Exception {
        givenAConfigurationWith();
        givenTheTimeZone("Europe/Rome");

        whenACopyIsWrittenAt("2026-10-25T00:30:00Z", 3);

        thenTheCopyIsNamed("njmule-config-20261025-003000.zip");
    }

    @Test
    void shouldMakeTheCopiesForTheirOwnerOnly() throws Exception {
        givenAConfigurationWith();
        givenTheFileSystemHasPosixPermissions();

        whenACopyIsWrittenAt("2026-10-15T20:00:00Z", 1);

        thenTheCopyHasThePermissions("rw-------");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void shouldKeepAtLeastOneCopy(int kept) throws Exception {
        givenAConfigurationWith();

        whenACopyIsWrittenAt("2026-10-15T20:00:00Z", kept);

        thenExceptionOccurred(IllegalArgumentException.class);
        thenThereAreNoCopies();
    }

    @Test
    void shouldLeaveTheKadIndexOutOfTheCopy() throws Exception {
        givenAConfigurationWith("nodes.dat", "key_index.dat", "src_index.dat", "notes_index.dat", "load_index.dat");

        whenACopyIsWritten();

        thenTheCopyHolds("nodes.dat");
    }

    private void givenAConfigurationWith(String... files) throws Exception {
        this.config = Files.createDirectories(this.dir.resolve("config"));
        for (String file : files) {
            Files.writeString(this.config.resolve(file), "x");
        }
    }

    private void givenTheConfigurationFile(String name, String content) throws Exception {
        givenAConfigurationWith();
        Files.writeString(this.config.resolve(name), content);
    }

    private void givenTheConfigurationFile(String name, byte[] content) throws Exception {
        givenAConfigurationWith();
        Files.write(this.config.resolve(name), content);
    }

    private void givenTheCopiesAreKeptUnder(String parent) {
        this.folder = this.dir.resolve(parent).resolve(ConfigBackup.FOLDER);
    }

    private void givenAFileNotOfOursInTheBackupFolder(String name) throws Exception {
        this.foreignFile =
                Files.writeString(Files.createDirectories(this.folder).resolve(name), "x");
    }

    private void givenTheTimeZone(String zoneId) {
        this.zone = ZoneId.of(zoneId);
    }

    private void givenTheFileSystemHasPosixPermissions() {
        assumeTrue(this.config.getFileSystem().supportedFileAttributeViews().contains("posix"), "POSIX permissions");
    }

    private void givenTheFirstCopyWrittenAt(String instant, int kept) throws Exception {
        givenACopyWrittenAt(instant, kept);
        this.firstCopy = this.copy;
    }

    private void givenACopyWrittenAt(String instant, int kept) throws Exception {
        this.copy = ConfigBackup.write(this.config, this.folder, kept, clockAt(instant));
    }

    private void whenACopyIsWritten() throws Exception {
        whenACopyIsWrittenAt("2026-10-15T20:00:00Z", 1);
    }

    private void whenACopyIsWrittenAt(String instant, int kept) throws Exception {
        try {
            this.copy = ConfigBackup.write(this.config, this.folder, kept, clockAt(instant));
        } catch (RuntimeException e) {
            this.exception = e;
        }
    }

    private void thenTheCopiesAreSoManyAndEndWithTheNewOne(int count) throws Exception {
        assertThat(ConfigBackup.copies(this.folder)).hasSize(count).last().isEqualTo(this.copy);
    }

    private void thenTheOnlyCopyIsTheNewOne() throws Exception {
        assertThat(ConfigBackup.copies(this.folder)).containsExactly(this.copy);
    }

    private void thenThereAreNoCopies() throws Exception {
        assertThat(ConfigBackup.copies(this.folder)).isEmpty();
    }

    private void thenTheFirstCopyIsGone() {
        assertThat(this.firstCopy).doesNotExist();
    }

    private void thenTheCopyExists() {
        assertThat(this.copy).exists();
    }

    private void thenTheFileNotOfOursIsStillThere() {
        assertThat(this.foreignFile).as("not one of ours").exists();
    }

    private void thenTheCopyIsNamed(String name) {
        assertThat(this.copy.getFileName().toString()).isEqualTo(name);
    }

    private void thenTheCopyHasThePermissions(String permissions) throws Exception {
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(this.copy)))
                .isEqualTo(permissions);
    }

    private void thenTheCopyHolds(String... files) throws Exception {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(this.copy))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                names.add(e.getName());
            }
        }
        assertThat(names).containsExactly(files);
    }

    private void thenExceptionOccurred(Class<? extends RuntimeException> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private Clock clockAt(String instant) {
        return Clock.fixed(Instant.parse(instant), this.zone);
    }
}
