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
import java.net.ServerSocket;
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
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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

/** The web interface driving an engine against a fake server: connect, search, download. */
@Timeout(120)
class WebUiFlowTest {

    private static final Pattern CSRF = Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"");

    @TempDir
    Path dir;

    private final HttpClient http = HttpClient.newHttpClient();
    private FakeServer server;
    private NjMuleEngine engine;
    private WebUi ui;
    private int port;
    private String cookie;
    private String results;
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
        givenConnectedToTheServerThroughThePages();
        givenTheStatusSoonShows("(HighID)");
        givenThePageShows("/servers", "<h1>Servers</h1>", "Disconnect");
        givenASearchForNothingLeadsTo("/search");
        givenASearchStartedFor("linux", "SERVER");
        givenTheResultsSoonShow("linux.txt");
        givenTheResultsShow("2 results", "linux web.iso");
        givenTheResultsListBefore("linux web.iso", "linux.txt");
        givenTheFirstResultDownloaded();
        givenTheTransfersShowTheDownloadOf("linux web.iso");
        givenADownloadFromTheUnknownSearchRefused("424242");
        givenThePageShowsNoResults("/search?id=nonsense");

        whenDisconnectedThroughThePages();

        thenTheStatusSoonShows("not connected");
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
                dirs.config().resolve("njmule.properties"), "net.tcpPort=" + freePort() + "\nnet.kad.enabled=false\n");
        this.engine = NjMuleEngine.start(new EngineConfig(dirs));
        this.ui = WebUi.start(
                this.engine,
                new WebUiSettings(true, freePort(), false, Passwords.hash(password.toCharArray(), new SecureRandom())));
        this.port = this.ui.port();
    }

    private void givenLoggedInWith(String password) throws Exception {
        HttpResponse<String> login = post("/login", Map.of("password", password));
        String set = login.headers().firstValue("Set-Cookie").orElseThrow();
        this.cookie = set.substring(0, set.indexOf(';'));
    }

    private void givenConnectedToTheServerThroughThePages() throws Exception {
        String address = "127.0.0.1:" + this.server.port();
        HttpResponse<String> connect =
                post("/servers", Map.of("action", "connect", "address", address, "csrf", csrf()));
        assertThat(connect.statusCode()).isEqualTo(303);
    }

    private void givenTheStatusSoonShows(String text) throws InterruptedException {
        thenTheStatusSoonShows(text);
    }

    private void givenThePageShows(String path, String... parts) {
        assertThat(page(path)).contains(parts);
    }

    private void givenASearchForNothingLeadsTo(String location) throws Exception {
        assertThat(post("/search", Map.of("query", " ", "csrf", csrf()))
                        .headers()
                        .firstValue("Location"))
                .hasValue(location);
    }

    private void givenASearchStartedFor(String query, String scope) throws Exception {
        HttpResponse<String> search = post("/search", Map.of("query", query, "scope", scope, "csrf", csrf()));
        this.results = search.headers().firstValue("Location").orElseThrow();
        assertThat(this.results).startsWith("/search?id=");
    }

    private void givenTheResultsSoonShow(String text) throws InterruptedException {
        await(() -> page(this.results).contains(text));
    }

    private void givenTheResultsShow(String... parts) {
        givenThePageShows(this.results, parts);
    }

    private void givenTheResultsListBefore(String first, String second) {
        String found = page(this.results);
        assertThat(found.indexOf(first)).isLessThan(found.indexOf(second));
    }

    private void givenTheFirstResultDownloaded() throws Exception {
        Matcher download =
                Pattern.compile("name=\"download\" value=\"([0-9A-F]{32})\"").matcher(page(this.results));
        assertThat(download.find()).isTrue();
        this.hash = download.group(1);
        String id = this.results.substring(this.results.indexOf('=') + 1);
        HttpResponse<String> taken = post("/search", Map.of("search", id, "download", this.hash, "csrf", csrf()));
        assertThat(taken.headers().firstValue("Location")).hasValue("/transfers");
    }

    private void givenTheTransfersShowTheDownloadOf(String name) {
        givenThePageShows("/transfers", name, this.hash);
    }

    private void givenADownloadFromTheUnknownSearchRefused(String search) throws Exception {
        HttpResponse<String> gone = post("/search", Map.of("search", search, "download", this.hash, "csrf", csrf()));
        assertThat(gone.statusCode()).isEqualTo(400);
        assertThat(gone.body()).contains("search again");
    }

    private void givenThePageShowsNoResults(String path) {
        assertThat(page(path)).doesNotContain("results;");
    }

    private void whenDisconnectedThroughThePages() throws Exception {
        post("/servers", Map.of("action", "disconnect", "csrf", csrf()));
    }

    private void thenTheStatusSoonShows(String text) throws InterruptedException {
        await(() -> page("/").contains(text));
    }

    private String csrf() throws Exception {
        Matcher m = CSRF.matcher(page("/"));
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private String page(String path) {
        try {
            return this.http
                    .send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString())
                    .body();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpResponse<String> post(String path, Map<String, String> fields) throws Exception {
        String body = fields.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return this.http.send(
                request(path).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
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

    private static int freePort() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }
}
