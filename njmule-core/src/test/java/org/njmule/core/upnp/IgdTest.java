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
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** A gateway found on loopback (never the real router), and what makes one unusable. */
@Timeout(60)
class IgdTest {

    private static final String IP = "urn:schemas-upnp-org:service:WANIPConnection:1";
    private static final String PPP = "urn:schemas-upnp-org:service:WANPPPConnection:1";
    // A gateway found ends the wait at once.
    private static final Duration WAIT = Duration.ofSeconds(5);
    // Plenty for an answer on loopback: the search for a gateway that cannot be used lasts this long.
    private static final Duration NONE_WAIT = Duration.ofSeconds(1);

    private FakeGateway gateway;
    private Optional<Igd> found = Optional.empty();
    private Throwable exception;
    private byte[] answer;
    private String reason;

    @AfterEach
    void close() throws IOException {
        this.found.ifPresent(Igd::close);
        if (this.gateway != null) {
            this.gateway.close();
        }
    }

    @Test
    void shouldFindTheControlAddressOfAGateway() throws IOException {
        givenAGatewayOf(IP, "/ctl");

        whenAGatewayIsSought(WAIT);

        thenTheControlPathIs("/ctl");
    }

    @Test
    void shouldAskForAMappingInUpperCaseWithItsDescriptionEscaped() throws IOException {
        givenAGatewayOf(IP, "/ctl");
        givenTheGatewayFound();

        whenAPortIsMapped("udp", 4672, "njMule <test> & co", 0);

        thenNoExceptionOccurred();
        thenTheMappingAskedFor(
                "<NewProtocol>UDP</NewProtocol>",
                "<NewPortMappingDescription>njMule &lt;test&gt; &amp; co</NewPortMappingDescription>",
                "<NewLeaseDuration>0</NewLeaseDuration>");
    }

    @Test
    void shouldAskForTheRemovalOfAMappingInUpperCase() throws IOException {
        givenAGatewayOf(IP, "/ctl");
        givenTheGatewayFound();

        whenAPortIsUnmapped("tcp", 4662);

        thenTheRemovalAskedFor("<NewProtocol>TCP</NewProtocol>");
    }

    @Test
    void shouldTakeOnlyTcpOrUdp() throws IOException {
        givenAGatewayOf(IP, "/ctl");
        givenTheGatewayFound();
        givenAPortMapped("udp", 4672, "njMule <test> & co", 0);
        givenAPortUnmapped("tcp", 4662);

        whenAPortIsMapped("sctp", 1, "x", 0);

        thenExceptionOccurred(IllegalArgumentException.class);
        thenTheGatewayWasAskedTimes(2);
    }

    @Test
    void shouldCarryTheReasonOfTheGatewayInARefusal() throws IOException {
        givenAGatewayOf(IP, "/ctl");
        givenTheGatewayFound();
        givenTheGatewayRefusesWith(718);

        whenAPortIsMapped("TCP", 4662, "njMule", 3600);

        thenExceptionOccurred(Igd.Refused.class, "718 Action not authorized");
    }

    @Test
    void shouldNotTakeAConnectionWhoseStatusCannotBeReadForUp() throws IOException {
        givenAGatewayDescribedAs(FakeGateway.description(new String[] {IP, "/ip", PPP, "/ppp"}));
        // The status breaks the answer's XML.
        givenTheStatusOf("/ip", "<broken");
        givenTheStatusOf("/ppp", "Disconnected");

        whenAGatewayIsSought(WAIT);

        thenTheControlPathIs("/ip", "none is up: the first");
    }

    @Test
    void shouldTakeAnEmptyBaseAddressForTheDescriptionsOwn() throws IOException {
        givenAGatewayDescribedAs(
                FakeGateway.description(new String[] {IP, "/ctl"}).replace("<device>", "<URLBase> </URLBase><device>"));

        whenAGatewayIsSought(WAIT);

        thenTheControlHostIs("127.0.0.1");
    }

