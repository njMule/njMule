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

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.FormFields;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Fields;

/**
 * One request and its answer, as the pages, the API and the event streams handle them, on Jetty's blocking adapters:
 * they run on a virtual thread of their own and may wait.
 */
final class Exchange {

    // The most of a request body left unread that the exchange reads anyway, as Tomcat's maxSwallowSize: past it the
    // connection closes.
    private static final long MAX_DISCARDED = 2 * 1024 * 1024;

    private final Request request;
    private final Response response;
    private RequestBody requestBody;
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

    /**
     * The fields of the query, decoded as UTF-8: a field given twice keeps its last value, one without a value is
     * empty. Bad percent-encoding or UTF-8 throws {@link IllegalArgumentException}.
     */
    Map<String, String> query() {
        try {
            return lastValues(Request.extractQueryParameters(this.request, StandardCharsets.UTF_8));
        } catch (IllegalStateException e) {
            throw new IllegalArgumentException("bad query", e);
        }
    }

    /**
     * The fields of a posted {@code application/x-www-form-urlencoded} form (none for another content type), as
     * {@link #query}; empty when there are more than {@code maxFields} fields or {@code maxBytes} bytes.
     */
    Optional<Map<String, String>> form(int maxFields, int maxBytes) {
        try {
            return Optional.of(lastValues(FormFields.getFields(this.request, maxFields, maxBytes)));
        } catch (IllegalStateException | IllegalArgumentException e) {
            if (e instanceof HttpException http && http.getCode() == 413) {
                return Optional.empty();
            }
            throw new IllegalArgumentException("bad form", e);
        }
    }

    private static Map<String, String> lastValues(Fields fields) {
        Map<String, String> values = new HashMap<>();
        for (Fields.Field field : fields) {
            List<String> given = field.getValues();
            values.put(field.getName(), given.isEmpty() ? "" : given.getLast());
        }
        return values;
    }

    /** The value of the request's cookie {@code name}, if it sent one. */
    Optional<String> cookie(String name) {
        return Request.getCookies(this.request).stream()
                .filter(c -> c.getName().equals(name))
                .map(HttpCookie::getValue)
                .findFirst();
    }

    void addCookie(HttpCookie cookie) {
        Response.addCookie(this.response, cookie);
    }

    /** The first value of a request header, or null. */
    String header(String name) {
        return this.request.getHeaders().get(name);
    }

    InetAddress remoteAddress() {
        SocketAddress remote = this.request.getConnectionMetaData().getRemoteSocketAddress();
        return remote instanceof InetSocketAddress inet ? inet.getAddress() : InetAddress.getLoopbackAddress();
    }

    /** The body of the request; what the handler leaves unread is read when the answer ends. */
    InputStream requestBody() {
        return requestStream();
    }

    private RequestBody requestStream() {
        if (this.requestBody == null) {
            this.requestBody = new RequestBody(Content.Source.asInputStream(this.request));
        }
        return this.requestBody;
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

    // The handler may close it before its end; the exchange closes it for real once it has read the rest.
    private static final class RequestBody extends FilterInputStream {

        RequestBody(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
            // The exchange reads the rest first.
        }

        // At most MAX_DISCARDED bytes: past them the close fails the body and Jetty closes the connection.
        void discardRest() throws IOException {
            try (InputStream rest = this.in) {
                long left = MAX_DISCARDED;
                long skipped;
                do {
                    skipped = rest.skip(left);
                    left -= skipped;
                } while (skipped > 0 && left > 0);
            }
        }
    }

    // The answer ends: what the body still holds goes out, then what the client still sends is read. Closing a
    // connection the client writes to makes TCP reset it, which can drop the answer unread (RFC 9112, 9.6).
    void finish() throws IOException {
        if (this.body != null) {
            this.body.close();
        }
        requestStream().discardRest();
    }
}
