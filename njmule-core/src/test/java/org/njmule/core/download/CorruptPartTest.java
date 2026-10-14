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
package org.njmule.core.download;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.Hash16;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.util.Ipv4;

/** What becomes of a part that fails its check. */
@Timeout(60)
class CorruptPartTest {

    private static final int SIZE = (int) Ed2k.PARTSIZE + 1000;
    // The fourth block of the first part.
    private static final int BAD_BLOCK = 3 * Ed2k.EMBLOCKSIZE;
    private static final int SENDER = Ipv4.of(192, 0, 2, 33);

    @TempDir
    Path dir;

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final BlockingQueue<String> events = new LinkedBlockingQueue<>();
    private final byte[] data = new byte[SIZE];
    private DownloadQueue queue;
    private DownloadQueue.Download download;
    private Path finished;

    @AfterEach
    void stop() throws Exception {
        if (this.queue != null) {
            this.onLoop(() -> {
                this.queue.close();
                return null;
            });
        }
        this.loop.close();
    }

    @Test
    void aCorruptPartIsWholeAgainOnceItsBadBlockIsDownloadedAgain() throws Exception {
        this.givenADownloadOfTwoParts();
        this.givenItsFirstPartArrivedWithABadByteIn(BAD_BLOCK + 5);

        this.whenTheBlockArrivesAgainAt(BAD_BLOCK);

        this.thenTheFirstPartIsWholeAndChecked();
    }

    @Test
    void dataForAPartThatIsWholeAgainIsNotWrittenOverIt() throws Exception {
        this.givenADownloadOfTwoParts();
        this.givenItsFirstPartArrivedWithABadByteIn(BAD_BLOCK + 5);
        this.givenTheBlockArrivedAgainAt(BAD_BLOCK);

        this.whenBadDataForTheFirstPartArrivesAndThenTheSecondPart();

        this.thenTheFinishedFileHoldsTheGoodData();
    }

    @Test
    void theSourceThatAloneSentACorruptPartThreeTimesIsReported() throws Exception {
        this.givenADownloadOfTwoParts();
        this.givenItsFirstPartArrivedFromWithABadByteIn(SENDER, BAD_BLOCK + 5);
        this.givenItsFirstPartArrivedFromWithABadByteIn(SENDER, BAD_BLOCK + 5);

        this.whenItsFirstPartArrivesFromWithABadByteIn(SENDER, BAD_BLOCK + 5);

        this.thenTheSourceIsReportedForCorruptData(SENDER);
    }

    private void givenADownloadOfTwoParts() throws Exception {
        new Random(11).nextBytes(this.data);
        List<Hash16> parts =
                List.of(Ed2k.md4(this.data, 0, (int) Ed2k.PARTSIZE), Ed2k.md4(this.data, (int) Ed2k.PARTSIZE, 1000));
        this.queue = this.onLoop(() -> DownloadQueue.load(
                this.loop,
                this.dir.resolve("temp"),
                file -> this.dir.resolve("incoming"),
                () -> 0,
                new DownloadQueue.Listener() {
                    @Override
                    public void added(DownloadQueue.Download d) {}

                    @Override
                    public void progress(DownloadQueue.Download d) {}

                    @Override
                    public void completed(DownloadQueue.Download d, Path file) {
                        CorruptPartTest.this.events.add("completed " + file);
                    }

                    @Override
                    public void failed(DownloadQueue.Download d, String reason) {
                        CorruptPartTest.this.events.add("failed " + reason);
                    }

                    @Override
                    public void corrupt(DownloadQueue.Download d, int part) {
                        CorruptPartTest.this.events.add("corrupt " + part);
                    }

                    @Override
                    public void corruptSenders(DownloadQueue.Download d, Set<Integer> ips) {
                        CorruptPartTest.this.events.add("guilty " + ips);
                    }
                }));
        this.download = this.onLoop(() -> this.queue.add(new Ed2kLink.FileLink(
                "two.bin", SIZE, Ed2k.fileHash(parts), Optional.empty(), parts, List.of(), List.of())));
    }

    private void givenItsFirstPartArrivedWithABadByteIn(int offset) throws Exception {
        byte[] bad = Arrays.copyOf(this.data, (int) Ed2k.PARTSIZE);
        bad[offset] ^= 1;
        this.onLoop(() -> {
            this.queue.received(this.download, 0, bad);
            return null;
        });
        assertThat(this.events.poll(30, TimeUnit.SECONDS)).isEqualTo("corrupt 0");
    }

    private void givenItsFirstPartArrivedFromWithABadByteIn(int ip, int offset) throws Exception {
        this.whenItsFirstPartArrivesFromWithABadByteIn(ip, offset);
        assertThat(this.events.poll(30, TimeUnit.SECONDS)).isEqualTo("corrupt 0");
    }

    private void whenItsFirstPartArrivesFromWithABadByteIn(int ip, int offset) throws Exception {
        byte[] bad = Arrays.copyOf(this.data, (int) Ed2k.PARTSIZE);
        bad[offset] ^= 1;
        this.onLoop(() -> {
            this.queue.received(this.download, 0, bad, ip);
            return null;
        });
    }

    private void thenTheSourceIsReportedForCorruptData(int ip) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        String event;
        do {
            event = this.events.poll(100, TimeUnit.MILLISECONDS);
            assertThat(System.nanoTime()).as("reported").isLessThan(deadline);
        } while (event == null || !event.startsWith("guilty"));
        assertThat(event).isEqualTo("guilty " + Set.of(ip));
    }

    private void givenTheBlockArrivedAgainAt(int start) throws Exception {
        this.whenTheBlockArrivesAgainAt(start);
        this.thenTheFirstPartIsWholeAndChecked();
    }

    private void whenTheBlockArrivesAgainAt(int start) throws Exception {
        this.onLoop(() -> {
            this.queue.received(this.download, start, Arrays.copyOfRange(this.data, start, start + Ed2k.EMBLOCKSIZE));
            return null;
        });
    }

    private void whenBadDataForTheFirstPartArrivesAndThenTheSecondPart() throws Exception {
        byte[] bad = Arrays.copyOf(this.data, Ed2k.EMBLOCKSIZE);
        bad[7] ^= 1;
        this.onLoop(() -> {
            this.queue.received(this.download, 0, bad);
            this.queue.received(this.download, Ed2k.PARTSIZE, Arrays.copyOfRange(this.data, (int) Ed2k.PARTSIZE, SIZE));
            return null;
        });
        String event = this.events.poll(30, TimeUnit.SECONDS);
        assertThat(event).startsWith("completed ");
        this.finished = Path.of(event.substring("completed ".length()));
    }

    private void thenTheFirstPartIsWholeAndChecked() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!this.onLoop(() -> this.download.verifiedParts().get(0))) {
            assertThat(System.nanoTime()).as("the part checked").isLessThan(deadline);
            Thread.sleep(20);
        }
        assertThat(this.onLoop(() -> this.download.file().isComplete(0))).isTrue();
    }

    private void thenTheFinishedFileHoldsTheGoodData() throws Exception {
        assertThat(Files.readAllBytes(this.finished)).isEqualTo(this.data);
    }

    private <T> T onLoop(Callable<T> task) throws Exception {
        return this.loop.submit(task).get(30, TimeUnit.SECONDS);
    }
}
