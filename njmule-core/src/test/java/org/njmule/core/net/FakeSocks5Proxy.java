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

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.njmule.core.config.ProxySettings;

/**
 * A SOCKS5 proxy on the loopback that grants UDP ASSOCIATE and relays the datagrams both ways, for tests. It may ask
 * for a user name and password, or refuse the command; it notes what it saw.
 */
public final class FakeSocks5Proxy implements AutoCloseable {

    private final ServerSocket server;
    private final String user;
    private final String password;
    private final boolean grants;
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private final List<Socket> controls = new CopyOnWriteArrayList<>();
    private final List<DatagramSocket> relays = new CopyOnWriteArrayList<>();

    private FakeSocks5Proxy(String user, String password, boolean grants) throws IOException {
        this.user = user;
        this.password = password;
        this.grants = grants;
        this.server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().name("fake-socks5").start(this::accept);
    }

    /** A proxy that relays UDP for anyone. */
    public static FakeSocks5Proxy start() throws IOException {
        return new FakeSocks5Proxy("", "", true);
    }

    /** A proxy that relays UDP for {@code user} and {@code password} only. */
    public static FakeSocks5Proxy withLogin(String user, String password) throws IOException {
        return new FakeSocks5Proxy(user, password, true);
    }

    /** A proxy that does not support UDP ASSOCIATE. */
    public static FakeSocks5Proxy refusingUdp() throws IOException {
        return new FakeSocks5Proxy("", "", false);
    }

    /** The settings of this proxy, with {@code user} and {@code password}. */
    public ProxySettings settings(String user, String password) {
        return new ProxySettings(ProxySettings.Type.SOCKS, "127.0.0.1", this.server.getLocalPort(), user, password);
    }

    public ProxySettings settings() {
        return settings("", "");
    }

    /** What it saw: "login user:password", "associate", "relay to 1.2.3.4:5" and "ended". */
    public List<String> seen() {
        return List.copyOf(this.seen);
    }

    /** Ends every association, as a proxy that restarts. */
    public void endAssociations() throws IOException {
        for (Socket control : this.controls) {
            control.close();
        }
    }

    @Override
    public void close() throws IOException {
        this.server.close();
        endAssociations();
        for (DatagramSocket relay : this.relays) {
            relay.close();
        }
    }

    private void accept() {
        while (!this.server.isClosed()) {
            try {
                Socket control = this.server.accept();
                this.controls.add(control);
                Thread.ofVirtual().name("fake-socks5-control").start(() -> serve(control));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket control) {
        try (control) {
            DataInputStream in = new DataInputStream(control.getInputStream());
            OutputStream out = control.getOutputStream();
            in.readUnsignedByte();
            byte[] methods = new byte[in.readUnsignedByte()];
            in.readFully(methods);
            int wanted = this.user.isEmpty() ? 0 : 2;
            boolean offered = false;
            for (byte method : methods) {
                offered |= method == wanted;
            }
            out.write(new byte[] {5, (byte) (offered ? wanted : 0xFF)});
            out.flush();
            if (!offered || (wanted == 2 && !loggedIn(in, out))) {
                return;
            }
            byte[] request = new byte[10];
            in.readFully(request);
            this.seen.add("associate");
            if (!this.grants) {
                out.write(new byte[] {5, 7, 0, 1, 0, 0, 0, 0, 0, 0});
                out.flush();
                return;
            }
            DatagramSocket relay = new DatagramSocket(0, InetAddress.getLoopbackAddress());
            this.relays.add(relay);
            int port = relay.getLocalPort();
            out.write(new byte[] {5, 0, 0, 1, 127, 0, 0, 1, (byte) (port >>> 8), (byte) port});
            out.flush();
            Thread.ofVirtual().name("fake-socks5-relay").start(() -> relay(relay, control.getInetAddress()));
            // The association lasts as long as this connection.
            while (in.read() >= 0) {
                // Nothing comes.
            }
            relay.close();
        } catch (IOException e) {
            // The client or the test closed the connection.
        } finally {
            this.seen.add("ended");
        }
    }

    private boolean loggedIn(DataInputStream in, OutputStream out) throws IOException {
        in.readUnsignedByte();
        String name = new String(in.readNBytes(in.readUnsignedByte()), StandardCharsets.UTF_8);
        String secret = new String(in.readNBytes(in.readUnsignedByte()), StandardCharsets.UTF_8);
        this.seen.add("login " + name + ":" + secret);
        boolean right = name.equals(this.user) && secret.equals(this.password);
        out.write(new byte[] {1, (byte) (right ? 0 : 1)});
        out.flush();
        return right;
    }

    // The client's datagrams carry a header naming their destination; the others go to the client behind one that
    // names their sender.
    private void relay(DatagramSocket relay, InetAddress clientAddress) {
        byte[] buffer = new byte[64 * 1024];
        InetSocketAddress client = null;
        while (!relay.isClosed()) {
            try {
                DatagramPacket in = new DatagramPacket(buffer, buffer.length);
                relay.receive(in);
                InetSocketAddress from = (InetSocketAddress) in.getSocketAddress();
                if ((client == null && from.getAddress().equals(clientAddress)) || from.equals(client)) {
                    client = from;
                    InetSocketAddress to = new InetSocketAddress(
                            InetAddress.getByAddress(Arrays.copyOfRange(buffer, 4, 8)),
                            (buffer[8] & 0xFF) << 8 | (buffer[9] & 0xFF));
                    this.seen.add("relay to " + to.getAddress().getHostAddress() + ":" + to.getPort());
                    relay.send(new DatagramPacket(buffer, 10, in.getLength() - 10, to));
                } else if (client != null) {
                    byte[] wrapped = Socks5Udp.wrap(from, Arrays.copyOf(buffer, in.getLength()));
                    relay.send(new DatagramPacket(wrapped, wrapped.length, client));
                }
            } catch (IOException e) {
                // Closed with the association.
            }
        }
    }
}
