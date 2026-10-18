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

import com.google.common.net.InetAddresses;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet6Address;
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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** A router that answers NAT-PMP or PCP forwards our ports when no UPnP one answers. */
@Timeout(60)
class NatPmpForwardingTest {

    private static final Inet6Address LOOPBACK6 = (Inet6Address) InetAddresses.forString("::1");

    private final List<String> log = new CopyOnWriteArrayList<>();
    private final BlockingQueue<Duration> waits = new LinkedBlockingQueue<>();
    private final Semaphore renew = new Semaphore(0);
    private DatagramSocket noUpnp;
    private FakeNatPmp router;
    private DatagramSocket noRouter;
    private PortForwarding forwarding;
    private FakeNatPmp ipv6Gateway;
    private Optional<Inet6Address> ourIpv6 = Optional.empty();

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
        if (this.ipv6Gateway != null) {
            this.ipv6Gateway.close();
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
    void shouldOpenTheIpv6FirewallByPcpAtTheIpv6GatewayWhenTheRouterOpensNone() throws Exception {
        givenAnIpv6Loopback();
        givenNoUpnpRouter();
        givenARouterThatSpeaks(FakeNatPmp.Speaks.NAT_PMP);
        givenAPcpServerAtTheIpv6Gateway();
        givenOurIpv6Address(LOOPBACK6);

        whenAForwardingStarts();

        thenTheMappingsAre("TCP 4662", "UDP 4672");
        thenTheIpv6GatewayWasAskedFrom("::1", "map TCP 4662 3600 ", "map UDP 4672 3600 ");
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

    private void givenAnIpv6Loopback() {
        try (DatagramSocket probe = new DatagramSocket(0, LOOPBACK6)) {
            assertThat(probe.getLocalPort()).isPositive();
        } catch (IOException e) {
            Assumptions.abort("no IPv6 loopback here");
        }
    }

    private void givenAPcpServerAtTheIpv6Gateway() throws IOException {
        this.ipv6Gateway = new FakeNatPmp(FakeNatPmp.Speaks.PCP, LOOPBACK6);
    }

    private void givenOurIpv6Address(Inet6Address address) {
        this.ourIpv6 = Optional.of(address);
    }

    private void thenTheIpv6GatewayWasAskedFrom(String client, String... starts) {
        List<String> asked = this.ipv6Gateway.requests();
        assertThat(asked).hasSize(starts.length);
        for (int i = 0; i < starts.length; i++) {
            assertThat(asked.get(i)).startsWith(starts[i]);
        }
        assertThat(this.ipv6Gateway.clients()).containsOnly(client);
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
        Optional<InetSocketAddress> pcpIpv6 =
                Optional.ofNullable(this.ipv6Gateway).map(FakeNatPmp::address);
        Routers routers = new Routers(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), this.noUpnp.getLocalPort()),
                () -> Optional.of(natPmp),
                () -> pcpIpv6);
        Optional<Inet6Address> ipv6 = this.ourIpv6;
        this.forwarding = PortForwarding.start(
                routers,
                4662,
                OptionalInt.of(4672),
                (outcome, router, line) -> this.log.add(line),
                wait -> {
                    this.waits.add(wait);
                    this.renew.acquire();
                },
                () -> ipv6);
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
