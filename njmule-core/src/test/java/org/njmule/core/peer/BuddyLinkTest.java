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
package org.njmule.core.peer;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.base.Ticker;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Hash16;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.kad.KadNode;
import org.njmule.core.kad.UInt128;
import org.njmule.core.net.TcpListener;
import org.njmule.core.protocol.ed2k.ClientMessages;
import org.njmule.core.protocol.ed2k.ClientReasks;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.ed2k.LowIdMessages;
import org.njmule.core.protocol.ed2k.NatTraversalMessages;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.packet.FrameLimit;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Packing;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.packet.TcpFraming;
import org.njmule.core.security.ConnectionGuard;
import org.njmule.core.security.IpFilter;
import org.njmule.core.util.Ipv4;

/** The TCP link between Kad buddies, with peers on this machine and a manual clock. */
@Timeout(60)
class BuddyLinkTest {

    private static final Hash16 OURS = Hash16.parseHex("ab".repeat(16));
    private static final Hash16 BUDDY = Hash16.parseHex("bb".repeat(16));
    private static final Hash16 FIREWALLED = Hash16.parseHex("cc".repeat(16));
    private static final int LOOPBACK = Ipv4.of(127, 0, 0, 1);
    private static final Packet PING =
            new ClientReasks.Ping(Hash16.parseHex("dd".repeat(16)), ClientMessages.PartStatus.COMPLETE, 3).encode(4);
    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final Set<Integer> IDENTIFICATION = Set.of(
            Opcodes.Extended.SECIDENTSTATE.code(),
            Opcodes.Extended.PUBLICKEY.code(),
            Opcodes.Extended.SIGNATURE.code());

    @TempDir
    Path dir;

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final Ticker ticker = new Ticker() {
        @Override
        public long read() {
            return BuddyLinkTest.this.now.get();
        }
    };
    private final List<AutoCloseable> resources = new ArrayList<>();
    private final List<LowIdMessages.Callback> callbacks = new CopyOnWriteArrayList<>();
    private final List<ClientReasks.Relayed> reasks = new CopyOnWriteArrayList<>();
    private final List<String> rendezvous = new CopyOnWriteArrayList<>();
    private final UInt128 buddyId = UInt128.parseHex("00112233445566778899AABBCCDDEEFF");
    private BuddyLink buddies;
    private int listenPort;
    private ServerSocket buddyListener;
    private Peer peer;
    private boolean relayed;

