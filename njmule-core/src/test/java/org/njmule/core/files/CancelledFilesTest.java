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
package org.njmule.core.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Hash16;
import org.njmule.core.persist.format.CancelledMet;

class CancelledFilesTest {

    private static final Hash16 FILM = hash(1);
    private static final Hash16 SONG = hash(2);

    @TempDir
    Path dir;

    @Test
    void remembersCancelledFilesAcrossLoadsWithoutListingThem() throws Exception {
        Path file = this.dir.resolve(CancelledFiles.FILE_NAME);
        CancelledFiles cancelled = CancelledFiles.load(file, new SecureRandom());
        assertThat(cancelled.contains(FILM)).isFalse();

        cancelled.add(FILM);

        CancelledFiles again = CancelledFiles.load(file, new SecureRandom());
        assertThat(again.contains(FILM)).isTrue();
        assertThat(again.contains(SONG)).isFalse();
        CancelledMet.Contents contents = CancelledMet.read(Files.readAllBytes(file));
        assertThat(contents.keys())
                .as("keys, not file hashes")
                .doesNotContain(FILM)
                .hasSize(1);
    }

    @Test
    void givesTheOldFormatKeysOfItsOwn() throws Exception {
        Path file = this.dir.resolve(CancelledFiles.FILE_NAME);
        // The old format: header 0x0E, then the plain hashes.
        byte[] old = new byte[1 + 4 + 17];
        old[0] = 0x0E;
        old[1] = 1;
        FILM.copyTo(old, 5);
        Files.write(file, old);

        CancelledFiles cancelled = CancelledFiles.load(file, new SecureRandom());

        assertThat(cancelled.contains(FILM)).isTrue();
        cancelled.add(SONG);
        assertThat(CancelledMet.read(Files.readAllBytes(file)).legacy()).isFalse();
        assertThat(CancelledFiles.load(file, new SecureRandom()).contains(FILM)).isTrue();
    }

    @Test
    void aDamagedListIsReadFromItsBackup() throws Exception {
        Path file = this.dir.resolve(CancelledFiles.FILE_NAME);
        CancelledFiles cancelled = CancelledFiles.load(file, new SecureRandom());
        cancelled.add(FILM);
        cancelled.add(SONG);
        Files.write(file, new byte[] {0x0F, 1, 2});

        CancelledFiles again = CancelledFiles.load(file, new SecureRandom());

        assertThat(again.contains(FILM)).as("as it was before the last change").isTrue();
    }

    @Test
    void aSnapshotKeepsTheListAsItWas() throws Exception {
        CancelledFiles cancelled = CancelledFiles.load(this.dir.resolve(CancelledFiles.FILE_NAME), new SecureRandom());
        cancelled.add(FILM);
        Predicate<Hash16> before = cancelled.snapshot();

        cancelled.add(SONG);
        cancelled.clear();

        assertThat(List.of(FILM, SONG).stream().filter(before).toList()).containsExactly(FILM);
        assertThat(cancelled.contains(FILM)).isFalse();
        assertThat(cancelled.size()).isZero();
    }

    private static Hash16 hash(int seed) {
        byte[] bytes = new byte[16];
        bytes[0] = (byte) seed;
        return Hash16.of(bytes);
    }
}
