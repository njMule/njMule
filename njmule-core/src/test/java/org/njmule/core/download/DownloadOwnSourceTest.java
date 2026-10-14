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
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.SourceInfo;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.files.PartFile;
import org.njmule.core.peer.Identities;
import org.njmule.core.peer.IdentityHandshake;
import org.njmule.core.peer.PeerLinks;
import org.njmule.core.protocol.ed2k.ClientMessages;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.ed2k.ServerMessages;
import org.njmule.core.protocol.ed2k.SourceExchange;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.util.Ipv4;

/** We never take ourselves as a source, however the network tells of us. */
@Timeout(90)
class DownloadOwnSourceTest {

    private static final int PORT = 4662;
    private static final Hash16 OURS = Hash16.parseHex("ab".repeat(16));
    private static final Hash16 PEER = Hash16.parseHex("5a".repeat(16));
    private static final int OUR_KAD_IP = Ipv4.of(198, 51, 100, 8);
    private static final int THEIR_IP = Ipv4.of(192, 0, 2, 70);
    private static final Hash16 FILE = Hash16.parseHex("65".repeat(16));
    private static final Hash16 OTHER_FILE = Hash16.parseHex("66".repeat(16));

    @TempDir
    Path dir;

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final Ticker ticker = new Ticker() {
        @Override
        public long read() {
            return DownloadOwnSourceTest.this.now.get();
        }
    };
    private final List<AutoCloseable> resources = new ArrayList<>();
    private final AtomicReference<Consumer<List<DownloadManager.KadSource>>> kadSearch = new AtomicReference<>();
    private DownloadManager manager;
    private ScriptedSource source;
    private ScriptedSource.Link link;

    @AfterEach
    void stop() throws Exception {
        if (this.manager != null) {
            this.onLoop(() -> {
                this.manager.close();
                return null;
            });
        }
        this.loop.close();
        Collections.reverse(this.resources);
        for (AutoCloseable resource : this.resources) {
            resource.close();
        }
    }

    @Test
    void shouldLeaveOutAServerSourceWithOurUserHash() throws Exception {
        this.givenADownload();

        this.whenTheServerFindsSources(
                new ServerMessages.Source(Ipv4.toEd2k(Ipv4.of(192, 0, 2, 71)), PORT, 0, Optional.of(OURS)),
                new ServerMessages.Source(Ipv4.toEd2k(THEIR_IP), PORT, 0, Optional.of(PEER)));

        this.thenTheSourcesOfTheDownloadAre(at(THEIR_IP, PORT));
    }

    @Test
    void shouldLeaveOutAKadSourceWithOurUserHashOrAtOurAddress() throws Exception {
        this.givenADownload();
        this.givenKadSearchesForItsSources();

        this.whenKadFindsSources(
                kadSource(Ipv4.of(192, 0, 2, 72), PORT, OURS),
                kadSource(OUR_KAD_IP, PORT, PEER),
                kadSource(OUR_KAD_IP, PORT + 1, PEER),
                kadSource(THEIR_IP, PORT, PEER));

        this.thenTheSourcesOfTheDownloadAre(at(OUR_KAD_IP, PORT + 1), at(THEIR_IP, PORT));
    }

    @Test
    void shouldLeaveOutAnExchangedSourceWithOurUserHash() throws Exception {
        this.givenASourceOf(PEER, Hello.MISC2_SOURCE_EXCHANGE2);

        this.whenTheSourceTellsOfSources(exchanged(Ipv4.of(192, 0, 2, 73), OURS), exchanged(THEIR_IP, PEER));

        this.thenTheSourcesOfTheDownloadAre(this.source.address(), at(THEIR_IP, PORT));
    }

    @Test
    void shouldDropASourceThatTurnsOutToBeOurselvesFromEveryDownload() throws Exception {
        this.givenASourceOf(OURS, 0);
        this.givenTheSourceIsASourceOfAnotherDownloadToo();

        this.whenTheSourceAnswersOurHello();

        this.thenTheConnectionCloses();
        this.thenNoDownloadHasASource();
    }

    private void givenADownload() throws Exception {
        this.manager = this.open();
        this.onLoop(() -> this.manager.add(Ed2kLink.FileLink.of("ours.bin", 5000, FILE)));
    }

    private void givenKadSearchesForItsSources() throws Exception {
        this.await("Kad searched", () -> this.kadSearch.get() != null);
    }

    private void givenASourceOf(Hash16 userHash, int misc2) throws Exception {
        this.source = new ScriptedSource(userHash, 0, misc2);
        this.resources.add(this.source);
        this.manager = this.open();
        this.onLoop(() -> this.manager.add(this.source.link("ours.bin", 5000, FILE)));
    }

