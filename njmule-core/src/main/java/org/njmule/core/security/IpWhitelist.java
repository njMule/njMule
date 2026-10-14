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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.njmule.core.persist.format.IpFilterDat;

/**
 * The addresses the user always allows, whatever the IP filter says: one entry per line, an address ({@code 1.2.3.4}),
 * a CIDR block ({@code 1.2.3.0/24}) or a range as ipfilter.dat writes it ({@code 1.2.3.0 - 1.2.3.255 , 100 , name}, the
 * level left aside). Empty lines and lines starting with {@code #} or {@code //} are comments.
 */
public final class IpWhitelist {

    private static final int MAX_PREFIX_DIGITS = 2;

    private IpWhitelist() {}

    /**
     * The ranges of {@code text}.
     *
     * @throws IllegalArgumentException naming the first entry that is none of the forms
     */
    public static List<IpFilterDat.Range> ranges(String text) {
        List<IpFilterDat.Range> ranges = new ArrayList<>();
        for (String entry : entries(text)) {
            ranges.add(range(entry)
                    .orElseThrow(() -> new IllegalArgumentException("not an address, CIDR block or range: " + entry)));
        }
        return ranges;
    }

    /** The first entry of {@code text} that is not an address, a CIDR block or a range, if any. */
    public static Optional<String> firstInvalidEntry(String text) {
        return entries(text).stream().filter(e -> range(e).isEmpty()).findFirst();
    }

    private static List<String> entries(String text) {
        return text.lines()
                .map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith("#") && !l.startsWith("//"))
                .toList();
    }

    private static Optional<IpFilterDat.Range> range(String entry) {
        // A range line has a dash, a level or a PeerGuardian name; its name may hold a slash.
        if (entry.chars().anyMatch(c -> c == '-' || c == ',' || c == ':')) {
            return filterLine(entry);
        }
        int slash = entry.indexOf('/');
        if (slash < 0) {
            return filterLine(entry + " - " + entry);
        }
        String prefix = entry.substring(slash + 1).strip();
        if (prefix.isEmpty()
                || prefix.length() > MAX_PREFIX_DIGITS
                || !prefix.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return Optional.empty();
        }
        int bits = Integer.parseInt(prefix);
        String address = entry.substring(0, slash).strip();
        if (bits > 32 || address.isEmpty()) {
            return Optional.empty();
        }
        // The address is read as ipfilter.dat reads them; JDK and Guava have no CIDR parser.
        return filterLine(address + " - " + address).map(r -> {
            int mask = bits == 0 ? 0 : -1 << (32 - bits);
            int start = r.start() & mask;
            return new IpFilterDat.Range(start, start | ~mask, IpFilterDat.DEFAULT_LEVEL, "");
        });
    }

    // One ipfilter.dat or PeerGuardian line, all of it a range: what follows the addresses is only level and name.
    private static Optional<IpFilterDat.Range> filterLine(String line) {
        List<IpFilterDat.Range> read = IpFilterDat.read(line.getBytes(StandardCharsets.UTF_8), "");
        return read.size() == 1 ? Optional.of(read.getFirst()) : Optional.empty();
    }
}
