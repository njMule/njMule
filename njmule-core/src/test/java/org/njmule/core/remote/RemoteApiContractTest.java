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

import static org.assertj.core.api.Assertions.assertThat;
import static org.njmule.core.remote.ApiSamples.HASH;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.SearchId;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.config.Preferences;

/** Every answer and event of the API follows its OpenAPI document. */
@Timeout(60)
class RemoteApiContractTest {

    private static final ApiContract CONTRACT = new ApiContract();
    private static final String LINK = "ed2k://|file|talk.bin|5000|" + HASH + "|/";
    private static final String LIST = "{\"url\":\"http://example.org/list.met\"}";

    @TempDir
    Path dir;

    private final FakeEngine fake = new FakeEngine();
    private final HttpClient http = HttpClient.newHttpClient();
    private RemoteApi api;
    private ApiServer server;
    private RecentEvents recent;
    private String token;
    private String method;
    private String path;
    private HttpResponse<String> answer;
    private BufferedReader stream;
    private Map<String, String> event = Map.of();
    private Set<Class<?>> sampled = Set.of();
    private List<String> violations = List.of();

    @AfterEach
    void stop() throws IOException {
        if (this.stream != null) {
            this.stream.close();
        }
        if (this.api != null) {
            this.api.close();
        }
        if (this.server != null) {
            this.server.close();
        }
        this.fake.events.close();
    }

    static Stream<String> reads() {
        return Stream.of(
                "/api/v1",
                "/api/v1/openapi.yaml",
                "/api/v1/status",
                "/api/v1/limits",
                "/api/v1/log?after=0",
                "/api/v1/downloads",
                "/api/v1/downloads/" + HASH,
                "/api/v1/sources",
                "/api/v1/uploads",
                "/api/v1/shared",
                "/api/v1/shared/directories",
                "/api/v1/servers",
                "/api/v1/kad",
                "/api/v1/kad/contacts",
                "/api/v1/kad/searches",
                "/api/v1/categories",
                "/api/v1/friends",
                "/api/v1/bans");
    }

    @ParameterizedTest
    @MethodSource("reads")
    void shouldAnswerAReadAsTheDocumentDescribesIt(String read) throws Exception {
        givenTheEngineHolds(true);
        givenTheApiRuns();
        givenTheEngineLogged();

        whenCalled("GET", read, "");

        thenTheStatusIs(200);
        thenTheAnswerFollowsTheDocument();
    }

    @ParameterizedTest
    @MethodSource("disconnectedReads")
    void shouldDescribeAnEngineConnectedToNothing(String read) throws Exception {
        givenTheEngineHolds(false);
        givenTheApiRuns();

        whenCalled("GET", read, "");

        thenTheStatusIs(200);
        thenTheAnswerFollowsTheDocument();
    }

    static Stream<String> disconnectedReads() {
        return Stream.of("/api/v1/status", "/api/v1/kad");
    }

