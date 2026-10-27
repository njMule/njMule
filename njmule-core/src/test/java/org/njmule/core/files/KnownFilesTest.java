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

import java.io.ByteArrayInputStream;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;
import org.njmule.core.api.UploadPriority;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.files.hash.FileHasher;
import org.njmule.core.persist.AtomicFiles;
import org.njmule.core.persist.format.Known2Met;
import org.njmule.core.persist.format.KnownMet;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;

/** What known.met and known2_64.met keep of the files shared now and before. */
@Timeout(60)
class KnownFilesTest {

    @TempDir
    Path dir;

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final Map<String, FileHasher.Result> hashes = new HashMap<>();
    private final List<KnownMet.KnownFile> records = new ArrayList<>();
    private final List<Known2Met.Entry> trees = new ArrayList<>();
    private Path config;
    private Path incoming;
    private SharedFiles shares;
    private FileTime treesDate;
    private PartFile finished;

    @AfterEach
    void stop() throws Exception {
        closeShares();
        this.loop.close();
    }

    @Test
    void shouldReadADamagedKnown2FromItsBackup() throws Exception {
        givenTheIncomingDirectoryHolds("a.bin");
        givenKnownMetRecords("a.bin");
        givenTheBackupOfKnown2HoldsTheTreeOf("a.bin");
        givenKnown2Is(new byte[] {9, 9, 9});

        whenTheSharesOpenAndCloseAtOnce();

        thenKnown2HoldsOnlyTheTreesOf("a.bin");
    }

    @Test
    void shouldKeepTheTreesBeforeTheCutOfAKnown2CutShort() throws Exception {
        givenTheIncomingDirectoryHolds("a.bin", "b.bin");
        givenKnownMetRecords("a.bin", "b.bin");
        givenKnown2HoldsTheTreesOfCutShort("a.bin", "b.bin");

        whenTheSharesOpenAndCloseAtOnce();

        thenKnown2HoldsOnlyTheTreesOf("a.bin");
    }

    @Test
    void shouldKeepTheRecordsBeforeTheCutOfAKnownMetCutShort() throws Exception {
        givenTheIncomingDirectoryHolds("a.bin", "b.bin");
        givenKnownMetCutShortRecords("a.bin", "b.bin");

        whenTheSharesOpenAndCloseAtOnce();

        thenKnownMetRecords("a.bin");
    }

    @Test
    void shouldKeepOneRecordForAFileWhoseDateChanges() throws Exception {
        givenTheIncomingDirectoryHolds("a.bin");
        givenTheFilesAreSharedAndRecorded();

        whenTheDateOfChangesAndItIsHashedAgain("a.bin");

        thenKnownMetHoldsOneRecordOfAtTheDateItHasNow("a.bin");
    }

    @Test
    void shouldDropATreeNoRecordNeeds() throws Exception {
        givenTheIncomingDirectoryHolds("a.bin");
        givenKnownMetRecords("a.bin");
        givenKnown2HoldsTheTreesOf("a.bin");
        givenKnown2HoldsATreeNoRecordNeeds();

        whenTheFilesAreSharedAndSaved();

        thenKnown2HoldsOnlyTheTreesOf("a.bin");
    }

    @Test
    void shouldKeepTheRecordButNotTheTreeOfAFileUnsharedForOverAMonth() throws Exception {
        givenTheIncomingDirectoryHolds();
        givenKnownMetRecordsAFileLastSharedDaysAgo("old.bin", 40);
        givenKnownMetRecordsAFileLastSharedDaysAgo("recent.bin", 10);

        whenTheFilesAreSharedAndSaved();

        thenKnownMetRecords("old.bin", "recent.bin");
        thenKnown2HoldsOnlyTheTreesOf("recent.bin");
    }

    @Test
    void shouldHashForItsTreeAKnownFileSharedWithoutIt() throws Exception {
        givenTheIncomingDirectoryHolds("a.bin");
        givenKnownMetRecords("a.bin");

        whenTheFilesAreShared();

        thenTheAichTreeOfIsKnownSoon("a.bin");
    }

