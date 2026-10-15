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
package org.njmule.core.security;

import com.google.common.base.Ticker;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

/**
 * Admission control for incoming TCP connections: the IP filter first, then caps on the total and per-address
 * connections and on the accept rate, in all and per address. Our own connections count in the total too. Thread-safe.
 */
public final class ConnectionGuard {

    /** Why a connection was refused. */
    public enum Refusal {
        FILTERED,
        BANNED,
        TOO_MANY_CONNECTIONS,
        TOO_MANY_FROM_ADDRESS,
        ACCEPT_RATE,
        ACCEPT_RATE_FROM_ADDRESS
    }

    /** Admission outcome: a permit to release when the connection closes, or a refusal. */
    public sealed interface Admission {
        record Admitted(Permit permit) implements Admission {}

        record Refused(Refusal reason) implements Admission {}
    }

    /** Limits; the accept rate counts connections admitted within {@code rateWindow}. */
    public record Limits(int maxTotal, int maxPerAddress, int maxAccepts, Duration rateWindow) {
        /** The longest rate window; a longer one would no longer limit a rate. */
        public static final Duration MAX_RATE_WINDOW = Duration.ofHours(1);

        public static final Limits DEFAULT = new Limits(500, 5, 20, Duration.ofSeconds(5));

        public Limits {
            if (maxTotal < 1 || maxPerAddress < 1 || maxAccepts < 1) {
                throw new IllegalArgumentException("limits must be positive");
            }
            Objects.requireNonNull(rateWindow, "rateWindow");
            // A zero or negative window would turn the rate limit off.
            if (!rateWindow.isPositive() || rateWindow.compareTo(MAX_RATE_WINDOW) > 0) {
                throw new IllegalArgumentException(
                        "the rate window must be within 0-" + MAX_RATE_WINDOW + ": " + rateWindow);
            }
        }
    }

    // Connections of ours in the rate window: no address of theirs is counted.
    private static final int OURS = 0;

    private final Supplier<IpFilter> filter;
    private final IntPredicate exempt;
    private final IntPredicate banned;
    private final Supplier<Limits> limits;
    private final Ticker ticker;
    private final Map<Integer, Integer> perAddress = new HashMap<>();
    private final ArrayDeque<Accept> recentAccepts = new ArrayDeque<>();
    private final Map<Integer, Integer> recentFrom = new HashMap<>();
    private int total;

    private record Accept(long at, int ip) {}

    /** {@code filter} is read on every admission, so a reloaded filter applies at once. */
    public ConnectionGuard(Supplier<IpFilter> filter, Limits limits, Ticker ticker) {
        this(filter, ip -> false, limits, ticker);
    }

    /**
     * @param exempt addresses never held back by the accept rate or the total, such as the server testing whether we
     *     are reachable: refusing it would leave us with a LowID
     */
    public ConnectionGuard(Supplier<IpFilter> filter, IntPredicate exempt, Limits limits, Ticker ticker) {
        this(filter, exempt, ip -> false, limits, ticker);
    }

    /**
     * @param exempt addresses never held back by the accept rate or the total, such as the server testing whether we
     *     are reachable: refusing it would leave us with a LowID
     * @param banned addresses refused for a while because they misbehaved; read on every admission
     */
    public ConnectionGuard(
            Supplier<IpFilter> filter, IntPredicate exempt, IntPredicate banned, Limits limits, Ticker ticker) {
        this(filter, exempt, banned, constant(limits), ticker);
    }

    /** {@code limits} is read on every admission, so the settings apply at once. */
    public ConnectionGuard(
            Supplier<IpFilter> filter,
            IntPredicate exempt,
            IntPredicate banned,
            Supplier<Limits> limits,
            Ticker ticker) {
        this.filter = Objects.requireNonNull(filter, "filter");
        this.exempt = Objects.requireNonNull(exempt, "exempt");
        this.banned = Objects.requireNonNull(banned, "banned");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
    }