    static Stream<Arguments> changes() {
        String download = "/api/v1/downloads/" + HASH;
        return Stream.of(
                Arguments.of("POST", "/api/v1/downloads", "{\"links\":[\"" + LINK + "\"],\"category\":1}", 201),
                Arguments.of("POST", "/api/v1/downloads", "{\"links\":\"" + LINK + "\"}", 201),
                Arguments.of("POST", download + "/pause", "", 200),
                Arguments.of("POST", download + "/resume", "", 200),
                Arguments.of("POST", download + "/stop", "", 200),
                Arguments.of("PUT", download + "/priority", "{\"priority\":\"high\"}", 200),
                Arguments.of("PUT", download + "/category", "{\"category\":1}", 200),
                Arguments.of("PUT", download + "/name", "{\"name\":\"talk 2.bin\"}", 200),
                Arguments.of("POST", download + "/sources", "{\"address\":\"198.51.100.7:4662\"}", 200),
                Arguments.of("DELETE", download, "", 200),
                Arguments.of("PUT", "/api/v1/shared/" + HASH + "/priority", "{\"priority\":\"release\"}", 200),
                Arguments.of("POST", "/api/v1/servers", "{\"address\":\"203.0.113.10:4661\"}", 201),
                Arguments.of("POST", "/api/v1/servers/connect", "", 200),
                Arguments.of("POST", "/api/v1/servers/connect", "{\"address\":\"203.0.113.10:4661\"}", 200),
                Arguments.of("POST", "/api/v1/servers/disconnect", "", 200),
                Arguments.of("POST", "/api/v1/servers/import", LIST, 200),
                Arguments.of("DELETE", "/api/v1/servers/203.0.113.10:4661", "", 200),
                Arguments.of("POST", "/api/v1/kad/connect", "", 200),
                Arguments.of("POST", "/api/v1/kad/disconnect", "", 200),
                Arguments.of("POST", "/api/v1/kad/recheck", "", 200),
                Arguments.of("POST", "/api/v1/kad/bootstrap", "{\"address\":\"198.51.100.40:4672\"}", 200),
                Arguments.of("POST", "/api/v1/kad/nodes", LIST, 200),
                Arguments.of("PUT", "/api/v1/limits", "{\"uploadKiBps\":50}", 200),
                Arguments.of("POST", "/api/v1/ipfilter", LIST, 200),
                Arguments.of("POST", "/api/v1/messages", "{\"to\":\"198.51.100.9:4662\",\"text\":\"hi\"}", 200),
                Arguments.of("DELETE", "/api/v1/bans/192.0.2.200", "", 200),
                Arguments.of("POST", "/api/v1/searches", "{\"query\":\"talk\",\"scope\":\"global\"}", 201),
                Arguments.of("POST", "/api/v1/shutdown", "", 202));
    }

    @ParameterizedTest
    @MethodSource("changes")
    void shouldAnswerAChangeAsTheDocumentDescribesIt(String verb, String change, String body, int status)
            throws Exception {
        givenTheEngineHolds(true);
        givenTheEngineDoesWhatItIsAsked();
        givenTheApiRuns();

        whenCalled(verb, change, body);

        thenTheStatusIs(status);
        thenTheAnswerFollowsTheDocument();
    }

    static Stream<Arguments> refusals() {
        return Stream.of(
                Arguments.of("GET", "/api/v1/nothing", "", 404),
                Arguments.of("DELETE", "/api/v1/status", "", 405),
                Arguments.of("GET", "/api/v1/downloads/talk", "", 400),
                Arguments.of("GET", "/api/v1/downloads/" + "CD".repeat(16), "", 404),
                Arguments.of("GET", "/api/v1/searches/7", "", 404),
                Arguments.of("GET", "/api/v1/log?after=-1", "", 400),
                Arguments.of("PUT", "/api/v1/limits", "{\"uploadKiBps\":-1}", 400),
                Arguments.of("POST", "/api/v1/downloads", "", 400),
                Arguments.of("POST", "/api/v1/downloads", "{\"links\":[]}", 400),
                Arguments.of("POST", "/api/v1/servers/import", "{\"url\":\"file:///etc/passwd\"}", 400),
                Arguments.of("POST", "/api/v1/searches", "{\"query\":\"talk\",\"scope\":\"everywhere\"}", 400));
    }

    @ParameterizedTest
    @MethodSource("refusals")
    void shouldAnswerARefusalAsTheDocumentDescribesIt(String verb, String refused, String body, int status)
            throws Exception {
        givenTheEngineHolds(true);
        givenTheApiRuns();

        whenCalled(verb, refused, body);

        thenTheStatusIs(status);
        thenTheAnswerFollowsTheDocument();
    }

    @Test
    void shouldAnswerACallerWithoutTheTokenAsTheDocumentDescribesIt() throws Exception {
        givenTheApiRuns();

        whenCalledWithoutTheToken("GET", "/api/v1/status");

        thenTheStatusIs(401);
        thenTheAnswerFollowsTheDocument();
    }

