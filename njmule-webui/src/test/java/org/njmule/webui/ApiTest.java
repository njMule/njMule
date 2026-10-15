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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.PartMap;
import org.njmule.core.api.SearchId;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.config.Preferences;

/** The remote-control API against an engine the test controls. */
@Timeout(60)
class ApiTest {

    private static final String HASH = "AB".repeat(16);
    private static final String LINK = "ed2k://|file|talk.bin|5000|" + HASH + "|/";

    @TempDir
    Path dir;

    private final FakeEngine fake = new FakeEngine();
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final CountDownLatch exited = new CountDownLatch(1);
    private WebUi ui;
    private String token;
    private HttpResponse<String> answer;
    private Preferences preferences = Preferences.defaults();
    private BufferedReader stream;

    @AfterEach
    void stop() throws IOException {
        if (this.stream != null) {
            this.stream.close();
        }
        if (this.ui != null) {
            this.ui.close();
        }
        this.fake.events.close();
    }

    @Test
    void shouldRefuseACallWithoutTheToken() throws Exception {
        givenTheApiRuns();

        whenCalled("GET", "/api/v1/status", Optional.empty(), "");

        thenTheStatusIs(401);
        thenTheAnswerAsksForABearerToken();
    }

    @Test
    void shouldLockOutAnAddressThatKeepsSendingWrongTokens() throws Exception {
        givenTheApiRuns();
        givenWrongTokensSent(Sessions.MAX_FAILURES);

        whenCalled("GET", "/api/v1/status", Optional.of("wrong"), "");

        thenTheStatusIs(429);
    }

    @Test
    void shouldTellItsNameAndVersionToATokenHolder() throws Exception {
        givenTheApiRuns();

        whenCalled("GET", "/api/v1", "");

        thenTheStatusIs(200);
        thenTheBodyContains("\"name\":\"njMule\"", "\"api\":1");
    }

    @Test
    void shouldListTheDownloadsAsJson() throws Exception {
        givenTheApiRuns();
        givenTheEngineDownloads(download());

        whenCalled("GET", "/api/v1/downloads", "");

        thenTheStatusIs(200);
        thenTheBodyContains(
                "\"hash\":\"" + HASH + "\"",
                "\"name\":\"talk.bin\"",
                "\"status\":\"paused\"",
                "\"priority\":\"normal\"");
    }

    @Test
    void shouldAddTheDownloadsOfTheLinksSent() throws Exception {
        givenTheApiRuns();
        givenTheEngineAddsDownloads();

        whenCalled("POST", "/api/v1/downloads", "{\"links\":[\"" + LINK + "\"]}");

        thenTheStatusIs(201);
        thenTheEngineWasAsked("addDownload talk.bin");
    }

    @Test
    void shouldRefuseABodyThatIsNotALink() throws Exception {
        givenTheApiRuns();

        whenCalled("POST", "/api/v1/downloads", "{\"links\":[\"http://example.org/\"]}");

        thenTheStatusIs(400);
        thenTheBodyContains("Not an eD2K file link");
    }

    @Test
    void shouldRefuseMalformedJson() throws Exception {
        givenTheApiRuns();

        whenCalled("POST", "/api/v1/downloads", "{\"links\":");

        thenTheStatusIs(400);
        thenTheBodyContains("Malformed JSON");
    }

    @Test
    void shouldPauseAKnownDownload() throws Exception {
        givenTheApiRuns();
        givenTheEngineDownloads(download());
        givenTheEnginePausesOnly(HASH);

        whenCalled("POST", "/api/v1/downloads/" + HASH + "/pause", "");

        thenTheStatusIs(200);
    }

    @Test
    void shouldRenameADownload() throws Exception {
        givenTheApiRuns();
        givenTheEngineDownloads(download());
        givenTheEngineRenamesDownloads();

        whenCalled("PUT", "/api/v1/downloads/" + HASH + "/name", "{\"name\":\"Debian 13.iso\"}");

        thenTheStatusIs(200);
        thenTheEngineWasAsked("renameDownload Debian 13.iso");
    }

    @Test
    void shouldRefuseABlankNameForADownload() throws Exception {
        givenTheApiRuns();
        givenTheEngineDownloads(download());
        givenTheEngineRenamesDownloads();

        whenCalled("PUT", "/api/v1/downloads/" + HASH + "/name", "{\"name\":\" \"}");

        thenTheStatusIs(400);
    }

    @Test
    void shouldReportAnUnknownDownloadToPause() throws Exception {
        givenTheApiRuns();
        givenTheEngineDownloads(download());
        givenTheEnginePausesOnly(HASH);

        whenCalled("POST", "/api/v1/downloads/" + "CD".repeat(16) + "/pause", "");

        thenTheStatusIs(404);
    }