    private static Supplier<Limits> constant(Limits limits) {
        Objects.requireNonNull(limits, "limits");
        return () -> limits;
    }

    /** Decides on a connection from {@code ip} ({@link org.njmule.core.util.Ipv4} form). */
    public synchronized Admission admit(int ip) {
        Limits limits = this.limits.get();
        if (this.filter.get().blocks(ip)) {
            return new Admission.Refused(Refusal.FILTERED);
        }
        if (this.banned.test(ip)) {
            return new Admission.Refused(Refusal.BANNED);
        }
        long now = this.ticker.read();
        forgetOldAccepts(limits, now);
        boolean exempted = this.exempt.test(ip);
        if (!exempted && this.recentAccepts.size() >= limits.maxAccepts()) {
            return new Admission.Refused(Refusal.ACCEPT_RATE);
        }
        if (!exempted && this.total >= limits.maxTotal()) {
            return new Admission.Refused(Refusal.TOO_MANY_CONNECTIONS);
        }
        int fromAddress = this.perAddress.getOrDefault(ip, 0);
        if (fromAddress >= limits.maxPerAddress()) {
            return new Admission.Refused(Refusal.TOO_MANY_FROM_ADDRESS);
        }
        if (!exempted && this.recentFrom.getOrDefault(ip, 0) >= acceptsPerAddress(limits)) {
            return new Admission.Refused(Refusal.ACCEPT_RATE_FROM_ADDRESS);
        }
        // Exempt connections leave the accept rate to the others.
        if (!exempted) {
            this.recentAccepts.addLast(new Accept(now, ip));
            this.recentFrom.merge(ip, 1, Integer::sum);
        }
        this.perAddress.put(ip, fromAddress + 1);
        this.total++;
        return new Admission.Admitted(new Permit(ip, true));
    }

    // An address that opens and closes connections in a row could take the whole accept rate and lock everyone else
    // out: it gets a quarter of it, or as many accepts as connections it may hold when that is more.
    private static int acceptsPerAddress(Limits limits) {
        return Math.max(limits.maxPerAddress(), limits.maxAccepts() / 4);
    }

    /**
     * Whether a connection of ours fits: in the total and in the rate of new connections, which incoming ones share.
     */
    public synchronized boolean hasRoom() {
        Limits limits = this.limits.get();
        forgetOldAccepts(limits, this.ticker.read());
        return this.total < limits.maxTotal() && this.recentAccepts.size() < limits.maxAccepts();
    }

    /** Counts a connection of ours in the total until the permit is closed, and in the rate of new connections. */
    public synchronized Permit outgoing() {
        this.recentAccepts.addLast(new Accept(this.ticker.read(), OURS));
        this.total++;
        return new Permit(0, false);
    }

    private void forgetOldAccepts(Limits limits, long now) {
        long window = limits.rateWindow().toNanos();
        while (!this.recentAccepts.isEmpty()
                && now - this.recentAccepts.peekFirst().at() >= window) {
            Accept old = this.recentAccepts.pollFirst();
            this.recentFrom.computeIfPresent(old.ip(), (key, count) -> count > 1 ? count - 1 : null);
        }
    }

    /** Connections currently holding a permit, ours included. */
    public synchronized int openConnections() {
        return this.total;
    }

    private synchronized void release(int ip, boolean incoming) {
        this.total--;
        if (incoming) {
            this.perAddress.computeIfPresent(ip, (key, count) -> count > 1 ? count - 1 : null);
        }
    }

    /** Held while a connection is open; closing it more than once has no effect. */
    public final class Permit implements AutoCloseable {
        private final int ip;
        private final boolean incoming;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(int ip, boolean incoming) {
            this.ip = ip;
            this.incoming = incoming;
        }

        @Override
        public void close() {
            if (this.released.compareAndSet(false, true)) {
                ConnectionGuard.this.release(this.ip, this.incoming);
            }
        }
    }
}
