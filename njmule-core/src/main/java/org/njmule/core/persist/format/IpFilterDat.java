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
package org.njmule.core.persist.format;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.njmule.core.protocol.io.Ed2kStrings;
import org.njmule.core.util.Ipv4;

/**
 * IP filter lists: {@code ipfilter.dat} lines {@code a.b.c.d - e.f.g.h , level , description}, PeerGuardian text lines
 * {@code description:a.b.c.d-e.f.g.h} and the PeerGuardian binary P2B format ({@code FF FF FF FF 'P2B' <version 1|2>}
 * then {@code <name NUL><start BE><end BE>}). An address is blocked when its range level is below the configured filter
 * level.
 */
public final class IpFilterDat {

    /** Level of ranges that do not give one. */
    public static final int DEFAULT_LEVEL = 100;

    private static final byte[] P2B_MAGIC = {-1, -1, -1, -1, 'P', '2', 'B'};
    private static final int MIN_LINE = 15;
    private static final int MAX_DESCRIPTION = 255;

    /** {@code start} and {@code end} are inclusive, in {@link Ipv4} form. */
    public record Range(int start, int end, int level, String description) {
        public Range {
            Objects.requireNonNull(description, "description");
            if (Integer.compareUnsigned(start, end) > 0) {
                throw new IllegalArgumentException("range start after end");
            }
        }

        public boolean contains(int ip) {
            return Integer.compareUnsigned(ip, this.start) >= 0 && Integer.compareUnsigned(ip, this.end) <= 0;
        }
    }

    private enum Kind {
        UNKNOWN,
        FILTER_DAT,
        PEER_GUARDIAN
    }

    private IpFilterDat() {}

    /**
     * Parses any of the formats, then sorts by start and merges overlapping ranges and adjacent ones with the same
     * level; identical ranges keep the lowest level.
     *
     * @param fileName used only to recognise {@code .p2p} and {@code .prefix} files
     */
    public static List<Range> read(byte[] data, String fileName) {
        List<Range> ranges = startsWith(data, P2B_MAGIC) ? p2b(data) : text(data, kindOf(fileName));
        return merge(ranges);
    }

    /** The ipfilter.dat text form. */
    public static byte[] write(List<Range> ranges) {
        StringBuilder out = new StringBuilder();
        for (Range r : ranges) {
            out.append(String.format(
                            Locale.ROOT,
                            "%-15s - %-15s , %3d , %s",
                            Ipv4.format(r.start()),
                            Ipv4.format(r.end()),
                            r.level(),
                            r.description()))
                    .append('\n');
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static Kind kindOf(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".p2p") || name.equals("guarding.p2p.txt")) {
            return Kind.PEER_GUARDIAN;
        }
        return name.endsWith(".prefix") ? Kind.FILTER_DAT : Kind.UNKNOWN;
    }

    private static List<Range> text(byte[] data, Kind hint) {
        Kind kind = hint;
        List<Range> ranges = new ArrayList<>();
        for (String raw : TextFiles.lines(data)) {
            String line = raw.strip();
            if (line.isEmpty() || line.charAt(0) == '#' || line.charAt(0) == '/' || line.length() < MIN_LINE) {
                continue;
            }
            if (kind == Kind.UNKNOWN) {
                if (line.indexOf('>') >= 0 && line.indexOf('<') >= 0) {
                    line = line.substring(line.lastIndexOf('>') + 1);
                }
                if (rangeAt(line, 0) != null) {
                    kind = Kind.FILTER_DAT;
                } else {
                    int colon = line.indexOf(':');
                    if (colon >= 0 && rangeAt(line, colon + 1) != null) {
                        kind = Kind.PEER_GUARDIAN;
                    }
                }
            }
            Range r = kind == Kind.FILTER_DAT
                    ? filterDatLine(line)
                    : kind == Kind.PEER_GUARDIAN ? peerGuardianLine(line) : null;
            if (r != null) {
                ranges.add(r);
            }
        }
        return ranges;
    }

    private static Range filterDatLine(String line) {
        int[] ips = rangeAt(line, 0);
        if (ips == null) {
            return null;
        }
        int level = DEFAULT_LEVEL;
        String description = "";
        int comma = line.indexOf(',', ips[2]);
        if (comma >= 0) {
            String rest = line.substring(comma + 1);
            int second = rest.indexOf(',');
            String levelText = (second >= 0 ? rest.substring(0, second) : rest).strip();
            if (!levelText.isEmpty() && levelText.chars().allMatch(c -> c >= '0' && c <= '9')) {
                // A level has three digits; lists that write more are read by their first three.
                level = Integer.parseInt(levelText.substring(0, Math.min(3, levelText.length())));
                if (second >= 0) {
                    description = rest.substring(second + 1).strip();
                }
            }
        }
        return range(ips, level, description);
    }

