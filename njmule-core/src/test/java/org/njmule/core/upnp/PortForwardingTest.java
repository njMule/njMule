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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class PortForwardingTest {

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    private final List<String> soap = new CopyOnWriteArrayList<>();
    private final List<String> log = new CopyOnWriteArrayList<>();
    private DatagramSocket ssdp;
    private ServerSocket http;
    private volatile String description;
    private volatile int status = 200;
    private volatile String locationHost;

    @AfterEach
    void close() throws IOException {
        if (this.ssdp != null) {
            this.ssdp.close();
        }
        if (this.http != null) {
            this.http.close();
        }
    }

    @Test
    void forwardsOurPortsAndRemovesThemAtTheEnd() throws Exception {
        this.router(service("urn:schemas-upnp-org:service:WANIPConnection:1", "/ctl"));

        PortForwarding forwarding = this.start(OptionalInt.of(4672));
        forwarding.awaitStarted(Duration.ofSeconds(10));

        assertThat(forwarding.mappings()).containsExactly("TCP 4662", "UDP 4672");
        assertThat(this.log).singleElement().asString().contains("TCP 4662 and UDP 4672 forwarded");
        assertThat(this.soap).hasSize(2);
        assertThat(this.soap.getFirst())
                .contains("#AddPortMapping")
                .contains("<NewExternalPort>4662</NewExternalPort>")
                .contains("<NewProtocol>TCP</NewProtocol>")
                .contains("<NewInternalClient>127.0.0.1</NewInternalClient>")
                .contains("<NewPortMappingDescription>njMule</NewPortMappingDescription>");

        forwarding.close();

        assertThat(this.soap).hasSize(4);
        assertThat(this.soap.get(2)).contains("#DeletePortMapping").contains("<NewProtocol>TCP</NewProtocol>");
        assertThat(this.soap.get(3)).contains("<NewProtocol>UDP</NewProtocol>");
        assertThat(forwarding.mappings()).isEmpty();
    }

    @Test
    void aPppGatewayWorksTooAndRefusalsAreReported() throws Exception {
        this.router(service("urn:schemas-upnp-org:service:WANPPPConnection:1", "ctl"));
        this.status = 500;

        PortForwarding forwarding = this.start(OptionalInt.empty());
        forwarding.awaitStarted(Duration.ofSeconds(10));
        forwarding.close();

        assertThat(forwarding.mappings()).isEmpty();
        assertThat(this.log).singleElement().asString().contains("refused TCP 4662 (606 Action not authorized)");
        assertThat(this.soap).singleElement().asString().contains("WANPPPConnection:1#AddPortMapping");
    }

    @Test
    void routersWithoutTheServiceOrElsewhereAreIgnored() throws Exception {
        this.router(service("urn:schemas-upnp-org:service:Layer3Forwarding:1", "/l3"));
        PortForwarding forwarding = this.start(OptionalInt.empty());
        forwarding.awaitStarted(Duration.ofSeconds(10));
        forwarding.close();
        assertThat(this.log).singleElement().asString().contains("no router answered");

        // A description announced on another host is not fetched.
        this.locationHost = "192.168.200.1";
        PortForwarding elsewhere = this.start(OptionalInt.empty());
        elsewhere.awaitStarted(Duration.ofSeconds(10));
        elsewhere.close();
        assertThat(this.log).last().asString().contains("no router answered");
        assertThat(this.soap).isEmpty();
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
        return PortForwarding.start(
                new InetSocketAddress(LOOPBACK, this.ssdp.getLocalPort()),
                4662,
                udp,
                (fine, line) -> this.log.add(line));
    }

    private static String service(String type, String control) {
        return "<?xml version=\"1.0\"?><root xmlns=\"urn:schemas-upnp-org:device-1-0\"><device>"
                + "<deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:1</deviceType>"
                + "<deviceList><device><serviceList><service>"
                + "<serviceType>"
                + type
                + "</serviceType>"
                + "<controlURL>"
                + control
                + "</controlURL>"
                + "</service></serviceList></device></deviceList></device></root>";
    }

    // Answers discovery with the address of its HTTP server, which serves the description and
    // records the SOAP requests.
    private void router(String deviceDescription) throws IOException {
        this.description = deviceDescription;
        this.http = new ServerSocket(0, 10, LOOPBACK);
        this.ssdp = new DatagramSocket(0, LOOPBACK);
        Thread.ofVirtual().start(() -> {
            byte[] buffer = new byte[2048];
            while (!this.ssdp.isClosed()) {
                try {
                    DatagramPacket search = new DatagramPacket(buffer, buffer.length);
                    this.ssdp.receive(search);
                    String host = this.locationHost == null ? "127.0.0.1" : this.locationHost;
                    byte[] answer = ("HTTP/1.1 200 OK\r\n"
                                    + "ST: upnp:rootdevice\r\n"
                                    + "LOCATION: http://"
                                    + host
                                    + ":"
                                    + this.http.getLocalPort()
                                    + "/desc.xml\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII);
                    this.ssdp.send(new DatagramPacket(answer, answer.length, search.getSocketAddress()));
                } catch (IOException e) {
                    return;
                }
            }
        });
        Thread.ofVirtual().start(() -> {
            while (!this.http.isClosed()) {
                try (Socket client = this.http.accept()) {
                    this.serve(client);
                } catch (IOException e) {
                    return;
                }
            }
        });
    }

    private void serve(Socket client) throws IOException {
        InputStream in = client.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (!head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
            int c = in.read();
            if (c < 0) {
                return;
            }
            head.write(c);
        }
        String request = head.toString(StandardCharsets.ISO_8859_1);
        int length = 0;
        String action = "";
        for (String line : request.split("\r\n", -1)) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("content-length:")) {
                length = Integer.parseInt(line.substring(15).strip());
            } else if (lower.startsWith("soapaction:")) {
                action = line.substring(11).strip();
            }
        }
        String body = new String(in.readNBytes(length), StandardCharsets.UTF_8);
        OutputStream out = client.getOutputStream();
        String reply;
        int code = 200;
        if (request.startsWith("GET")) {
            reply = this.description;
        } else {
            this.soap.add(action + " " + body);
            code = this.status;
            reply = code == 200
                    ? "<s:Envelope/>"
                    : "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault>"
                            + "<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>"
                            + "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>606</errorCode>"
                            + "<errorDescription>Action not\nauthorized</errorDescription></UPnPError>"
                            + "</detail></s:Fault></s:Body></s:Envelope>";
        }
        byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 "
                        + code
                        + " X\r\nContent-Type: text/xml\r\nContent-Length: "
                        + bytes.length
                        + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }
}
