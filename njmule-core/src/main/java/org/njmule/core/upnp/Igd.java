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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * An Internet gateway device found with UPnP: discovery over SSDP, its description, and the WAN
 * connection service's port mappings over SOAP. Blocking; run it off the engine thread. A gateway
 * is only believed when it describes itself from its own LAN address.
 */
public final class Igd implements AutoCloseable {

    /** Where gateways listen for discovery. */
    public static final InetSocketAddress SSDP =
            new InetSocketAddress(InetAddress.ofLiteral("239.255.255.250"), 1900);

    private static final Logger logger = LoggerFactory.getLogger(Igd.class);
    private static final int MAX_DESCRIPTION = 256 * 1024;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);
    private static final String[] DEVICES = {
        "urn:schemas-upnp-org:device:InternetGatewayDevice:2",
        "urn:schemas-upnp-org:device:InternetGatewayDevice:1"
    };
    private static final String[] SERVICES = {
        "urn:schemas-upnp-org:service:WANIPConnection:",
        "urn:schemas-upnp-org:service:WANPPPConnection:"
    };

    private final URI control;
    private final String service;
    private final InetAddress local;
    private final HttpClient http;

    private Igd(URI control, String service, InetAddress local, HttpClient http) {
        this.control = control;
        this.service = service;
        this.local = local;
        this.http = http;
    }

    /** Asks {@code target} (normally {@link #SSDP}) for a gateway and waits up to {@code wait}. */
    public static Optional<Igd> discover(InetSocketAddress target, Duration wait)
            throws IOException {
        HttpClient http =
                HttpClient.newBuilder()
                        .connectTimeout(HTTP_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .proxy(ProxySelector.of(null))
                        .build();
        Set<URI> seen = new LinkedHashSet<>();
        boolean found = false;
        try (DatagramSocket socket = new DatagramSocket()) {
            for (String device : DEVICES) {
                byte[] search = search(device, target);
                socket.send(new DatagramPacket(search, search.length, target));
            }
            long deadline = System.nanoTime() + wait.toNanos();
            byte[] buffer = new byte[2048];
            while (System.nanoTime() < deadline) {
                int left = (int) Math.max(1, (deadline - System.nanoTime()) / 1_000_000);
                socket.setSoTimeout(left);
                DatagramPacket answer = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(answer);
                } catch (SocketTimeoutException e) {
                    break;
                }
                Optional<URI> location =
                        location(
                                new String(
                                        answer.getData(),
                                        0,
                                        answer.getLength(),
                                        StandardCharsets.ISO_8859_1));
                if (location.isEmpty() || !seen.add(location.get())) {
                    continue;
                }
                if (!trusted(location.get(), answer.getAddress())) {
                    logger.debug(
                            "UPnP: ignoring {} announced by {}",
                            location.get(),
                            answer.getAddress());
                    continue;
                }
                Optional<Igd> gateway = describe(location.get(), http);
                if (gateway.isPresent()) {
                    found = true;
                    return gateway;
                }
            }
        } finally {
            if (!found) {
                http.close();
            }
        }
        return Optional.empty();
    }

    @Override
    public void close() {
        this.http.close();
    }

    public URI controlUrl() {
        return this.control;
    }

    /** Forwards {@code port} of {@code protocol} ("TCP" or "UDP") to us; false if refused. */
    public boolean addMapping(String protocol, int port, String description) throws IOException {
        return this.call(
                "AddPortMapping",
                "<NewRemoteHost></NewRemoteHost>"
                        + "<NewExternalPort>"
                        + port
                        + "</NewExternalPort>"
                        + "<NewProtocol>"
                        + protocol(protocol)
                        + "</NewProtocol>"
                        + "<NewInternalPort>"
                        + port
                        + "</NewInternalPort>"
                        + "<NewInternalClient>"
                        + this.local.getHostAddress()
                        + "</NewInternalClient>"
                        + "<NewEnabled>1</NewEnabled>"
                        + "<NewPortMappingDescription>"
                        + escape(description)
                        + "</NewPortMappingDescription>"
                        + "<NewLeaseDuration>0</NewLeaseDuration>");
    }

    /** Removes the forwarding of {@code port}; false if the gateway refused. */
    public boolean deleteMapping(String protocol, int port) throws IOException {
        return this.call(
                "DeletePortMapping",
                "<NewRemoteHost></NewRemoteHost>"
                        + "<NewExternalPort>"
                        + port
                        + "</NewExternalPort>"
                        + "<NewProtocol>"
                        + protocol(protocol)
                        + "</NewProtocol>");
    }

    private boolean call(String action, String arguments) throws IOException {
        String body =
                "<?xml version=\"1.0\"?>"
                        + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
                        + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                        + "<s:Body><u:"
                        + action
                        + " xmlns:u=\""
                        + this.service
                        + "\">"
                        + arguments
                        + "</u:"
                        + action
                        + "></s:Body></s:Envelope>";
        HttpRequest request =
                HttpRequest.newBuilder(this.control)
                        .timeout(HTTP_TIMEOUT)
                        .header("Content-Type", "text/xml; charset=\"utf-8\"")
                        .header("SOAPAction", "\"" + this.service + "#" + action + "\"")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
        try {
            HttpResponse<String> response =
                    this.http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                logger.debug("UPnP {} refused: {}", action, response.statusCode());
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private static Optional<Igd> describe(URI location, HttpClient http) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(location).timeout(HTTP_TIMEOUT).GET().build();
        byte[] xml;
        try {
            HttpResponse<byte[]> response =
                    http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body().length > MAX_DESCRIPTION) {
                return Optional.empty();
            }
            xml = response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        Document doc;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (ParserConfigurationException | SAXException e) {
            logger.debug("UPnP: unreadable description at {}: {}", location, e.getMessage());
            return Optional.empty();
        }
        String base = text(doc.getDocumentElement(), "URLBase").orElse(location.toString());
        NodeList services = doc.getElementsByTagName("service");
        for (int i = 0; i < services.getLength(); i++) {
            Element s = (Element) services.item(i);
            String type = text(s, "serviceType").orElse("");
            Optional<String> controlUrl = text(s, "controlURL");
            for (String wanted : SERVICES) {
                if (type.startsWith(wanted) && controlUrl.isPresent()) {
                    try {
                        URI control = new URI(base).resolve(controlUrl.get().strip());
                        if (!trusted(control, InetAddress.getByName(location.getHost()))) {
                            return Optional.empty();
                        }
                        return Optional.of(new Igd(control, type, localTowards(location), http));
                    } catch (URISyntaxException | IllegalArgumentException e) {
                        return Optional.empty();
                    }
                }
            }
        }
        return Optional.empty();
    }

    // Only the device that answered may describe itself, and only over plain HTTP on its LAN.
    private static boolean trusted(URI uri, InetAddress responder) {
        if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            return false;
        }
        try {
            InetAddress host = InetAddress.ofLiteral(uri.getHost());
            return host.equals(responder)
                    && (host.isSiteLocalAddress()
                            || host.isLinkLocalAddress()
                            || host.isLoopbackAddress());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static InetAddress localTowards(URI location) throws IOException {
        try (DatagramSocket probe = new DatagramSocket()) {
            int port = location.getPort() > 0 ? location.getPort() : 80;
            probe.connect(new InetSocketAddress(InetAddress.ofLiteral(location.getHost()), port));
            return probe.getLocalAddress();
        }
    }

    static Optional<URI> location(String answer) {
        for (String line : answer.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).strip().equalsIgnoreCase("LOCATION")) {
                try {
                    return Optional.of(new URI(line.substring(colon + 1).strip()));
                } catch (URISyntaxException e) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    private static byte[] search(String device, InetSocketAddress target) {
        return ("M-SEARCH * HTTP/1.1\r\n"
                        + "HOST: "
                        + target.getAddress().getHostAddress()
                        + ":"
                        + target.getPort()
                        + "\r\n"
                        + "MAN: \"ssdp:discover\"\r\n"
                        + "MX: 2\r\n"
                        + "ST: "
                        + device
                        + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static Optional<String> text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            String value = node.getTextContent();
            if (value != null && !value.isBlank()) {
                return Optional.of(value.strip());
            }
        }
        return Optional.empty();
    }

    private static String protocol(String protocol) {
        String p = Objects.requireNonNull(protocol, "protocol").toUpperCase(Locale.ROOT);
        if (!p.equals("TCP") && !p.equals("UDP")) {
            throw new IllegalArgumentException("TCP or UDP: " + protocol);
        }
        return p;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
