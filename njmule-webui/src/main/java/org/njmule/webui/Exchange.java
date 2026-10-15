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

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;

/**
 * One request and its answer, as the pages, the API and the event streams handle them, on Jetty's blocking adapters:
 * they run on a virtual thread of their own and may wait.
 */
final class Exchange {

    private final Request request;
    private final Response response;
    private OutputStream body;
    private boolean started;

    Exchange(Request request, Response response) {
        this.request = request;
        this.response = response;
    }

    String method() {
        return this.request.getMethod();
    }

    boolean isHead() {
        return method().equals("HEAD");
    }

    /** The path, decoded. */
    String path() {
        return this.request.getHttpURI().getDecodedPath();
    }

    /** The path as sent, still percent-encoded. */
    String rawPath() {
        return this.request.getHttpURI().getPath();
    }

    /** The query as sent, or null. */
    String rawQuery() {
        return this.request.getHttpURI().getQuery();
    }

    /** The first value of a request header, or null. */
    String header(String name) {
        return this.request.getHeaders().get(name);
    }

    List<String> headers(String name) {
        return this.request.getHeaders().getValuesList(name);
    }

    InetAddress remoteAddress() {
        SocketAddress remote = this.request.getConnectionMetaData().getRemoteSocketAddress();
        return remote instanceof InetSocketAddress inet ? inet.getAddress() : InetAddress.getLoopbackAddress();
    }

    InputStream requestBody() {
        return Content.Source.asInputStream(this.request);
    }

    void setHeader(String name, String value) {
        this.response.getHeaders().put(name, value);
    }

    void addHeader(String name, String value) {
        this.response.getHeaders().add(name, value);
    }

    /** Whether an answer has started. */
    boolean started() {
        return this.started;
    }

    /** Starts the answer: {@code length} is that of the body, 0 when not known ahead (it is streamed), -1 for none. */
    void sendHeaders(int status, long length) {
        this.started = true;
        this.response.setStatus(status);
        if (length > 0) {
            this.response.getHeaders().put(HttpHeader.CONTENT_LENGTH, length);
        } else if (length < 0 && !isHead()) {
            this.response.getHeaders().put(HttpHeader.CONTENT_LENGTH, 0);
        }
    }

    /** The body of the answer; a write sends the headers first, a flush sends what was written. */
    OutputStream responseBody() {
        if (this.body == null) {
            this.body = new Body(Content.Sink.asOutputStream(this.response));
        }
        return this.body;
    }

    // Closed by the handler and again when the answer ends: the last write goes once.
    private static final class Body extends FilterOutputStream {
        private boolean closed;

        Body(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            this.out.write(bytes, offset, length);
        }

        @Override
        public void close() throws IOException {
            if (!this.closed) {
                this.closed = true;
                this.out.close();
            }
        }
    }

    // The answer ends: what the body still holds goes out.
    void finish() throws IOException {
        if (this.body != null) {
            this.body.close();
        }
    }
}
