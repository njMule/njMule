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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.util.Ipv4;
import org.njmule.webui.Passwords;
import org.njmule.webui.WebUi;
import org.njmule.webui.WebUiSettings;

/** The web interface driving an engine against a fake server: connect, search, download. */
@Timeout(120)
class WebUiFlowTest {

    private static final Pattern CSRF = Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"");

    @TempDir Path dir;

    private final HttpClient http = HttpClient.newHttpClient();
    private int port;
    private String cookie;

    @Test
    void connectsSearchesAndDownloadsAResult() throws Exception {
        AppDirectories dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), true);
        Files.createDirectories(dirs.config());
        Files.writeString(
                dirs.config().resolve("njmule.properties"),
                "net.tcpPort=" + freePort() + "\nnet.kad.enabled=false\n");
        try (FakeServer server =
                        FakeServer.start(
                                InetAddress.getLoopbackAddress(),
                                new FakeServer.Config(
                                        Ipv4.toEd2k(Ipv4.parse("1.2.3.4")),
                                        "welcome",
                                        List.of(
                                                FakeServer.file("linux web.iso", 700_000_000L, 40),
                                                FakeServer.file("linux.txt", 10, 2))));
                NjMuleEngine engine = NjMuleEngine.start(new EngineConfig(dirs));
                WebUi ui =
                        WebUi.start(
                                engine,
                                new WebUiSettings(
                                        true,
                                        freePort(),
                                        false,
                                        Passwords.hash(
                                                "flow password".toCharArray(),
                                                new SecureRandom())))) {
            this.port = ui.port();
            HttpResponse<String> login = this.post("/login", Map.of("password", "flow password"));
            String set = login.headers().firstValue("Set-Cookie").orElseThrow();
            this.cookie = set.substring(0, set.indexOf(';'));

            String address = "127.0.0.1:" + server.port();
            HttpResponse<String> connect =
                    this.post(
                            "/servers",
                            Map.of("action", "connect", "address", address, "csrf", this.csrf()));
            assertThat(connect.statusCode()).isEqualTo(303);
            await(() -> this.page("/").contains("(HighID)"));
            assertThat(this.page("/servers")).contains("<h1>Servers</h1>", "Disconnect");

            assertThat(
                            this.post("/search", Map.of("query", " ", "csrf", this.csrf()))
                                    .headers()
                                    .firstValue("Location"))
                    .hasValue("/search");
            HttpResponse<String> search =
                    this.post(
                            "/search",
                            Map.of("query", "linux", "scope", "SERVER", "csrf", this.csrf()));
            String results = search.headers().firstValue("Location").orElseThrow();
            assertThat(results).startsWith("/search?id=");
            await(() -> this.page(results).contains("linux.txt"));
            String found = this.page(results);
            assertThat(found).contains("2 results", "linux web.iso");
            assertThat(found.indexOf("linux web.iso")).isLessThan(found.indexOf("linux.txt"));

            Matcher download =
                    Pattern.compile("name=\"download\" value=\"([0-9A-F]{32})\"").matcher(found);
            assertThat(download.find()).isTrue();
            String hash = download.group(1);
            String id = results.substring(results.indexOf('=') + 1);
            HttpResponse<String> taken =
                    this.post(
                            "/search", Map.of("search", id, "download", hash, "csrf", this.csrf()));
            assertThat(taken.headers().firstValue("Location")).hasValue("/transfers");
            assertThat(this.page("/transfers")).contains("linux web.iso", hash);

            HttpResponse<String> gone =
                    this.post(
                            "/search",
                            Map.of("search", "424242", "download", hash, "csrf", this.csrf()));
            assertThat(gone.statusCode()).isEqualTo(400);
            assertThat(gone.body()).contains("search again");
            assertThat(this.page("/search?id=nonsense")).doesNotContain("results;");

            this.post("/servers", Map.of("action", "disconnect", "csrf", this.csrf()));
            await(() -> this.page("/").contains("not connected"));
        }
    }

    private String csrf() throws Exception {
        Matcher m = CSRF.matcher(this.page("/"));
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private String page(String path) {
        try {
            return this.http
                    .send(this.request(path).GET().build(), HttpResponse.BodyHandlers.ofString())
                    .body();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpResponse<String> post(String path, Map<String, String> fields) throws Exception {
        String body =
                fields.entrySet().stream()
                        .map(
                                e ->
                                        URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                                                + "="
                                                + URLEncoder.encode(
                                                        e.getValue(), StandardCharsets.UTF_8))
                        .collect(Collectors.joining("&"));
        return this.http.send(
                this.request(path).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder b =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + path))
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
