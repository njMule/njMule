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
package org.njmule.core.upnp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** A router that answers NAT-PMP or PCP forwards our ports when no UPnP one answers. */
@Timeout(60)
class NatPmpForwardingTest {

    private final List<String> log = new CopyOnWriteArrayList<>();
    private final BlockingQueue<Duration> waits = new LinkedBlockingQueue<>();
    private final Semaphore renew = new Semaphore(0);
    private DatagramSocket noUpnp;
    private FakeNatPmp router;
    private DatagramSocket noRouter;
    private PortForwarding forwarding;

    @AfterEach
    void close() {
        if (this.forwarding != null) {
            this.forwarding.close();
        }
        for (DatagramSocket socket : new DatagramSocket[] {this.noUpnp, this.noRouter}) {
            if (socket != null) {
                socket.close();
            }
        }
        if (this.router != null) {
            this.router.close();
        }
    }

    @Test
    void shouldForwardOurPortsByNatPmpWhenNoUpnpRouterAnswers() throws Exception {
        givenNoUpnpRouter();
        givenARouterThatSpeaks(FakeNatPmp.Speaks.NAT_PMP);

        whenAForwardingStarts();

        thenTheOnlyLineLoggedIs("NAT-PMP: TCP 4662 and UDP 4672 forwarded by 127.0.0.1");
        thenTheMappingsAre("TCP 4662", "UDP 4672");
    }

    @Test
    void shouldForwardOurPortsByPcp() throws Exception {
        givenNoUpnpRouter();
        givenARouterThatSpeaks(FakeNatPmp.Speaks.PCP);

        whenAForwardingStarts();

        thenTheOnlyLineLoggedIs("PCP: TCP 4662 and UDP 4672 forwarded by 127.0.0.1");
    }

    @Test
    void shouldRenewAtHalfTheLeaseTheRouterGranted() throws Exception {
        givenNoUpnpRouter();
        givenARouterThatSpeaks(FakeNatPmp.Speaks.NAT_PMP);
        givenTheRouterGrantsAtMost(600);

        whenAForwardingStarts();

        thenTheFirstRenewalWaits(Duration.ofMinutes(5));
    }

    @Test
    void shouldRemoveTheMappingsAtTheEnd() throws Exception {
        givenNoUpnpRouter();
        givenARouterThatSpeaks(FakeNatPmp.Speaks.NAT_PMP);
        givenAForwardingStarted();

        whenTheForwardingIsClosed();

        thenTheRouterWasAsked("map TCP 4662 3600", "map UDP 4672 3600", "delete TCP 4662", "delete UDP 4672");
    }

    @Test
    void shouldSayThatNoRouterAnswered() throws Exception {
        givenNoUpnpRouter();
        givenNoNatPmpRouter();

        whenAForwardingStarts();

        thenTheOnlyLineLoggedIs("UPnP, NAT-PMP and PCP: no router answered");
    }

    private void givenNoUpnpRouter() throws IOException {
        this.noUpnp = new DatagramSocket(0, InetAddress.getLoopbackAddress());
    }

    private void givenARouterThatSpeaks(FakeNatPmp.Speaks speaks) throws IOException {
        this.router = new FakeNatPmp(speaks);
    }

    private void givenNoNatPmpRouter() throws IOException {
        this.noRouter = new DatagramSocket(0, InetAddress.getLoopbackAddress());
    }

    private void givenTheRouterGrantsAtMost(long seconds) {
        this.router.grantsAtMost(seconds);
    }

    private void givenAForwardingStarted() throws InterruptedException {
        whenAForwardingStarts();
    }

    private void whenAForwardingStarts() throws InterruptedException {
        InetSocketAddress natPmp = this.router != null
                ? this.router.address()
                : new InetSocketAddress(InetAddress.getLoopbackAddress(), this.noRouter.getLocalPort());
        Routers routers = new Routers(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), this.noUpnp.getLocalPort()),
                () -> Optional.of(natPmp));
        this.forwarding = PortForwarding.start(
                routers, 4662, OptionalInt.of(4672), (outcome, router, line) -> this.log.add(line), wait -> {
                    this.waits.add(wait);
                    this.renew.acquire();
                });
        this.forwarding.awaitStarted(Duration.ofSeconds(20));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (this.log.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    private void whenTheForwardingIsClosed() {
        this.forwarding.close();
        this.forwarding = null;
    }

    private void thenTheOnlyLineLoggedIs(String line) {
        assertThat(this.log).containsExactly(line);
    }

    private void thenTheMappingsAre(String... mappings) {
        assertThat(this.forwarding.mappings()).containsExactly(mappings);
    }

    private void thenTheFirstRenewalWaits(Duration wait) throws InterruptedException {
        assertThat(this.waits.poll(10, TimeUnit.SECONDS)).isEqualTo(wait);
    }

    private void thenTheRouterWasAsked(String... requests) {
        assertThat(this.router.requests()).containsExactly(requests);
    }
}