    @Test
    void shouldWriteTheTreesOnlyWhenTheyChange() throws Exception {
        givenTheIncomingDirectoryHolds("a.bin");
        givenTheFilesAreSharedAndRecorded();
        givenKnown2WasLastWrittenLongAgo();

        whenOnlyTheUploadPriorityOfChanges("a.bin");

        thenKnown2WasNotWrittenAgain();
    }

    @Test
    void shouldNotRecordAFinishedDownloadWithTheAichHashOfItsLinkBeforeItsOwnTreeIsBuilt() throws Exception {
        givenTheIncomingDirectoryHolds("done.bin");
        givenADownloadOfWhoseLinkGaveAnotherAichHash("done.bin");

        whenItFinishesAndTheSharesCloseAtOnce("done.bin");

        thenKnownMetRecordsWithoutAnAichHash("done.bin");
    }

    private void givenTheIncomingDirectoryHolds(String... names) throws Exception {
        this.config = Files.createDirectories(this.dir.resolve("config"));
        this.incoming = Files.createDirectories(this.dir.resolve("incoming"));
        long seed = 1;
        for (String name : names) {
            Path file = Files.write(this.incoming.resolve(name), bytes(400_000, seed++));
            this.hashes.put(name, FileHasher.hash(file));
        }
    }

    private void givenKnownMetRecords(String... names) throws Exception {
        for (String name : names) {
            this.records.add(record(name, this.hashes.get(name).ed2k().fileHash(), System.currentTimeMillis()));
        }
        Files.write(this.config.resolve(SharedFiles.KNOWN_MET), KnownMet.write(this.records));
    }

    private void givenKnownMetCutShortRecords(String... names) throws Exception {
        for (String name : names) {
            this.records.add(record(name, this.hashes.get(name).ed2k().fileHash(), System.currentTimeMillis()));
        }
        byte[] data = KnownMet.write(this.records);
        Files.write(this.config.resolve(SharedFiles.KNOWN_MET), Arrays.copyOf(data, data.length - 5));
    }

    private void givenKnownMetRecordsAFileLastSharedDaysAgo(String name, int days) throws Exception {
        byte[] content = bytes(300_000, name.hashCode());
        FileHasher.Result result =
                FileHasher.hash(Channels.newChannel(new ByteArrayInputStream(content)), content.length, done -> {});
        this.hashes.put(name, result);
        long lastShared = System.currentTimeMillis() - Duration.ofDays(days).toMillis();
        this.records.add(record(name, result, 1_700_000_000L, lastShared));
        this.trees.add(entry(result));
        Files.write(this.config.resolve(SharedFiles.KNOWN_MET), KnownMet.write(this.records));
        Files.write(this.config.resolve(SharedFiles.KNOWN2_MET), Known2Met.write(this.trees));
    }

    private void givenADownloadOfWhoseLinkGaveAnotherAichHash(String name) throws Exception {
        FileHasher.Result result = this.hashes.get(name);
        this.finished = PartFile.create(
                1,
                this.dir,
                name,
                result.ed2k().size(),
                result.ed2k().fileHash(),
                Optional.of(Hash20.parseBase32("L".repeat(32))),
                result.ed2k().partHashes());
    }

    private void givenTheBackupOfKnown2HoldsTheTreeOf(String name) throws Exception {
        Path known2 = this.config.resolve(SharedFiles.KNOWN2_MET).toAbsolutePath();
        Files.write(AtomicFiles.bakPath(known2), Known2Met.write(List.of(entry(this.hashes.get(name)))));
    }

    private void givenKnown2Is(byte[] content) throws Exception {
        Files.write(this.config.resolve(SharedFiles.KNOWN2_MET), content);
    }

    private void givenKnown2HoldsTheTreesOf(String... names) throws Exception {
        for (String name : names) {
            this.trees.add(entry(this.hashes.get(name)));
        }
        Files.write(this.config.resolve(SharedFiles.KNOWN2_MET), Known2Met.write(this.trees));
    }

