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
 * Our ports forwarded by the router while njMule runs: found with UPnP, or else with NAT-PMP or PCP, and mapped on a
 * virtual thread at the start, for a lease renewed at half its time (which also recreates mappings the router lost),
 * unmapped at the end. Only the mappings made here are ever removed.
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

    // The shortest wait between renewals, whatever lease a router grants.
    private static final Duration SHORTEST_RENEWAL = Duration.ofSeconds(10);

    private final Routers routers;
    private final List<Mapping> wanted;
    private final Report report;
    private final Pause pause;
    // Mappings the router may hold for us: those made, and those asked for whose answer did not come.
    private final CopyOnWriteArrayList<Mapping> mapped = new CopyOnWriteArrayList<>();
    private final CountDownLatch started = new CountDownLatch(1);
    private final Thread worker;
    private volatile Gateway gateway;
    private volatile boolean closed;
    // Worker thread only: the lease asked for, and the shortest the router granted at the last round (0: none).
    private long leaseSeconds = LEASE.toSeconds();
    private long grantedSeconds;

    private PortForwarding(Routers routers, List<Mapping> wanted, Report report, Pause pause) {
        this.routers = Objects.requireNonNull(routers, "routers");
        this.wanted = List.copyOf(wanted);
        this.report = Objects.requireNonNull(report, "report");
        this.pause = Objects.requireNonNull(pause, "pause");
        this.worker = Thread.ofVirtual().name("njmule-upnp").unstarted(this::run);
    }

    /**
     * Starts forwarding {@code tcpPort} and, when present, {@code udpPort} by a router of {@code routers};
     * {@code report} gets the outcome.
     */
    public static PortForwarding start(Routers routers, int tcpPort, OptionalInt udpPort, Report report) {
        return start(routers, tcpPort, udpPort, report, Thread::sleep);
    }

    /** As above, with UPnP only, searched at {@code ssdp}. */
    public static PortForwarding start(InetSocketAddress ssdp, int tcpPort, OptionalInt udpPort, Report report) {
        return start(Routers.upnpOnly(ssdp), tcpPort, udpPort, report);
    }

    static PortForwarding start(InetSocketAddress ssdp, int tcpPort, OptionalInt udpPort, Report report, Pause pause) {
        return start(Routers.upnpOnly(ssdp), tcpPort, udpPort, report, pause);
    }

    static PortForwarding start(Routers routers, int tcpPort, OptionalInt udpPort, Report report, Pause pause) {
        List<Mapping> wanted = new ArrayList<>();
        wanted.add(new Mapping("TCP", tcpPort));
        udpPort.ifPresent(p -> wanted.add(new Mapping("UDP", p)));
        PortForwarding forwarding = new PortForwarding(routers, wanted, report, pause);
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
            Optional<Gateway> found = discover();
            if (found.isEmpty()) {
                this.report.line(Outcome.NO_ROUTER, "", "UPnP, NAT-PMP and PCP: no router answered");
                return;
            }
            this.gateway = found.get();
            mapAll(true);
            this.started.countDown();
            while (!this.closed) {
                this.pause.sleep(renewal());
                renew();
            }
        } catch (InterruptedException e) {
            // Closing.
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (!this.closed) {
                Gateway found = this.gateway;
                this.report.line(
                        Outcome.FAILED,
                        found == null ? "" : found.host(),
                        (found == null ? "UPnP" : found.protocol()) + " failed: " + e.getMessage());
            }
        } finally {
            this.started.countDown();
        }
    }

    // UPnP first, the router of most homes; else NAT-PMP or PCP at the default gateway.
    private Optional<Gateway> discover() throws IOException {
        Optional<Igd> upnp = Igd.discover(this.routers.ssdp(), DISCOVERY);
        if (upnp.isPresent()) {
            return Optional.of(upnp.get());
        }
        Optional<InetSocketAddress> natPmp = this.routers.natPmp().get();
        return natPmp.isEmpty()
                ? Optional.empty()
                : NatPmp.discover(natPmp.get(), DISCOVERY).map(Gateway.class::cast);
    }

    // Half the shortest lease granted, as for the one asked for; a router may grant less.
    private Duration renewal() {
        Duration lease = this.grantedSeconds > 0 ? Duration.ofSeconds(this.grantedSeconds) : LEASE;
        Duration half = lease.dividedBy(2);
        return half.compareTo(SHORTEST_RENEWAL) < 0 ? SHORTEST_RENEWAL : half;
    }

    // Asks for every port; refusals are reported the first time and tried again at each renewal.
    private void mapAll(boolean tell) throws IOException, InterruptedException {
        List<String> refused = new ArrayList<>();
        this.grantedSeconds = 0;
        for (Mapping m : this.wanted) {
            try {
                long granted = add(m);
                if (granted > 0 && (this.grantedSeconds == 0 || granted < this.grantedSeconds)) {
                    this.grantedSeconds = granted;
                }
            } catch (Gateway.Refused e) {
                refused.add(m + " (" + e.getMessage() + ")");
            }
        }
        if (!tell) {
            return;
        }
        String host = this.gateway.host();
        String protocol = this.gateway.protocol();
        String forwarded = String.join(" and ", mappings()) + " forwarded by " + host;
        String denied = String.join(" and ", refused);
        if (refused.isEmpty()) {
            this.report.line(Outcome.FORWARDED, host, protocol + ": " + forwarded);
        } else if (this.mapped.isEmpty()) {
            this.report.line(Outcome.REFUSED, host, protocol + ": the router at " + host + " refused " + denied);
        } else {
            this.report.line(Outcome.PARTLY, host, protocol + ": " + forwarded + ", which refused " + denied);
        }
    }

    // A renewal asks again, from our address now; a router that no longer answers is looked for again.
    private void renew() throws InterruptedException {
        try {
            mapAll(false);
            return;
        } catch (IOException e) {
            logger.debug("Port forwarding: renewal failed, looking for the router again: {}", e.getMessage());
        }
        try {
            Optional<Gateway> found = discover();
            if (found.isEmpty()) {
                return;
            }
            Gateway old = this.gateway;
            this.gateway = found.get();
            old.close();
            mapAll(true);
        } catch (IOException e) {
            // Tried again at the next renewal.
            logger.debug("Port forwarding: the router is still away: {}", e.getMessage());
        }
    }

    // The lease granted, 0 for until removed.
    private long add(Mapping m) throws IOException, InterruptedException {
        Gateway router = this.gateway;
        boolean known = this.mapped.contains(m);
        // Ours from the request on: an answer lost to the end may still have made it.
        this.mapped.addIfAbsent(m);
        long granted;
        try {
            try {
                granted = router.addMapping(m.protocol(), m.port(), DESCRIPTION, this.leaseSeconds);
            } catch (Igd.Refused e) {
                if (this.leaseSeconds == 0 || !e.getMessage().startsWith(ONLY_PERMANENT_LEASES)) {
                    throw e;
                }
                this.leaseSeconds = 0;
                granted = router.addMapping(m.protocol(), m.port(), DESCRIPTION, 0);
            }
        } catch (Gateway.Refused e) {
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
        return granted;
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
        Gateway router = this.gateway;
        if (router == null) {
            return;
        }
        for (Mapping m : this.mapped) {
            try {
                router.deleteMapping(m.protocol(), m.port());
            } catch (IOException e) {
                logger.debug("{}: cannot remove {}: {}", router.protocol(), m, e.getMessage());
            }
        }
        this.mapped.clear();
        router.close();
    }
}