    @BeforeEach
    void start() throws Exception {
        SecureRandom random = new SecureRandom();
        Identities identities = new Identities(
                IdentityHandshake.Identity.of(Identities.loadOrCreateKey(this.dir, random)),
                hash -> Optional.empty(),
                (hash, key) -> {},
                Optional::empty,
                random);
        Hello hello = Hello.of(OURS, 1, 4662, "test", 4672, 4672, 0, 0, 0, 0);
        PeerLinks links = new PeerLinks(this.loop, identities, link -> Optional.empty());
        this.buddies = new BuddyLink(
                this.loop,
                links,
                () -> hello,
                options -> false,
                this.callbacks::add,
                this.reasks::add,
                random,
                this.ticker);
        this.buddies.onRendezvous((from, r) ->
                this.rendezvous.add(from.getAddress().getHostAddress() + ":" + from.getPort() + " " + r.requester()));
        PeerGreetings greetings = new PeerGreetings(OURS, () -> hello, () -> true, ip -> false, random);
        greetings.routeTo((connection, from) -> {
            try {
                return Optional.of(this.loop
                        .submit(() -> {
                            PeerLink link = links.incoming(connection, from);
                            if (!this.buddies.adopt(link)) {
                                link.disconnect();
                            }
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
        this.listenPort = listener.port();
    }

    @AfterEach
    void stop() throws Exception {
        onLoop(() -> {
            this.buddies.release();
            return null;
        });
        this.loop.close();
        Collections.reverse(this.resources);
        for (AutoCloseable resource : this.resources) {
            resource.close();
        }
    }

    @Test
    void shouldTakeANodeThatOfferedAsOurBuddy() throws Exception {
        givenABuddyListening();
        givenItOfferedToBeOurBuddy();

        whenItAnswersOurHelloAs(BUDDY, 4675);

        thenTheStateBecomes(KadNode.Buddies.State.CONNECTED);
        thenOurBuddyIsAt(LOOPBACK, 4675);
    }

    @Test
    void shouldPingOurBuddyEveryTenMinutes() throws Exception {
        givenABuddyListening();
        givenItIsOurBuddy();

        whenMinutesPass(10);

        thenTheBuddyGets(Opcodes.Extended.BUDDYPING);
    }

    @Test
    void shouldLetOurBuddyRelayTheCallbacksOfOtherClients() throws Exception {
        givenABuddyListening();
        givenItIsOurBuddy();

        whenTheBuddyRelaysACallbackFrom(Ipv4.of(1, 2, 3, 4), 4711);

        thenACallbackArrivesFrom(Ipv4.of(1, 2, 3, 4), 4711);
    }

    @Test
    void shouldNotTakeAClientThatAnswersWithAnotherHashAsBuddy() throws Exception {
        givenABuddyListening();
        givenItOfferedToBeOurBuddy();

        whenItAnswersOurHelloAs(FIREWALLED, 4675);

        thenTheStateBecomes(KadNode.Buddies.State.NONE);
        thenOurBuddyIsAt(0, 0);
    }

    @Test
    void shouldTakeAFirewalledNodeWeAgreedToServe() throws Exception {
        givenWeAgreedToServe(FIREWALLED);

        whenItConnectsAs(FIREWALLED);

        thenTheStateBecomes(KadNode.Buddies.State.SERVING);
    }

    @Test
    void shouldRelayToTheNodeWeServeTheCallbacksForItsId() throws Exception {
        givenWeAgreedToServe(FIREWALLED);
        givenItConnectedAs(FIREWALLED);

        whenACallbackIsRelayed(this.buddyId);

        thenTheRelayWent(true);
        thenTheFirewalledNodeGetsTheCallback();
    }

    @Test
    void shouldNotRelayACallbackForAnotherId() throws Exception {
        givenWeAgreedToServe(FIREWALLED);
        givenItConnectedAs(FIREWALLED);

        whenACallbackIsRelayed(UInt128.random(new SecureRandom()));

        thenTheRelayWent(false);
    }

    @Test
    void shouldRelayToTheNodeWeServeTheReasksForItsId() throws Exception {
        givenWeAgreedToServe(FIREWALLED);
        givenItConnectedAs(FIREWALLED);

        whenAReaskThroughUsArrivesFrom(Ipv4.of(5, 6, 7, 8), 4672, this.buddyId);

        thenTheRelayWent(true);
        thenTheFirewalledNodeGetsTheReaskOf(Ipv4.of(5, 6, 7, 8), 4672);
    }

    @Test
    void shouldNotRelayAReaskForAnotherId() throws Exception {
        givenWeAgreedToServe(FIREWALLED);
        givenItConnectedAs(FIREWALLED);

        whenAReaskThroughUsArrivesFrom(Ipv4.of(5, 6, 7, 8), 4672, UInt128.random(new SecureRandom()));

        thenTheRelayWent(false);
    }

    @Test
    void shouldRelayTheReasksOfAClientOnceInThirtySeconds() throws Exception {
        givenWeAgreedToServe(FIREWALLED);
        givenItConnectedAs(FIREWALLED);
        givenAReaskThroughUsRelayedFrom(Ipv4.of(5, 6, 7, 8), 4672);
        givenSecondsPassed(29);

        whenAReaskThroughUsArrivesFrom(Ipv4.of(5, 6, 7, 8), 4672, this.buddyId);

        thenTheRelayWent(false);
    }

    @Test
    void shouldLetOurBuddyRelayTheReasksOfClientsInOurQueue() throws Exception {
        givenABuddyListening();
        givenItIsOurBuddy();

        whenTheBuddyRelaysAReaskFrom(Ipv4.of(1, 2, 3, 4), 4672);

        thenAReaskArrivesFrom(Ipv4.of(1, 2, 3, 4), 4672);
    }

    @Test
    void shouldPassOnARendezvousOurBuddyRelaysWithTheRequestersAddress() throws Exception {
        givenABuddyListening();
        givenItIsOurBuddy();

        whenTheBuddyRelaysARendezvousFrom(Ipv4.of(1, 2, 3, 4), 4672);

        thenARendezvousArrivesFrom("1.2.3.4:4672 " + Hash16.parseHex("ab".repeat(16)));
        thenNoReaskArrives();
    }

    @Test
    void shouldNotTakeAClientWeDidNotAgreeToServe() throws Exception {
        givenWeAgreedToServe(FIREWALLED);

        whenItConnectsAs(BUDDY);

        thenTheConnectionCloses();
        thenTheStateStays(KadNode.Buddies.State.NONE);
    }

    @Test
    void shouldAnswerTheNodeWeServeAtMostOnceInThirteenMinutes() throws Exception {
        givenWeAgreedToServe(FIREWALLED);
        givenItConnectedAs(FIREWALLED);
        givenMinutesPassed(13);
        givenItPinged();

        whenItPingsAgain();

        thenItGets(Opcodes.Extended.BUDDYPONG);
        thenItGetsNothingMore();
    }

    @Test
    void shouldCloseAReleasedLink() throws Exception {
        givenABuddyListening();
        givenItIsOurBuddy();

        whenTheLinkIsReleased();

        thenTheConnectionCloses();
        thenTheStateStays(KadNode.Buddies.State.NONE);
    }

    // Steps.

    private void givenABuddyListening() throws IOException {
        this.buddyListener = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
        this.resources.add(this.buddyListener);
    }

    private void givenItIsOurBuddy() throws Exception {
        givenItOfferedToBeOurBuddy();
        whenItAnswersOurHelloAs(BUDDY, 4675);
        thenTheStateBecomes(KadNode.Buddies.State.CONNECTED);
    }

    private void givenWeAgreedToServe(Hash16 userHash) throws Exception {
        assertThat(onLoop(() -> this.buddies.asked(LOOPBACK, 4663, 4676, userHash, this.buddyId)))
                .isTrue();
    }

    private void givenItConnectedAs(Hash16 userHash) throws Exception {
        whenItConnectsAs(userHash);
        thenTheStateBecomes(KadNode.Buddies.State.SERVING);
    }

    private void givenMinutesPassed(int minutes) throws Exception {
        passMinutes(minutes);
    }

    private void givenAReaskThroughUsRelayedFrom(int ip, int port) throws Exception {
        whenAReaskThroughUsArrivesFrom(ip, port, this.buddyId);
        thenTheRelayWent(true);
    }

    private void givenSecondsPassed(int seconds) throws Exception {
        this.now.addAndGet(Duration.ofSeconds(seconds).toNanos());
        onLoop(() -> {
            this.buddies.tick();
            return null;
        });
    }

    private void givenItPinged() throws IOException {
        ping();
    }

    private void givenItOfferedToBeOurBuddy() throws Exception {
        onLoop(() -> {
            this.buddies.offered(LOOPBACK, this.buddyListener.getLocalPort(), 4673, BUDDY, 0);
            return null;
        });
    }

    private void whenItAnswersOurHelloAs(Hash16 userHash, int udpPort) throws Exception {
        this.buddyListener.setSoTimeout((int) WAIT.toMillis());
        Socket socket = this.buddyListener.accept();
        this.peer = new Peer(socket);
        this.resources.add(this.peer);
        assertThat(this.peer.next().opcode()).isEqualTo(Opcodes.Client.HELLO.code());
        this.peer.send(Hello.of(userHash, 1, 4664, "buddy", udpPort, udpPort, 0, 0, 0, 0)
                .encode(true));
    }

    private void whenItConnectsAs(Hash16 userHash) throws IOException {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), this.listenPort);
        this.peer = new Peer(socket);
        this.resources.add(this.peer);
        this.peer.send(Hello.of(userHash, 1, 4663, "firewalled", 4676, 4676, 0, 0, 0, 0)
                .encode(false));
        assertThat(this.peer.next().opcode()).isEqualTo(Opcodes.Client.HELLOANSWER.code());
    }

    private void whenTheBuddyRelaysACallbackFrom(int ip, int port) throws IOException {
        this.peer.send(new LowIdMessages.Callback(this.buddyId, UInt128.ZERO, ip, port).encode());
    }

    private void whenAReaskThroughUsArrivesFrom(int ip, int port, UInt128 id) throws Exception {
        Packet throughUs = new ClientReasks.ThroughBuddy(id.toHash16(), PING).encode();
        this.relayed = onLoop(() -> this.buddies.relayReask(ip, port, throughUs));
    }

    private void whenTheBuddyRelaysARendezvousFrom(int ip, int port) throws IOException {
        Packet rendezvousThroughBuddy = new NatTraversalMessages.Rendezvous(
                        Hash16.parseHex("ab".repeat(16)), 0x80, Hash16.ZERO, 0, 0, 0)
                .encodeThroughBuddy(Hash16.parseHex("cd".repeat(16)));
        byte[] afterTheBuddyId =
                Arrays.copyOfRange(rendezvousThroughBuddy.payload(), Hash16.LENGTH, rendezvousThroughBuddy.length());
        this.peer.send(Packet.of(
                Protocol.EMULE,
                Opcodes.Extended.REASKCALLBACKTCP.code(),
                new PacketWriter().u32(Ipv4.toEd2k(ip)).u16(port).bytes(afterTheBuddyId)));
    }

    private void whenTheBuddyRelaysAReaskFrom(int ip, int port) throws IOException {
        this.peer.send(new ClientReasks.Relayed(ip, port, PING).encode());
    }

    private void whenACallbackIsRelayed(UInt128 id) throws Exception {
        this.relayed = onLoop(() -> this.buddies.relay(id, UInt128.ZERO, Ipv4.of(5, 6, 7, 8), 4711));
    }

    private void whenItPingsAgain() throws IOException {
        ping();
    }

    private void whenMinutesPass(int minutes) throws Exception {
        passMinutes(minutes);
    }

    private void ping() throws IOException {
        this.peer.send(LowIdMessages.buddyPing());
    }

    private void passMinutes(int minutes) throws Exception {
        this.now.addAndGet(Duration.ofMinutes(minutes).toNanos());
        onLoop(() -> {
            this.buddies.tick();
            return null;
        });
    }

    private void whenTheLinkIsReleased() throws Exception {
        onLoop(() -> {
            this.buddies.release();
            return null;
        });
    }

    private void thenTheStateBecomes(KadNode.Buddies.State state) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (onLoop(this.buddies::state) == state) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("waited in vain for " + state);
    }

    private void thenTheStateStays(KadNode.Buddies.State state) throws Exception {
        assertThat(onLoop(this.buddies::state)).isEqualTo(state);
    }

    private void thenOurBuddyIsAt(int ip, int port) throws Exception {
        assertThat(onLoop(this.buddies::buddyIp)).isEqualTo(ip);
        assertThat(onLoop(this.buddies::buddyUdpPort)).isEqualTo(port);
    }

    private void thenTheBuddyGets(Opcodes.Extended opcode) throws IOException {
        thenItGets(opcode);
    }

    private void thenItGets(Opcodes.Extended opcode) throws IOException {
        Packet packet = this.peer.next();
        assertThat(packet.protocol()).isEqualTo(Protocol.EMULE);
        assertThat(packet.opcode()).isEqualTo(opcode.code());
    }

    private void thenItGetsNothingMore() throws IOException {
        assertThat(this.peer.silentFor(Duration.ofMillis(500))).isTrue();
    }

    private void thenACallbackArrivesFrom(int ip, int port) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (this.callbacks.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(this.callbacks).containsExactly(new LowIdMessages.Callback(this.buddyId, UInt128.ZERO, ip, port));
    }

    private void thenTheFirewalledNodeGetsTheReaskOf(int ip, int port) throws Exception {
        Packet packet = this.peer.next();
        assertThat(packet.opcode()).isEqualTo(Opcodes.Extended.REASKCALLBACKTCP.code());
        assertThat(ClientReasks.Relayed.decode(packet)).isEqualTo(new ClientReasks.Relayed(ip, port, PING));
    }

    private void thenAReaskArrivesFrom(int ip, int port) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (this.reasks.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(this.reasks).containsExactly(new ClientReasks.Relayed(ip, port, PING));
    }

    private void thenARendezvousArrivesFrom(String expected) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (this.rendezvous.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(this.rendezvous).containsExactly(expected);
    }

    private void thenNoReaskArrives() {
        assertThat(this.reasks).isEmpty();
    }

    private void thenTheRelayWent(boolean went) {
        assertThat(this.relayed).isEqualTo(went);
    }

    private void thenTheFirewalledNodeGetsTheCallback() throws Exception {
        Packet packet = this.peer.next();
        assertThat(packet.opcode()).isEqualTo(Opcodes.Extended.CALLBACK.code());
        assertThat(LowIdMessages.Callback.decode(packet))
                .isEqualTo(new LowIdMessages.Callback(this.buddyId, UInt128.ZERO, Ipv4.of(5, 6, 7, 8), 4711));
    }

    private void thenTheConnectionCloses() throws IOException {
        assertThat(this.peer.closesWithin(WAIT)).isTrue();
    }

    private <T> T onLoop(Callable<T> task) throws Exception {
        return this.loop.submit(task).get(30, TimeUnit.SECONDS);
    }

    /** The other end of a buddy link, written and read packet by packet. */
    private static final class Peer implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        Peer(Socket socket) throws IOException {
            this.socket = socket;
            this.socket.setSoTimeout((int) WAIT.toMillis());
            this.in = new BufferedInputStream(socket.getInputStream());
            this.out = socket.getOutputStream();
        }

        void send(Packet packet) throws IOException {
            this.out.write(TcpFraming.encode(packet));
            this.out.flush();
        }

        // The next packet; the secure identification is left out.
        Packet next() throws IOException {
            while (true) {
                Packet packet = Packing.unpack(TcpFraming.read(this.in, FrameLimit.fixed(1 << 22)), 1 << 22);
                if (packet.protocol() != Protocol.EMULE || !IDENTIFICATION.contains(packet.opcode())) {
                    return packet;
                }
            }
        }

        boolean silentFor(Duration wait) throws IOException {
            this.socket.setSoTimeout((int) wait.toMillis());
            try {
                next();
                return false;
            } catch (SocketTimeoutException e) {
                return true;
            } finally {
                this.socket.setSoTimeout((int) WAIT.toMillis());
            }
        }

        boolean closesWithin(Duration wait) throws IOException {
            this.socket.setSoTimeout((int) wait.toMillis());
            try {
                while (true) {
                    next();
                }
            } catch (EOFException | SocketException e) {
                return true;
            } catch (SocketTimeoutException e) {
                return false;
            }
        }

        @Override
        public void close() throws IOException {
            this.socket.close();
        }
    }
}
