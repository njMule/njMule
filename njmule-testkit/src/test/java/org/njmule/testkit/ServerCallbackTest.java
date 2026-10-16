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
package org.njmule.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.util.Ipv4;

@Timeout(60)
class ServerCallbackTest {

    @TempDir
    Path dir;

    private final List<AutoCloseable> resources = new ArrayList<>();
    private final BlockingQueue<EngineEvent> events = new LinkedBlockingQueue<>();
    private final InetAddress loopback = InetAddress.getLoopbackAddress();
    private FakeServer server;
    private NjMuleEngine engine;
    private ServerSocket firewalled;
    private Throwable exception;

    @AfterEach
    void closeEverything() throws Exception {
        Collections.reverse(this.resources);
        for (AutoCloseable resource : this.resources) {
            resource.close();
        }
    }

    @Test
    void shouldNotLetAServerHaveUsCallALanAddressWhileLanAddressesAreFiltered() throws Exception {
        givenAServerOnTheLoopback();
        givenNjMuleRunningWithLanAddressesFiltered();
        givenAFirewalledPeerListeningOnTheLan();
        givenNjMuleConnectedToTheServer();
        givenTheServerAskedUsToCallTheFirewalledPeer();

        whenThePeerWaitsForACallForMillis(3_000);

        thenExceptionOccurred(SocketTimeoutException.class);
    }

    private void givenAServerOnTheLoopback() throws IOException {
        this.server = FakeServer.start(
                this.loopback,
                new FakeServer.Config(Ipv4.toEd2k(Ipv4.fromBytes(this.loopback.getAddress())), "hi", List.of()));
        this.resources.add(this.server);
    }

    private void givenNjMuleRunningWithLanAddressesFiltered() throws IOException {
        AppDirectories dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), false);
        Files.createDirectories(dirs.config());
        int port = FreePorts.tcp();
        Files.writeString(
                dirs.config().resolve("njmule.properties"),
                "net.tcpPort=" + port + "\nnet.kad.enabled=false\nsecurity.filterLan=true\n");
        this.engine = NjMuleEngine.start(new EngineConfig(dirs));
        this.resources.add(this.engine);
    }

    private void givenAFirewalledPeerListeningOnTheLan() throws IOException {
        this.firewalled = new ServerSocket(0, 4, ServerFlowTest.lanAddress());
        this.resources.add(this.firewalled);
    }

    private void givenNjMuleConnectedToTheServer() throws Exception {
        this.engine.events().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(EngineEvent item) {
                ServerCallbackTest.this.events.add(item);
            }

            @Override
            public void onError(Throwable throwable) {}

            @Override
            public void onComplete() {}
        });
        this.engine
                .connect(Optional.of(new ServerAddress(this.loopback.getHostAddress(), this.server.port())))
                .get(10, TimeUnit.SECONDS);
        assertThat(connected(this.events)).isTrue();
    }

    private void givenTheServerAskedUsToCallTheFirewalledPeer() throws IOException {
        this.server.broadcast(Packet.of(
                Protocol.EDONKEY,
                0x35,
                new PacketWriter()
                        .u32(Ipv4.toEd2k(
                                Ipv4.fromBytes(ServerFlowTest.lanAddress().getAddress())))
                        .u16(this.firewalled.getLocalPort())));
    }

    private void whenThePeerWaitsForACallForMillis(int millis) throws IOException {
        this.firewalled.setSoTimeout(millis);
        this.exception = catchThrowable(this.firewalled::accept);
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private static boolean connected(BlockingQueue<EngineEvent> events) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (events.poll(100, TimeUnit.MILLISECONDS) instanceof EngineEvent.ServerConnected) {
                return true;
            }
        }
        return false;
    }
}
