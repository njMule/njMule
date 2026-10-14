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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.PartMap;
import org.njmule.core.files.PartFile.Block;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.persist.format.PartMet;

class PartFileTest {

    private static final long P = Ed2k.PARTSIZE;
    private static final int B = Ed2k.EMBLOCKSIZE;

    @TempDir
    Path dir;

    @Test
    void handsOutEachMissingBlockOnceUntilItIsWrittenOrReleased() {
        PartFile file = this.small(500_000);
        BitSet all = all(file);

        List<Block> first = file.nextBlocks(all, 3);
        assertThat(first).containsExactly(new Block(0, B), new Block(B, 2L * B), new Block(2L * B, 500_000));
        assertThat(file.nextBlocks(all, 3)).isEmpty();

        file.written(0, B);
        file.release(first.get(2));
        assertThat(file.nextBlocks(all, 3)).containsExactly(new Block(2L * B, 500_000));
        assertThat(file.completedBytes()).isEqualTo(B);
        assertThat(file.hasHashSet()).isTrue();
        assertThat(file.partHash(0)).contains(file.hash());
    }

    @Test
    void picksTheRarestPartTheSourceHasAndTakesBackCorruptParts() {
        List<Hash16> parts = List.of(hash(1), hash(2), hash(3));
        PartFile file =
                PartFile.create(1, this.dir, "big.bin", 2 * P + 100, Ed2k.fileHash(parts), Optional.empty(), parts);
        BitSet common = new BitSet();
        common.set(0, 3);
        file.addAvailability(common, 1);
        BitSet rare = new BitSet();
        rare.set(0);
        rare.set(2);
        file.addAvailability(rare, 1);

        assertThat(file.nextBlocks(common, 1)).containsExactly(new Block(P, P + B));

        file.written(0, P);
        assertThat(file.isComplete(0)).isTrue();
        file.corrupt(0);
        assertThat(file.isComplete(0)).isFalse();
        assertThat(file.completeParts().isEmpty()).isTrue();
    }

    @Test
    void aPreviewFetchesTheFirstAndLastPartsBeforeRarerOnes() throws Exception {
        List<Hash16> parts = List.of(hash(1), hash(2), hash(3), hash(4));
        PartFile film =
                PartFile.create(3, this.dir, "film.mkv", 3 * P + 100, Ed2k.fileHash(parts), Optional.empty(), parts);
        BitSet all = new BitSet();
        all.set(0, 4);
        film.addAvailability(all, 5);
        BitSet rare = new BitSet();
        rare.set(1);
        film.addAvailability(rare, -4);
        assertThat(film.nextBlocks(all, 1).getFirst().start())
                .as("the rarest part")
                .isEqualTo(P);

        film.setPreviewFirst(true);
        BitSet rest = new BitSet();
        rest.set(0);
        rest.set(2, 4);
        assertThat(film.nextBlocks(rest, 1).getFirst().start())
                .as("the first part, before rarer ones")
                .isZero();
        BitSet twoLast = new BitSet();
        twoLast.set(2, 4);
        assertThat(film.nextBlocks(twoLast, 1).getFirst().start())
                .as("the last part, and the one before as the last is short")
                .isEqualTo(2 * P);
        film.save();
        assertThat(PartFile.load(this.dir, 3).previewFirst()).isTrue();

        PartFile notes =
                PartFile.create(4, this.dir, "notes.txt", 3 * P + 100, Ed2k.fileHash(parts), Optional.empty(), parts);
        notes.setPreviewFirst(true);
        assertThat(notes.previewParts(false).isEmpty()).as("no player shows it").isTrue();
    }

    @Test
    void finishesABegunPartFirstAndCutsBlocksOnTheAichBoundaries() {
        List<Hash16> parts = List.of(hash(1), hash(2), hash(3));
        PartFile file =
                PartFile.create(2, this.dir, "big.bin", 2 * P + 100, Ed2k.fileHash(parts), Optional.empty(), parts);
        BitSet all = new BitSet();
        all.set(0, 3);
        file.addAvailability(all, 1);
        file.written(P, P + 1000);

        assertThat(file.nextBlocks(all, 2))
                .as("part 1 was begun")
                .containsExactly(new Block(P + 1000, P + B), new Block(P + B, P + 2L * B));
    }

