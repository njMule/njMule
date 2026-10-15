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

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.base.Ticker;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.njmule.core.security.ConnectionGuard;
import org.njmule.core.security.IpFilter;

@Timeout(60)
class TcpListenerTest {

    // On Linux a closed socket kept its port while its accepting thread still held it, most times in 200.
    private static final int ROUNDS = 50;

    private int portsStillTaken;

    @Test
    void shouldFreeItsPortBeforeCloseReturns() throws Exception {
        whenListenersAreOpenedClosedAndTheirPortsBoundAgain(ROUNDS);
        thenEveryPortWasFree();
    }

    private void whenListenersAreOpenedClosedAndTheirPortsBoundAgain(int rounds) throws Exception {
        ConnectionGuard guard =
                new ConnectionGuard(IpFilter::empty, ConnectionGuard.Limits.DEFAULT, Ticker.systemTicker());
        for (int i = 0; i < rounds; i++) {
            TcpListener listener = TcpListener.open(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), guard, (socket, permit) -> {});
            int port = listener.port();
            // Its thread waits in accept.
            Thread.sleep(5);
            listener.close();
            try (ServerSocket again = new ServerSocket()) {
                again.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            } catch (IOException e) {
                this.portsStillTaken++;
            }
        }
    }

    private void thenEveryPortWasFree() {
        assertThat(this.portsStillTaken).isZero();
    }
}
