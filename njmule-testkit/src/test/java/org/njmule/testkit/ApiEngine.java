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
package org.njmule.testkit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.njmule.core.api.BanInfo;
import org.njmule.core.api.Category;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EmuleHistory;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.FileComment;
import org.njmule.core.api.FileHistory;
import org.njmule.core.api.Friend;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.ImportOptions;
import org.njmule.core.api.ImportReport;
import org.njmule.core.api.KadContactInfo;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.KadNote;
import org.njmule.core.api.KadSearchInfo;
import org.njmule.core.api.KnownClient;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.NotificationResult;
import org.njmule.core.api.PartMap;
import org.njmule.core.api.PeerComment;
import org.njmule.core.api.PeerInfo;
import org.njmule.core.api.PortTest;
import org.njmule.core.api.SavedSearch;
import org.njmule.core.api.SearchId;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.api.SharedDirectory;
import org.njmule.core.api.SharedFileInfo;
import org.njmule.core.api.SourceInfo;
import org.njmule.core.api.SpamMarks;
import org.njmule.core.api.UpdateHandOver;
import org.njmule.core.api.UpdateStatus;
import org.njmule.core.api.UploadInfo;
import org.njmule.core.api.UploadPriority;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.config.Preferences;
import org.njmule.core.config.Schedule;
import org.njmule.core.persist.PreferencesCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A njmule executable on a profile, driven only through the remote-control API of its control endpoint, as the desktop
 * window drives it: the flows' black-box mode. What the API has no call for (the data of a download for a preview)
 * fails or comes back empty.
 */
final class ApiEngine implements NjMuleEngine {

