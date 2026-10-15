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
package org.njmule.core.kad;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.njmule.core.protocol.kad.KadMessages;

/**
 * The UDP firewall test. A helper is a node we never wrote to, so that no NAT mapping lets its datagram through by
 * chance; asked over TCP, it sends one to our UDP port. One that arrives means the port is open, two helpers whose
 * datagram never came mean it is firewalled. Before asking, a few nodes tell us the UDP port they see us on, which a
 * NAT may change: helpers try that one too. Engine thread only.
 */
final class UdpFirewallTest {

    /**
     * Helpers with an outcome that end a test: more raise the odds of a false "open", fewer of a false "firewalled".
     */
    static final int HELPERS = 2;

    /** A TCP-firewalled node whose test brings no outcome this long counts as UDP-firewalled too. */
    static final Duration TIMEOUT = Duration.ofMinutes(6);

    /** Nodes asked the UDP port they see us on; two that agree decide it. */
    static final int PORT_SAMPLES = 3;

    // The second datagram of a helper may come soon after the first, on the other port.
    private static final Duration LATE_DATAGRAM = Duration.ofSeconds(10);
    private static final int MAX_CANDIDATES = 64;
    // Helpers are never asked twice; the oldest are forgotten past this many.
    private static final int MAX_ASKED = 256;

    /** A helper asked in test {@code round}, and whether its outcome came. */
    private record Asked(int round, boolean answered) {}

    private final Deque<KadMessages.Contact> candidates = new ArrayDeque<>();
    private final Map<Integer, Asked> asked = new LinkedHashMap<>();
    private final List<Integer> portAnswerers = new ArrayList<>();
    private final List<Integer> portsSeen = new ArrayList<>();
    private boolean portDecided;
    private int externalPort;
    private boolean useExternalPort = true;
    private boolean firewalled;
    private boolean lastFirewalled;
    private boolean verified;
    private boolean timedOut;
    private int round;
    private int running;
    private int finished;
    // When the test that waits for its outcome started; 0 when none waits.
    private long started;
    private boolean succeededOnce;
    private long lastSuccess;

    /** Whether a test runs: from the start, and again after each recheck until it has its outcome. */
    boolean isRunning() {
        return this.finished < HELPERS;
    }

    /**
     * Whether our UDP port is firewalled, as far as we know; while a test runs the last outcome stands when
     * {@code lastWhileTesting}. A test that timed out says firewalled.
     */
    boolean isFirewalled(boolean lastWhileTesting) {
        boolean testing = isRunning();
        if (this.timedOut && testing) {
            return true;
        }
        return (lastWhileTesting && testing) ? this.lastFirewalled : this.firewalled;
    }

    /** Whether a test ever ended with an outcome. */
    boolean isVerified() {
        return this.verified;
    }

    /** The UDP port a NAT shows other nodes, 0 while unknown or when the nodes disagree. */
    int externalPort() {
        return this.externalPort;
    }

    /** Whether others should reach us on the port a NAT shows them rather than on our own. */
    boolean useExternalPort() {
        return this.useExternalPort;
    }

    /** Whether nodes are still asked the UDP port they see us on. */
    boolean findsExternalPort() {
        return !this.portDecided;
    }

    /** Whether helpers are missing and none is left to ask. */
    boolean wantsCandidates() {
        return needsHelpers() && this.candidates.isEmpty();
    }

    /** Starts a new test; the outcome of the last one stands meanwhile. */
    void recheck(boolean connected, long now) {
        this.round++;
        this.running = 0;
        this.finished = 0;
        this.succeededOnce = false;
        this.started = connected ? now : 0;
        this.timedOut = false;
        this.lastFirewalled = this.firewalled;
        this.candidates.clear();
        this.portAnswerers.clear();
        this.portsSeen.clear();
        this.portDecided = false;
    }

    /** Runs every second: the test's time counts from when Kad is connected; one without an outcome times out. */
    void tick(boolean connected, boolean tcpFirewalled, long now) {
        if (!isRunning()) {
            return;
        }
        if (this.started == 0 && connected) {
            this.started = now;
        }
        // A port proven open once is not lost to a timeout.
        if (!this.timedOut
                && !this.firewalled
                && tcpFirewalled
                && this.started != 0
                && !this.verified
                && now - this.started >= TIMEOUT.toNanos()) {
            this.timedOut = true;
        }
    }

