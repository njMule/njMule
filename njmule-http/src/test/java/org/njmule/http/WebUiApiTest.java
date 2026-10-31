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
package org.njmule.http;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.remote.ApiToken;

/** The remote-control API of the core as the web interface serves it next to its pages. */
@Timeout(60)
class WebUiApiTest {

    private static final String SHELL_PAGE = "http://tauri.localhost";

    @TempDir
    Path dir;

    private final FakeEngine fake = new FakeEngine();
    private final HttpClient http = HttpClient.newHttpClient();
    private final CountDownLatch exited = new CountDownLatch(1);
    private WebUi ui;
    private String token;
    private HttpResponse<String> answer;
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
    void shouldAnswerUnderItsPrefixWithTheTokenMadeAtTheStart() throws Exception {
        givenTheApiRuns();

        whenCalled("GET", "/api/v1", Optional.of(this.token));

        thenTheStatusIs(200);
        thenTheBodyContains("\"name\":\"njMule\"", "\"api\":1");
    }

    @Test
    void shouldCountWrongTokensAsFailedLoginsOfTheirAddress() throws Exception {
        givenTheApiRuns();
        givenWrongTokensSent(Sessions.MAX_FAILURES);

        whenCalled("GET", "/api/v1/status", Optional.of("wrong"));

        thenTheStatusIs(429);
    }

    @Test
    void shouldStopNjMuleWhenTheApiAsks() throws Exception {
        givenTheApiRuns();

        whenCalled("POST", "/api/v1/shutdown", Optional.of(this.token));

        thenTheStatusIs(202);
        thenNjMuleWasStopped();
    }

    @Test
    void shouldEndTheEventStreamsWhenTheInterfaceStops() throws Exception {
        givenTheApiRuns();
        givenAnEventStreamOpen();

        whenTheInterfaceStops();

        thenTheStreamEnds();
    }

    @Test
    void shouldAnswerNotFoundWhileTheApiIsOff() throws Exception {
        givenTheWebInterfaceRunsWithoutTheApi();

        whenCalled("GET", "/api/v1/status", Optional.of("anything"));

        thenTheStatusIs(404);
        thenTheBodyContains("API is off");
    }

    @Test
    void shouldLetTheDesktopShellsPagesAskAcrossOrigins() throws Exception {
        givenTheApiRuns();

        whenCalledFrom("OPTIONS", "/api/v1/downloads", Optional.empty(), SHELL_PAGE);

        thenTheStatusIs(204);
        thenTheAnswerAllows(SHELL_PAGE);
    }

    @Test
    void shouldLetTheDesktopShellsPagesReadWhatTheTokenGets() throws Exception {
        givenTheApiRuns();

        whenCalledFrom("GET", "/api/v1", Optional.of(this.token), SHELL_PAGE);

        thenTheStatusIs(200);
        thenTheAnswerIsReadableBy(SHELL_PAGE);
    }

    @Test
    void shouldLetNoOtherPageAskAcrossOrigins() throws Exception {
        givenTheApiRuns();

        whenCalledFrom("OPTIONS", "/api/v1/downloads", Optional.empty(), "https://example.org");

        thenTheStatusIs(403);
        thenTheAnswerIsReadableBy(null);
    }

    // Given

    private void givenTheApiRuns() throws IOException {
        AppDirectories dirs = AppDirectories.underRoot(this.dir, true);
        this.fake.value("directories", dirs);
        this.ui = FreePorts.bound(port -> WebUi.start(
                this.fake.engine(),
                new WebUiSettings(true, port, false, "pbkdf2-sha512$1$AA$AA", true),
                Optional.of(this.exited::countDown),
                1,
                Duration.ofSeconds(1)));
        this.token = ApiToken.load(dirs.config()).orElseThrow();
    }

    private void givenTheWebInterfaceRunsWithoutTheApi() throws IOException {
        this.ui = FreePorts.bound(
                port -> WebUi.start(this.fake.engine(), new WebUiSettings(true, port, false, "pbkdf2-sha512$1$AA$AA")));
    }

    private void givenWrongTokensSent(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            whenCalled("GET", "/api/v1/status", Optional.of("wrong"));
            thenTheStatusIs(401);
        }
    }

    private void givenAnEventStreamOpen() throws Exception {
        HttpResponse<InputStream> opened = this.http.send(
                request("/api/v1/events", Optional.of(this.token)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(opened.statusCode()).isEqualTo(200);
        this.stream = new BufferedReader(new InputStreamReader(opened.body(), StandardCharsets.UTF_8));
        assertThat(this.stream.readLine()).startsWith(":");
    }

    // When

    private void whenCalled(String method, String path, Optional<String> token) throws Exception {
        this.answer = this.http.send(
                request(path, token)
                        .method(method, HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void whenCalledFrom(String method, String path, Optional<String> token, String origin) throws Exception {
        this.answer = this.http.send(
                request(path, token)
                        .header("Origin", origin)
                        .method(method, HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void whenTheInterfaceStops() {
        this.ui.close();
        this.ui = null;
    }

    // Then

    private void thenTheStatusIs(int status) {
        assertThat(this.answer.statusCode()).as(this.answer.body()).isEqualTo(status);
    }

    private void thenTheBodyContains(String... parts) {
        assertThat(this.answer.body()).contains(parts);
    }

    private void thenTheAnswerAllows(String origin) {
        thenTheAnswerIsReadableBy(origin);
        assertThat(this.answer.headers().firstValue("Access-Control-Allow-Headers"))
                .hasValueSatisfying(h -> assertThat(h).contains("Authorization"));
    }

    private void thenTheAnswerIsReadableBy(String origin) {
        assertThat(this.answer.headers().firstValue("Access-Control-Allow-Origin"))
                .isEqualTo(Optional.ofNullable(origin));
    }

    private void thenNjMuleWasStopped() throws InterruptedException {
        assertThat(this.exited.await(10, TimeUnit.SECONDS)).isTrue();
    }

    private void thenTheStreamEnds() throws IOException {
        String line = this.stream.readLine();
        while (line != null) {
            line = this.stream.readLine();
        }
        assertThat(line).isNull();
    }

    private HttpRequest.Builder request(String path, Optional<String> token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.ui.port() + path));
        token.ifPresent(t -> builder.header("Authorization", "Bearer " + t));
        return builder;
    }
}
