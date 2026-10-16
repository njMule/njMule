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
import java.util.List;
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
    private static final String HASH = "0123456789ABCDEF0123456789ABCDEF";
    private static final String REFUSED_LINK = "ed2k://|file|refused.bin|1000|" + "AB".repeat(16) + "|/";

    @TempDir
    static Path dir;

    private static NjMuleEngine engine;

    private final HttpClient http = HttpClient.newHttpClient();
    private WebUi ui;
    private String cookie;
    private String formToken;
    private String origin;
    private HttpResponse<String> answer;
    private String rawAnswer;

    @BeforeAll
    static void startEngine() throws IOException {
        AppDirectories dirs = AppDirectories.underRoot(dir.resolve("njmule"), true);
        Files.createDirectories(dirs.config());
        Files.writeString(
                dirs.config().resolve("njmule.properties"),
                "net.tcpPort=" + FreePorts.tcp() + "\nnet.kad.enabled=false\n");
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
    void shouldSendVisitorsToTheLoginPage() throws Exception {
        givenTheWebInterfaceRuns();

        whenThePageIsAsked("/");

        thenTheStatusIs(303);
        thenTheHeaderIs("Location", "login");
        thenTheAnswerForbidsFramesAndForeignContent();
    }

    @Test
    void shouldShowTheLoginPageWithoutFormToken() throws Exception {
        givenTheWebInterfaceRuns();

        whenThePageIsAsked("/login");

        thenTheStatusIs(200);
        thenTheBodyContains("type=\"password\"");
        thenTheBodyDoesNotContain("csrf");
    }

    @Test
    void shouldServeTheStyleSheetToVisitors() throws Exception {
        givenTheWebInterfaceRuns();

        whenThePageIsAsked("/style.css");

        thenTheBodyContains("color-scheme");
    }

    @Test
    void shouldRefuseTheFormsOfVisitors() throws Exception {
        givenTheWebInterfaceRuns();

        whenPosted("/transfers", Map.of("action", "add"));

        thenTheStatusIs(403);
    }

    @Test
    void shouldLockOutAfterTooManyWrongPasswords() throws Exception {
        givenTheWebInterfaceRuns();
        givenWrongPasswordsSent(Sessions.MAX_FAILURES);

        whenPosted("/login", Map.of("password", PASSWORD));

        thenTheStatusIs(429);
        thenNoCookieIsSet();
    }

    @Test
    void shouldLetAnotherClientOfATrustedProxyLogInWhileOneIsLockedOut() throws Exception {
        givenTheWebInterfaceRunsBehindTheProxies("127.0.0.0/8");
        givenWrongPasswordsSentFor("198.51.100.7", Sessions.MAX_FAILURES);

        whenLoggedInFor("198.51.100.8");

        thenTheStatusIs(303);
    }

    @Test
    void shouldBelieveTheClientNamedByNoAddressItDoesNotTrust() throws Exception {
        givenTheWebInterfaceRuns();
        givenWrongPasswordsSentFor("198.51.100.7", Sessions.MAX_FAILURES);

        whenLoggedInFor("198.51.100.8");

        thenTheStatusIs(429);
    }

    @Test
    void shouldShowTheStatusToAUserLoggedIn() throws Exception {
        givenALoggedInUser();

        whenThePageIsAsked("/");

        thenTheStatusIs(200);
        thenTheBodyContains("<h1>Status</h1>", "not connected", "Log out");
    }

    @Test
    void shouldAnswerNotFoundForAnUnknownPage() throws Exception {
        givenALoggedInUser();

        whenThePageIsAsked("/nowhere");

        thenTheStatusIs(404);
    }

    @Test
    void shouldRefuseAMethodThePagesDoNotTake() throws Exception {
        givenALoggedInUser();

        whenThePageIsAskedWithTheMethod("/", "PUT");

        thenTheStatusIs(405);
    }

    @Test
    void shouldEndTheSessionOnLogout() throws Exception {
        givenALoggedInUser();
        givenTheFormTokenOfThePage("/");

        whenPosted("/logout", Map.of());

        thenTheStatusIs(303);
        thenTheCookieExpires();
        thenTheSessionIsOver();
    }

    @Test
    void shouldRefuseAFormWithoutToken() throws Exception {
        givenALoggedInUser();

        whenPosted("/transfers", Map.of("action", "add", "link", REFUSED_LINK));

        thenTheStatusIs(403);
        thenTheDownloadsDoNotShow("refused.bin");
    }

    @Test
    void shouldRefuseAFormWithAnotherToken() throws Exception {
        givenALoggedInUser();
        givenTheFormToken("not-the-token");

        whenPosted("/transfers", Map.of("action", "add", "link", REFUSED_LINK));

        thenTheStatusIs(403);
        thenTheDownloadsDoNotShow("refused.bin");
    }

    @Test
    void shouldRefuseAFormFromAnotherSite() throws Exception {
        givenALoggedInUser();
        givenTheFormTokenOfThePage("/transfers");
        givenTheOrigin("http://evil.example");

        whenPosted("/transfers", Map.of("action", "add", "link", REFUSED_LINK));

        thenTheStatusIs(403);
        thenTheDownloadsDoNotShow("refused.bin");
    }

    // With no-referrer a browser sends "Origin: null" even to the page's own origin.
    @Test
    void shouldHaveBrowsersSendTheOriginOfThePages() throws Exception {
        givenTheWebInterfaceRuns();

        whenThePageIsAsked("/login");

        thenTheHeaderIs("Referrer-Policy", "same-origin");
    }

    @Test
    void shouldTakeALoginFromTheOriginOfThePages() throws Exception {
        givenTheWebInterfaceRuns();
        givenTheOriginOfThePages();

        whenPosted("/login", Map.of("password", PASSWORD));

        thenTheStatusIs(303);
    }

    @Test
    void shouldRefuseALoginFromAnOpaqueOrigin() throws Exception {
        givenTheWebInterfaceRuns();
        givenTheOrigin("null");

        whenPosted("/login", Map.of("password", PASSWORD));

        thenTheStatusIs(403);
    }

    @Test
    void shouldRefuseOtherHostNames() throws Exception {
        givenTheWebInterfaceRuns();

        whenThePageIsAskedForTheHost("/login", "rebound.example");

        thenTheRawAnswerStartsWith("HTTP/1.1 421");
    }

    @Test
    void shouldAnswerUnderANameOfTheSettings() throws Exception {
        givenTheWebInterfaceRunsWithTheHostNames("nas.local");

        whenThePageIsAskedForTheHost("/login", "nas.local");

        thenTheRawAnswerStartsWith("HTTP/1.1 200");
    }

    @Test
    void shouldRefuseOtherHostNamesBesideTheNamesOfTheSettings() throws Exception {
        givenTheWebInterfaceRunsWithTheHostNames("nas.local");

        whenThePageIsAskedForTheHost("/login", "rebound.example");

        thenTheRawAnswerStartsWith("HTTP/1.1 421");
    }

    // Traefik and Caddy pass the browser's Host on; the browser came over HTTPS.
    @Test
    void shouldTakeALoginThroughAReverseProxyThatPassesTheHostOn() throws Exception {
        givenTheWebInterfaceRunsWithTheHostNames("njmule.example.com");

        whenLoggedInThrough("njmule.example.com", "https://njmule.example.com");

        thenTheRawAnswerStartsWith("HTTP/1.1 303");
        thenTheRawAnswerSetsACookie("Secure");
    }

    // nginx sends its own address of the upstream as Host unless told otherwise.
    @Test
    void shouldTakeALoginThroughAReverseProxyThatSendsItsOwnAddress() throws Exception {
        givenTheWebInterfaceRunsWithTheHostNames("njmule.example.com");

        whenLoggedInThrough("127.0.0.1:" + this.ui.port(), "https://njmule.example.com");

        thenTheRawAnswerStartsWith("HTTP/1.1 303");
    }

    @Test
    void shouldRefuseALoginThroughAReverseProxyWhoseNameIsNotInTheSettings() throws Exception {
        givenTheWebInterfaceRuns();

        whenLoggedInThrough("127.0.0.1:" + this.ui.port(), "https://njmule.example.com");

        thenTheRawAnswerStartsWith("HTTP/1.1 403");
    }

    @Test
    void shouldKeepTheCookieOfAPlainHttpLoginForPlainHttp() throws Exception {
        givenTheWebInterfaceRunsWithTheHostNames("nas.local");

        whenLoggedInThrough("nas.local:" + this.ui.port(), "http://nas.local:" + this.ui.port());

        thenTheRawAnswerStartsWith("HTTP/1.1 303");
        thenTheRawAnswerSetsACookieWithout("Secure");
    }

    @Test
    void shouldAddPauseResumeAndCancelADownload() throws Exception {
        givenALoggedInUser();
        givenTheFormTokenOfThePage("/transfers");
        givenADownloadAdded("ed2k://|file|web & test.bin|1000|" + HASH + "|/");
        givenTheDownloadsShow("web &amp; test.bin", "Pause");
        givenTheDownloadsDoNotShow("web & test");
        givenTheActionDoneOnTheDownload("pause", HASH);
        givenTheDownloadsShow("paused", "Resume");
        givenTheActionDoneOnTheDownload("resume", HASH);
        givenTheDownloadsShow("Pause");
        givenTheActionDoneOnTheDownload("stop", HASH);
        givenTheDownloadsShow("stopped", "Resume");
        givenThePriorityOfTheDownloadSet(HASH, "high");
        givenTheDownloadsShow("value=\"high\" selected");

        whenPosted("/transfers", Map.of("action", "cancel", "hash", HASH));

        thenTheStatusIs(303);
        thenTheDownloadsSoonDoNotShow("web &amp; test.bin");
    }

    @Test
    void shouldRefuseALinkThatIsNoFileLink() throws Exception {
        givenALoggedInUser();
        givenTheFormTokenOfThePage("/transfers");

        whenPosted("/transfers", Map.of("action", "add", "link", "ed2k://|server|1.2.3.4|4661|/"));

        thenTheStatusIs(400);
        thenTheBodyContains("Not an eD2K file link");
    }

    @Test
    void shouldRefuseAnUnknownAction() throws Exception {
        givenALoggedInUser();
        givenTheFormTokenOfThePage("/");

        whenPosted("/transfers", Map.of("action", "explode", "hash", HASH));

        thenTheStatusIs(400);
    }

    private void givenTheWebInterfaceRuns() throws IOException {
        givenTheWebInterfaceRunsWithTheHostNames();
    }

    private void givenTheWebInterfaceRunsWithTheHostNames(String... names) throws IOException {
        String hash = Passwords.hash(PASSWORD.toCharArray(), new byte[16], 1000);
        this.ui = WebUi.start(engine, new WebUiSettings(true, FreePorts.tcp(), false, hash, false, List.of(names)));
    }

    private void givenALoggedInUser() throws Exception {
        givenTheWebInterfaceRuns();
        HttpResponse<String> in = post("/login", Map.of("password", PASSWORD));
        assertThat(in.statusCode()).isEqualTo(303);
        String set = in.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(set).contains("HttpOnly", "SameSite=Strict");
        this.cookie = set.substring(0, set.indexOf(';'));
    }

    private void givenTheWebInterfaceRunsBehindTheProxies(String... proxies) throws IOException {
        String hash = Passwords.hash(PASSWORD.toCharArray(), new byte[16], 1000);
        this.ui = WebUi.start(
                engine, new WebUiSettings(true, FreePorts.tcp(), false, hash, false, List.of(), List.of(proxies)));
    }

    // Sent as a proxy forwards them, naming its client.
    private void givenWrongPasswordsSentFor(String client, int count) throws Exception {
        for (int i = 0; i < count; i++) {
            HttpResponse<String> wrong = this.http.send(
                    request("/login")
                            .header("X-Forwarded-For", client)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(form(Map.of("password", "guess " + i)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(wrong.statusCode()).isEqualTo(401);
        }
    }

    private void whenLoggedInFor(String client) throws Exception {
        this.answer = this.http.send(
                request("/login")
                        .header("X-Forwarded-For", client)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(form(Map.of("password", PASSWORD)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void givenWrongPasswordsSent(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            HttpResponse<String> wrong = post("/login", Map.of("password", "guess " + i));
            assertThat(wrong.statusCode()).isEqualTo(401);
            assertThat(wrong.body()).contains("Wrong password");
        }
    }

    private void givenTheFormTokenOfThePage(String page) throws Exception {
        Matcher m = CSRF.matcher(get(page).body());
        assertThat(m.find()).isTrue();
        this.formToken = m.group(1);
    }

    private void givenTheFormToken(String token) {
        this.formToken = token;
    }

    private void givenTheOrigin(String value) {
        this.origin = value;
    }

    private void givenTheOriginOfThePages() {
        this.origin = "http://127.0.0.1:" + this.ui.port();
    }

    private void givenADownloadAdded(String link) throws Exception {
        whenPosted("/transfers", Map.of("action", "add", "link", link));
        assertThat(this.answer.statusCode()).isEqualTo(303);
        assertThat(this.answer.headers().firstValue("Location")).hasValue("transfers");
    }

    private void givenTheActionDoneOnTheDownload(String action, String hash) throws Exception {
        whenPosted("/transfers", Map.of("action", action, "hash", hash));
        assertThat(this.answer.statusCode()).isEqualTo(303);
    }

    private void givenThePriorityOfTheDownloadSet(String hash, String priority) throws Exception {
        whenPosted("/transfers", Map.of("action", "priority", "hash", hash, "priority", priority));
    }

    private void givenTheDownloadsShow(String... parts) throws Exception {
        assertThat(get("/transfers").body()).contains(parts);
    }

    private void givenTheDownloadsDoNotShow(String part) throws Exception {
        thenTheDownloadsDoNotShow(part);
    }

    private void whenThePageIsAsked(String path) throws Exception {
        this.answer = get(path);
    }

    private void whenThePageIsAskedWithTheMethod(String path, String method) throws Exception {
        this.answer = this.http.send(
                request(path)
                        .method(method, HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void whenPosted(String path, Map<String, String> fields) throws Exception {
        Map<String, String> all = new LinkedHashMap<>(fields);
        if (this.formToken != null) {
            all.put("csrf", this.formToken);
        }
        HttpRequest.Builder request = request(path);
        if (this.origin != null) {
            request.header("Origin", this.origin);
        }
        this.answer = this.http.send(
                request.header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(form(all))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void whenThePageIsAskedForTheHost(String path, String host) throws IOException {
        this.rawAnswer = raw(
                "GET " + path + " HTTP/1.1\r\nHost: " + host + ":" + this.ui.port() + "\r\nConnection: close\r\n\r\n");
    }

    // The request a reverse proxy forwards: its Host, the browser's Origin.
    private void whenLoggedInThrough(String host, String origin) throws IOException {
        String body = "password=" + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8);
        this.rawAnswer = raw("POST /login HTTP/1.1\r\nHost: " + host + "\r\nOrigin: " + origin
                + "\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: " + body.length()
                + "\r\nConnection: close\r\n\r\n" + body);
    }

    private void thenTheStatusIs(int status) {
        assertThat(this.answer.statusCode()).isEqualTo(status);
    }

    private void thenTheHeaderIs(String name, String value) {
        assertThat(this.answer.headers().firstValue(name)).hasValue(value);
    }

    private void thenTheAnswerForbidsFramesAndForeignContent() {
        assertThat(this.answer.headers().firstValue("X-Frame-Options")).hasValue("DENY");
        assertThat(this.answer.headers().firstValue("Content-Security-Policy").orElseThrow())
                .contains("default-src 'none'")
                .contains("frame-ancestors 'none'");
    }

    private void thenTheBodyContains(String... parts) {
        assertThat(this.answer.body()).contains(parts);
    }

    private void thenTheBodyDoesNotContain(String part) {
        assertThat(this.answer.body()).doesNotContain(part);
    }

    private void thenNoCookieIsSet() {
        assertThat(this.answer.headers().firstValue("Set-Cookie")).isEmpty();
    }

    private void thenTheCookieExpires() {
        assertThat(this.answer.headers().firstValue("Set-Cookie").orElseThrow())
                .contains("Expires=Thu, 01 Jan 1970 00:00:00 GMT");
    }

    private void thenTheSessionIsOver() throws Exception {
        assertThat(get("/").statusCode()).isEqualTo(303);
    }

    private void thenTheDownloadsDoNotShow(String part) throws Exception {
        assertThat(get("/transfers").body()).doesNotContain(part);
    }

    private void thenTheDownloadsSoonDoNotShow(String part) throws InterruptedException {
        await(() -> !uncheckedGet("/transfers").contains(part));
    }

    private void thenTheRawAnswerStartsWith(String start) {
        assertThat(this.rawAnswer).startsWith(start);
    }

    private void thenTheRawAnswerSetsACookie(String attribute) {
        assertThat(rawCookie()).contains("HttpOnly", "SameSite=Strict", attribute);
    }

    private void thenTheRawAnswerSetsACookieWithout(String attribute) {
        assertThat(rawCookie()).contains("HttpOnly").doesNotContain(attribute);
    }

    private String rawCookie() {
        return this.rawAnswer
                .lines()
                .filter(line -> line.regionMatches(true, 0, "Set-Cookie:", 0, 11))
                .findFirst()
                .orElseThrow();
    }

    private String raw(String request) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), this.ui.port())) {
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return this.http.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private String uncheckedGet(String path) {
        try {
            return get(path).body();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpResponse<String> post(String path, Map<String, String> fields) throws Exception {
        return this.http.send(
                request(path)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(form(fields))
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
}
