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
package org.njmule.core.remote;

import java.io.IOException;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.VirtualThreadPool;

/** The remote-control API on a bare Jetty server of the loopback, served as a front end serves it. */
final class ApiServer implements AutoCloseable {

    private final Server server;
    private final int port;

    private ApiServer(Server server, int port) {
        this.server = server;
        this.port = port;
    }

    static ApiServer start(RemoteApi api) throws Exception {
        Server server = new Server(new VirtualThreadPool());
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("127.0.0.1");
        connector.setPort(0);
        server.addConnector(connector);
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) {
                Exchange exchange = new Exchange(request, response);
                try {
                    api.handle(exchange);
                    exchange.finish();
                    callback.succeeded();
                } catch (IOException | RuntimeException e) {
                    callback.failed(e);
                }
                return true;
            }
        });
        server.start();
        return new ApiServer(server, connector.getLocalPort());
    }

    int port() {
        return this.port;
    }

    @Override
    public void close() throws IOException {
        try {
            this.server.stop();
        } catch (Exception e) {
            throw new IOException("the server did not stop", e);
        }
    }
}
