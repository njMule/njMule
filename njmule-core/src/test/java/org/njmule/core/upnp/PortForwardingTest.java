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
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class PortForwardingTest {

    private static final Pattern EXTERNAL_PORT = Pattern.compile("<NewExternalPort>(\\d+)<");

    private static final String IP = "urn:schemas-upnp-org:service:WANIPConnection:1";
    private static final String PPP = "urn:schemas-upnp-org:service:WANPPPConnection:1";
    private static final String ADD = "AddPortMapping";
    private static final String DELETE = "DeletePortMapping";

    private final List<String> log = new CopyOnWriteArrayList<>();
    private final Renewals renewals = new Renewals();
    private FakeGateway gateway;

    @AfterEach
    void close() throws IOException {
        if (this.gateway != null) {
            this.gateway.close();
        }
    }

    @Test
    void forwardsOurPortsForALeaseAndRemovesThemAtTheEnd() throws Exception {
        this.gateway = FakeGateway.start(IP, "/ctl");

        PortForwarding forwarding = this.start(OptionalInt.of(4672));
        forwarding.awaitStarted(Duration.ofSeconds(10));

        assertThat(forwarding.mappings()).containsExactly("TCP 4662", "UDP 4672");
        assertThat(this.log).singleElement().asString().contains("TCP 4662 and UDP 4672 forwarded");
        assertThat(this.gateway.actions()).hasSize(2);
        assertThat(this.gateway.actions(ADD).getFirst().body())
                .contains("<NewExternalPort>4662</NewExternalPort>")
                .contains("<NewProtocol>TCP</NewProtocol>")
                .contains("<NewInternalClient>127.0.0.1</NewInternalClient>")
                .contains("<NewPortMappingDescription>njMule</NewPortMappingDescription>")
                .contains("<NewLeaseDuration>" + PortForwarding.LEASE.toSeconds() + "</NewLeaseDuration>");

        forwarding.close();

        assertThat(this.gateway.actions(DELETE)).hasSize(2);
        assertThat(this.gateway.actions(DELETE).getFirst().body()).contains("<NewProtocol>TCP</NewProtocol>");
        assertThat(this.gateway.actions(DELETE).get(1).body()).contains("<NewProtocol>UDP</NewProtocol>");
        assertThat(forwarding.mappings()).isEmpty();
    }

    @Test
    void renewsTheMappingsAtHalfTheLease() throws Exception {
        this.gateway = FakeGateway.start(IP, "/ctl");
        try (PortForwarding forwarding = this.start(OptionalInt.of(4672))) {
            forwarding.awaitStarted(Duration.ofSeconds(10));

            assertThat(this.renewals.asked.poll(10, TimeUnit.SECONDS)).isEqualTo(PortForwarding.LEASE.dividedBy(2));
            this.renewals.go.release();

            assertThat(await(() -> this.gateway.actions(ADD).size() == 4)).isTrue();
            assertThat(this.gateway.actions(ADD).get(2).body()).contains("<NewProtocol>TCP</NewProtocol>");
            assertThat(this.renewals.asked.poll(10, TimeUnit.SECONDS))
                    .as("and again")
                    .isNotNull();
        }
    }

    @Test
    void asksForAPermanentMappingWhenTheRouterKeepsNoOther() throws Exception {
        this.gateway = FakeGateway.start(IP, "/ctl");
        this.gateway.onlyPermanentLeases();

        try (PortForwarding forwarding = this.start(OptionalInt.empty())) {
            forwarding.awaitStarted(Duration.ofSeconds(10));

            assertThat(forwarding.mappings()).containsExactly("TCP 4662");
            assertThat(this.log).singleElement().asString().contains("TCP 4662 forwarded");
            assertThat(this.gateway.actions(ADD)).hasSize(2);
            assertThat(this.gateway.actions(ADD).get(1).body()).contains("<NewLeaseDuration>0</NewLeaseDuration>");
        }
    }

    @Test
    void findsTheRouterAgainWhenItStopsAnswering() throws Exception {
        this.gateway = FakeGateway.start(IP, "/ctl");
        PortForwarding forwarding = this.start(OptionalInt.empty());
        forwarding.awaitStarted(Duration.ofSeconds(10));
        assertThat(this.renewals.asked.poll(10, TimeUnit.SECONDS)).isNotNull();

        this.gateway.moveHttp();
        this.renewals.go.release();

        assertThat(await(() -> this.gateway.actions(ADD).size() == 2))
                .as("mapped again at the router's new address")
                .isTrue();
        assertThat(forwarding.mappings()).containsExactly("TCP 4662");
        forwarding.close();
        assertThat(this.gateway.actions(DELETE)).hasSize(1);
    }

    @Test
    void nothingStaysMappedAfterTheEnd() throws Exception {
        this.gateway = FakeGateway.start(IP, "/ctl");
        // Each mapping takes the router longer than the end waits for.
        this.gateway.delayMappings(Duration.ofSeconds(3));
        PortForwarding forwarding = this.start(OptionalInt.of(4672));
        assertThat(await(() -> !this.gateway.actions(ADD).isEmpty())).isTrue();

        forwarding.close();
        Thread.sleep(4_000);

        List<FakeGateway.Action> adds = this.gateway.actions(ADD);
        for (FakeGateway.Action add : adds) {
            String port = externalPort(add.body());
            assertThat(this.gateway.actions(DELETE))
                    .as("the mapping of port %s asked for is removed", port)
                    .anyMatch(d -> d.nanos() > add.nanos() && d.body().contains(">" + port + "<"));
        }
    }

    @Test
    void aPppGatewayWorksTooAndRefusalsAreReported() throws Exception {
        this.gateway = FakeGateway.start(PPP, "ctl");
        this.gateway.refuseWith(606);

        PortForwarding forwarding = this.start(OptionalInt.empty());
        forwarding.awaitStarted(Duration.ofSeconds(10));
        forwarding.close();

        assertThat(forwarding.mappings()).isEmpty();
        assertThat(this.log).singleElement().asString().contains("refused TCP 4662 (606 Action not authorized)");
        assertThat(this.gateway.actions())
                .singleElement()
                .satisfies(a -> assertThat(a.name()).isEqualTo(ADD));
    }

    @Test
    void routersWithoutTheServiceOrElsewhereAreIgnored() throws Exception {
        this.gateway = FakeGateway.start("urn:schemas-upnp-org:service:Layer3Forwarding:1", "/l3");
        PortForwarding forwarding = this.start(OptionalInt.empty());
        forwarding.awaitStarted(Duration.ofSeconds(10));
        forwarding.close();
        assertThat(this.log).singleElement().asString().contains("no router answered");

        // A description announced on another host is not fetched.
        this.gateway.announceOn("192.168.200.1");
        PortForwarding elsewhere = this.start(OptionalInt.empty());
        elsewhere.awaitStarted(Duration.ofSeconds(10));
        elsewhere.close();
        assertThat(this.log).last().asString().contains("no router answered");
        assertThat(this.gateway.actions()).isEmpty();
    }

    @Test
    void aDeviceThatCannotBeReadDoesNotHideTheRouter() throws Exception {
        this.gateway = FakeGateway.start(IP, "/ctl");
        int closed;
        try (ServerSocket gone = new ServerSocket(0)) {
            closed = gone.getLocalPort();
        }
        this.gateway.othersAnswerFirst(List.of("http://127.0.0.1:" + closed + "/desc.xml"));

        try (PortForwarding forwarding = this.start(OptionalInt.empty())) {
            forwarding.awaitStarted(Duration.ofSeconds(10));

            assertThat(forwarding.mappings()).containsExactly("TCP 4662");
        }
    }

    @Test
    void aDescriptionThatStallsOrNeverEndsIsGivenUp() throws Exception {
        this.gateway = FakeGateway.start(IP, "/ctl");
        this.gateway.serveDescription(FakeGateway.Body.STALLED);
        try (PortForwarding stalled = this.start(OptionalInt.empty())) {
            stalled.awaitStarted(Duration.ofSeconds(20));
            assertThat(this.log).singleElement().asString().contains("no router answered");
        }

        this.gateway.serveDescription(FakeGateway.Body.OVERSIZED);
        try (PortForwarding oversized = this.start(OptionalInt.empty())) {
            oversized.awaitStarted(Duration.ofSeconds(10));
            assertThat(this.log).last().asString().contains("no router answered");
        }
        assertThat(this.gateway.actions()).isEmpty();
    }

    @Test
    void theWanConnectionInUseIsChosen() throws Exception {
        this.gateway = FakeGateway.describing(FakeGateway.description(new String[] {IP, "/ip", PPP, "/ppp"}));
        this.gateway.status("/ip", "Disconnected");

        try (PortForwarding forwarding = this.start(OptionalInt.empty())) {
            forwarding.awaitStarted(Duration.ofSeconds(10));

            assertThat(this.gateway.actions(ADD))
                    .singleElement()
                    .satisfies(a -> assertThat(a.body()).startsWith("/ppp ").contains("WANPPPConnection"));
        }
    }

    @Test
    void answersCarryTheirLocation() {
        assertThat(Igd.location("""
                                HTTP/1.1 200 OK\r
                                Cache-Control: max-age=120\r
                                Location: http://10.0.0.1:5000/d.xml\r
                                """))
                .hasValueSatisfying(u -> assertThat(u.toString()).isEqualTo("http://10.0.0.1:5000/d.xml"));
        assertThat(Igd.location("HTTP/1.1 200 OK\r\n")).isEmpty();
        assertThat(Igd.location("LOCATION: http://bad host/\r\n")).isEmpty();
    }

    private PortForwarding start(OptionalInt udp) {
        return PortForwarding.start(this.gateway.ssdp(), 4662, udp, (fine, line) -> this.log.add(line), this.renewals);
    }

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    /** The waits between renewals, let go of by the test one at a time. */
    private static final class Renewals implements PortForwarding.Pause {
        private final BlockingQueue<Duration> asked = new LinkedBlockingQueue<>();
        private final Semaphore go = new Semaphore(0);

        @Override
        public void sleep(Duration duration) throws InterruptedException {
            this.asked.add(duration);
            this.go.acquire();
        }
    }

    // The external port an AddPortMapping asked for.
    private static String externalPort(String body) {
        Matcher port = EXTERNAL_PORT.matcher(body);
        assertThat(port.find()).as("an external port in %s", body).isTrue();
        return port.group(1);
    }
}
