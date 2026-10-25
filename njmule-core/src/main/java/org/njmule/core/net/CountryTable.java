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

import com.google.common.base.Splitter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.njmule.core.util.Ipv4;

/**
 * The country of IPv4 addresses, from DB-IP's "IP to Country Lite" (CC BY 4.0, IP Geolocation by DB-IP): ranges sorted
 * by their first address, those next to each other with the same country joined. Immutable; a lookup is a binary
 * search.
 */
public final class CountryTable {

    /** No country for any address. */
    public static final CountryTable EMPTY = new CountryTable(new int[0], new int[0], new short[0]);

    private static final int FORMAT = 1;
    // Far more than the database holds: a file claiming more is not one of ours.
    private static final int MAX_RANGES = 4_000_000;
    private static final Splitter COMMAS = Splitter.on(',');

    private final int[] starts;
    private final int[] ends;
    // Two lower-case ASCII letters, one byte each.
    private final short[] codes;

    private CountryTable(int[] starts, int[] ends, short[] codes) {
        this.starts = starts;
        this.ends = ends;
        this.codes = codes;
    }

    /**
     * The table of the database's CSV: {@code first,last,country} lines; IPv6 lines and lines without a two-letter
     * country, such as {@code ZZ} for the unknown, are left out.
     *
     * @throws IOException when no line holds an IPv4 range
     */
    public static CountryTable parse(byte[] csv) throws IOException {
        String text = new String(csv, StandardCharsets.US_ASCII);
        Builder builder = new Builder();
        int at = 0;
        while (at < text.length()) {
            int end = text.indexOf('\n', at);
            if (end < 0) {
                end = text.length();
            }
            line(text.substring(at, end).strip(), builder);
            at = end + 1;
        }
        if (builder.size == 0) {
            throw new IOException("no IPv4 range in the country database");
        }
        return builder.build();
    }

    private static void line(String line, Builder builder) {
        List<String> fields = COMMAS.splitToList(line);
        if (fields.size() < 3 || line.indexOf(':') >= 0) {
            return;
        }
        String code = fields.get(2).strip().replace("\"", "").toLowerCase(Locale.ROOT);
        if (code.length() != 2 || code.equals("zz") || !isLetters(code)) {
            return;
        }
        try {
            builder.add(
                    Ipv4.parse(fields.get(0).strip()), Ipv4.parse(fields.get(1).strip()), code);
        } catch (IllegalArgumentException e) {
            // A line that is not a range is left out.
        }
    }

    private static boolean isLetters(String code) {
        return code.chars().allMatch(c -> c >= 'a' && c <= 'z');
    }

    /** The table as {@link #read} takes it back. */
    public byte[] write() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(12 + this.starts.length * 10);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(FORMAT);
            out.writeInt(this.starts.length);
            for (int i = 0; i < this.starts.length; i++) {
                out.writeInt(this.starts[i]);
                out.writeInt(this.ends[i]);
                out.writeShort(this.codes[i]);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot write to memory", e);
        }
        return bytes.toByteArray();
    }

    /** The table {@link #write} wrote. */
    public static CountryTable read(byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (in.readInt() != FORMAT) {
                throw new IOException("not a country table of this version");
            }
            int count = in.readInt();
            if (count < 0 || count > MAX_RANGES || count > (data.length - 8) / 10) {
                throw new IOException("a country table cut short: " + count + " ranges");
            }
            Builder builder = new Builder();
            for (int i = 0; i < count; i++) {
                int start = in.readInt();
                int end = in.readInt();
                short code = in.readShort();
                builder.add(start, end, new String(new char[] {(char) (code >>> 8), (char) (code & 0xFF)}));
            }
            return builder.build();
        }
    }

    /** The ranges the table holds. */
    public int size() {
        return this.starts.length;
    }

    /** The country of {@code ip} ({@code Ipv4} form) as its ISO 3166-1 code in lower case; empty when unknown. */
    public Optional<String> country(int ip) {
        int low = 0;
        int high = this.starts.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (Integer.compareUnsigned(this.starts[middle], ip) <= 0) {
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        if (high < 0 || Integer.compareUnsigned(ip, this.ends[high]) > 0) {
            return Optional.empty();
        }
        short code = this.codes[high];
        return Optional.of(new String(new char[] {(char) (code >>> 8), (char) (code & 0xFF)}));
    }

    /** The country of a client at {@code address}, {@code ip:port} as the API writes it; empty for IPv6. */
    public Optional<String> countryOf(String address) {
        int colon = address.lastIndexOf(':');
        if (address.startsWith("[") || colon < 0 || this.starts.length == 0) {
            return Optional.empty();
        }
        try {
            return country(Ipv4.parse(address.substring(0, colon)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    // Ranges in any order, sorted and joined when built.
    private static final class Builder {
        private int[] starts = new int[1024];
        private int[] ends = new int[1024];
        private short[] codes = new short[1024];
        private int size;

        void add(int start, int end, String code) {
            if (Integer.compareUnsigned(start, end) > 0 || this.size >= MAX_RANGES) {
                return;
            }
            if (this.size == this.starts.length) {
                this.starts = Arrays.copyOf(this.starts, this.size * 2);
                this.ends = Arrays.copyOf(this.ends, this.size * 2);
                this.codes = Arrays.copyOf(this.codes, this.size * 2);
            }
            this.starts[this.size] = start;
            this.ends[this.size] = end;
            this.codes[this.size] = (short) (code.charAt(0) << 8 | code.charAt(1));
            this.size++;
        }

        CountryTable build() {
            Integer[] order = new Integer[this.size];
            for (int i = 0; i < this.size; i++) {
                order[i] = i;
            }
            Arrays.sort(order, (a, b) -> Integer.compareUnsigned(this.starts[a], this.starts[b]));
            int[] s = new int[this.size];
            int[] e = new int[this.size];
            short[] c = new short[this.size];
            int n = 0;
            for (int i : order) {
                boolean next = n > 0 && c[n - 1] == this.codes[i] && e[n - 1] + 1 == this.starts[i];
                if (next) {
                    e[n - 1] = this.ends[i];
                } else if (n == 0 || Integer.compareUnsigned(this.starts[i], e[n - 1]) > 0) {
                    s[n] = this.starts[i];
                    e[n] = this.ends[i];
                    c[n] = this.codes[i];
                    n++;
                }
            }
            return new CountryTable(Arrays.copyOf(s, n), Arrays.copyOf(e, n), Arrays.copyOf(c, n));
        }
    }
}
