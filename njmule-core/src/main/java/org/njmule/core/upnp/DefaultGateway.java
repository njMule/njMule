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
package org.njmule.core.upnp;

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.net.InetAddresses;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.njmule.core.config.OsFamily;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The default gateways of this computer, where NAT-PMP and PCP are asked: read from the routing table as each system
 * shows it, since the JDK does not tell them. The IPv6 one is mostly a link-local address, written with its interface.
 */
final class DefaultGateway {

    private static final Logger logger = LoggerFactory.getLogger(DefaultGateway.class);
    private static final long COMMAND_SECONDS = 5;
    // The flag of a route through a gateway, in /proc/net/route.
    private static final int GATEWAY_FLAG = 2;
    private static final Splitter FIELDS = Splitter.on(CharMatcher.whitespace()).omitEmptyStrings();

    private DefaultGateway() {}

    /** The default gateway, if the routing table names one. */
    static Optional<InetAddress> find() {
        try {
            return switch (OsFamily.current()) {
                case LINUX ->
                    fromProcNetRoute(Files.readAllLines(Path.of("/proc/net/route"), StandardCharsets.US_ASCII));
                case WINDOWS -> fromRoutePrint(run(windowsRoute(), "print", "-4", "0.0.0.0"));
                case MACOS -> fromRouteGet(run("/sbin/route", "-n", "get", "default"));
            };
        } catch (IOException | RuntimeException e) {
            logger.debug("No default gateway: {}", e.toString());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** The IPv6 default gateway, if the routing table names one. */
    static Optional<InetAddress> findIpv6() {
        try {
            Optional<String> scoped = switch (OsFamily.current()) {
                case LINUX ->
                    fromProcNetIpv6Route(
                            Files.readAllLines(Path.of("/proc/net/ipv6_route"), StandardCharsets.US_ASCII));
                case WINDOWS -> fromRoutePrintIpv6(run(windowsRoute(), "print", "-6", "::/0"));
                case MACOS -> fromRouteGet(run("/sbin/route", "-n", "get", "-inet6", "default"), "gateway:");
            };
            // A literal with its interface: no name is looked up.
            return scoped.isPresent() ? Optional.of(InetAddress.getByName(scoped.get())) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            logger.debug("No IPv6 default gateway: {}", e.toString());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Linux: the route to ::/0 with a next hop, in hex, then its interface last; the lowest metric wins. Returns the
     * next hop with its interface, as "fe80::1%eth0".
     */
    static Optional<String> fromProcNetIpv6Route(List<String> lines) {
        String best = null;
        long bestMetric = Long.MAX_VALUE;
        for (String line : lines) {
            List<String> fields = FIELDS.splitToList(line);
            if (fields.size() < 10
                    || !fields.get(0).equals("0".repeat(32))
                    || !fields.get(1).equals("00")
                    || fields.get(4).equals("0".repeat(32))) {
                continue;
            }
            try {
                long metric = Long.parseLong(fields.get(5), 16);
                byte[] hop = HexFormat.of().parseHex(fields.get(4));
                if (metric < bestMetric) {
                    best = InetAddresses.toAddrString(InetAddress.getByAddress(hop)) + "%" + fields.get(9);
                    bestMetric = metric;
                }
            } catch (IllegalArgumentException | IOException e) {
                // Not a route line.
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Windows: the rows of "route print -6" for ::/0, with the interface index first, the metric second and the gateway
     * fourth; the lowest metric wins. Returns the gateway with the interface index, as "fe80::1%11".
     */
    static Optional<String> fromRoutePrintIpv6(String text) {
        String best = null;
        int bestMetric = Integer.MAX_VALUE;
        for (String line : text.lines().toList()) {
            List<String> fields = FIELDS.splitToList(line);
            if (fields.size() < 4 || !fields.get(2).equals("::/0") || !InetAddresses.isInetAddress(fields.get(3))) {
                continue;
            }
            try {
                int index = Integer.parseInt(fields.get(0));
                int metric = Integer.parseInt(fields.get(1));
                if (metric < bestMetric && !fields.get(3).equals("::")) {
                    best = fields.get(3) + "%" + index;
                    bestMetric = metric;
                }
            } catch (NumberFormatException e) {
                // A heading.
            }
        }
        return Optional.ofNullable(best);
    }

    /** Linux: the route to 0.0.0.0 through a gateway, whose address the table writes in hex, lowest byte first. */
    static Optional<InetAddress> fromProcNetRoute(List<String> lines) {
        for (String line : lines) {
            List<String> fields = FIELDS.splitToList(line);
            if (fields.size() < 4 || !fields.get(1).equals("00000000")) {
                continue;
            }
            try {
                if ((Integer.parseInt(fields.get(3), 16) & GATEWAY_FLAG) == 0) {
                    continue;
                }
                int gateway = Integer.parseUnsignedInt(fields.get(2), 16);
                if (gateway != 0) {
                    return Optional.of(InetAddress.getByAddress(new byte[] {
                        (byte) gateway, (byte) (gateway >>> 8), (byte) (gateway >>> 16), (byte) (gateway >>> 24)
                    }));
                }
            } catch (NumberFormatException | IOException e) {
                // Not a route line.
            }
        }
        return Optional.empty();
    }

    /**
     * Windows: the rows of "route print" for destination and mask 0.0.0.0, the gateway third and the metric fifth; the
     * lowest metric wins. The headings are in the system's language, the rows are not.
     */
    static Optional<InetAddress> fromRoutePrint(String text) {
        InetAddress best = null;
        int bestMetric = Integer.MAX_VALUE;
        for (String line : text.lines().toList()) {
            List<String> fields = FIELDS.splitToList(line);
            if (fields.size() < 5
                    || !fields.get(0).equals("0.0.0.0")
                    || !fields.get(1).equals("0.0.0.0")) {
                continue;
            }
            Optional<InetAddress> gateway = ipv4(fields.get(2));
            try {
                int metric = Integer.parseInt(fields.get(4));
                if (gateway.isPresent() && metric < bestMetric) {
                    best = gateway.get();
                    bestMetric = metric;
                }
            } catch (NumberFormatException e) {
                // A persistent route without a metric of its own.
            }
        }
        return Optional.ofNullable(best);
    }

    /** macOS: the "gateway:" line of "route -n get default". */
    static Optional<InetAddress> fromRouteGet(String text) {
        return fromRouteGet(text, "gateway:").flatMap(DefaultGateway::ipv4);
    }

    // The value of the line of "route -n get" that starts with {@code key}; an IPv6 one keeps its "%interface".
    static Optional<String> fromRouteGet(String text, String key) {
        for (String line : text.lines().toList()) {
            String trimmed = line.strip();
            if (trimmed.startsWith(key)) {
                return Optional.of(trimmed.substring(key.length()).strip());
            }
        }
        return Optional.empty();
    }

    private static Optional<InetAddress> ipv4(String text) {
        try {
            return Optional.of(Inet4Address.ofLiteral(text));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    // The system's own route.exe, never one found first on the path.
    private static String windowsRoute() {
        String root = System.getenv("SystemRoot");
        return root == null ? "route" : Path.of(root, "System32", "ROUTE.EXE").toString();
    }

    private static String run(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            byte[] output = process.getInputStream().readAllBytes();
            if (!process.waitFor(COMMAND_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException(command[0] + " did not end");
            }
            return new String(output, StandardCharsets.US_ASCII);
        } finally {
            process.destroy();
        }
    }
}
