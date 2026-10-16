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
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
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
import org.njmule.core.FreePorts;

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
    private PortForwarding forwarding;
    private String answer;
    private Optional<URI> location;

    @AfterEach
    void close() throws IOException {
        if (this.forwarding != null) {
            this.forwarding.close();
        }
        if (this.gateway != null) {
            this.gateway.close();
        }
    }

    @Test
    void shouldForwardOurPortsForALease() throws Exception {
        givenAGatewayOf(IP, "/ctl");

        whenAForwardingStarts(OptionalInt.of(4672));

        thenTheMappingsAre("TCP 4662", "UDP 4672");
        thenTheOnlyLineLoggedSays("TCP 4662 and UDP 4672 forwarded");
        thenTheGatewayWasAskedTimes(2);
        thenTheFirstMappingAskedFor(
                "<NewExternalPort>4662</NewExternalPort>",
                "<NewProtocol>TCP</NewProtocol>",
                "<NewInternalClient>127.0.0.1</NewInternalClient>",
                "<NewPortMappingDescription>njMule</NewPortMappingDescription>",
                "<NewLeaseDuration>" + PortForwarding.LEASE.toSeconds() + "</NewLeaseDuration>");
    }

    @Test
    void shouldRemoveTheMappingsAtTheEnd() throws Exception {
        givenAGatewayOf(IP, "/ctl");
        givenAForwardingStarted(OptionalInt.of(4672));

        whenTheForwardingIsClosed();

        thenTheRemovalsAskedForAre(2);
        thenTheRemovalAskedForIs(0, "<NewProtocol>TCP</NewProtocol>");
        thenTheRemovalAskedForIs(1, "<NewProtocol>UDP</NewProtocol>");
        thenTheMappingsAre();
    }

    @Test
    void shouldRenewTheMappingsAtHalfTheLease() throws Exception {
        givenAGatewayOf(IP, "/ctl");
        givenAForwardingStarted(OptionalInt.of(4672));
        givenAWaitForTheRenewalOf(PortForwarding.LEASE.dividedBy(2));

        whenTheWaitForTheRenewalEnds();

        thenTheMappingsAskedForAre(4);
        thenTheMappingAskedForIs(2, "<NewProtocol>TCP</NewProtocol>");
        thenTheNextRenewalIsWaitedFor();
    }

    @Test
    void shouldAskForAPermanentMappingWhenTheRouterKeepsNoOther() throws Exception {
        givenAGatewayOf(IP, "/ctl");
        givenTheGatewayKeepsOnlyPermanentMappings();

        whenAForwardingStarts(OptionalInt.empty());

        thenTheMappingsAre("TCP 4662");
        thenTheOnlyLineLoggedSays("TCP 4662 forwarded");
        thenTheMappingsAskedForAre(2);
        thenTheMappingAskedForIs(1, "<NewLeaseDuration>0</NewLeaseDuration>");
    }

    @Test
    void shouldFindTheRouterAgainWhenItStopsAnswering() throws Exception {
        givenAGatewayOf(IP, "/ctl");
        givenAForwardingStarted(OptionalInt.empty());
        givenAWaitForTheRenewal();
        givenTheRouterMovedToAnotherAddress();

        whenTheWaitForTheRenewalEnds();

        thenTheMappingsAskedForAre(2, "mapped again at the router's new address");
        thenTheMappingsAre("TCP 4662");
    }

    @Test
    void shouldRemoveTheMappingAtTheAddressTheRouterMovedTo() throws Exception {
        givenAGatewayOf(IP, "/ctl");
        givenAForwardingStarted(OptionalInt.empty());
        givenAWaitForTheRenewal();
        givenTheRouterMovedToAnotherAddress();
        givenTheWaitForTheRenewalEnded();
        givenTheMappingsAskedFor(2, "mapped again at the router's new address");

        whenTheForwardingIsClosed();

        thenTheRemovalsAskedForAre(1);
    }

    @Test
    void shouldLeaveNothingMappedAfterTheEnd() throws Exception {
        givenAGatewayOf(IP, "/ctl");
        // Each mapping takes the router longer than the end waits for.
        givenTheGatewayTakesForEachMapping(Duration.ofSeconds(3));
        givenAForwardingStartedWithoutWaiting(OptionalInt.of(4672));
        givenAMappingAskedFor();

        whenTheForwardingIsClosed();

        thenEveryMappingAskedForIsRemovedAfter(Duration.ofSeconds(4));
    }

    @Test
    void shouldWorkWithAPppGatewayAndReportItsRefusals() throws Exception {
        givenAGatewayOf(PPP, "ctl");
        givenTheGatewayRefusesWith(606);
        givenAForwardingStarted(OptionalInt.empty());

        whenTheForwardingIsClosed();

        thenTheMappingsAre();
        thenTheOnlyLineLoggedSays("refused TCP 4662 (606 Action not authorized)");
        thenTheGatewayWasAskedOnlyFor(ADD);
    }

    @Test
    void shouldIgnoreRoutersWithoutTheServiceOrElsewhere() throws Exception {
        givenAGatewayOf("urn:schemas-upnp-org:service:Layer3Forwarding:1", "/l3");
        givenAForwardingThatFoundNoRouter();
        // A description announced on another host is not fetched.
        givenTheDescriptionAnnouncedOn("192.168.200.1");

        whenAForwardingStarts(OptionalInt.empty());

        thenTheLastLineLoggedSays("no router answered");
        thenTheGatewayWasAskedNothing();
    }

    @Test
    void shouldNotLetADeviceThatCannotBeReadHideTheRouter() throws Exception {
        givenAGatewayOf(IP, "/ctl");
        givenADeviceThatCannotBeReadAnsweringFirst();

        whenAForwardingStarts(OptionalInt.empty());

        thenTheMappingsAre("TCP 4662");
    }

    @Test
    void shouldGiveUpADescriptionThatStalls() throws Exception {
        givenAGatewayOf(IP, "/ctl");
        givenTheDescriptionServed(FakeGateway.Body.STALLED);

        whenAForwardingStartsWithin(OptionalInt.empty(), Duration.ofSeconds(20));

        thenTheOnlyLineLoggedSays("no router answered");
        thenTheGatewayWasAskedNothing();
    }

    @Test
    void shouldGiveUpADescriptionThatNeverEnds() throws Exception {
        givenAGatewayOf(IP, "/ctl");
        givenTheDescriptionServed(FakeGateway.Body.OVERSIZED);

        whenAForwardingStarts(OptionalInt.empty());

        thenTheLastLineLoggedSays("no router answered");
        thenTheGatewayWasAskedNothing();
    }

    @Test
    void shouldChooseTheWanConnectionInUse() throws Exception {
        givenAGatewayDescribedAs(FakeGateway.description(new String[] {IP, "/ip", PPP, "/ppp"}));
        givenTheStatusOf("/ip", "Disconnected");

        whenAForwardingStarts(OptionalInt.empty());

        thenTheOnlyMappingAskedForGoesTo("/ppp ", "WANPPPConnection");
    }

    @Test
    void shouldReadTheLocationOfASearchAnswer() {
        givenTheSearchAnswer("""
                HTTP/1.1 200 OK\r
                Cache-Control: max-age=120\r
                Location: http://10.0.0.1:5000/d.xml\r
                """);

        whenItsLocationIsRead();

        thenTheLocationIs("http://10.0.0.1:5000/d.xml");
    }

    @Test
    void shouldFindNoLocationInASearchAnswerWithoutOne() {
        givenTheSearchAnswer("HTTP/1.1 200 OK\r\n");

        whenItsLocationIsRead();

        thenThereIsNoLocation();
    }

    @Test
    void shouldFindNoLocationInASearchAnswerWithABadOne() {
        givenTheSearchAnswer("LOCATION: http://bad host/\r\n");

        whenItsLocationIsRead();

        thenThereIsNoLocation();
    }

    private void givenAGatewayOf(String type, String control) throws IOException {
        this.gateway = FakeGateway.start(type, control);
    }

    private void givenAGatewayDescribedAs(String description) throws IOException {
        this.gateway = FakeGateway.describing(description);
    }

    private void givenTheStatusOf(String control, String status) {
        this.gateway.status(control, status);
    }

    private void givenTheGatewayKeepsOnlyPermanentMappings() {
        this.gateway.onlyPermanentLeases();
    }

    private void givenTheGatewayRefusesWith(int code) {
        this.gateway.refuseWith(code);
    }

    private void givenTheGatewayTakesForEachMapping(Duration delay) {
        this.gateway.delayMappings(delay);
    }

    private void givenTheDescriptionServed(FakeGateway.Body how) {
        this.gateway.serveDescription(how);
    }

    private void givenTheDescriptionAnnouncedOn(String host) {
        this.gateway.announceOn(host);
    }

    private void givenADeviceThatCannotBeReadAnsweringFirst() throws IOException {
        int closed = FreePorts.tcp();
        this.gateway.othersAnswerFirst(List.of("http://127.0.0.1:" + closed + "/desc.xml"));
    }

    private void givenAForwardingStarted(OptionalInt udp) throws InterruptedException {
        whenAForwardingStarts(udp);
    }

    private void givenAForwardingStartedWithoutWaiting(OptionalInt udp) {
        this.forwarding = start(udp);
    }

    private void givenAForwardingThatFoundNoRouter() throws InterruptedException {
        try (PortForwarding first = start(OptionalInt.empty())) {
            first.awaitStarted(Duration.ofSeconds(10));
        }
        assertThat(this.log).singleElement().asString().contains("no router answered");
    }

    private void givenAWaitForTheRenewalOf(Duration expected) throws InterruptedException {
        assertThat(this.renewals.asked.poll(10, TimeUnit.SECONDS)).isEqualTo(expected);
    }

    private void givenAWaitForTheRenewal() throws InterruptedException {
        assertThat(this.renewals.asked.poll(10, TimeUnit.SECONDS)).isNotNull();
    }

    private void givenTheRouterMovedToAnotherAddress() throws IOException, InterruptedException {
        this.gateway.moveHttp();
    }

    private void givenTheWaitForTheRenewalEnded() {
        whenTheWaitForTheRenewalEnds();
    }

    private void givenTheMappingsAskedFor(int count, String why) throws InterruptedException {
        thenTheMappingsAskedForAre(count, why);
    }

    private void givenAMappingAskedFor() throws InterruptedException {
        assertThat(await(() -> !this.gateway.actions(ADD).isEmpty())).isTrue();
    }

    private void givenTheSearchAnswer(String text) {
        this.answer = text;
    }

    private void whenAForwardingStarts(OptionalInt udp) throws InterruptedException {
        whenAForwardingStartsWithin(udp, Duration.ofSeconds(10));
    }

    private void whenAForwardingStartsWithin(OptionalInt udp, Duration wait) throws InterruptedException {
        this.forwarding = start(udp);
        this.forwarding.awaitStarted(wait);
    }

    private void whenTheForwardingIsClosed() {
        this.forwarding.close();
    }

    private void whenTheWaitForTheRenewalEnds() {
        this.renewals.go.release();
    }

    private void whenItsLocationIsRead() {
        this.location = Igd.location(this.answer);
    }

    private void thenTheMappingsAre(String... expected) {
        assertThat(this.forwarding.mappings()).containsExactly(expected);
    }

    private void thenTheOnlyLineLoggedSays(String text) {
        assertThat(this.log).singleElement().asString().contains(text);
    }

    private void thenTheLastLineLoggedSays(String text) {
        assertThat(this.log).last().asString().contains(text);
    }

    private void thenTheGatewayWasAskedTimes(int count) {
        assertThat(this.gateway.actions()).hasSize(count);
    }

    private void thenTheGatewayWasAskedNothing() {
        assertThat(this.gateway.actions()).isEmpty();
    }

    private void thenTheGatewayWasAskedOnlyFor(String action) {
        assertThat(this.gateway.actions())
                .singleElement()
                .satisfies(a -> assertThat(a.name()).isEqualTo(action));
    }

    private void thenTheFirstMappingAskedFor(String... parts) {
        assertThat(this.gateway.actions(ADD).getFirst().body()).contains(parts);
    }

    private void thenTheMappingsAskedForAre(int count) throws InterruptedException {
        assertThat(await(() -> this.gateway.actions(ADD).size() == count)).isTrue();
    }

    private void thenTheMappingsAskedForAre(int count, String why) throws InterruptedException {
        assertThat(await(() -> this.gateway.actions(ADD).size() == count))
                .as(why)
                .isTrue();
    }

    private void thenTheMappingAskedForIs(int index, String part) {
        assertThat(this.gateway.actions(ADD).get(index).body()).contains(part);
    }

    private void thenTheOnlyMappingAskedForGoesTo(String control, String service) {
        assertThat(this.gateway.actions(ADD))
                .singleElement()
                .satisfies(a -> assertThat(a.body()).startsWith(control).contains(service));
    }

    private void thenTheRemovalsAskedForAre(int count) {
        assertThat(this.gateway.actions(DELETE)).hasSize(count);
    }

    private void thenTheRemovalAskedForIs(int index, String part) {
        assertThat(this.gateway.actions(DELETE).get(index).body()).contains(part);
    }

    private void thenTheNextRenewalIsWaitedFor() throws InterruptedException {
        assertThat(this.renewals.asked.poll(10, TimeUnit.SECONDS))
                .as("and again")
                .isNotNull();
    }

    private void thenEveryMappingAskedForIsRemovedAfter(Duration wait) throws InterruptedException {
        Thread.sleep(wait.toMillis());
        for (FakeGateway.Action add : this.gateway.actions(ADD)) {
            String port = externalPort(add.body());
            assertThat(this.gateway.actions(DELETE))
                    .as("the mapping of port %s asked for is removed", port)
                    .anyMatch(d -> d.nanos() > add.nanos() && d.body().contains(">" + port + "<"));
        }
    }

    private void thenTheLocationIs(String expected) {
        assertThat(this.location)
                .hasValueSatisfying(u -> assertThat(u.toString()).isEqualTo(expected));
    }

    private void thenThereIsNoLocation() {
        assertThat(this.location).isEmpty();
    }

    private PortForwarding start(OptionalInt udp) {
        return PortForwarding.start(
                this.gateway.ssdp(), 4662, udp, (outcome, router, line) -> this.log.add(line), this.renewals);
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
