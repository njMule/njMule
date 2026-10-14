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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** A gateway found on loopback (never the real router), and what makes one unusable. */
@Timeout(60)
class IgdTest {

    private static final String IP = "urn:schemas-upnp-org:service:WANIPConnection:1";
    private static final String PPP = "urn:schemas-upnp-org:service:WANPPPConnection:1";
    // A gateway found ends the wait at once.
    private static final Duration WAIT = Duration.ofSeconds(5);
    // Plenty for an answer on loopback: the search for a gateway that cannot be used lasts this long.
    private static final Duration NONE_WAIT = Duration.ofSeconds(1);

    @Test
    void aGatewayMapsAndUnmapsPortsAndTakesOnlyTcpOrUdp() throws Exception {
        try (FakeGateway gateway = FakeGateway.start(IP, "/ctl");
                Igd igd = Igd.discover(gateway.ssdp(), WAIT).orElseThrow()) {
            assertThat(igd.controlUrl().getPath()).isEqualTo("/ctl");

            igd.addMapping("udp", 4672, "njMule <test> & co", 0);
            igd.deleteMapping("tcp", 4662);

            assertThat(gateway.actions("AddPortMapping").getFirst().body())
                    .contains("<NewProtocol>UDP</NewProtocol>")
                    .contains("<NewPortMappingDescription>njMule &lt;test&gt; &amp; co</NewPortMappingDescription>")
                    .contains("<NewLeaseDuration>0</NewLeaseDuration>");
            assertThat(gateway.actions("DeletePortMapping").getFirst().body())
                    .contains("<NewProtocol>TCP</NewProtocol>");
            assertThatThrownBy(() -> igd.addMapping("sctp", 1, "x", 0)).isInstanceOf(IllegalArgumentException.class);
            assertThat(gateway.actions()).hasSize(2);
        }
    }

    @Test
    void aRefusalCarriesTheGatewaysReason() throws Exception {
        try (FakeGateway gateway = FakeGateway.start(IP, "/ctl");
                Igd igd = Igd.discover(gateway.ssdp(), WAIT).orElseThrow()) {
            gateway.refuseWith(718);

            assertThatThrownBy(() -> igd.addMapping("TCP", 4662, "njMule", 3600))
                    .isInstanceOf(Igd.Refused.class)
                    .hasMessage("718 Action not authorized");
        }
    }

    @Test
    void aConnectionWhoseStatusCannotBeReadIsNotTakenForUp() throws Exception {
        try (FakeGateway gateway =
                FakeGateway.describing(FakeGateway.description(new String[] {IP, "/ip", PPP, "/ppp"}))) {
            // The status breaks the answer's XML.
            gateway.status("/ip", "<broken");
            gateway.status("/ppp", "Disconnected");

            try (Igd igd = Igd.discover(gateway.ssdp(), WAIT).orElseThrow()) {
                assertThat(igd.controlUrl().getPath())
                        .as("none is up: the first")
                        .isEqualTo("/ip");
            }
        }
    }

    @Test
    void anEmptyBaseAddressIsTheDescriptionsOwn() throws Exception {
        String description =
                FakeGateway.description(new String[] {IP, "/ctl"}).replace("<device>", "<URLBase> </URLBase><device>");
        try (FakeGateway gateway = FakeGateway.describing(description);
                Igd igd = Igd.discover(gateway.ssdp(), WAIT).orElseThrow()) {
            assertThat(igd.controlUrl().getHost()).isEqualTo("127.0.0.1");
        }
    }

    @Test
    void descriptionsThatCannotBeTrustedOrReadGiveNoGateway() throws Exception {
        for (String description : List.of(
                "not xml at all",
                FakeGateway.description(new String[] {IP, "http://192.168.1.1/ctl"}),
                FakeGateway.description(new String[] {IP, "https://127.0.0.1/ctl"}),
                FakeGateway.description(new String[] {IP, "/a b"}),
                FakeGateway.description(new String[] {IP, " "}))) {
            try (FakeGateway gateway = FakeGateway.describing(description)) {
                assertThat(Igd.discover(gateway.ssdp(), NONE_WAIT))
                        .as(description)
                        .isEmpty();
                assertThat(gateway.actions()).isEmpty();
            }
        }
    }

    @Test
    void aDescriptionThatIsMissingOrAnnouncedByNameGivesNoGateway() throws Exception {
        try (FakeGateway gateway = FakeGateway.start(IP, "/ctl")) {
            gateway.serveDescription(FakeGateway.Body.MISSING);
            assertThat(Igd.discover(gateway.ssdp(), NONE_WAIT)).isEmpty();

            gateway.serveDescription(FakeGateway.Body.WHOLE);
            gateway.announceOn("localhost");
            assertThat(Igd.discover(gateway.ssdp(), NONE_WAIT))
                    .as("only an address is believed")
                    .isEmpty();
        }
    }

    @Test
    void aDeviceAnnouncedTwiceIsAskedOnce() throws Exception {
        int closed;
        try (ServerSocket gone = new ServerSocket(0)) {
            closed = gone.getLocalPort();
        }
        String broken = "http://127.0.0.1:" + closed + "/desc.xml";
        try (FakeGateway gateway = FakeGateway.start(IP, "/ctl")) {
            gateway.othersAnswerFirst(List.of(broken, broken));

            Optional<Igd> found = Igd.discover(gateway.ssdp(), WAIT);

            assertThat(found).isPresent();
            found.get().close();
        }
    }

    @Test
    void aFaultIsReadForItsCodeAndDescriptionElseTheStatusIsTold() {
        String longText = "x".repeat(100) + "\t" + "x".repeat(100);

        assertThat(Igd.reason(500, fault("606", longText)))
                .startsWith("606 xxx")
                .hasSize(120);
        assertThat(Igd.reason(500, fault(" ", "ignored"))).as("a blank code").isEqualTo("HTTP 500");
        assertThat(Igd.reason(500, "<s:Envelope/>".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("HTTP 500");
        assertThat(Igd.reason(503, "not xml".getBytes(StandardCharsets.UTF_8))).isEqualTo("HTTP 503");
        assertThat(Igd.reason(500, new byte[256 * 1024 + 1]))
                .as("too large to read")
                .isEqualTo("HTTP 500");
    }

    private static byte[] fault(String code, String description) {
        return ("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault><detail>"
                        + "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>" + code
                        + "</errorCode><errorDescription>" + description + "</errorDescription></UPnPError>"
                        + "</detail></s:Fault></s:Body></s:Envelope>")
                .getBytes(StandardCharsets.UTF_8);
    }
}
