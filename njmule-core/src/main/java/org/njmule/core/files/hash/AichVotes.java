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
package org.njmule.core.files.hash;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.njmule.core.api.Hash20;

/**
 * The AICH master hashes sources announce for a file, trusted when at least {@value #MIN_SIGNERS} networks (/20)
 * announce the same one and it is {@value #MIN_PERCENT}% of the announcements. A network counts for the first hash it
 * announces only, so that one host cannot outvote the others.
 */
public final class AichVotes {

    public static final int MIN_SIGNERS = 10;
    public static final int MIN_PERCENT = 92;

    private final Map<Hash20, Set<Integer>> signers = new LinkedHashMap<>();
    private final Map<Integer, Hash20> byNetwork = new HashMap<>();

    /** {@code ip} (in {@code Ipv4} form) announced {@code hash}; returns the hash trusted now, if any. */
    public Optional<Hash20> announced(int ip, Hash20 hash) {
        int network = ip >>> 12;
        Hash20 before = this.byNetwork.putIfAbsent(network, hash);
        if (before == null || before.equals(hash)) {
            this.signers.computeIfAbsent(hash, h -> new TreeSet<>()).add(network);
        }
        return this.trusted();
    }

    public Optional<Hash20> trusted() {
        int total = 0;
        Hash20 most = null;
        int mostSigners = 0;
        for (Map.Entry<Hash20, Set<Integer>> e : this.signers.entrySet()) {
            int count = e.getValue().size();
            total += count;
            if (count > mostSigners) {
                most = e.getKey();
                mostSigners = count;
            }
        }
        return most != null && mostSigners >= MIN_SIGNERS && 100 * mostSigners / total >= MIN_PERCENT
                ? Optional.of(most)
                : Optional.empty();
    }
}
