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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.NjMuleInfo;
import org.njmule.core.api.SearchId;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.api.UploadPriority;
import org.njmule.core.config.Preferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JsonGenerator;

/**
 * The remote-control API under {@value #PREFIX}: JSON over HTTP for callers that send the API token as a bearer token,
 * and the engine's events as server-sent events. It uses no cookie, so other sites' pages cannot act through a browser;
 * wrong tokens count as failed logins of their address.
 */
final class Api {

    static final String PREFIX = "/api/v1";

    /** The version of the API, which grows when calls are added and changes only with the prefix otherwise. */
    static final int VERSION = 1;

    private static final Logger logger = LoggerFactory.getLogger(Api.class);
    private static final String JSON = "application/json; charset=utf-8";
    private static final String GET = "GET";
    private static final String POST = "POST";
    private static final String PUT = "PUT";
    private static final String DELETE = "DELETE";
    private static final String HASH = "hash";
    private static final String ADDRESS = "address";
    private static final String PRIORITY = "priority";
    private static final long ENGINE_WAIT_SECONDS = 10;

    private final NjMuleEngine engine;
    private final Path configDir;
    private final Sessions sessions;
    private final SearchCollector searches;
    private final RecentLog log;
    private final EventStreams streams;
    private final Optional<Runnable> exit;

    Api(
            NjMuleEngine engine,
            Sessions sessions,
            SearchCollector searches,
            RecentLog log,
            EventStreams streams,
            Optional<Runnable> exit) {
        this.engine = engine;
        this.configDir = engine.directories().config();
        this.sessions = sessions;
        this.searches = searches;
        this.log = log;
        this.streams = streams;
        this.exit = exit;
    }

    /** A request that cannot be answered as asked; the message is meant for the caller. */
    static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final int status;