    @Test
    void mapsWhatIsMissingRequestedAndAvailable() {
        PartFile file = this.small(500_000);
        BitSet all = all(file);
        file.written(0, 100_000);
        List<Block> asked = file.nextBlocks(all, 1);
        file.addAvailability(all, 2);

        PartMap map = file.map();

        assertThat(map.size()).isEqualTo(500_000);
        assertThat(map.missing()).containsExactly(new PartMap.Span(100_000, 500_000));
        assertThat(map.requested())
                .containsExactly(new PartMap.Span(
                        asked.getFirst().start(), asked.getFirst().end()));
        assertThat(map.sources()).containsExactly(2);
    }

    @Test
    void acceptsOnlyAHashSetThatMatchesTheFileHash() {
        List<Hash16> parts = List.of(hash(1), hash(2));
        PartFile file = PartFile.create(1, this.dir, "f", P + 1, Ed2k.fileHash(parts), Optional.empty(), List.of());

        assertThat(file.hasHashSet()).isFalse();
        assertThat(file.partHash(1)).isEmpty();
        assertThatThrownBy(() -> file.setHashSet(List.of(hash(2), hash(1))))
                .isInstanceOf(IllegalArgumentException.class);
        file.setHashSet(parts);
        assertThat(file.partHash(1)).contains(hash(2));
    }

    @Test
    void aLinkHashSetThatProvesNothingIsAskedFromTheSources() {
        List<Hash16> exact = List.of(hash(1), Ed2k.EMPTY_PART_HASH);
        Hash16 exactHash = Ed2k.fileHash(exact);

        PartFile noEmptyPart = PartFile.create(1, this.dir, "a", P, exactHash, Optional.empty(), List.of(hash(1)));
        assertThat(noEmptyPart.hasHashSet())
                .as("an exact multiple also hashes the empty part after it")
                .isFalse();
        PartFile wrong = PartFile.create(
                2,
                this.dir,
                "b",
                P + 1,
                Ed2k.fileHash(List.of(hash(1), hash(2))),
                Optional.empty(),
                List.of(hash(2), hash(1)));
        assertThat(wrong.hasHashSet()).isFalse();
        assertThat(wrong.partHashes()).isEmpty();
        PartFile small = PartFile.create(3, this.dir, "c", 1000, hash(3), Optional.empty(), List.of(hash(3)));
        assertThat(small.hasHashSet()).isTrue();
        assertThat(small.partHashes()).as("one part needs only the file hash").isEmpty();

        PartFile right = PartFile.create(4, this.dir, "d", P, exactHash, Optional.empty(), exact);
        assertThat(right.partHashes()).isEqualTo(exact);
        assertThat(right.partCount()).isEqualTo(1);
        assertThat(right.partHash(0)).contains(hash(1));
    }

    @Test
    void aSavedHashSetThatDoesNotGiveTheFileHashIsDropped() throws Exception {
        List<Hash16> parts = List.of(hash(1), hash(2));
        PartFile file = PartFile.create(5, this.dir, "e", P + 1, Ed2k.fileHash(parts), Optional.empty(), parts);
        file.save();
        PartMet.Contents saved = PartMet.read(Files.readAllBytes(file.metFile()));
        Files.write(
                file.metFile(),
                PartMet.write(new PartMet.Contents(
                        saved.version(), saved.mtime(), saved.fileHash(), List.of(hash(2), hash(1)), saved.tags())));

        PartFile loaded = PartFile.load(this.dir, 5);

        assertThat(loaded.hasHashSet()).as("asked from the sources again").isFalse();
        assertThat(loaded.partHash(0)).isEmpty();
    }

    @Test
    void savesAndLoadsItsProgress() throws Exception {
        List<Hash16> parts = List.of(hash(1), hash(2), hash(3));
        PartFile file =
                PartFile.create(7, this.dir, "film.mkv", 2 * P + 5, Ed2k.fileHash(parts), Optional.empty(), parts);
        file.written(0, P);
        file.written(P + 10, P + 20);
        file.setStatus(PartFile.Status.PAUSED);
        file.save();

        PartFile loaded = PartFile.load(this.dir, 7);

        assertThat(loaded.name()).isEqualTo("film.mkv");
        assertThat(loaded.size()).isEqualTo(2 * P + 5);
        assertThat(loaded.completedBytes()).isEqualTo(P + 10);
        assertThat(loaded.isComplete(0)).isTrue();
        assertThat(loaded.status()).isEqualTo(PartFile.Status.PAUSED);
        assertThat(loaded.partHash(2)).contains(hash(3));
        assertThat(loaded.metFile().getFileName().toString()).isEqualTo("007.part.met");
    }