    private static Range peerGuardianLine(String line) {
        int colon = line.lastIndexOf(':');
        if (colon < 0) {
            return null;
        }
        int[] ips = rangeAt(line, colon + 1);
        if (ips == null) {
            return null;
        }
        return range(
                ips,
                DEFAULT_LEVEL,
                line.substring(0, colon).replace("PGIPDB", "").strip());
    }

    private static Range range(int[] ips, int level, String description) {
        if (Integer.compareUnsigned(ips[0], ips[1]) > 0) {
            return null;
        }
        String d = description.length() > MAX_DESCRIPTION ? description.substring(0, MAX_DESCRIPTION) : description;
        return new Range(ips[0], ips[1], level, d);
    }

    /** {@code a.b.c.d - e.f.g.h} at {@code from} (spaces allowed): start, end, end index; or null. */
    private static int[] rangeAt(String line, int from) {
        int[] pos = {skipSpaces(line, from)};
        Integer start = ip(line, pos);
        if (start == null) {
            return null;
        }
        pos[0] = skipSpaces(line, pos[0]);
        if (pos[0] >= line.length() || line.charAt(pos[0]) != '-') {
            return null;
        }
        pos[0] = skipSpaces(line, pos[0] + 1);
        Integer end = ip(line, pos);
        if (end == null) {
            return null;
        }
        return new int[] {start, end, pos[0]};
    }

    private static Integer ip(String line, int[] pos) {
        int value = 0;
        int i = pos[0];
        for (int part = 0; part < 4; part++) {
            if (part > 0) {
                if (i >= line.length() || line.charAt(i) != '.') {
                    return null;
                }
                i++;
            }
            int digits = 0;
            int octet = 0;
            while (i < line.length() && digits < 3 && line.charAt(i) >= '0' && line.charAt(i) <= '9') {
                octet = octet * 10 + (line.charAt(i) - '0');
                i++;
                digits++;
            }
            if (digits == 0 || octet > 255) {
                return null;
            }
            value = value << 8 | octet;
        }
        pos[0] = i;
        return value;
    }

    private static int skipSpaces(String line, int from) {
        int i = from;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    private static List<Range> p2b(byte[] data) {
        List<Range> ranges = new ArrayList<>();
        int pos = P2B_MAGIC.length;
        if (pos >= data.length || (data[pos] != 1 && data[pos] != 2)) {
            return ranges;
        }
        boolean utf8 = data[pos] == 2;
        pos++;
        while (pos < data.length) {
            int nul = pos;
            while (nul < data.length && data[nul] != 0) {
                nul++;
            }
            if (nul + 8 >= data.length) {
                break;
            }
            int nameLength = Math.min(nul - pos, MAX_DESCRIPTION);
            String name = utf8
                    ? Ed2kStrings.decode(data, pos, nameLength)
                    : new String(data, pos, nameLength, StandardCharsets.ISO_8859_1);
            int start = bigEndian(data, nul + 1);
            int end = bigEndian(data, nul + 5);
            if (Integer.compareUnsigned(start, end) <= 0) {
                ranges.add(new Range(start, end, DEFAULT_LEVEL, name));
            }
            pos = nul + 9;
        }
        return ranges;
    }

    private static int bigEndian(byte[] data, int at) {
        return (data[at] & 0xFF) << 24
                | (data[at + 1] & 0xFF) << 16
                | (data[at + 2] & 0xFF) << 8
                | (data[at + 3] & 0xFF);
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static List<Range> merge(List<Range> input) {
        List<Range> sorted = new ArrayList<>(input);
        sorted.sort(Comparator.comparing(Range::start, Integer::compareUnsigned));
        List<Range> out = new ArrayList<>(sorted.size());
        for (Range cur : sorted) {
            if (out.isEmpty()) {
                out.add(cur);
                continue;
            }
            Range prev = out.getLast();
            boolean overlapping = Integer.compareUnsigned(cur.start(), prev.end()) <= 0;
            boolean adjacent = prev.end() != -1 && cur.start() == prev.end() + 1 && cur.level() == prev.level();
            if (overlapping || adjacent) {
                if (cur.start() == prev.start() && cur.end() == prev.end()) {
                    out.set(
                            out.size() - 1,
                            new Range(
                                    prev.start(), prev.end(), Math.min(prev.level(), cur.level()), prev.description()));
                } else if (Integer.compareUnsigned(cur.end(), prev.end()) > 0) {
                    out.set(out.size() - 1, new Range(prev.start(), cur.end(), prev.level(), prev.description()));
                }
            } else {
                out.add(cur);
            }
        }
        return out;
    }
}
