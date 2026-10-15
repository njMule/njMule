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

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.njmule.core.config.ProxySettings;

@Timeout(30)
class ProxyRouteTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final List<String> seen = new CopyOnWriteArrayList<>();
    private final List<AutoCloseable> resources = new CopyOnWriteArrayList<>();

    private ServerSocket target;
    private ServerSocket proxy;
    private ProxySettings settings;
    private Socket accepted;
    private Proxy chosen;
    private boolean enabled;
    private RuntimeException exception;

    @AfterEach
    void reset() throws Exception {
        ProxyRoute.install(() -> ProxySettings.NONE);
        Authenticator.setDefault(null);
        for (AutoCloseable r : this.resources) {
            r.close();
        }
    }

    @Test
    void shouldConnectThroughASocksProxyWithItsCredentials() throws IOException {
        givenATarget();
        givenASocksProxy(true);
        givenTheProxyIsUsedWith(ProxySettings.Type.SOCKS, "me", "secret");

        whenAConnectionIsMadeToTheTarget();

        thenTheTargetAcceptedTheConnection();
        thenTheProxyAuthenticatedAndConnectedToTheTarget("me", "secret");
        thenTheSettingsDoNotShowThePassword("secret");
    }

    @Test
    void shouldConnectThroughASocksProxyThatNeedsNoCredentials() throws IOException {
        givenATarget();
        givenASocksProxy(false);
        givenTheProxyIsUsedWith(ProxySettings.Type.SOCKS, "", "");

        whenAConnectionIsMadeToTheTarget();

        thenTheTargetAcceptedTheConnection();
        thenTheProxyOnlyConnectedToTheTargetWith("connect");
    }

    @Test
    void shouldConnectThroughAnHttpProxy() throws IOException {
        givenATarget();
        givenAnHttpProxy();
        givenTheProxyIsUsedWith(ProxySettings.Type.HTTP, "", "");

        whenAConnectionIsMadeToTheTarget();

        thenTheTargetAcceptedTheConnection();
        thenTheProxyOnlyConnectedToTheTargetWith("CONNECT");
    }

    @Test
    void shouldGoDirectWithoutProxySettings() {
        whenTheProxyIsChosenFor(ProxySettings.NONE);

        thenTheConnectionsGoDirect();
    }

    @Test
    void shouldTellThatNoProxySettingsAreDisabled() {
        whenItIsAskedWhetherTheyAreEnabled(ProxySettings.NONE);

        thenTheSettingsAreDisabled();
    }

    @Test
    void shouldRefuseABlankHost() {
        whenTheSettingsAreMade(ProxySettings.Type.SOCKS, " ", 1080);

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldRefusePortZero() {
        whenTheSettingsAreMade(ProxySettings.Type.HTTP, "h", 0);

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    private void givenATarget() throws IOException {
        this.target = listen();
    }

    private void givenASocksProxy(boolean wantsPassword) throws IOException {
        this.proxy = socks5(wantsPassword);
    }

    private void givenAnHttpProxy() throws IOException {
        this.proxy = httpConnect();
    }

    private void givenTheProxyIsUsedWith(ProxySettings.Type type, String user, String password) {
        ProxySettings used = new ProxySettings(type, "127.0.0.1", this.proxy.getLocalPort(), user, password);
        this.settings = used;
        ProxyRoute.install(() -> used);
    }

    private void whenAConnectionIsMadeToTheTarget() throws IOException {
        TcpConnection connection = connect(this.target);
        try (Socket socket = this.target.accept()) {
            this.accepted = socket;
        }
        connection.close();
    }

    private void whenTheProxyIsChosenFor(ProxySettings proxySettings) {
        this.chosen = ProxyRoute.proxy(proxySettings);
    }

    private void whenItIsAskedWhetherTheyAreEnabled(ProxySettings proxySettings) {
        this.enabled = proxySettings.isEnabled();
    }

    private void whenTheSettingsAreMade(ProxySettings.Type type, String host, int port) {
        try {
            this.settings = new ProxySettings(type, host, port, "", "");
        } catch (RuntimeException e) {
            this.exception = e;
        }
    }

    private void thenTheTargetAcceptedTheConnection() {
        assertThat(this.accepted).isNotNull();
    }

    private void thenTheProxyAuthenticatedAndConnectedToTheTarget(String user, String password) {
        assertThat(this.seen)
                .containsExactly("auth " + user + ":" + password, "connect 127.0.0.1:" + this.target.getLocalPort());
    }

    private void thenTheProxyOnlyConnectedToTheTargetWith(String request) {
        assertThat(this.seen).containsExactly(request + " 127.0.0.1:" + this.target.getLocalPort());
    }

    private void thenTheSettingsDoNotShowThePassword(String password) {
        assertThat(this.settings.toString()).doesNotContain(password).contains("password=***");
    }

    private void thenTheConnectionsGoDirect() {
        assertThat(this.chosen).isEqualTo(Proxy.NO_PROXY);
    }

    private void thenTheSettingsAreDisabled() {
        assertThat(this.enabled).isFalse();
    }

    private void thenExceptionOccurred(Class<? extends RuntimeException> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private TcpConnection connect(ServerSocket server) throws IOException {
        return TcpConnector.connectToClient(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort()),
                Optional.empty(),
                TIMEOUT,
                new SecureRandom());
    }

    private ServerSocket listen() throws IOException {
        ServerSocket socket = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        socket.setSoTimeout((int) TIMEOUT.toMillis());
        this.resources.add(socket);
        return socket;
    }

    // A SOCKS 5 proxy (RFC 1928, user name and password per RFC 1929) that relays one connection.
    private ServerSocket socks5(boolean wantsPassword) throws IOException {
        ServerSocket server = listen();
        Thread.ofVirtual().start(() -> {
            try (Socket client = server.accept()) {
                DataInputStream in = new DataInputStream(client.getInputStream());
                OutputStream out = client.getOutputStream();
                in.readUnsignedByte();
                in.readFully(new byte[in.readUnsignedByte()]);
                out.write(new byte[] {5, (byte) (wantsPassword ? 2 : 0)});
                if (wantsPassword) {
                    in.readUnsignedByte();
                    String user = new String(in.readNBytes(in.readUnsignedByte()), StandardCharsets.US_ASCII);
                    String password = new String(in.readNBytes(in.readUnsignedByte()), StandardCharsets.US_ASCII);
                    this.seen.add("auth " + user + ":" + password);
                    out.write(new byte[] {1, 0});
                }
                in.readFully(new byte[3]);
                int kind = in.readUnsignedByte();
                byte[] address = new byte[kind == 1 ? 4 : 16];
                in.readFully(address);
                int port = in.readUnsignedShort();
                this.seen.add("connect " + InetAddress.getByAddress(address).getHostAddress() + ":" + port);
                Socket upstream = new Socket(InetAddress.getByAddress(address), port);
                this.resources.add(upstream);
                out.write(new byte[] {5, 0, 0, 1, 127, 0, 0, 1, 0, 0});
                out.flush();
                relay(client.getInputStream(), upstream.getOutputStream());
            } catch (IOException e) {
                // The test closes everything at the end.
            }
        });
        return server;
    }

    // An HTTP proxy that accepts one CONNECT.
    private ServerSocket httpConnect() throws IOException {
        ServerSocket server = listen();
        Thread.ofVirtual().start(() -> {
            try (Socket client = server.accept()) {
                InputStream in = client.getInputStream();
                StringBuilder head = new StringBuilder();
                while (!head.toString().endsWith("\r\n\r\n")) {
                    int c = in.read();
                    if (c < 0) {
                        return;
                    }
                    head.append((char) c);
                }
                String[] request = head.toString().split(" ", -1);
                this.seen.add(request[0] + " " + request[1]);
                String[] hostPort = request[1].split(":", -1);
                Socket upstream = new Socket(hostPort[0], Integer.parseInt(hostPort[1]));
                this.resources.add(upstream);
                client.getOutputStream()
                        .write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();
                relay(in, upstream.getOutputStream());
            } catch (IOException e) {
                // The test closes everything at the end.
            }
        });
        return server;
    }

    private static void relay(InputStream from, OutputStream to) throws IOException {
        byte[] buffer = new byte[4096];
        for (int n; (n = from.read(buffer)) >= 0; ) {
            to.write(buffer, 0, n);
        }
    }
}
