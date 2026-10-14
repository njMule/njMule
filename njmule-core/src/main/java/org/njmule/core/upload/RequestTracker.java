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
package org.njmule.core.upload;

import java.net.InetAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.njmule.core.api.Hash16;

/**
 * How each client, by address and user hash, used our queue lately: how soon it asked again for each file and how many
 * slots it left idle. Kept while the client is out of the queue too, so leaving and coming back clears nothing. Engine
 * thread only.
 */
final class RequestTracker {

    /** A client: its address and user hash. */
    record Key(InetAddress ip, Hash16 userHash) {}

    // Clients re-asking on a timer of exactly the shortest time come a little early now and then.
    private static final Duration TOLERANCE = Duration.ofSeconds(10);
    private static final int MAX_FILES = 32;

    private final long fastAsk;
    private final long window;
    private final Map<Key, History> histories = new HashMap<>();

    /**
     * @param minAsk the shortest time between two asks for a file that does not count against a client
     * @param window how long idle slots count, and a client is remembered
     */
    RequestTracker(Duration minAsk, Duration window) {
        this.fastAsk = minAsk.minus(TOLERANCE).toNanos();
        this.window = window.toNanos();
    }

    /** Counts an ask for {@code file}; returns how many asks for it came too soon with no slower one between. */
    int asked(Key client, Hash16 file, long now) {
        History history = this.history(client, now);
        Ask ask = history.asks.get(file);
        if (ask == null) {
            history.asks.put(file, new Ask(now));
            if (history.asks.size() > MAX_FILES) {
                history.asks.remove(history.asks.keySet().iterator().next());
            }
            return 0;
        }
        ask.bad = now - ask.last < this.fastAsk ? ask.bad + 1 : Math.max(0, ask.bad - 1);
        ask.last = now;
        return ask.bad;
    }

    /** A slot ended because its client asked for nothing; returns how many did within the window. */
    int idleSlot(Key client, long now) {
        History history = this.history(client, now);
        if (history.idleSlots == 0 || now - history.firstIdle >= this.window) {
            history.idleSlots = 0;
            history.firstIdle = now;
        }
        return ++history.idleSlots;
    }

    /** A slot sent file data: the idle slots of its client are forgiven. */
    void productive(Key client) {
        History history = this.histories.get(client);
        if (history != null) {
            history.idleSlots = 0;
        }
    }

    /** Forgets the clients not heard of within the window. */
    void purge(long now) {
        this.histories.values().removeIf(r -> now - r.seen >= this.window);
    }

    int size() {
        return this.histories.size();
    }

    private History history(Key client, long now) {
        History history = this.histories.computeIfAbsent(client, k -> new History());
        history.seen = now;
        return history;
    }

    private static final class History {
        private final Map<Hash16, Ask> asks = new LinkedHashMap<>();
        private long seen;
        private int idleSlots;
        private long firstIdle;
    }

    /** The last ask for a file and how many came too soon. */
    private static final class Ask {
        private long last;
        private int bad;

        Ask(long last) {
            this.last = last;
        }
    }
}