    private static final Logger logger = LoggerFactory.getLogger(ApiEngine.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration RETRY = Duration.ofSeconds(5);
    private static final Duration PORT_TEST_READ = Duration.ofSeconds(1);
    private static final Duration STOP_WAIT = Duration.ofSeconds(30);
    private static final String CONTROL_LINE = "njmule-control ";
    private static final JsonMapper MAPPER = JsonMapper.shared();

    private final String api;
    private final String token;
    private final AppDirectories directories;
    private final Process process;
    private final HttpClient http;
    private final SubmissionPublisher<EngineEvent> publisher = new SubmissionPublisher<>();
    // The part maps of the last list, for the download events, which carry none.
    private final Map<Hash16, PartMap> parts = new ConcurrentHashMap<>();
    // The search each result came from: the other njMule marks spam through it.
    private final Map<Hash16, SearchId> resultSearches = new ConcurrentHashMap<>();
    private volatile Preferences preferences = Preferences.defaults();
    private volatile Schedule schedule = Schedule.EMPTY;
    private volatile Optional<String> activeRule = Optional.empty();
    private volatile UpdateStatus update;
    private volatile boolean running = true;
    private volatile Thread stream;

    private ApiEngine(URI api, String token, AppDirectories directories, Process process) {
        String base = api.toString();
        this.api = base.endsWith("/") ? base : base + "/";
        this.token = token;
        this.directories = directories;
        this.process = process;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    /**
     * Starts {@code executable} on the profile of {@code dirs} with the control endpoint and follows its events; its
     * output goes to {@code engine.log} beside the profile's folders.
     */
    static ApiEngine start(Path executable, AppDirectories dirs) throws IOException {
        Path root = dirs.config().getParent();
        Path log = root.resolve("engine.log");
        ProcessBuilder builder = command(executable, List.of("--config-dir", root.toString()));
        builder.environment().put("NJMULE_CONTROL_ENDPOINT", "1");
        Process process = builder.start();
        BufferedReader output = process.inputReader(StandardCharsets.UTF_8);
        Writer saved = Files.newBufferedWriter(log, StandardCharsets.UTF_8);
        String line = output.readLine();
        while (line != null && !line.startsWith(CONTROL_LINE)) {
            saved.write(line + System.lineSeparator());
            line = output.readLine();
        }
        if (line == null) {
            saved.close();
            stop(process);
            throw new IOException("The engine stopped before its API answered:\n" + Files.readString(log));
        }
        // The output is read to the end, or the engine would block once the pipe is full.
        Thread.ofVirtual().name("njmule-engine-output").start(() -> {
            try (saved;
                    output) {
                output.transferTo(saved);
            } catch (IOException e) {
                logger.debug("The engine's output broke off: {}", e.toString());
            }
        });
        JsonNode endpoint = MAPPER.readTree(line.substring(CONTROL_LINE.length()));
        ApiEngine engine = new ApiEngine(
                URI.create(endpoint.path("url").asString()),
                endpoint.path("token").asString(),
                dirs,
                process);
        try {
            engine.load();
        } catch (IOException | RuntimeException e) {
            engine.close();
            throw e;
        }
        engine.stream = Thread.ofVirtual().name("njmule-remote-events").start(engine::follow);
        return engine;
    }

    /** A start of {@code executable} with {@code arguments}, its errors in its output. */
    static ProcessBuilder command(Path executable, List<String> arguments) throws IOException {
        // The test JVM's switches (no UPnP, real servers, update checks or downloads) hold for the engine too: options
        // of a native executable, JAVA_OPTS of a launcher script.
        List<String> switches = System.getProperties().stringPropertyNames().stream()
                .filter(name -> name.startsWith("njmule.") && name.endsWith(".disabled"))
                .sorted()
                .map(name -> "-D" + name + "=" + System.getProperty(name))
                .toList();
        List<String> command = new ArrayList<>(List.of(executable.toString()));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (script(executable)) {
            builder.environment().put("JAVA_OPTS", String.join(" ", switches));
        } else {
            command.addAll(switches);
        }
        command.addAll(arguments);
        return builder;
    }

    private static boolean script(Path executable) throws IOException {
        String name = executable.getFileName().toString().toLowerCase(Locale.ROOT);
        try (InputStream in = Files.newInputStream(executable)) {
            return name.endsWith(".bat") || Arrays.equals(in.readNBytes(2), new byte[] {'#', '!'});
        }
    }

    // The engine saves and stops once its input closes, as under the desktop window.
    private static void stop(Process process) {
        try {
            process.getOutputStream().close();
            if (!process.waitFor(STOP_WAIT.toSeconds(), TimeUnit.SECONDS)) {
                // A launcher script leaves its Java behind when only the script is stopped.
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly().waitFor();
            }
        } catch (IOException e) {
            process.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private void load() throws IOException {
        try {
            this.preferences = preferencesOf(json("GET", "preferences", null).join());
            readSchedule(json("GET", "schedule", null).join());
            this.update = ApiReader.update(json("GET", "update", null).join());
        } catch (CompletionException e) {
            throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
        }
    }

    @Override
    public AppDirectories directories() {
        return this.directories;
    }

    @Override
    public Preferences preferences() {
        return this.preferences;
    }

    // Only what changed goes to the other njMule, which gives back all of them.
    @Override
    public CompletableFuture<Preferences> updatePreferences(UnaryOperator<Preferences> change) {
        Preferences now = this.preferences;
        Map<String, String> before = PreferencesCodec.values(now);
        ObjectNode body = MAPPER.createObjectNode();
        PreferencesCodec.values(change.apply(now)).forEach((key, value) -> {
            if (!value.equals(before.get(key))) {
                body.put(key, value);
            }
        });
        if (body.isEmpty()) {
            return CompletableFuture.completedFuture(now);
        }
        return json("PATCH", "preferences", body).thenApply(n -> {
            this.preferences = preferencesOf(n);
            return this.preferences;
        });
    }

    private static Preferences preferencesOf(JsonNode n) {
        Map<String, String> values = new LinkedHashMap<>();
        n.properties().forEach(e -> values.put(e.getKey(), e.getValue().asString()));
        return PreferencesCodec.apply(Preferences.defaults(), values);
    }

    @Override
    public Flow.Publisher<EngineEvent> events() {
        return this.publisher;
    }

    @Override
    public Schedule schedule() {
        return this.schedule;
    }

    @Override
    public CompletableFuture<Schedule> updateSchedule(Schedule schedule) {
        // shortcut: the core's ApiJson, not exported, writes this body; write it here once a flow needs it.
        return notHere("a schedule");
    }

    private void readSchedule(JsonNode n) {
        this.schedule = ApiReader.schedule(n);
        JsonNode active = n.path("active");
        this.activeRule = active.isString() ? Optional.of(active.asString()) : Optional.empty();
    }

    @Override
    public Optional<Schedule.Rule> activeRule() {
        return this.activeRule.flatMap(name -> this.schedule.rules().stream()
                .filter(r -> r.name().equals(name))
                .findFirst());
    }

    @Override
    public CompletableFuture<Integer> addServers(Path serverMet) {
        return read(serverMet).thenCompose(this::addServers);
    }

    @Override
    public CompletableFuture<Integer> addServers(byte[] serverMet) {
        return count("POST", "servers/import", body("data", Base64.getEncoder().encodeToString(serverMet)));
    }

    @Override
    public CompletableFuture<Integer> addServers(URI url) {
        return count("POST", "servers/import", body("url", url.toString()));
    }

    @Override
    public CompletableFuture<Integer> updateIpFilter(URI url) {
        return count("POST", "ipfilter", body("url", url.toString()));
    }

    @Override
    public CompletableFuture<Integer> updateIpFilter(List<URI> urls) {
        return urls.size() == 1 ? updateIpFilter(urls.getFirst()) : count("POST", "ipfilter/lists", null);
    }

    @Override
    public CompletableFuture<List<ServerInfo>> servers() {
        return list("servers", ApiReader::server);
    }

    @Override
    public CompletableFuture<Boolean> removeServer(ServerAddress server) {
        return yes("DELETE", "servers/" + server, null);
    }

    @Override
    public CompletableFuture<List<URI>> serverListAddresses() {
        return json("GET", "servers/lists", null)
                .thenApply(n -> ApiReader.list(n.path("addresses"), a -> URI.create(a.asString())));
    }

    @Override
    public CompletableFuture<Void> setServerListAddresses(List<URI> addresses) {
        ObjectNode body = MAPPER.createObjectNode();
        ArrayNode all = body.putArray("addresses");
        addresses.forEach(a -> all.add(a.toString()));
        return done("PUT", "servers/lists", body);
    }

    @Override
    public CompletableFuture<Boolean> addServer(ServerAddress server) {
        return yes("POST", "servers", body("address", server.toString()));
    }

    @Override
    public CompletableFuture<Boolean> setServerPriority(ServerAddress server, ServerInfo.Priority priority) {
        return yes("PUT", "servers/" + server + "/priority", body("priority", name(priority)));
    }

    @Override
    public CompletableFuture<Boolean> setStaticServer(ServerAddress server, boolean isStatic) {
        ObjectNode body = MAPPER.createObjectNode().put("static", isStatic);
        return yes("PUT", "servers/" + server + "/static", body);
    }

    @Override
    public CompletableFuture<EngineStatus> status() {
        return json("GET", "status", null).thenApply(ApiReader::status);
    }

    @Override
    public CompletableFuture<List<UploadInfo>> uploads() {
        return list("uploads", ApiReader::upload);
    }

    @Override
    public CompletableFuture<List<SavedSearch>> savedSearches() {
        return list("searches/saved", ApiReader::savedSearch);
    }

    @Override
    public CompletableFuture<Void> saveSearches(List<SavedSearch> searches) {
        // shortcut: the core's ApiJson, not exported, writes this body; write it here once a flow needs it.
        return notHere("saved searches");
    }

    @Override
    public CompletableFuture<FileHistory> fileHistory() {
        return CompletableFuture.completedFuture(FileHistory.NONE);
    }

    @Override
    public CompletableFuture<SpamMarks> spamMarks() {
        return json("GET", "searches/spam", null).thenApply(ApiReader::spamMarks);
    }

    @Override
    public CompletableFuture<SpamMarks> markSpam(List<SearchResult> results, boolean isSpam) {
        Map<SearchId, List<Hash16>> bySearch = new LinkedHashMap<>();
        for (SearchResult result : results) {
            SearchId search = this.resultSearches.get(result.hash());
            if (search == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("That result is gone: search again."));
            }
            bySearch.computeIfAbsent(search, s -> new ArrayList<>()).add(result.hash());
        }
        CompletableFuture<Void> marked = CompletableFuture.completedFuture(null);
        for (Map.Entry<SearchId, List<Hash16>> one : bySearch.entrySet()) {
            ObjectNode body = MAPPER.createObjectNode().put("spam", isSpam);
            ArrayNode hashes = body.putArray("hashes");
            one.getValue().forEach(hash -> hashes.add(hash.toHex()));
            marked = marked.thenCompose(
                    done -> done("POST", "searches/" + one.getKey().value() + "/spam", body));
        }
        return marked.thenCompose(done -> spamMarks());
    }

    @Override
    public CompletableFuture<List<SearchResult>> rateSpam(List<SearchResult> results) {
        return CompletableFuture.completedFuture(results);
    }

    @Override
    public CompletableFuture<List<SourceInfo>> sources() {
        return list("sources", ApiReader::source);
    }

    @Override
    public CompletableFuture<List<BanInfo>> bans() {
        return list("bans", ApiReader::ban);
    }

    @Override
    public CompletableFuture<Boolean> unban(String ip) {
        return yes("DELETE", "bans/" + ip, null);
    }

    @Override
    public CompletableFuture<Void> connect(Optional<ServerAddress> server) {
        ObjectNode body = MAPPER.createObjectNode();
        server.ifPresent(s -> body.put("address", s.toString()));
        return done("POST", "servers/connect", body);
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        return done("POST", "servers/disconnect", null);
    }

    @Override
    public CompletableFuture<SearchId> search(SearchRequest request) {
        ObjectNode body =
                MAPPER.createObjectNode().put("query", request.expression()).put("scope", name(request.scope()));
        return json("POST", "searches", body)
                .thenApply(n -> new SearchId(n.path("id").asLong()));
    }

    @Override
    public CompletableFuture<Boolean> moreResults(SearchId search) {
        return yes("POST", "searches/" + search.value() + "/more", null);
    }

    @Override
    public CompletableFuture<Boolean> hasMoreResults(SearchId search) {
        return json("GET", "searches/" + search.value(), null)
                .thenApply(n -> n.path("more").asBoolean());
    }

    @Override
    public CompletableFuture<Void> stopSearch(SearchId search) {
        this.resultSearches.values().removeIf(search::equals);
        return done("DELETE", "searches/" + search.value(), null);
    }

    @Override
    public CompletableFuture<PeerInfo> hello(ServerAddress peer) {
        return json("POST", "clients/" + peer + "/hello", null).thenApply(ApiReader::peer);
    }

    @Override
    public CompletableFuture<DownloadInfo> addDownload(Ed2kLink.FileLink link) {
        return add(link, OptionalInt.empty(), Optional.empty());
    }

    @Override
    public CompletableFuture<DownloadInfo> addDownload(Ed2kLink.FileLink link, OptionalInt category, boolean paused) {
        return add(link, category, Optional.of(paused));
    }

    // Without paused, the other njMule's preferences decide.
    private CompletableFuture<DownloadInfo> add(
            Ed2kLink.FileLink link, OptionalInt category, Optional<Boolean> paused) {
        ObjectNode body = MAPPER.createObjectNode();
        body.putArray("links").add(link.toUri());
        category.ifPresent(c -> body.put("category", c));
        paused.ifPresent(p -> body.put("paused", p));
        return json("POST", "downloads", body).thenApply(n -> ApiReader.download(n.path(0), PartMap.EMPTY));
    }

    @Override
    public CompletableFuture<List<DownloadInfo>> downloads() {
        return json("GET", "downloads?include=parts", null).thenApply(n -> {
            List<DownloadInfo> all = ApiReader.list(n, d -> ApiReader.download(d, PartMap.EMPTY));
            this.parts.clear();
            all.forEach(d -> this.parts.put(d.hash(), d.parts()));
            return all;
        });
    }

    @Override
    public CompletableFuture<Integer> clearCompletedDownloads() {
        return json("DELETE", "downloads/completed", null)
                .thenApply(n -> n.path("removed").asInt());
    }

    @Override
    public CompletableFuture<Boolean> clearCompletedDownload(Hash16 hash) {
        return yes("DELETE", "downloads/completed/" + hash.toHex(), null);
    }

    @Override
    public CompletableFuture<Boolean> pauseDownload(Hash16 hash) {
        return yes("POST", download(hash, "pause"), null);
    }

    @Override
    public CompletableFuture<Boolean> resumeDownload(Hash16 hash) {
        return yes("POST", download(hash, "resume"), null);
    }

    @Override
    public CompletableFuture<Boolean> stopDownload(Hash16 hash) {
        return yes("POST", download(hash, "stop"), null);
    }

    @Override
    public CompletableFuture<List<Category>> categories() {
        return list("categories", ApiReader::category);
    }

    @Override
    public CompletableFuture<Void> setCategories(List<Category> categories) {
        // shortcut: the core's ApiJson, not exported, writes this body; write it here once a flow needs it.
        return notHere("categories");
    }

    @Override
    public CompletableFuture<Boolean> setDownloadCategory(Hash16 hash, int category) {
        return yes("PUT", download(hash, "category"), MAPPER.createObjectNode().put("category", category));
    }

    @Override
    public CompletableFuture<Boolean> addSource(Hash16 download, ServerAddress source) {
        return yes("POST", download(download, "sources"), body("address", source.toString()));
    }

    @Override
    public CompletableFuture<Boolean> setDownloadPreview(Hash16 hash, boolean previewFirst) {
        return yes("PUT", download(hash, "preview"), MAPPER.createObjectNode().put("previewFirst", previewFirst));
    }

    @Override
    public CompletableFuture<Integer> swapSourcesToDownload(Hash16 hash) {
        return count("POST", download(hash, "swap"), body("direction", "here"));
    }

    @Override
    public CompletableFuture<Integer> swapSourcesFromDownload(Hash16 hash) {
        return count("POST", download(hash, "swap"), body("direction", "away"));
    }

    @Override
    public CompletableFuture<Boolean> setDownloadAutoSwap(Hash16 hash, boolean autoSwap) {
        return yes("PUT", download(hash, "autoswap"), MAPPER.createObjectNode().put("autoSwap", autoSwap));
    }

    @Override
    public CompletableFuture<Boolean> swapSourceToDownload(Hash16 download, ServerAddress source) {
        return json("POST", download(download, "sources/swap"), body("address", source.toString()))
                .thenApply(n -> n.path("moved").asBoolean());
    }

    @Override
    public CompletableFuture<Optional<Path>> downloadData(Hash16 hash) {
        return notHere("a preview, which plays the data on the computer that downloads it");
    }

    @Override
    public CompletableFuture<Boolean> setDownloadPriority(Hash16 hash, DownloadPriority priority) {
        return yes("PUT", download(hash, "priority"), body("priority", name(priority)));
    }

    @Override
    public CompletableFuture<Boolean> cancelDownload(Hash16 hash) {
        return yes("DELETE", "downloads/" + hash.toHex(), null);
    }

    @Override
    public CompletableFuture<Boolean> restoreDownload(Hash16 hash) {
        return yes("POST", download(hash, "restore"), null);
    }

    @Override
    public CompletableFuture<List<SharedFileInfo>> sharedFiles() {
        return list("shared", ApiReader::shared);
    }

    @Override
    public CompletableFuture<Boolean> renameDownload(Hash16 hash, String name) {
        return yes("PUT", download(hash, "name"), body("name", name));
    }

    @Override
    public CompletableFuture<Boolean> setUploadPriority(Hash16 hash, UploadPriority priority) {
        return yes("PUT", "shared/" + hash.toHex() + "/priority", body("priority", name(priority)));
    }

    @Override
    public CompletableFuture<Boolean> renameSharedFile(Hash16 hash, String name) {
        return yes("PUT", "shared/" + hash.toHex() + "/name", body("name", name));
    }

    @Override
    public CompletableFuture<Void> shareDirectory(Path directory) {
        return shareDirectory(directory, false);
    }

    @Override
    public CompletableFuture<Void> shareDirectory(Path directory, boolean subdirectories) {
        ObjectNode body = body("path", directory.toString()).put("subdirectories", subdirectories);
        return done("POST", "shared/directories", body);
    }

    @Override
    public CompletableFuture<Void> unshareDirectory(Path directory) {
        return done("DELETE", "shared/directories?path=" + query(directory.toString()), null);
    }

    @Override
    public CompletableFuture<List<SharedDirectory>> sharedDirectories() {
        return list("shared/directories", ApiReader::directory);
    }

    @Override
    public CompletableFuture<Void> connectKad() {
        return done("POST", "kad/connect", null);
    }

    @Override
    public CompletableFuture<Void> disconnectKad() {
        return done("POST", "kad/disconnect", null);
    }

    @Override
    public CompletableFuture<Boolean> recheckKadFirewall() {
        return yes("POST", "kad/recheck", null);
    }

    @Override
    public CompletableFuture<PortTest> testPorts() {
        return json("POST", "ports/test", null).thenApply(ApiReader::portTest).thenCompose(this::untilOver);
    }

    // The other njMule answers as the test starts: it is read again until over, when this engine's future completes.
    private CompletableFuture<PortTest> untilOver(PortTest test) {
        if (test.done()) {
            return CompletableFuture.completedFuture(test);
        }
        Executor later = CompletableFuture.delayedExecutor(PORT_TEST_READ.toMillis(), TimeUnit.MILLISECONDS);
        return CompletableFuture.runAsync(() -> {}, later)
                .thenCompose(nothing -> portTest())
                .thenCompose(
                        read -> read.map(this::untilOver).orElseGet(() -> CompletableFuture.completedFuture(test)));
    }

    @Override
    public CompletableFuture<Optional<PortTest>> portTest() {
        return json("GET", "ports/test", null)
                .thenApply(n -> n.isObject() ? Optional.of(ApiReader.portTest(n)) : Optional.empty());
    }

    @Override
    public CompletableFuture<Void> kadBootstrap(ServerAddress node) {
        return done("POST", "kad/bootstrap", body("address", node.toString()));
    }

    @Override
    public CompletableFuture<Integer> addKadContacts(URI url) {
        return count("POST", "kad/nodes", body("url", url.toString()));
    }

    @Override
    public CompletableFuture<Integer> addKadContacts(Path nodesDat) {
        return read(nodesDat).thenCompose(this::addKadContacts);
    }

    @Override
    public CompletableFuture<Integer> addKadContacts(byte[] nodesDat) {
        return count("POST", "kad/nodes", body("data", Base64.getEncoder().encodeToString(nodesDat)));
    }

    @Override
    public CompletableFuture<Optional<FileComment>> fileComment(Hash16 file) {
        return json("GET", "files/" + file.toHex() + "/comment", null).thenApply(n -> {
            FileComment comment = ApiReader.comment(n);
            return comment.equals(FileComment.NONE) ? Optional.empty() : Optional.of(comment);
        });
    }

    @Override
    public CompletableFuture<Void> setFileComment(Hash16 file, FileComment comment) {
        ObjectNode body = body("text", comment.text()).put("rating", comment.rating());
        return done("PUT", "files/" + file.toHex() + "/comment", body);
    }

    @Override
    public CompletableFuture<List<PeerComment>> peerComments(Hash16 download) {
        return list(download(download, "comments"), ApiReader::peerComment);
    }

    @Override
    public CompletableFuture<List<KadNote>> kadNotes(Hash16 file) {
        return list("files/" + file.toHex() + "/notes", ApiReader::kadNote);
    }

    @Override
    public CompletableFuture<List<KadNote>> searchKadNotes(Hash16 file) {
        return json("POST", "files/" + file.toHex() + "/notes", null)
                .thenApply(n -> ApiReader.list(n, ApiReader::kadNote));
    }

    @Override
    public CompletableFuture<Optional<Hash16>> friendSlot() {
        return json("GET", "friends/slot", null).thenApply(ApiEngine::slot);
    }

    @Override
    public CompletableFuture<Boolean> setFriendSlot(Optional<Hash16> friend) {
        ObjectNode body = MAPPER.createObjectNode();
        if (friend.isPresent()) {
            body.put("userHash", friend.get().toHex());
        } else {
            body.putNull("userHash");
        }
        return yes("PUT", "friends/slot", body);
    }

    private static Optional<Hash16> slot(JsonNode n) {
        JsonNode user = n.path("userHash");
        return user.isString() ? Optional.of(Hash16.parseHex(user.asString())) : Optional.empty();
    }

    @Override
    public CompletableFuture<List<SearchResult>> browseSharedFiles(ServerAddress client) {
        return json("POST", "clients/" + client + "/files", null)
                .thenApply(n -> ApiReader.list(n, ApiEngine::sharedByClient));
    }

    private static SearchResult sharedByClient(JsonNode n) {
        JsonNode type = n.path("type");
        return new SearchResult(
                ApiReader.hash(n, "hash"),
                n.path("name").asString(""),
                n.path("size").asLong(),
                0,
                -1,
                type.isString() ? Optional.of(type.asString()) : Optional.empty(),
                SearchResult.Media.NONE,
                SearchResult.Origin.NONE,
                0,
                Optional.empty(),
                List.of());
    }

    @Override
    public CompletableFuture<List<KadContactInfo>> kadContacts() {
        return list("kad/contacts", ApiReader::kadContact);
    }

    @Override
    public CompletableFuture<List<KadSearchInfo>> kadSearches() {
        return list("kad/searches", ApiReader::kadSearch);
    }

    @Override
    public CompletableFuture<Optional<KadInfo>> kad() {
        return json("GET", "kad", null).thenApply(n -> n.isObject() ? Optional.of(ApiReader.kad(n)) : Optional.empty());
    }

    @Override
    public CompletableFuture<List<KnownClient>> knownClients() {
        return json("GET", "clients/known", null).thenApply(n -> ApiReader.list(n, ApiReader::knownClient));
    }

    @Override
    public CompletableFuture<List<NotificationResult>> testNotifications() {
        return json("POST", "notifications/test", null)
                .thenApply(n -> ApiReader.list(n, ApiReader::notificationResult));
    }

    @Override
    public CompletableFuture<ImportReport> importFromEmule(Path emuleConfigDir, ImportOptions options) {
        ObjectNode body = body("path", emuleConfigDir.toString())
                .put("dryRun", options.dryRun())
                .put("identity", options.identity())
                .put("downloads", options.downloads());
        return json("POST", "import/emule", body).thenApply(ApiReader::importReport);
    }

    @Override
    public CompletableFuture<Optional<Path>> emuleProfile() {
        return json("GET", "import/emule", null).thenApply(n -> {
            JsonNode path = n.path("path");
            return path.isString() ? Optional.of(Path.of(path.asString())) : Optional.empty();
        });
    }

    @Override
    public CompletableFuture<EmuleHistory> emuleHistory() {
        return json("GET", "import/emule/history", null)
                .thenApply(n -> new EmuleHistory(
                        ApiReader.list(n.path("searches"), JsonNode::asString),
                        ApiReader.list(n.path("serverLists"), JsonNode::asString)));
    }

    @Override
    public CompletableFuture<Boolean> setupPending() {
        return json("GET", "setup", null).thenApply(n -> n.path("pending").asBoolean(false));
    }

    @Override
    public CompletableFuture<Void> finishSetup() {
        return done("POST", "setup/done", null);
    }

    @Override
    public CompletableFuture<Void> sendMessage(ServerAddress to, String text) {
        return done("POST", "messages", body("to", to.toString()).put("text", text));
    }

    @Override
    public CompletableFuture<List<Friend>> friends() {
        return list("friends", ApiReader::friend);
    }

    @Override
    public CompletableFuture<Void> addFriend(Friend friend) {
        ObjectNode body = body("userHash", friend.userHash().toHex()).put("name", friend.name());
        friend.address().ifPresent(a -> body.put("address", a.toString()));
        return done("POST", "friends", body);
    }

    @Override
    public CompletableFuture<Boolean> removeFriend(Hash16 userHash) {
        return yes("DELETE", "friends/" + userHash.toHex(), null);
    }

    @Override
    public UpdateStatus updateStatus() {
        return this.update;
    }

    @Override
    public CompletableFuture<UpdateStatus> checkForUpdate() {
        return updating("POST", "update/check", null);
    }

    @Override
    public CompletableFuture<UpdateStatus> skipUpdate(String version) {
        return updating("POST", "update/skip", body("version", version));
    }

    @Override
    public CompletableFuture<UpdateStatus> downloadUpdate() {
        return updating("POST", "update/download", null);
    }

    @Override
    public CompletableFuture<UpdateStatus> installUpdate(boolean restart) {
        return updating("POST", "update/install", MAPPER.createObjectNode().put("restart", restart));
    }

    private CompletableFuture<UpdateStatus> updating(String method, String path, ObjectNode body) {
        return json(method, path, body).thenApply(n -> {
            this.update = ApiReader.update(n);
            return this.update;
        });
    }

    // The other njMule updates itself; nothing is handed over to this computer.
    @Override
    public Optional<UpdateHandOver> updateHandOver() {
        return Optional.empty();
    }

    @Override
    public boolean isRunning() {
        return this.running && this.process.isAlive();
    }

    @Override
    public void close() {
        this.running = false;
        Thread events = this.stream;
        if (events != null) {
            events.interrupt();
        }
        this.publisher.close();
        // Not close(), which waits for every request: the event stream lasts as long as the other njMule.
        this.http.shutdownNow();
        stop(this.process);
    }

    // The events, followed again after a pause when the stream breaks, as the other njMule may restart.
    private void follow() {
        while (this.running) {
            HttpRequest request = request("GET", "events", null)
                    .timeout(Duration.ofDays(365))
                    .header("Accept", "text/event-stream")
                    .build();
            try {
                HttpResponse<Stream<String>> answer = this.http.send(request, HttpResponse.BodyHandlers.ofLines());
                if (answer.statusCode() == 200) {
                    try (Stream<String> lines = answer.body()) {
                        read(lines.iterator());
                    }
                } else {
                    logger.warn("The remote njMule refused its events: HTTP {}", answer.statusCode());
                }
            } catch (IOException | UncheckedIOException | CompletionException e) {
                logger.debug("The events of the remote njMule broke off: {}", e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                Thread.sleep(RETRY);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // Server-sent events: "event:" and "data:" lines, a blank line after each event.
    private void read(Iterator<String> lines) {
        String name = null;
        StringBuilder data = new StringBuilder();
        while (this.running && lines.hasNext()) {
            String line = lines.next();
            if (line.isEmpty()) {
                if (name != null && !dispatch(name, data.toString())) {
                    return;
                }
                name = null;
                data.setLength(0);
            } else if (line.startsWith("event:")) {
                name = line.substring("event:".length()).strip();
            } else if (line.startsWith("data:")) {
                data.append(line.substring("data:".length()).strip());
            }
        }
    }

    /** Hands an event on; false when the stream must be opened again. */
    private boolean dispatch(String name, String data) {
        if (name.equals("lost")) {
            return false;
        }
        JsonNode n = data.isEmpty() ? MAPPER.createObjectNode() : MAPPER.readTree(data);
        switch (name) {
            // The stream's own thread waits for what the event needs besides.
            case "preferences.changed" -> {
                this.preferences =
                        preferencesOf(json("GET", "preferences", null).join());
                this.publisher.submit(new EngineEvent.PreferencesChanged(this.preferences));
            }
            case "captcha.requested" -> captcha(n);
            default -> {
                PartMap known = this.parts.getOrDefault(partsOf(n), PartMap.EMPTY);
                ApiReader.event(name, n, known).ifPresent(event -> {
                    if (event instanceof EngineEvent.UpdateStatusChanged changed) {
                        this.update = changed.status();
                    }
                    if (event instanceof EngineEvent.SearchResults found) {
                        found.results().forEach(r -> this.resultSearches.put(r.hash(), found.search()));
                    }
                    this.publisher.submit(event);
                });
            }
        }
        return true;
    }

    private static Hash16 partsOf(JsonNode n) {
        JsonNode hash = n.path("download").path("hash");
        return hash.isString() ? Hash16.parseHex(hash.asString()) : Hash16.ZERO;
    }

    // The picture of a CAPTCHA is in the conversation it came with.
    private void captcha(JsonNode n) {
        String address = n.path("address").asString("");
        Hash16 user = ApiReader.hash(n, "userHash");
        json("GET", "conversations", null).join().values().forEach(chat -> {
            JsonNode picture = chat.path("captcha");
            if (chat.path("address").asString("").equals(address) && picture.isString()) {
                byte[] image = Base64.getDecoder().decode(picture.asString());
                this.publisher.submit(new EngineEvent.CaptchaRequested(address, user, image));
            }
        });
    }

    private static String download(Hash16 hash, String what) {
        return "downloads/" + hash.toHex() + "/" + what;
    }

    private static ObjectNode body(String name, String value) {
        return MAPPER.createObjectNode().put(name, value);
    }

    private static String query(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static <T> CompletableFuture<T> notHere(String what) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Not through the API: " + what));
    }

    private static CompletableFuture<byte[]> read(Path file) {
        try {
            return CompletableFuture.completedFuture(Files.readAllBytes(file));
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static String name(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private <T> CompletableFuture<List<T>> list(String path, Function<JsonNode, T> each) {
        return json("GET", path, null).thenApply(n -> ApiReader.list(n, each));
    }

    private CompletableFuture<Integer> count(String method, String path, ObjectNode body) {
        return json(method, path, body).thenApply(n -> n.path("added").asInt());
    }

    private CompletableFuture<Void> done(String method, String path, ObjectNode body) {
        return json(method, path, body).thenApply(n -> null);
    }

    // Done, or refused as unknown or not allowed: the engine's own answer, true or false.
    private CompletableFuture<Boolean> yes(String method, String path, ObjectNode body) {
        return send(method, path, bytes(body), true).thenApply(r -> r.statusCode() / 100 == 2);
    }

    private CompletableFuture<JsonNode> json(String method, String path, ObjectNode body) {
        return send(method, path, bytes(body)).thenApply(ApiEngine::tree);
    }

    private CompletableFuture<HttpResponse<byte[]>> send(String method, String path, byte[] body) {
        return send(method, path, body, false);
    }

    private CompletableFuture<HttpResponse<byte[]>> send(String method, String path, byte[] body, boolean refusable) {
        HttpRequest request = request(method, path, body).timeout(TIMEOUT).build();
        return this.http
                .sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(r -> {
                    int status = r.statusCode();
                    if (status / 100 == 2 || (refusable && (status == 404 || status == 409))) {
                        return r;
                    }
                    throw new CompletionException(new IOException(failure(r)));
                });
    }

    private HttpRequest.Builder request(String method, String path, byte[] body) {
        HttpRequest.Builder b =
                HttpRequest.newBuilder(URI.create(this.api + path)).header("Authorization", "Bearer " + this.token);
        if (body == null) {
            return b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return b.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
    }

    private static String failure(HttpResponse<byte[]> r) {
        String message = "";
        try {
            message = MAPPER.readTree(r.body()).path("error").asString("");
        } catch (RuntimeException e) {
            // Not the API's own answer: the status says it.
        }
        return "The remote njMule answered " + r.statusCode() + (message.isEmpty() ? "" : ": " + message);
    }

    private static byte[] bytes(ObjectNode body) {
        return body == null ? null : MAPPER.writeValueAsBytes(body);
    }

    private static JsonNode tree(HttpResponse<byte[]> r) {
        byte[] body = r.body();
        return body.length == 0 ? MAPPER.createObjectNode() : MAPPER.readTree(body);
    }
}
