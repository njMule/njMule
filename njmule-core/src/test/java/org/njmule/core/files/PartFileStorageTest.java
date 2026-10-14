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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Hash20;
import org.njmule.core.files.hash.AichTree;
import org.njmule.core.files.hash.Ed2k;

@Timeout(30)
class PartFileStorageTest {

    private static final long SIZE = 2L * Ed2k.EMBLOCKSIZE + 1000;

    @TempDir
    Path dir;

    @Test
    void whatWasNeverWrittenHashesAsNothing() throws Exception {
        PartFileStorage storage = PartFileStorage.open(this.dir.resolve("001.part"));

        assertThat(storage.hashPart(SIZE, 0).get(10, TimeUnit.SECONDS)).isEqualTo(Ed2k.EMPTY_PART_HASH);
        Hash20 empty = Hash20.of(AichTree.sha1().digest());
        assertThat(storage.hashBlocks(SIZE, 0).get(10, TimeUnit.SECONDS))
                .as("a hash per block, of the bytes there are")
                .containsExactlyElementsOf(Collections.nCopies(3, empty));
        storage.close();
    }

    @Test
    void afterTheFinishTheFileTakesNoMoreWork() throws Exception {
        PartFileStorage storage = PartFileStorage.open(this.dir.resolve("002.part"));
        storage.write(0, new byte[] {1, 2, 3}).get(10, TimeUnit.SECONDS);
        Path moved = storage.finish(this.dir.resolve("incoming"), "done.bin").get(10, TimeUnit.SECONDS);

        assertThat(moved).hasBinaryContent(new byte[] {1, 2, 3});
        assertThat(storage.isOpen()).isFalse();
        assertThatThrownBy(() -> storage.hashPart(3, 0).get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseInstanceOf(ClosedChannelException.class);
        assertThatThrownBy(() -> storage.hashBlocks(3, 0).get(10, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(ClosedChannelException.class);
        storage.close();
        storage.close();
        assertThat(storage.isOpen()).isFalse();
    }

    @Test
    void aFinishThatCannotMoveTheDataGivesItsReservedNameBack() throws Exception {
        Path data = this.dir.resolve("003.part");
        PartFileStorage storage = PartFileStorage.open(data);
        storage.write(0, new byte[] {4, 5, 6}).get(10, TimeUnit.SECONDS);
        Files.delete(data);
        Path incoming = this.dir.resolve("incoming");

        assertThatThrownBy(() -> storage.finish(incoming, "lost.bin").get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(UncheckedIOException.class)
                .hasRootCauseInstanceOf(NoSuchFileException.class);
        try (Stream<Path> left = Files.list(incoming)) {
            assertThat(left).as("the name reserved for it is free again").isEmpty();
        }
        storage.close();
    }
}
