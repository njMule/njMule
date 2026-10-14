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
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A UPnP router on loopback, for tests only (never the real one): it answers discovery with the address of its HTTP
 * server, which serves the description and records the SOAP actions.
 */
public final class FakeGateway implements AutoCloseable {

    /** A SOAP action received, such as "AddPortMapping", with its arguments and when. */
    public record Action(String name, String body, long nanos, Instant at) {}

    /** How the description is served. */
    public enum Body {
        WHOLE,
        // Headers, then a few bytes, then nothing.
        STALLED,
        // A megabyte, more than any description has.
        OVERSIZED
    }

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    private final DatagramSocket ssdp;
    private final List<Action> actions = new CopyOnWriteArrayList<>();
    private final Map<String, String> statuses = new ConcurrentHashMap<>();
    private final List<Socket> open = new CopyOnWriteArrayList<>();
    private volatile ServerSocket http;
    private volatile String description;
    private volatile Body body = Body.WHOLE;
    private volatile int faultCode;
    private volatile boolean onlyPermanent;
    private volatile Duration mappingDelay = Duration.ZERO;
    private volatile Duration deleteDelay = Duration.ZERO;
    private volatile String locationHost = "127.0.0.1";
    private volatile List<String> otherLocations = List.of();

    private FakeGateway(String description) throws IOException {
        this.description = description;
        this.ssdp = new DatagramSocket(0, LOOPBACK);
        this.http = new ServerSocket(0, 50, LOOPBACK);
        Thread.ofVirtual().start(this::answerDiscovery);
        this.serve(this.http);
    }

    /** A gateway with one WAN connection service of {@code type} at {@code control}. */
    public static FakeGateway start(String type, String control) throws IOException {
        return new FakeGateway(description(new String[] {type, control}));
    }

    /** A gateway describing itself with {@code description}. */
    public static FakeGateway describing(String description) throws IOException {
        return new FakeGateway(description);
    }

