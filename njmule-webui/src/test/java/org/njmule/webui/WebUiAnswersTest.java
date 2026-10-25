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
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.njmule.core.api.DownloadInfo;

/** How the web interface answers what the engine does, against an engine a test controls. */
@Timeout(60)
class WebUiAnswersTest {

    private static final String PASSWORD = "correct horse";
    private static final Pattern APP_TOKEN = Pattern.compile("name=\"njmule-csrf\" content=\"([^\"]+)\"");

    private final FakeEngine fake = new FakeEngine();
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<Object> asked = new CopyOnWriteArrayList<>();
    private WebUi ui;
    private String cookie;
    private String origin;
    private HttpResponse<String> answer;
    private HttpResponse<byte[]> packed;
    private CompletableFuture<HttpResponse<String>> firstLogin;
    private FakeEngine otherEngine;
    private WebUi other;
    private String otherCookie;
    private String mine;
    private Throwable exception;

    @AfterEach
    void stop() {
        if (this.ui != null) {
            this.ui.close();
        }
        this.fake.events.close();
        if (this.other != null) {
            this.other.close();
        }
        if (this.otherEngine != null) {
            this.otherEngine.events.close();
        }
    }

    @Test
    void shouldCheckFewPasswordsAtOnce() throws Exception {
        givenTheWebInterfaceRunsWithASlowPassword(1);
        givenALoginBeingChecked("first");

        whenPostedWithoutToken("/login", Map.of("password", "second"));

        thenTheStatusIs(429);
        thenTheBodyContains("try again");
        thenTheFirstLoginGets(401);
    }

    @Test
    void shouldAnswerHeadWithTheHeadersOnly() throws Exception {
        givenTheWebInterfaceRuns();

        whenTheHeadIsAsked("/login");

        thenTheStatusIs(200);
        thenTheHeaderIs("Content-Type", "text/html; charset=utf-8");
        thenTheContentLengthIsPositive();
        thenTheBodyIs("");
    }

    @Test
    void shouldSendAVisitorAskingTheHeadOfAPageToTheLogin() throws Exception {
        givenTheWebInterfaceRuns();

        whenTheHeadIsAsked("/");

        thenTheStatusIs(303);
    }

    @Test
    void shouldAnswerTheHeadOfAPageToAUserLoggedIn() throws Exception {
        givenALoggedInUser();

        whenTheHeadIsAsked("/");

        thenTheStatusIs(200);
    }

    @Test
    void shouldAnswerTheHeadOfAnUnknownPageWithNotFound() throws Exception {
        givenALoggedInUser();

        whenTheHeadIsAsked("/nowhere");

        thenTheStatusIs(404);
    }

    @Test
    void shouldGiveItsIconWithoutALogin() throws Exception {
        givenTheWebInterfaceRuns();

        whenThePageIsAsked("/favicon.svg");

        thenTheStatusIs(200);
        thenTheHeaderIs("Content-Type", "image/svg+xml");
        thenTheBodyContains("<svg");
    }

    @Test
    void shouldLinkItsIconsFromThePages() throws Exception {
        givenTheWebInterfaceRuns();

        whenThePageIsAsked("/login");

        thenTheBodyContains("href=\"favicon.png\" sizes=\"32x32\"", "href=\"favicon.svg\" type=\"image/svg+xml\"");
    }

    @Test
    void shouldKeepTheSessionsOfTwoInstancesOnOneComputer() throws Exception {
        givenALoggedInUser();
        givenAnotherInstanceWithTheUserLoggedIn();

        whenThePageIsAskedWithTheCookiesOfBothInstances("/");

        thenTheStatusIs(200);
        thenTheCookiesAreNamedAfterThePortsOfTheirInstances();
    }

