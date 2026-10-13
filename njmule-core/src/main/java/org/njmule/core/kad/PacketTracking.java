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
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import org.njmule.core.protocol.packet.Opcodes.Kad;

/**
 * Flood control and answer checking for Kad datagrams: each address may send each kind of request
 * only so often (a long burst bans it for two hours), and an answer is taken only from an address
 * and port we sent the matching request to in the last three minutes. Engine thread only.
 */
final class PacketTracking {

    /** How long a request waits for its answer. */
    static final Duration ANSWER_WINDOW = Duration.ofMinutes(3);

    static final Duration BAN_TIME = Duration.ofHours(2);

    private static final long MINUTE_MS = 60_000;
    private static final long BAN_DEBT_MS = 3 * MINUTE_MS;
    private static final Map<Kad, Integer> PER_MINUTE =
            Map.ofEntries(
                    Map.entry(Kad.BOOTSTRAP_REQ, 2),
                    Map.entry(Kad.HELLO_REQ, 3),
                    Map.entry(Kad.REQ, 10),
                    Map.entry(Kad.SEARCH_KEY_REQ, 3),
                    Map.entry(Kad.SEARCH_SOURCE_REQ, 3),
                    Map.entry(Kad.SEARCH_NOTES_REQ, 3),
                    Map.entry(Kad.PUBLISH_KEY_REQ, 4),
                    Map.entry(Kad.PUBLISH_SOURCE_REQ, 3),
                    Map.entry(Kad.PUBLISH_NOTES_REQ, 2),
                    Map.entry(Kad.FIREWALLED_REQ, 2),
                    Map.entry(Kad.FIREWALLED2_REQ, 2),
                    Map.entry(Kad.FINDBUDDY_REQ, 2),
                    Map.entry(Kad.CALLBACK_REQ, 1),
                    Map.entry(Kad.PING, 2));

    private record Key(int ip, Kad kind) {}

    private record Sent(int ip, int port, Kad request, long at) {}

    private final Map<Key, long[]> buckets = new HashMap<>();
    private final Map<Integer, Long> bans = new HashMap<>();
    private final Deque<Sent> sent = new ArrayDeque<>();

    /** Whether a request may be served; may ban the address. Times in milliseconds. */
    boolean allow(int ip, Kad request, long nowMs) {
        Integer perMinute = PER_MINUTE.get(request);
        if (perMinute == null) {
            return true;
        }
        if (this.isBanned(ip, nowMs)) {
            return false;
        }
        // Firewall requests share one budget.
        Kad kind = request == Kad.FIREWALLED2_REQ ? Kad.FIREWALLED_REQ : request;
        long cost = MINUTE_MS / perMinute;
        long[] bucket = this.buckets.get(new Key(ip, kind));
        if (bucket == null) {
            this.buckets.put(new Key(ip, kind), new long[] {MINUTE_MS - cost, nowMs});
            return true;
        }
        long tokens = Math.min(bucket[0] + (nowMs - bucket[1]), MINUTE_MS) - cost;
        bucket[0] = tokens;
        bucket[1] = nowMs;
        if (tokens < -BAN_DEBT_MS) {
            this.bans.put(ip, nowMs + BAN_TIME.toMillis());
        }
        return tokens >= 0;
    }

    boolean isBanned(int ip, long nowMs) {
        Long until = this.bans.get(ip);
        if (until == null) {
            return false;
        }
        if (nowMs - until >= 0) {
            this.bans.remove(ip);
            return false;
        }
        return true;
    }

    /** We sent {@code request} to {@code ip:port}. */
    void sent(int ip, int port, Kad request, long nowMs) {
        this.sent.addLast(new Sent(ip, port, request, nowMs));
    }

    /**
     * Whether {@code answer} from {@code ip:port} answers a request of ours; the request is
     * consumed.
     */
    boolean expected(int ip, int port, Kad answer, long nowMs) {
        this.expire(nowMs);
        Optional<Kad[]> requests = requestsOf(answer);
        if (requests.isEmpty()) {
            return false;
        }
        for (Iterator<Sent> it = this.sent.iterator(); it.hasNext(); ) {
            Sent s = it.next();
            if (s.ip() == ip && s.port() == port) {
                for (Kad request : requests.get()) {
                    if (s.request() == request) {
                        it.remove();
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Whether some request to {@code ip} waits for {@code answer}, without consuming it. */
    boolean awaits(int ip, Kad answer, long nowMs) {
        this.expire(nowMs);
        Optional<Kad[]> requests = requestsOf(answer);
        if (requests.isEmpty()) {
            return false;
        }
        for (Sent s : this.sent) {
            for (Kad request : requests.get()) {
                if (s.ip() == ip && s.request() == request) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Drops what is old; call every few minutes. */
    void clean(long nowMs) {
        this.expire(nowMs);
        this.buckets.values().removeIf(b -> nowMs - b[1] > 2 * MINUTE_MS);
        this.bans.values().removeIf(until -> nowMs - until >= 0);
    }

    private void expire(long nowMs) {
        while (!this.sent.isEmpty()
                && nowMs - this.sent.peekFirst().at() > ANSWER_WINDOW.toMillis()) {
            this.sent.removeFirst();
        }
    }

    private static Optional<Kad[]> requestsOf(Kad answer) {
        return Optional.ofNullable(
                switch (answer) {
                    case BOOTSTRAP_RES -> new Kad[] {Kad.BOOTSTRAP_REQ};
                    case HELLO_RES -> new Kad[] {Kad.HELLO_REQ};
                    case HELLO_RES_ACK -> new Kad[] {Kad.HELLO_RES};
                    case RES -> new Kad[] {Kad.REQ};
                    case SEARCH_RES ->
                            new Kad[] {
                                Kad.SEARCH_KEY_REQ, Kad.SEARCH_SOURCE_REQ, Kad.SEARCH_NOTES_REQ
                            };
                    case PUBLISH_RES ->
                            new Kad[] {
                                Kad.PUBLISH_KEY_REQ, Kad.PUBLISH_SOURCE_REQ, Kad.PUBLISH_NOTES_REQ
                            };
                    case FIREWALLED_RES -> new Kad[] {Kad.FIREWALLED_REQ, Kad.FIREWALLED2_REQ};
                    case FINDBUDDY_RES -> new Kad[] {Kad.FINDBUDDY_REQ};
                    case PONG -> new Kad[] {Kad.PING};
                    default -> null;
                });
    }
}