    @ParameterizedTest
    @MethodSource("descriptionsThatCannotBeTrustedOrRead")
    void shouldFindNoGatewayInADescriptionThatCannotBeTrustedOrRead(String description) throws IOException {
        givenAGatewayDescribedAs(description);

        whenAGatewayIsSought(NONE_WAIT);

        thenNoGatewayIsFound(description);
        thenTheGatewayWasAskedNothing();
    }

    @Test
    void shouldFindNoGatewayWhoseDescriptionIsMissing() throws IOException {
        givenAGatewayOf(IP, "/ctl");
        givenTheDescriptionServed(FakeGateway.Body.MISSING);

        whenAGatewayIsSought(NONE_WAIT);

        thenNoGatewayIsFound();
    }

    @Test
    void shouldFindNoGatewayWhoseDescriptionIsAnnouncedByName() throws IOException {
        givenAGatewayOf(IP, "/ctl");
        givenTheDescriptionServed(FakeGateway.Body.WHOLE);
        givenTheDescriptionAnnouncedOn("localhost");

        whenAGatewayIsSought(NONE_WAIT);

        thenNoGatewayIsFound("only an address is believed");
    }

    @Test
    void shouldAskADeviceAnnouncedTwiceOnce() throws IOException {
        givenAGatewayOf(IP, "/ctl");
        givenADeviceThatCannotBeReadAnsweringFirst(2);

        whenAGatewayIsSought(WAIT);

        thenAGatewayIsFound();
    }

    @Test
    void shouldReadAFaultForItsCodeAndDescription() {
        givenAFault("606", "x".repeat(100) + "\t" + "x".repeat(100));

        whenTheReasonIsReadWithTheStatus(500);

        thenTheReasonStartsWith("606 xxx");
        thenTheReasonHasLength(120);
    }

    @Test
    void shouldTellTheStatusForAFaultWithABlankCode() {
        givenAFault(" ", "ignored");

        whenTheReasonIsReadWithTheStatus(500);

        thenTheReasonIs("HTTP 500", "a blank code");
    }

    @Test
    void shouldTellTheStatusForAnAnswerWithoutAFault() {
        givenTheAnswer("<s:Envelope/>");

        whenTheReasonIsReadWithTheStatus(500);

        thenTheReasonIs("HTTP 500");
    }

    @Test
    void shouldTellTheStatusForAnAnswerThatIsNotXml() {
        givenTheAnswer("not xml");

        whenTheReasonIsReadWithTheStatus(503);

        thenTheReasonIs("HTTP 503");
    }

    @Test
    void shouldTellTheStatusForAnAnswerTooLargeToRead() {
        givenAnAnswerOfBytes(256 * 1024 + 1);

        whenTheReasonIsReadWithTheStatus(500);

        thenTheReasonIs("HTTP 500", "too large to read");
    }