    @Test
    void shouldAnswerTheResultsOfASearchAsTheDocumentDescribesThem() throws Exception {
        givenTheEngineDoesWhatItIsAsked();
        givenTheApiRuns();
        givenASearchFoundResults();

        whenCalled("GET", "/api/v1/searches/7", "");

        thenTheStatusIs(200);
        thenTheAnswerFollowsTheDocument();
    }

    static Stream<EngineEvent> events() {
        return ApiSamples.events().stream();
    }

    @ParameterizedTest
    @MethodSource("events")
    void shouldStreamAnEventAsTheDocumentDescribesIt(EngineEvent announced) throws Exception {
        givenTheApiRuns();
        givenAnEventStreamOpen();

        whenTheEngineAnnounces(announced);

        thenTheStreamedEventFollowsTheDocument();
    }

    @Test
    void shouldNoticeAnAnswerThatDoesNotFollowTheDocument() {
        whenAnAnswerIsChecked("GET", "/api/v1/downloads/" + HASH, 200, "{\"hash\":\"talk\"}");

        thenItDoesNotFollowTheDocument();
    }

    @Test
    void shouldHaveASampleOfEveryEventOfTheEngine() {
        whenTheSampledEventKindsAreListed();

        thenTheyAreEveryEventKindOfTheEngine();
    }

    // Given

    private void givenTheEngineHolds(boolean connected) {
        answer("status", ApiSamples.status(connected));
        answer("kad", connected ? Optional.of(ApiSamples.kad()) : Optional.empty());
        answer("downloads", List.of(ApiSamples.download()));
        answer("sources", List.of(ApiSamples.source()));
        answer("uploads", List.of(ApiSamples.upload()));
        answer("sharedFiles", List.of(ApiSamples.shared()));
        answer("sharedDirectories", List.of(ApiSamples.directory()));
        answer("servers", List.of(ApiSamples.server()));
        answer("kadContacts", List.of(ApiSamples.kadContact()));
        answer("kadSearches", List.of(ApiSamples.kadSearch()));
        answer("categories", List.of(ApiSamples.category()));
        answer("friends", List.of(ApiSamples.friend()));
        answer("bans", List.of(ApiSamples.ban()));
        this.fake.value("preferences", Preferences.defaults());
    }

    @SuppressWarnings("unchecked")
    private void givenTheEngineDoesWhatItIsAsked() {
        for (String yes : List.of(
                "pauseDownload",
                "resumeDownload",
                "stopDownload",
                "setDownloadPriority",
                "setDownloadCategory",
                "renameDownload",
                "addSource",
                "cancelDownload",
                "setUploadPriority",
                "addServer",
                "removeServer",
                "recheckKadFirewall",
                "unban")) {
            answer(yes, true);
        }
        for (String done : List.of(
                "connect", "disconnect", "connectKad", "disconnectKad", "kadBootstrap", "sendMessage", "stopSearch")) {
            this.fake.answer(done, args -> CompletableFuture.completedFuture(null));
        }
        for (String counted : List.of("addServers", "addKadContacts", "updateIpFilter")) {
            answer(counted, 14);
        }
        answer("addDownload", ApiSamples.download());
        answer("search", new SearchId(7));
        this.fake.answer(
                "updatePreferences",
                args -> CompletableFuture.completedFuture(
                        ((UnaryOperator<Preferences>) args[0]).apply(Preferences.defaults())));
    }

    private void givenTheApiRuns() throws Exception {
        AppDirectories dirs = AppDirectories.underRoot(this.dir, true);
        this.fake.value("directories", dirs);
        this.token = ApiToken.loadOrCreate(dirs.config(), new SecureRandom());
        NjMuleEngine engine = this.fake.engine();
        this.recent = RecentEvents.follow(engine);
        this.api = new RemoteApi(engine, this.recent, client -> true, Optional.of(() -> {}), Duration.ofSeconds(1));
        this.server = ApiServer.start(this.api);
    }

