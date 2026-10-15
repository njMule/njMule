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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.njmule.core.net.TcpListener;
import org.njmule.core.peer.Identities;
import org.njmule.core.peer.IdentityHandshake;
import org.njmule.core.peer.PeerGreetings;
import org.njmule.core.peer.PeerLink;
import org.njmule.core.peer.PeerLinks;
import org.njmule.core.protocol.ed2k.ClientMessages;
import org.njmule.core.protocol.ed2k.ClientReasks;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.security.ConnectionGuard;
import org.njmule.core.security.IpFilter;
import org.njmule.core.util.Ipv4;

/** Sources firewalled on Kad: asked to connect to us through their buddy or directly, then taken when they do. */
@Timeout(60)
class DownloadKadCallbackTest {

    private static final int PORT = 4662;
    private static final Hash16 OURS = Hash16.parseHex("ab".repeat(16));
    private static final Hash16 PEER = Hash16.parseHex("5f".repeat(16));
    private static final Hash16 FILE = Hash16.parseHex("61".repeat(16));
    private static final int BUDDY_IP = Ipv4.of(198, 51, 100, 20);
    private static final Hash16 BUDDY_ID = Hash16.parseHex("77".repeat(16));
    private static final int SOURCE_UDP_PORT = 4673;
    private static final Duration WAIT = ScriptedSource.WAIT;

    /** A callback request Kad was asked to send. */
    private record Asked(DownloadManager.KadSource source, Hash16 file) {}

    @TempDir
    Path dir;

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final Ticker ticker = new Ticker() {
        @Override
        public long read() {
            return DownloadKadCallbackTest.this.now.get();
        }
    };
    private final List<AutoCloseable> resources = new ArrayList<>();
    private final List<Asked> asked = new CopyOnWriteArrayList<>();
    private final List<String> routed = new CopyOnWriteArrayList<>();
    private final AtomicReference<Consumer<List<DownloadManager.KadSource>>> search = new AtomicReference<>();
    private final Udp udp = new Udp();
    private DownloadManager manager;
    private ScriptedSource source;
    private ScriptedSource.Link link;
    private int port;

