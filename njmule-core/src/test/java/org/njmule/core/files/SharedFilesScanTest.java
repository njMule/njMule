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

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.persist.format.Known2Met;
import org.njmule.core.persist.format.KnownMet;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;

/** How the scan of the shared directories picks the files it shares. */
@Timeout(60)
class SharedFilesScanTest {

    private static final Hash16 REMEMBERED = Hash16.parseHex("5e".repeat(16));

    @TempDir
    Path dir;

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final BlockingQueue<String> events = new LinkedBlockingQueue<>();
    private Path config;
    private Path incoming;
    private Path other;
    private SharedFiles shares;
    private final List<String> sharedNames = new ArrayList<>();

    @AfterEach
    void stop() throws Exception {
        if (this.shares != null) {
            this.onLoop(() -> {
                this.shares.close();
                return null;
            });
        }
        this.loop.close();
    }

    @Test
    void filesOtherProgramsLeaveBehindOrAreStillWritingAreNeverShared() throws Exception {
        this.givenTheIncomingDirectoryHolds(
                "real.bin",
                "~$report.docx",
                "._photo.jpg",
                ".~lock.notes.odt#",
                "ehthumbs.db",
                ".nfs0000000001",
                ".syncthing.film.avi",
                ".sb-1234-abcd",
                "video.mp4.crdownload",
                "page.html.partial",
                "song.mp3.download",
                "movie.mkv.!qB",
                "album.zip.!ut",
                "draft.txt~");

        this.whenTheDirectoriesAreScanned("real.bin");

        this.thenOnlyTheseAreShared("real.bin");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aFileAnotherProgramLocksForAMomentIsSharedOnceItIsFree() throws Exception {
        this.givenTheIncomingDirectoryHolds("locked.bin");
        this.givenAnotherProgramLocksItFor("locked.bin", Duration.ofMillis(1500));

        this.whenTheDirectoriesAreScanned("locked.bin");

        this.thenOnlyTheseAreShared("locked.bin");
    }

    private void givenTheIncomingDirectoryHolds(String... names) throws Exception {
        this.config = Files.createDirectories(this.dir.resolve("config"));
        this.incoming = Files.createDirectories(this.dir.resolve("incoming"));
        long seed = 1;
        for (String name : names) {
            Files.write(this.incoming.resolve(name), bytes(3000, seed++));
        }
    }

    @Test
    void aFileThatChangesWhileItIsHashedIsSharedWithItsNewContent() throws Exception {
        this.givenTheIncomingDirectoryHolds();
        this.givenALargeFileKeepsTheHashingBusy();
        this.givenAnotherDirectoryHolds("changing.bin", bytes(3000, 7));

        this.whenThatDirectoryIsSharedAndTheFileChangesAtOnceTo(bytes(5000, 8));

        this.thenTheFileIsSharedWithItsContentAndDateNow("changing.bin");
    }

    @ParameterizedTest
    @ValueSource(longs = {2, -2, 3600, -3600, 3602, -3598})
    void aKnownFileWhoseDateMovedAsOnFatOrAcrossDaylightSavingIsNotHashedAgain(long seconds) throws Exception {
        this.givenTheIncomingDirectoryHolds("dated.bin");
        this.givenKnownMetRemembersItUnderAHashOfItsOwnAtADateOffBy("dated.bin", seconds);

        this.whenTheDirectoriesAreScanned("dated.bin");

        this.thenItIsSharedWithTheRememberedHash("dated.bin");
        this.thenKnownMetHoldsOneRecordOfItAtTheDateItHasNow("dated.bin");
    }

    @ParameterizedTest
    @ValueSource(longs = {3, -3, 60, 3597, 3603, 7200})
    void aKnownFileWhoseDateMovedOtherwiseIsHashedAgain(long seconds) throws Exception {
        this.givenTheIncomingDirectoryHolds("dated.bin");
        this.givenKnownMetRemembersItUnderAHashOfItsOwnAtADateOffBy("dated.bin", seconds);

        this.whenTheDirectoriesAreScanned("dated.bin");

        this.thenItIsSharedWithTheHashOfItsContent("dated.bin");
    }

    private void givenKnownMetRemembersItUnderAHashOfItsOwnAtADateOffBy(String name, long seconds) throws Exception {
        Path file = this.incoming.resolve(name);
        long remembered = 1_700_000_000L;
        Files.setLastModifiedTime(file, FileTime.fromMillis((remembered + seconds) * 1000));
        Hash20 tree = Hash20.parseBase32("T".repeat(32));
        KnownMet.KnownFile record = new KnownMet.KnownFile(
                remembered,
                REMEMBERED,
                List.of(),
                List.of(
                        new Tag.StringTag(TagName.id(FileTag.FILENAME), name),
                        new Tag.IntTag(TagName.id(FileTag.FILESIZE), Files.size(file)),
                        new Tag.StringTag(TagName.id(FileTag.AICH_HASH), tree.toBase32())));
        Files.write(this.config.resolve(SharedFiles.KNOWN_MET), KnownMet.write(List.of(record)));
        Files.write(
                this.config.resolve(SharedFiles.KNOWN2_MET),
                Known2Met.write(List.of(new Known2Met.Entry(tree, List.of(tree)))));
    }

    private void thenItIsSharedWithTheRememberedHash(String name) throws Exception {
        assertThat(this.sharedFile(name).hash()).isEqualTo(REMEMBERED);
    }

    private void thenItIsSharedWithTheHashOfItsContent(String name) throws Exception {
        byte[] content = Files.readAllBytes(this.incoming.resolve(name));
        assertThat(this.sharedFile(name).hash()).isEqualTo(Ed2k.md4(content, 0, content.length));
    }

    private void thenKnownMetHoldsOneRecordOfItAtTheDateItHasNow(String name) throws Exception {
        this.onLoop(() -> {
            this.shares.close();
            return null;
        });
        this.shares = null;
        long date = Files.getLastModifiedTime(this.incoming.resolve(name)).toMillis() / 1000;
        assertThat(KnownMet.read(Files.readAllBytes(this.config.resolve(SharedFiles.KNOWN_MET))))
                .singleElement()
                .satisfies(record -> assertThat(record.mtime()).isEqualTo(date));
    }

    private SharedFiles.SharedFile sharedFile(String name) throws Exception {
        return this.onLoop(() -> this.shares.all().stream()
                .filter(f -> f.name().equals(name))
                .findFirst()
                .orElseThrow());
    }

    private void givenALargeFileKeepsTheHashingBusy() throws Exception {
        Files.write(this.incoming.resolve("large.bin"), bytes(32_000_000, 9));
        this.shares = this.open();
    }

    private void givenAnotherDirectoryHolds(String name, byte[] content) throws Exception {
        this.other = Files.createDirectories(this.dir.resolve("other"));
        Files.write(this.other.resolve(name), content);
    }

    private void whenThatDirectoryIsSharedAndTheFileChangesAtOnceTo(byte[] content) throws Exception {
        Path file = this.other.resolve("changing.bin");
        this.onLoop(() -> {
            this.shares.addDirectory(this.other);
            Files.write(file, content);
            Files.setLastModifiedTime(
                    file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 60_000));
            return null;
        });
        this.awaitShared("changing.bin");
    }

