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

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.jetty.client.BytesRequestContent;
import org.eclipse.jetty.client.CompletableResponseListener;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.InputStreamResponseListener;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.thread.VirtualThreadPool;
import org.njmule.core.remote.ApiToken;
import org.njmule.core.remote.RemoteApi;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonEncoding;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;

/**
 * A caller of the remote-control API, for the command line: answers come back as maps, lists, strings, numbers,
 * booleans and nulls. Each call has an HTTP client of its own, as the command line makes only a few.
 */
public final class ApiClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_ANSWER = 64 * 1024 * 1024;

    private static final JsonFactory FACTORY = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxDocumentLength(MAX_ANSWER)
                    .maxNestingDepth(16)
                    .build())
            .build();

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    // A stream sends a comment at each heartbeat: one silent for several is gone.
    private static final Duration STREAM_IDLE = RemoteApi.HEARTBEAT.multipliedBy(4);

    private final URI base;
    private final String token;

    /** @param base the address of the API, as {@code http://127.0.0.1:4711/api/v1} */
    public ApiClient(URI base, String token) {
        this.base = base;
        this.token = token;
    }

    /** The API of the njMule using {@code configDir} on this computer, when its settings let it answer. */
    public static Optional<ApiClient> local(Path configDir) throws IOException {
        WebUiSettings settings = WebUiSettings.load(configDir);
        Optional<String> token = ApiToken.load(configDir);
        if (!settings.usable() || !settings.api() || token.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                new ApiClient(URI.create("http://127.0.0.1:" + settings.port() + RemoteApi.PREFIX), token.get()));
    }

    /** An answer other than a success; the message is the API's. */
    public static final class Failure extends IOException {
        private static final long serialVersionUID = 1L;

        private final int status;

        Failure(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return this.status;
        }
    }

    public Object get(String path) throws IOException, InterruptedException {
        return call("GET", path, null);
    }

    public Object post(String path, Map<String, ?> body) throws IOException, InterruptedException {
        return call("POST", path, body);
    }

    public Object put(String path, Map<String, ?> body) throws IOException, InterruptedException {
        return call("PUT", path, body);
    }

    public Object delete(String path) throws IOException, InterruptedException {
        return call("DELETE", path, null);
    }

    /**
     * Follows the event stream, handing each event's name and data to {@code each} until it returns false or the stream
     * ends.
     */
    // The abort is not waited for: the client stops right after.
    @SuppressWarnings("FutureReturnValueIgnored")
    public void events(String types, EventSink each) throws IOException, InterruptedException {
        HttpClient http = start();
        try {
            // No deadline for the whole answer: the stream lasts until either side ends it.
            Request request = request(http, "/events?types=" + URLEncoder.encode(types, StandardCharsets.UTF_8))
                    .idleTimeout(STREAM_IDLE.toMillis(), TimeUnit.MILLISECONDS);
            InputStreamResponseListener listener = new InputStreamResponseListener();
            request.send(listener);
            Response answer;
            try {
                answer = listener.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                request.abort(e);
                throw new IOException("No answer from " + this.base.getHost() + " in time.", e);
            } catch (ExecutionException e) {
                throw failure(e.getCause());
            }
            try (InputStream body = listener.getInputStream();
                    BufferedReader in = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                if (answer.getStatus() != 200) {
                    throw failure(answer.getStatus(), body.readNBytes(64 * 1024));
                }
                String name = "message";
                for (String line = in.readLine(); line != null; line = in.readLine()) {
                    if (line.startsWith("event: ")) {
                        name = line.substring(7);
                    } else if (line.startsWith("data: ")) {
                        if (!each.event(name, parse(line.substring(6).getBytes(StandardCharsets.UTF_8)))) {
                            return;
                        }
                        name = "message";
                    }
                }
            }
        } finally {
            stop(http);
        }
    }

    /** Takes the events of a stream. */
    @FunctionalInterface
    public interface EventSink {
        /** Takes one event; returns whether to go on. */
        boolean event(String name, Object data) throws IOException;
    }

    private Object call(String method, String path, Map<String, ?> body) throws IOException, InterruptedException {
        HttpClient http = start();
        try {
            Request request = request(http, path).method(method).timeout(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (body != null) {
                request.body(new BytesRequestContent("application/json; charset=utf-8", write(body)));
            }
            CompletableFuture<ContentResponse> sent = new CompletableResponseListener(request, MAX_ANSWER).send();
            ContentResponse answer;
            try {
                answer = sent.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                sent.cancel(true);
                throw e;
            } catch (TimeoutException e) {
                sent.cancel(true);
                throw new IOException("No answer from " + this.base.getHost() + " in time.", e);
            } catch (ExecutionException e) {
                throw failure(e.getCause());
            }
            if (answer.getStatus() / 100 != 2) {
                throw failure(answer.getStatus(), answer.getContent());
            }
            return parse(answer.getContent());
        } finally {
            stop(http);
        }
    }

    private Request request(HttpClient http, String path) {
        return http.newRequest(URI.create(this.base + path))
                .headers(h -> h.put(HttpHeader.AUTHORIZATION, "Bearer " + this.token));
    }

    // Our own njMule, on this computer or at an address given: never through a proxy, no redirects.
    private static HttpClient start() throws IOException {
        HttpClient http = new HttpClient();
        http.setExecutor(new VirtualThreadPool());
        http.setConnectTimeout(CONNECT_TIMEOUT.toMillis());
        http.setFollowRedirects(false);
        try {
            http.start();
        } catch (Exception e) {
            stop(http);
            throw new IOException("Cannot start the HTTP client.", e);
        }
        return http;
    }

    private static void stop(HttpClient http) {
        try {
            http.stop();
        } catch (Exception e) {
            // The answer came, or the failure that matters is already on its way.
        }
    }

    // A failed exchange: the buffer's refusal of a larger answer, a timeout, or the cause itself.
    private static IOException failure(Throwable cause) {
        if (cause instanceof IllegalArgumentException
                && String.valueOf(cause.getMessage()).contains("capacity")) {
            return new IOException("The answer is too large.", cause);
        }
        if (cause instanceof TimeoutException) {
            return new IOException("No answer in time.", cause);
        }
        return cause instanceof IOException io ? io : new IOException(cause.getMessage(), cause);
    }

    private static Failure failure(int status, byte[] body) {
        String message = "HTTP " + status;
        try {
            if (parse(body) instanceof Map<?, ?> fields && fields.get("error") instanceof String text) {
                message = text;
            }
        } catch (IOException e) {
            // Not ours: the status says enough.
        }
        return new Failure(status, message);
    }

    /** The value of a JSON text. */
    static Object parse(byte[] text) throws IOException {
        try (JsonParser p = FACTORY.createParser(ObjectReadContext.empty(), text)) {
            JsonToken first = p.nextToken();
            if (first == null) {
                return null;
            }
            return value(p, first);
        } catch (JacksonException e) {
            throw new IOException("Malformed answer: " + e.getOriginalMessage(), e);
        }
    }

    private static Object value(JsonParser p, JsonToken t) {
        return switch (t) {
            case START_OBJECT -> {
                Map<String, Object> fields = new LinkedHashMap<>();
                for (JsonToken n = p.nextToken(); n != JsonToken.END_OBJECT; n = p.nextToken()) {
                    String name = p.currentName();
                    fields.put(name, value(p, p.nextToken()));
                }
                yield fields;
            }
            case START_ARRAY -> {
                List<Object> items = new ArrayList<>();
                for (JsonToken n = p.nextToken(); n != JsonToken.END_ARRAY; n = p.nextToken()) {
                    items.add(value(p, n));
                }
                yield items;
            }
            case VALUE_STRING -> p.getString();
            case VALUE_NUMBER_INT -> p.getLongValue();
            case VALUE_NUMBER_FLOAT -> p.getDoubleValue();
            case VALUE_TRUE -> true;
            case VALUE_FALSE -> false;
            default -> null;
        };
    }

    private static byte[] write(Map<String, ?> body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator g = FACTORY.createGenerator(ObjectWriteContext.empty(), out, JsonEncoding.UTF8)) {
            g.writeStartObject();
            for (Map.Entry<String, ?> e : body.entrySet()) {
                g.writeName(e.getKey());
                writeValue(g, e.getValue());
            }
            g.writeEndObject();
        }
        return out.toByteArray();
    }

    private static void writeValue(JsonGenerator g, Object value) {
        switch (value) {
            case null -> g.writeNull();
            case String s -> g.writeString(s);
            case Boolean b -> g.writeBoolean(b);
            case Integer i -> g.writeNumber(i);
            case Long l -> g.writeNumber(l);
            case List<?> items -> {
                g.writeStartArray();
                for (Object item : items) {
                    writeValue(g, item);
                }
                g.writeEndArray();
            }
            default -> g.writeString(String.valueOf(value));
        }
    }
}
