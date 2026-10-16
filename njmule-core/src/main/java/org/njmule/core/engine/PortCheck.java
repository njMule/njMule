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
package org.njmule.core.engine;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.njmule.core.api.PortTest;
import org.njmule.core.api.PortTest.Forwarding;
import org.njmule.core.api.PortTest.Reach;
import org.njmule.core.upnp.PortForwarding;

/**
 * A test of our ports: the router asked through UPnP first when it is on, then the server checks our TCP port at a new
 * login (a HighID), and Kad nodes check both ports. Engine thread only; times are a ticker's nanoseconds.
 */
final class PortCheck {

    /** How long a test waits for its outcomes: Kad's UDP test may take minutes. */
    static final Duration WAIT = Duration.ofMinutes(3);

    // A router answers within seconds: past this the other checks start anyway.
    static final Duration UPNP_WAIT = Duration.ofSeconds(15);

    private enum Login {
        NONE,
        WAITING,
        HIGH_ID,
        LOW_ID
    }

    private final int tcpPort;
    private final int udpPort;
    private final boolean upnp;
    private final long started;
    private final long deadline;
    private final CompletableFuture<PortTest> done = new CompletableFuture<>();
    private Forwarding forwarding;
    private String router = "";
    private boolean reachStarted;
    private Login login = Login.NONE;
    private boolean stopped;
    private PortTest state;

    PortCheck(int tcpPort, int udpPort, boolean upnp, long now, Duration wait) {
        this.tcpPort = tcpPort;
        this.udpPort = udpPort;
        this.upnp = upnp;
        this.started = now;
        this.deadline = now + wait.toNanos();
        this.forwarding = upnp ? Forwarding.ASKING : Forwarding.OFF;
        this.state = new PortTest(tcpPort, udpPort, this.forwarding, "", Reach.TESTING, Reach.TESTING, false);
    }

    /** Whether this test is of these ports, asked through UPnP or not. */
    boolean tests(int tcpPort, int udpPort, boolean upnp) {
        return this.tcpPort == tcpPort && this.udpPort == udpPort && this.upnp == upnp;
    }

    CompletableFuture<PortTest> done() {
        return this.done;
    }

    PortTest state() {
        return this.state;
    }

    boolean isOver() {
        return this.state.done();
    }

    /** Whether the server and Kad checks are to start: UPnP answered, or took too long. */
    boolean readyToReach(long now) {
        return !this.reachStarted
                && (this.forwarding != Forwarding.ASKING || now - this.started >= UPNP_WAIT.toNanos());
    }

    /** The server and Kad checks start; {@code login} when a server is asked to take us again. */
    void reachStarted(boolean login) {
        this.reachStarted = true;
        this.login = login ? Login.WAITING : Login.NONE;
    }

    void forwarded(PortForwarding.Outcome outcome, String router) {
        if (this.forwarding != Forwarding.ASKING) {
            return;
        }
        this.router = router;
        this.forwarding = switch (outcome) {
            case FORWARDED -> Forwarding.FORWARDED;
            case PARTLY -> Forwarding.PARTLY;
            case REFUSED -> Forwarding.REFUSED;
            case NO_ROUTER -> Forwarding.NO_ROUTER;
            case FAILED -> Forwarding.FAILED;
        };
    }

    /** A server took us, with a HighID when it reached our TCP port; only the login the test asked for counts. */
    void loggedIn(boolean highId) {
        if (this.login == Login.WAITING) {
            this.login = highId ? Login.HIGH_ID : Login.LOW_ID;
        }
    }

    /**
     * The state as the checks stand: {@code kadTcp} and {@code kadUdp} are Kad's outcomes (open or not), empty while
     * they run. The test is over when every outcome is known or the time is up.
     */
    PortTest update(boolean kadRuns, Optional<Boolean> kadTcp, Optional<Boolean> kadUdp, long now) {
        if (isOver()) {
            return this.state;
        }
        boolean late = this.stopped || now - this.deadline >= 0;
        boolean kadCounts = kadRuns && this.reachStarted;
        Reach tcp = tcp(kadCounts, kadTcp, late);
        Reach udp = !kadCounts
                ? (this.reachStarted ? Reach.UNKNOWN : Reach.TESTING)
                : kadUdp.map(open -> open ? Reach.OPEN : Reach.CLOSED).orElse(late ? Reach.UNKNOWN : Reach.TESTING);
        if (late && this.forwarding == Forwarding.ASKING) {
            this.forwarding = Forwarding.FAILED;
        }
        boolean over = late || (this.forwarding != Forwarding.ASKING && tcp != Reach.TESTING && udp != Reach.TESTING);
        this.state = new PortTest(this.tcpPort, this.udpPort, this.forwarding, this.router, tcp, udp, over);
        if (over) {
            this.done.complete(this.state);
        }
        return this.state;
    }

    /** Ends the test at once, as the engine stops: what is not known stays unknown. */
    void stop() {
        this.stopped = true;
        update(false, Optional.empty(), Optional.empty(), this.started);
    }

    // Open as soon as the server or Kad got through; closed once neither did and nothing more will tell.
    private Reach tcp(boolean kadCounts, Optional<Boolean> kadTcp, boolean late) {
        if (!this.reachStarted) {
            return Reach.TESTING;
        }
        boolean kadOpen = kadCounts && kadTcp.orElse(false);
        if (this.login == Login.HIGH_ID || kadOpen) {
            return Reach.OPEN;
        }
        boolean closed = this.login == Login.LOW_ID || (kadCounts && kadTcp.isPresent());
        boolean pending = this.login == Login.WAITING || (kadCounts && kadTcp.isEmpty());
        if (!pending || late) {
            return closed ? Reach.CLOSED : Reach.UNKNOWN;
        }
        return Reach.TESTING;
    }
}