    /** A description listing services as type, control URL, type, control URL... */
    public static String description(String[] services) {
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < services.length; i += 2) {
            list.append("<service><serviceType>")
                    .append(services[i])
                    .append("</serviceType><controlURL>")
                    .append(services[i + 1])
                    .append("</controlURL></service>");
        }
        return "<?xml version=\"1.0\"?><root xmlns=\"urn:schemas-upnp-org:device-1-0\"><device>"
                + "<deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:1</deviceType>"
                + "<deviceList><device><serviceList>"
                + list
                + "</serviceList></device></deviceList></device></root>";
    }

    /** Where to send discovery. */
    public InetSocketAddress ssdp() {
        return new InetSocketAddress(LOOPBACK, this.ssdp.getLocalPort());
    }

    public List<Action> actions() {
        return List.copyOf(this.actions);
    }

    /** The actions named {@code name}, in order. */
    public List<Action> actions(String name) {
        return this.actions.stream().filter(a -> a.name().equals(name)).toList();
    }

    /** Refuses mappings with this UPnP error code (0: accepts them). */
    public void refuseWith(int code) {
        this.faultCode = code;
    }

    /** Accepts only mappings without a lease, as some routers do. */
    public void onlyPermanentLeases() {
        this.onlyPermanent = true;
    }

    /** What GetStatusInfo says for the service at {@code control}; "Connected" otherwise. */
    public void status(String control, String status) {
        this.statuses.put(control, status);
    }

    /** Answers each DeletePortMapping only after {@code delay}. */
    public void delayDeletes(Duration delay) {
        this.deleteDelay = delay;
    }

    /** Answers each AddPortMapping only after {@code delay}, as a slow router does. */
    public void delayMappings(Duration delay) {
        this.mappingDelay = delay;
    }

    public void serveDescription(Body how) {
        this.body = how;
    }

    /** Announces the description on another host, which must not be believed. */
    public void announceOn(String host) {
        this.locationHost = host;
    }

    /** Other devices answering discovery first, from these description addresses. */
    public void othersAnswerFirst(List<String> locations) {
        this.otherLocations = List.copyOf(locations);
    }

    /** The router restarts its web server on another port: the old control address no longer answers. */
    public void moveHttp() throws IOException {
        ServerSocket old = this.http;
        this.http = new ServerSocket(0, 50, LOOPBACK);
        old.close();
        this.serve(this.http);
    }

    @Override
    public void close() throws IOException {
        this.ssdp.close();
        this.http.close();
        for (Socket s : this.open) {
            s.close();
        }
    }

    private void answerDiscovery() {
        byte[] buffer = new byte[2048];
        while (!this.ssdp.isClosed()) {
            try {
                DatagramPacket search = new DatagramPacket(buffer, buffer.length);
                this.ssdp.receive(search);
                for (String location : this.otherLocations) {
                    this.announce(search, location);
                }
                this.announce(search, "http://" + this.locationHost + ":" + this.http.getLocalPort() + "/desc.xml");
            } catch (IOException e) {
                return;
            }
        }
    }

    private void announce(DatagramPacket search, String location) throws IOException {
        byte[] answer = ("HTTP/1.1 200 OK\r\nST: upnp:rootdevice\r\nLOCATION: " + location + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        this.ssdp.send(new DatagramPacket(answer, answer.length, search.getSocketAddress()));
    }

    private void serve(ServerSocket server) {
        Thread.ofVirtual().start(() -> {
            while (!server.isClosed()) {
                try {
                    Socket client = server.accept();
                    this.open.add(client);
                    Thread.ofVirtual().start(() -> this.answer(client));
                } catch (IOException e) {
                    return;
                }
            }
        });
    }

    private void answer(Socket client) {
        try (client) {
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
                    action = line.substring(11).strip().replace("\"", "");
                }
            }
            String text = new String(in.readNBytes(length), StandardCharsets.UTF_8);
            OutputStream out = client.getOutputStream();
            if (request.startsWith("GET")) {
                this.describe(out);
                return;
            }
            String control = request.substring(request.indexOf(' ') + 1, request.indexOf(" HTTP/"));
            String name = action.substring(action.indexOf('#') + 1);
            if (name.equals("GetStatusInfo")) {
                String status = this.statuses.getOrDefault(control, "Connected");
                reply(
                        out,
                        200,
                        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>"
                                + "<u:GetStatusInfoResponse xmlns:u=\"" + action.substring(0, action.indexOf('#'))
                                + "\"><NewConnectionStatus>" + status
                                + "</NewConnectionStatus></u:GetStatusInfoResponse></s:Body></s:Envelope>");
                return;
            }
            this.actions.add(new Action(name, control + " " + text, System.nanoTime(), Instant.now()));
            if (name.equals("AddPortMapping")) {
                Thread.sleep(this.mappingDelay);
            } else if (name.equals("DeletePortMapping")) {
                Thread.sleep(this.deleteDelay);
            }
            boolean leased = !text.contains("<NewLeaseDuration>0</NewLeaseDuration>");
            if (name.equals("AddPortMapping") && this.onlyPermanent && leased) {
                reply(out, 500, fault(725, "OnlyPermanentLeasesSupported"));
            } else if (this.faultCode != 0) {
                reply(out, 500, fault(this.faultCode, "Action not\nauthorized"));
            } else {
                reply(out, 200, "<s:Envelope/>");
            }
        } catch (IOException e) {
            // The test closed the gateway.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void describe(OutputStream out) throws IOException, InterruptedException {
        switch (this.body) {
            case WHOLE -> reply(out, 200, this.description);
            case STALLED -> {
                out.write("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: 5000\r\n\r\n<?xml"
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                Thread.sleep(Duration.ofMinutes(5));
            }
            case OVERSIZED -> {
                byte[] spaces = new byte[1024 * 1024];
                Arrays.fill(spaces, (byte) ' ');
                reply(out, 200, this.description + new String(spaces, StandardCharsets.US_ASCII));
            }
        }
    }

    private static String fault(int code, String description) {
        return "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault>"
                + "<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>"
                + "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>" + code + "</errorCode>"
                + "<errorDescription>" + description + "</errorDescription></UPnPError>"
                + "</detail></s:Fault></s:Body></s:Envelope>";
    }

    private static void reply(OutputStream out, int code, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + code + " X\r\nContent-Type: text/xml\r\nContent-Length: " + bytes.length
                        + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }
}