    @Test
    void shouldRefuseAMethodACallDoesNotTake() throws Exception {
        givenTheApiRuns();

        whenCalled("DELETE", "/api/v1/status", "");

        thenTheStatusIs(405);
    }

    @Test
    void shouldGiveTheResultsOfASearchItStarted() throws Exception {
        givenTheApiRuns();
        givenTheEngineSearchesAs(7);
        givenASearchStarted("{\"query\":\"talk\",\"scope\":\"kad\"}", "\"id\":7");
        givenTheEngineFinds(7, "talk.bin");

        whenTheResultsAreAskedUntilTheyCome("/api/v1/searches/7", "talk.bin");

        thenTheStatusIs(200);
        thenTheBodyContains("\"name\":\"talk.bin\"");
        thenTheEngineWasAsked("search talk KAD");
    }

    @Test
    void shouldSetTheSpeedLimits() throws Exception {
        givenTheApiRuns();
        givenTheEngineKeepsPreferences();

        whenCalled("PUT", "/api/v1/limits", "{\"uploadKiBps\":50,\"downloadKiBps\":0}");

        thenTheStatusIs(200);
        thenTheBodyContains("\"uploadKiBps\":50", "\"downloadKiBps\":0");
        thenTheUploadLimitIs(50);
    }

    @Test
    void shouldStreamTheEngineEvents() throws Exception {
        givenTheApiRuns();
        givenAnEventStreamOpen("download");

        whenTheEngineAnnounces(new EngineEvent.DownloadAdded(download()));

        thenTheStreamCarries("event: download.added", "\"name\":\"talk.bin\"");
    }

    @Test
    void shouldEndTheStreamsWhenTheInterfaceStops() throws Exception {
        givenTheApiRuns();
        givenAnEventStreamOpen("");

        whenTheInterfaceStops();

        thenTheStreamEnds();
    }

    @Test
    void shouldStopNjMuleWhenAsked() throws Exception {
        givenTheApiRuns();

        whenCalled("POST", "/api/v1/shutdown", "");

        thenTheStatusIs(202);
        thenNjMuleWasStopped();
    }

    @Test
    void shouldAnswerNotFoundWhileTheApiIsOff() throws Exception {
        givenTheWebInterfaceRunsWithoutTheApi();

        whenCalled("GET", "/api/v1/status", Optional.of("anything"), "");

        thenTheStatusIs(404);
        thenTheBodyContains("API is off");
    }

    // Given

    private void givenTheApiRuns() throws IOException {
        AppDirectories dirs = AppDirectories.underRoot(this.dir, true);
        this.fake.value("directories", dirs);
        this.ui = WebUi.start(
                this.fake.engine(),
                new WebUiSettings(true, freePort(), false, "pbkdf2-sha512$1$AA$AA", true),
                Optional.of(this.exited::countDown),
                1,
                Duration.ofSeconds(1));
        this.token = ApiToken.load(dirs.config()).orElseThrow();
    }

    private void givenTheWebInterfaceRunsWithoutTheApi() throws IOException {
        this.ui = WebUi.start(this.fake.engine(), new WebUiSettings(true, freePort(), false, "pbkdf2-sha512$1$AA$AA"));
    }

