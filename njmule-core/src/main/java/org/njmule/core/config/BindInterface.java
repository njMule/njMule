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
package org.njmule.core.config;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The network interface njMule's connections are bound to, as the preferences choose it: any (blank), an interface by
 * its name or display name, or an IPv4 address of this computer. A chosen interface that is not there leaves njMule
 * offline rather than reaching the network another way.
 */
public final class BindInterface {

    /** A network interface of this computer that is up. */
    public record Candidate(String name, String displayName, boolean loopback, List<Inet4Address> addresses) {
        public Candidate {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(displayName, "displayName");
            addresses = List.copyOf(addresses);
        }
    }

    /** What a choice binds to now. */
    public sealed interface Binding {
        /** Every interface. */
        record Any() implements Binding {}

        /** The address of the chosen interface. */
        record Bound(Inet4Address address) implements Binding {
            public Bound {
                Objects.requireNonNull(address, "address");
            }
        }

        /** The chosen interface or address is not there now. */
        record Missing(String choice) implements Binding {
            public Missing {
                Objects.requireNonNull(choice, "choice");
            }
        }
    }

    private static final Pattern FOUR_PARTS = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private BindInterface() {}

    /** The interfaces that are up now, with their IPv4 addresses; none when they cannot be listed. */
    public static List<Candidate> interfaces() {
        List<Candidate> found = new ArrayList<>();
        try {
            for (NetworkInterface nic : NetworkInterface.networkInterfaces().toList()) {
                if (!nic.isUp()) {
                    continue;
                }
                List<Inet4Address> addresses = nic.inetAddresses()
                        .filter(Inet4Address.class::isInstance)
                        .map(Inet4Address.class::cast)
                        .toList();
                found.add(new Candidate(
                        nic.getName(),
                        nic.getDisplayName() == null ? nic.getName() : nic.getDisplayName(),
                        nic.isLoopback(),
                        addresses));
            }
        } catch (SocketException e) {
            return List.of();
        }
        return found;
    }

    /** The interfaces worth choosing: up, not the loopback, with an IPv4 address. */
    public static List<Candidate> choices() {
        return interfaces().stream()
                .filter(c -> !c.loopback() && !c.addresses().isEmpty())
                .toList();
    }

    /** What {@code choice} binds to with the interfaces up now. */
    public static Binding resolve(String choice) {
        return resolve(choice, interfaces());
    }

    /** What {@code choice} binds to among {@code available}. */
    public static Binding resolve(String choice, List<Candidate> available) {
        String wanted = Objects.requireNonNull(choice, "choice").strip();
        if (wanted.isEmpty()) {
            return new Binding.Any();
        }
        if (isAddress(wanted)) {
            Inet4Address address = Inet4Address.ofLiteral(wanted);
            for (Candidate c : available) {
                if (c.addresses().contains(address)) {
                    return new Binding.Bound(address);
                }
            }
            return new Binding.Missing(wanted);
        }
        for (Candidate c : available) {
            if (c.name().equalsIgnoreCase(wanted) || c.displayName().equals(wanted)) {
                for (Inet4Address address : c.addresses()) {
                    // A link-local address is what an interface takes while it waits for its own.
                    if (!address.isLinkLocalAddress()) {
                        return new Binding.Bound(address);
                    }
                }
            }
        }
        return new Binding.Missing(wanted);
    }

    /** Whether {@code choice} reads as an IPv4 address, in four decimal parts, rather than an interface name. */
    public static boolean isAddress(String choice) {
        String text = choice.strip();
        // The JDK also takes shortened forms such as 10.8.0, which nobody means as an address here.
        if (!FOUR_PARTS.matcher(text).matches()) {
            return false;
        }
        try {
            Inet4Address.ofLiteral(text);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
