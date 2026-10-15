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
package org.njmule.core.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.DatagramSocket;
import java.net.SocketException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class ServerUdpTest {

    // Enough rounds to meet the close race, which most rounds lose on Linux when the port is not waited for.
    private static final int ROUNDS = 20;

    private int busy;

    @Test
    void aClosedSocketLeavesItsPortFreeAtOnce() throws Exception {
        whenSocketsAreClosedAndTheirPortsBoundAgain(ROUNDS);
        thenThePortWasBusyTimes(0);
    }

    // Each socket receives (its reader waits on it) when it closes, as when Kad takes the port of the server socket.
    private void whenSocketsAreClosedAndTheirPortsBoundAgain(int rounds) throws Exception {
        for (int i = 0; i < rounds; i++) {
            ServerUdp udp = ServerUdp.open(0, (from, packet) -> {});
            int port = udp.port();
            Thread.sleep(5);
            udp.close();
            try (DatagramSocket again = new DatagramSocket(port)) {
                assertThat(again.getLocalPort()).isEqualTo(port);
            } catch (SocketException e) {
                this.busy++;
            }
        }
    }

    private void thenThePortWasBusyTimes(int times) {
        assertThat(this.busy).isEqualTo(times);
    }
}
