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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Our ports forwarded by the router while njMule runs: found and mapped on a virtual thread at the
 * start, unmapped at the end. Only the mappings made here are ever removed.
 */
public final class PortForwarding implements AutoCloseable {

    /** How long discovery waits for a router to answer. */
    public static final Duration DISCOVERY = Duration.ofSeconds(3);

    private static final Logger logger = LoggerFactory.getLogger(PortForwarding.class);
    private static final String DESCRIPTION = "njMule";
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(5);

    private record Mapping(String protocol, int port) {}

    private final List<Mapping> mapped = new CopyOnWriteArrayList<>();
    private final Thread worker;
    private volatile Igd gateway;

    private PortForwarding(Thread worker) {
        this.worker = worker;
    }

    /**
     * Starts forwarding {@code tcpPort} and, when present, {@code udpPort}; {@code report} gets one
     * line for the log when it is done or failed.
     */
    public static PortForwarding start(
            InetSocketAddress ssdp, int tcpPort, OptionalInt udpPort, Consumer<String> report) {
        PortForwarding[] self = new PortForwarding[1];
        Thread worker =
                Thread.ofVirtual()
                        .name("njmule-upnp")
                        .unstarted(() -> self[0].run(ssdp, tcpPort, udpPort, report));
        self[0] = new PortForwarding(worker);
        worker.start();
        return self[0];
    }

    /** The forwarded ports, as "TCP 4662". */
    public List<String> mappings() {
        List<String> all = new ArrayList<>();
        for (Mapping m : this.mapped) {
            all.add(m.protocol() + " " + m.port());
        }
        return all;
    }

    /** Waits for the discovery to end, for tests. */
    public void awaitStarted(Duration wait) throws InterruptedException {
        this.worker.join(wait);
    }

    private void run(
            InetSocketAddress ssdp, int tcpPort, OptionalInt udpPort, Consumer<String> report) {
        try {
            Optional<Igd> found = Igd.discover(ssdp, DISCOVERY);
            if (found.isEmpty()) {
                report.accept("UPnP: no router answered");
                return;
            }
            this.gateway = found.get();
            List<Mapping> wanted = new ArrayList<>();
            wanted.add(new Mapping("TCP", tcpPort));
            udpPort.ifPresent(p -> wanted.add(new Mapping("UDP", p)));
            for (Mapping m : wanted) {
                if (this.gateway.addMapping(m.protocol(), m.port(), DESCRIPTION)) {
                    this.mapped.add(m);
                }
            }
            String host = this.gateway.controlUrl().getHost();
            report.accept(
                    this.mapped.isEmpty()
                            ? "UPnP: the router at " + host + " refused to forward our ports"
                            : "UPnP: "
                                    + String.join(" and ", this.mappings())
                                    + " forwarded by "
                                    + host);
        } catch (IOException e) {
            report.accept("UPnP failed: " + e.getMessage());
        }
    }

    /** Removes the mappings made here. */
    @Override
    public void close() {
        try {
            this.worker.join(CLOSE_WAIT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Igd igd = this.gateway;
        if (igd == null) {
            return;
        }
        for (Mapping m : this.mapped) {
            try {
                igd.deleteMapping(m.protocol(), m.port());
            } catch (IOException e) {
                logger.debug(
                        "UPnP: cannot remove {} {}: {}", m.protocol(), m.port(), e.getMessage());
            }
        }
        this.mapped.clear();
        igd.close();
    }
}
