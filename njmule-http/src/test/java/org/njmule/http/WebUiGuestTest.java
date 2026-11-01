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

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** A guest, who logs in with a password of its own and may only look, then logs out. */
@Timeout(60)
class WebUiGuestTest {

    private static final String PASSWORD = "correct horse";
    private static final String GUEST_PASSWORD = "battery staple";
    private static final Pattern APP_TOKEN = Pattern.compile("name=\"njmule-csrf\" content=\"([^\"]+)\"");

    private final FakeEngine fake = new FakeEngine();
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<String> asked = new CopyOnWriteArrayList<>();

    private WebUi ui;
    private String cookie;
    private HttpResponse<String> answer;

    @AfterEach
    void stop() {
        if (this.ui != null) {
            this.ui.close();
        }
        this.fake.events.close();
    }

    @Test
    void shouldShowTheAppToTheGuestAsToAGuest() throws Exception {
        givenTheWebInterfaceRunsWithAGuest();
        givenLoggedInWith(GUEST_PASSWORD);

        whenThePageIsAsked("/");

        thenTheAnswerIs(200, "name=\"njmule-role\" content=\"guest\"");
    }

    @Test
    void shouldShowTheAppToTheAdminAsToTheAdmin() throws Exception {
        givenTheWebInterfaceRunsWithAGuest();
        givenLoggedInWith(PASSWORD);

        whenThePageIsAsked("/");

        thenTheAnswerIs(200, "name=\"njmule-role\" content=\"admin\"");
    }

    @Test
    void shouldRefuseTheGuestAChangeEvenWithItsSessionsToken() throws Exception {
        givenTheWebInterfaceRunsWithAGuest();
        givenTheEngineDisconnects();
        givenLoggedInWith(GUEST_PASSWORD);

        whenTheAppCalls("POST", "/api/v1/servers/disconnect");

        thenTheAnswerIs(403, "A guest can only look");
        thenTheEngineWasNotAsked();
    }

    @Test
    void shouldRefuseTheGuestTheSettingsOfTheWebInterface() throws Exception {
        givenTheWebInterfaceRunsWithAGuest();
        givenLoggedInWith(GUEST_PASSWORD);

        whenThePageIsAsked("/api/v1/webui");

        thenTheAnswerIs(403, "A guest can only look");
    }

    @Test
    void shouldEndTheGuestsSessionOnLogout() throws Exception {
        givenTheWebInterfaceRunsWithAGuest();
        givenLoggedInWith(GUEST_PASSWORD);

        whenTheAppLogsOut();

        thenTheAnswerLeadsTo("login");
        thenTheSessionIsOver();
    }

    // Given

    private void givenTheWebInterfaceRunsWithAGuest() throws Exception {
        this.ui = FreePorts.bound(port -> WebUi.start(this.fake.engine(), settings(port)));
    }

    private void givenTheEngineDisconnects() {
        this.fake.answer("disconnect", args -> {
            this.asked.add("disconnect");
            return CompletableFuture.completedFuture(null);
        });
    }

    private void givenLoggedInWith(String password) throws Exception {
        HttpResponse<String> in = this.http.send(
                request("/login")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(form(Map.of("password", password)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(in.statusCode()).isEqualTo(303);
        String set = in.headers().firstValue("Set-Cookie").orElseThrow();
        this.cookie = set.substring(0, set.indexOf(';'));
    }

    // When

    private void whenThePageIsAsked(String path) throws Exception {
        this.answer = get(path);
    }

    private void whenTheAppCalls(String method, String path) throws Exception {
        String token = token();
        this.answer = this.http.send(
                request(path)
                        .header("X-NjMule-CSRF", token)
                        .method(method, HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // The app sends the logout form with the session's token, as a form of the page.
    private void whenTheAppLogsOut() throws Exception {
        String token = token();
        this.answer = this.http.send(
                request("/logout")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(form(Map.of("csrf", token)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // Then

    private void thenTheAnswerIs(int status, String part) {
        assertThat(this.answer.statusCode()).as(this.answer.body()).isEqualTo(status);
        assertThat(this.answer.body()).contains(part);
    }

    private void thenTheAnswerLeadsTo(String location) {
        assertThat(this.answer.statusCode()).as(this.answer.body()).isEqualTo(303);
        assertThat(this.answer.headers().firstValue("Location")).hasValue(location);
    }

    private void thenTheSessionIsOver() throws Exception {
        assertThat(get("/").statusCode()).isEqualTo(303);
    }

    private void thenTheEngineWasNotAsked() {
        assertThat(this.asked).isEmpty();
    }

    private static WebUiSettings settings(int port) {
        return new WebUiSettings(true, port, false, Passwords.hash(PASSWORD.toCharArray(), new byte[16], 1000))
                .withGuest(Passwords.hash(GUEST_PASSWORD.toCharArray(), new byte[16], 1000));
    }

    private String token() throws Exception {
        Matcher m = APP_TOKEN.matcher(get("/").body());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return this.http.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.ui.port() + path))
                .timeout(Duration.ofSeconds(30));
        if (this.cookie != null) {
            b.header("Cookie", this.cookie);
        }
        return b;
    }

    private static HttpRequest.BodyPublisher form(Map<String, String> fields) {
        return HttpRequest.BodyPublishers.ofString(fields.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&")));
    }
}
