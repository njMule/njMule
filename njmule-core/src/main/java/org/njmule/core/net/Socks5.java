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
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import org.njmule.core.config.ProxySettings;

/**
 * The SOCKS5 handshake (RFC 1928, logging in as RFC 1929 says) that TCP connections and UDP associations share. A login
 * is offered only when the settings have a user name: a proxy that prefers one, given the choice, would otherwise
 * refuse us, as it does the JDK's own client, which offers it always.
 */
public final class Socks5 {

    /** The proxy will not do what we ask: it speaks no SOCKS5, wants another login, or refuses the command. */
    public static class Unsupported extends IOException {
        private static final long serialVersionUID = 1L;

        Unsupported(String message) {
            super(message);
        }
    }

    /** The proxy answered in another SOCKS version, or in none. */
    public static final class NotVersion5 extends Unsupported {
        private static final long serialVersionUID = 1L;

        NotVersion5() {
            super("the proxy speaks no SOCKS5");
        }
    }

    static final int CONNECT = 1;
    static final int UDP_ASSOCIATE = 3;
    static final int IPV4 = 1;

    private static final int VERSION = 5;
    private static final int NO_LOGIN = 0;
    private static final int USER_AND_PASSWORD = 2;
    private static final int LOGIN_VERSION = 1;
    private static final int DOMAIN = 3;
    private static final int IPV6 = 4;
    private static final int MAX_NAME = 255;
    // The replies that say the proxy will never relay UDP for us: not allowed by its rules, command not supported.
    private static final int NOT_ALLOWED = 2;
    private static final int COMMAND_NOT_SUPPORTED = 7;
    private static final String[] REPLIES = {
        "succeeded",
        "general failure",
        "not allowed by its rules",
        "network unreachable",
        "host unreachable",
        "connection refused",
        "TTL expired",
        "command not supported",
        "address type not supported"
    };

    private Socks5() {}

    /**
     * A TCP connection to the SOCKS proxy of {@code proxy}, from {@code local} (null: any address), logged in; each
     * answer is waited for {@code millis} at most.
     */
    static Socket open(ProxySettings proxy, InetAddress local, int millis) throws IOException {
        if (proxy.type() != ProxySettings.Type.SOCKS) {
            throw new Unsupported("not a SOCKS proxy");
        }
        InetSocketAddress at = new InetSocketAddress(proxy.host(), proxy.port());
        if (at.isUnresolved()) {
            throw new UnknownHostException(proxy.host());
        }
        Socket socket = new Socket(Proxy.NO_PROXY);
        try {
            if (local != null) {
                socket.bind(new InetSocketAddress(local, 0));
            }
            socket.connect(at, millis);
            socket.setSoTimeout(millis);
            logIn(socket, proxy);
            return socket;
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    // Unbuffered: after the answer to CONNECT the stream is the connection's own.
    private static void logIn(Socket socket, ProxySettings proxy) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        OutputStream out = socket.getOutputStream();
        boolean login = !proxy.user().isEmpty();
        out.write(login ? new byte[] {VERSION, 2, NO_LOGIN, USER_AND_PASSWORD} : new byte[] {VERSION, 1, NO_LOGIN});
        out.flush();
        int version = in.readUnsignedByte();
        int method = in.readUnsignedByte();
        if (version != VERSION) {
            throw new NotVersion5();
        }
        if (method == NO_LOGIN) {
            return;
        }
        if (method != USER_AND_PASSWORD || !login) {
            throw new Unsupported(
                    method == USER_AND_PASSWORD
                            ? "the proxy wants a user name and password"
                            : "the proxy takes none of our ways to log in");
        }
        byte[] user = proxy.user().getBytes(StandardCharsets.UTF_8);
        byte[] password = proxy.password().getBytes(StandardCharsets.UTF_8);
        if (user.length > MAX_NAME || password.length > MAX_NAME) {
            throw new Unsupported("the user name or password is too long for SOCKS5");
        }
        out.write(LOGIN_VERSION);
        out.write(user.length);
        out.write(user);
        out.write(password.length);
        out.write(password);
        out.flush();
        in.readUnsignedByte();
        if (in.readUnsignedByte() != 0) {
            throw new Unsupported("the proxy refused the user name or password");
        }
    }

    /**
     * Asks the proxy, over {@code socket} that {@link #open} logged in, for {@code command} on {@code target}; returns
     * the address the proxy bound, unresolved when it gave a name.
     */
    static InetSocketAddress request(Socket socket, int command, InetSocketAddress target) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        OutputStream out = socket.getOutputStream();
        out.write(new byte[] {VERSION, (byte) command, 0});
        if (target.isUnresolved()) {
            byte[] name = target.getHostString().getBytes(StandardCharsets.US_ASCII);
            if (name.length > MAX_NAME) {
                throw new IOException("host name too long for SOCKS5: " + target.getHostString());
            }
            out.write(DOMAIN);
            out.write(name.length);
            out.write(name);
        } else {
            InetAddress address = target.getAddress();
            out.write(address instanceof Inet6Address ? IPV6 : IPV4);
            out.write(address.getAddress());
        }
        out.write(target.getPort() >>> 8);
        out.write(target.getPort());
        out.flush();
        if (in.readUnsignedByte() != VERSION) {
            throw new NotVersion5();
        }
        int status = in.readUnsignedByte();
        in.readUnsignedByte();
        int type = in.readUnsignedByte();
        if (status != 0) {
            String reason = status < REPLIES.length ? REPLIES[status] : "reply " + status;
            if (command == UDP_ASSOCIATE && (status == NOT_ALLOWED || status == COMMAND_NOT_SUPPORTED)) {
                throw new Unsupported("the proxy relays no UDP (" + reason + ")");
            }
            throw new IOException("the proxy could not reach " + target + ": " + reason);
        }
        return switch (type) {
            case IPV4 -> new InetSocketAddress(InetAddress.getByAddress(read(in, 4)), in.readUnsignedShort());
            case IPV6 -> new InetSocketAddress(InetAddress.getByAddress(read(in, 16)), in.readUnsignedShort());
            case DOMAIN -> {
                String name = new String(read(in, in.readUnsignedByte()), StandardCharsets.US_ASCII);
                yield InetSocketAddress.createUnresolved(name, in.readUnsignedShort());
            }
            default -> throw new IOException("unknown address type " + type + " in a SOCKS5 reply");
        };
    }

    /** Whether {@code address} is one a UDP relay can be reached at as given. */
    static boolean usable(InetSocketAddress address) {
        return !address.isUnresolved()
                && address.getAddress() instanceof Inet4Address
                && !address.getAddress().isAnyLocalAddress();
    }

    private static byte[] read(DataInputStream in, int count) throws IOException {
        byte[] bytes = new byte[count];
        in.readFully(bytes);
        return bytes;
    }
}
