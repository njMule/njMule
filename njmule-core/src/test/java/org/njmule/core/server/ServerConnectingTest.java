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
package org.njmule.core.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.config.Preferences;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.persist.format.ServerMet;
import org.njmule.core.protocol.ed2k.ServerMessages;
import org.njmule.core.protocol.tag.ServerTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Ipv4;

/** How the session reaches servers: obfuscated or not, which first; scripted servers on loopback. */
@Timeout(60)
class ServerConnectingTest {

    private static final long HIGH_ID = Ipv4.toEd2k(Ipv4.parse("1.2.3.4"));

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final BlockingQueue<String> events = new LinkedBlockingQueue<>();
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private final List<ScriptedServer> scripted = new ArrayList<>();
    private volatile Preferences preferences = Preferences.defaults();
    private volatile boolean keepConnected;
    private final ServerList servers = new ServerList();
    private final ServerSession session = new ServerSession(
            this.loop,
            this.servers,
            () -> this.preferences,
            Hash16.parseHex("01".repeat(16)),
            () -> 4662,
            new Recorder(),
            () -> this.keepConnected,
            new SecureRandom());
    private ServerMet.Server target;

    @AfterEach
    void stop() throws Exception {
        this.onLoop(() -> {
            this.session.disconnect();
            return null;
        });
        this.loop.close();
        for (ScriptedServer server : this.scripted) {
            server.close();
        }
    }

    @Test
    void aServerNeverPingedIsTriedObfuscatedOnceThenPlainly() throws Exception {
        this.givenObfuscation(ObfuscationMode.REQUESTED);
        this.givenANeverPingedServer(this.plainServer());

        this.whenTheSessionConnects();

        this.thenTheSessionIsLoggedIn(false);
        this.thenTheServerWasConnectedTo(2);
    }

    @Test
    void aServerTriedObfuscatedOnceIsNotTriedSoAgain() throws Exception {
        this.givenObfuscation(ObfuscationMode.REQUESTED);
        this.givenANeverPingedServer(this.plainServer());
        this.givenTheSessionConnectedAndLeft();

        this.whenTheSessionConnects();

        this.thenTheSessionIsLoggedIn(false);
        this.thenTheServerWasConnectedTo(3);
    }

    @Test
    void aServerNeverPingedThatTakesObfuscationIsLoggedInObfuscatedAndKnownToTakeIt() throws Exception {
        this.givenObfuscation(ObfuscationMode.REQUESTED);
        this.givenANeverPingedServer(this.obfuscatingServer());

        this.whenTheSessionConnects();

        this.thenTheSessionIsLoggedIn(true);
        this.thenTheServerIsKnownToTakeObfuscation();
    }

    @Test
    void aServerNeverPingedIsTriedObfuscatedWhenObfuscationIsRequired() throws Exception {
        this.givenObfuscation(ObfuscationMode.REQUIRED);
        this.givenANeverPingedServer(this.plainServer());

        this.whenTheSessionConnects();

        this.thenTheSessionGivesUp();
        this.thenTheServerWasConnectedTo(1);
    }

    @Test
    void aServerThatDoesNotAnswerDoesNotHoldUpTheNextOne() throws Exception {
        this.givenObfuscation(ObfuscationMode.REQUESTED);
        InetAddress lan = lanAddress();
        this.givenTheKnownServers(ScriptedServer.startAt(lan, 0, 0), ScriptedServer.startAt(lan, HIGH_ID, 0));

        this.whenTheSessionLooksForAnyServer();

        this.thenTheSessionIsLoggedInWithin(Duration.ofSeconds(10));
    }

    @Test
    void aLostSessionIsReplacedWhenThePreferencesSaySo() throws Exception {
        this.givenTheSessionKeepsConnected(true);
        this.givenTheKnownServers(ScriptedServer.startAt(lanAddress(), HIGH_ID, 0));
        this.givenTheSessionLoggedInToAnyServer();

        this.whenTheServerHangsUp();

        this.thenTheSessionIsLost();
        this.thenTheSessionIsLoggedInWithin(Duration.ofSeconds(10));
    }

    @Test
    void aSessionLostAgainSoonAfterAReconnectWaitsBeforeTheNext() throws Exception {
        this.givenTheSessionKeepsConnected(true);
        this.givenTheKnownServers(ScriptedServer.startAt(lanAddress(), HIGH_ID, 0));
        this.givenTheSessionLoggedInToAnyServer();
        this.givenTheServerHungUpAndTheSessionCameBack();

        this.whenTheServerHangsUp();

        this.thenTheSessionIsLost();
        this.thenTheServersAreTriedAgainAfter(ServerSession.RETRY_AFTER);
        this.thenNoSessionIsOpenFor(Duration.ofSeconds(2));
    }

