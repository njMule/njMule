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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.config.AppDirectories;

/** The web interface over HTTP, against a real engine. */
@Timeout(120)
class WebUiTest {

    private static final String PASSWORD = "correct horse";
    private static final Pattern CSRF = Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"");

    @TempDir
    static Path dir;

    private static NjMuleEngine engine;

    private final HttpClient http = HttpClient.newHttpClient();
    private WebUi ui;
    private String cookie;

    @BeforeAll
    static void startEngine() throws IOException {
        AppDirectories dirs = AppDirectories.underRoot(dir.resolve("njmule"), true);
        Files.createDirectories(dirs.config());
        Files.writeString(
                dirs.config().resolve("njmule.properties"), "net.tcpPort=" + freePort() + "\nnet.kad.enabled=false\n");
        engine = NjMuleEngine.start(new EngineConfig(dirs));
    }

    @AfterAll
    static void stopEngine() {
        engine.close();
    }

    @AfterEach
    void stop() {
        if (this.ui != null) {
            this.ui.close();
        }
    }

    @Test
    void sendsVisitorsToTheLoginPage() throws Exception {
        this.start();
        HttpResponse<String> root = this.get("/");
        assertThat(root.statusCode()).isEqualTo(303);
        assertThat(root.headers().firstValue("Location")).hasValue("/login");
        assertThat(root.headers().firstValue("X-Frame-Options")).hasValue("DENY");
        assertThat(root.headers().firstValue("Content-Security-Policy").orElseThrow())
                .contains("default-src 'none'")
                .contains("frame-ancestors 'none'");

        HttpResponse<String> login = this.get("/login");
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.body()).contains("type=\"password\"").doesNotContain("csrf");
        assertThat(this.get("/style.css").body()).contains("color-scheme");
        assertThat(this.post("/transfers", Map.of("action", "add")).statusCode())
                .isEqualTo(403);
    }

    @Test
    void locksOutAfterTooManyWrongPasswords() throws Exception {
        this.start();
        for (int i = 0; i < Sessions.MAX_FAILURES; i++) {
            HttpResponse<String> wrong = this.post("/login", Map.of("password", "guess " + i));
            assertThat(wrong.statusCode()).isEqualTo(401);
            assertThat(wrong.body()).contains("Wrong password");
        }
        HttpResponse<String> locked = this.post("/login", Map.of("password", PASSWORD));
        assertThat(locked.statusCode()).isEqualTo(429);
        assertThat(locked.headers().firstValue("Set-Cookie")).isEmpty();
    }

    @Test
    void showsStatusAndEndsTheSessionOnLogout() throws Exception {
        this.start();
        this.login();
        HttpResponse<String> status = this.get("/");
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(status.body()).contains("<h1>Status</h1>", "not connected", "Log out");
        assertThat(this.get("/nowhere").statusCode()).isEqualTo(404);
        assertThat(this.send("/", "PUT").statusCode()).isEqualTo(405);

        HttpResponse<String> out = this.post("/logout", Map.of("csrf", this.csrf("/")));
        assertThat(out.statusCode()).isEqualTo(303);
        assertThat(out.headers().firstValue("Set-Cookie").orElseThrow()).contains("Max-Age=0");
        assertThat(this.get("/").statusCode()).isEqualTo(303);
    }

    @Test
    void refusesFormsWithoutTokenOrFromOtherSites() throws Exception {
        this.start();
        this.login();
        String link = "ed2k://|file|refused.bin|1000|" + "AB".repeat(16) + "|/";
        assertThat(this.post("/transfers", Map.of("action", "add", "link", link))
                        .statusCode())
                .isEqualTo(403);
        assertThat(this.post("/transfers", Map.of("action", "add", "link", link, "csrf", "not-the-token"))
                        .statusCode())
                .isEqualTo(403);
        HttpRequest foreign = this.request("/transfers")
                .header("Origin", "http://evil.example")
                .POST(form(Map.of("action", "add", "link", link, "csrf", this.csrf("/transfers"))))
                .build();
        assertThat(this.http.send(foreign, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(403);
        assertThat(this.get("/transfers").body()).doesNotContain("refused.bin");
    }

    @Test
    void refusesOtherHostNames() throws Exception {
        this.start();
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), this.ui.port())) {
            OutputStream out = socket.getOutputStream();
            out.write(
                    ("GET /login HTTP/1.1\r\nHost: rebound.example:" + this.ui.port() + "\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            String answer = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertThat(answer).startsWith("HTTP/1.1 421");
        }
    }

    @Test
    void addsPausesResumesAndCancelsDownloads() throws Exception {
        this.start();
        this.login();
        String hash = "0123456789ABCDEF0123456789ABCDEF";
        String link = "ed2k://|file|web & test.bin|1000|" + hash + "|/";
        HttpResponse<String> added =
                this.post("/transfers", Map.of("action", "add", "link", link, "csrf", this.csrf("/transfers")));
        assertThat(added.statusCode()).isEqualTo(303);
        assertThat(added.headers().firstValue("Location")).hasValue("/transfers");
        String page = this.get("/transfers").body();
        assertThat(page).contains("web &amp; test.bin", "Pause").doesNotContain("web & test");

        this.action("pause", hash);
        assertThat(this.get("/transfers").body()).contains("paused", "Resume");
        this.action("resume", hash);
        assertThat(this.get("/transfers").body()).contains("Pause");
        this.action("stop", hash);
        assertThat(this.get("/transfers").body()).contains("stopped", "Resume");
        this.post(
                "/transfers",
                Map.of("action", "priority", "hash", hash, "priority", "high", "csrf", this.csrf("/transfers")));
        assertThat(this.get("/transfers").body()).contains("value=\"high\" selected");
        this.action("cancel", hash);
        await(() -> !this.uncheckedGet("/transfers").contains("web &amp; test.bin"));

        HttpResponse<String> bad = this.post(
                "/transfers",
                Map.of("action", "add", "link", "ed2k://|server|1.2.3.4|4661|/", "csrf", this.csrf("/transfers")));
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(bad.body()).contains("Not an eD2K file link");
        HttpResponse<String> unknown =
                this.post("/transfers", Map.of("action", "explode", "hash", hash, "csrf", this.csrf("/")));
        assertThat(unknown.statusCode()).isEqualTo(400);
    }

    private void start() throws IOException {
        String hash = Passwords.hash(PASSWORD.toCharArray(), new byte[16], 1000);
        this.ui = WebUi.start(engine, new WebUiSettings(true, freePort(), false, hash));
    }

    private void login() throws Exception {
        HttpResponse<String> in = this.post("/login", Map.of("password", PASSWORD));
        assertThat(in.statusCode()).isEqualTo(303);
        String set = in.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(set).contains("HttpOnly", "SameSite=Strict");
        this.cookie = set.substring(0, set.indexOf(';'));
    }

    private void action(String action, String hash) throws Exception {
        HttpResponse<String> done =
                this.post("/transfers", Map.of("action", action, "hash", hash, "csrf", this.csrf("/transfers")));
        assertThat(done.statusCode()).isEqualTo(303);
    }

    private String csrf(String page) throws Exception {
        Matcher m = CSRF.matcher(this.get(page).body());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return this.http.send(this.request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private String uncheckedGet(String path) {
        try {
            return this.get(path).body();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpResponse<String> send(String path, String method) throws Exception {
        return this.http.send(
                this.request(path)
                        .method(method, HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, Map<String, String> fields) throws Exception {
        return this.http.send(this.request(path).POST(form(fields)).build(), HttpResponse.BodyHandlers.ofString());
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
        String body = new LinkedHashMap<>(fields)
                .entrySet().stream()
                        .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                                + "="
                                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                        .collect(Collectors.joining("&"));
        return HttpRequest.BodyPublishers.ofString(body);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("waited too long").isLessThan(end);
            Thread.sleep(50);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }
}
