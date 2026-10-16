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
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Our ports forwarded by the router while njMule runs: found and mapped on a virtual thread at the start, for a lease
 * renewed at half its time (which also recreates mappings the router lost), unmapped at the end. Only the mappings made
 * here are ever removed.
 */
public final class PortForwarding implements AutoCloseable {

    /** How long discovery waits for a router to answer. */
    public static final Duration DISCOVERY = Duration.ofSeconds(3);

    /** How long the router keeps a mapping we no longer renew. */
    public static final Duration LEASE = Duration.ofHours(1);

    private static final Logger logger = LoggerFactory.getLogger(PortForwarding.class);
    private static final String DESCRIPTION = "njMule";
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(5);
    // The UPnP error of a router that keeps mappings only until they are removed.
    private static final String ONLY_PERMANENT_LEASES = "725";

    /** Waits between renewals; tests pass one they control. */
    @FunctionalInterface
    interface Pause {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** How asking the router for our ports ended. */
    public enum Outcome {
        /** Every port is forwarded. */
        FORWARDED,
        /** Some ports are forwarded, the router refused the others. */
        PARTLY,
        /** The router refused every port. */
        REFUSED,
        /** No router answered. */
        NO_ROUTER,
        /** The router stopped answering, or answered what cannot be read. */
        FAILED
    }

    /** Where the outcome goes: its kind, the router's host (empty when none answered) and one line for the log. */
    @FunctionalInterface
    public interface Report {
        void line(Outcome outcome, String router, String text);
    }

    private record Mapping(String protocol, int port) {
        @Override
        public String toString() {
            return this.protocol + " " + this.port;
        }
    }

    private final InetSocketAddress ssdp;
    private final List<Mapping> wanted;
    private final Report report;
    private final Pause pause;
    // Mappings the router may hold for us: those made, and those asked for whose answer did not come.
    private final CopyOnWriteArrayList<Mapping> mapped = new CopyOnWriteArrayList<>();
    private final CountDownLatch started = new CountDownLatch(1);
    private final Thread worker;
    private volatile Igd gateway;
    private volatile boolean closed;
    // Worker thread only.
    private long leaseSeconds = LEASE.toSeconds();

    private PortForwarding(InetSocketAddress ssdp, List<Mapping> wanted, Report report, Pause pause) {
        this.ssdp = Objects.requireNonNull(ssdp, "ssdp");
        this.wanted = List.copyOf(wanted);
        this.report = Objects.requireNonNull(report, "report");
        this.pause = Objects.requireNonNull(pause, "pause");
        this.worker = Thread.ofVirtual().name("njmule-upnp").unstarted(this::run);
    }

    /** Starts forwarding {@code tcpPort} and, when present, {@code udpPort}; {@code report} gets the outcome. */
    public static PortForwarding start(InetSocketAddress ssdp, int tcpPort, OptionalInt udpPort, Report report) {
        return start(ssdp, tcpPort, udpPort, report, Thread::sleep);
    }

    static PortForwarding start(InetSocketAddress ssdp, int tcpPort, OptionalInt udpPort, Report report, Pause pause) {
        List<Mapping> wanted = new ArrayList<>();
        wanted.add(new Mapping("TCP", tcpPort));
        udpPort.ifPresent(p -> wanted.add(new Mapping("UDP", p)));
        PortForwarding forwarding = new PortForwarding(ssdp, wanted, report, pause);
        forwarding.worker.start();
        return forwarding;
    }

    /** The forwarded ports, as "TCP 4662". */
    public List<String> mappings() {
        List<String> all = new ArrayList<>();
        for (Mapping m : this.mapped) {
            all.add(m.toString());
        }
        return all;
    }

    /** Waits for the router to be found and asked for the ports, for tests. */
    public void awaitStarted(Duration wait) throws InterruptedException {
        if (!this.started.await(wait.toNanos(), TimeUnit.NANOSECONDS)) {
            logger.debug("UPnP: not started within {}", wait);
        }
    }

