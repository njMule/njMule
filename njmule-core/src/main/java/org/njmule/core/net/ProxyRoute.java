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

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.util.Objects;
import java.util.function.Supplier;
import org.njmule.core.config.ProxySettings;

/**
 * Sends outgoing TCP connections through the proxy of the preferences, with the JDK's own SOCKS and HTTP CONNECT
 * support; the user name and password go to the SOCKS proxy only.
 */
public final class ProxyRoute extends Authenticator {

    // One engine per process: the network sets the proxy, which web requests read too, and takes it back when it
    // closes.
    private static volatile Supplier<ProxySettings> current = () -> ProxySettings.NONE;
    private static final Object INSTALLING = new Object();

    private final Supplier<ProxySettings> settings;

    private ProxyRoute(Supplier<ProxySettings> settings) {
        this.settings = settings;
    }

    /** From now on connections follow {@code settings}, read at each connection. */
    public static void install(Supplier<ProxySettings> settings) {
        Objects.requireNonNull(settings, "settings");
        synchronized (INSTALLING) {
            current = settings;
            TcpConnector.useProxy(() -> proxy(settings.get()));
            Authenticator.setDefault(new ProxyRoute(settings));
        }
    }

    /** Connections go direct again, unless other settings were installed after {@code settings}. */
    @SuppressWarnings("ReferenceEquality")
    public static void uninstall(Supplier<ProxySettings> settings) {
        synchronized (INSTALLING) {
            // The very supplier installed, whatever it gives: a network that closes late leaves a newer one's proxy
            // alone.
            if (current != settings) {
                return;
            }
            current = () -> ProxySettings.NONE;
            TcpConnector.useProxy(() -> Proxy.NO_PROXY);
            Authenticator.setDefault(null);
        }
    }

    /** The proxy of the preferences now. */
    public static ProxySettings current() {
        return current.get();
    }

    /** The JDK proxy for {@code settings}. */
    public static Proxy proxy(ProxySettings settings) {
        return switch (settings.type()) {
            case NONE -> Proxy.NO_PROXY;
            case SOCKS -> new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(settings.host(), settings.port()));
            case HTTP -> new Proxy(Proxy.Type.HTTP, new InetSocketAddress(settings.host(), settings.port()));
        };
    }

    @Override
    protected PasswordAuthentication getPasswordAuthentication() {
        ProxySettings proxy = this.settings.get();
        String protocol = getRequestingProtocol();
        boolean socks = protocol != null && protocol.startsWith("SOCKS");
        if (proxy.type() != ProxySettings.Type.SOCKS
                || !socks
                || proxy.user().isEmpty()
                || !proxy.host().equalsIgnoreCase(getRequestingHost())) {
            return null;
        }
        return new PasswordAuthentication(proxy.user(), proxy.password().toCharArray());
    }
}
