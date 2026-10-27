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
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.eclipse.jetty.client.HttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.njmule.core.config.BindInterface;

class HttpRouteTest {

    private HttpClient http;
    private Throwable exception;

    @AfterEach
    void stopTheClientAndBindAnywhereAgain() {
        if (this.http != null) {
            HttpRoute.stop(this.http);
        }
        TcpConnector.useBinding(BindInterface.Binding.Any::new);
    }

    @Test
    void shouldLeaveFromTheChosenInterface() {
        givenTheChosenInterface((Inet4Address) InetAddress.getLoopbackAddress());

        whenAClientStarts();

        thenItLeavesFrom(InetAddress.getLoopbackAddress());
    }

    @Test
    void shouldStartNoClientWhileTheChosenInterfaceIsGone() {
        givenTheChosenInterfaceIsGone("wg0");

        whenAClientStarts();

        thenItFailedFor("wg0");
    }

    private void givenTheChosenInterface(Inet4Address address) {
        TcpConnector.useBinding(() -> new BindInterface.Binding.Bound(address));
    }

    private void givenTheChosenInterfaceIsGone(String name) {
        TcpConnector.useBinding(() -> new BindInterface.Binding.Missing(name));
    }

    private void whenAClientStarts() {
        this.exception = catchThrowable(() -> this.http = HttpRoute.start());
    }

    private void thenItLeavesFrom(InetAddress expected) {
        assertThat(this.exception).isNull();
        assertThat(this.http.isStarted()).isTrue();
        assertThat(this.http.getBindAddress())
                .isInstanceOfSatisfying(
                        InetSocketAddress.class,
                        bound -> assertThat(bound.getAddress()).isEqualTo(expected));
    }

    private void thenItFailedFor(String name) {
        assertThat(this.exception).isInstanceOf(IOException.class).hasMessageContaining(name);
        assertThat(this.http).isNull();
    }
}