    private void givenKnown2HoldsTheTreesOfCutShort(String... names) throws Exception {
        for (String name : names) {
            this.trees.add(entry(this.hashes.get(name)));
        }
        byte[] data = Known2Met.write(this.trees);
        Files.write(this.config.resolve(SharedFiles.KNOWN2_MET), Arrays.copyOf(data, data.length - 7));
    }

    private void givenKnown2HoldsATreeNoRecordNeeds() throws Exception {
        this.trees.add(
                new Known2Met.Entry(Hash20.parseBase32("Q".repeat(32)), List.of(Hash20.parseBase32("R".repeat(32)))));
        Files.write(this.config.resolve(SharedFiles.KNOWN2_MET), Known2Met.write(this.trees));
    }

    private void givenTheFilesAreSharedAndRecorded() throws Exception {
        whenTheFilesAreShared();
        awaitShared(this.hashes.keySet().size());
        closeShares();
        this.shares = open();
        awaitShared(this.hashes.keySet().size());
    }

    private void givenKnown2WasLastWrittenLongAgo() throws Exception {
        this.treesDate = FileTime.fromMillis(1_600_000_000_000L);
        Files.setLastModifiedTime(this.config.resolve(SharedFiles.KNOWN2_MET), this.treesDate);
    }

    // No scan runs: what was read is saved again as it is.
    private void whenTheSharesOpenAndCloseAtOnce() throws Exception {
        onLoop(() -> {
            openOnLoop().close();
            return null;
        });
    }

    // One task of the loop: neither the first scan nor the hashing for its tree can build that tree before the close.
    private void whenItFinishesAndTheSharesCloseAtOnce(String name) throws Exception {
        Path file = this.incoming.resolve(name);
        onLoop(() -> {
            SharedFiles opened = openOnLoop();
            opened.finished(file, this.finished);
            opened.close();
            return null;
        });
    }

    private void whenTheFilesAreShared() throws Exception {
        this.shares = open();
    }

    private void whenTheFilesAreSharedAndSaved() throws Exception {
        this.shares = open();
        awaitShared(namesOnDisk());
        closeShares();
    }

