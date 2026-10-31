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
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.VirtualThreadPool;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.remote.Exchange;
import org.njmule.core.remote.RecentEvents;
import org.njmule.core.remote.RemoteApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The remote-control API for the desktop app's window on this computer: on the loopback address only, at a free port,
 * answering only the token made for this run, which the desktop shell that started njMule reads from its output.
 */
public final class ControlEndpoint implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ControlEndpoint.class);
    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom TOKENS = new SecureRandom();
    // The origins of the desktop shell's own pages (Tauri: Windows, then macOS and Linux); no web page has them.
    private static final Set<String> SHELL_ORIGINS =
            Set.of("http://tauri.localhost", "https://tauri.localhost", "tauri://localhost");

    private final Server server;
    private final RecentEvents recent;
    private final RemoteApi api;
    private final String token;
    private final int port;

    private ControlEndpoint(Server server, RecentEvents recent, RemoteApi api, String token, int port) {
        this.server = server;
        this.recent = recent;
        this.api = api;
        this.token = token;
        this.port = port;
    }

    /** Starts the endpoint; {@code exit} stops njMule when the window asks. */
    public static ControlEndpoint start(NjMuleEngine engine, Runnable exit) throws IOException {
        byte[] secret = new byte[TOKEN_BYTES];
        TOKENS.nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        Server server = new Server(new VirtualThreadPool());
        HttpConfiguration http = new HttpConfiguration();
        http.setSendServerVersion(false);
        ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory(http));
        connector.setHost(InetAddress.getLoopbackAddress().getHostAddress());
        connector.setPort(0);
        server.addConnector(connector);
        ErrorHandler errors = new ErrorHandler();
        errors.setShowStacks(false);
        server.setErrorHandler(errors);
        connector.open();
        RecentEvents recent = RecentEvents.follow(engine);
        // Only the shell knows the token, and nobody can guess it: no attempts to count.
        RemoteApi api = new RemoteApi(
                engine,
                recent,
                client -> true,
                Optional.of(exit),
                RemoteApi.HEARTBEAT,
                Optional.of(new SettingsFile(() -> engine.directories().config())));
        ControlEndpoint endpoint = new ControlEndpoint(server, recent, api, token, connector.getLocalPort());
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) {
                Exchange exchange = new Exchange(request, response);
                try {
                    endpoint.handle(exchange);
                    exchange.finish();
                    callback.succeeded();
                } catch (IOException | RuntimeException e) {
                    callback.failed(e);
                }
                return true;
            }
        });
        try {
            server.start();
        } catch (Exception e) {
            endpoint.close();
            throw new IOException("cannot start the control endpoint: " + e.getMessage(), e);
        }
        logger.info("Control endpoint for the desktop app on port {}", endpoint.port);
        return endpoint;
    }

    /** The API's address, as the shell reaches it. */
    public String url() {
        return "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + this.port + RemoteApi.PREFIX;
    }

    /** The token of this run. */
    public String token() {
        return this.token;
    }

    private void handle(Exchange exchange) throws IOException {
        String origin = exchange.header("Origin");
        boolean shell = origin != null && SHELL_ORIGINS.contains(origin);
        if (shell) {
            exchange.setHeader("Access-Control-Allow-Origin", origin);
            exchange.setHeader("Vary", "Origin");
        }
        if (exchange.method().equals("OPTIONS")) {
            if (shell) {
                exchange.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE");
                exchange.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, Accept");
                exchange.setHeader("Access-Control-Max-Age", "600");
            }
            exchange.sendHeaders(shell ? 204 : 403, -1);
            return;
        }
        // A page of any other origin is refused outright, whatever it sends.
        if (origin != null && !shell) {
            exchange.sendHeaders(403, -1);
            return;
        }
        if (!exchange.path().startsWith(RemoteApi.PREFIX + "/")
                && !exchange.path().equals(RemoteApi.PREFIX)) {
            exchange.sendHeaders(404, -1);
            return;
        }
        String header = exchange.header("Authorization");
        String given = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                ? header.substring(7).strip()
                : "";
        if (!MessageDigest.isEqual(
                this.token.getBytes(StandardCharsets.US_ASCII), given.getBytes(StandardCharsets.US_ASCII))) {
            exchange.setHeader("WWW-Authenticate", "Bearer");
            exchange.sendHeaders(401, -1);
            return;
        }
        // The window of this computer: it may change the web interface's settings too.
        this.api.handleLocal(exchange);
    }

    @Override
    public void close() {
        this.recent.close();
        this.api.close();
        try {
            this.server.stop();
        } catch (Exception e) {
            logger.debug("The control endpoint did not stop cleanly: {}", e.toString());
        }
    }
}
