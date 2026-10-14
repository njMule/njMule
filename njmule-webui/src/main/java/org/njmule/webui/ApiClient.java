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
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * booleans and nulls.
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

    private final URI base;
    private final String token;
    private final HttpClient http;

    /** @param base the address of the API, as {@code http://127.0.0.1:4711/api/v1} */
    public ApiClient(URI base, String token) {
        this.base = base;
        this.token = token;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .proxy(ProxySelector.of(null))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** The API of the njMule using {@code configDir} on this computer, when its settings let it answer. */
    public static Optional<ApiClient> local(Path configDir) throws IOException {
        WebUiSettings settings = WebUiSettings.load(configDir);
        Optional<String> token = ApiToken.load(configDir);
        if (!settings.usable() || !settings.api() || token.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ApiClient(URI.create("http://127.0.0.1:" + settings.port() + Api.PREFIX), token.get()));
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
        return this.call("GET", path, null);
    }

    public Object post(String path, Map<String, ?> body) throws IOException, InterruptedException {
        return this.call("POST", path, body);
    }

    public Object put(String path, Map<String, ?> body) throws IOException, InterruptedException {
        return this.call("PUT", path, body);
    }

    public Object delete(String path) throws IOException, InterruptedException {
        return this.call("DELETE", path, null);
    }

    /**
     * Follows the event stream, handing each event's name and data to {@code each} until it returns false or the stream
     * ends.
     */
    public void events(String types, EventSink each) throws IOException, InterruptedException {
        HttpRequest request = this.request("/events?types=" + URLEncoder.encode(types, StandardCharsets.UTF_8))
                .GET()
                .build();
        HttpResponse<InputStream> answer = this.http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (answer.statusCode() != 200) {
            try (InputStream in = answer.body()) {
                throw failure(answer.statusCode(), in.readNBytes(64 * 1024));
            }
        }
        try (BufferedReader in = new BufferedReader(new InputStreamReader(answer.body(), StandardCharsets.UTF_8))) {
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
    }

    /** Takes the events of a stream. */
    @FunctionalInterface
    public interface EventSink {
        /** Takes one event; returns whether to go on. */
        boolean event(String name, Object data) throws IOException;
    }

    private Object call(String method, String path, Map<String, ?> body) throws IOException, InterruptedException {
        HttpRequest.BodyPublisher content = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(write(body));
        HttpRequest.Builder request = this.request(path).method(method, content);
        if (body != null) {
            request.header("Content-Type", "application/json; charset=utf-8");
        }
        HttpResponse<InputStream> answer = this.http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        byte[] data;
        try (InputStream in = answer.body()) {
            data = in.readNBytes(MAX_ANSWER + 1);
        }
        if (data.length > MAX_ANSWER) {
            throw new IOException("The answer is too large.");
        }
        if (answer.statusCode() / 100 != 2) {
            throw failure(answer.statusCode(), data);
        }
        return parse(data);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(this.base + path))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + this.token);
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
