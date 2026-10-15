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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Hash16;
import org.njmule.core.persist.format.PreferencesDat;

class UserHashStoreTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    @TempDir
    Path dir;

    private Hash16 expectedHash;
    private byte[] fileContent;
    private HeldFile held;
    private Hash16 hash;
    private IOException exception;

    @AfterEach
    void releaseTheFile() throws IOException {
        if (this.held != null) {
            this.held.close();
        }
    }

    @Test
    void shouldCreateAMarkedHashOnceAndKeepIt() throws Exception {
        givenTheUserHashLoadedOnce();

        whenTheUserHashIsLoaded();

        thenTheHashIsKept();
        thenTheHashIsMarked();
        thenTheFileHoldsTheHash();
        thenTheFileStartsWithTheVersion();
    }

    @Test
    void shouldReplaceAnUnusableHash() throws Exception {
        givenAFileWithAnUnusableHash();

        whenTheUserHashIsLoaded();

        thenTheHashIsUsable();
    }

    @Test
    void shouldReplaceACorruptHashWithoutBackup() throws Exception {
        givenAFileWithAnUnusableHash();
        givenTheUserHashLoadedOnce();
        givenTheFileIsCorrupt();
        givenTheBackupIsGone();

        whenTheUserHashIsLoaded();

        thenTheHashIsUsable();
    }

    @Test
    void shouldGiveWayToTheBackupOfACorruptFile() throws Exception {
        givenTheUserHashLoadedOnce();
        givenABackupOfTheFile();
        givenTheFileIsCorrupt();

        whenTheUserHashIsLoaded();

        thenTheHashIsKept();
    }

    @Test
    void shouldKeepTheHashOfTheEmuleFileWithItsWindowPlacement() throws Exception {
        givenAnEmuleFileWithItsWindowPlacement();
        givenTheLeftoverOfAnInterruptedWrite();

        whenTheUserHashIsLoaded();

        thenTheHashIsKept();
        thenTheFileIsUnchanged();
        thenTheLeftoverIsGone();
    }

    @Test
    void shouldNotReplaceAUserHashHeldByAnotherProgram() throws Exception {
        givenTheUserHashLoadedOnce();
        givenTheFileIsHeldByAnotherProgram();

        whenTheUserHashIsLoaded();

        thenExceptionOccurred(IOException.class);
    }

    @Test
    void shouldKeepTheUserHashOnceTheOtherProgramLetsItGo() throws Exception {
        givenTheUserHashLoadedOnce();
        givenALoadFailedWhileTheFileWasHeldByAnotherProgram();

        whenTheUserHashIsLoaded();

        thenTheHashIsKept();
    }

    @Test
    void shouldMakeTheUserHashForItsOwnerOnly() throws Exception {
        givenTheFileSystemHasPosixPermissions();

        whenTheUserHashIsLoaded();

        thenTheFileHasThePermissions("rw-------");
    }

    private void givenTheUserHashLoadedOnce() throws IOException {
        this.expectedHash = UserHashStore.loadOrCreate(this.dir, RANDOM);
    }

    private void givenAFileWithAnUnusableHash() throws IOException {
        byte[] zero = new byte[17];
        zero[0] = PreferencesDat.VERSION;
        zero[1 + 5] = 14;
        zero[1 + 14] = 111;
        Files.write(file(), zero);
    }

    private void givenTheFileIsCorrupt() throws IOException {
        Files.write(file(), new byte[3]);
    }

    private void givenTheBackupIsGone() throws IOException {
        Files.deleteIfExists(AtomicFiles.bakPath(file()));
    }

    private void givenABackupOfTheFile() throws IOException {
        Files.copy(file(), AtomicFiles.bakPath(file()));
    }

    private void givenAnEmuleFileWithItsWindowPlacement() throws IOException {
        this.expectedHash = UserHashStore.generate(RANDOM);
        this.fileContent = new byte[61];
        this.fileContent[0] = PreferencesDat.VERSION;
        this.expectedHash.copyTo(this.fileContent, 1);
        this.fileContent[60] = 7;
        Files.write(file(), this.fileContent);
    }

    private void givenTheLeftoverOfAnInterruptedWrite() throws IOException {
        Files.writeString(AtomicFiles.tmpPath(file()), "interrupted");
    }

    private void givenTheFileIsHeldByAnotherProgram() throws IOException {
        this.held = HeldFile.hold(file());
    }

    private void givenALoadFailedWhileTheFileWasHeldByAnotherProgram() throws IOException {
        try (HeldFile _ = HeldFile.hold(file())) {
            assertThatThrownBy(() -> UserHashStore.loadOrCreate(this.dir, RANDOM))
                    .isInstanceOf(IOException.class);
        }
    }

    private void givenTheFileSystemHasPosixPermissions() {
        assumeTrue(this.dir.getFileSystem().supportedFileAttributeViews().contains("posix"), "POSIX permissions");
    }

    private void whenTheUserHashIsLoaded() {
        try {
            this.hash = UserHashStore.loadOrCreate(this.dir, RANDOM);
        } catch (IOException e) {
            this.exception = e;
        }
    }

    private void thenTheHashIsKept() {
        assertThat(this.exception).isNull();
        assertThat(this.hash).isEqualTo(this.expectedHash);
    }

    private void thenTheHashIsMarked() {
        assertThat(PreferencesDat.isMarkedUserHash(this.expectedHash)).isTrue();
    }

    private void thenTheHashIsUsable() {
        assertThat(this.exception).isNull();
        assertThat(UserHashStore.isUsable(this.hash)).isTrue();
    }

    private void thenTheFileHoldsTheHash() throws IOException {
        assertThat(PreferencesDat.read(Files.readAllBytes(file())).userHash()).isEqualTo(this.expectedHash);
    }

    private void thenTheFileStartsWithTheVersion() throws IOException {
        assertThat(Files.readAllBytes(file())[0]).isEqualTo((byte) PreferencesDat.VERSION);
    }

    private void thenTheFileIsUnchanged() {
        assertThat(file()).hasBinaryContent(this.fileContent);
    }

    private void thenTheLeftoverIsGone() {
        assertThat(AtomicFiles.tmpPath(file())).doesNotExist();
    }

    private void thenTheFileHasThePermissions(String permissions) throws IOException {
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file())))
                .isEqualTo(permissions);
    }

    private void thenExceptionOccurred(Class<? extends Exception> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private Path file() {
        return this.dir.resolve(UserHashStore.FILE_NAME);
    }
}
