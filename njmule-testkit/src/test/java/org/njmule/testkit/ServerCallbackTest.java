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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
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

    @Test
    void aServerCannotHaveUsCallALanAddressWhileLanAddressesAreFiltered() throws Exception {
        InetAddress lan = ServerFlowTest.lanAddress();
        InetAddress loopback = InetAddress.getLoopbackAddress();
        AppDirectories dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), false);
        Files.createDirectories(dirs.config());
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        Files.writeString(
                dirs.config().resolve("njmule.properties"),
                "net.tcpPort=" + port + "\nnet.kad.enabled=false\nsecurity.filterLan=true\n");
        BlockingQueue<EngineEvent> events = new LinkedBlockingQueue<>();

        try (FakeServer server = FakeServer.start(
                        loopback,
                        new FakeServer.Config(Ipv4.toEd2k(Ipv4.fromBytes(loopback.getAddress())), "hi", List.of()));
                NjMuleEngine engine = NjMuleEngine.start(new EngineConfig(dirs));
                ServerSocket firewalled = new ServerSocket(0, 4, lan)) {
            engine.events().subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(EngineEvent item) {
                    events.add(item);
                }

                @Override
                public void onError(Throwable throwable) {}

                @Override
                public void onComplete() {}
            });
            engine.connect(Optional.of(new ServerAddress(loopback.getHostAddress(), server.port())))
                    .get(10, TimeUnit.SECONDS);
            assertThat(connected(events)).isTrue();

            server.broadcast(Packet.of(
                    Protocol.EDONKEY,
                    0x35,
                    new PacketWriter()
                            .u32(Ipv4.toEd2k(Ipv4.fromBytes(lan.getAddress())))
                            .u16(firewalled.getLocalPort())));

            firewalled.setSoTimeout(3_000);
            assertThatThrownBy(firewalled::accept).isInstanceOf(SocketTimeoutException.class);
        }
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
