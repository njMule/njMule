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
import java.net.Socket;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
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
import org.njmule.core.net.TcpListener;
import org.njmule.core.peer.Identities;
import org.njmule.core.peer.IdentityHandshake;
import org.njmule.core.peer.PeerGreetings;
import org.njmule.core.peer.PeerLink;
import org.njmule.core.peer.PeerLinks;
import org.njmule.core.protocol.ed2k.ClientMessages;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.ed2k.ServerMessages;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.security.ConnectionGuard;
import org.njmule.core.security.IpFilter;
import org.njmule.core.util.Ipv4;

/**
 * Sources that connect to us: calling back with a slot, as firewalled clients asked through the server, or for ours.
 */
@Timeout(60)
class DownloadCallbackTest {

    private static final int PORT = 4662;
    private static final int OUR_IP = Ipv4.of(198, 51, 100, 7);
    private static final int SERVER_IP = Ipv4.of(198, 51, 100, 99);
    private static final Hash16 OURS = Hash16.parseHex("ab".repeat(16));
    private static final Hash16 PEER = Hash16.parseHex("5f".repeat(16));
    private static final Duration WAIT = ScriptedSource.WAIT;

    @TempDir
    Path dir;

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final Ticker ticker = new Ticker() {
        @Override
        public long read() {
            return DownloadCallbackTest.this.now.get();
        }
    };
    private final List<AutoCloseable> resources = new ArrayList<>();
    private final List<Long> callbacks = new CopyOnWriteArrayList<>();
    private final List<String> routed = new CopyOnWriteArrayList<>();
    private DownloadManager manager;
    private int port;
    private ScriptedSource source;
    private ScriptedSource second;
    private ScriptedSource.Link asked;
    private ScriptedSource.Link queued;
    private ScriptedSource.Link call;
    private Socket silent;
    private Hash16 hash;
    private Hash16 gone;
    private List<Packet> request;

