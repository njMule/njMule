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
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.LogLine;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.api.SharedDirectory;
import org.njmule.core.api.SharedFileInfo;
import org.njmule.core.api.UploadPriority;

/** The shared files, Kad and log pages. */
@Timeout(60)
class WebUiPagesTest {

    private static final String PASSWORD = "correct horse";
    private static final Pattern CSRF = Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"");
    private static final String HASH = "AB".repeat(16);

    private final FakeEngine fake = new FakeEngine();
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<String> calls = new CopyOnWriteArrayList<>();
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
    void shouldListTheSharedFilesAndSetTheirPriority() throws Exception {
        this.givenALoggedInUser();
        this.givenTheEngineShares("talk.bin", "/files");

        this.whenThePageIsAsked("/shared");
        this.thenThePageShows("talk.bin", Path.of("/files") + " (with subfolders)", "value=\"high\" selected");

        this.whenPosted("/shared", Map.of("action", "priority", "hash", HASH, "priority", "release"));
        this.thenTheEngineWasAsked("upload priority " + HASH + " RELEASE");
    }

    @Test
    void shouldShowKadAndBootstrapIt() throws Exception {
        this.givenALoggedInUser();
        this.givenKadConnectedAndFirewalled();

        this.whenThePageIsAsked("/kad");
        this.thenThePageShows("firewalled", "42 (40 verified)", ">Disconnect<", "Recheck firewall");

        this.whenPosted("/kad", Map.of("action", "bootstrap", "address", "10.0.0.5:4672"));
        this.thenTheEngineWasAsked("bootstrap 10.0.0.5:4672");
    }

    @Test
    void shouldShowTheLatestLogLinesNewestFirst() throws Exception {
        this.givenALoggedInUser();
        this.givenTheEngineLogged("first line", "second <line>");

        this.whenTheLogShows("second &lt;line&gt;");

        this.thenTheNewestLineComesFirst("second &lt;line&gt;", "first line");
    }

    // Given

    private void givenALoggedInUser() throws Exception {
        String hash = Passwords.hash(PASSWORD.toCharArray(), new byte[16], 1000);
        this.ui = WebUi.start(this.fake.engine(), new WebUiSettings(true, freePort(), false, hash));
        HttpResponse<String> in = this.post("/login", Map.of("password", PASSWORD));
        assertThat(in.statusCode()).isEqualTo(303);
        String set = in.headers().firstValue("Set-Cookie").orElseThrow();
        this.cookie = set.substring(0, set.indexOf(';'));
    }

    private void givenTheEngineShares(String name, String folder) {
        this.fake.answer(
                "sharedFiles",
                args -> CompletableFuture.completedFuture(List.of(new SharedFileInfo(
                        Hash16.parseHex(HASH), name, 5000, Path.of(folder, name), UploadPriority.HIGH))));
        this.fake.answer(
                "sharedDirectories",
                args -> CompletableFuture.completedFuture(List.of(new SharedDirectory(Path.of(folder), true))));
        this.fake.answer("setUploadPriority", args -> {
            this.calls.add("upload priority " + ((Hash16) args[0]).toHex() + " " + args[1]);
            return CompletableFuture.completedFuture(true);
        });
    }

    private void givenKadConnectedAndFirewalled() {
        this.fake.answer(
                "kad",
                args -> CompletableFuture.completedFuture(
                        Optional.of(new KadInfo(true, 42, 40, true, "00".repeat(16), "1.2.3.4", 4672))));
        this.fake.answer("kadBootstrap", args -> {
            ServerAddress node = (ServerAddress) args[0];
            this.calls.add("bootstrap " + node.host() + ":" + node.port());
            return CompletableFuture.completedFuture(null);
        });
    }

    private void givenTheEngineLogged(String... messages) {
        for (String message : messages) {
            this.fake.events.submit(new EngineEvent.Log(new LogLine(Instant.now(), LogLine.Level.INFO, message)));
        }
    }

    // When

    private void whenThePageIsAsked(String path) throws Exception {
        this.answer = this.get(path);
    }

    private void whenPosted(String path, Map<String, String> fields) throws Exception {
        Map<String, String> all = new HashMap<>(fields);
        all.put("csrf", this.csrf());
        this.answer = this.post(path, all);
    }

    // Delivered on the publisher's threads.
    private void whenTheLogShows(String text) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            this.whenThePageIsAsked("/log");
        } while (!this.answer.body().contains(text) && System.nanoTime() < end);
    }

    // Then

    private void thenThePageShows(String... parts) {
        assertThat(this.answer.statusCode()).as(this.answer.body()).isEqualTo(200);
        assertThat(this.answer.body()).contains(parts);
    }

    private void thenTheEngineWasAsked(String call) {
        assertThat(this.answer.statusCode()).as(this.answer.body()).isEqualTo(303);
        assertThat(this.calls).contains(call);
    }

    private void thenTheNewestLineComesFirst(String newer, String older) {
        String page = this.answer.body();
        assertThat(page).contains(newer, older).doesNotContain("second <line>");
        assertThat(page.indexOf(newer)).isLessThan(page.indexOf(older));
    }

    private String csrf() throws Exception {
        Matcher m = CSRF.matcher(this.get("/search").body());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return this.http.send(this.request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, Map<String, String> fields) throws Exception {
        String body = fields.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return this.http.send(
                this.request(path)
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

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }
}
