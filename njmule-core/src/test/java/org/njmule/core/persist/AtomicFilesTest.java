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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicFilesTest {

    @TempDir
    Path dir;

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

    @Test
    void writesAndKeepsThePreviousVersionAsBackup() throws IOException {
        Path file = this.dir.resolve("sub/known.met");

        AtomicFiles.writeBytes(file, "v1".getBytes(UTF_8));
        AtomicFiles.writeBytes(file, "v2".getBytes(UTF_8));

        assertThat(readUtf8(file)).isEqualTo("v2");
        assertThat(AtomicFiles.bakPath(file)).hasContent("v1");
        assertThat(AtomicFiles.tmpPath(file)).doesNotExist();
    }

    @Test
    void failingWriterLeavesTheTargetUntouched() throws IOException {
        Path file = this.dir.resolve("clients.met");
        AtomicFiles.writeBytes(file, "original".getBytes(UTF_8));

        assertThatThrownBy(() -> AtomicFiles.write(file, out -> {
                    out.write("partial".getBytes(UTF_8));
                    throw new IOException("disk full");
                }))
                .hasMessage("disk full");

        assertThat(file).hasContent("original");
        assertThat(AtomicFiles.tmpPath(file)).doesNotExist();
    }

    @Test
    void readFallsBackToTheBackupWhenThePrimaryIsCorrupt() throws IOException {
        Path file = this.dir.resolve("emfriends.met");
        AtomicFiles.writeBytes(file, "valid:1".getBytes(UTF_8));
        AtomicFiles.writeBytes(file, "valid:2".getBytes(UTF_8));
        Files.writeString(file, "garbage"); // simulates a torn write by another program

        assertThat(readValid(file)).isEqualTo("valid:1");
    }

    @Test
    void readFallsBackToTheBackupWhenThePrimaryIsMissing() throws IOException {
        Path file = this.dir.resolve("server.met");
        AtomicFiles.writeBytes(file, "valid:a".getBytes(UTF_8));
        AtomicFiles.writeBytes(file, "valid:b".getBytes(UTF_8));
        Files.delete(file); // crash between the two renames

        assertThat(readValid(file)).isEqualTo("valid:a");
    }

    @Test
    void readFailsWithBothFailuresWhenEverythingIsCorrupt() throws IOException {
        Path file = this.dir.resolve("x.met");
        Files.writeString(file, "bad");
        Files.writeString(AtomicFiles.bakPath(file), "bad too");

        assertThatThrownBy(() -> readValid(file))
                .isInstanceOf(IOException.class)
                .hasMessage("corrupt")
                .satisfies(e -> assertThat(e.getSuppressed()).hasSize(1));
    }

    @Test
    void aBackupRejectedWithARuntimeExceptionFailsAsIo() throws IOException {
        Path file = this.dir.resolve("njmule.properties");
        Files.writeString(AtomicFiles.bakPath(file), "\\u12");

        assertThatThrownBy(() -> AtomicFiles.read(file, in -> {
                    throw new IllegalStateException("malformed");
                }))
                .isInstanceOf(IOException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void aPrimaryHeldForAMomentIsReadOnceFree() throws Exception {
        Path file = this.dir.resolve("clients.met");
        AtomicFiles.writeBytes(file, "valid:1".getBytes(UTF_8));
        AtomicFiles.writeBytes(file, "valid:2".getBytes(UTF_8));

        try (HeldFile held = HeldFile.hold(file)) {
            Thread release = Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(60);
                    held.close();
                } catch (InterruptedException | IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            assertThat(readValid(file)).as("not the older backup").isEqualTo("valid:2");
            release.join();
        }
    }

    @Test
    void aPrimaryHeldLongerYieldsTheBackupOnlyWhereAllowed() throws Exception {
        Path file = this.dir.resolve("njmule.properties");
        AtomicFiles.writeBytes(file, "valid:1".getBytes(UTF_8));
        AtomicFiles.writeBytes(file, "valid:2".getBytes(UTF_8));

        try (HeldFile _ = HeldFile.hold(file)) {
            assertThat(readValid(file)).isEqualTo("valid:1");
            assertThatThrownBy(() -> AtomicFiles.read(file, in -> new String(in.readAllBytes(), UTF_8), false))
                    .isInstanceOf(FileSystemException.class);
        }
    }

    @Test
    void secretsAreForTheirOwnerOnly() throws IOException {
        Path file = this.dir.resolve("cryptkey.dat");
        assumeTrue(file.getFileSystem().supportedFileAttributeViews().contains("posix"), "POSIX permissions");
        Files.writeString(AtomicFiles.tmpPath(file), "left by a crash");

        AtomicFiles.writeSecret(file, "key 1".getBytes(UTF_8));
        AtomicFiles.writeSecret(file, "key 2".getBytes(UTF_8));

        assertThat(file).hasContent("key 2");
        for (Path written : List.of(file, AtomicFiles.bakPath(file))) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(written)))
                    .isEqualTo("rw-------");
        }
    }

    @Test
    void readOfMissingFileFails() {
        assertThatThrownBy(() -> readUtf8(this.dir.resolve("none"))).isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void writeWithoutBackupAndCleanupOfInterruptedWrites() throws IOException {
        Path file = this.dir.resolve("prefs");
        AtomicFiles.write(file, out -> out.write(1), false);
        AtomicFiles.write(file, out -> out.write(2), false);
        assertThat(AtomicFiles.bakPath(file)).doesNotExist();

        Files.writeString(AtomicFiles.tmpPath(file), "leftover");
        assertThat(AtomicFiles.cleanupInterruptedWrite(file)).isTrue();
        assertThat(AtomicFiles.cleanupInterruptedWrite(file)).isFalse();
    }
}