    // Log lines and results come through the engine's events, delivered on the publisher's threads.
    private void givenTheEngineLogged() throws InterruptedException {
        this.fake.events.submit(new EngineEvent.Log(ApiSamples.logLine()));
        waitFor(() -> !this.recent.log().after(0).isEmpty());
    }

    private void givenASearchFoundResults() throws Exception {
        whenCalled("POST", "/api/v1/searches", "{\"query\":\"talk\"}");
        thenTheStatusIs(201);
        this.fake.events.submit(new EngineEvent.SearchResults(new SearchId(7), List.of(ApiSamples.result())));
        waitFor(() -> this.recent.searches().results(7).map(r -> !r.isEmpty()).orElse(false));
    }

    private void givenAnEventStreamOpen() throws Exception {
        HttpResponse<InputStream> opened =
                this.http.send(request("/api/v1/events").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(opened.statusCode()).isEqualTo(200);
        this.stream = new BufferedReader(new InputStreamReader(opened.body(), StandardCharsets.UTF_8));
        assertThat(this.stream.readLine()).startsWith(":");
        // The stream and the collector of recent events.
        waitFor(() -> this.fake.events.getNumberOfSubscribers() >= 2);
    }

    // When

    private void whenCalled(String verb, String target, String body) throws Exception {
        this.method = verb;
        this.path = target;
        HttpRequest.BodyPublisher content =
                body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        this.answer =
                this.http.send(request(target).method(verb, content).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void whenCalledWithoutTheToken(String verb, String target) throws Exception {
        this.method = verb;
        this.path = target;
        this.answer = this.http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.server.port() + target))
                        .method(verb, HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void whenTheEngineAnnounces(EngineEvent announced) throws IOException {
        this.fake.events.submit(announced);
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line = this.stream.readLine(); line != null; line = this.stream.readLine()) {
            if (line.isEmpty() && fields.containsKey("data")) {
                break;
            }
            if (!line.isEmpty() && !line.startsWith(":")) {
                int colon = line.indexOf(':');
                fields.put(line.substring(0, colon), line.substring(colon + 1).stripLeading());
            }
        }
        this.event = fields;
    }

    private void whenAnAnswerIsChecked(String verb, String target, int status, String body) {
        this.violations = CONTRACT.answerViolations(verb, target, status, "application/json", body);
    }

    private void whenTheSampledEventKindsAreListed() {
        this.sampled = ApiSamples.events().stream().map(Object::getClass).collect(Collectors.toSet());
    }

    // Then

    private void thenTheStatusIs(int status) {
        assertThat(this.answer.statusCode()).as(this.answer.body()).isEqualTo(status);
    }

    private void thenTheAnswerFollowsTheDocument() {
        String type = this.answer.headers().firstValue("Content-Type").orElse("");
        assertThat(CONTRACT.answerViolations(
                        this.method, this.path, this.answer.statusCode(), type, this.answer.body()))
                .as(this.answer.body())
                .isEmpty();
    }

    private void thenTheStreamedEventFollowsTheDocument() {
        assertThat(this.event).containsKeys("event", "id", "data");
        assertThat(CONTRACT.eventViolations(this.event.get("event"), this.event.get("id"), this.event.get("data")))
                .as(this.event.toString())
                .isEmpty();
    }

    private void thenItDoesNotFollowTheDocument() {
        assertThat(this.violations).isNotEmpty();
    }

    private void thenTheyAreEveryEventKindOfTheEngine() {
        assertThat(this.sampled)
                .containsExactlyInAnyOrderElementsOf(Arrays.asList(EngineEvent.class.getPermittedSubclasses()));
    }

    private void answer(String method, Object value) {
        this.fake.answer(method, args -> CompletableFuture.completedFuture(value));
    }

    private HttpRequest.Builder request(String target) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.server.port() + target))
                .header("Authorization", "Bearer " + this.token);
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < end) {
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
