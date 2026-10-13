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
 * Admission control for incoming TCP connections: the IP filter first, then caps on the total and
 * per-address connections and on the accept rate. Thread-safe.
 */
public final class ConnectionGuard {

    /** Why a connection was refused. */
    public enum Refusal {
        FILTERED,
        TOO_MANY_CONNECTIONS,
        TOO_MANY_FROM_ADDRESS,
        ACCEPT_RATE
    }

    /** Admission outcome: a permit to release when the connection closes, or a refusal. */
    public sealed interface Admission {
        record Admitted(Permit permit) implements Admission {}

        record Refused(Refusal reason) implements Admission {}
    }

    /** Limits; the accept rate counts connections admitted within {@code rateWindow}. */
    public record Limits(int maxTotal, int maxPerAddress, int maxAccepts, Duration rateWindow) {
        public static final Limits DEFAULT = new Limits(500, 5, 20, Duration.ofSeconds(5));

        public Limits {
            if (maxTotal < 1 || maxPerAddress < 1 || maxAccepts < 1) {
                throw new IllegalArgumentException("limits must be positive");
            }
            Objects.requireNonNull(rateWindow, "rateWindow");
        }
    }

    private final Supplier<IpFilter> filter;
    private final IntPredicate exempt;
    private final Limits limits;
    private final Ticker ticker;
    private final Map<Integer, Integer> perAddress = new HashMap<>();
    private final ArrayDeque<Long> recentAccepts = new ArrayDeque<>();
    private int total;

    /** {@code filter} is read on every admission, so a reloaded filter applies at once. */
    public ConnectionGuard(Supplier<IpFilter> filter, Limits limits, Ticker ticker) {
        this(filter, ip -> false, limits, ticker);
    }

    /**
     * @param exempt addresses never held back by the accept rate or the total, such as the server
     *     testing whether we are reachable: refusing it would leave us with a LowID
     */
    public ConnectionGuard(
            Supplier<IpFilter> filter, IntPredicate exempt, Limits limits, Ticker ticker) {
        this.filter = Objects.requireNonNull(filter, "filter");
        this.exempt = Objects.requireNonNull(exempt, "exempt");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
    }

    /** Decides on a connection from {@code ip} ({@link org.njmule.core.util.Ipv4} form). */
    public synchronized Admission admit(int ip) {
        if (this.filter.get().blocks(ip)) {
            return new Admission.Refused(Refusal.FILTERED);
        }
        long now = this.ticker.read();
        long window = this.limits.rateWindow().toNanos();
        while (!this.recentAccepts.isEmpty() && now - this.recentAccepts.peekFirst() >= window) {
            this.recentAccepts.pollFirst();
        }
        boolean exempted = this.exempt.test(ip);
        if (!exempted && this.recentAccepts.size() >= this.limits.maxAccepts()) {
            return new Admission.Refused(Refusal.ACCEPT_RATE);
        }
        if (!exempted && this.total >= this.limits.maxTotal()) {
            return new Admission.Refused(Refusal.TOO_MANY_CONNECTIONS);
        }
        int fromAddress = this.perAddress.getOrDefault(ip, 0);
        if (fromAddress >= this.limits.maxPerAddress()) {
            return new Admission.Refused(Refusal.TOO_MANY_FROM_ADDRESS);
        }
        this.recentAccepts.addLast(now);
        this.perAddress.put(ip, fromAddress + 1);
        this.total++;
        return new Admission.Admitted(new Permit(ip));
    }

    /** Connections currently holding a permit. */
    public synchronized int openConnections() {
        return this.total;
    }

    private synchronized void release(int ip) {
        this.total--;
        this.perAddress.computeIfPresent(ip, (key, count) -> count > 1 ? count - 1 : null);
    }

    /** Held while a connection is open; closing it more than once has no effect. */
    public final class Permit implements AutoCloseable {
        private final int ip;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(int ip) {
            this.ip = ip;
        }

        @Override
        public void close() {
            if (this.released.compareAndSet(false, true)) {
                ConnectionGuard.this.release(this.ip);
            }
        }
    }
}