    /** A node told us the UDP port it saw us on: one sample per node, a port seen twice is the one. */
    void portSeen(int port, int from) {
        if (this.portDecided || this.portAnswerers.contains(from)) {
            return;
        }
        this.portAnswerers.add(from);
        if (this.portsSeen.contains(port)) {
            this.externalPort = port;
            this.portDecided = true;
            return;
        }
        this.portsSeen.add(port);
        if (this.portAnswerers.size() >= PORT_SAMPLES) {
            // The nodes disagree: a NAT that changes the port for each of them cannot be used.
            this.externalPort = 0;
            this.portDecided = true;
        }
    }

    /** A contact that a lookup for helpers found. */
    void candidate(KadMessages.Contact contact) {
        if (!isRunning()) {
            return;
        }
        this.candidates.addFirst(contact);
        while (this.candidates.size() > MAX_CANDIDATES) {
            this.candidates.removeLast();
        }
    }

    /**
     * The next helper to ask, once the external port is known: a recent enough node that is not us, never asked before
     * and {@code usable}; it counts as asked from now on.
     */
    Optional<KadMessages.Contact> nextHelper(Predicate<KadMessages.Contact> usable, int ourIp, UInt128 ourId) {
        if (!needsHelpers() || findsExternalPort()) {
            return Optional.empty();
        }
        while (!this.candidates.isEmpty()) {
            KadMessages.Contact c = this.candidates.removeFirst();
            // Older nodes cannot help.
            if (c.version() < 6
                    || (ourIp != 0 && c.ip() == ourIp)
                    || c.id().equals(ourId)
                    || this.asked.containsKey(c.ip())
                    || !usable.test(c)) {
                continue;
            }
            this.asked.put(c.ip(), new Asked(this.round, false));
            Iterator<Integer> oldest = this.asked.keySet().iterator();
            while (this.asked.size() > MAX_ASKED) {
                oldest.next();
                oldest.remove();
            }
            this.running++;
            return Optional.of(c);
        }
        return Optional.empty();
    }

    /** Whether the helper at {@code ip} was asked and its outcome is awaited or came. */
    boolean asked(int ip) {
        return this.asked.containsKey(ip);
    }

    /**
     * The outcome a helper gave: {@code succeeded} when its datagram reached {@code port} of ours, {@code cancelled}
     * when the helper could not test (it knew us, or the request never went). Returns whether another helper should be
     * asked now.
     */
    boolean outcome(int ip, boolean succeeded, boolean cancelled, int port, int internalPort, long now) {
        Asked helper = this.asked.get(ip);
        if (helper == null) {
            return false;
        }
        // The helper's datagram to our own port came right after the one to the NAT's: our own port is better.
        if (!isRunning()
                && !this.firewalled
                && this.succeededOnce
                && now - this.lastSuccess < LATE_DATAGRAM.toNanos()
                && succeeded
                && port == internalPort
                && this.useExternalPort) {
            this.useExternalPort = false;
            return false;
        }
        if (helper.answered() || helper.round() != this.round) {
            return false;
        }
        this.asked.put(ip, new Asked(helper.round(), true));
        if (!isRunning()) {
            return false;
        }
        if (this.running > 0) {
            this.running--;
        }
        if (cancelled) {
            return true;
        }
        this.finished++;
        if (succeeded) {
            this.started = 0;
            this.firewalled = false;
            this.verified = true;
            this.timedOut = false;
            this.finished = HELPERS;
            this.running = 0;
            this.candidates.clear();
            this.succeededOnce = true;
            this.lastSuccess = now;
            if (port == internalPort) {
                this.useExternalPort = false;
            } else if (port == this.externalPort && port != 0) {
                this.useExternalPort = true;
            }
            return false;
        }
        if (this.finished >= HELPERS) {
            this.started = 0;
            this.firewalled = true;
            this.verified = true;
            this.timedOut = false;
            this.candidates.clear();
            return false;
        }
        return true;
    }

    private boolean needsHelpers() {
        return isRunning() && this.running + this.finished < HELPERS;
    }
}
