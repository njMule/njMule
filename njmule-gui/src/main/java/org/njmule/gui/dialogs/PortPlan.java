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
package org.njmule.gui.dialogs;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.util.Optional;
import java.util.function.IntPredicate;
import java.util.random.RandomGenerator;
import org.njmule.core.api.EmulePorts;

/**
 * The ports the first start proposes: njMule's own, unless an eMule on this computer listens on one of them; then a
 * free one eMule does not use, so that both can run.
 */
record PortPlan(int tcp, int udp) {

    // Registered ports, below the range systems hand out for outgoing connections.
    static final int LOW = 10_000;
    static final int HIGH = 49_151;
    private static final int TRIES = 200;

    static PortPlan of(int tcp, int udp, Optional<EmulePorts> emule, RandomGenerator random) {
        return of(tcp, udp, emule, PortPlan::tcpFree, PortPlan::udpFree, random);
    }

    static PortPlan of(
            int tcp,
            int udp,
            Optional<EmulePorts> emule,
            IntPredicate tcpFree,
            IntPredicate udpFree,
            RandomGenerator random) {
        IntPredicate taken = p -> emule.map(e -> e.uses(p)).orElse(false);
        int newTcp = taken.test(tcp) ? pick(tcpFree, taken, random) : tcp;
        int newUdp = taken.test(udp) ? pick(udpFree, taken.or(p -> p == newTcp), random) : udp;
        return new PortPlan(newTcp, newUdp);
    }

    private static int pick(IntPredicate free, IntPredicate taken, RandomGenerator random) {
        for (int i = 0; i < TRIES; i++) {
            int port = random.nextInt(LOW, HIGH + 1);
            if (!taken.test(port) && free.test(port)) {
                return port;
            }
        }
        // Nothing free found: one eMule does not use, which binding will tell about.
        int port = LOW;
        while (taken.test(port)) {
            port++;
        }
        return port;
    }

    static boolean tcpFree(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            return socket.getLocalPort() == port;
        } catch (IOException e) {
            return false;
        }
    }

    static boolean udpFree(int port) {
        try (DatagramSocket socket = new DatagramSocket(port)) {
            return socket.getLocalPort() == port;
        } catch (IOException e) {
            return false;
        }
    }
}
