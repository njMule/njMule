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
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
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
import org.njmule.core.api.Ed2kLink;

/** How the web interface answers what the engine does, against an engine a test controls. */
@Timeout(60)
class WebUiAnswersTest {

    private static final String PASSWORD = "correct horse";
    private static final Pattern CSRF = Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"");

    private final FakeEngine fake = new FakeEngine();
    private final HttpClient http = HttpClient.newHttpClient();
    private WebUi ui;
    private String cookie;

    @AfterEach
    void stop() {
        if (this.ui != null) {
            this.ui.close();
        }
        this.fake.events.close();
    }

    @Test
    void anEngineThatFailsGetsAnAnswer() throws Exception {
        this.start();
        this.login();
        HttpResponse<String> failed = this.get("/");
        assertThat(failed.statusCode()).isEqualTo(503);
        assertThat(failed.body()).contains("status failed");

        this.fake.answer("status", args -> CompletableFuture.completedFuture(null));
        assertThat(this.get("/").statusCode()).as("a fault of the page").isEqualTo(500);
    }

    @Test
    void checksFewPasswordsAtOnce() throws Exception {
        // Two million rounds keep the first check busy for a while.
        String slow = "pbkdf2-sha512$2000000$" + "A".repeat(22) + "$" + "A".repeat(86);
        this.ui = WebUi.start(this.fake.engine(), new WebUiSettings(true, freePort(), false, slow), 1);
        CompletableFuture<HttpResponse<String>> first = this.http.sendAsync(
                this.request("/login").POST(form(Map.of("password", "first"))).build(),
                HttpResponse.BodyHandlers.ofString());
        long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (this.ui.checkingPasswords() == 0) {
            assertThat(System.nanoTime()).as("waited too long").isLessThan(end);
            Thread.sleep(5);
        }
        HttpResponse<String> second = this.post("/login", Map.of("password", "second"));
        assertThat(second.statusCode()).isEqualTo(429);
        assertThat(second.body()).contains("try again");
        assertThat(first.get().statusCode()).isEqualTo(401);
    }

    @Test
    void answersHeadWithTheHeadersOnly() throws Exception {
        this.start();
        HttpResponse<String> login = this.head("/login");
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.headers().firstValue("Content-Type")).hasValue("text/html; charset=utf-8");
        assertThat(login.headers().firstValueAsLong("Content-Length").orElse(0)).isPositive();
        assertThat(login.body()).isEmpty();

        assertThat(this.head("/search").statusCode()).isEqualTo(303);
        this.login();
        assertThat(this.head("/search").statusCode()).isEqualTo(200);
        assertThat(this.head("/nowhere").statusCode()).isEqualTo(404);
    }

    @Test
    void actionsOnADownloadThatIsGoneAreReported() throws Exception {
        this.start();
        this.login();
        String hash = "AB".repeat(16);
        this.fake.answer("pauseDownload", args -> CompletableFuture.completedFuture(true));
        assertThat(this.post("/transfers", Map.of("action", "pause", "hash", hash, "csrf", this.csrf()))
                        .statusCode())
                .isEqualTo(303);

        for (String action : new String[] {"pauseDownload", "resumeDownload", "stopDownload", "cancelDownload"}) {
            this.fake.answer(action, args -> CompletableFuture.completedFuture(false));
        }
        this.fake.answer("setDownloadPriority", args -> CompletableFuture.completedFuture(false));
        for (String action : new String[] {"pause", "resume", "stop", "cancel", "priority"}) {
            HttpResponse<String> gone = this.post(
                    "/transfers", Map.of("action", action, "hash", hash, "priority", "high", "csrf", this.csrf()));
            assertThat(gone.statusCode()).as(action).isEqualTo(400);
            assertThat(gone.body()).as(action).contains("That download is gone");
        }
    }

    @Test
    void theLinkFieldTakesWhatTheServerTakes() throws Exception {
        this.start();
        this.login();
        List<String> added = new CopyOnWriteArrayList<>();
        this.fake.answer("downloads", args -> CompletableFuture.completedFuture(List.of()));
        this.fake.answer("addDownload", args -> {
            added.add(((Ed2kLink.FileLink) args[0]).name());
            return CompletableFuture.completedFuture(null);
        });
        String page = this.get("/transfers").body();
        Matcher field = Pattern.compile("name=\"link\"[^>]* pattern=\"([^\"]+)\" maxlength=\"(\\d+)\"")
                .matcher(page);
        assertThat(field.find()).isTrue();
        Pattern browser = Pattern.compile(field.group(1));
        int maxLength = Integer.parseInt(field.group(2));
        String hash = "AB".repeat(16);
        String ed2k = "ed2k://|file|a.bin|1000|" + hash + "|/";
        String magnet = "magnet:?xt=urn:ed2k:" + "CD".repeat(16) + "&xl=1000&dn=b.bin";
        assertThat(browser.matcher(ed2k).matches()).isTrue();
        assertThat(browser.matcher(" " + magnet).matches()).isTrue();

        assertThat(this.post("/transfers", Map.of("action", "add", "link", ed2k + "\n" + magnet, "csrf", this.csrf()))
                        .statusCode())
                .isEqualTo(303);
        assertThat(added).containsExactly("a.bin", "b.bin");

        // The longest text the field takes, each character taking 9 bytes once encoded.
        String head = "ed2k://|file|";
        String tail = "|1000|" + hash + "|/";
        String longest = head + "\u20AC".repeat(maxLength - head.length() - tail.length()) + tail;
        assertThat(this.post("/transfers", Map.of("action", "add", "link", longest, "csrf", this.csrf()))
                        .statusCode())
                .isEqualTo(303);
        assertThat(added).hasSize(3);

        HttpResponse<String> tooMuch =
                this.post("/transfers", Map.of("action", "add", "link", longest + " " + longest, "csrf", this.csrf()));
        assertThat(tooMuch.statusCode()).isEqualTo(413);
        assertThat(tooMuch.headers().firstValue("Content-Type")).hasValue("text/html; charset=utf-8");
        assertThat(tooMuch.body()).contains("Too much text");
    }

    private void start() throws IOException {
        String hash = Passwords.hash(PASSWORD.toCharArray(), new byte[16], 1000);
        this.ui = WebUi.start(this.fake.engine(), new WebUiSettings(true, freePort(), false, hash));
    }

    private void login() throws Exception {
        HttpResponse<String> in = this.post("/login", Map.of("password", PASSWORD));
        assertThat(in.statusCode()).isEqualTo(303);
        String set = in.headers().firstValue("Set-Cookie").orElseThrow();
        this.cookie = set.substring(0, set.indexOf(';'));
    }

    // The search page asks nothing of the engine.
    private String csrf() throws Exception {
        Matcher m = CSRF.matcher(this.get("/search").body());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return this.http.send(this.request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> head(String path) throws Exception {
        return this.http.send(
                this.request(path)
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
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

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }
}