    @AfterEach
    void stop() throws Exception {
        if (this.manager != null) {
            onLoop(() -> {
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
    void shouldLetASourceThatCallsBackWhileWeWaitInItsQueueTakeTheSessionOver() throws Exception {
        givenTwoSources();
        givenTheDownloadManagerWithAListener();
        givenAPausedDownloadAndADownloadFromBothSources(
                Hash16.parseHex("41".repeat(16)), Hash16.parseHex("42".repeat(16)));
        givenTheFirstSourceIsStillAsked();
        givenTheSecondSourceKeepsUsInItsQueue();
        givenTheFirstSourceCalledBackAndWasRefused();
        givenTheSecondSourceCalledBackAndWasAdopted();
        givenTheQueuedConnectionEnded();

        whenTheSecondSourceGivesUsASlotOverItsCall();

        thenWeRequestPartsWithoutAskingForTheSlotItGave();
        thenWeDoNotAskForTheFileAgain();
    }

    @Test
    void shouldAskAClientConnectedToUsOverItsOwnConnection() throws Exception {
        givenASource();
        givenTheDownloadManagerWithAListener();
        givenTheSourceConnectedToUsForOurFiles();

        whenADownloadFromTheSourceIsAdded("asked.bin", Hash16.parseHex("43".repeat(16)));

        thenTheSourceIsAskedForTheFileOverItsOwnConnection();
    }

    @Test
    void shouldTakeAFirewalledSourceCallingBackOnlyWithItsServerAndHashAndAskItThroughTheServerAgain()
            throws Exception {
        givenASource();
        givenTheDownloadManagerWithAListener();
        givenTwoDownloadsWithFirewalledSourcesFromTheServer(
                Hash16.parseHex("44".repeat(16)), Hash16.parseHex("45".repeat(16)));
        givenBothSourcesWereAskedThroughTheServer();
        givenTheSecondDownloadPaused();
        givenCallsBackWithAnotherClientIdServerOrHashWereRefused();
        givenTheSourceCalledBackWithItsServerAndHashAndWasAdopted();
        givenTheSourceKeptUsInItsQueueAndHungUp();

        whenItIsTimeToAskTheSourceAgain();

        thenTheServerIsAskedForCallbacksOf(5000L, 6000L, 5000L);
    }

    @Test
    void shouldKeepASourceAskedObfuscatedConnectingUntilItAnswers() throws Exception {
        givenASourceSupportingObfuscation();
        givenTheDownloadManagerWithAListener();
        givenADownloadFromTheSource("crypt.bin", Hash16.parseHex("47".repeat(16)));
        givenTheSourceKeptUsInItsQueueOverAPlainConnection();
        givenTheSourceIsAskedAgainObfuscatedAndDoesNotAnswer();

        whenTheDownloadIsPausedBeforeTheSourceAnswers();

        thenTheSourceIs(SourceInfo.State.WAITING);
        thenTheDownloadIs(DownloadInfo.Status.PAUSED);
    }

    private void givenASource() throws Exception {
        this.source = source();
    }

    private void givenTwoSources() throws Exception {
        this.source = source();
        this.second = source();
    }

    private void givenASourceSupportingObfuscation() throws Exception {
        this.source = new ScriptedSource(PEER, 0, Hello.MISC2_SUPPORTS_CRYPT);
        this.resources.add(this.source);
    }

    private void givenTheDownloadManagerWithAListener() throws Exception {
        start();
    }

    private void givenAPausedDownloadAndADownloadFromBothSources(Hash16 paused, Hash16 hash) throws Exception {
        this.hash = hash;
        onLoop(() -> {
            this.manager.add(Ed2kLink.FileLink.of("paused.bin", 5000, paused));
            this.manager.pause(paused);
            return this.manager.add(link("taken.bin", hash, this.source, this.second));
        });
    }

    private void givenADownloadFromTheSource(String name, Hash16 hash) throws Exception {
        this.hash = hash;
        onLoop(() -> this.manager.add(this.source.link(name, 5000, hash)));
    }

    private void givenTwoDownloadsWithFirewalledSourcesFromTheServer(Hash16 hash, Hash16 gone) throws Exception {
        this.hash = hash;
        this.gone = gone;
        onLoop(() -> {
            this.manager.add(Ed2kLink.FileLink.of("lowid.bin", 5000, hash));
            this.manager.add(Ed2kLink.FileLink.of("gone.bin", 5000, gone));
            this.manager.foundSources(new ServerMessages.FoundSources(
                    hash, List.of(new ServerMessages.Source(5000, PORT, 0, Optional.of(PEER)))));
            this.manager.foundSources(new ServerMessages.FoundSources(
                    gone, List.of(new ServerMessages.Source(6000, PORT, 0, Optional.empty()))));
            return null;
        });
    }

    private void givenTheFirstSourceIsStillAsked() throws Exception {
        this.asked = this.source.accept();
        this.resources.add(this.asked);
        this.asked.until(Opcodes.Client.SETREQFILEID);
    }

    private void givenTheSecondSourceKeepsUsInItsQueue() throws Exception {
        this.queued = this.second.accept();
        this.resources.add(this.queued);
        this.queued.until(Opcodes.Client.SETREQFILEID);
        this.queued.send(new ClientMessages.FileStatus(this.hash, 0, new BitSet()).encode());
        this.queued.until(Opcodes.Client.STARTUPLOADREQ);
    }

    // One still being asked cannot call back; one keeping us queued can, with a slot.
    private void givenTheFirstSourceCalledBackAndWasRefused() throws Exception {
        ScriptedSource.Link early = ScriptedSource.call(this.port, this.source.hello());
        this.resources.add(early);
        assertThat(routedAs(this.source)).isEqualTo("refused");
    }

    private void givenTheSecondSourceCalledBackAndWasAdopted() throws Exception {
        this.call = ScriptedSource.call(this.port, this.second.hello());
        this.resources.add(this.call);
        assertThat(routedAs(this.second)).isEqualTo("adopted");
    }

    private void givenTheQueuedConnectionEnded() throws Exception {
        assertThat(this.queued.closesWithin(WAIT))
                .as("the queued connection ends")
                .isTrue();
    }

    private void givenTheSourceConnectedToUsForOurFiles() throws Exception {
        this.call = ScriptedSource.call(this.port, this.source.hello());
        this.resources.add(this.call);
        assertThat(routedAs(this.source)).isEqualTo("refused");
    }

    private void givenBothSourcesWereAskedThroughTheServer() throws Exception {
        await("both asked through the server", () -> this.callbacks.size() >= 2);
    }

    private void givenTheSecondDownloadPaused() throws Exception {
        onLoop(() -> this.manager.pause(this.gone));
    }

    // Only the client with the LowID, the server and the user hash the server told calls back.
    private void givenCallsBackWithAnotherClientIdServerOrHashWereRefused() throws Exception {
        ScriptedSource.call(this.port, this.source.hello(PEER, 5000, 0)).close();
        ScriptedSource.call(this.port, this.source.hello(PEER, 5000, Ipv4.of(198, 51, 100, 98)))
                .close();
        ScriptedSource.call(this.port, this.source.hello(Hash16.parseHex("46".repeat(16)), 5000, SERVER_IP))
                .close();
        ScriptedSource.call(this.port, this.source.hello(PEER, 6000, SERVER_IP)).close();
        await("four calls routed", () -> this.routed.size() == 4);
        assertThat(this.routed).containsOnly("refused");
        this.routed.clear();
    }

    private void givenTheSourceCalledBackWithItsServerAndHashAndWasAdopted() throws Exception {
        this.call = ScriptedSource.call(this.port, this.source.hello(PEER, 5000, SERVER_IP));
        this.resources.add(this.call);
        assertThat(routedAs(this.source)).isEqualTo("adopted");
    }

    private void givenTheSourceKeptUsInItsQueueAndHungUp() throws Exception {
        this.call.until(Opcodes.Client.SETREQFILEID);
        this.call.send(new ClientMessages.FileStatus(this.hash, 0, new BitSet()).encode());
        this.call.until(Opcodes.Client.STARTUPLOADREQ);
        this.call.close();
        awaitWaiting(this.hash);
    }

    private void givenTheSourceKeptUsInItsQueueOverAPlainConnection() throws Exception {
        ScriptedSource.Link plain = this.source.accept();
        plain.until(Opcodes.Client.SETREQFILEID);
        plain.send(new ClientMessages.FileStatus(this.hash, 0, new BitSet()).encode());
        plain.until(Opcodes.Client.STARTUPLOADREQ);
        plain.close();
        awaitWaiting(this.hash);
    }

    // Its user hash and its taste for obfuscation known now, it is asked again obfuscated.
    private void givenTheSourceIsAskedAgainObfuscatedAndDoesNotAnswer() throws Exception {
        advance(DownloadManager.REASK_INTERVAL);
        this.silent = this.source.acceptSilently();
        await("connecting", () -> this.manager.sources().getFirst().state() == SourceInfo.State.CONNECTING);
        Thread.sleep(1200);
    }

    // Its parts known from the queued connection, the slot comes at once.
    private void whenTheSecondSourceGivesUsASlotOverItsCall() throws Exception {
        this.call.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.ACCEPTUPLOADREQ.code()));
        this.request = this.call.until(Opcodes.Client.REQUESTPARTS);
    }

    private void whenADownloadFromTheSourceIsAdded(String name, Hash16 hash) throws Exception {
        this.hash = hash;
        onLoop(() -> this.manager.add(this.source.link(name, 5000, hash)));
    }

    private void whenItIsTimeToAskTheSourceAgain() {
        advance(DownloadManager.REASK_INTERVAL);
    }

    private void whenTheDownloadIsPausedBeforeTheSourceAnswers() throws Exception {
        onLoop(() -> this.manager.pause(this.hash));
        this.silent.close();
    }

    private void thenWeDoNotAskForTheFileAgain() {
        assertThat(this.request)
                .as("no request again for the file whose parts it told")
                .noneMatch(p -> p.opcode() == Opcodes.Client.SETREQFILEID.code());
    }

    private void thenWeRequestPartsWithoutAskingForTheSlotItGave() {
        assertThat(this.request)
                .as("no request for a slot it gave")
                .noneMatch(p -> p.opcode() == Opcodes.Client.STARTUPLOADREQ.code());
    }

    private void thenTheSourceIsAskedForTheFileOverItsOwnConnection() throws Exception {
        assertThat(names(this.call.until(Opcodes.Client.SETREQFILEID))).contains("REQUESTFILENAME");
    }

    private void thenTheServerIsAskedForCallbacksOf(Long... lowIds) throws Exception {
        await("asked through the server again", () -> this.callbacks.size() >= lowIds.length);
        assertThat(this.callbacks).containsExactly(lowIds);
    }

    private void thenTheSourceIs(SourceInfo.State expected) throws Exception {
        assertThat(onLoop(() -> this.manager.sources().getFirst().state())).isEqualTo(expected);
    }

    private void thenTheDownloadIs(DownloadInfo.Status expected) throws Exception {
        assertThat(onLoop(() -> this.manager.download(this.hash).orElseThrow().status()))
                .isEqualTo(expected);
    }

    private String routedAs(ScriptedSource source) throws Exception {
        await("routed " + source.port(), () -> !this.routed.isEmpty());
        return this.routed.removeLast();
    }

    private void awaitWaiting(Hash16 hash) throws Exception {
        await(
                "waiting",
                () -> this.manager.sources().stream()
                        .filter(s -> s.file().equals(hash))
                        .allMatch(s -> s.state() == SourceInfo.State.WAITING));
    }

    private static Ed2kLink.FileLink link(String name, Hash16 hash, ScriptedSource... sources) {
        List<Ed2kLink.Source> all = new ArrayList<>();
        for (ScriptedSource source : sources) {
            all.add(new Ed2kLink.Source("127.0.0.1", source.port()));
        }
        return new Ed2kLink.FileLink(name, 5000, hash, Optional.empty(), List.of(), all, List.of());
    }

    private static List<String> names(List<Packet> packets) {
        return packets.stream()
                .map(p -> Opcodes.Client.of(p.opcode()).map(Enum::name).orElse("?"))
                .toList();
    }

    private ScriptedSource source() throws Exception {
        ScriptedSource source = new ScriptedSource(PEER, 0, 0);
        this.resources.add(source);
        return source;
    }

    // The download manager with a listener: clients that connect are offered to it, as the engine does, and the others
    // kept as if they came for our files.
    private void start() throws Exception {
        SecureRandom random = new SecureRandom();
        Identities identities = new Identities(
                IdentityHandshake.Identity.of(Identities.loadOrCreateKey(this.dir, random)),
                hash -> Optional.empty(),
                (hash, key) -> {},
                Optional::empty,
                random);
        Hello hello = Hello.of(OURS, Ipv4.toEd2k(OUR_IP), PORT, "test", 0, 0, 0, 0, 0, 0);
        PeerLinks links = new PeerLinks(this.loop, identities, link -> Optional.empty());
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
                links,
                (hash, bytes) -> {},
                Optional::empty,
                List::of,
                new Quiet()));
        PeerGreetings greetings = new PeerGreetings(OURS, () -> hello, () -> true, ip -> false, random);
        greetings.routeTo((connection, peer) -> {
            try {
                return Optional.of(this.loop
                        .submit(() -> {
                            PeerLink link = links.incoming(connection, peer);
                            boolean adopted = this.manager.adopt(link);
                            if (!adopted) {
                                link.attach(PeerLink.Side.UPLOAD, new PeerLink.Role() {
                                    @Override
                                    public void received(Packet packet) {}

                                    @Override
                                    public void closed(Optional<String> cause) {}
                                });
                            }
                            this.routed.add(adopted ? "adopted" : "refused");
                            return link;
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

    private void advance(Duration duration) {
        this.now.addAndGet(duration.toNanos());
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

    // Our HighID on a server that asks firewalled sources to call us.
    private final class Server implements DownloadManager.ServerLink {
        @Override
        public boolean askSources(Hash16 file, long size) {
            return false;
        }

        @Override
        public long clientId() {
            return Ipv4.toEd2k(OUR_IP);
        }

        @Override
        public int serverIp() {
            return SERVER_IP;
        }

        @Override
        public boolean requestCallback(long lowId) {
            DownloadCallbackTest.this.callbacks.add(lowId);
            return true;
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
