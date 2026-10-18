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

import static java.util.Map.entry;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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
import org.njmule.core.api.UpdateStatus;
import org.njmule.core.api.UploadPriority;
import org.njmule.core.config.Preferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JsonNode;

/**
 * The remote-control API under {@value #PREFIX}, as its OpenAPI document {@code openapi.yaml} describes it: JSON over
 * HTTP for callers that send the API token as a bearer token, and the engine's events as server-sent events. Requests
 * are routed by the document's paths to the handler of each operation, after their parameters and bodies passed the
 * document's schemas. It uses no cookie, so other sites' pages cannot act through a browser; the host's {@link Guard}
 * slows down addresses that send wrong tokens. The host serves it on its own HTTP server.
 */
public final class RemoteApi implements AutoCloseable {

    public static final String PREFIX = "/api/v1";

    /** The version of the API, which grows when calls are added and changes only with the prefix otherwise. */
    public static final int VERSION = 1;

    /** How often an idle event stream sends a comment, so that callers and proxies keep its connection. */
    public static final Duration HEARTBEAT = EventStreams.HEARTBEAT;

    private static final Logger logger = LoggerFactory.getLogger(RemoteApi.class);
    private static final String JSON = "application/json; charset=utf-8";
    private static final String YAML = "application/yaml; charset=utf-8";
    private static final String HASH_KEY = "hash";
    private static final String ADDRESS_KEY = "address";
    private static final String PRIORITY_KEY = "priority";
    private static final String CATEGORY_KEY = "category";
    private static final String URL_KEY = "url";
    private static final long ENGINE_WAIT_SECONDS = 10;

    private final NjMuleEngine engine;
    private final Path configDir;
    private final Guard guard;
    private final SearchCollector searches;
    private final RecentLog log;
    private final EventStreams streams;
    private final Optional<Runnable> exit;
    private final ApiSpecification specification;
    private final Map<String, Handler> handlers;

    /** Decides whether a client that sent a wrong or no token may try again now. */
    @FunctionalInterface
    public interface Guard {
        /** Whether {@code client} may try now; each call counts as a failed attempt. */
        boolean mayTry(InetAddress client);
    }

    /**
     * An API over {@code engine}, answering searches and log lines from what {@code recent} collects; {@code exit},
     * when given, stops njMule.
     */
    public RemoteApi(NjMuleEngine engine, RecentEvents recent, Guard guard, Optional<Runnable> exit) {
        this(engine, recent, guard, exit, HEARTBEAT);
    }

    /** As the other constructor, with event streams that send a comment every {@code heartbeat} while idle. */
    public RemoteApi(
            NjMuleEngine engine, RecentEvents recent, Guard guard, Optional<Runnable> exit, Duration heartbeat) {
        this.engine = engine;
        this.configDir = engine.directories().config();
        this.guard = guard;
        this.searches = recent.searches();
        this.log = recent.log();
        this.streams = new EventStreams(engine, heartbeat);
        this.exit = exit;
        this.specification = Shipped.DOCUMENT;
        this.handlers = handlers();
        if (!this.handlers.keySet().equals(this.specification.operations().keySet())) {
            throw new IllegalStateException("operations without a handler or handlers without an operation: "
                    + difference(
                            this.handlers.keySet(),
                            this.specification.operations().keySet()));
        }
    }

    // Parsed once: every instance serves the same document.
    private static final class Shipped {
        static final ApiSpecification DOCUMENT = ApiSpecification.load();
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

    /** A request matched to an operation, with its parameters and body checked against the document. */
    private record Call(Exchange exchange, Map<String, JsonNode> parameters, JsonNode body) {}

    @FunctionalInterface
    private interface Handler {
        void handle(Call call) throws IOException;
    }

