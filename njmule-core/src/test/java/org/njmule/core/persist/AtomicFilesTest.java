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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicFilesTest {

    @TempDir
    Path dir;

    private Path file;
    private Path obstacle;
    private HeldFile held;
    private Thread release;
    private String content;
    private boolean cleanedUp;
    private Exception exception;

    @AfterEach
    void releaseTheHeldFile() throws Exception {
        if (this.release != null) {
            this.release.join();
        }
        if (this.held != null) {
            this.held.close();
        }
    }

    @Test
    void shouldWriteAndKeepThePreviousVersionAsBackup() throws IOException {
        givenTheFile("sub/known.met");
        givenTheVersionsWritten("v1");

        whenTheContentIsWritten("v2");

        thenTheFileReads("v2");
        thenTheBackupHasContent("v1");
        thenNoTemporaryFileIsLeft();
    }

    @Test
    void shouldLeaveTheTargetUntouchedWhenTheWriterFails() throws IOException {
        givenTheFile("clients.met");
        givenTheVersionsWritten("original");

        whenAWriterFailsAfterWriting("partial", new IOException("disk full"));

        thenExceptionOccurred(IOException.class, "disk full");
        thenTheFileHasContent("original");
        thenNoTemporaryFileIsLeft();
    }

    @Test
    void shouldReadTheBackupWhenThePrimaryIsCorrupt() throws IOException {
        givenTheFile("emfriends.met");
        givenTheVersionsWritten("valid:1", "valid:2");
        givenThePrimaryContent("garbage");

        whenTheFileIsReadAsValid();

        thenTheContentReadIs("valid:1");
    }

    @Test
    void shouldReadTheBackupWhenThePrimaryIsMissing() throws IOException {
        givenTheFile("server.met");
        givenTheVersionsWritten("valid:a", "valid:b");
        givenThePrimaryDeletedByACrashBetweenTheTwoRenames();

        whenTheFileIsReadAsValid();

        thenTheContentReadIs("valid:a");
    }

    @Test
    void shouldFailWithBothFailuresWhenPrimaryAndBackupAreCorrupt() throws IOException {
        givenTheFile("x.met");
        givenThePrimaryContent("bad");
        givenTheBackupContent("bad too");

        whenTheFileIsReadAsValid();

        thenExceptionOccurred(IOException.class, "corrupt");
        thenTheNumberOfSuppressedFailuresIs(1);
    }

    @Test
    void shouldFailAsIoWhenTheBackupIsRejectedWithARuntimeException() throws IOException {
        givenTheFile("njmule.properties");
        givenTheBackupContent("\\u12");

        whenTheFileIsReadByAReaderThrowing(new IllegalStateException("malformed"));

        thenExceptionOccurred(IOException.class);
        thenTheCauseIs(IllegalStateException.class);
    }

    @Test
    void shouldReadAPrimaryHeldForAMomentOnceItIsFree() throws IOException {
        givenTheFile("clients.met");
        givenTheVersionsWritten("valid:1", "valid:2");
        givenThePrimaryHeld();
        givenThePrimaryReleasedAfterMillis(60);

        whenTheFileIsReadAsValid();

        thenThePrimaryIsReadRatherThanTheOlderBackup("valid:2");
    }

    @Test
    void shouldYieldTheBackupOfAPrimaryHeldLonger() throws IOException {
        givenTheFile("njmule.properties");
        givenTheVersionsWritten("valid:1", "valid:2");
        givenThePrimaryHeld();

        whenTheFileIsReadAsValid();

        thenTheContentReadIs("valid:1");
    }

    @Test
    void shouldFailOnAPrimaryHeldLongerWhereTheBackupIsNotAllowed() throws IOException {
        givenTheFile("njmule.properties");
        givenTheVersionsWritten("valid:1", "valid:2");
        givenThePrimaryHeld();

        whenTheFileIsReadWithoutTheBackupIfHeld();

        thenExceptionOccurred(FileSystemException.class);
    }

    @Test
    void shouldKeepSecretsForTheirOwnerOnly() throws IOException {
        givenTheFile("cryptkey.dat");
        givenPosixPermissions();
        givenALeftoverOfAnInterruptedWrite("left by a crash");
        givenTheSecretWritten("key 1");

        whenTheSecretIsWritten("key 2");

        thenTheFileHasContent("key 2");
        thenTheFileIsForItsOwnerOnly();
        thenTheBackupIsForItsOwnerOnly();
    }

    @Test
    void shouldLeaveADirectoryInThePlaceOfTheFileAlone() throws IOException {
        givenADirectoryInThePlaceOfTheFile("server.met", "inside", "x");

        whenTheContentIsWritten("v");

        thenExceptionOccurred(IOException.class);
        thenTheDirectoryStillHolds("inside", "x");
        thenNoTemporaryFileIsLeft();
    }

    @Test
    void shouldFailWhenAFileIsInThePlaceOfTheFolder() throws IOException {
        givenAFileInThePlaceOfTheFolder("config", "not a folder", "x.met");

        whenTheBytesAreWritten(new byte[1]);

        thenExceptionOccurred(IOException.class);
        thenTheFileInThePlaceOfTheFolderStillHas("not a folder");
    }

    @Test
    void shouldNotKeepALongerLeftoverOfAnInterruptedWrite() throws IOException {
        givenTheFile("known.met");
        givenALeftoverOfAnInterruptedWrite("a much longer leftover of a crash");

        whenTheContentIsWritten("v1");

        thenTheFileHasContent("v1");
    }

    @Test
    void shouldKeepTheVersionBeforeTheLastAsBackup() throws IOException {
        givenTheFile("clients.met");
        givenTheVersionsWritten("valid:1", "valid:2");

        whenTheContentIsWritten("valid:3");

        thenTheBackupHasContent("valid:2");
    }

    @Test
    void shouldRejectAnEmptyPrimaryAndReadTheBackup() throws IOException {
        givenTheFile("clients.met");
        givenTheVersionsWritten("valid:1", "valid:2", "valid:3");
        givenThePrimaryContent("");

        whenTheFileIsReadAsValid();

        thenTheBackupIsReadInPlaceOfTheEmptyFile("valid:2");
    }

    @Test
    void shouldFailAsIoWhenThePrimaryIsRejectedWithARuntimeExceptionAndThereIsNoBackup() throws IOException {
        givenTheFile("scheduler.properties");
        givenThePrimaryContent("x");

        whenTheFileIsReadByAReaderThrowing(new IllegalArgumentException("Malformed \\uxxxx encoding."));

        thenExceptionOccurred(IOException.class);
        thenTheCauseIs(IllegalArgumentException.class);
    }

    @Test
    void shouldFailToReadAMissingFile() {
        givenTheFile("none");

        whenTheFileIsRead();

        thenExceptionOccurred(NoSuchFileException.class);
    }

    @Test
    void shouldWriteWithoutBackupWhenToldTo() throws IOException {
        givenTheFile("prefs");
        givenTheByteWrittenWithoutBackup(1);

        whenTheByteIsWrittenWithoutBackup(2);

        thenThereIsNoBackup();
    }

    @Test
    void shouldCleanUpTheLeftoverOfAnInterruptedWrite() throws IOException {
        givenTheFile("prefs");
        givenTheByteWrittenWithoutBackup(1);
        givenTheByteWrittenWithoutBackup(2);
        givenALeftoverOfAnInterruptedWrite("leftover");

        whenTheInterruptedWriteIsCleanedUp();

        thenALeftoverWasCleanedUp(true);
    }

    @Test
    void shouldFindNothingToCleanUpOnceTheLeftoverIsGone() throws IOException {
        givenTheFile("prefs");
        givenTheByteWrittenWithoutBackup(1);
        givenTheByteWrittenWithoutBackup(2);
        givenALeftoverOfAnInterruptedWrite("leftover");
        givenTheInterruptedWriteCleanedUp();

        whenTheInterruptedWriteIsCleanedUp();

        thenALeftoverWasCleanedUp(false);
    }

    @Test
    void shouldDeleteAFileHeldForAMomentOnceItIsFree() throws IOException {
        givenTheFileInAFolderOfItsOwn("001.part");
        givenTheVersionsWritten("data");
        givenTheFileHeldFromDeletion();
        givenThePrimaryReleasedAfterMillis(60);

        whenTheFileIsDeleted();

        thenNoExceptionOccurred();
        thenTheFileIsGone();
    }

    @Test
    void shouldFailToDeleteAFileHeldLonger() throws IOException {
        givenTheFileInAFolderOfItsOwn("001.part");
        givenTheVersionsWritten("data");
        givenTheFileHeldFromDeletion();

        whenTheFileIsDeleted();

        thenExceptionOccurred(FileSystemException.class);
    }

    @Test
    void shouldDeleteNothingWhenTheFileIsMissing() {
        givenTheFile("001.part");

        whenTheFileIsDeleted();

        thenNoExceptionOccurred();
        thenTheFileIsGone();
    }

    private void givenTheFile(String name) {
        this.file = this.dir.resolve(name);
    }

    // Held from deletion through its folder, which must hold nothing else of the test.
    private void givenTheFileInAFolderOfItsOwn(String name) throws IOException {
        this.file = Files.createDirectory(this.dir.resolve("held")).resolve(name);
    }

    private void givenTheFileHeldFromDeletion() throws IOException {
        this.held = HeldFile.holdFromDeletion(this.file);
    }

    private void givenTheVersionsWritten(String... versions) throws IOException {
        for (String version : versions) {
            AtomicFiles.writeBytes(this.file, version.getBytes(UTF_8));
        }
    }

    // Written past AtomicFiles, as a torn write by another program would leave it.
    private void givenThePrimaryContent(String primary) throws IOException {
        Files.writeString(this.file, primary);
    }

    private void givenThePrimaryDeletedByACrashBetweenTheTwoRenames() throws IOException {
        Files.delete(this.file);
    }

    private void givenTheBackupContent(String backup) throws IOException {
        Files.writeString(AtomicFiles.bakPath(this.file), backup);
    }

    private void givenThePrimaryHeld() throws IOException {
        this.held = HeldFile.hold(this.file);
    }

    private void givenThePrimaryReleasedAfterMillis(long millis) {
        HeldFile holding = this.held;
        this.release = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(millis);
                holding.release();
            } catch (InterruptedException | IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private void givenPosixPermissions() {
        assumeTrue(this.file.getFileSystem().supportedFileAttributeViews().contains("posix"), "POSIX permissions");
    }

    private void givenALeftoverOfAnInterruptedWrite(String leftover) throws IOException {
        Files.writeString(AtomicFiles.tmpPath(this.file), leftover);
    }

    private void givenTheSecretWritten(String secret) throws IOException {
        AtomicFiles.writeSecret(this.file, secret.getBytes(UTF_8));
    }

    private void givenADirectoryInThePlaceOfTheFile(String name, String inside, String insideContent)
            throws IOException {
        this.file = Files.createDirectories(this.dir.resolve(name));
        Files.writeString(this.file.resolve(inside), insideContent);
    }

    private void givenAFileInThePlaceOfTheFolder(String folder, String folderContent, String name) throws IOException {
        this.obstacle = Files.writeString(this.dir.resolve(folder), folderContent);
        this.file = this.obstacle.resolve(name);
    }

    private void givenTheByteWrittenWithoutBackup(int value) throws IOException {
        AtomicFiles.write(this.file, out -> out.write(value), false);
    }

    private void givenTheInterruptedWriteCleanedUp() {
        assertThat(AtomicFiles.cleanupInterruptedWrite(this.file)).isTrue();
    }

    private void whenTheContentIsWritten(String written) {
        whenTheBytesAreWritten(written.getBytes(UTF_8));
    }

    private void whenTheBytesAreWritten(byte[] bytes) {
        try {
            AtomicFiles.writeBytes(this.file, bytes);
        } catch (IOException e) {
            this.exception = e;
        }
    }

    private void whenAWriterFailsAfterWriting(String partial, IOException failure) {
        try {
            AtomicFiles.write(this.file, out -> {
                out.write(partial.getBytes(UTF_8));
                throw failure;
            });
        } catch (IOException e) {
            this.exception = e;
        }
    }

    private void whenTheSecretIsWritten(String secret) throws IOException {
        AtomicFiles.writeSecret(this.file, secret.getBytes(UTF_8));
    }

    private void whenTheByteIsWrittenWithoutBackup(int value) throws IOException {
        AtomicFiles.write(this.file, out -> out.write(value), false);
    }

    private void whenTheFileIsRead() {
        try {
            this.content = readUtf8(this.file);
        } catch (IOException e) {
            this.exception = e;
        }
    }

    private void whenTheFileIsReadAsValid() {
        try {
            this.content = readValid(this.file);
        } catch (IOException e) {
            this.exception = e;
        }
    }

    private void whenTheFileIsReadWithoutTheBackupIfHeld() {
        try {
            this.content = AtomicFiles.read(this.file, in -> new String(in.readAllBytes(), UTF_8), false);
        } catch (IOException e) {
            this.exception = e;
        }
    }

    private void whenTheFileIsReadByAReaderThrowing(RuntimeException failure) {
        try {
            this.content = AtomicFiles.read(this.file, in -> {
                throw failure;
            });
        } catch (IOException e) {
            this.exception = e;
        }
    }

    private void whenTheFileIsDeleted() {
        try {
            AtomicFiles.delete(this.file);
        } catch (IOException e) {
            this.exception = e;
        }
    }

    private void whenTheInterruptedWriteIsCleanedUp() {
        this.cleanedUp = AtomicFiles.cleanupInterruptedWrite(this.file);
    }

    private void thenTheFileReads(String expected) throws IOException {
        assertThat(readUtf8(this.file)).isEqualTo(expected);
    }

    private void thenTheFileHasContent(String expected) {
        assertThat(this.file).hasContent(expected);
    }

    private void thenTheBackupHasContent(String expected) {
        assertThat(AtomicFiles.bakPath(this.file)).hasContent(expected);
    }

    private void thenThereIsNoBackup() {
        assertThat(AtomicFiles.bakPath(this.file)).doesNotExist();
    }

    private void thenNoTemporaryFileIsLeft() {
        assertThat(AtomicFiles.tmpPath(this.file)).doesNotExist();
    }

    private void thenTheContentReadIs(String expected) {
        assertThat(this.content).isEqualTo(expected);
    }

    private void thenThePrimaryIsReadRatherThanTheOlderBackup(String expected) {
        assertThat(this.content).as("not the older backup").isEqualTo(expected);
    }

    private void thenTheBackupIsReadInPlaceOfTheEmptyFile(String expected) {
        assertThat(this.content).as("an empty file is rejected too").isEqualTo(expected);
    }

    private void thenTheFileIsForItsOwnerOnly() throws IOException {
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(this.file)))
                .isEqualTo("rw-------");
    }

    private void thenTheBackupIsForItsOwnerOnly() throws IOException {
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(AtomicFiles.bakPath(this.file))))
                .isEqualTo("rw-------");
    }

    private void thenTheDirectoryStillHolds(String inside, String expected) {
        assertThat(this.file.resolve(inside)).hasContent(expected);
    }

    private void thenTheFileInThePlaceOfTheFolderStillHas(String expected) {
        assertThat(this.obstacle).hasContent(expected);
    }

    private void thenALeftoverWasCleanedUp(boolean expected) {
        assertThat(this.cleanedUp).isEqualTo(expected);
    }

    private void thenTheFileIsGone() {
        assertThat(this.file).doesNotExist();
    }

    private void thenNoExceptionOccurred() {
        assertThat(this.exception).isNull();
    }

    private void thenExceptionOccurred(Class<? extends Exception> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private void thenExceptionOccurred(Class<? extends Exception> type, String message) {
        assertThat(this.exception).isInstanceOf(type).hasMessage(message);
    }

    private void thenTheNumberOfSuppressedFailuresIs(int count) {
        assertThat(this.exception.getSuppressed()).hasSize(count);
    }

    private void thenTheCauseIs(Class<? extends Throwable> type) {
        assertThat(this.exception).hasCauseInstanceOf(type);
    }

    private static String readUtf8(Path file) throws IOException {
        return AtomicFiles.read(file, in -> new String(in.readAllBytes(), UTF_8));
    }

    // Rejects any content that is not "valid:...", like a parser rejecting a corrupt .met file.
    private static String readValid(Path file) throws IOException {
        return AtomicFiles.read(file, in -> {
            String s = new String(in.readAllBytes(), UTF_8);
            if (!s.startsWith("valid:")) {
                throw new IOException("corrupt");
            }
            return s;
        });
    }
}