    private void thenTheFileIsSharedWithItsContentAndDateNow(String name) throws Exception {
        Path file = this.other.resolve(name);
        byte[] content = Files.readAllBytes(file);
        SharedFiles.SharedFile shared = this.onLoop(() -> this.shares.all().stream()
                .filter(f -> f.path().equals(file))
                .findFirst()
                .orElseThrow());
        assertThat(shared.hash()).isEqualTo(Ed2k.md4(content, 0, content.length));
        assertThat(shared.size()).isEqualTo(content.length);
        assertThat(shared.mtime()).isEqualTo(Files.getLastModifiedTime(file).toMillis() / 1000);
    }

    // A lock on Windows keeps other handles from reading the file, as a program writing it does.
    private void givenAnotherProgramLocksItFor(String name, Duration time) throws Exception {
        FileChannel channel = FileChannel.open(this.incoming.resolve(name), StandardOpenOption.WRITE);
        FileLock lock = channel.lock();
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(time);
                lock.release();
                channel.close();
            } catch (InterruptedException | IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    // Waits for the file expected last, then a moment for any other.
    private void whenTheDirectoriesAreScanned(String awaited) throws Exception {
        this.shares = this.open();
        this.awaitShared(awaited);
        this.collect(this.events.poll(1, TimeUnit.SECONDS));
    }

    private void awaitShared(String name) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!this.sharedNames.contains(name) && System.nanoTime() < deadline) {
            this.collect(this.events.poll(100, TimeUnit.MILLISECONDS));
        }
    }

    private SharedFiles open() throws Exception {
        return this.onLoop(() -> SharedFiles.open(
                this.loop,
                this.config,
                () -> List.of(this.incoming),
                () -> List.of(this.dir.resolve("temp")),
                new SharedFiles.Listener() {
                    @Override
                    public void shared(SharedFiles.SharedFile file) {
                        SharedFilesScanTest.this.events.add("shared " + file.name());
                    }

                    @Override
                    public void unshared(SharedFiles.SharedFile file) {
                        SharedFilesScanTest.this.events.add("unshared " + file.name());
                    }
                }));
    }

    private void collect(String event) {
        if (event != null && event.startsWith("shared ")) {
            this.sharedNames.add(event.substring("shared ".length()));
        }
    }

    private void thenOnlyTheseAreShared(String... names) throws Exception {
        assertThat(this.sharedNames).containsExactlyInAnyOrder(names);
        assertThat(this.onLoop(() -> this.shares.all().stream()
                        .map(SharedFiles.SharedFile::name)
                        .toList()))
                .containsExactlyInAnyOrder(names);
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