    // One handler for each operationId of the document.
    private Map<String, Handler> handlers() {
        return Map.ofEntries(
                entry("getApi", this::getApi),
                entry("getSpecification", c -> send(c.exchange(), 200, YAML, this.specification.yaml())),
                entry("getStatus", this::getStatus),
                entry("getLimits", c -> limits(c, this.engine.preferences())),
                entry("setLimits", this::setLimits),
                entry("updateIpFilter", c -> count(c, this.engine.updateIpFilter(url(c)))),
                entry("getLog", this::getLog),
                entry("streamEvents", this::streamEvents),
                entry("shutdown", this::shutdown),
                entry("listDownloads", c -> list(c, this.engine::downloads, ApiJson::download)),
                entry("addDownloads", this::addDownloads),
                entry("getDownload", c -> download(c, 200)),
                entry("cancelDownload", c -> known(c, this.engine.cancelDownload(hash(c)))),
                entry("pauseDownload", c -> changed(c, this.engine.pauseDownload(hash(c)))),
                entry("resumeDownload", c -> changed(c, this.engine.resumeDownload(hash(c)))),
                entry("stopDownload", c -> changed(c, this.engine.stopDownload(hash(c)))),
                entry(
                        "setDownloadPriority",
                        c -> changed(
                                c,
                                this.engine.setDownloadPriority(
                                        hash(c), enumValue(DownloadPriority.class, text(c.body(), PRIORITY_KEY))))),
                entry(
                        "setDownloadCategory",
                        c -> changed(c, this.engine.setDownloadCategory(hash(c), number(c.body(), CATEGORY_KEY)))),
                entry("renameDownload", c -> changed(c, this.engine.renameDownload(hash(c), text(c.body(), "name")))),
                entry(
                        "addDownloadSource",
                        c -> changed(c, this.engine.addSource(hash(c), address(text(c.body(), ADDRESS_KEY))))),
                entry("listSources", c -> list(c, this.engine::sources, ApiJson::source)),
                entry("listUploads", c -> list(c, this.engine::uploads, ApiJson::upload)),
                entry("listSharedFiles", c -> list(c, this.engine::sharedFiles, ApiJson::shared)),
                entry("listSharedDirectories", c -> list(c, this.engine::sharedDirectories, ApiJson::directory)),
                entry(
                        "setUploadPriority",
                        c -> known(
                                c,
                                this.engine.setUploadPriority(
                                        hash(c), enumValue(UploadPriority.class, text(c.body(), PRIORITY_KEY))))),
                entry("listServers", c -> list(c, this.engine::servers, ApiJson::server)),
                entry("addServer", this::addServer),
                entry("connectServer", this::connectServer),
                entry("disconnectServer", c -> done(c, this.engine.disconnect())),
                entry("importServers", c -> count(c, this.engine.addServers(url(c)))),
                entry(
                        "removeServer",
                        c -> known(c, this.engine.removeServer(address(text(c.parameters(), ADDRESS_KEY))))),
                entry("getKad", this::getKad),
                entry("listKadContacts", c -> list(c, this.engine::kadContacts, ApiJson::kadContact)),
                entry("listKadSearches", c -> list(c, this.engine::kadSearches, ApiJson::kadSearch)),
                entry("connectKad", c -> done(c, this.engine.connectKad())),
                entry("disconnectKad", c -> done(c, this.engine.disconnectKad())),
                entry("recheckKadFirewall", c -> allowed(c, this.engine.recheckKadFirewall(), "Kad is not connected.")),
                entry("bootstrapKad", c -> done(c, this.engine.kadBootstrap(address(text(c.body(), ADDRESS_KEY))))),
                entry("importKadNodes", c -> count(c, this.engine.addKadContacts(url(c)))),
                entry("startSearch", this::startSearch),
                entry("getSearchResults", this::getSearchResults),
                entry("stopSearch", this::stopSearch),
                entry("moreSearchResults", this::moreSearchResults),
                entry("downloadSearchResult", this::downloadSearchResult),
                entry("listCategories", c -> list(c, this.engine::categories, ApiJson::category)),
                entry("listFriends", c -> list(c, this.engine::friends, ApiJson::friend)),
                entry(
                        "sendMessage",
                        c -> done(c, this.engine.sendMessage(address(text(c.body(), "to")), text(c.body(), "text")))),
                entry("listBans", c -> list(c, this.engine::bans, ApiJson::ban)),
                entry("getUpdate", c -> reply(c.exchange(), 200, g -> ApiJson.update(g, this.engine.updateStatus()))),
                entry("checkForUpdate", this::checkForUpdate),
                entry("skipUpdate", c -> {
                    UpdateStatus after = await(this.engine.skipUpdate(text(c.body(), "version")));
                    reply(c.exchange(), 200, g -> ApiJson.update(g, after));
                }),
                entry("unban", c -> known(c, this.engine.unban(text(c.parameters(), ADDRESS_KEY)))));
    }