    private void givenWrongTokensSent(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            whenCalled("GET", "/api/v1/status", Optional.of("wrong"), "");
            thenTheStatusIs(401);
        }
    }

    private void givenTheEngineDownloads(DownloadInfo download) {
        this.fake.answer("downloads", args -> CompletableFuture.completedFuture(List.of(download)));
    }

    private void givenTheEngineAddsDownloads() {
        this.fake.answer("addDownload", args -> {
            Ed2kLink.FileLink link = (Ed2kLink.FileLink) args[0];
            this.calls.add("addDownload " + link.name());
            return CompletableFuture.completedFuture(download());
        });
    }

    // As the engine does: a blank name is refused.
    private void givenTheEngineRenamesDownloads() {
        this.fake.answer("renameDownload", args -> {
            String name = (String) args[1];
            if (name.isBlank()) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("a download needs a name"));
            }
            this.calls.add("renameDownload " + name);
            return CompletableFuture.completedFuture(true);
        });
    }

    private void givenTheEnginePausesOnly(String hash) {
        this.fake.answer(
                "pauseDownload", args -> CompletableFuture.completedFuture(args[0].equals(Hash16.parseHex(hash))));
    }

    private void givenTheEngineSearchesAs(long id) {
        this.fake.answer("search", args -> {
            SearchRequest request = (SearchRequest) args[0];
            this.calls.add("search " + request.expression() + " " + request.scope());
            return CompletableFuture.completedFuture(new SearchId(id));
        });
    }

    private void givenASearchStarted(String body, String answered) throws Exception {
        whenCalled("POST", "/api/v1/searches", body);
        thenTheStatusIs(201);
        thenTheBodyContains(answered);
    }

    private void givenTheEngineFinds(long search, String name) {
        this.fake.events.submit(new EngineEvent.SearchResults(
                new SearchId(search),
                List.of(new SearchResult(Hash16.parseHex(HASH), name, 5000, 3, 1, Optional.empty()))));
    }

    @SuppressWarnings("unchecked")
    private void givenTheEngineKeepsPreferences() {
        this.fake.answer("updatePreferences", args -> {
            this.preferences = ((UnaryOperator<Preferences>) args[0]).apply(this.preferences);
            return CompletableFuture.completedFuture(this.preferences);
        });
    }

    private void givenAnEventStreamOpen(String types) throws Exception {
        HttpResponse<InputStream> opened = this.http.send(
                request("/api/v1/events?types=" + types, Optional.of(this.token))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(opened.statusCode()).isEqualTo(200);
        assertThat(opened.headers().firstValue("Content-Type")).hasValue("text/event-stream; charset=utf-8");
        this.stream = new BufferedReader(new InputStreamReader(opened.body(), StandardCharsets.UTF_8));
        assertThat(this.stream.readLine()).startsWith(":");
        // Subscribed once the stream is open.
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (this.fake.events.getNumberOfSubscribers() < 2 && System.nanoTime() < end) {
            Thread.sleep(10);
        }
    }

    // When

    private void whenCalled(String method, String path, String body) throws Exception {
        whenCalled(method, path, Optional.of(this.token), body);
    }

    private void whenCalled(String method, String path, Optional<String> token, String body) throws Exception {
        HttpRequest.BodyPublisher content =
                body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        this.answer = this.http.send(
                request(path, token).method(method, content).build(), HttpResponse.BodyHandlers.ofString());
    }

    // Results come through the engine's events, delivered on the publisher's threads.
    private void whenTheResultsAreAskedUntilTheyCome(String path, String name) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            whenCalled("GET", path, "");
        } while (!this.answer.body().contains(name) && System.nanoTime() < end);
    }

    private void whenTheEngineAnnounces(EngineEvent event) {
        this.fake.events.submit(event);
    }

    private void whenTheInterfaceStops() {
        this.ui.close();
        this.ui = null;
    }

    // Then

    private void thenTheStatusIs(int status) {
        assertThat(this.answer.statusCode()).as(this.answer.body()).isEqualTo(status);
    }

    private void thenTheAnswerAsksForABearerToken() {
        assertThat(this.answer.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");
        assertThat(this.answer.headers().firstValue("Content-Type")).hasValue("application/json; charset=utf-8");
    }

    private void thenTheBodyContains(String... parts) {
        assertThat(this.answer.body()).contains(parts);
    }

    private void thenTheEngineWasAsked(String call) {
        assertThat(this.calls).contains(call);
    }

    private void thenTheStreamCarries(String... parts) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String line = this.stream.readLine(); line != null; line = this.stream.readLine()) {
            if (line.startsWith(":") || line.isEmpty()) {
                continue;
            }
            lines.add(line);
            if (line.startsWith("data:")) {
                break;
            }
        }
        assertThat(String.join("\n", lines)).contains(parts);
    }

    private void thenTheStreamEnds() throws IOException {
        String line = this.stream.readLine();
        while (line != null) {
            line = this.stream.readLine();
        }
        assertThat(line).isNull();
    }

    private void thenTheUploadLimitIs(int kibps) {
        assertThat(this.preferences.maxUploadKiBps()).isEqualTo(kibps);
    }

    private void thenNjMuleWasStopped() throws InterruptedException {
        assertThat(this.exited.await(10, TimeUnit.SECONDS)).isTrue();
    }

    private DownloadInfo download() {
        return new DownloadInfo(
                Hash16.parseHex(HASH),
                "talk.bin",
                5000,
                0,
                DownloadInfo.Status.PAUSED,
                DownloadPriority.NORMAL,
                0,
                0,
                0,
                0,
                PartMap.EMPTY);
    }

    private HttpRequest.Builder request(String path, Optional<String> token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.ui.port() + path));
        token.ifPresent(t -> builder.header("Authorization", "Bearer " + t));
        return builder;
    }

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }
}
