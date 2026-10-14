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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The ids nodes proved at their address. Kad ids are kept for good, so a node that proves another one soon after the
 * last change moves around the network to sit where it likes; an id merely claimed does not replace a proven one. Each
 * address is forgotten an hour after its last proof. Engine thread only.
 */
final class ProvenIds {

    /** A node changing its id more often than this is not an ordinary client. */
    static final Duration MIN_CHANGE_GAP = Duration.ofHours(1);

    private static final Duration FORGET_AFTER = Duration.ofHours(1);
    private static final int MAX_NODES = 10_000;

    private record Proven(UInt128 id, long changedAt, long seenAt) {}

    // Proven longest ago first.
    private final Map<Long, Proven> nodes = new LinkedHashMap<>();

    /** The node at {@code ip:port} proved it holds {@code id}; true when it changed its id too soon. */
    boolean proved(int ip, int port, UInt128 id, long now) {
        Objects.requireNonNull(id, "id");
        long address = address(ip, port);
        Proven known = this.nodes.remove(address);
        if (known != null && !known.id().equals(id)) {
            if (now - known.changedAt() < MIN_CHANGE_GAP.toNanos()) {
                return true;
            }
            known = null;
        }
        this.nodes.put(address, new Proven(id, known == null ? now : known.changedAt(), now));
        if (this.nodes.size() > MAX_NODES) {
            this.nodes.remove(this.nodes.keySet().iterator().next());
        }
        return false;
    }

    /** Whether the node at {@code ip:port} proved another id than {@code id}. */
    boolean contradicts(int ip, int port, UInt128 id) {
        Proven known = this.nodes.get(address(ip, port));
        return known != null && !known.id().equals(id);
    }

    /** Forgets the addresses that proved nothing for an hour. */
    void clean(long now) {
        for (Iterator<Proven> it = this.nodes.values().iterator(); it.hasNext(); ) {
            if (now - it.next().seenAt() < FORGET_AFTER.toNanos()) {
                return;
            }
            it.remove();
        }
    }

    private static long address(int ip, int port) {
        return Integer.toUnsignedLong(ip) << 16 | (port & 0xFFFF);
    }
}
