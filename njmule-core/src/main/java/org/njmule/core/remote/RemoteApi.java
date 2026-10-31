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
import java.security.SecureRandom;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.njmule.core.api.Category;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.FileComment;
import org.njmule.core.api.FileHistory;
import org.njmule.core.api.FileNames;
import org.njmule.core.api.Friend;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.ImportOptions;
import org.njmule.core.api.ImportReport;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.KnownClient;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.NjMuleInfo;
import org.njmule.core.api.NotificationResult;
import org.njmule.core.api.PortTest;
import org.njmule.core.api.SearchId;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.api.SharedDirectory;
import org.njmule.core.api.UpdateStatus;
import org.njmule.core.api.UploadPriority;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.config.BindInterface;
import org.njmule.core.config.Preferences;
import org.njmule.core.config.Schedule;
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
    private static final String PAUSED_KEY = "paused";
    private static final String URL_KEY = "url";
    private static final long ENGINE_WAIT_SECONDS = 10;
    // The passwords the preferences hold, which never leave.
    private static final Set<String> SECRETS = Set.of("proxy.password", "notify.smtpPassword");

    // What a guest of the web interface may read: the transfers, the searches made, the servers, Kad and the
    // statistics; never the settings, the log, the chats, the shared files or the files' data.
    private static final Set<String> GUEST_READS = Set.of(
            "getApi",
            "getStatus",
            "getLimits",
            "getRates",
            "streamEvents",
            "listDownloads",
            "getDownload",
            "getDownloadParts",
            "listPeerComments",
            "listSources",
            "listUploads",
            "listServers",
            "getKad",
            "listKadContacts",
            "listKadSearches",
            "listSearches",
            "getSearchResults",
            "listCategories");

    // What only the desktop app's window on njMule's computer may do: a browser's session or a token sent from
    // elsewhere
    // must not open the web interface wider, or take the API token with it.
    private static final Set<String> LOCAL_ONLY = Set.of("updateWebInterface", "getApiToken", "renewApiToken");

    private static final SecureRandom RANDOM = new SecureRandom();

    // The events a guest follows, by the prefixes of their names.
    private static final List<String> GUEST_EVENTS =
            List.of("started", "stopping", "server", "search", "download", "disk");

    private final NjMuleEngine engine;
    private final Guard guard;
    private final SearchCollector searches;
    private final RecentLog log;
    private final Conversations conversations;
    private final RateHistory rates = new RateHistory();
    private final Thread sampler;
    private final EventStreams streams;
    private final Optional<Runnable> exit;
    private final Optional<WebInterface> web;
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
        this(engine, recent, guard, exit, heartbeat, Optional.empty());
    }

    /** As the other constructors, giving and changing the settings of the web interface {@code web}, when given. */
    public RemoteApi(
            NjMuleEngine engine,
            RecentEvents recent,
            Guard guard,
            Optional<Runnable> exit,
            Duration heartbeat,
            Optional<WebInterface> web) {
        this.engine = engine;
        this.guard = guard;
        this.searches = recent.searches();
        this.log = recent.log();
        this.conversations = recent.conversations();
        this.sampler = Thread.ofVirtual().name("njmule-api-rates").start(this::sampleRates);
        this.streams = new EventStreams(engine, heartbeat);
        this.exit = exit;
        this.web = web;
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
    private record Call(Exchange exchange, Map<String, JsonNode> parameters, JsonNode body, Caller caller) {
        boolean guest() {
            return this.caller == Caller.GUEST;
        }
    }

    /** Who calls: a guest, an admin, or the desktop app's window on this computer. */
    private enum Caller {
        GUEST,
        ADMIN,
        LOCAL
    }

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
                entry("getPreferenceDefaults", c -> preferences(c, Preferences.defaults())),
                entry("getPreferencePasswords", this::getPreferencePasswords),
                entry("getFolders", c -> {
                    Preferences p = this.engine.preferences();
                    AppDirectories dirs = this.engine.directories();
                    reply(
                            c.exchange(),
                            200,
                            g -> ApiJson.folders(g, p.effectiveIncomingDir(dirs), p.effectiveTempDir(dirs)));
                }),
                entry("checkFolder", c -> {
                    String kind = pathKind(text(c.body(), "path"));
                    reply(c.exchange(), 200, g -> {
                        g.writeStartObject();
                        g.writeStringProperty("kind", kind);
                        g.writeEndObject();
                    });
                }),
                entry("getRates", this::getRates),
                entry("importEmule", this::importEmule),
                entry("findEmuleProfile", this::findEmuleProfile),
                entry("getSetup", c -> {
                    boolean pending = await(this.engine.setupPending());
                    reply(c.exchange(), 200, g -> {
                        g.writeStartObject();
                        g.writeBooleanProperty("pending", pending);
                        g.writeEndObject();
                    });
                }),
                entry("finishSetup", c -> done(c, this.engine.finishSetup())),
                entry(
                        "getSchedule",
                        c -> reply(
                                c.exchange(),
                                200,
                                g -> ApiJson.schedule(g, this.engine.schedule(), this.engine.activeRule()))),
                entry("setSchedule", this::setSchedule),
                entry("updatePreferences", this::updatePreferences),
                entry("setLimits", this::setLimits),
                entry("updateIpFilter", c -> count(c, this.engine.updateIpFilter(url(c)))),
                entry("getLog", this::getLog),
                entry("streamEvents", this::streamEvents),
                entry("shutdown", this::shutdown),
                entry("listDownloads", this::listDownloads),
                entry("clearCompletedDownloads", c -> count(c, "removed", this.engine.clearCompletedDownloads())),
                entry("clearCompletedDownload", c -> known(c, this.engine.clearCompletedDownload(hash(c)))),
                entry("addDownloads", this::addDownloads),
                entry("getDownload", c -> download(c, 200)),
                entry("cancelDownload", c -> known(c, this.engine.cancelDownload(hash(c)))),
                entry("pauseDownload", c -> changed(c, this.engine.pauseDownload(hash(c)))),
                entry("resumeDownload", c -> changed(c, this.engine.resumeDownload(hash(c)))),
                entry("stopDownload", c -> changed(c, this.engine.stopDownload(hash(c)))),
                entry("restoreDownload", c -> changed(c, this.engine.restoreDownload(hash(c)))),
                entry("getDownloadParts", this::getDownloadParts),
                entry("getDownloadData", this::getDownloadData),
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
                entry("cleanUpName", this::cleanUpName),
                entry(
                        "addDownloadSource",
                        c -> changed(c, this.engine.addSource(hash(c), address(text(c.body(), ADDRESS_KEY))))),
                entry("swapSourceToDownload", this::swapSourceToDownload),
                entry("listSavedSearches", c -> list(c, this.engine::savedSearches, ApiJson::savedSearch)),
                entry("saveSearches", this::saveSearches),
                entry(
                        "getSpamMarks",
                        c -> reply(c.exchange(), 200, g -> ApiJson.spamMarks(g, await(this.engine.spamMarks())))),
                entry(
                        "helloClient",
                        c -> reply(
                                c.exchange(),
                                200,
                                g -> ApiJson.peer(
                                        g, await(this.engine.hello(address(text(c.parameters(), ADDRESS_KEY))))))),
                entry("listSources", c -> list(c, this.engine::sources, ApiJson::source)),
                entry("listUploads", c -> list(c, this.engine::uploads, ApiJson::upload)),
                entry(
                        "browseClient",
                        c -> list(
                                c,
                                () -> this.engine.browseSharedFiles(address(text(c.parameters(), ADDRESS_KEY))),
                                ApiJson::sharedByClient)),
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
                entry("testPorts", this::testPorts),
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
                entry(
                        "importServers",
                        c -> count(
                                c,
                                fromList(
                                        c, this.engine::addServers, this.engine::addServers, this.engine::addServers))),
                entry(
                        "removeServer",
                        c -> known(c, this.engine.removeServer(address(text(c.parameters(), ADDRESS_KEY))))),
                entry("getKad", this::getKad),
                entry("testNotifications", this::testNotifications),
                entry("listKnownClients", this::listKnownClients),
                entry("listKadContacts", c -> list(c, this.engine::kadContacts, ApiJson::kadContact)),
                entry("listKadSearches", c -> list(c, this.engine::kadSearches, ApiJson::kadSearch)),
                entry("connectKad", c -> done(c, this.engine.connectKad())),
                entry("disconnectKad", c -> done(c, this.engine.disconnectKad())),
                entry("recheckKadFirewall", c -> allowed(c, this.engine.recheckKadFirewall(), "Kad is not connected.")),
                entry("bootstrapKad", c -> done(c, this.engine.kadBootstrap(address(text(c.body(), ADDRESS_KEY))))),
                entry(
                        "importKadNodes",
                        c -> count(
                                c,
                                fromList(
                                        c,
                                        this.engine::addKadContacts,
                                        this.engine::addKadContacts,
                                        this.engine::addKadContacts))),
                entry("startSearch", this::startSearch),
                entry("checkSearch", c -> {
                    SearchRequest.checkExpression(text(c.body(), "query").strip());
                    ok(c.exchange(), 200);
                }),
                entry("listSearches", this::listSearches),
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
                entry("removeFriend", c -> known(c, this.engine.removeFriend(hash(text(c.parameters(), "userHash"))))),
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
                entry(
                        "listNetworkInterfaces",
                        c -> reply(c.exchange(), 200, g -> ApiJson.array(g, BindInterface.choices(), ApiJson::nic))),
                entry("getWebInterface", c -> webSettings(c, webInterface().settings())),
                entry("updateWebInterface", this::updateWebInterface),
                entry(
                        "getApiToken",
                        c -> apiToken(
                                c,
                                ApiToken.loadOrCreate(this.engine.directories().config(), RANDOM))),
                entry(
                        "renewApiToken",
                        c -> apiToken(
                                c, ApiToken.renew(this.engine.directories().config(), RANDOM))),
                entry("unban", c -> known(c, this.engine.unban(text(c.parameters(), ADDRESS_KEY)))));
    }

    /** Answers a request whose path starts with {@value #PREFIX}, from a caller that sends the API token. */
    public void handle(Exchange exchange) throws IOException {
        serve(exchange, true, Caller.ADMIN);
    }

    /**
     * Answers a request whose path starts with {@value #PREFIX} from a caller the host has let in already, such as a
     * logged-in browser whose request carried its session's token.
     */
    public void handleTrusted(Exchange exchange) throws IOException {
        serve(exchange, false, Caller.ADMIN);
    }

    /**
     * Answers the desktop app's window on this computer, which the host has let in with the token it made for it: every
     * operation, the changes of the web interface's settings included.
     */
    public void handleLocal(Exchange exchange) throws IOException {
        serve(exchange, false, Caller.LOCAL);
    }

    /**
     * Answers a guest the host has let in: the reads of the transfers, the searches, the servers, Kad and the
     * statistics only, with their events.
     */
    public void handleGuest(Exchange exchange) throws IOException {
        serve(exchange, false, Caller.GUEST);
    }

    private void serve(Exchange exchange, boolean needsToken, Caller caller) throws IOException {
        try {
            if (needsToken) {
                authorize(exchange);
            }
            route(exchange, caller);
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
        this.sampler.interrupt();
        this.streams.close();
    }

    // No Host check for token callers: browsers never send the token by themselves, so a page reaching us through DNS
    // rebinding cannot act, and other computers may use any name of this one.
    private void route(Exchange exchange, Caller caller) throws IOException {
        List<String> segments = segments(exchange.rawPath());
        ApiSpecification.Route route = this.specification.route(segments).orElseThrow(RemoteApi::notFound);
        ApiSpecification.Operation operation = route.operation(exchange.method())
                .orElseThrow(() -> {
                    exchange.setHeader("Allow", route.allowed());
                    return new Failure(405, "Method not allowed.");
                });
        boolean guest = caller == Caller.GUEST;
        if (guest && !GUEST_READS.contains(operation.id())) {
            throw new Failure(403, "A guest can only look at the transfers, searches, servers, Kad and statistics.");
        }
        if (caller != Caller.LOCAL && LOCAL_ONLY.contains(operation.id())) {
            throw new Failure(403, "Only the desktop app's window on njMule's computer can do this.");
        }
        Map<String, JsonNode> parameters = operation.arguments(route.values(segments), exchange.query());
        JsonNode body = operation.body().isPresent() ? operation.body(read(exchange)) : null;
        this.handlers.get(operation.id()).handle(new Call(exchange, parameters, body, caller));
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
            // What a report of a problem needs, for those who may change things only.
            if (!c.guest()) {
                g.writeStringProperty("java", System.getProperty("java.version"));
                g.writeName("system");
                g.writeStartObject();
                g.writeStringProperty("name", System.getProperty("os.name"));
                g.writeStringProperty("version", System.getProperty("os.version"));
                g.writeStringProperty("arch", System.getProperty("os.arch"));
                g.writeEndObject();
            }
            g.writeEndObject();
        });
    }

    private void getStatus(Call c) throws IOException {
        var status = await(this.engine.status());
        Preferences p = this.engine.preferences();
        OptionalLong incoming = freeSpace(p.effectiveIncomingDir(this.engine.directories()));
        OptionalLong temp = freeSpace(p.effectiveTempDir(this.engine.directories()));
        ApiJson.Reach reach = new ApiJson.Reach(p.upnp(), p.routerTraversal());
        reply(c.exchange(), 200, g -> ApiJson.status(g, status, incoming, temp, Optional.of(reach)));
    }

    private static OptionalLong freeSpace(Path dir) {
        try {
            return OptionalLong.of(Files.getFileStore(dir).getUsableSpace());
        } catch (IOException | RuntimeException e) {
            return OptionalLong.empty();
        }
    }

    // The rates of each second, for the front ends that draw an hour or a day of them; a status that does not come
    // in time leaves its second out.
    private void sampleRates() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                EngineStatus s = this.engine.status().get(ENGINE_WAIT_SECONDS, TimeUnit.SECONDS);
                this.rates.add(s.downloadBytesPerSecond(), s.uploadBytesPerSecond());
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                logger.trace("No rates this second: {}", e.toString());
                pause();
            }
        }
    }

    private static void pause() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void getRates(Call c) throws IOException {
        JsonNode span = c.parameters().get("span");
        int seconds = span == null ? 600 : span.asInt();
        if (seconds > RateHistory.SECONDS_KEPT) {
            reply(c.exchange(), 200, g -> ApiJson.rates(g, 60, this.rates.minutes(seconds / 60)));
        } else {
            reply(c.exchange(), 200, g -> ApiJson.rates(g, 1, this.rates.seconds(seconds)));
        }
    }

    // Only reads eMule's folder; a check changes nothing.
    private void importEmule(Call c) throws IOException {
        ImportOptions options = new ImportOptions(
                c.body().path("dryRun").asBoolean(true),
                c.body().path("identity").asBoolean(false),
                c.body().path("downloads").asBoolean(false));
        ImportReport report = await(this.engine.importFromEmule(folder(text(c.body(), "path")), options));
        reply(c.exchange(), 200, g -> ApiJson.importReport(g, report));
    }

    private void findEmuleProfile(Call c) throws IOException {
        Optional<Path> profile = await(this.engine.emuleProfile());
        reply(c.exchange(), 200, g -> {
            g.writeStartObject();
            profile.ifPresent(p -> g.writeStringProperty("path", p.toString()));
            g.writeEndObject();
        });
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

    private void setSchedule(Call c) throws IOException {
        List<Schedule.Rule> rules = new ArrayList<>();
        for (JsonNode r : c.body().get("rules").values()) {
            Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
            r.path("days").values().forEach(d -> days.add(enumValue(DayOfWeek.class, d.asString())));
            try {
                rules.add(new Schedule.Rule(
                        r.path("name").asString(""),
                        r.path("enabled").asBoolean(true),
                        days,
                        LocalTime.parse(text(r, "start")),
                        LocalTime.parse(text(r, "end")),
                        optionalInt(r, "uploadKiBps"),
                        optionalInt(r, "downloadKiBps"),
                        optionalInt(r, "maxConnections"),
                        optionalInt(r, "maxSourcesPerFile"),
                        optionalInt(r, "stopCategory"),
                        optionalInt(r, "resumeCategory")));
            } catch (DateTimeParseException e) {
                throw new Failure(400, "Not a time of the form hh:mm: " + e.getParsedString());
            }
        }
        Schedule now = await(this.engine.updateSchedule(new Schedule(rules)));
        reply(c.exchange(), 200, g -> ApiJson.schedule(g, now, this.engine.activeRule()));
    }

    private static OptionalInt optionalInt(JsonNode values, String name) {
        return values.hasNonNull(name) ? OptionalInt.of(number(values, name)) : OptionalInt.empty();
    }

    // Checked before the engine's loop takes them, so that a wrong value is the caller's error.
    private void updatePreferences(Call c) throws IOException {
        Map<String, String> changes = new LinkedHashMap<>();
        c.body().properties().forEach(e -> changes.put(e.getKey(), e.getValue().asString()));
        PreferencesCodec.apply(this.engine.preferences(), changes);
        preferences(c, await(this.engine.updatePreferences(p -> PreferencesCodec.apply(p, changes))));
    }

    private void preferences(Call c, Preferences now) throws IOException {
        reply(c.exchange(), 200, g -> {
            g.writeStartObject();
            PreferencesCodec.values(now).forEach((key, value) -> {
                if (!SECRETS.contains(key)) {
                    g.writeStringProperty(key, value);
                }
            });
            g.writeEndObject();
        });
    }

    // Whether each is set, so that a form shows one is without ever holding it.
    private void getPreferencePasswords(Call c) throws IOException {
        Map<String, String> values = PreferencesCodec.values(this.engine.preferences());
        reply(c.exchange(), 200, g -> {
            g.writeStartObject();
            new TreeSet<>(SECRETS)
                    .forEach(key -> g.writeBooleanProperty(
                            key, !values.getOrDefault(key, "").isEmpty()));
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

    // The tabs to show again at the next start, in place of those kept before.
    private void saveSearches(Call c) throws IOException {
        await(this.engine.saveSearches(ApiReader.list(c.body(), ApiReader::savedSearch)));
        ok(c.exchange(), 200);
    }

    private void swapSourceToDownload(Call c) throws IOException {
        boolean moved = await(this.engine.swapSourceToDownload(hash(c), address(text(c.body(), ADDRESS_KEY))));
        reply(c.exchange(), 200, g -> {
            g.writeStartObject();
            g.writeBooleanProperty("moved", moved);
            g.writeEndObject();
        });
    }

    // The words taken out are those of the preferences, as for the names of new downloads.
    private void cleanUpName(Call c) throws IOException {
        String clean = FileNames.cleanUp(
                text(c.body(), "name"), this.engine.preferences().nameCleanups());
        reply(c.exchange(), 200, g -> {
            g.writeStartObject();
            g.writeStringProperty("name", clean);
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
        if (c.guest()) {
            guestEvents(wanted);
        }
        if (!this.streams.serve(c.exchange(), wanted)) {
            throw new Failure(503, "Too many event streams.");
        }
    }

    // All the guest's events when it asks for all, else those it asks for among them.
    private static void guestEvents(List<String> wanted) {
        if (wanted.isEmpty()) {
            wanted.addAll(GUEST_EVENTS);
            return;
        }
        wanted.removeIf(w -> GUEST_EVENTS.stream().noneMatch(w::startsWith));
        if (wanted.isEmpty()) {
            throw new Failure(403, "A guest cannot follow these events.");
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
        Optional<Boolean> paused =
                c.body().has(PAUSED_KEY) ? Optional.of(flag(c.body(), PAUSED_KEY)) : Optional.empty();
        List<DownloadInfo> added = new ArrayList<>();
        for (Ed2kLink.FileLink link : links) {
            DownloadInfo d = paused.isPresent()
                    ? await(this.engine.addDownload(link, OptionalInt.empty(), paused.get()))
                    : await(this.engine.addDownload(link));
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

    // What a path typed names here: a relative one is taken from where njMule runs, as the preferences take it.
    private static String pathKind(String text) {
        Path path;
        try {
            path = Path.of(text.strip()).toAbsolutePath();
        } catch (InvalidPathException e) {
            return "invalid";
        }
        if (Files.isDirectory(path)) {
            return "folder";
        }
        return Files.exists(path) ? "file" : "missing";
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

    private WebInterface webInterface() {
        return this.web.orElseThrow(() -> new Failure(501, "This njMule has no web interface."));
    }

    // With whether this caller may change them, so that a form offers the changes only where they are taken.
    private void webSettings(Call c, WebInterface.Settings now) throws IOException {
        reply(c.exchange(), 200, g -> ApiJson.webInterface(g, now, c.caller() == Caller.LOCAL));
    }

    // What the body names changes; a wrong value changes nothing.
    private void updateWebInterface(Call c) throws IOException {
        JsonNode b = c.body();
        WebInterface.Change change = new WebInterface.Change(
                optionalFlag(b, "enabled"),
                b.has("port") ? Optional.of(number(b, "port")) : Optional.empty(),
                optionalFlag(b, "lan"),
                optionalTexts(b, "hosts"),
                optionalTexts(b, "proxies"),
                optionalFlag(b, "api"),
                b.has("password") ? Optional.of(text(b, "password")) : Optional.empty(),
                b.has("guestPassword") ? Optional.of(text(b, "guestPassword")) : Optional.empty());
        webSettings(c, webInterface().change(change));
    }

    private static Optional<Boolean> optionalFlag(JsonNode values, String name) {
        return values.has(name) ? Optional.of(flag(values, name)) : Optional.empty();
    }

    private static Optional<List<String>> optionalTexts(JsonNode values, String name) {
        if (!values.has(name)) {
            return Optional.empty();
        }
        List<String> texts = new ArrayList<>();
        values.get(name).values().forEach(v -> texts.add(v.asString()));
        return Optional.of(texts);
    }

    private void apiToken(Call c, String token) throws IOException {
        reply(c.exchange(), 200, g -> {
            g.writeStartObject();
            g.writeStringProperty("token", token);
            g.writeEndObject();
        });
    }

    // A list from the web by its address, a file of this computer by its path, or the content of a file sent.
    private static CompletableFuture<Integer> fromList(
            Call c,
            Function<URI, CompletableFuture<Integer>> web,
            Function<Path, CompletableFuture<Integer>> onDisk,
            Function<byte[], CompletableFuture<Integer>> content) {
        JsonNode b = c.body();
        if (b.has("path")) {
            return onDisk.apply(file(text(b, "path")));
        }
        if (b.has("data")) {
            try {
                return content.apply(Base64.getDecoder().decode(text(b, "data")));
            } catch (IllegalArgumentException e) {
                throw new Failure(400, "The data is not Base64.");
            }
        }
        return web.apply(url(c));
    }

    // An existing file of this computer, by its absolute path.
    private static Path file(String text) {
        try {
            Path path = Path.of(text.strip());
            if (path.isAbsolute() && Files.isRegularFile(path)) {
                return path.normalize();
            }
        } catch (InvalidPathException e) {
            // Answered below.
        }
        throw new Failure(400, "Not a file of this computer: " + text);
    }

    private void listKnownClients(Call c) throws IOException {
        List<KnownClient> clients = await(this.engine.knownClients());
        reply(c.exchange(), 200, g -> ApiJson.array(g, clients, ApiJson::knownClient));
    }

    private void testNotifications(Call c) throws IOException {
        List<NotificationResult> results = await(this.engine.testNotifications());
        reply(c.exchange(), 200, g -> ApiJson.array(g, results, ApiJson::notificationResult));
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

    // A test lasts minutes, longer than a call waits: the answer is the test as it starts, read again until done. The
    // engine starts it before it reads it, so a test refused or over at once answers with its outcome.
    private void testPorts(Call c) throws IOException {
        CompletableFuture<PortTest> test = this.engine.testPorts();
        Optional<PortTest> started = test.isDone() ? Optional.empty() : await(this.engine.portTest());
        PortTest answer = test.isDone() || started.isEmpty() ? await(test) : started.get();
        reply(c.exchange(), 202, g -> ApiJson.portTest(g, answer));
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
        this.searches.started(id.value(), new SearchCollector.Asked(expression, asked.toLowerCase(Locale.ROOT)));
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

    private void listSearches(Call c) throws IOException {
        List<SearchCollector.Listed> listed = this.searches.searches();
        reply(
                c.exchange(),
                200,
                g -> ApiJson.array(g, listed, (w, s) -> {
                    w.writeStartObject();
                    w.writeNumberProperty("id", s.id());
                    w.writeStringProperty("query", s.asked().expression());
                    w.writeStringProperty("scope", s.asked().scope());
                    w.writeBooleanProperty("running", s.running());
                    w.writeNumberProperty("results", s.results());
                    w.writeEndObject();
                }));
    }

    private void getSearchResults(Call c) throws IOException {
        long id = searchId(c);
        // Rated now, as the files marked since they came make them; spam last.
        List<SearchResult> found = new ArrayList<>(
                await(this.engine.rateSpam(this.searches.results(id).orElseThrow(RemoteApi::notFound))));
        found.sort(Comparator.comparing(SearchResult::isSpam)
                .thenComparing(Comparator.comparingInt(SearchResult::sources).reversed()));
        boolean running = this.searches.running(id);
        Known known = known();
        reply(c.exchange(), 200, g -> {
            g.writeStartObject();
            g.writeNumberProperty("id", id);
            g.writeBooleanProperty("running", running);
            g.writeName("results");
            ApiJson.array(g, found, (w, r) -> ApiJson.result(w, r, known.of(r.hash())));
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

    // What we know of files: downloading, shared, or downloaded or cancelled before; the first that holds wins.
    private record Known(Set<Hash16> downloading, Set<Hash16> shared, FileHistory history) {
        Optional<String> of(Hash16 file) {
            if (this.downloading.contains(file)) {
                return Optional.of("downloading");
            }
            if (this.shared.contains(file)) {
                return Optional.of("shared");
            }
            if (this.history.downloaded(file)) {
                return Optional.of("downloaded");
            }
            return this.history.cancelled(file) ? Optional.of("cancelled") : Optional.empty();
        }
    }

    private Known known() {
        Set<Hash16> downloading = new HashSet<>();
        await(this.engine.downloads()).stream()
                .filter(d -> d.status() != DownloadInfo.Status.COMPLETE)
                .forEach(d -> downloading.add(d.hash()));
        Set<Hash16> shared = new HashSet<>();
        await(this.engine.sharedFiles()).forEach(f -> shared.add(f.hash()));
        return new Known(downloading, shared, await(this.engine.fileHistory()));
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

    // With include=parts each download comes with the map of its parts, as a remote desktop app shows them.
    private void listDownloads(Call c) throws IOException {
        JsonNode include = c.parameters().get("include");
        boolean withParts = include != null && include.asString().equals("parts");
        list(c, this.engine::downloads, withParts ? ApiJson::downloadAndParts : ApiJson::download);
    }

    private void getDownloadParts(Call c) throws IOException {
        Hash16 hash = hash(c);
        DownloadInfo d = await(this.engine.downloads()).stream()
                .filter(i -> i.hash().equals(hash))
                .findFirst()
                .orElseThrow(RemoteApi::notFound);
        reply(c.exchange(), 200, g -> ApiJson.downloadWithParts(g, d));
    }

    private void getDownloadData(Call c) throws IOException {
        Path data = await(this.engine.downloadData(hash(c)))
                .orElseThrow(() -> new Failure(404, "The start of the file has not come yet."));
        reply(c.exchange(), 200, g -> {
            g.writeStartObject();
            g.writeStringProperty("path", data.toAbsolutePath().toString());
            g.writeEndObject();
        });
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
        count(c, "added", work);
    }

    private void count(Call c, String what, CompletableFuture<Integer> work) throws IOException {
        int counted = await(work);
        reply(c.exchange(), 200, g -> {
            g.writeStartObject();
            g.writeNumberProperty(what, counted);
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
