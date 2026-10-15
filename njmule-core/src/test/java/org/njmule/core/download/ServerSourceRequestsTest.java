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

import com.google.common.base.Ticker;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.Hash16;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.files.PartFile;
import org.njmule.core.peer.Identities;
import org.njmule.core.peer.IdentityHandshake;
import org.njmule.core.peer.PeerLinks;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.util.Ipv4;

/** How often the downloads ask the connected server for sources; the downloads tick every second. */
@Timeout(60)
class ServerSourceRequestsTest {

    private static final int PORT = 4662;
    private static final int OUR_IP = Ipv4.of(198, 51, 100, 7);
    private static final Duration SETTLE = Duration.ofMillis(2500);

    @TempDir
    Path dir;

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final ManualTicker ticker = new ManualTicker();
    private final List<Hash16> asked = new CopyOnWriteArrayList<>();
    private DownloadManager manager;

    @AfterEach
    void stop() throws Exception {
        if (this.manager != null) {
            onLoop(() -> {
                this.manager.close();
                return null;
            });
        }
        this.loop.close();
    }

    @Test
    void theServerIsAskedForFifteenFilesAtOnceThenNoMore() throws Exception {
        givenAServerThatTakesSourceRequests();
        givenDownloads(20);

        whenTheDownloadsHaveAskedFor(15);

        thenNoMoreIsAskedForAWhile();
    }

    @Test
    void oneMoreFileIsAskedForEveryTwentySeconds() throws Exception {
        givenAServerThatTakesSourceRequests();
        givenDownloads(20);
        givenTheDownloadsHaveAskedFor(15);

        whenTimePasses(DownloadManager.SERVER_REQUEST_CREDIT.minusSeconds(1));
        thenNoMoreIsAskedForAWhile();
        whenTimePasses(Duration.ofSeconds(1));

        thenTheDownloadsHaveAskedFor(16);
        thenNoMoreIsAskedForAWhile();
    }

    private void givenAServerThatTakesSourceRequests() throws Exception {
        SecureRandom random = new SecureRandom();
        Identities identities = new Identities(
                IdentityHandshake.Identity.of(Identities.loadOrCreateKey(this.dir, random)),
                hash -> Optional.empty(),
                (hash, key) -> {},
                Optional::empty,
                random);
        Hello hello = Hello.of(Hash16.parseHex("ab".repeat(16)), Ipv4.toEd2k(OUR_IP), PORT, "test", 0, 0, 0, 0, 0, 0);
        this.manager = onLoop(() -> DownloadManager.open(
                this.loop,
                this.dir.resolve("temp"),
                () -> this.dir.resolve("incoming"),
                () -> hello,
                () -> new DownloadManager.Settings(ObfuscationMode.SUPPORTED, 100, PORT, 0, true),
                new Server(),
                ip -> false,
                random,
                this.ticker,
                new PeerLinks(this.loop, identities, link -> Optional.empty()),
                (hash, bytes) -> {},
                Optional::empty,
                List::of,
                new Quiet()));
    }

    private void givenDownloads(int count) throws Exception {
        onLoop(() -> {
            for (int i = 0; i < count; i++) {
                Hash16 hash = Hash16.parseHex(HexFormat.of().toHexDigits(i + 1L).repeat(2));
                this.manager.add(Ed2kLink.FileLink.of("file" + i + ".bin", 5000, hash));
            }
            return null;
        });
    }

    private void givenTheDownloadsHaveAskedFor(int files) throws InterruptedException {
        whenTheDownloadsHaveAskedFor(files);
    }

    private void whenTheDownloadsHaveAskedFor(int files) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (this.asked.size() < files && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(this.asked).hasSize(files);
    }

    private void whenTimePasses(Duration time) {
        this.ticker.advance(time);
    }

    private void thenNoMoreIsAskedForAWhile() throws InterruptedException {
        int before = this.asked.size();
        Thread.sleep(SETTLE.toMillis());
        assertThat(this.asked).hasSize(before);
    }

    private void thenTheDownloadsHaveAskedFor(int files) throws InterruptedException {
        whenTheDownloadsHaveAskedFor(files);
        assertThat(this.asked).doesNotHaveDuplicates();
    }

    private <T> T onLoop(Callable<T> task) throws Exception {
        return this.loop.submit(task).get(30, TimeUnit.SECONDS);
    }

    private final class Server implements DownloadManager.ServerLink {
        @Override
        public boolean askSources(Hash16 file, long size) {
            ServerSourceRequestsTest.this.asked.add(file);
            return true;
        }

        @Override
        public long clientId() {
            return Ipv4.toEd2k(OUR_IP);
        }

        @Override
        public int serverIp() {
            return 0;
        }

        @Override
        public boolean requestCallback(long lowId) {
            return false;
        }
    }

    private static final class ManualTicker extends Ticker {
        private final AtomicLong nanos = new AtomicLong();

        void advance(Duration time) {
            this.nanos.addAndGet(time.toNanos());
        }

        @Override
        public long read() {
            return this.nanos.get();
        }
    }

    private static final class Quiet implements DownloadManager.Listener {
        @Override
        public void added(DownloadInfo download) {}

        @Override
        public void progress(DownloadInfo download) {}

        @Override
        public void completed(DownloadInfo download, Path file, PartFile part) {}

        @Override
        public void failed(DownloadInfo download, String reason) {}

        @Override
        public void removed(Hash16 hash) {}
    }
}