    @Test
    void whenEveryServerFailsTheyAreTriedAgainLater() throws Exception {
        this.givenTheSessionKeepsConnected(true);
        this.givenTheKnownServers(ScriptedServer.startAt(lanAddress(), 0, 0));
        this.givenTheServerIsGone();

        this.whenTheSessionLooksForAnyServer();

        this.thenTheServersAreTriedAgainAfter(ServerSession.RETRY_AFTER);
        this.thenTheSessionIsStillLooking();
    }

    @Test
    void withoutAServerToTryTheSessionGivesUpAtOnce() throws Exception {
        this.givenTheSessionKeepsConnected(true);

        this.whenTheSessionLooksForAnyServer();

        this.thenTheSessionGivesUp();
    }

    @Test
    void aLostSessionIsNotReplacedUnlessThePreferencesSaySo() throws Exception {
        this.givenTheSessionKeepsConnected(false);
        this.givenTheKnownServers(ScriptedServer.startAt(lanAddress(), HIGH_ID, 0));
        this.givenTheSessionLoggedInToAnyServer();

        this.whenTheServerHangsUp();

        this.thenTheSessionIsLost();
        this.thenNoSessionIsOpenFor(Duration.ofSeconds(2));
    }

    @Test
    void aServerKnownToBePlainIsTriedPlainlyAtOnce() throws Exception {
        this.givenObfuscation(ObfuscationMode.REQUESTED);
        this.givenAPlainServerPingedBefore(this.plainServer());

        this.whenTheSessionConnects();

        this.thenTheSessionIsLoggedIn(false);
        this.thenTheServerWasConnectedTo(1);
    }

    private ScriptedServer plainServer() throws Exception {
        ScriptedServer server = ScriptedServer.start(HIGH_ID, 0);
        this.scripted.add(server);
        return server;
    }

    private ScriptedServer obfuscatingServer() throws Exception {
        ScriptedServer server = ScriptedServer.startObfuscating(HIGH_ID, ServerMessages.TCP_OBFUSCATION);
        this.scripted.add(server);
        return server;
    }

    private void givenObfuscation(ObfuscationMode mode) {
        this.preferences = Preferences.defaults().toBuilder().obfuscation(mode).build();
    }

    private void givenANeverPingedServer(ScriptedServer server) {
        this.target = new ServerMet.Server(server.ip(), server.port(), List.of());
    }

    private void givenAPlainServerPingedBefore(ScriptedServer server) {
        this.target = new ServerMet.Server(server.ip(), server.port(), List.of())
                .with(new Tag.IntTag(TagName.id(ServerTag.UDPFLAGS), 0));
    }

    // Pinged before and plain, the first of high priority.
    private void givenTheKnownServers(ScriptedServer... known) {
        for (ScriptedServer server : known) {
            this.scripted.add(server);
            ServerMet.Server listed = new ServerMet.Server(server.ip(), server.port(), List.of())
                    .with(new Tag.IntTag(TagName.id(ServerTag.UDPFLAGS), 0));
            assertThat(this.servers.add(listed)).isTrue();
        }
        this.servers.setPriority(new ServerList.Address(known[0].ip(), known[0].port()), ServerInfo.Priority.HIGH);
    }

    private void givenTheSessionKeepsConnected(boolean keep) {
        this.keepConnected = keep;
    }

    private void givenTheSessionLoggedInToAnyServer() throws Exception {
        this.whenTheSessionLooksForAnyServer();
        this.thenTheSessionIsLoggedInWithin(Duration.ofSeconds(10));
    }

    private void givenTheServerHungUpAndTheSessionCameBack() throws Exception {
        this.whenTheServerHangsUp();
        this.thenTheSessionIsLost();
        this.thenTheSessionIsLoggedInWithin(Duration.ofSeconds(10));
    }

    private void givenTheServerIsGone() throws Exception {
        this.scripted.getLast().close();
    }

    private void givenTheSessionConnectedAndLeft() throws Exception {
        this.whenTheSessionConnects();
        this.thenTheSessionIsLoggedIn(false);
        this.onLoop(() -> {
            this.session.disconnect();
            return null;
        });
        this.events.clear();
    }

    private void whenTheSessionConnects() throws Exception {
        this.onLoop(() -> {
            this.session.connect(Optional.of(this.target));
            return null;
        });
    }

