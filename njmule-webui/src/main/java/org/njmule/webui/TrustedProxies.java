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
package org.njmule.webui;

import com.google.common.net.InetAddresses;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.eclipse.jetty.util.InetAddressSet;
import org.njmule.core.remote.Exchange;

/**
 * The reverse proxies trusted to tell who their client is: a request coming from one of them has its client read from
 * its X-Forwarded-For header, right to left past the trusted proxies. A request from anywhere else is its own client,
 * whatever it says, and no proxy is trusted unless named.
 */
final class TrustedProxies implements Exchange.ClientResolver {

    static final TrustedProxies NONE = new TrustedProxies(List.of());

    /** The most addresses an X-Forwarded-For header is read for: what a client adds beyond them is left alone. */
    static final int MAX_HOPS = 32;

    private final InetAddressSet addresses = new InetAddressSet();
    private final boolean any;

    /**
     * @param entries addresses, CIDR blocks or ranges, IPv4 or IPv6, as {@link #entry} keeps them
     * @throws IllegalArgumentException when one is none of those
     */
    TrustedProxies(List<String> entries) {
        for (String entry : entries) {
            this.addresses.add(entry(entry));
        }
        this.any = !entries.isEmpty();
    }

    /**
     * {@code text} as a proxy is named: an address ({@code 172.17.0.1}, {@code ::1}), a CIDR block ({@code 10.0.0.0/8})
     * or a range ({@code 10.0.0.1-10.0.0.9}), without white space.
     *
     * @throws IllegalArgumentException when it is none of those
     */
    static String entry(String text) {
        String entry = Objects.requireNonNull(text, "text").strip();
        // Literal addresses only, which no name lookup takes for a proxy.
        int slash = entry.indexOf('/');
        int dash = entry.indexOf('-');
        boolean shaped;
        if (slash >= 0) {
            String bits = entry.substring(slash + 1);
            shaped = InetAddresses.isInetAddress(entry.substring(0, slash))
                    && !bits.isEmpty()
                    && bits.length() <= 3
                    && bits.chars().allMatch(c -> c >= '0' && c <= '9');
        } else if (dash >= 0) {
            shaped = InetAddresses.isInetAddress(entry.substring(0, dash))
                    && InetAddresses.isInetAddress(entry.substring(dash + 1));
        } else {
            shaped = InetAddresses.isInetAddress(entry);
        }
        if (shaped) {
            try {
                new InetAddressSet().add(entry);
                return entry;
            } catch (IllegalArgumentException e) {
                // Told below.
            }
        }
        throw new IllegalArgumentException("not an address, a block or a range: " + text);
    }

    boolean isEmpty() {
        return !this.any;
    }

    /** The client of a request that came from {@code remote}, with these X-Forwarded-For headers. */
    @Override
    public InetAddress client(InetAddress remote, List<String> forwardedFor) {
        if (!this.any || !this.addresses.test(remote)) {
            return remote;
        }
        List<String> hops = new ArrayList<>();
        for (String header : forwardedFor) {
            for (String hop : header.split(",", -1)) {
                hops.add(hop.strip());
            }
        }
        InetAddress client = remote;
        for (int i = hops.size() - 1; i >= Math.max(0, hops.size() - MAX_HOPS); i--) {
            // Only addresses: a name or a port the proxy did not write ends the trust.
            if (!InetAddresses.isInetAddress(hops.get(i))) {
                return client;
            }
            client = InetAddresses.forString(hops.get(i));
            if (!this.addresses.test(client)) {
                return client;
            }
        }
        return client;
    }
}