        Failure(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    void handle(Exchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (Failure e) {
            error(exchange, e.status, e.getMessage());
        } catch (IllegalArgumentException e) {
            error(exchange, 400, String.valueOf(e.getMessage()));
        } catch (RuntimeException e) {
            logger.warn("API request failed", e);
            error(exchange, 500, "The request failed.");
        }
    }

    // No Host check: browsers never send the token by themselves, so a page reaching us through DNS rebinding cannot
    // act, and other computers may use any name of this one.
    private void route(Exchange exchange) throws IOException {
        authorize(exchange);
        String method = exchange.method();
        List<String> path = segments(exchange.rawPath());
        if (path.isEmpty()) {
            only(method, GET);
            reply(exchange, 200, g -> {
                g.writeStartObject();
                g.writeStringProperty("name", NjMuleInfo.NAME);
                g.writeStringProperty("version", NjMuleInfo.version());
                g.writeNumberProperty("api", VERSION);
                g.writeEndObject();
            });
            return;
        }
        List<String> rest = path.subList(1, path.size());
        switch (path.getFirst()) {
            case "status" -> status(exchange, method, rest);
            case "downloads" -> downloads(exchange, method, rest);
            case "sources" -> list(exchange, method, rest, this.engine::sources, ApiJson::source);
            case "uploads" -> list(exchange, method, rest, this.engine::uploads, ApiJson::upload);
            case "shared" -> shared(exchange, method, rest);
            case "servers" -> servers(exchange, method, rest);
            case "kad" -> kad(exchange, method, rest);
            case "searches" -> searches(exchange, method, rest);
            case "categories" -> list(exchange, method, rest, this.engine::categories, ApiJson::category);
            case "friends" -> list(exchange, method, rest, this.engine::friends, ApiJson::friend);
            case "messages" -> messages(exchange, method, rest);
            case "bans" -> bans(exchange, method, rest);
            case "limits" -> limits(exchange, method, rest);
            case "ipfilter" -> ipFilter(exchange, method, rest);
            case "log" -> log(exchange, method, rest);
            case "events" -> events(exchange, method, rest);
            case "shutdown" -> shutdown(exchange, method, rest);
            default -> throw notFound();
        }
    }

    private void authorize(Exchange exchange) {
        String header = exchange.header("Authorization");
        String given = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                ? header.substring(7).strip()
                : null;
        Optional<String> token;
        try {
            token = ApiToken.load(this.configDir);
        } catch (IOException e) {
            logger.warn("Cannot read the API token: {}", e.toString());
            throw new Failure(503, "The API token cannot be read.");
        }
        if (token.isPresent() && ApiToken.matches(token.get(), given)) {
            return;
        }
        String address = Sessions.clientKey(exchange.remoteAddress());
        exchange.setHeader("WWW-Authenticate", "Bearer");
        // A wrong token counts as a failed login of its address.
        if (!this.sessions.mayTry(address)) {
            throw new Failure(429, "Too many attempts: wait a minute.");
        }
        if (given != null) {
            logger.warn("Wrong API token from {}", address);
        }
        throw new Failure(401, given == null ? "Send the API token as a bearer token." : "Wrong token.");
    }

    private void status(Exchange exchange, String method, List<String> rest) throws IOException {
        noMore(rest);
        only(method, GET);
        var status = await(this.engine.status());
        reply(exchange, 200, g -> ApiJson.status(g, status));
    }

    private <T> void list(
            Exchange exchange,
            String method,
            List<String> rest,
            Supplier<CompletableFuture<List<T>>> items,
            ApiJson.Writer<T> each)
            throws IOException {
        noMore(rest);
        only(method, GET);
        List<T> all = await(items.get());
        reply(exchange, 200, g -> ApiJson.array(g, all, each));
    }

    private void downloads(Exchange exchange, String method, List<String> rest) throws IOException {
        if (rest.isEmpty()) {
            switch (method) {
                case GET -> list(exchange, method, rest, this.engine::downloads, ApiJson::download);
                case POST -> addDownloads(exchange);
                default -> throw notAllowed();
            }
            return;
        }
        Hash16 file = hash(rest.getFirst());
        if (rest.size() == 1) {
            switch (method) {
                case GET -> {
                    DownloadInfo d = download(file);
                    reply(exchange, 200, g -> ApiJson.download(g, d));
                }
                case DELETE -> {
                    known(await(this.engine.cancelDownload(file)));
                    done(exchange);
                }
                default -> throw notAllowed();
            }
            return;
        }
        noMore(rest.subList(2, rest.size()));
        Map<String, Object> body = body(exchange);
        boolean known = switch (rest.get(1)) {
            case "pause" -> action(method, () -> this.engine.pauseDownload(file));
            case "resume" -> action(method, () -> this.engine.resumeDownload(file));
            case "stop" -> action(method, () -> this.engine.stopDownload(file));
            case PRIORITY -> {
                only(method, PUT);
                yield await(
                        this.engine.setDownloadPriority(file, enumValue(DownloadPriority.class, text(body, PRIORITY))));
            }
            case "category" -> {
                only(method, PUT);
                yield await(this.engine.setDownloadCategory(file, (int) number(body, "category")));
            }
            case "sources" -> {
                only(method, POST);
                yield await(this.engine.addSource(file, address(text(body, ADDRESS))));
            }
            default -> throw notFound();
        };
        known(known);
        DownloadInfo d = download(file);
        reply(exchange, 200, g -> ApiJson.download(g, d));
    }

    private boolean action(String method, Supplier<CompletableFuture<Boolean>> work) {
        only(method, POST);
        return await(work.get());
    }

    private DownloadInfo download(Hash16 hash) {
        return await(this.engine.downloads()).stream()
                .filter(d -> d.hash().equals(hash))
                .findFirst()
                .orElseThrow(Api::notFound);
    }

    private void addDownloads(Exchange exchange) throws IOException {
        Map<String, Object> body = body(exchange);
        List<Ed2kLink.FileLink> links = new ArrayList<>();
        for (String text : strings(body, "links")) {
            if (!(Ed2kLink.tryParse(text.strip()).orElse(null) instanceof Ed2kLink.FileLink link)) {
                throw new Failure(400, "Not an eD2K file link: " + text);
            }
            links.add(link);
        }
        if (links.isEmpty()) {
            throw new Failure(400, "No links.");
        }
        Optional<Integer> category =
                body.get("category") == null ? Optional.empty() : Optional.of((int) number(body, "category"));
        List<DownloadInfo> added = new ArrayList<>();
        for (Ed2kLink.FileLink link : links) {
            DownloadInfo d = await(this.engine.addDownload(link));
            if (category.isPresent()) {
                await(this.engine.setDownloadCategory(d.hash(), category.get()));
            }
            added.add(d);
        }
        reply(exchange, 201, g -> ApiJson.array(g, added, ApiJson::download));
    }

    private void shared(Exchange exchange, String method, List<String> rest) throws IOException {
        if (rest.isEmpty()) {
            list(exchange, method, rest, this.engine::sharedFiles, ApiJson::shared);
            return;
        }
        if (rest.equals(List.of("directories"))) {
            list(exchange, method, List.of(), this.engine::sharedDirectories, ApiJson::directory);
            return;
        }
        if (rest.size() != 2 || !rest.get(1).equals(PRIORITY)) {
            throw notFound();
        }
        only(method, PUT);
        Hash16 hash = hash(rest.getFirst());
        Map<String, Object> body = body(exchange);
        known(await(this.engine.setUploadPriority(hash, enumValue(UploadPriority.class, text(body, PRIORITY)))));
        done(exchange);
    }

    private void servers(Exchange exchange, String method, List<String> rest) throws IOException {
        if (rest.isEmpty()) {
            switch (method) {
                case GET -> list(exchange, method, rest, this.engine::servers, ApiJson::server);
                case POST -> {
                    ServerAddress server = address(text(body(exchange), ADDRESS));
                    if (!await(this.engine.addServer(server))) {
                        throw new Failure(409, "That server is known already.");
                    }
                    done(exchange, 201);
                }
                default -> throw notAllowed();
            }
            return;
        }
        noMore(rest.subList(1, rest.size()));
        switch (rest.getFirst()) {
            case "connect" -> {
                only(method, POST);
                Object address = body(exchange).get(ADDRESS);
                await(this.engine.connect(
                        address == null ? Optional.empty() : Optional.of(address(String.valueOf(address)))));
                done(exchange);
            }
            case "disconnect" -> {
                only(method, POST);
                await(this.engine.disconnect());
                done(exchange);
            }
            case "import" -> {
                only(method, POST);
                int added = await(this.engine.addServers(url(text(body(exchange), "url"))));
                count(exchange, added);
            }
            default -> {
                only(method, DELETE);
                known(await(this.engine.removeServer(address(rest.getFirst()))));
                done(exchange);
            }
        }
    }

    private void kad(Exchange exchange, String method, List<String> rest) throws IOException {
        if (rest.isEmpty()) {
            only(method, GET);
            var kad = await(this.engine.kad());
            reply(exchange, 200, g -> {
                if (kad.isPresent()) {
                    ApiJson.kad(g, kad.get());
                } else {
                    g.writeNull();
                }
            });
            return;
        }
        noMore(rest.subList(1, rest.size()));
        switch (rest.getFirst()) {
            case "contacts" -> list(exchange, method, List.of(), this.engine::kadContacts, ApiJson::kadContact);
            case "searches" -> list(exchange, method, List.of(), this.engine::kadSearches, ApiJson::kadSearch);
            case "connect" -> {
                only(method, POST);
                await(this.engine.connectKad());
                done(exchange);
            }
            case "disconnect" -> {
                only(method, POST);
                await(this.engine.disconnectKad());
                done(exchange);
            }
            case "recheck" -> {
                only(method, POST);
                if (!await(this.engine.recheckKadFirewall())) {
                    throw new Failure(409, "Kad is not connected.");
                }
                done(exchange);
            }
            case "bootstrap" -> {
                only(method, POST);
                await(this.engine.kadBootstrap(address(text(body(exchange), ADDRESS))));
                done(exchange);
            }
            case "nodes" -> {
                only(method, POST);
                count(exchange, await(this.engine.addKadContacts(url(text(body(exchange), "url")))));
            }
            default -> throw notFound();
        }
    }

    private void searches(Exchange exchange, String method, List<String> rest) throws IOException {
        if (rest.isEmpty()) {
            only(method, POST);
            Map<String, Object> body = body(exchange);
            String expression = text(body, "query").strip();
            SearchRequest.checkExpression(expression);
            SearchRequest.Scope scope = body.get("scope") == null
                    ? SearchRequest.Scope.SERVER
                    : enumValue(SearchRequest.Scope.class, text(body, "scope"));
            SearchId id = await(this.engine.search(new SearchRequest(expression, scope)));
            this.searches.started(id.value());
            reply(exchange, 201, g -> {
                g.writeStartObject();
                g.writeNumberProperty("id", id.value());
                g.writeEndObject();
            });
            return;
        }
        long id = searchId(rest.getFirst());
        if (rest.size() == 1) {
            switch (method) {
                case GET -> {
                    List<SearchResult> found = this.searches.results(id).orElseThrow(Api::notFound);
                    found.sort((a, b) -> Integer.compare(b.sources(), a.sources()));
                    reply(exchange, 200, g -> {
                        g.writeStartObject();
                        g.writeNumberProperty("id", id);
                        g.writeName("results");
                        ApiJson.array(g, found, ApiJson::result);
                        g.writeEndObject();
                    });
                }
                case DELETE -> {
                    searchKnown(id);
                    await(this.engine.stopSearch(new SearchId(id)));
                    this.searches.forget(id);
                    done(exchange);
                }
                default -> throw notAllowed();
            }
            return;
        }
        noMore(rest.subList(2, rest.size()));
        searchKnown(id);
        switch (rest.get(1)) {
            case "more" -> {
                only(method, POST);
                if (!await(this.engine.moreResults(new SearchId(id)))) {
                    throw new Failure(409, "That search cannot get more results.");
                }
                done(exchange);
            }
            case "download" -> {
                only(method, POST);
                Hash16 hash = hash(text(body(exchange), HASH));
                SearchResult result = this.searches
                        .result(id, hash)
                        .orElseThrow(() -> new Failure(404, "That result is gone: search again."));
                DownloadInfo d = await(this.engine.addDownload(result.link()));
                reply(exchange, 201, g -> ApiJson.download(g, d));
            }
            default -> throw notFound();
        }
    }

    private void searchKnown(long id) {
        if (this.searches.results(id).isEmpty()) {
            throw notFound();
        }
    }

    private void messages(Exchange exchange, String method, List<String> rest) throws IOException {
        noMore(rest);
        only(method, POST);
        Map<String, Object> body = body(exchange);
        await(this.engine.sendMessage(address(text(body, "to")), text(body, "text")));
        done(exchange);
    }

    private void bans(Exchange exchange, String method, List<String> rest) throws IOException {
        if (rest.isEmpty()) {
            list(exchange, method, rest, this.engine::bans, ApiJson::ban);
            return;
        }
        noMore(rest.subList(1, rest.size()));
        only(method, DELETE);
        known(await(this.engine.unban(rest.getFirst())));
        done(exchange);
    }

    private void limits(Exchange exchange, String method, List<String> rest) throws IOException {
        noMore(rest);
        Preferences now = switch (method) {
            case GET -> this.engine.preferences();
            case PUT -> {
                Map<String, Object> body = body(exchange);
                yield await(this.engine.updatePreferences(p -> {
                    Preferences.Builder b = p.toBuilder();
                    if (body.get("uploadKiBps") != null) {
                        b.maxUploadKiBps(rate(body, "uploadKiBps"));
                    }
                    if (body.get("downloadKiBps") != null) {
                        b.maxDownloadKiBps(rate(body, "downloadKiBps"));
                    }
                    return b.build();
                }));
            }
            default -> throw notAllowed();
        };
        reply(exchange, 200, g -> {
            g.writeStartObject();
            g.writeNumberProperty("uploadKiBps", now.maxUploadKiBps());
            g.writeNumberProperty("downloadKiBps", now.maxDownloadKiBps());
            g.writeEndObject();
        });
    }

    private void ipFilter(Exchange exchange, String method, List<String> rest) throws IOException {
        noMore(rest);
        only(method, POST);
        count(exchange, await(this.engine.updateIpFilter(url(text(body(exchange), "url")))));
    }

    private void log(Exchange exchange, String method, List<String> rest) throws IOException {
        noMore(rest);
        only(method, GET);
        String after = query(exchange).get("after");
        long seen;
        try {
            seen = after == null ? 0 : Long.parseLong(after);
        } catch (NumberFormatException e) {
            throw new Failure(400, "after must be a number.");
        }
        List<RecentLog.Entry> lines = this.log.after(seen);
        reply(exchange, 200, g -> ApiJson.array(g, lines, (w, e) -> ApiJson.log(w, e.sequence(), e.line())));
    }

    private void events(Exchange exchange, String method, List<String> rest) throws IOException {
        noMore(rest);
        only(method, GET);
        String types = query(exchange).getOrDefault("types", "");
        List<String> wanted = Arrays.stream(types.split(",", -1))
                .map(String::strip)
                .filter(t -> !t.isEmpty())
                .toList();
        if (!this.streams.serve(exchange, wanted)) {
            throw new Failure(503, "Too many event streams.");
        }
    }

    private void shutdown(Exchange exchange, String method, List<String> rest) throws IOException {
        noMore(rest);
        only(method, POST);
        Runnable stop = this.exit.orElseThrow(() -> new Failure(501, "This njMule cannot be stopped from here."));
        done(exchange, 202);
        logger.info("Exiting as the remote-control API asked");
        // Out of the server's threads, which the exit stops.
        Thread.ofVirtual().name("njmule-api-exit").start(stop);
    }

    // Values of the body.

    private Map<String, Object> body(Exchange exchange) throws IOException {
        try (InputStream in = exchange.requestBody()) {
            byte[] body = in.readNBytes(ApiJson.MAX_BODY + 1);
            if (body.length > ApiJson.MAX_BODY) {
                throw new Failure(413, "The body is too large.");
            }
            return ApiJson.read(body);
        }
    }

    private static String text(Map<String, Object> body, String name) {
        if (!(body.get(name) instanceof String value)) {
            throw new Failure(400, "Missing text field: " + name);
        }
        return value;
    }

    private static long number(Map<String, Object> body, String name) {
        if (!(body.get(name) instanceof Long value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new Failure(400, "Missing number field: " + name);
        }
        return value;
    }

    private static int rate(Map<String, Object> body, String name) {
        long value = number(body, name);
        if (value < 0 || value > Preferences.MAX_RATE_KIBPS) {
            throw new Failure(400, name + " must be in [0, " + Preferences.MAX_RATE_KIBPS + "] (0: unlimited).");
        }
        return (int) value;
    }

    private static List<String> strings(Map<String, Object> body, String name) {
        Object value = body.get(name);
        if (value instanceof String one) {
            return List.of(one);
        }
        if (value instanceof List<?> many) {
            return many.stream().map(String::valueOf).toList();
        }
        throw new Failure(400, "Missing list field: " + name);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String name) {
        try {
            return Enum.valueOf(type, name.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new Failure(400, "Unknown value: " + name);
        }
    }

    private static Hash16 hash(String text) {
        try {
            return Hash16.parseHex(text);
        } catch (IllegalArgumentException e) {
            throw new Failure(400, "Not a file hash: " + text);
        }
    }

    private static ServerAddress address(String text) {
        try {
            return ServerAddress.parse(text.strip());
        } catch (IllegalArgumentException e) {
            throw new Failure(400, "Not a host:port address: " + text);
        }
    }

    private static URI url(String text) {
        try {
            URI uri = new URI(text.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new Failure(400, "Only http and https addresses are taken.");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new Failure(400, "Not an address: " + text);
        }
    }

    private static long searchId(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw notFound();
        }
    }

    // Answers.

    private void reply(Exchange exchange, int status, Consumer<JsonGenerator> body) throws IOException {
        send(exchange, status, ApiJson.write(body));
    }

    private void done(Exchange exchange) throws IOException {
        done(exchange, 200);
    }

    private void done(Exchange exchange, int status) throws IOException {
        reply(exchange, status, g -> {
            g.writeStartObject();
            g.writeBooleanProperty("ok", true);
            g.writeEndObject();
        });
    }

    private void count(Exchange exchange, int added) throws IOException {
        reply(exchange, 200, g -> {
            g.writeStartObject();
            g.writeNumberProperty("added", added);
            g.writeEndObject();
        });
    }

    private void error(Exchange exchange, int status, String message) throws IOException {
        if (!exchange.started()) {
            send(exchange, status, ApiJson.write(g -> {
                g.writeStartObject();
                g.writeStringProperty("error", message);
                g.writeEndObject();
            }));
        }
    }

    private void send(Exchange exchange, int status, byte[] body) throws IOException {
        exchange.setHeader("Content-Type", JSON);
        exchange.setHeader("Cache-Control", "no-store");
        exchange.setHeader("X-Content-Type-Options", "nosniff");
        exchange.sendHeaders(status, body.length);
        try (OutputStream out = exchange.responseBody()) {
            out.write(body);
        }
    }

    // Requests.

    private void only(String method, String allowed) {
        if (!method.equals(allowed)) {
            throw notAllowed();
        }
    }

    private void noMore(List<String> rest) {
        if (!rest.isEmpty()) {
            throw notFound();
        }
    }

    private void known(boolean known) {
        if (!known) {
            throw notFound();
        }
    }

    private static Failure notFound() {
        return new Failure(404, "Not found.");
    }

    private static Failure notAllowed() {
        return new Failure(405, "Method not allowed.");
    }

    // The decoded segments of the path after the prefix.
    static List<String> segments(String rawPath) {
        if (!rawPath.equals(PREFIX) && !rawPath.startsWith(PREFIX + "/")) {
            throw notFound();
        }
        List<String> parts = new ArrayList<>();
        for (String part : rawPath.substring(PREFIX.length()).split("/", -1)) {
            if (!part.isEmpty()) {
                parts.add(URLDecoder.decode(part.replace("+", "%2B"), StandardCharsets.UTF_8));
            }
        }
        return parts;
    }

    private static Map<String, String> query(Exchange exchange) {
        String raw = exchange.rawQuery();
        Map<String, String> values = new HashMap<>();
        if (raw == null) {
            return values;
        }
        for (String pair : raw.split("&", -1)) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                values.put(
                        URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private <T> T await(CompletableFuture<T> work) {
        try {
            return work.get(ENGINE_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(503, "Interrupted.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IllegalArgumentException bad) {
                throw new Failure(400, String.valueOf(bad.getMessage()));
            }
            throw new Failure(503, "The engine cannot do it now: " + cause.getMessage());
        } catch (TimeoutException e) {
            throw new Failure(503, "The engine did not answer in time.");
        }
    }
}