    private void whenTheDateOfChangesAndItIsHashedAgain(String name) throws Exception {
        Path file = this.incoming.resolve(name);
        FileTime date = FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 100_000);
        Files.setLastModifiedTime(file, date);
        onLoop(() -> {
            this.shares.scan();
            return null;
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (onLoop(() -> this.shares.all().getFirst().mtime()) != date.toMillis() / 1000) {
            assertThat(System.nanoTime()).as("hashed again").isLessThan(deadline);
            Thread.sleep(20);
        }
        closeShares();
    }

    private void whenOnlyTheUploadPriorityOfChanges(String name) throws Exception {
        Hash16 hash = this.hashes.get(name).ed2k().fileHash();
        onLoop(() -> this.shares.setPriority(hash, UploadPriority.HIGH));
        closeShares();
    }

    private void thenTheAichTreeOfIsKnownSoon(String name) throws Exception {
        Hash16 hash = this.hashes.get(name).ed2k().fileHash();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (onLoop(() -> this.shares.aichTree(hash)).isEmpty()) {
            assertThat(System.nanoTime()).as("hashed for its tree").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    private void thenKnownMetHoldsOneRecordOfAtTheDateItHasNow(String name) throws Exception {
        long date = Files.getLastModifiedTime(this.incoming.resolve(name)).toMillis() / 1000;
        assertThat(knownMet())
                .filteredOn(r -> r.name().equals(Optional.of(name)))
                .singleElement()
                .satisfies(r -> assertThat(r.mtime()).isEqualTo(date));
    }

    private void thenKnownMetRecordsWithoutAnAichHash(String name) throws Exception {
        assertThat(knownMet())
                .filteredOn(r -> r.name().equals(Optional.of(name)))
                .singleElement()
                .satisfies(r -> assertThat(r.aichHash()).isEmpty());
    }

    private void thenKnownMetRecords(String... names) throws Exception {
        assertThat(knownMet()).extracting(r -> r.name().orElseThrow()).containsExactlyInAnyOrder(names);
    }

    private void thenKnown2HoldsOnlyTheTreesOf(String... names) throws Exception {
        List<Hash20> expected = new ArrayList<>();
        for (String name : names) {
            expected.add(this.hashes.get(name).aich().orElseThrow().masterHash());
        }
        assertThat(Known2Met.read(Files.readAllBytes(this.config.resolve(SharedFiles.KNOWN2_MET))))
                .extracting(Known2Met.Entry::masterHash)
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    private void thenKnown2WasNotWrittenAgain() throws Exception {
        assertThat(Files.getLastModifiedTime(this.config.resolve(SharedFiles.KNOWN2_MET)))
                .isEqualTo(this.treesDate);
        assertThat(knownMet())
                .singleElement()
                .satisfies(r -> assertThat(r.uploadPriority()).isPresent());
    }

    private List<KnownMet.KnownFile> knownMet() throws Exception {
        return KnownMet.read(Files.readAllBytes(this.config.resolve(SharedFiles.KNOWN_MET)));
    }

    private int namesOnDisk() throws Exception {
        try (var files = Files.list(this.incoming)) {
            return (int) files.count();
        }
    }

    private void awaitShared(int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (onLoop(() -> this.shares.all().size()) < count) {
            assertThat(System.nanoTime()).as("shared").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    private KnownMet.KnownFile record(String name, Hash16 hash, long lastSharedMillis) throws Exception {
        Path file = this.incoming.resolve(name);
        List<Tag> tags = new ArrayList<>();
        tags.add(new Tag.StringTag(TagName.id(FileTag.FILENAME), name));
        tags.add(new Tag.IntTag(TagName.id(FileTag.FILESIZE), Files.size(file)));
        this.hashes
                .get(name)
                .aich()
                .ifPresent(t -> tags.add(new Tag.StringTag(
                        TagName.id(FileTag.AICH_HASH), t.masterHash().toBase32())));
        tags.add(new Tag.IntTag(TagName.id(FileTag.LASTSHARED), lastSharedMillis / 1000));
        return new KnownMet.KnownFile(Files.getLastModifiedTime(file).toMillis() / 1000, hash, List.of(), tags);
    }

    private KnownMet.KnownFile record(String name, FileHasher.Result result, long mtime, long lastSharedMillis) {
        return new KnownMet.KnownFile(
                mtime,
                result.ed2k().fileHash(),
                result.ed2k().partHashes(),
                List.of(
                        new Tag.StringTag(TagName.id(FileTag.FILENAME), name),
                        new Tag.IntTag(
                                TagName.id(FileTag.FILESIZE), result.ed2k().size()),
                        new Tag.StringTag(
                                TagName.id(FileTag.AICH_HASH),
                                result.aich().orElseThrow().masterHash().toBase32()),
                        new Tag.IntTag(TagName.id(FileTag.LASTSHARED), lastSharedMillis / 1000)));
    }

    private static Known2Met.Entry entry(FileHasher.Result result) {
        return new Known2Met.Entry(
                result.aich().orElseThrow().masterHash(),
                result.aich().orElseThrow().blockHashes());
    }

    private SharedFiles open() throws Exception {
        return onLoop(this::openOnLoop);
    }

    private SharedFiles openOnLoop() {
        return SharedFiles.open(
                this.loop,
                this.config,
                () -> List.of(this.incoming),
                () -> List.of(this.dir.resolve("temp")),
                new SharedFiles.Listener() {
                    @Override
                    public void shared(SharedFiles.SharedFile file) {}

                    @Override
                    public void unshared(SharedFiles.SharedFile file) {}
                });
    }

    private void closeShares() throws Exception {
        if (this.shares != null) {
            onLoop(() -> {
                this.shares.close();
                return null;
            });
            this.shares = null;
        }
    }

    private <T> T onLoop(Callable<T> task) throws Exception {
        return this.loop.submit(task).get(30, TimeUnit.SECONDS);
    }

    private static byte[] bytes(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }
}
