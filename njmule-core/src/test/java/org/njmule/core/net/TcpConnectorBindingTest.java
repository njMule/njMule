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

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.njmule.core.config.BindInterface;

/** Outgoing connections leave from the network interface of the preferences, and none while it is gone. */
@Timeout(30)
class TcpConnectorBindingTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private ServerSocket peer;
    private Socket accepted;
    private Exception failure;

    @AfterEach
    void closeAndBindAnywhereAgain() throws IOException {
        TcpConnector.useBinding(BindInterface.Binding.Any::new);
        if (this.accepted != null) {
            this.accepted.close();
        }
        if (this.peer != null) {
            this.peer.close();
        }
    }

    @Test
    void shouldLeaveFromTheAddressOfTheChosenInterface() throws Exception {
        givenAPeerListening();
        givenConnectionsBoundTo(new BindInterface.Binding.Bound((Inet4Address) InetAddress.getLoopbackAddress()));
        whenThePeerIsCalled();
        thenThePeerSawUsFrom(InetAddress.getLoopbackAddress());
    }

    @Test
    void shouldNotConnectAtAllWhileTheChosenInterfaceIsGone() throws Exception {
        givenAPeerListening();
        givenConnectionsBoundTo(new BindInterface.Binding.Missing("wg0"));
        whenThePeerIsCalled();
        thenTheCallFailedForTheMissingInterface("wg0");
    }

    private void givenAPeerListening() throws IOException {
        this.peer = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        this.peer.setSoTimeout((int) TIMEOUT.toMillis());
    }

    private static void givenConnectionsBoundTo(BindInterface.Binding binding) {
        TcpConnector.useBinding(() -> binding);
    }

    private void whenThePeerIsCalled() throws IOException {
        try {
            TcpConnection connection = TcpConnector.connectToClient(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), this.peer.getLocalPort()),
                    Optional.empty(),
                    TIMEOUT,
                    new SecureRandom());
            this.accepted = this.peer.accept();
            connection.close();
        } catch (IOException e) {
            this.failure = e;
        }
    }

    private void thenThePeerSawUsFrom(InetAddress address) {
        assertThat(this.failure).isNull();
        assertThat(this.accepted.getInetAddress()).isEqualTo(address);
    }

    private void thenTheCallFailedForTheMissingInterface(String name) {
        assertThat(this.failure).isInstanceOf(IOException.class).hasMessageContaining(name);
        assertThat(this.accepted).isNull();
    }
}