    private void givenTheSourceIsASourceOfAnotherDownloadToo() throws Exception {
        this.onLoop(() -> this.manager.add(this.source.link("other.bin", 5000, OTHER_FILE)));
    }

    private void whenTheServerFindsSources(ServerMessages.Source... found) throws Exception {
        this.onLoop(() -> {
            this.manager.foundSources(new ServerMessages.FoundSources(FILE, List.of(found)));
            return null;
        });
    }

    private void whenKadFindsSources(DownloadManager.KadSource... found) throws Exception {
        this.onLoop(() -> {
            this.kadSearch.get().accept(List.of(found));
            return null;
        });
    }

    // Asked for other sources with the file, it tells of them, then queues us.
    private void whenTheSourceTellsOfSources(SourceExchange.Source... found) throws Exception {
        this.link = this.source.accept();
        this.link.until(Opcodes.Extended.REQUESTSOURCES2);
        this.link.send(new SourceExchange.Answer(4, FILE, List.of(found)).encode());
        this.link.send(new ClientMessages.FileStatus(FILE, 0, new BitSet()).encode());
        this.link.until(Opcodes.Client.STARTUPLOADREQ);
    }

    private void whenTheSourceAnswersOurHello() throws Exception {
        this.link = this.source.accept();
    }

    private void thenTheSourcesOfTheDownloadAre(InetSocketAddress... expected) throws Exception {
        List<String> addresses = new ArrayList<>();
        for (InetSocketAddress address : expected) {
            addresses.add(address.getAddress().getHostAddress() + ":" + address.getPort());
        }
        this.await(addresses + " as sources", () -> this.manager.sources().size() == expected.length);
        assertThat(this.onLoop(() ->
                        this.manager.sources().stream().map(SourceInfo::address).toList()))
                .containsExactlyInAnyOrderElementsOf(addresses);
    }

    private void thenTheConnectionCloses() throws Exception {
        assertThat(this.link.closesWithin(ScriptedSource.WAIT)).isTrue();
    }

    private void thenNoDownloadHasASource() throws Exception {
        this.await("no source", () -> this.manager.sources().isEmpty());
    }

    private static InetSocketAddress at(int ip, int port) {
        return new InetSocketAddress(Ipv4.format(ip), port);
    }

    private static DownloadManager.KadSource kadSource(int ip, int port, Hash16 userHash) {
        return new DownloadManager.KadSource(at(ip, port), Optional.of(userHash), 0, Optional.empty());
    }

    private static SourceExchange.Source exchanged(int ip, Hash16 userHash) {
        return new SourceExchange.Source(ip, 0, PORT, 0, 0, Optional.of(userHash), 0);
    }

    private DownloadManager open() throws Exception {
        SecureRandom random = new SecureRandom();
        Identities identities = new Identities(
                IdentityHandshake.Identity.of(Identities.loadOrCreateKey(this.dir, random)),
                hash -> Optional.empty(),
                (hash, key) -> {},
                Optional::empty,
                random);
        Hello hello = Hello.of(OURS, 0, PORT, "test", 0, 0, 0, 0, 0, 0);
        DownloadManager.KadSources kad = new DownloadManager.KadSources() {
            @Override
            public boolean find(Hash16 file, long size, Consumer<List<DownloadManager.KadSource>> sources) {
                DownloadOwnSourceTest.this.kadSearch.compareAndSet(null, sources);
                return true;
            }

            @Override
            public int publicIp() {
                return OUR_KAD_IP;
            }
        };
        return this.onLoop(() -> DownloadManager.open(
                this.loop,
                this.dir.resolve("temp"),
                () -> this.dir.resolve("incoming"),
                () -> hello,
                () -> new DownloadManager.Settings(ObfuscationMode.SUPPORTED, 100, PORT, 0, true),
                new NoServer(),
                ip -> false,
                random,
                this.ticker,
                new PeerLinks(this.loop, identities, link -> Optional.empty()),
                (hash, bytes) -> {},
                () -> Optional.of(kad),
                List::of,
                new Quiet()));
    }

    private void await(String what, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + ScriptedSource.WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (this.onLoop(condition)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("waited in vain for " + what);
    }

    private <T> T onLoop(Callable<T> task) throws Exception {
        return this.loop.submit(task).get(30, TimeUnit.SECONDS);
    }

    // Not on a server: only Kad tells our address.
    private static final class NoServer implements DownloadManager.ServerLink {
        @Override
        public boolean askSources(Hash16 file, long size) {
            return false;
        }

        @Override
        public long clientId() {
            return 0;
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
