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
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
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

@Timeout(60)
class DownloadManagerTest {

    private static final int PORT = 4662;
    private static final int OUR_IP = Ipv4.of(198, 51, 100, 7);
    private static final int BLOCKED_IP = Ipv4.of(203, 0, 113, 9);

    @TempDir
    Path dir;

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final CompletableFuture<Consumer<List<InetSocketAddress>>> kadSearch = new CompletableFuture<>();
    private DownloadManager manager;

    @AfterEach
    void stop() throws Exception {
        if (this.manager != null) {
            this.onLoop(() -> {
                this.manager.close();
                return null;
            });
        }
        this.loop.close();
    }

    @Test
    void keepsOnlyUsableSourcesFromKad() throws Exception {
        DownloadManager manager = this.manager();
        Hash16 hash = Hash16.parseHex("12".repeat(16));
        this.onLoop(() -> manager.add(Ed2kLink.FileLink.of("kad.bin", 5000, hash)));
        Consumer<List<InetSocketAddress>> found = this.kadSearch.get(30, TimeUnit.SECONDS);

        DownloadInfo info = this.onLoop(() -> {
            manager.pause(hash);
            found.accept(List.of(
                    address(Ipv4.of(127, 0, 0, 1), PORT),
                    address(Ipv4.of(224, 0, 0, 1), PORT),
                    address(BLOCKED_IP, PORT),
                    address(OUR_IP, PORT),
                    address(Ipv4.of(192, 0, 2, 10), 0),
                    address(Ipv4.of(192, 0, 2, 10), PORT)));
            return manager.download(hash).orElseThrow();
        });

        assertThat(info.sources()).isEqualTo(1);
    }

    @Test
    void takesTheLinkSourcesTheIpFilterAllows() throws Exception {
        DownloadManager manager = this.manager();
        Ed2kLink.FileLink link = (Ed2kLink.FileLink) Ed2kLink.parse("ed2k://|file|link.bin|5000|" + "34".repeat(16)
                + "|/|sources,127.0.0.1:" + PORT + "," + Ipv4.format(BLOCKED_IP) + ":" + PORT + "|/");

        DownloadInfo info = this.onLoop(() -> {
            DownloadInfo added = manager.add(link);
            manager.pause(link.hash());
            return added;
        });

        assertThat(info.sources()).isEqualTo(1);
    }

    @Test
    void stoppingForgetsTheSourcesAndKeepsThePriority() throws Exception {
        DownloadManager manager = this.manager();
        Ed2kLink.FileLink link = (Ed2kLink.FileLink) Ed2kLink.parse(
                "ed2k://|file|stop.bin|5000|" + "56".repeat(16) + "|/|sources,192.0.2.10:" + PORT + "|/");

        DownloadInfo stopped = this.onLoop(() -> {
            manager.add(link);
            manager.setPriority(link.hash(), DownloadPriority.HIGH);
            manager.stop(link.hash());
            return manager.download(link.hash()).orElseThrow();
        });

        assertThat(stopped.status()).isEqualTo(DownloadInfo.Status.STOPPED);
        assertThat(stopped.sources()).isZero();
        assertThat(stopped.priority()).isEqualTo(DownloadPriority.HIGH);
        DownloadInfo resumed = this.onLoop(() -> {
            manager.resume(link.hash());
            DownloadInfo info = manager.download(link.hash()).orElseThrow();
            manager.pause(link.hash());
            return info;
        });
        assertThat(resumed.status()).isEqualTo(DownloadInfo.Status.DOWNLOADING);
        assertThat(this.onLoop(() -> manager.stop(Hash16.parseHex("00".repeat(16)))))
                .isFalse();
    }

    @Test
    void higherPrioritiesAskTheServerFirst() throws Exception {
        HighIdServer server = new HighIdServer(true);
        DownloadManager manager = this.manager(server);
        Hash16 low = Hash16.parseHex("78".repeat(16));
        Hash16 high = Hash16.parseHex("9a".repeat(16));

        this.onLoop(() -> {
            manager.add(Ed2kLink.FileLink.of("low.bin", 5000, low));
            manager.setPriority(low, DownloadPriority.LOW);
            manager.add(Ed2kLink.FileLink.of("high.bin", 5000, high));
            manager.setPriority(high, DownloadPriority.HIGH);
            return null;
        });
        for (int i = 0; i < 100 && server.asked.size() < 2; i++) {
            Thread.sleep(100);
        }

        assertThat(server.asked).startsWith(high, low);
    }

    private DownloadManager manager() throws Exception {
        return this.manager(new HighIdServer(false));
    }

    private DownloadManager manager(HighIdServer server) throws Exception {
        SecureRandom random = new SecureRandom();
        Identities identities = new Identities(
                IdentityHandshake.Identity.of(Identities.loadOrCreateKey(this.dir, random)),
                hash -> Optional.empty(),
                (hash, key) -> {},
                Optional::empty,
                random);
        Hello hello = Hello.of(Hash16.parseHex("ab".repeat(16)), Ipv4.toEd2k(OUR_IP), PORT, "test", 0, 0, 0, 0, 0, 0);
        DownloadManager.KadSources kad = (file, size, sources) -> this.kadSearch.complete(sources);
        this.manager = this.onLoop(() -> DownloadManager.open(
                this.loop,
                this.dir.resolve("temp"),
                () -> this.dir.resolve("incoming"),
                () -> hello,
                () -> new DownloadManager.Settings(ObfuscationMode.SUPPORTED, 100, PORT, 0),
                server,
                ip -> ip == BLOCKED_IP,
                random,
                Ticker.systemTicker(),
                new PeerLinks(this.loop, identities, link -> Optional.empty()),
                (hash, bytes) -> {},
                () -> Optional.of(kad),
                new Quiet()));
        return this.manager;
    }

    private static InetSocketAddress address(int ip, int port) {
        return new InetSocketAddress(Ipv4.format(ip), port);
    }

    private <T> T onLoop(Callable<T> task) throws Exception {
        return this.loop.submit(task).get(30, TimeUnit.SECONDS);
    }

    // Answers source requests as asked to, and keeps the files they were for.
    private static final class HighIdServer implements DownloadManager.ServerLink {
        private final boolean answers;
        private final List<Hash16> asked = new CopyOnWriteArrayList<>();

        HighIdServer(boolean answers) {
            this.answers = answers;
        }

        @Override
        public boolean askSources(Hash16 file, long size) {
            if (this.answers) {
                this.asked.add(file);
            }
            return this.answers;
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
