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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.protocol.ed2k.FileEntry;
import org.njmule.core.util.Ipv4;
import org.njmule.webui.Passwords;
import org.njmule.webui.WebUi;
import org.njmule.webui.WebUiSettings;

/** The web interface's app calls driving an engine against a fake server: log in, connect, search, download. */
@Timeout(120)
class WebUiFlowTest {

    // The session's token, as the app's page carries it.
    private static final Pattern CSRF = Pattern.compile("name=\"njmule-csrf\" content=\"([^\"]+)\"");
    private static final String API = "/api/v1";

    @TempDir
    Path dir;

    private final HttpClient http = HttpClient.newHttpClient();
    private FakeServer server;
    private NjMuleEngine engine;
    private WebUi ui;
    private int port;
    private String cookie;
    private String token;
    private String search;
    private String hash;

    @AfterEach
    void stop() throws IOException {
        if (this.ui != null) {
            this.ui.close();
        }
        if (this.engine != null) {
            this.engine.close();
        }
        if (this.server != null) {
            this.server.close();
        }
    }

    @Test
    void shouldConnectSearchDownloadAResultAndDisconnect() throws Exception {
        givenAServerFinding(FakeServer.file("linux web.iso", 700_000_000L, 40), FakeServer.file("linux.txt", 10, 2));
        givenNjMuleRunningWithTheWebInterfaceAndThePassword("flow password");
        givenLoggedInWith("flow password");
        givenConnectedToTheServer();
        givenTheStatusSoonShows("\"server\":{");
        givenASearchStartedFor("linux", "server");
        givenTheResultsSoonShow("linux.txt");
        givenTheResultsListBefore("linux web.iso", "linux.txt");
        givenTheFirstResultDownloaded();
        givenTheDownloadsShow("linux web.iso");
        givenADownloadFromAnUnknownSearchNotFound("424242");

        whenDisconnected();

        thenTheStatusSoonShows("\"server\":null");
    }

    private void givenAServerFinding(FileEntry... files) throws IOException {
        this.server = FakeServer.start(
                InetAddress.getLoopbackAddress(),
                new FakeServer.Config(Ipv4.toEd2k(Ipv4.parse("1.2.3.4")), "welcome", List.of(files)));
    }

    private void givenNjMuleRunningWithTheWebInterfaceAndThePassword(String password) throws Exception {
        AppDirectories dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), true);
        Files.createDirectories(dirs.config());
        Files.writeString(
                dirs.config().resolve("njmule.properties"),
                "net.tcpPort=" + FreePorts.tcp() + "\nnet.kad.enabled=false\n");
        this.engine = NjMuleEngine.start(new EngineConfig(dirs));
        this.ui = WebUi.start(
                this.engine,
                new WebUiSettings(
                        true, FreePorts.tcp(), false, Passwords.hash(password.toCharArray(), new SecureRandom())));
        this.port = this.ui.port();
    }

    private void givenLoggedInWith(String password) throws Exception {
        HttpResponse<String> login = this.http.send(
                request("/login")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "password=" + URLEncoder.encode(password, StandardCharsets.UTF_8)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        String set = login.headers().firstValue("Set-Cookie").orElseThrow();
        this.cookie = set.substring(0, set.indexOf(';'));
        Matcher m = CSRF.matcher(get("/"));
        assertThat(m.find()).isTrue();
        this.token = m.group(1);
    }

    private void givenConnectedToTheServer() throws Exception {
        String address = "127.0.0.1:" + this.server.port();
        assertThat(call("POST", "/servers/connect", "{\"address\":\"" + address + "\"}")
                        .statusCode())
                .isEqualTo(200);
    }

    private void givenTheStatusSoonShows(String text) throws InterruptedException {
        thenTheStatusSoonShows(text);
    }

    private void givenASearchStartedFor(String query, String scope) throws Exception {
        HttpResponse<String> started =
                call("POST", "/searches", "{\"query\":\"" + query + "\",\"scope\":\"" + scope + "\"}");
        assertThat(started.statusCode()).isEqualTo(201);
        Matcher id = Pattern.compile("\"id\":([0-9]+)").matcher(started.body());
        assertThat(id.find()).isTrue();
        this.search = id.group(1);
    }

    private void givenTheResultsSoonShow(String text) throws InterruptedException {
        await(() -> get(API + "/searches/" + this.search).contains(text));
    }

    // The results come with the most sources first.
    private void givenTheResultsListBefore(String first, String second) {
        String found = get(API + "/searches/" + this.search);
        assertThat(found.indexOf(first)).isLessThan(found.indexOf(second));
    }

    private void givenTheFirstResultDownloaded() throws Exception {
        Matcher first = Pattern.compile("\"hash\":\"([0-9A-F]{32})\"").matcher(get(API + "/searches/" + this.search));
        assertThat(first.find()).isTrue();
        this.hash = first.group(1);
        HttpResponse<String> taken =
                call("POST", "/searches/" + this.search + "/download", "{\"hash\":\"" + this.hash + "\"}");
        assertThat(taken.statusCode()).isEqualTo(201);
    }

    private void givenTheDownloadsShow(String name) {
        assertThat(get(API + "/downloads")).contains(name, this.hash);
    }

    private void givenADownloadFromAnUnknownSearchNotFound(String unknown) throws Exception {
        HttpResponse<String> gone =
                call("POST", "/searches/" + unknown + "/download", "{\"hash\":\"" + this.hash + "\"}");
        assertThat(gone.statusCode()).isEqualTo(404);
    }

    private void whenDisconnected() throws Exception {
        assertThat(call("POST", "/servers/disconnect", "{}").statusCode()).isEqualTo(200);
    }

    private void thenTheStatusSoonShows(String text) throws InterruptedException {
        await(() -> get(API + "/status").contains(text));
    }

    // A change, as the app makes it: JSON with the session's token in its header.
    private HttpResponse<String> call(String method, String path, String json) throws Exception {
        return this.http.send(
                request(API + path)
                        .header("Content-Type", "application/json")
                        .header("X-NjMule-CSRF", this.token)
                        .method(method, HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String get(String path) {
        try {
            return this.http
                    .send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString())
                    .body();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + path))
                .timeout(Duration.ofSeconds(30));
        if (this.cookie != null) {
            b.header("Cookie", this.cookie);
        }
        return b;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("waited too long").isLessThan(end);
            Thread.sleep(50);
        }
    }
}
