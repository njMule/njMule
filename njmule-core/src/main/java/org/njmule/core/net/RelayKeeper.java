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
package org.njmule.core.net;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import org.njmule.core.api.LogLine;
import org.njmule.core.config.ProxySettings;

/**
 * Keeps a UDP socket on the route its proxy asks for: straight without a proxy, or with one that relays no UDP (which
 * the log says), through a SOCKS5 association otherwise, made again when it ends. Nothing goes while an association is
 * being made, so no datagram leaves outside the proxy.
 */
public final class RelayKeeper implements AutoCloseable {

    /** How long to wait for each answer of the proxy. */
    static final Duration TIMEOUT = Duration.ofSeconds(15);

    /** The first wait before asking the proxy again; it doubles up to {@link #LONGEST_WAIT}. */
    static final Duration FIRST_WAIT = Duration.ofSeconds(30);

    static final Duration LONGEST_WAIT = Duration.ofMinutes(5);

    /** Where lines for the front ends' log go; from any thread. */
    @FunctionalInterface
    public interface Log {
        void line(LogLine.Level level, String text);
    }

    private final String what;
    private final Consumer<UdpRoute> target;
    private final InetAddress local;
    private final Log log;
    private final Duration firstWait;
    private final Object lock = new Object();
    // Guarded by lock: the settings followed, and which of the workers started for them is the current one.
    private ProxySettings settings;
    private int generation;
    private Thread worker;
    private Socks5Udp association;
    private boolean closed;

    private RelayKeeper(String what, Consumer<UdpRoute> target, InetAddress local, Log log, Duration firstWait) {
        this.what = Objects.requireNonNull(what, "what");
        this.target = Objects.requireNonNull(target, "target");
        this.local = local;
        this.log = Objects.requireNonNull(log, "log");
        this.firstWait = firstWait;
    }

    /**
     * Puts the socket that {@code target} routes on the route of {@code settings} at once, then keeps it there.
     *
     * @param what the socket, as the log names it ("Kad UDP")
     * @param local the address to reach the proxy from; null for any
     */
    public static RelayKeeper start(
            String what, Consumer<UdpRoute> target, ProxySettings settings, InetAddress local, Log log) {
        return start(what, target, settings, local, log, FIRST_WAIT);
    }

    static RelayKeeper start(
            String what,
            Consumer<UdpRoute> target,
            ProxySettings settings,
            InetAddress local,
            Log log,
            Duration firstWait) {
        RelayKeeper keeper = new RelayKeeper(what, target, local, log, firstWait);
        keeper.use(settings);
        return keeper;
    }

    /** Follows {@code now}, the proxy settings that changed; the same settings change nothing. */
    public void use(ProxySettings now) {
        Objects.requireNonNull(now, "now");
        synchronized (this.lock) {
            if (this.closed || now.equals(this.settings)) {
                return;
            }
            this.settings = now;
            stopWorker();
            int current = ++this.generation;
            switch (now.type()) {
                case NONE -> this.target.accept(UdpRoute.DIRECT);
                case HTTP -> {
                    this.target.accept(UdpRoute.DIRECT);
                    this.log.line(
                            LogLine.Level.WARNING,
                            this.what + " goes straight to the network: an HTTP proxy carries TCP only");
                }
                case SOCKS -> {
                    this.target.accept(UdpRoute.HELD);
                    this.worker = Thread.ofVirtual().name("njmule-udp-relay").start(() -> keep(now, current));
                }
            }
        }
    }

    // Asks for an association, routes through it while it lasts, and asks again after it ends or fails.
    private void keep(ProxySettings proxy, int current) {
        Duration wait = this.firstWait;
        boolean failedBefore = false;
        try {
            while (true) {
                try {
                    Socks5Udp made = Socks5Udp.associate(proxy, this.local, TIMEOUT);
                    if (!routeThrough(made, current)) {
                        made.close();
                        return;
                    }
                    this.log.line(
                            LogLine.Level.INFO,
                            this.what + " goes through the UDP relay of the SOCKS5 proxy, " + made.relay());
                    wait = this.firstWait;
                    failedBefore = false;
                    made.awaitEnd();
                    if (!route(UdpRoute.HELD, current)) {
                        return;
                    }
                    this.log.line(
                            LogLine.Level.WARNING,
                            this.what + " is held: the SOCKS5 proxy ended its UDP relay, asking it again");
                } catch (Socks5.Unsupported e) {
                    if (route(UdpRoute.DIRECT, current)) {
                        this.log.line(
                                LogLine.Level.WARNING, this.what + " goes straight to the network: " + e.getMessage());
                    }
                    return;
                } catch (IOException e) {
                    if (!failedBefore) {
                        this.log.line(
                                LogLine.Level.WARNING,
                                this.what + " is held until the SOCKS5 proxy answers: " + e.getMessage());
                    }
                    failedBefore = true;
                }
                Thread.sleep(wait.toMillis());
                wait = wait.multipliedBy(2).compareTo(LONGEST_WAIT) > 0 ? LONGEST_WAIT : wait.multipliedBy(2);
            }
        } catch (InterruptedException e) {
            // Other settings, or closed.
        }
    }

    // Routes through a new association, unless other settings came meanwhile.
    private boolean routeThrough(Socks5Udp made, int current) {
        synchronized (this.lock) {
            if (this.closed || current != this.generation) {
                return false;
            }
            this.association = made;
            this.target.accept(UdpRoute.through(made));
            return true;
        }
    }

    private boolean route(UdpRoute route, int current) {
        synchronized (this.lock) {
            if (this.closed || current != this.generation) {
                return false;
            }
            this.association = null;
            this.target.accept(route);
            return true;
        }
    }

    // Under lock.
    private void stopWorker() {
        if (this.worker != null) {
            this.worker.interrupt();
            this.worker = null;
        }
        if (this.association != null) {
            this.association.close();
            this.association = null;
        }
    }

    /** Stops keeping the route; the socket keeps the last one, for its own close. */
    @Override
    public void close() {
        synchronized (this.lock) {
            this.closed = true;
            stopWorker();
        }
    }
}
