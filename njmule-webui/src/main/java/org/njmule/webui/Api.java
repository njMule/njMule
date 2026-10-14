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

import com.sun.net.httpserver.HttpExchange;
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

    void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                this.route(exchange);
            } catch (Failure e) {
                this.error(exchange, e.status, e.getMessage());
            } catch (IllegalArgumentException e) {
                this.error(exchange, 400, String.valueOf(e.getMessage()));
            } catch (RuntimeException e) {
                logger.warn("API request failed", e);
                this.error(exchange, 500, "The request failed.");
            }
        }
    }

    // No Host check: browsers never send the token by themselves, so a page reaching us through DNS rebinding cannot
    // act, and other computers may use any name of this one.
    private void route(HttpExchange exchange) throws IOException {
        this.authorize(exchange);
        String method = exchange.getRequestMethod();
        List<String> path = segments(exchange.getRequestURI().getRawPath());
        if (path.isEmpty()) {
            this.only(method, GET);
            this.reply(exchange, 200, g -> {
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
            case "status" -> this.status(exchange, method, rest);
            case "downloads" -> this.downloads(exchange, method, rest);
            case "sources" -> this.list(exchange, method, rest, this.engine::sources, ApiJson::source);
            case "uploads" -> this.list(exchange, method, rest, this.engine::uploads, ApiJson::upload);
            case "shared" -> this.shared(exchange, method, rest);
            case "servers" -> this.servers(exchange, method, rest);
            case "kad" -> this.kad(exchange, method, rest);
            case "searches" -> this.searches(exchange, method, rest);
            case "categories" -> this.list(exchange, method, rest, this.engine::categories, ApiJson::category);
            case "friends" -> this.list(exchange, method, rest, this.engine::friends, ApiJson::friend);
            case "messages" -> this.messages(exchange, method, rest);
            case "bans" -> this.bans(exchange, method, rest);
            case "limits" -> this.limits(exchange, method, rest);
            case "ipfilter" -> this.ipFilter(exchange, method, rest);
            case "log" -> this.log(exchange, method, rest);
            case "events" -> this.events(exchange, method, rest);
            case "shutdown" -> this.shutdown(exchange, method, rest);
            default -> throw notFound();
        }
    }

    private void authorize(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
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
        String address = exchange.getRemoteAddress().getAddress().getHostAddress();
        exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
        // A wrong token counts as a failed login of its address.
        if (!this.sessions.mayTry(address)) {
            throw new Failure(429, "Too many attempts: wait a minute.");
        }
        throw new Failure(401, given == null ? "Send the API token as a bearer token." : "Wrong token.");
    }

    private void status(HttpExchange exchange, String method, List<String> rest) throws IOException {
        this.noMore(rest);
        this.only(method, GET);
        var status = this.await(this.engine.status());
        this.reply(exchange, 200, g -> ApiJson.status(g, status));
    }

    private <T> void list(
            HttpExchange exchange,
            String method,
            List<String> rest,
            Supplier<CompletableFuture<List<T>>> items,
            ApiJson.Writer<T> each)
            throws IOException {
        this.noMore(rest);
        this.only(method, GET);
        List<T> all = this.await(items.get());
        this.reply(exchange, 200, g -> ApiJson.array(g, all, each));
    }

    private void downloads(HttpExchange exchange, String method, List<String> rest) throws IOException {
        if (rest.isEmpty()) {
            switch (method) {
                case GET -> this.list(exchange, method, rest, this.engine::downloads, ApiJson::download);
                case POST -> this.addDownloads(exchange);
                default -> throw notAllowed();
            }
            return;
        }
        Hash16 file = hash(rest.getFirst());
        if (rest.size() == 1) {
            switch (method) {
                case GET -> {
                    DownloadInfo d = this.download(file);
                    this.reply(exchange, 200, g -> ApiJson.download(g, d));
                }
                case DELETE -> {
                    this.known(this.await(this.engine.cancelDownload(file)));
                    this.done(exchange);
                }
                default -> throw notAllowed();
            }
            return;
        }
        this.noMore(rest.subList(2, rest.size()));
        Map<String, Object> body = this.body(exchange);
        boolean known = switch (rest.get(1)) {
            case "pause" -> this.action(method, () -> this.engine.pauseDownload(file));
            case "resume" -> this.action(method, () -> this.engine.resumeDownload(file));
            case "stop" -> this.action(method, () -> this.engine.stopDownload(file));
            case PRIORITY -> {
                this.only(method, PUT);
                yield this.await(
                        this.engine.setDownloadPriority(file, enumValue(DownloadPriority.class, text(body, PRIORITY))));
            }
            case "category" -> {
                this.only(method, PUT);
                yield this.await(this.engine.setDownloadCategory(file, (int) number(body, "category")));
            }
            case "sources" -> {
                this.only(method, POST);
                yield this.await(this.engine.addSource(file, address(text(body, ADDRESS))));
            }
            default -> throw notFound();
        };
        this.known(known);
        DownloadInfo d = this.download(file);
        this.reply(exchange, 200, g -> ApiJson.download(g, d));
    }

    private boolean action(String method, Supplier<CompletableFuture<Boolean>> work) {
        this.only(method, POST);
        return this.await(work.get());
    }

    private DownloadInfo download(Hash16 hash) {
        return this.await(this.engine.downloads()).stream()
                .filter(d -> d.hash().equals(hash))
                .findFirst()
                .orElseThrow(Api::notFound);
    }

    private void addDownloads(HttpExchange exchange) throws IOException {
        Map<String, Object> body = this.body(exchange);
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
            DownloadInfo d = this.await(this.engine.addDownload(link));
            if (category.isPresent()) {
                this.await(this.engine.setDownloadCategory(d.hash(), category.get()));
            }
            added.add(d);
        }
        this.reply(exchange, 201, g -> ApiJson.array(g, added, ApiJson::download));
    }

    private void shared(HttpExchange exchange, String method, List<String> rest) throws IOException {
        if (rest.isEmpty()) {
            this.list(exchange, method, rest, this.engine::sharedFiles, ApiJson::shared);
            return;
        }
        if (rest.equals(List.of("directories"))) {
            this.list(exchange, method, List.of(), this.engine::sharedDirectories, ApiJson::directory);
            return;
        }
        if (rest.size() != 2 || !rest.get(1).equals(PRIORITY)) {
            throw notFound();
        }
        this.only(method, PUT);
        Hash16 hash = hash(rest.getFirst());
        Map<String, Object> body = this.body(exchange);
        this.known(
                this.await(this.engine.setUploadPriority(hash, enumValue(UploadPriority.class, text(body, PRIORITY)))));
        this.done(exchange);
    }

    private void servers(HttpExchange exchange, String method, List<String> rest) throws IOException {
        if (rest.isEmpty()) {
            switch (method) {
                case GET -> this.list(exchange, method, rest, this.engine::servers, ApiJson::server);
                case POST -> {
                    ServerAddress server = address(text(this.body(exchange), ADDRESS));
                    if (!this.await(this.engine.addServer(server))) {
                        throw new Failure(409, "That server is known already.");
                    }
                    this.done(exchange, 201);
                }
                default -> throw notAllowed();
            }
            return;
        }
        this.noMore(rest.subList(1, rest.size()));
        switch (rest.getFirst()) {
            case "connect" -> {
                this.only(method, POST);
                Object address = this.body(exchange).get(ADDRESS);
                this.await(this.engine.connect(
                        address == null ? Optional.empty() : Optional.of(address(String.valueOf(address)))));
                this.done(exchange);
            }
            case "disconnect" -> {
                this.only(method, POST);
                this.await(this.engine.disconnect());
                this.done(exchange);
            }
            case "import" -> {
                this.only(method, POST);
                int added = this.await(this.engine.addServers(url(text(this.body(exchange), "url"))));
                this.count(exchange, added);
            }
            default -> {
                this.only(method, DELETE);
                this.known(this.await(this.engine.removeServer(address(rest.getFirst()))));
                this.done(exchange);
            }
        }
    }

    private void kad(HttpExchange exchange, String method, List<String> rest) throws IOException {
        if (rest.isEmpty()) {
            this.only(method, GET);
            var kad = this.await(this.engine.kad());
            this.reply(exchange, 200, g -> {
                if (kad.isPresent()) {
                    ApiJson.kad(g, kad.get());
                } else {
                    g.writeNull();
                }
            });
            return;
        }
        this.noMore(rest.subList(1, rest.size()));
        switch (rest.getFirst()) {
            case "contacts" -> this.list(exchange, method, List.of(), this.engine::kadContacts, ApiJson::kadContact);
            case "searches" -> this.list(exchange, method, List.of(), this.engine::kadSearches, ApiJson::kadSearch);
            case "connect" -> {
                this.only(method, POST);
                this.await(this.engine.connectKad());
                this.done(exchange);
            }
            case "disconnect" -> {
                this.only(method, POST);
                this.await(this.engine.disconnectKad());
                this.done(exchange);
            }
            case "recheck" -> {
                this.only(method, POST);
                if (!this.await(this.engine.recheckKadFirewall())) {
                    throw new Failure(409, "Kad is not connected.");
                }
                this.done(exchange);
            }
            case "bootstrap" -> {
                this.only(method, POST);
                this.await(this.engine.kadBootstrap(address(text(this.body(exchange), ADDRESS))));
                this.done(exchange);
            }
            case "nodes" -> {
                this.only(method, POST);
                this.count(exchange, this.await(this.engine.addKadContacts(url(text(this.body(exchange), "url")))));
            }
            default -> throw notFound();
        }
    }

    private void searches(HttpExchange exchange, String method, List<String> rest) throws IOException {
        if (rest.isEmpty()) {
            this.only(method, POST);
            Map<String, Object> body = this.body(exchange);
            String expression = text(body, "query").strip();
            SearchRequest.checkExpression(expression);
            SearchRequest.Scope scope = body.get("scope") == null
                    ? SearchRequest.Scope.SERVER
                    : enumValue(SearchRequest.Scope.class, text(body, "scope"));
            SearchId id = this.await(this.engine.search(new SearchRequest(expression, scope)));
            this.searches.started(id.value());
            this.reply(exchange, 201, g -> {
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
                    this.reply(exchange, 200, g -> {
                        g.writeStartObject();
                        g.writeNumberProperty("id", id);
                        g.writeName("results");
                        ApiJson.array(g, found, ApiJson::result);
                        g.writeEndObject();
                    });
                }
                case DELETE -> {
                    this.searchKnown(id);
                    this.await(this.engine.stopSearch(new SearchId(id)));
                    this.searches.forget(id);
                    this.done(exchange);
                }
                default -> throw notAllowed();
            }
            return;
        }
        this.noMore(rest.subList(2, rest.size()));
        this.searchKnown(id);
        switch (rest.get(1)) {
            case "more" -> {
                this.only(method, POST);
                if (!this.await(this.engine.moreResults(new SearchId(id)))) {
                    throw new Failure(409, "That search cannot get more results.");
                }
                this.done(exchange);
            }
            case "download" -> {
                this.only(method, POST);
                Hash16 hash = hash(text(this.body(exchange), HASH));
                SearchResult result = this.searches
                        .result(id, hash)
                        .orElseThrow(() -> new Failure(404, "That result is gone: search again."));
                DownloadInfo d = this.await(this.engine.addDownload(result.link()));
                this.reply(exchange, 201, g -> ApiJson.download(g, d));
            }
            default -> throw notFound();
        }
    }

    private void searchKnown(long id) {
        if (this.searches.results(id).isEmpty()) {
            throw notFound();
        }
    }

    private void messages(HttpExchange exchange, String method, List<String> rest) throws IOException {
        this.noMore(rest);
        this.only(method, POST);
        Map<String, Object> body = this.body(exchange);
        this.await(this.engine.sendMessage(address(text(body, "to")), text(body, "text")));
        this.done(exchange);
    }

    private void bans(HttpExchange exchange, String method, List<String> rest) throws IOException {
        if (rest.isEmpty()) {
            this.list(exchange, method, rest, this.engine::bans, ApiJson::ban);
            return;
        }
        this.noMore(rest.subList(1, rest.size()));
        this.only(method, DELETE);
        this.known(this.await(this.engine.unban(rest.getFirst())));
        this.done(exchange);
    }

    private void limits(HttpExchange exchange, String method, List<String> rest) throws IOException {
        this.noMore(rest);
        Preferences now = switch (method) {
            case GET -> this.engine.preferences();
            case PUT -> {
                Map<String, Object> body = this.body(exchange);
                yield this.await(this.engine.updatePreferences(p -> {
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
        this.reply(exchange, 200, g -> {
            g.writeStartObject();
            g.writeNumberProperty("uploadKiBps", now.maxUploadKiBps());
            g.writeNumberProperty("downloadKiBps", now.maxDownloadKiBps());
            g.writeEndObject();
        });
    }

    private void ipFilter(HttpExchange exchange, String method, List<String> rest) throws IOException {
        this.noMore(rest);
        this.only(method, POST);
        this.count(exchange, this.await(this.engine.updateIpFilter(url(text(this.body(exchange), "url")))));
    }

    private void log(HttpExchange exchange, String method, List<String> rest) throws IOException {
        this.noMore(rest);
        this.only(method, GET);
        String after = query(exchange).get("after");
        long seen;
        try {
            seen = after == null ? 0 : Long.parseLong(after);
        } catch (NumberFormatException e) {
            throw new Failure(400, "after must be a number.");
        }
        List<RecentLog.Entry> lines = this.log.after(seen);
        this.reply(exchange, 200, g -> ApiJson.array(g, lines, (w, e) -> ApiJson.log(w, e.sequence(), e.line())));
    }

    private void events(HttpExchange exchange, String method, List<String> rest) throws IOException {
        this.noMore(rest);
        this.only(method, GET);
        String types = query(exchange).getOrDefault("types", "");
        List<String> wanted = Arrays.stream(types.split(",", -1))
                .map(String::strip)
                .filter(t -> !t.isEmpty())
                .toList();
        if (!this.streams.serve(exchange, wanted)) {
            throw new Failure(503, "Too many event streams.");
        }
    }

    private void shutdown(HttpExchange exchange, String method, List<String> rest) throws IOException {
        this.noMore(rest);
        this.only(method, POST);
        Runnable stop = this.exit.orElseThrow(() -> new Failure(501, "This njMule cannot be stopped from here."));
        this.done(exchange, 202);
        logger.info("Exiting as the remote-control API asked");
        // Out of the server's threads, which the exit stops.
        Thread.ofVirtual().name("njmule-api-exit").start(stop);
    }

    // Values of the body.

    private Map<String, Object> body(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
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

    private void reply(HttpExchange exchange, int status, Consumer<JsonGenerator> body) throws IOException {
        this.send(exchange, status, ApiJson.write(body));
    }

    private void done(HttpExchange exchange) throws IOException {
        this.done(exchange, 200);
    }

    private void done(HttpExchange exchange, int status) throws IOException {
        this.reply(exchange, status, g -> {
            g.writeStartObject();
            g.writeBooleanProperty("ok", true);
            g.writeEndObject();
        });
    }

    private void count(HttpExchange exchange, int added) throws IOException {
        this.reply(exchange, 200, g -> {
            g.writeStartObject();
            g.writeNumberProperty("added", added);
            g.writeEndObject();
        });
    }

    private void error(HttpExchange exchange, int status, String message) throws IOException {
        if (exchange.getResponseCode() < 0) {
            this.send(exchange, status, ApiJson.write(g -> {
                g.writeStartObject();
                g.writeStringProperty("error", message);
                g.writeEndObject();
            }));
        }
    }

    private void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", JSON);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
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

    private static Map<String, String> query(HttpExchange exchange) {
        String raw = exchange.getRequestURI().getRawQuery();
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
