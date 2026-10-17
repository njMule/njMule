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
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class NatPmpTest {

    private static final Duration WAIT = Duration.ofSeconds(2);

    private FakeNatPmp router;
    private DatagramSocket silent;
    private Optional<NatPmp> found;
    private long granted;
    private Throwable exception;

    @AfterEach
    void close() {
        if (this.router != null) {
            this.router.close();
        }
        if (this.silent != null) {
            this.silent.close();
        }
    }

    @Test
    void shouldFindARouterThatAnswersNatPmp() throws Exception {
        givenARouterThatSpeaks(FakeNatPmp.Speaks.NAT_PMP);

        whenTheRouterIsLookedFor();

        thenTheRouterSpeaks("NAT-PMP");
    }

    @Test
    void shouldFindARouterThatAnswersPcpOnly() throws Exception {
        givenARouterThatSpeaks(FakeNatPmp.Speaks.PCP);

        whenTheRouterIsLookedFor();

        thenTheRouterSpeaks("PCP");
    }

    @Test
    void shouldFindNoRouterWhereNothingAnswers() throws Exception {
        givenASilentAddress();

        whenTheSilentAddressIsLookedAt();

        thenNoRouterIsFound();
    }

    @Test
    void shouldMapAPortForTheLeaseTheRouterGrants() throws Exception {
        givenARouterThatSpeaks(FakeNatPmp.Speaks.NAT_PMP);
        givenTheRouterGrantsAtMost(120);
        givenTheRouterFound();

        whenAPortIsMapped("TCP", 4662, 3600);

        thenTheLeaseGrantedIs(120);
        thenTheRouterWasAsked("map TCP 4662 3600");
    }

    @Test
    void shouldMapAPortOverPcp() throws Exception {
        givenARouterThatSpeaks(FakeNatPmp.Speaks.PCP);
        givenTheRouterFound();

        whenAPortIsMapped("UDP", 4672, 3600);

        thenTheLeaseGrantedIs(3600);
        thenTheRouterWasAskedSomethingStartingWith("map UDP 4672 3600 ");
    }

    @Test
    void shouldRemoveAPcpMappingWithTheNonceItWasMadeWith() throws Exception {
        givenARouterThatSpeaks(FakeNatPmp.Speaks.PCP);
        givenTheRouterFound();
        givenAPortMapped("TCP", 4662);

        whenThePortIsUnmapped("TCP", 4662);

        thenTheRemovalCarriedTheNonceOfTheMapping();
    }

    @Test
    void shouldRefuseAndRemoveAMappingToAnotherOutsidePort() throws Exception {
        givenARouterThatSpeaks(FakeNatPmp.Speaks.NAT_PMP);
        givenTheRouterMapsOutsideToAnotherPort();
        givenTheRouterFound();

        whenAPortIsMapped("TCP", 4662, 3600);

        thenTheMappingIsRefused("the router gave outside port 4663 for 4662");
        thenTheRouterWasAsked("map TCP 4662 3600", "delete TCP 4662");
    }

    @Test
    void shouldTellTheRefusalOfTheRouter() throws Exception {
        givenARouterThatSpeaks(FakeNatPmp.Speaks.NAT_PMP);
        givenTheRouterAnswers(2);
        givenTheRouterFound();

        whenAPortIsMapped("TCP", 4662, 3600);

        thenTheMappingIsRefused("not authorized");
    }

    @Test
    void shouldTellTheRefusalOfAPcpRouter() throws Exception {
        givenARouterThatSpeaks(FakeNatPmp.Speaks.PCP);
        givenTheRouterAnswers(8);
        givenTheRouterFound();

        whenAPortIsMapped("TCP", 4662, 3600);

        thenTheMappingIsRefused("no resources");
    }

    private void givenARouterThatSpeaks(FakeNatPmp.Speaks speaks) throws IOException {
        this.router = new FakeNatPmp(speaks);
    }

    private void givenASilentAddress() throws IOException {
        this.silent = new DatagramSocket(0, InetAddress.getLoopbackAddress());
    }

    private void givenTheRouterGrantsAtMost(long seconds) {
        this.router.grantsAtMost(seconds);
    }

    private void givenTheRouterMapsOutsideToAnotherPort() {
        this.router.mapsOutsideTo(1);
    }

    private void givenTheRouterAnswers(int result) {
        this.router.answers(result);
    }

    private void givenTheRouterFound() throws IOException {
        whenTheRouterIsLookedFor();
    }

    private void givenAPortMapped(String protocol, int port) throws IOException {
        this.found.orElseThrow().addMapping(protocol, port, "njMule", 3600);
    }

    private void whenTheRouterIsLookedFor() throws IOException {
        this.found = NatPmp.discover(this.router.address(), WAIT);
    }

    private void whenTheSilentAddressIsLookedAt() throws IOException {
        this.found = NatPmp.discover(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), this.silent.getLocalPort()), WAIT);
    }

    private void whenAPortIsMapped(String protocol, int port, long lease) {
        this.exception = catchThrowable(
                () -> this.granted = this.found.orElseThrow().addMapping(protocol, port, "njMule", lease));
    }

    private void whenThePortIsUnmapped(String protocol, int port) throws IOException {
        this.found.orElseThrow().deleteMapping(protocol, port);
    }

    private void thenTheRouterSpeaks(String protocol) {
        assertThat(this.found).hasValueSatisfying(r -> assertThat(r.protocol()).isEqualTo(protocol));
        assertThat(this.found.orElseThrow().host()).isEqualTo("127.0.0.1");
    }

    private void thenNoRouterIsFound() {
        assertThat(this.found).isEmpty();
    }

    private void thenTheLeaseGrantedIs(long seconds) {
        assertThat(this.exception).isNull();
        assertThat(this.granted).isEqualTo(seconds);
    }

    private void thenTheRouterWasAsked(String... requests) {
        assertThat(this.router.requests()).containsExactly(requests);
    }

    private void thenTheRouterWasAskedSomethingStartingWith(String start) {
        assertThat(this.router.requests()).singleElement().asString().startsWith(start);
    }

    private void thenTheRemovalCarriedTheNonceOfTheMapping() {
        String map = this.router.requests().get(0);
        String delete = this.router.requests().get(1);
        assertThat(delete).startsWith("delete TCP 4662 ");
        assertThat(delete.substring(delete.lastIndexOf(' '))).isEqualTo(map.substring(map.lastIndexOf(' ')));
    }

    private void thenTheMappingIsRefused(String reason) {
        assertThat(this.exception).isInstanceOf(Gateway.Refused.class).hasMessageContaining(reason);
    }
}