    @Test
    void shouldNotStartWhileOff() {
        whenTheWebInterfaceStartsWith(WebUiSettings.DEFAULTS);

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldNotStartWithoutAPassword() {
        whenTheWebInterfaceStartsWith(new WebUiSettings(true, 4711, false, ""));

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldAnswerTheAppsCallsWithTheLoginSession() throws Exception {
        givenALoggedInUser();
        givenTheEngineDownloads();

        whenTheApiIsCalled("GET", "/api/v1/downloads", Optional.empty());

        thenTheStatusIs(200);
        thenTheBodyIs("[]");
    }

    @Test
    void shouldTurnAwayTheAppsCallsWithoutLogin() throws Exception {
        givenTheWebInterfaceRuns();

        whenTheApiIsCalled("GET", "/api/v1/status", Optional.empty());

        thenTheStatusIs(401);
    }

    @Test
    void shouldRefuseTheAppsChangesWithoutTheSessionsToken() throws Exception {
        givenALoggedInUser();
        givenTheEngineConnectsAndDisconnects();

        whenTheApiIsCalled("POST", "/api/v1/servers/disconnect", Optional.of("not-the-token"));

        thenTheStatusIs(403);
        thenTheEngineWasNotAskedToDisconnect();
    }

    @Test
    void shouldTakeTheAppsChangesWithTheSessionsToken() throws Exception {
        givenALoggedInUser();
        givenTheEngineConnectsAndDisconnects();

        whenTheApiIsCalledWithTheSessionsToken("POST", "/api/v1/servers/disconnect");

        thenTheStatusIs(200);
        thenTheEngineWasAskedToDisconnect();
    }

    @Test
    void shouldServeTheAppsTranslationsBeforeTheLogin() throws Exception {
        givenTheWebInterfaceRuns();

        whenThePageIsAsked("/i18n/en.json");

        thenTheStatusIs(200);
        thenTheBodyContains("\"IDS_EM_TRANS\": \"Transfers\"");
        thenTheHeaderIsMissing("Content-Encoding");
    }

    @Test
    void shouldServeTheFlagOfACountryAsAnImage() throws Exception {
        givenTheWebInterfaceRuns();

        whenThePageIsAsked("/flags/it.png");

        thenTheStatusIs(200);
        thenTheHeaderIs("Content-Type", "image/png");
    }

    @Test
    void shouldSendTheAppsFilesCompressedToABrowserTakingGzip() throws Exception {
        givenTheWebInterfaceRuns();

        whenThePageIsAskedTakingGzip("/i18n/en.json");

        thenItComesCompressedHolding("\"IDS_EM_TRANS\": \"Transfers\"");
    }

    @Test
    void shouldServeNoOtherResourceAsAFileOfTheApp() throws Exception {
        givenALoggedInUser();

        whenThePageIsAsked("/index.html");

        thenTheStatusIs(404);
    }

    @Test
    void shouldServeTheAppWithTheSessionsTokenAtTheTop() throws Exception {
        givenALoggedInUser();

        whenThePageIsAsked("/");

        thenTheStatusIs(200);
        thenTheBodyContains("<meta name=\"njmule-csrf\" content=\"");
        thenTheBodyDoesNotContain("__NJMULE_CSRF__");
        thenTheHeaderIs(
                "Content-Security-Policy",
                "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self';"
                        + " connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'");
    }

    @Test
    void shouldRefuseALoginThatIsNoPost() throws Exception {
        givenTheWebInterfaceRuns();

        whenThePageIsAskedWithTheMethod("/login", "PUT");

        thenTheStatusIs(405);
    }

    @Test
    void shouldRefuseAHugeLogin() throws Exception {
        givenTheWebInterfaceRuns();

        whenPostedWithoutToken("/login", Map.of("password", "x".repeat(16 * 1024)));

        thenTheStatusIs(400);
        thenTheBodyIs("Bad request.");
    }

    @Test
    void shouldRefuseALoginFromAnUnreadableOrigin() throws Exception {
        givenTheWebInterfaceRuns();
        givenTheOrigin("http://bad host");

        whenPostedWithoutToken("/login", Map.of("password", PASSWORD));

        thenTheStatusIs(403);
    }

    @Test
    void shouldRefuseToTakeAPostOnTheStyleSheet() throws Exception {
        givenTheWebInterfaceRuns();

        whenPostedWithoutToken("/style.css", Map.of());

        thenTheStatusIs(403, "the style sheet is only read");
    }

    @Test
    void shouldNotOpenTheSessionWithOtherCookies() throws Exception {
        givenALoggedInUser();
        givenOnlyTheCookies("other=1; broken");

        whenThePageIsAsked("/");

        thenTheStatusIs(303);
    }

    @Test
    void shouldNotOpenTheSessionWithAForgedCookie() throws Exception {
        givenALoggedInUser();
        givenTheSessionCookieForged("forged");

        whenThePageIsAsked("/");

        thenTheStatusIs(303);
    }

    @Test
    void shouldOpenTheSessionWithItsOwnCookieAmongOthers() throws Exception {
        givenALoggedInUser();
        givenOtherCookiesBeforeTheSessionOne("other=1; broken");

        whenThePageIsAsked("/");

        thenTheStatusIs(200);
    }

    @Test
    void shouldAnswerNotFoundForAFormOfAnUnknownPage() throws Exception {
        givenALoggedInUser();

        whenTheFormIsPosted("/nowhere", Map.of());

        thenTheStatusIs(404);
        thenTheBodyIs("Not found.");
    }

    @Test
    void shouldStillAnswerOnceTheEventsEnd() throws Exception {
        givenALoggedInUser();
        givenTheEventsEndWith("engine stopped");

        whenThePageIsAsked("/");

        thenTheStatusIs(200);
    }

    @Test
    void shouldStillShowTheLoginPageOnceTheEventsEnd() throws Exception {
        givenAnotherInstanceWhoseEventsEnded();

        whenTheLoginPageOfTheOtherInstanceIsAsked();

        thenTheStatusIs(200);
    }

    private void givenTheWebInterfaceRuns() throws IOException {
        String hash = Passwords.hash(PASSWORD.toCharArray(), new byte[16], 1000);
        this.ui = WebUi.start(this.fake.engine(), new WebUiSettings(true, FreePorts.tcp(), false, hash));
    }

    private void givenALoggedInUser() throws Exception {
        givenTheWebInterfaceRuns();
        HttpResponse<String> in = post("/login", Map.of("password", PASSWORD));
        assertThat(in.statusCode()).isEqualTo(303);
        String set = in.headers().firstValue("Set-Cookie").orElseThrow();
        this.cookie = set.substring(0, set.indexOf(';'));
    }

    private void givenTheWebInterfaceRunsWithASlowPassword(int checksAtOnce) throws IOException {
        // Two million rounds keep the first check busy for a while.
        String slow = "pbkdf2-sha512$2000000$" + "A".repeat(22) + "$" + "A".repeat(86);
        this.ui = WebUi.start(this.fake.engine(), new WebUiSettings(true, FreePorts.tcp(), false, slow), checksAtOnce);
    }

    private void givenALoginBeingChecked(String password) throws InterruptedException {
        this.firstLogin = this.http.sendAsync(
                request("/login")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(form(Map.of("password", password)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (this.ui.checkingPasswords() == 0) {
            assertThat(System.nanoTime()).as("waited too long").isLessThan(end);
            Thread.sleep(5);
        }
    }

    private void givenTheEngineDownloads(DownloadInfo... downloads) {
        this.fake.answer("downloads", args -> CompletableFuture.completedFuture(List.of(downloads)));
        this.fake.answer("categories", args -> CompletableFuture.completedFuture(List.of()));
    }

    private void givenAnotherInstanceWithTheUserLoggedIn() throws Exception {
        startAnotherInstance();
        HttpResponse<String> in = this.http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.other.port() + "/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(form(Map.of("password", PASSWORD)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        String set = in.headers().firstValue("Set-Cookie").orElseThrow();
        this.otherCookie = set.substring(0, set.indexOf(';'));
    }

    private void givenAnotherInstanceWhoseEventsEnded() throws IOException {
        startAnotherInstance();
        this.otherEngine.events.close();
    }

    private void givenTheEngineConnectsAndDisconnects() {
        this.fake.answer("connect", args -> {
            this.asked.add(args[0]);
            return CompletableFuture.completedFuture(null);
        });
        this.fake.answer("disconnect", args -> {
            this.asked.add("disconnect");
            return CompletableFuture.completedFuture(null);
        });
    }

    private void givenTheOrigin(String value) {
        this.origin = value;
    }

    private void givenOnlyTheCookies(String cookies) {
        this.cookie = cookies;
    }

    private void givenTheSessionCookieForged(String value) {
        this.cookie = this.cookie.substring(0, this.cookie.indexOf('=')) + "=" + value;
    }

    private void givenOtherCookiesBeforeTheSessionOne(String others) {
        this.cookie = others + "; " + this.cookie;
    }

    private void givenTheEventsEndWith(String message) {
        this.fake.events.closeExceptionally(new IllegalStateException(message));
    }

    private void whenThePageIsAsked(String path) throws Exception {
        this.answer = get(path);
    }

    private void whenThePageIsAskedTakingGzip(String path) throws Exception {
        this.packed = this.http.send(
                request(path)
                        .header("Accept-Encoding", "gzip, deflate, br")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private void whenTheHeadIsAsked(String path) throws Exception {
        whenThePageIsAskedWithTheMethod(path, "HEAD");
    }

    private void whenThePageIsAskedWithTheMethod(String path, String method) throws Exception {
        this.answer = this.http.send(
                request(path)
                        .method(method, HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void whenTheApiIsCalled(String method, String path, Optional<String> token) throws Exception {
        HttpRequest.Builder call = request(path).method(method, HttpRequest.BodyPublishers.noBody());
        token.ifPresent(v -> call.header("X-NjMule-CSRF", v));
        this.answer = this.http.send(call.build(), HttpResponse.BodyHandlers.ofString());
    }

    // The token the app's page carries for its session.
    private void whenTheApiIsCalledWithTheSessionsToken(String method, String path) throws Exception {
        Matcher m = APP_TOKEN.matcher(get("/").body());
        assertThat(m.find()).isTrue();
        whenTheApiIsCalled(method, path, Optional.of(m.group(1)));
    }

    private void whenTheFormIsPosted(String path, Map<String, String> fields) throws Exception {
        Map<String, String> all = new LinkedHashMap<>(fields);
        all.put("csrf", csrf());
        this.answer = post(path, all);
    }

    private void whenPostedWithoutToken(String path, Map<String, String> fields) throws Exception {
        HttpRequest.Builder request = request(path);
        if (this.origin != null) {
            request.header("Origin", this.origin);
        }
        this.answer = this.http.send(
                request.header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(form(fields))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // Cookies do not depend on the port: the browser sends both to each.
    private void whenThePageIsAskedWithTheCookiesOfBothInstances(String path) throws Exception {
        this.mine = this.cookie;
        this.cookie = this.otherCookie + "; " + this.mine;
        this.answer = get(path);
    }

    private void whenTheWebInterfaceStartsWith(WebUiSettings settings) {
        this.exception = catchThrowable(() -> this.ui = WebUi.start(this.fake.engine(), settings));
    }

    private void whenTheLoginPageOfTheOtherInstanceIsAsked() throws Exception {
        this.answer = this.http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.other.port() + "/login"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void thenTheStatusIs(int status) {
        assertThat(this.answer.statusCode()).isEqualTo(status);
    }

    private void thenTheStatusIs(int status, String why) {
        assertThat(this.answer.statusCode()).as(why).isEqualTo(status);
    }

    private void thenTheHeaderIs(String name, String value) {
        assertThat(this.answer.headers().firstValue(name)).hasValue(value);
    }

    private void thenTheContentLengthIsPositive() {
        assertThat(this.answer.headers().firstValueAsLong("Content-Length").orElse(0))
                .isPositive();
    }

    private void thenTheBodyIs(String body) {
        assertThat(this.answer.body()).isEqualTo(body);
    }

    private void thenTheHeaderIsMissing(String name) {
        assertThat(this.answer.headers().firstValue(name)).isEmpty();
    }

    private void thenItComesCompressedHolding(String part) throws IOException {
        assertThat(this.packed.statusCode()).isEqualTo(200);
        assertThat(this.packed.headers().firstValue("Content-Encoding")).hasValue("gzip");
        assertThat(this.packed.headers().firstValue("Vary")).hasValue("Accept-Encoding");
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(this.packed.body()))) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).contains(part);
        }
    }

    private void thenTheBodyContains(String... parts) {
        assertThat(this.answer.body()).contains(parts);
    }

    private void thenTheBodyDoesNotContain(String... parts) {
        assertThat(this.answer.body()).doesNotContain(parts);
    }

    private void thenTheFirstLoginGets(int status) throws Exception {
        assertThat(this.firstLogin.get().statusCode()).isEqualTo(status);
    }

    private void thenTheCookiesAreNamedAfterThePortsOfTheirInstances() {
        assertThat(this.mine).startsWith("njmule-session-" + this.ui.port() + "=");
        assertThat(this.otherCookie).startsWith("njmule-session-" + this.other.port() + "=");
    }

    private void thenTheEngineWasNotAskedToDisconnect() {
        assertThat(this.asked).doesNotContain("disconnect");
    }

    private void thenTheEngineWasAskedToDisconnect() {
        assertThat(this.asked).containsExactly("disconnect");
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private void startAnotherInstance() throws IOException {
        this.otherEngine = new FakeEngine();
        String hash = Passwords.hash(PASSWORD.toCharArray(), new byte[16], 1000);
        this.other = WebUi.start(this.otherEngine.engine(), new WebUiSettings(true, FreePorts.tcp(), false, hash));
    }

    // The search page asks nothing of the engine.
    private String csrf() throws Exception {
        Matcher m = APP_TOKEN.matcher(get("/").body());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return this.http.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
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
}