    private void run() {
        try {
            Optional<Igd> found = Igd.discover(this.ssdp, DISCOVERY);
            if (found.isEmpty()) {
                this.report.line(Outcome.NO_ROUTER, "", "UPnP: no router answered");
                return;
            }
            this.gateway = found.get();
            mapAll(true);
            this.started.countDown();
            while (!this.closed) {
                this.pause.sleep(LEASE.dividedBy(2));
                renew();
            }
        } catch (InterruptedException e) {
            // Closing.
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (!this.closed) {
                Igd igd = this.gateway;
                this.report.line(
                        Outcome.FAILED,
                        igd == null ? "" : igd.controlUrl().getHost(),
                        "UPnP failed: " + e.getMessage());
            }
        } finally {
            this.started.countDown();
        }
    }

    // Asks for every port; refusals are reported the first time and tried again at each renewal.
    private void mapAll(boolean tell) throws IOException, InterruptedException {
        List<String> refused = new ArrayList<>();
        for (Mapping m : this.wanted) {
            try {
                add(m);
            } catch (Igd.Refused e) {
                refused.add(m + " (" + e.getMessage() + ")");
            }
        }
        if (!tell) {
            return;
        }
        String host = this.gateway.controlUrl().getHost();
        String forwarded = String.join(" and ", mappings()) + " forwarded by " + host;
        String denied = String.join(" and ", refused);
        if (refused.isEmpty()) {
            this.report.line(Outcome.FORWARDED, host, "UPnP: " + forwarded);
        } else if (this.mapped.isEmpty()) {
            this.report.line(Outcome.REFUSED, host, "UPnP: the router at " + host + " refused " + denied);
        } else {
            this.report.line(Outcome.PARTLY, host, "UPnP: " + forwarded + ", which refused " + denied);
        }
    }

    // A renewal asks again, from our address now; a router that no longer answers is looked for again.
    private void renew() throws InterruptedException {
        try {
            mapAll(false);
            return;
        } catch (IOException e) {
            logger.debug("UPnP: renewal failed, looking for the router again: {}", e.getMessage());
        }
        try {
            Optional<Igd> found = Igd.discover(this.ssdp, DISCOVERY);
            if (found.isEmpty()) {
                return;
            }
            Igd old = this.gateway;
            this.gateway = found.get();
            old.close();
            mapAll(true);
        } catch (IOException e) {
            // Tried again at the next renewal.
            logger.debug("UPnP: the router is still away: {}", e.getMessage());
        }
    }

    private void add(Mapping m) throws IOException, InterruptedException {
        Igd igd = this.gateway;
        boolean known = this.mapped.contains(m);
        // Ours from the request on: an answer lost to the end may still have made it.
        this.mapped.addIfAbsent(m);
        try {
            try {
                igd.addMapping(m.protocol(), m.port(), DESCRIPTION, this.leaseSeconds);
            } catch (Igd.Refused e) {
                if (this.leaseSeconds == 0 || !e.getMessage().startsWith(ONLY_PERMANENT_LEASES)) {
                    throw e;
                }
                this.leaseSeconds = 0;
                igd.addMapping(m.protocol(), m.port(), DESCRIPTION, 0);
            }
        } catch (Igd.Refused e) {
            if (!known) {
                this.mapped.remove(m);
            }
            throw e;
        } catch (IOException e) {
            if (this.closed) {
                throw new InterruptedException("closing");
            }
            throw e;
        }
        if (this.closed) {
            throw new InterruptedException("closing");
        }
    }

    /** Removes the mappings made here; one asked for while closing is removed too. */
    @Override
    public void close() {
        this.closed = true;
        this.worker.interrupt();
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
                logger.debug("UPnP: cannot remove {}: {}", m, e.getMessage());
            }
        }
        this.mapped.clear();
        igd.close();
    }
}
