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
import org.njmule.core.api.Category;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;
import org.njmule.core.config.NextDownload;
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
    private List<Category> categories = List.of();
    private DownloadManager.Listener listener = new Quiet();

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
                    address(Ipv4.of(192, 168, 1, 20), PORT),
                    address(Ipv4.of(10, 1, 2, 3), PORT),
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
                + "|/|sources,127.0.0.1:" + PORT + "," + Ipv4.format(BLOCKED_IP) + ":" + PORT + ",172.20.0.9:" + PORT
                + "|/");

        DownloadInfo info = this.onLoop(() -> {
            DownloadInfo added = manager.add(link);
            manager.pause(link.hash());
            return added;
        });

        assertThat(info.sources()).as("the user chose them, LAN or loopback").isEqualTo(2);
        assertThat(this.onLoop(() -> manager.addSourceByHand(link.hash(), address(Ipv4.of(10, 0, 0, 5), PORT))))
                .isTrue();
        assertThat(this.onLoop(() -> manager.addSourceByHand(link.hash(), address(Ipv4.of(10, 0, 0, 5), PORT))))
                .as("known already")
                .isFalse();
        assertThat(this.onLoop(() -> manager.addSourceByHand(link.hash(), address(BLOCKED_IP, PORT))))
                .as("the IP filter still applies")
                .isFalse();
        assertThat(this.onLoop(() -> manager.download(link.hash()).orElseThrow().sources()))
                .isEqualTo(3);
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
    void resumesThePausedDownloadOfHighestPriorityAsAsked() throws Exception {
        this.categories =
                List.of(new Category(1, "Films", Optional.empty(), "", 1, DownloadPriority.AUTO, "*.mkv", false));
        DownloadManager manager = this.manager();
        Hash16 film = Hash16.parseHex("a1".repeat(16));
        Hash16 urgent = Hash16.parseHex("a2".repeat(16));
        Hash16 later = Hash16.parseHex("a3".repeat(16));
        this.onLoop(() -> {
            manager.add(Ed2kLink.FileLink.of("film.mkv", 5000, film));
            manager.add(Ed2kLink.FileLink.of("urgent.txt", 5000, urgent));
            manager.add(Ed2kLink.FileLink.of("later.txt", 5000, later));
            manager.setPriority(film, DownloadPriority.LOW);
            manager.setPriority(urgent, DownloadPriority.HIGH);
            manager.setPriority(later, DownloadPriority.NORMAL);
            manager.pause(film);
            manager.stop(urgent);
            manager.pause(later);
            return null;
        });

        assertThat(this.onLoop(() -> manager.resumeNext(1, NextDownload.NONE))).isEmpty();
        assertThat(this.onLoop(() -> manager.resumeNext(1, NextDownload.SAME_CATEGORY_FIRST)))
                .map(DownloadInfo::hash)
                .as("the category's, though of low priority")
                .contains(film);
        assertThat(this.onLoop(() -> manager.resumeNext(1, NextDownload.SAME_CATEGORY)))
                .as("none left in the category")
                .isEmpty();
        assertThat(this.onLoop(() -> manager.resumeNext(1, NextDownload.SAME_CATEGORY_FIRST)))
                .map(DownloadInfo::hash)
                .as("then any, a stopped one too")
                .contains(urgent);
        assertThat(this.onLoop(() -> manager.resumeNext(Category.NONE, NextDownload.ANY)))
                .map(DownloadInfo::status)
                .contains(DownloadInfo.Status.DOWNLOADING);
        assertThat(this.onLoop(() -> manager.resumeNext(Category.NONE, NextDownload.ANY)))
                .isEmpty();
    }

    @Test
    void newDownloadsGoInTheFirstCategoryTheirNameMatches() throws Exception {
        this.categories = List.of(
                new Category(1, "Films", Optional.empty(), "", 1, DownloadPriority.HIGH, "*.mkv", false),
                new Category(2, "Video", Optional.empty(), "", 0, DownloadPriority.AUTO, "mkv|avi", false));
        DownloadManager manager = this.manager();
        Hash16 film = Hash16.parseHex("bc".repeat(16));
        Hash16 other = Hash16.parseHex("cd".repeat(16));

        DownloadInfo added = this.onLoop(() -> manager.add(Ed2kLink.FileLink.of("Film.MKV", 5000, film)));
        DownloadInfo plain = this.onLoop(() -> manager.add(Ed2kLink.FileLink.of("notes.txt", 5000, other)));

        assertThat(added.category()).isEqualTo(1);
        assertThat(added.priority()).as("the category's priority").isEqualTo(DownloadPriority.HIGH);
        assertThat(plain.category()).isEqualTo(Category.NONE);
        assertThat(this.onLoop(() -> manager.setCategory(other, 2))).isTrue();
        assertThat(this.onLoop(() -> manager.setCategory(other, 9)))
                .as("no such category")
                .isFalse();
        assertThat(this.onLoop(() -> manager.download(other).orElseThrow().category()))
                .isEqualTo(2);

        this.categories = List.of(this.categories.getFirst());
        this.onLoop(() -> {
            manager.categoriesChanged();
            manager.pause(film);
            manager.pause(other);
            return null;
        });
        assertThat(this.onLoop(() -> manager.download(other).orElseThrow().category()))
                .as("its category is gone")
                .isEqualTo(Category.NONE);
        assertThat(this.onLoop(() -> manager.download(film).orElseThrow().category()))
                .isEqualTo(1);
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

    @Test
    void resumingARunningDownloadChangesNothing() throws Exception {
        HighIdServer server = new HighIdServer(true);
        DownloadManager manager = this.manager(server);
        Hash16 hash = Hash16.parseHex("bd".repeat(16));
        this.onLoop(() -> manager.add(Ed2kLink.FileLink.of("running.bin", 5000, hash)));
        for (int i = 0; i < 100 && server.asked.isEmpty(); i++) {
            Thread.sleep(100);
        }
        assertThat(server.asked).hasSize(1);

        assertThat(this.onLoop(() -> manager.resume(hash)))
                .as("it runs already")
                .isFalse();
        Thread.sleep(2500);

        assertThat(server.asked).as("not asked again before its time").hasSize(1);
        this.onLoop(() -> manager.pause(hash));
    }

    @Test
    void theFirstAddedStaysFirstAfterARestart() throws Exception {
        DownloadManager manager = this.manager();
        Hash16 gone = Hash16.parseHex("e1".repeat(16));
        Hash16 first = Hash16.parseHex("e2".repeat(16));
        Hash16 second = Hash16.parseHex("e3".repeat(16));
        this.onLoop(() -> {
            manager.add(Ed2kLink.FileLink.of("gone.bin", 5000, gone));
            manager.add(Ed2kLink.FileLink.of("first.bin", 5000, first));
            manager.cancel(gone);
            // Takes the part file number the cancelled download left.
            manager.add(Ed2kLink.FileLink.of("second.bin", 5000, second));
            manager.pause(first);
            manager.pause(second);
            manager.close();
            return null;
        });

        DownloadManager again = this.manager();

        assertThat(this.onLoop(() -> again.resumeNext(Category.NONE, NextDownload.ANY)))
                .map(DownloadInfo::hash)
                .contains(first);
        this.onLoop(() -> again.pause(first));
    }

    @Test
    void refusesSourcesNoClientCanHave() throws Exception {
        DownloadManager manager = this.manager();
        Ed2kLink.FileLink link = (Ed2kLink.FileLink) Ed2kLink.parse("ed2k://|file|odd.bin|5000|" + "4a".repeat(16)
                + "|/|sources,0.1.2.3:" + PORT + ",224.0.0.251:" + PORT + ",240.1.2.3:" + PORT
                + ",255.255.255.255:" + PORT + "|/");

        DownloadInfo info = this.onLoop(() -> {
            DownloadInfo added = manager.add(link);
            manager.pause(link.hash());
            return added;
        });

        assertThat(info.sources()).isZero();
        for (int ip : new int[] {0, Ipv4.of(0, 0, 0, 1), Ipv4.of(239, 255, 255, 250), -1}) {
            assertThat(this.onLoop(() -> manager.addSourceByHand(link.hash(), address(ip, PORT))))
                    .as(Ipv4.format(ip))
                    .isFalse();
        }
        assertThat(this.onLoop(() -> manager.addSourceByHand(link.hash(), address(Ipv4.of(127, 0, 0, 1), PORT))))
                .as("loopback, as the user chose")
                .isTrue();
    }

    @Test
    void theAddedEventTellsTheCategoryAndPriority() throws Exception {
        this.categories =
                List.of(new Category(1, "Films", Optional.empty(), "", 1, DownloadPriority.HIGH, "*.mkv", false));
        List<DownloadInfo> added = new CopyOnWriteArrayList<>();
        this.listener = new Quiet() {
            @Override
            public void added(DownloadInfo download) {
                added.add(download);
            }
        };
        DownloadManager manager = this.manager();
        Hash16 hash = Hash16.parseHex("4b".repeat(16));

        this.onLoop(() -> {
            manager.add(Ed2kLink.FileLink.of("film.mkv", 5000, hash));
            return manager.pause(hash);
        });

        assertThat(added).singleElement().satisfies(info -> {
            assertThat(info.category()).isEqualTo(1);
            assertThat(info.priority()).isEqualTo(DownloadPriority.HIGH);
        });
    }

    @Test
    void aLinkAddedAgainGivesSourcesOnlyWhenItIsOfTheSameFile() throws Exception {
        DownloadManager manager = this.manager();
        Hash16 hash = Hash16.parseHex("4c".repeat(16));
        Hash20 aich = Hash20.parseBase32("A".repeat(32));
        Hash20 other = Hash20.parseBase32("B".repeat(32));
        this.onLoop(() -> {
            manager.add(Ed2kLink.FileLink.of("same.bin", 5000, hash));
            return manager.pause(hash);
        });

        DownloadInfo bigger = this.onLoop(() -> manager.add(link(hash, 6000, Optional.empty(), Ipv4.of(192, 0, 2, 1))));
        assertThat(bigger.sources()).as("another size").isZero();
        DownloadInfo withAich =
                this.onLoop(() -> manager.add(link(hash, 5000, Optional.of(aich), Ipv4.of(192, 0, 2, 2))));
        assertThat(withAich.sources()).isEqualTo(1);
        assertThat(this.onLoop(() -> manager.find(hash).orElseThrow().file().aichHash()))
                .as("the link's AICH hash is taken")
                .contains(aich);
        DownloadInfo otherAich =
                this.onLoop(() -> manager.add(link(hash, 5000, Optional.of(other), Ipv4.of(192, 0, 2, 3))));
        assertThat(otherAich.sources()).as("another AICH hash").isEqualTo(1);
    }

    @Test
    void aPreviewNeedsTheStartOfTheFile() throws Exception {
        DownloadManager manager = this.manager();
        Hash16 hash = Hash16.parseHex("4d".repeat(16));
        this.onLoop(() -> {
            manager.add(Ed2kLink.FileLink.of("film.avi", 100_000, hash));
            return manager.pause(hash);
        });

        this.onLoop(() -> {
            manager.find(hash).orElseThrow().file().written(16 * 1024, 100_000);
            return null;
        });
        assertThat(this.onLoop(() -> manager.dataFile(hash)))
                .as("all but the start")
                .isEmpty();

        this.onLoop(() -> {
            manager.find(hash).orElseThrow().file().written(0, 16 * 1024);
            return null;
        });
        assertThat(this.onLoop(() -> manager.dataFile(hash))).isPresent();
    }

    private static Ed2kLink.FileLink link(Hash16 hash, long size, Optional<Hash20> aich, int source) {
        return new Ed2kLink.FileLink(
                "same.bin",
                size,
                hash,
                aich,
                List.of(),
                List.of(new Ed2kLink.Source(Ipv4.format(source), PORT)),
                List.of());
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
                () -> new DownloadManager.Settings(ObfuscationMode.SUPPORTED, 100, PORT, 0, true),
                server,
                ip -> ip == BLOCKED_IP,
                random,
                Ticker.systemTicker(),
                new PeerLinks(this.loop, identities, link -> Optional.empty()),
                (hash, bytes) -> {},
                () -> Optional.of(kad),
                () -> this.categories,
                this.listener));
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

    private static class Quiet implements DownloadManager.Listener {
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