    @BeforeEach
    void start() throws Exception {
        this.source =
                new ScriptedSource(PEER, ClientReasks.UDP_VERSION << Hello.MISC1_UDP_VERSION_SHIFT, 0, SOURCE_UDP_PORT);
        this.resources.add(this.source);
        SecureRandom random = new SecureRandom();
        Identities identities = new Identities(
                IdentityHandshake.Identity.of(Identities.loadOrCreateKey(this.dir, random)),
                hash -> Optional.empty(),
                (hash, key) -> {},
                Optional::empty,
                random);
        Hello hello = Hello.of(OURS, Ipv4.toEd2k(Ipv4.of(198, 51, 100, 7)), PORT, "test", 0, 0, 0, 0, 0, 0);
        PeerLinks links = new PeerLinks(this.loop, identities, link -> Optional.empty());
        DownloadManager.KadSources kad = new DownloadManager.KadSources() {
            @Override
            public boolean find(Hash16 file, long size, Consumer<List<DownloadManager.KadSource>> sources) {
                DownloadKadCallbackTest.this.search.compareAndSet(null, sources);
                return true;
            }

            @Override
            public boolean requestCallback(DownloadManager.KadSource firewalled, Hash16 file) {
                DownloadKadCallbackTest.this.asked.add(new Asked(firewalled, file));
                return true;
            }
        };
        this.manager = onLoop(() -> DownloadManager.open(
                this.loop,
                this.dir.resolve("temp"),
                () -> this.dir.resolve("incoming"),
                () -> hello,
                () -> new DownloadManager.Settings(ObfuscationMode.SUPPORTED, 100, PORT, 0, true),
                new NoServer(),
                ip -> false,
                random,
                this.ticker,
                links,
                (hash, bytes) -> {},
                () -> Optional.of(kad),
                List::of,
                new Quiet()));
        PeerGreetings greetings = new PeerGreetings(OURS, () -> hello, () -> true, ip -> false, random);
        greetings.routeTo((connection, peer) -> {
            try {
                return Optional.of(this.loop
                        .submit(() -> {
                            PeerLink incoming = links.incoming(connection, peer);
                            boolean adopted = this.manager.adopt(incoming);
                            if (!adopted) {
                                incoming.disconnect();
                            }
                            this.routed.add(adopted ? "adopted" : "refused");
                            return incoming;
                        })
                        .get(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (ExecutionException | TimeoutException e) {
                return Optional.empty();
            }
        });
        TcpListener listener = TcpListener.open(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                new ConnectionGuard(
                        IpFilter::empty,
                        new ConnectionGuard.Limits(500, 100, 1000, Duration.ofSeconds(5)),
                        Ticker.systemTicker()),
                greetings::accepted);
        this.resources.add(greetings);
        this.resources.add(listener);
        this.port = listener.port();
    }

    @AfterEach
    void stop() throws Exception {
        onLoop(() -> {
            this.manager.close();
            return null;
        });
        this.loop.close();
        Collections.reverse(this.resources);
        for (AutoCloseable resource : this.resources) {
            resource.close();
        }
    }

    @Test
    void shouldAskASourceBehindABuddyThroughItAndTakeItWhenItCallsBack() throws Exception {
        givenADownloadThatKadSearches();
        givenKadFound(behindABuddy(PEER));
        givenACallbackWasAskedFor(1, PEER, true);

        whenTheSourceCallsBackAs(PEER);

        thenTheCallIs("adopted");
        thenTheSourceIsAskedForTheFile();
    }

    @Test
    void shouldAskASourceTakingDirectCallbacksDirectly() throws Exception {
        givenADownloadThatKadSearches();

        whenKadFinds(takingDirectCallbacks(PEER));

        thenACallbackIsAskedFor(1, PEER, false);
    }

    @Test
    void shouldNotTakeAClientWeDidNotAskForTheSource() throws Exception {
        givenADownloadThatKadSearches();
        givenKadFound(behindABuddy(PEER));

        whenTheSourceCallsBackAs(Hash16.parseHex("46".repeat(16)));

        thenTheCallIs("refused");
    }

    @Test
    void shouldNeverAskUsToCallOurselves() throws Exception {
        givenADownloadThatKadSearches();

        whenKadFinds(behindABuddy(OURS), takingDirectCallbacks(PEER));

        thenACallbackIsAskedFor(1, PEER, false);
        thenNoMoreCallbacksAreAsked();
    }

    @Test
    void shouldAskASourceThatCalledBackThroughKadAgainAndNeverPassItOn() throws Exception {
        givenADownloadThatKadSearches();
        givenKadFound(behindABuddy(PEER));
        givenTheSourceCalledBackAndKeepsUsQueued();

        whenItsTurnComesAgain();

        thenACallbackIsAskedFor(2, PEER, true);
        thenTheSourceIsNotPassedOn();
    }

    @Test
    void shouldAskASourceBehindABuddyAgainOverUdpThroughTheBuddy() throws Exception {
        givenTheManagerAskingAgainOverUdp();
        givenADownloadThatKadSearches();
        givenKadFound(behindABuddy(PEER));
        givenTheSourceCalledBackAndQueuedUsAt(5);

        whenTimePasses(DownloadManager.REASK_INTERVAL.minusMinutes(1));

        thenTheBuddyIsAskedAgainOverUdpForTheFile();
    }

    @Test
    void shouldNotAskASourceToCallBackWhenItAnsweredThroughItsBuddy() throws Exception {
        givenTheManagerAskingAgainOverUdp();
        givenADownloadThatKadSearches();
        givenKadFound(behindABuddy(PEER));
        givenTheSourceCalledBackAndQueuedUsAt(5);
        givenTheBuddyWasAskedAgainOverUdp();
        givenTheSourceAnsweredOverUdpWithTheRank(2);

        whenTimePasses(Duration.ofMinutes(1));

        thenNoCallbackIsAskedAgainWithin(Duration.ofSeconds(3));
    }

    // Steps.

    private void givenTheManagerAskingAgainOverUdp() throws Exception {
        onLoop(() -> {
            this.manager.reaskOverUdp(this.udp);
            return null;
        });
    }

    private void givenTheSourceCalledBackAndQueuedUsAt(int rank) throws Exception {
        whenTheSourceCallsBackAs(PEER);
        thenTheCallIs("adopted");
        this.link.until(Opcodes.Client.SETREQFILEID);
        this.link.send(new ClientMessages.FileStatus(FILE, 0, new BitSet()).encode());
        this.link.until(Opcodes.Client.STARTUPLOADREQ);
        this.link.send(Packet.of(
                Protocol.EMULE,
                Opcodes.Extended.QUEUERANKING.code(),
                new PacketWriter().u16(rank).bytes(new byte[10])));
        await("queued", () -> this.manager.sources().stream().anyMatch(s -> s.queueRank() == rank));
        this.link.close();
        await(
                "the session ends",
                () -> this.manager.sources().stream()
                        .allMatch(s ->
                                s.state() != SourceInfo.State.CONNECTING && s.state() != SourceInfo.State.DOWNLOADING));
    }

    private void givenTheBuddyWasAskedAgainOverUdp() throws Exception {
        whenTimePasses(DownloadManager.REASK_INTERVAL.minusMinutes(1));
        thenTheBuddyIsAskedAgainOverUdpForTheFile();
    }

    private void givenTheSourceAnsweredOverUdpWithTheRank(int rank) throws Exception {
        onLoop(() -> {
            this.manager.reaskAnswered(
                    Ipv4.parse("127.0.0.1"),
                    SOURCE_UDP_PORT,
                    new ClientReasks.Ack(Optional.of(ClientMessages.PartStatus.COMPLETE), rank)
                            .encode(ClientReasks.UDP_VERSION));
            return null;
        });
        await("the rank", () -> this.manager.sources().stream().anyMatch(s -> s.queueRank() == rank));
    }

    private void whenTimePasses(Duration duration) {
        this.now.addAndGet(duration.toNanos());
    }

    private void thenTheBuddyIsAskedAgainOverUdpForTheFile() throws Exception {
        Udp.Sent sent = this.udp.sent.poll(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(sent).as("a re-ask through the buddy").isNotNull();
        assertThat(sent.to()).isEqualTo(new InetSocketAddress(Ipv4.format(BUDDY_IP), 4672));
        assertThat(sent.userHash()).as("a plain datagram").isEmpty();
        assertThat(sent.packet().opcode()).isEqualTo(Opcodes.ClientUdp.REASKCALLBACKUDP.code());
        ClientReasks.ThroughBuddy reask = ClientReasks.ThroughBuddy.decode(sent.packet());
        assertThat(reask.buddyId()).isEqualTo(BUDDY_ID);
        assertThat(ClientReasks.Ping.file(reask.ping())).isEqualTo(FILE);
    }

    private void givenADownloadThatKadSearches() throws Exception {
        onLoop(() -> this.manager.add(Ed2kLink.FileLink.of("kad.bin", 5000, FILE)));
        await("Kad searched", () -> this.search.get() != null);
    }

    private void givenKadFound(DownloadManager.KadSource found) throws Exception {
        whenKadFinds(found);
        thenACallbackIsAskedFor(
                1,
                found.userHash().orElseThrow(),
                found.callback().orElseThrow().viaBuddy());
    }

    private void givenACallbackWasAskedFor(int times, Hash16 userHash, boolean viaBuddy) throws Exception {
        thenACallbackIsAskedFor(times, userHash, viaBuddy);
    }

    private void givenTheSourceCalledBackAndKeepsUsQueued() throws Exception {
        whenTheSourceCallsBackAs(PEER);
        thenTheCallIs("adopted");
        this.link.until(Opcodes.Client.SETREQFILEID);
        this.link.send(new ClientMessages.FileStatus(FILE, 0, new BitSet()).encode());
        this.link.until(Opcodes.Client.STARTUPLOADREQ);
        this.link.close();
        await(
                "the session ends",
                () -> this.manager.sources().stream().allMatch(s -> s.state() == SourceInfo.State.WAITING));
    }

    private void whenKadFinds(DownloadManager.KadSource... found) throws Exception {
        onLoop(() -> {
            this.search.get().accept(List.of(found));
            return null;
        });
    }

    private void whenTheSourceCallsBackAs(Hash16 userHash) throws Exception {
        this.link = ScriptedSource.call(this.port, this.source.hello(userHash, 1, 0));
        this.resources.add(this.link);
    }

    private void whenItsTurnComesAgain() {
        this.now.addAndGet(DownloadManager.REASK_INTERVAL.toNanos());
    }

    private void thenACallbackIsAskedFor(int times, Hash16 userHash, boolean viaBuddy) throws Exception {
        await("callback asked " + times + " times", () -> this.asked.size() >= times);
        Asked last = this.asked.getLast();
        assertThat(this.asked).hasSize(times);
        assertThat(last.file()).isEqualTo(FILE);
        assertThat(last.source().userHash()).contains(userHash);
        assertThat(last.source().callback().orElseThrow().viaBuddy()).isEqualTo(viaBuddy);
    }

    // A callback queued on one tick goes out on the next: three ticks see one that is due.
    private void thenNoCallbackIsAskedAgainWithin(Duration wait) throws Exception {
        Thread.sleep(wait.toMillis());
        assertThat(this.asked).hasSize(1);
    }

    private void thenNoMoreCallbacksAreAsked() throws Exception {
        Thread.sleep(1500);
        assertThat(this.asked).hasSize(1);
    }

    private void thenTheCallIs(String outcome) throws Exception {
        await("routed", () -> !this.routed.isEmpty());
        assertThat(this.routed.removeLast()).isEqualTo(outcome);
    }

    private void thenTheSourceIsAskedForTheFile() throws Exception {
        List<Packet> asks = this.link.until(Opcodes.Client.SETREQFILEID);
        assertThat(asks.getLast().reader().hash16()).isEqualTo(FILE);
    }

    private void thenTheSourceIsNotPassedOn() throws Exception {
        assertThat(onLoop(() -> this.manager.exchangeable(FILE))).isEmpty();
    }

    // Helpers.

    private DownloadManager.KadSource behindABuddy(Hash16 userHash) {
        return new DownloadManager.KadSource(
                new InetSocketAddress(Ipv4.format(Ipv4.of(203, 0, 113, 5)), this.source.port()),
                Optional.of(userHash),
                0,
                Optional.of(new DownloadManager.KadCallback(BUDDY_IP, 4672, Optional.of(BUDDY_ID), 4672)));
    }

    private DownloadManager.KadSource takingDirectCallbacks(Hash16 userHash) {
        return new DownloadManager.KadSource(
                new InetSocketAddress(Ipv4.format(Ipv4.of(203, 0, 113, 6)), this.source.port()),
                Optional.of(userHash),
                0x08,
                Optional.of(new DownloadManager.KadCallback(0, 0, Optional.empty(), 4672)));
    }

    private void await(String what, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (onLoop(condition)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("waited in vain for " + what);
    }

    private <T> T onLoop(Callable<T> task) throws Exception {
        return this.loop.submit(task).get(30, TimeUnit.SECONDS);
    }

    // The client UDP port, always open: what goes out is kept.
    private static final class Udp implements DownloadManager.ClientUdp {
        record Sent(InetSocketAddress to, Packet packet, Optional<Hash16> userHash) {}

        private final BlockingQueue<Sent> sent = new LinkedBlockingQueue<>();

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public void send(InetSocketAddress to, Packet packet, Optional<Hash16> userHash) {
            this.sent.add(new Sent(to, packet, userHash));
        }
    }

    // No server: the sources come from Kad only.
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
