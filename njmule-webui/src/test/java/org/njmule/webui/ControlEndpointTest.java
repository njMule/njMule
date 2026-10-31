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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.config.AppDirectories;

/** The API as the desktop shell reaches it: on the loopback address, with the token of the run, from its own pages. */
@Timeout(60)
class ControlEndpointTest {

    private static final String SHELL_PAGE = "http://tauri.localhost";

    @TempDir
    Path dir;

    private final FakeEngine fake = new FakeEngine();
    private final HttpClient http = HttpClient.newHttpClient();
    private final CountDownLatch exited = new CountDownLatch(1);
    private ControlEndpoint endpoint;
    private HttpResponse<String> answer;

    @AfterEach
    void stop() {
        if (this.endpoint != null) {
            this.endpoint.close();
        }
        this.fake.events.close();
    }

    @Test
    void shouldListenOnTheLoopbackAddressOnly() throws Exception {
        whenTheEndpointStarts();

        thenItsAddressStartsWith("http://127.0.0.1:");
    }

    @Test
    void shouldAnswerTheTokenOfTheRun() throws Exception {
        givenTheEndpointRuns();

        whenCalled("GET", "/api/v1", Optional.of(this.endpoint.token()), Optional.empty());

        thenTheStatusIs(200);
        thenTheBodyContains("\"name\":\"njMule\"");
    }

    @Test
    void shouldRefuseACallerWithoutTheToken() throws Exception {
        givenTheEndpointRuns();

        whenCalled("GET", "/api/v1/status", Optional.of("not the token"), Optional.empty());

        thenTheStatusIs(401);
    }

    @Test
    void shouldRefuseAPageOfAnotherOriginEvenWithTheToken() throws Exception {
        givenTheEndpointRuns();

        whenCalled("GET", "/api/v1", Optional.of(this.endpoint.token()), Optional.of("https://example.org"));

        thenTheStatusIs(403);
    }

    @Test
    void shouldLetTheShellsPagesCallAcrossOrigins() throws Exception {
        givenTheEndpointRuns();

        whenCalled("OPTIONS", "/api/v1/downloads", Optional.empty(), Optional.of(SHELL_PAGE));

        thenTheStatusIs(204);
        thenTheAnswerAllows(SHELL_PAGE);
    }

    @Test
    void shouldLetTheWindowTurnTheWebInterfaceOn() throws Exception {
        givenTheEndpointRuns();

        whenTheWindowSends("PATCH", "/api/v1/webui", "{\"enabled\":true,\"password\":\"long enough\"}");

        thenTheStatusIs(200);
        thenTheWebInterfaceStartsNextTime();
    }

    @Test
    void shouldStopNjMuleWhenTheWindowAsks() throws Exception {
        givenTheEndpointRuns();

        whenCalled("POST", "/api/v1/shutdown", Optional.of(this.endpoint.token()), Optional.of(SHELL_PAGE));

        thenTheStatusIs(202);
        thenNjMuleWasStopped();
    }

    // Given

    private void givenTheEndpointRuns() throws IOException {
        whenTheEndpointStarts();
    }

    // When

    private void whenTheEndpointStarts() throws IOException {
        this.fake.value("directories", AppDirectories.underRoot(this.dir, true));
        this.endpoint = ControlEndpoint.start(this.fake.engine(), this.exited::countDown);
    }

    private void whenCalled(String method, String path, Optional<String> token, Optional<String> origin)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create(this.endpoint.url().replace("/api/v1", "") + path));
        token.ifPresent(t -> request.header("Authorization", "Bearer " + t));
        origin.ifPresent(o -> request.header("Origin", o));
        this.answer = this.http.send(
                request.method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void whenTheWindowSends(String method, String path, String body) throws Exception {
        this.answer = this.http.send(
                HttpRequest.newBuilder(URI.create(this.endpoint.url().replace("/api/v1", "") + path))
                        .header("Authorization", "Bearer " + this.endpoint.token())
                        .header("Origin", SHELL_PAGE)
                        .method(method, HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // Then

    private void thenTheWebInterfaceStartsNextTime() {
        assertThat(WebUiSettings.load(AppDirectories.underRoot(this.dir, true).config())
                        .usable())
                .isTrue();
    }

    private void thenItsAddressStartsWith(String start) {
        assertThat(this.endpoint.url()).startsWith(start).endsWith("/api/v1");
    }

    private void thenTheStatusIs(int status) {
        assertThat(this.answer.statusCode()).as(this.answer.body()).isEqualTo(status);
    }

    private void thenTheBodyContains(String part) {
        assertThat(this.answer.body()).contains(part);
    }

    private void thenTheAnswerAllows(String origin) {
        assertThat(this.answer.headers().firstValue("Access-Control-Allow-Origin"))
                .contains(origin);
        assertThat(this.answer.headers().firstValue("Access-Control-Allow-Headers"))
                .hasValueSatisfying(h -> assertThat(h).contains("Authorization"));
    }

    private void thenNjMuleWasStopped() throws InterruptedException {
        assertThat(this.exited.await(10, TimeUnit.SECONDS)).isTrue();
    }
}