    private void whenTheSessionLooksForAnyServer() throws Exception {
        this.onLoop(() -> {
            this.session.connect(Optional.empty());
            return null;
        });
    }

    private void whenTheServerHangsUp() throws Exception {
        this.scripted.getLast().hangUp();
    }

    private void thenTheSessionIsLost() throws Exception {
        assertThat(this.next("disconnected")).startsWith("disconnected");
    }

    private void thenTheServersAreTriedAgainAfter(Duration wait) throws Exception {
        assertThat(this.next("retrying")).isEqualTo("retrying " + wait.toSeconds());
    }

    private void thenTheSessionIsStillLooking() throws Exception {
        assertThat(this.onLoop(this.session::isConnected)).isFalse();
        assertThat(this.seen).noneMatch(e -> e.startsWith("disconnected"));
    }

    private void thenNoSessionIsOpenFor(Duration time) throws Exception {
        Thread.sleep(time.toMillis());
        assertThat(this.onLoop(this.session::isConnected)).isFalse();
    }

    private void thenTheSessionIsLoggedInWithin(Duration time) throws Exception {
        assertThat(this.next("connected", time)).isEqualTo("connected " + HIGH_ID);
    }

    private void thenTheSessionIsLoggedIn(boolean obfuscated) throws Exception {
        assertThat(this.next("connected")).isEqualTo("connected " + HIGH_ID);
        assertThat(this.onLoop(this.session::isObfuscated)).isEqualTo(obfuscated);
    }

    private void thenTheSessionGivesUp() throws Exception {
        assertThat(this.next("disconnected")).startsWith("disconnected");
        assertThat(this.onLoop(this.session::isConnected)).isFalse();
    }

    private void thenTheServerWasConnectedTo(int times) {
        assertThat(this.scripted.getLast().connections()).isEqualTo(times);
    }

    private void thenTheServerIsKnownToTakeObfuscation() throws Exception {
        assertThat(this.scripted.getLast().obfuscatedLogins()).isEqualTo(1);
        ServerMet.Server known = this.onLoop(this.session::server).orElseThrow();
        assertThat(ServerList.supportsObfuscation(known)).isTrue();
        assertThat(known.obfuscatedTcpPort()).hasValue(this.target.port());
    }

    private String next(String kind) throws InterruptedException {
        return this.next(kind, Duration.ofSeconds(20));
    }

    private String next(String kind, Duration wait) throws InterruptedException {
        long deadline = System.nanoTime() + wait.toNanos();
        while (System.nanoTime() < deadline) {
            String event = this.events.poll(100, TimeUnit.MILLISECONDS);
            if (event != null && event.startsWith(kind)) {
                return event;
            }
        }
        throw new AssertionError("no " + kind + " in " + this.seen);
    }

    // Servers at loopback are not taken in the server list: this computer's LAN address is.
    private static InetAddress lanAddress() throws SocketException {
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                continue;
            }
            for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                if (address instanceof Inet4Address && !address.isLinkLocalAddress()) {
                    return address;
                }
            }
        }
        assumeTrue(false, "no LAN address");
        throw new IllegalStateException();
    }

    private <T> T onLoop(Callable<T> task) throws Exception {
        return this.loop.submit(task).get(5, TimeUnit.SECONDS);
    }

    private final class Recorder implements ServerSession.Listener {
        private void add(String event) {
            ServerConnectingTest.this.seen.add(event);
            ServerConnectingTest.this.events.add(event);
        }

        @Override
        public void connecting(ServerMet.Server server) {
            this.add("connecting " + Ipv4.format(server.ip()) + ":" + server.port());
        }

        @Override
        public void connected(ServerMet.Server server, ServerMessages.IdChange id) {
            this.add("connected " + id.clientId());
        }

        @Override
        public void disconnected(Optional<ServerMet.Server> server, String reason) {
            this.add("disconnected " + reason);
        }

        @Override
        public void retrying(Duration wait) {
            this.add("retrying " + wait.toSeconds());
        }

        @Override
        public void message(ServerMet.Server server, String line) {}

        @Override
        public void updated(ServerMet.Server server) {}

        @Override
        public void searchResults(ServerMet.Server server, ServerMessages.SearchResults results) {}

        @Override
        public void callbackRequested(ServerMessages.CallbackRequested request) {}

        @Override
        public void foundSources(ServerMessages.FoundSources sources) {}

        @Override
        public void serverList(ServerMet.Server server, List<ServerMessages.Endpoint> servers) {}
    }
}