    static Stream<String> descriptionsThatCannotBeTrustedOrRead() {
        return Stream.of(
                "not xml at all",
                FakeGateway.description(new String[] {IP, "http://192.168.1.1/ctl"}),
                FakeGateway.description(new String[] {IP, "https://127.0.0.1/ctl"}),
                FakeGateway.description(new String[] {IP, "/a b"}),
                FakeGateway.description(new String[] {IP, " "}));
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

    private void givenTheGatewayFound() throws IOException {
        this.found = Optional.of(Igd.discover(this.gateway.ssdp(), WAIT).orElseThrow());
    }

    private void givenAPortMapped(String protocol, int port, String description, long lease) throws IOException {
        this.found.orElseThrow().addMapping(protocol, port, description, lease);
    }

    private void givenAPortUnmapped(String protocol, int port) throws IOException {
        this.found.orElseThrow().deleteMapping(protocol, port);
    }

    private void givenTheGatewayRefusesWith(int code) {
        this.gateway.refuseWith(code);
    }

    private void givenTheDescriptionServed(FakeGateway.Body how) {
        this.gateway.serveDescription(how);
    }

    private void givenTheDescriptionAnnouncedOn(String host) {
        this.gateway.announceOn(host);
    }

    private void givenADeviceThatCannotBeReadAnsweringFirst(int times) throws IOException {
        int closed;
        try (ServerSocket gone = new ServerSocket(0)) {
            closed = gone.getLocalPort();
        }
        this.gateway.othersAnswerFirst(Collections.nCopies(times, "http://127.0.0.1:" + closed + "/desc.xml"));
    }

    private void givenAFault(String code, String description) {
        this.answer = fault(code, description);
    }

    private void givenTheAnswer(String text) {
        this.answer = text.getBytes(StandardCharsets.UTF_8);
    }

    private void givenAnAnswerOfBytes(int size) {
        this.answer = new byte[size];
    }

    private void whenAGatewayIsSought(Duration wait) throws IOException {
        this.found = Igd.discover(this.gateway.ssdp(), wait);
    }

    private void whenAPortIsMapped(String protocol, int port, String description, long lease) {
        this.exception = catchThrowable(() -> this.found.orElseThrow().addMapping(protocol, port, description, lease));
    }

    private void whenAPortIsUnmapped(String protocol, int port) throws IOException {
        this.found.orElseThrow().deleteMapping(protocol, port);
    }

    private void whenTheReasonIsReadWithTheStatus(int status) {
        this.reason = Igd.reason(status, this.answer);
    }

    private void thenTheControlPathIs(String expected) {
        assertThat(this.found.orElseThrow().controlUrl().getPath()).isEqualTo(expected);
    }

    private void thenTheControlPathIs(String expected, String why) {
        assertThat(this.found.orElseThrow().controlUrl().getPath()).as(why).isEqualTo(expected);
    }

    private void thenTheControlHostIs(String expected) {
        assertThat(this.found.orElseThrow().controlUrl().getHost()).isEqualTo(expected);
    }

    private void thenTheMappingAskedFor(String... parts) {
        assertThat(this.gateway.actions("AddPortMapping").getFirst().body()).contains(parts);
    }

    private void thenTheRemovalAskedFor(String... parts) {
        assertThat(this.gateway.actions("DeletePortMapping").getFirst().body()).contains(parts);
    }

    private void thenTheGatewayWasAskedTimes(int count) {
        assertThat(this.gateway.actions()).hasSize(count);
    }

    private void thenTheGatewayWasAskedNothing() {
        assertThat(this.gateway.actions()).isEmpty();
    }

    private void thenAGatewayIsFound() {
        assertThat(this.found).isPresent();
    }

    private void thenNoGatewayIsFound() {
        assertThat(this.found).isEmpty();
    }

    private void thenNoGatewayIsFound(String why) {
        assertThat(this.found).as(why).isEmpty();
    }

    private void thenNoExceptionOccurred() {
        assertThat(this.exception).isNull();
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type, String message) {
        assertThat(this.exception).isInstanceOf(type).hasMessage(message);
    }

    private void thenTheReasonIs(String expected) {
        assertThat(this.reason).isEqualTo(expected);
    }

    private void thenTheReasonIs(String expected, String why) {
        assertThat(this.reason).as(why).isEqualTo(expected);
    }

    private void thenTheReasonStartsWith(String prefix) {
        assertThat(this.reason).startsWith(prefix);
    }

    private void thenTheReasonHasLength(int length) {
        assertThat(this.reason).hasSize(length);
    }

    private static byte[] fault(String code, String description) {
        return ("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault><detail>"
                        + "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>" + code
                        + "</errorCode><errorDescription>" + description + "</errorDescription></UPnPError>"
                        + "</detail></s:Fault></s:Body></s:Envelope>")
                .getBytes(StandardCharsets.UTF_8);
    }
}
