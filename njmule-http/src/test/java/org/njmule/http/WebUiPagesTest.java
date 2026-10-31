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
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.config.AppDirectories;

/** The shared files, Kad, log and settings pages, and the word of a new version. */
@Timeout(60)
class WebUiPagesTest {

    private static final String PASSWORD = "correct horse";

    private final FakeEngine fake = new FakeEngine();
    private final HttpClient http = HttpClient.newHttpClient();

    @TempDir
    Path dir;

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
    void shouldSaveTheHostNamesTheAppSendsWithTheSessionsToken() throws Exception {
        givenALoggedInUser();
        givenTheSettingsInTheConfigDirectory();

        whenTheAppPutsTheHostNames("NAS.local njmule.example.com", true);

        thenTheAnswerIs(200, "{\"lan\":false,\"hosts\":[\"nas.local\",\"njmule.example.com\"]}");
        thenTheSavedHostNamesAre("nas.local", "njmule.example.com");
    }

    @Test
    void shouldRefuseHostNamesSentWithoutTheSessionsToken() throws Exception {
        givenALoggedInUser();
        givenTheSettingsInTheConfigDirectory();

        whenTheAppPutsTheHostNames("nas.local", false);

        thenTheAnswerIs(403, "{\"error\":\"Expired session token: reload the page.\"}");
    }

    // Given

    private void givenALoggedInUser() throws Exception {
        String hash = Passwords.hash(PASSWORD.toCharArray(), new byte[16], 1000);
        this.ui = FreePorts.bound(port -> WebUi.start(this.fake.engine(), new WebUiSettings(true, port, false, hash)));
        HttpResponse<String> in = post("/login", Map.of("password", PASSWORD));
        assertThat(in.statusCode()).isEqualTo(303);
        String set = in.headers().firstValue("Set-Cookie").orElseThrow();
        this.cookie = set.substring(0, set.indexOf(';'));
    }

    private void givenTheSettingsInTheConfigDirectory() throws Exception {
        AppDirectories dirs = AppDirectories.resolve(Optional.of(this.dir));
        this.fake.value("directories", dirs);
        new WebUiSettings(true, 4711, false, "hash").save(dirs.config());
    }

    // When

    // Then

    private void thenTheAnswerIs(int status, String text) {
        assertThat(this.answer.statusCode()).as(this.answer.body()).isEqualTo(status);
        assertThat(this.answer.body()).contains(text);
    }

    private void thenTheSavedHostNamesAre(String... names) {
        assertThat(WebUiSettings.load(
                                AppDirectories.resolve(Optional.of(this.dir)).config())
                        .hosts())
                .containsExactly(names);
    }

    private void whenTheAppPutsTheHostNames(String hosts, boolean withToken) throws Exception {
        HttpRequest.Builder put = request("/webui/hosts")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .PUT(HttpRequest.BodyPublishers.ofString("hosts=" + URLEncoder.encode(hosts, StandardCharsets.UTF_8)));
        if (withToken) {
            Matcher m =
                    Pattern.compile("name=\"njmule-csrf\" content=\"([^\"]+)\"").matcher(get("/").body());
            assertThat(m.find()).isTrue();
            put.header("X-NjMule-CSRF", m.group(1));
        }
        this.answer = this.http.send(put.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return this.http.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, Map<String, String> fields) throws Exception {
        String body = fields.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return this.http.send(
                request(path)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.ui.port() + path))
                .timeout(Duration.ofSeconds(30));
        if (this.cookie != null) {
            b.header("Cookie", this.cookie);
        }
        return b;
    }
}
