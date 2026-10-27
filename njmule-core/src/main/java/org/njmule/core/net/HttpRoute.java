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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.Socks5;
import org.eclipse.jetty.client.Socks5Proxy;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.thread.VirtualThreadPool;
import org.njmule.core.api.NjMuleInfo;
import org.njmule.core.config.ProxySettings;

/**
 * Jetty HTTP clients that take the route of the other outgoing connections: from the network interface chosen and
 * through the proxy of the preferences, HTTP or SOCKS 5 with its user name and password. They follow no redirect and
 * unpack no body, which are the caller's to check.
 */
public final class HttpRoute {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

    private static final String USER_AGENT = NjMuleInfo.NAME + "/" + NjMuleInfo.version();

    private HttpRoute() {}

    /**
     * A started client on the route chosen now, for the caller to {@linkplain #stop stop}; a client for each use, as
     * the route may have changed since the last one.
     *
     * @throws IOException while the interface chosen is gone, or when the client cannot start
     */
    public static HttpClient start() throws IOException {
        HttpClient http = client(ProxyRoute.current(), TcpConnector.localAddress());
        try {
            http.start();
        } catch (Exception e) {
            stop(http);
            throw new IOException("cannot start the HTTP client", e);
        }
        return http;
    }

    /** Stops {@code http}; a failure to stop leaves the caller's result as it is. */
    public static void stop(HttpClient http) {
        try {
            http.stop();
        } catch (Exception e) {
            // What came, or the failure that matters, is already on its way.
        }
    }

    private static HttpClient client(ProxySettings proxy, InetAddress local) {
        HttpClient http = new HttpClient();
        if (local != null) {
            http.setBindAddress(new InetSocketAddress(local, 0));
        }
        http.setExecutor(new VirtualThreadPool());
        http.setFollowRedirects(false);
        http.setConnectTimeout(CONNECT_TIMEOUT.toMillis());
        http.setUserAgentField(new HttpField(HttpHeader.USER_AGENT, USER_AGENT));
        http.getContentDecoderFactories().clear();
        switch (proxy.type()) {
            case NONE -> {
                // Direct.
            }
            case HTTP -> http.getProxyConfiguration().addProxy(new HttpProxy(proxy.host(), proxy.port()));
            case SOCKS -> {
                Socks5Proxy socks = new Socks5Proxy(proxy.host(), proxy.port());
                if (!proxy.user().isEmpty()) {
                    socks.putAuthenticationFactory(new Socks5.UsernamePasswordAuthenticationFactory(
                            proxy.user(), proxy.password(), StandardCharsets.UTF_8));
                }
                http.getProxyConfiguration().addProxy(socks);
            }
        }
        return http;
    }
}