    /** Answers a request whose path starts with {@value #PREFIX}. */
    public void handle(Exchange exchange) throws IOException {
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

    /** Ends the open event streams; the host stops its server afterwards. */
    @Override
    public void close() {
        this.streams.close();
    }

    // No Host check: browsers never send the token by themselves, so a page reaching us through DNS rebinding cannot
    // act, and other computers may use any name of this one.
    private void route(Exchange exchange) throws IOException {
        authorize(exchange);
        List<String> segments = segments(exchange.rawPath());
        ApiSpecification.Route route = this.specification.route(segments).orElseThrow(RemoteApi::notFound);
        ApiSpecification.Operation operation = route.operation(exchange.method())
                .orElseThrow(() -> {
                    exchange.setHeader("Allow", route.allowed());
                    return new Failure(405, "Method not allowed.");
                });
        Map<String, JsonNode> parameters = operation.arguments(route.values(segments), exchange.query());
        JsonNode body = operation.body().isPresent() ? operation.body(read(exchange)) : null;
        this.handlers.get(operation.id()).handle(new Call(exchange, parameters, body));
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
        InetAddress client = exchange.clientAddress();
        exchange.setHeader("WWW-Authenticate", "Bearer");
        if (!this.guard.mayTry(client)) {
            throw new Failure(429, "Too many attempts: wait a minute.");
        }
        if (given != null) {
            logger.warn("Wrong API token from {}", client.getHostAddress());
        }
        throw new Failure(401, given == null ? "Send the API token as a bearer token." : "Wrong token.");
    }

    // Operations.

    private void getApi(Call c) throws IOException {
        reply(c.exchange(), 200, g -> {
            g.writeStartObject();
            g.writeStringProperty("name", NjMuleInfo.NAME);
            g.writeStringProperty("version", NjMuleInfo.version());
            g.writeNumberProperty("api", VERSION);
            g.writeEndObject();
        });
    }

    private void getStatus(Call c) throws IOException {
        var status = await(this.engine.status());
        reply(c.exchange(), 200, g -> ApiJson.status(g, status));
    }

    private void setLimits(Call c) throws IOException {
        JsonNode body = c.body();
        Preferences now = await(this.engine.updatePreferences(p -> {
            Preferences.Builder b = p.toBuilder();
            if (body.has("uploadKiBps")) {
                b.maxUploadKiBps(number(body, "uploadKiBps"));
            }
            if (body.has("downloadKiBps")) {
                b.maxDownloadKiBps(number(body, "downloadKiBps"));
            }
            return b.build();
        }));
        limits(c, now);
    }

    private void limits(Call c, Preferences now) throws IOException {
        reply(c.exchange(), 200, g -> {
            g.writeStartObject();
            g.writeNumberProperty("uploadKiBps", now.maxUploadKiBps());
            g.writeNumberProperty("downloadKiBps", now.maxDownloadKiBps());
            g.writeEndObject();
        });
    }

    private void getLog(Call c) throws IOException {
        JsonNode after = c.parameters().get("after");
        List<RecentLog.Entry> lines = this.log.after(after == null ? 0 : after.asLong());
        reply(c.exchange(), 200, g -> ApiJson.array(g, lines, (w, e) -> ApiJson.log(w, e.sequence(), e.line())));
    }

    private void streamEvents(Call c) throws IOException {
        List<String> wanted = new ArrayList<>();
        JsonNode types = c.parameters().get("types");
        if (types != null) {
            types.values().forEach(t -> wanted.add(t.asString()));
        }
        if (!this.streams.serve(c.exchange(), wanted)) {
            throw new Failure(503, "Too many event streams.");
        }
    }

    private void shutdown(Call c) throws IOException {
        Runnable stop = this.exit.orElseThrow(() -> new Failure(501, "This njMule cannot be stopped from here."));
        ok(c.exchange(), 202);
        logger.info("Exiting as the remote-control API asked");
        // Out of the server's threads, which the exit stops.
        Thread.ofVirtual().name("njmule-api-exit").start(stop);
    }

    private void addDownloads(Call c) throws IOException {
        JsonNode given = c.body().get("links");
        List<String> texts = new ArrayList<>();
        if (given.isArray()) {
            given.values().forEach(l -> texts.add(l.asString()));
        } else {
            texts.add(given.asString());
        }
        List<Ed2kLink.FileLink> links = new ArrayList<>();
        for (String text : texts) {
            if (!(Ed2kLink.tryParse(text.strip()).orElse(null) instanceof Ed2kLink.FileLink link)) {
                throw new Failure(400, "Not an eD2K file link: " + text);
            }
            links.add(link);
        }
        Optional<Integer> category =
                c.body().has(CATEGORY_KEY) ? Optional.of(number(c.body(), CATEGORY_KEY)) : Optional.empty();
        List<DownloadInfo> added = new ArrayList<>();
        for (Ed2kLink.FileLink link : links) {
            DownloadInfo d = await(this.engine.addDownload(link));
            if (category.isPresent()) {
                await(this.engine.setDownloadCategory(d.hash(), category.get()));
            }
            added.add(d);
        }
        reply(c.exchange(), 201, g -> ApiJson.array(g, added, ApiJson::download));
    }

    private void addServer(Call c) throws IOException {
        if (!await(this.engine.addServer(address(text(c.body(), ADDRESS_KEY))))) {
            throw new Failure(409, "That server is known already.");
        }
        ok(c.exchange(), 201);
    }

    private void connectServer(Call c) throws IOException {
        Optional<ServerAddress> server =
                c.body().has(ADDRESS_KEY) ? Optional.of(address(text(c.body(), ADDRESS_KEY))) : Optional.empty();
        done(c, this.engine.connect(server));
    }

    private void getKad(Call c) throws IOException {
        var kad = await(this.engine.kad());
        reply(c.exchange(), 200, g -> {
            if (kad.isPresent()) {
                ApiJson.kad(g, kad.get());
            } else {
                g.writeNull();
            }
        });
    }

    // A check that takes longer than a call waits for goes on: the caller reads its outcome later.
    private void checkForUpdate(Call c) throws IOException {
        CompletableFuture<UpdateStatus> check = this.engine.checkForUpdate();
        UpdateStatus status;
        try {
            status = check.get(ENGINE_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(503, "Interrupted.");
        } catch (ExecutionException e) {
            throw new Failure(
                    503, "The engine cannot do it now: " + e.getCause().getMessage());
        } catch (TimeoutException e) {
            status = this.engine.updateStatus();
        }
        UpdateStatus answer = status;
        reply(c.exchange(), answer.checking() ? 202 : 200, g -> ApiJson.update(g, answer));
    }

    private void startSearch(Call c) throws IOException {
        String expression = text(c.body(), "query").strip();
        SearchRequest.checkExpression(expression);
        SearchRequest.Scope scope = c.body().has("scope")
                ? enumValue(SearchRequest.Scope.class, text(c.body(), "scope"))
                : SearchRequest.Scope.SERVER;
        SearchId id = await(this.engine.search(new SearchRequest(expression, scope)));
        this.searches.started(id.value());
        reply(c.exchange(), 201, g -> {
            g.writeStartObject();
            g.writeNumberProperty("id", id.value());
            g.writeEndObject();
        });
    }

    private void getSearchResults(Call c) throws IOException {
        long id = searchId(c);
        List<SearchResult> found = this.searches.results(id).orElseThrow(RemoteApi::notFound);
        found.sort((a, b) -> Integer.compare(b.sources(), a.sources()));
        reply(c.exchange(), 200, g -> {
            g.writeStartObject();
            g.writeNumberProperty("id", id);
            g.writeName("results");
            ApiJson.array(g, found, ApiJson::result);
            g.writeEndObject();
        });
    }

    private void stopSearch(Call c) throws IOException {
        long id = searchId(c);
        searchKnown(id);
        await(this.engine.stopSearch(new SearchId(id)));
        this.searches.forget(id);
        ok(c.exchange(), 200);
    }

    private void moreSearchResults(Call c) throws IOException {
        long id = searchId(c);
        searchKnown(id);
        allowed(c, this.engine.moreResults(new SearchId(id)), "That search cannot get more results.");
    }

    private void downloadSearchResult(Call c) throws IOException {
        long id = searchId(c);
        searchKnown(id);
        Hash16 hash = hash(text(c.body(), HASH_KEY));
        SearchResult result = this.searches
                .result(id, hash)
                .orElseThrow(() -> new Failure(404, "That result is gone: search again."));
        DownloadInfo d = await(this.engine.addDownload(result.link()));
        reply(c.exchange(), 201, g -> ApiJson.download(g, d));
    }

    private void searchKnown(long id) {
        if (this.searches.results(id).isEmpty()) {
            throw notFound();
        }
    }

    // Answers shared by the operations.

    private <T> void list(Call c, Supplier<CompletableFuture<List<T>>> items, ApiJson.Writer<T> each)
            throws IOException {
        List<T> all = await(items.get());
        reply(c.exchange(), 200, g -> ApiJson.array(g, all, each));
    }

    private void download(Call c, int status) throws IOException {
        Hash16 hash = hash(c);
        DownloadInfo d = await(this.engine.downloads()).stream()
                .filter(i -> i.hash().equals(hash))
                .findFirst()
                .orElseThrow(RemoteApi::notFound);
        reply(c.exchange(), status, g -> ApiJson.download(g, d));
    }

    // A change to a download: unknown ones are not found, the others are answered as they are now.
    private void changed(Call c, CompletableFuture<Boolean> change) throws IOException {
        if (!await(change)) {
            throw notFound();
        }
        download(c, 200);
    }

    private void known(Call c, CompletableFuture<Boolean> work) throws IOException {
        if (!await(work)) {
            throw notFound();
        }
        ok(c.exchange(), 200);
    }

    private void allowed(Call c, CompletableFuture<Boolean> work, String refusal) throws IOException {
        if (!await(work)) {
            throw new Failure(409, refusal);
        }
        ok(c.exchange(), 200);
    }

    private void done(Call c, CompletableFuture<?> work) throws IOException {
        await(work);
        ok(c.exchange(), 200);
    }

    private void count(Call c, CompletableFuture<Integer> work) throws IOException {
        int added = await(work);
        reply(c.exchange(), 200, g -> {
            g.writeStartObject();
            g.writeNumberProperty("added", added);
            g.writeEndObject();
        });
    }

    // Values of the request; the document's schemas have checked their types already.

    private static byte[] read(Exchange exchange) throws IOException {
        try (InputStream in = exchange.requestBody()) {
            byte[] body = in.readNBytes(ApiSpecification.MAX_BODY + 1);
            if (body.length > ApiSpecification.MAX_BODY) {
                throw new Failure(413, "The body is too large.");
            }
            return body;
        }
    }

    private static String text(JsonNode values, String name) {
        JsonNode value = values.get(name);
        if (value == null || !value.isString()) {
            throw new Failure(400, "Missing text: " + name);
        }
        return value.asString();
    }

    private static String text(Map<String, JsonNode> parameters, String name) {
        JsonNode value = parameters.get(name);
        if (value == null) {
            throw new Failure(400, "Missing parameter: " + name);
        }
        return value.asString();
    }

    private static int number(JsonNode values, String name) {
        JsonNode value = values.get(name);
        if (value == null || !value.canConvertToInt()) {
            throw new Failure(400, "Missing number: " + name);
        }
        return value.intValue();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String name) {
        try {
            return Enum.valueOf(type, name.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new Failure(400, "Unknown value: " + name);
        }
    }

    private static Hash16 hash(Call c) {
        return hash(text(c.parameters(), HASH_KEY));
    }

    private static Hash16 hash(String text) {
        try {
            return Hash16.parseHex(text);
        } catch (IllegalArgumentException e) {
            throw new Failure(400, "Not a file hash: " + text);
        }
    }

    private static long searchId(Call c) {
        return c.parameters().get("id").asLong();
    }

    private static ServerAddress address(String text) {
        try {
            return ServerAddress.parse(text.strip());
        } catch (IllegalArgumentException e) {
            throw new Failure(400, "Not a host:port address: " + text);
        }
    }

    private static URI url(Call c) {
        String text = text(c.body(), URL_KEY);
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

    // Answers.

    private void reply(Exchange exchange, int status, Consumer<JsonGenerator> body) throws IOException {
        send(exchange, status, JSON, ApiJson.write(body));
    }

    private void ok(Exchange exchange, int status) throws IOException {
        reply(exchange, status, g -> {
            g.writeStartObject();
            g.writeBooleanProperty("ok", true);
            g.writeEndObject();
        });
    }

    private void error(Exchange exchange, int status, String message) throws IOException {
        if (!exchange.started()) {
            send(exchange, status, JSON, ApiJson.write(g -> {
                g.writeStartObject();
                g.writeStringProperty("error", message);
                g.writeEndObject();
            }));
        }
    }

    private static void send(Exchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.setHeader("Content-Type", type);
        exchange.setHeader("Cache-Control", "no-store");
        exchange.setHeader("X-Content-Type-Options", "nosniff");
        exchange.sendHeaders(status, body.length);
        try (OutputStream out = exchange.responseBody()) {
            out.write(body);
        }
    }

    private static Failure notFound() {
        return new Failure(404, "Not found.");
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

    private static String difference(Set<String> a, Set<String> b) {
        TreeSet<String> all = new TreeSet<>(a);
        all.addAll(b);
        all.removeIf(id -> a.contains(id) && b.contains(id));
        return all.toString();
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