    @Test
    void trustsThePartsCheckedBeforeOnlyWhileTheDataIsUnchanged() throws Exception {
        List<Hash16> parts = List.of(hash(1), hash(2), hash(3));
        PartFile file =
                PartFile.create(8, this.dir, "film.mkv", 2 * P + 5, Ed2k.fileHash(parts), Optional.empty(), parts);
        Files.write(file.dataFile(), new byte[] {1, 2, 3});
        file.written(0, P);
        file.written(P, 2 * P);
        file.verified(0);
        file.save();

        assertThat(PartFile.load(this.dir, 8).trustedParts().stream().boxed().toList())
                .containsExactly(0);

        Files.setLastModifiedTime(
                file.dataFile(),
                FileTime.fromMillis(Files.getLastModifiedTime(file.dataFile()).toMillis() + 5_000));
        assertThat(PartFile.load(this.dir, 8).trustedParts().isEmpty())
                .as("the data changed since the save")
                .isTrue();
    }

    @Test
    void keepsItsPriorityAndWhetherItIsStopped() throws Exception {
        PartFile file = PartFile.create(3, this.dir, "a.bin", 5000, hash(1), Optional.empty(), List.of());
        assertThat(file.priority()).as("new downloads follow their rarity").isEqualTo(DownloadPriority.AUTO);
        for (DownloadPriority priority : DownloadPriority.values()) {
            file.setPriority(priority);
            file.save();
            assertThat(PartFile.load(this.dir, 3).priority()).isEqualTo(priority);
        }
        file.setPriority(DownloadPriority.HIGH);
        file.setStatus(PartFile.Status.STOPPED);
        file.setCategory(4);
        file.save();

        PartFile loaded = PartFile.load(this.dir, 3);

        assertThat(loaded.category()).isEqualTo(4);

        assertThat(loaded.status()).isEqualTo(PartFile.Status.STOPPED);
        PartMet.Contents met = PartMet.read(Files.readAllBytes(loaded.metFile()));
        assertThat(met.paused()).as("other clients see a paused file").isTrue();
        assertThat(met.downloadPriority()).as("the value other clients write").hasValue(2);
    }

    @Test
    void storageWritesHashesPartsFromDiskAndMovesTheFinishedFile() throws Exception {
        byte[] data = new byte[300_000];
        new Random(1).nextBytes(data);
        Path part = this.dir.resolve("001.part");
        Path target = this.dir.resolve("incoming").resolve("done.bin");

        try (PartFileStorage storage = PartFileStorage.open(part)) {
            storage.write(100_000, Arrays.copyOfRange(data, 100_000, data.length))
                    .get(5, TimeUnit.SECONDS);
            storage.write(0, Arrays.copyOfRange(data, 0, 100_000)).get(5, TimeUnit.SECONDS);

            assertThat(storage.hashPart(data.length, 0).get(5, TimeUnit.SECONDS))
                    .isEqualTo(Ed2k.md4(data, 0, data.length));
            assertThat(storage.finish(target.getParent(), "done.bin").get(5, TimeUnit.SECONDS))
                    .isEqualTo(target);
        }
        assertThat(Files.readAllBytes(target)).isEqualTo(data);
        assertThat(part).doesNotExist();
    }

    private PartFile small(long size) {
        return PartFile.create(1, this.dir, "small.bin", size, hash(9), Optional.empty(), List.of());
    }

    private static BitSet all(PartFile file) {
        BitSet parts = new BitSet();
        parts.set(0, file.partCount());
        return parts;
    }

    private static Hash16 hash(int seed) {
        byte[] bytes = new byte[16];
        bytes[0] = (byte) seed;
        return Hash16.of(bytes);
    }

    @Test
    void aFinishedFileNeverReplacesOneWithItsName() throws Exception {
        Path incoming = Files.createDirectories(this.dir.resolve("incoming"));
        Files.writeString(incoming.resolve("same.bin"), "already here");
        List<Path> finished = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Path part = this.dir.resolve("00" + i + ".part");
            try (PartFileStorage storage = PartFileStorage.open(part)) {
                storage.write(0, new byte[] {(byte) i, 1, 2}).get(5, TimeUnit.SECONDS);
                finished.add(storage.finish(incoming, "same.bin").get(5, TimeUnit.SECONDS));
            }
        }
        assertThat(finished)
                .extracting(p -> p.getFileName().toString())
                .containsExactly("same (2).bin", "same (3).bin");
        assertThat(Files.readString(incoming.resolve("same.bin"))).isEqualTo("already here");
        assertThat(Files.readAllBytes(finished.get(1))).containsExactly(1, 1, 2);
    }
}
