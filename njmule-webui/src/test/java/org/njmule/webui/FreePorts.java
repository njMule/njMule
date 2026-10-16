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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.util.function.IntPredicate;

/**
 * Ports free for a test to bind, below the ranges systems hand out for port 0 (32768 up on Linux, 49152 up on Windows
 * and macOS): a port handed out once may be handed out again, to any program, before the test binds it.
 */
final class FreePorts {

    private static final int FIRST = 20_000;
    private static final int LAST = 32_000;
    private static final int ATTEMPTS = 1_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private FreePorts() {}

    /** A port no TCP socket is bound to now. */
    static int tcp() {
        return find(FreePorts::isFreeForTcp);
    }

    /** A port no UDP socket is bound to now. */
    static int udp() {
        return find(FreePorts::isFreeForUdp);
    }

    private static int find(IntPredicate free) {
        for (int i = 0; i < ATTEMPTS; i++) {
            int port = FIRST + RANDOM.nextInt(LAST - FIRST);
            if (free.test(port)) {
                return port;
            }
        }
        throw new UncheckedIOException(new IOException("No free port from " + FIRST + " to " + LAST));
    }

    private static boolean isFreeForTcp(int port) {
        try {
            new ServerSocket(port).close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isFreeForUdp(int port) {
        try {
            new DatagramSocket(port).close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
