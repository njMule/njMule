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
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
import org.njmule.core.api.Category;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.FileComment;
import org.njmule.core.api.Friend;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.NjMuleInfo;
import org.njmule.core.api.PortTest;
import org.njmule.core.api.SearchId;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.api.SharedDirectory;
import org.njmule.core.api.UpdateStatus;
import org.njmule.core.api.UploadPriority;
import org.njmule.core.config.Preferences;
import org.njmule.core.persist.PreferencesCodec;
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
    private static final String PROXY_PASSWORD = "proxy.password";

    private final NjMuleEngine engine;
    private final Guard guard;
    private final SearchCollector searches;
    private final RecentLog log;
    private final Conversations conversations;
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
        this.guard = guard;
        this.searches = recent.searches();
        this.log = recent.log();
        this.conversations = recent.conversations();
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
                entry("getPreferences", c -> preferences(c, this.engine.preferences())),
                entry("updatePreferences", this::updatePreferences),
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
                entry("restoreDownload", c -> changed(c, this.engine.restoreDownload(hash(c)))),
                entry("getDownloadParts", this::getDownloadParts),
                entry(
                        "listPeerComments",
                        c -> list(
                                c,
                                () -> this.engine.peerComments(hash(c)),
                                (g, p) -> ApiJson.comment(g, p.user(), p.fileName(), p.comment()))),
                entry("getFileComment", c -> {
                    Optional<FileComment> mine = await(this.engine.fileComment(hash(c)));
                    reply(c.exchange(), 200, g -> {
                        FileComment f = mine.orElse(FileComment.of("", 0));
                        g.writeStartObject();
                        g.writeNumberProperty("rating", f.rating());
                        g.writeStringProperty("text", f.text());
                        g.writeEndObject();
                    });
                }),
                entry("setFileComment", c -> {
                    FileComment f = FileComment.of(c.body().path("text").asString(""), number(c.body(), "rating"));
                    done(c, this.engine.setFileComment(hash(c), f));
                }),
                entry(
                        "listKadNotes",
                        c -> list(
                                c,
                                () -> this.engine.kadNotes(hash(c)),
                                (g, n) -> ApiJson.comment(g, n.publisher(), n.fileName(), n.comment()))),
                entry(
                        "searchKadNotes",
                        c -> list(
                                c,
                                () -> this.engine.searchKadNotes(hash(c)),
                                (g, n) -> ApiJson.comment(g, n.publisher(), n.fileName(), n.comment()))),
                entry(
                        "setDownloadPreview",
                        c -> changed(c, this.engine.setDownloadPreview(hash(c), flag(c.body(), "previewFirst")))),
                entry(
                        "setDownloadAutoSwap",
                        c -> changed(c, this.engine.setDownloadAutoSwap(hash(c), flag(c.body(), "autoSwap")))),
                entry(
                        "swapSources",
                        c -> count(
                                c,
                                text(c.body(), "direction").equals("here")
                                        ? this.engine.swapSourcesToDownload(hash(c))
                                        : this.engine.swapSourcesFromDownload(hash(c)))),
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
                entry("shareDirectory", this::shareDirectory),
                entry("unshareDirectory", this::unshareDirectory),
                entry("renameSharedFile", c -> known(c, this.engine.renameSharedFile(hash(c), text(c.body(), "name")))),
                entry(
                        "setUploadPriority",
                        c -> known(
                                c,
                                this.engine.setUploadPriority(
                                        hash(c), enumValue(UploadPriority.class, text(c.body(), PRIORITY_KEY))))),
                entry("listServers", c -> list(c, this.engine::servers, ApiJson::server)),
                entry(
                        "setServerPriority",
                        c -> known(
                                c,
                                this.engine.setServerPriority(
                                        address(text(c.parameters(), ADDRESS_KEY)),
                                        enumValue(ServerInfo.Priority.class, text(c.body(), PRIORITY_KEY))))),
                entry("setStaticServer", c -> {
                    JsonNode on = c.body().get("static");
                    if (on == null || !on.isBoolean()) {
                        throw new Failure(400, "Missing truth value: static");
                    }
                    known(c, this.engine.setStaticServer(address(text(c.parameters(), ADDRESS_KEY)), on.asBoolean()));
                }),
                entry(
                        "testPorts",
                        c -> reply(c.exchange(), 202, g -> ApiJson.portTest(g, await(this.engine.testPorts())))),
                entry("getPortTest", c -> {
                    Optional<PortTest> test = await(this.engine.portTest());
                    reply(c.exchange(), 200, g -> {
                        if (test.isPresent()) {
                            ApiJson.portTest(g, test.get());
                        } else {
                            g.writeNull();
                        }
                    });
                }),
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
                entry("markSpam", this::markSpam),
                entry("listCategories", c -> list(c, this.engine::categories, ApiJson::category)),
                entry("setCategories", this::setCategories),
                entry("updateIpFilterLists", this::updateIpFilterLists),
                entry(
                        "getServerLists",
                        c -> reply(c.exchange(), 200, g -> addresses(g, await(this.engine.serverListAddresses())))),
                entry("setServerLists", this::setServerLists),
                entry("listFriends", c -> list(c, this.engine::friends, ApiJson::friend)),
                entry("addFriend", this::addFriend),
                entry("removeFriend", c -> known(c, this.engine.removeFriend(hash(text(c.parameters(), HASH_KEY))))),
                entry("getFriendSlot", this::getFriendSlot),
                entry("setFriendSlot", this::setFriendSlot),
                entry("sendMessage", this::sendMessage),
                entry(
                        "listConversations",
                        c -> reply(c.exchange(), 200, g -> ApiJson.array(g, this.conversations.all(), ApiJson::chat))),
                entry("closeConversation", c -> {
                    if (!this.conversations.close(text(c.parameters(), ADDRESS_KEY))) {
                        throw notFound();
                    }
                    ok(c.exchange(), 200);
                }),
                entry("listBans", c -> list(c, this.engine::bans, ApiJson::ban)),
                entry("getUpdate", c -> reply(c.exchange(), 200, g -> ApiJson.update(g, this.engine.updateStatus()))),
                entry("checkForUpdate", this::checkForUpdate),
                entry("skipUpdate", c -> {
                    UpdateStatus after = await(this.engine.skipUpdate(text(c.body(), "version")));
                    reply(c.exchange(), 200, g -> ApiJson.update(g, after));
                }),
                entry("downloadUpdate", c -> updateWork(c, this.engine.downloadUpdate())),
                entry("installUpdate", c -> {
                    boolean restart = c.body().path("restart").asBoolean(true);
                    updateWork(c, this.engine.installUpdate(restart));
                }),
                entry("unban", c -> known(c, this.engine.unban(text(c.parameters(), ADDRESS_KEY)))));
    }

    /** Answers a request whose path starts with {@value #PREFIX}, from a caller that sends the API token. */
    public void handle(Exchange exchange) throws IOException {
        serve(exchange, true);
    }

    /**
     * Answers a request whose path starts with {@value #PREFIX} from a caller the host has let in already, such as a
     * logged-in browser whose request carried its session's token.
     */
    public void handleTrusted(Exchange exchange) throws IOException {
        serve(exchange, false);
    }

    private void serve(Exchange exchange, boolean needsToken) throws IOException {
        try {
            if (needsToken) {
                authorize(exchange);
            }
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

    // No Host check for token callers: browsers never send the token by themselves, so a page reaching us through DNS
    // rebinding cannot act, and other computers may use any name of this one.
    private void route(Exchange exchange) throws IOException {
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
            token = ApiToken.load(this.engine.directories().config());
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

    // Checked before the engine's loop takes them, so that a wrong value is the caller's error.
    private void updatePreferences(Call c) throws IOException {
        Map<String, String> changes = new LinkedHashMap<>();
        c.body().properties().forEach(e -> changes.put(e.getKey(), e.getValue().asString()));
        PreferencesCodec.apply(this.engine.preferences(), changes);
        preferences(c, await(this.engine.updatePreferences(p -> PreferencesCodec.apply(p, changes))));
    }

    // The proxy password never leaves.
    private void preferences(Call c, Preferences now) throws IOException {
        reply(c.exchange(), 200, g -> {
            g.writeStartObject();
            PreferencesCodec.values(now).forEach((key, value) -> {
                if (!key.equals(PROXY_PASSWORD)) {
                    g.writeStringProperty(key, value);
                }
            });
            g.writeEndObject();
        });
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

    private void shareDirectory(Call c) throws IOException {
        Path dir = folder(text(c.body(), "path"));
        if (SharedDirectory.isSensitive(dir) && !c.body().path("everything").asBoolean(false)) {
            throw new Failure(
                    409, "This folder may hold personal or system files: share it with everything set to true.");
        }
        done(c, this.engine.shareDirectory(dir, c.body().path("subdirectories").asBoolean(true)));
    }

    private void unshareDirectory(Call c) throws IOException {
        String path = text(c.parameters(), "path");
        Path dir = await(this.engine.sharedDirectories()).stream()
                .map(SharedDirectory::path)
                .filter(p -> p.toString().equals(path))
                .findFirst()
                .orElseThrow(RemoteApi::notFound);
        done(c, this.engine.unshareDirectory(dir));
    }

    // An existing folder of this computer, by its absolute path.
    private static Path folder(String text) {
        try {
            Path dir = Path.of(text.strip());
            if (dir.isAbsolute() && Files.isDirectory(dir)) {
                return dir.normalize();
            }
        } catch (InvalidPathException e) {
            // Answered below.
        }
        throw new Failure(400, "Not a folder of this computer: " + text);
    }

    // The line is kept once the engine took the message; a CAPTCHA it answers is then solved.
    private void sendMessage(Call c) throws IOException {
        ServerAddress to = address(text(c.body(), "to"));
        String text = text(c.body(), "text");
        await(this.engine.sendMessage(to, text));
        this.conversations.sent(to.host() + ":" + to.port(), text);
        ok(c.exchange(), 200);
    }

    private void addFriend(Call c) throws IOException {
        Optional<ServerAddress> where =
                c.body().has(ADDRESS_KEY) ? Optional.of(address(text(c.body(), ADDRESS_KEY))) : Optional.empty();
        done(c, this.engine.addFriend(new Friend(hash(text(c.body(), "userHash")), text(c.body(), "name"), where)));
    }

    private void getFriendSlot(Call c) throws IOException {
        Optional<Hash16> slot = await(this.engine.friendSlot());
        reply(c.exchange(), 200, g -> {
            g.writeStartObject();
            if (slot.isPresent()) {
                g.writeStringProperty("userHash", slot.get().toHex());
            } else {
                g.writeNullProperty("userHash");
            }
            g.writeEndObject();
        });
    }

    private void setFriendSlot(Call c) throws IOException {
        JsonNode given = c.body().get("userHash");
        Optional<Hash16> friend =
                given == null || given.isNull() ? Optional.empty() : Optional.of(hash(given.asString()));
        if (!await(this.engine.setFriendSlot(friend))) {
            throw new Failure(404, "Not a friend.");
        }
        getFriendSlot(c);
    }

    private void setCategories(Call c) throws IOException {
        List<Category> all = new ArrayList<>();
        for (JsonNode n : c.body().values()) {
            all.add(new Category(
                    number(n, "id"),
                    text(n, "name"),
                    n.hasNonNull("incoming") ? Optional.of(Path.of(text(n, "incoming"))) : Optional.empty(),
                    n.path("comment").asString(""),
                    n.path("color").asInt(0),
                    n.has(PRIORITY_KEY)
                            ? enumValue(DownloadPriority.class, text(n, PRIORITY_KEY))
                            : DownloadPriority.AUTO,
                    n.path("patterns").asString(""),
                    n.path("regex").asBoolean(false)));
        }
        await(this.engine.setCategories(all));
        list(c, this.engine::categories, ApiJson::category);
    }

    // The lists of the preferences, one http or https address a line.
    private void updateIpFilterLists(Call c) throws IOException {
        List<URI> lists = new ArrayList<>();
        this.engine
                .preferences()
                .ipFilterLists()
                .lines()
                .filter(line -> !line.isBlank())
                .forEach(line -> lists.add(webAddress(line)));
        if (lists.isEmpty()) {
            throw new Failure(409, "No IP filter list is set.");
        }
        count(c, this.engine.updateIpFilter(lists));
    }

    private void setServerLists(Call c) throws IOException {
        List<URI> lists = new ArrayList<>();
        for (JsonNode n : c.body().get("addresses").values()) {
            lists.add(webAddress(n.asString()));
        }
        await(this.engine.setServerListAddresses(lists));
        reply(c.exchange(), 200, g -> addresses(g, lists));
    }

    private static void addresses(JsonGenerator g, List<URI> lists) {
        g.writeStartObject();
        g.writeArrayPropertyStart("addresses");
        lists.forEach(u -> g.writeString(u.toString()));
        g.writeEndArray();
        g.writeEndObject();
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

    // A download that takes longer than a call waits for goes on: the caller reads its outcome later. A refusal
    // (nothing
    // to download or install, or not here) is a conflict with the state.
    private void updateWork(Call c, CompletableFuture<UpdateStatus> work) throws IOException {
        UpdateStatus status;
        try {
            status = work.get(ENGINE_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(503, "Interrupted.");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IllegalStateException refused) {
                throw new Failure(409, String.valueOf(refused.getMessage()));
            }
            throw new Failure(
                    503, "The engine cannot do it now: " + e.getCause().getMessage());
        } catch (TimeoutException e) {
            status = this.engine.updateStatus();
        }
        UpdateStatus answer = status;
        boolean goesOn = answer.download().isPresent() || answer.install() == UpdateStatus.Install.RESTART;
        reply(c.exchange(), goesOn ? 202 : 200, g -> ApiJson.update(g, answer));
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
        String asked = c.body().has("scope") ? text(c.body(), "scope") : "server";
        SearchRequest.Scope scope =
                asked.equalsIgnoreCase("automatic") ? automaticScope() : enumValue(SearchRequest.Scope.class, asked);
        SearchId id = await(this.engine.search(new SearchRequest(expression, scope)));
        this.searches.started(id.value());
        reply(c.exchange(), 201, g -> {
            g.writeStartObject();
            g.writeNumberProperty("id", id.value());
            g.writeEndObject();
        });
    }

    // As the desktop app picks it: the network connected, Kad with both unless the server is a static or a big one.
    private SearchRequest.Scope automaticScope() {
        EngineStatus status = await(this.engine.status());
        return SearchRequest.automatic(
                        status.server().isPresent(),
                        status.kad().map(KadInfo::connected).orElse(false),
                        status.server(),
                        await(this.engine.servers()).size())
                .orElseThrow(() -> new Failure(409, "Not connected to a server or to Kad."));
    }

    private void getSearchResults(Call c) throws IOException {
        long id = searchId(c);
        // Rated now, as the files marked since they came make them; spam last.
        List<SearchResult> found = new ArrayList<>(
                await(this.engine.rateSpam(this.searches.results(id).orElseThrow(RemoteApi::notFound))));
        found.sort(Comparator.comparing(SearchResult::isSpam)
                .thenComparing(Comparator.comparingInt(SearchResult::sources).reversed()));
        boolean running = this.searches.running(id);
        reply(c.exchange(), 200, g -> {
            g.writeStartObject();
            g.writeNumberProperty("id", id);
            g.writeBooleanProperty("running", running);
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

    private void markSpam(Call c) throws IOException {
        long id = searchId(c);
        searchKnown(id);
        List<SearchResult> marked = new ArrayList<>();
        for (JsonNode h : c.body().get("hashes").values()) {
            marked.add(this.searches
                    .result(id, hash(h.asString()))
                    .orElseThrow(() -> new Failure(404, "That result is gone: search again.")));
        }
        await(this.engine.markSpam(marked, c.body().path("spam").asBoolean(true)));
        ok(c.exchange(), 200);
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

    private void getDownloadParts(Call c) throws IOException {
        Hash16 hash = hash(c);
        DownloadInfo d = await(this.engine.downloads()).stream()
                .filter(i -> i.hash().equals(hash))
                .findFirst()
                .orElseThrow(RemoteApi::notFound);
        reply(c.exchange(), 200, g -> ApiJson.downloadWithParts(g, d));
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

    private static boolean flag(JsonNode values, String name) {
        JsonNode value = values.get(name);
        if (value == null || !value.isBoolean()) {
            throw new Failure(400, "Missing truth value: " + name);
        }
        return value.asBoolean();
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
        return webAddress(text(c.body(), URL_KEY));
    }

    private static URI webAddress(String text) {
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
