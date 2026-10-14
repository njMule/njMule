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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.PartMap;
import org.njmule.core.api.SearchId;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.api.ServerInfo;

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

    @Test
    void searchesOfTheWindowLeaveThoseOfThePagesAlone() throws Exception {
        this.start();
        this.login();
        this.fake.answer("search", args -> CompletableFuture.completedFuture(new SearchId(1)));
        HttpResponse<String> started = this.post("/search", Map.of("query", "linux", "csrf", this.csrf()));
        assertThat(started.headers().firstValue("Location")).hasValue("/search?id=1");
        this.fake.events.submit(new EngineEvent.SearchResults(new SearchId(1), List.of(result("first.iso"))));
        for (long other = 100; other < 120; other++) {
            this.fake.events.submit(new EngineEvent.SearchResults(new SearchId(other), List.of(result("other.iso"))));
        }
        this.fake.events.submit(new EngineEvent.SearchResults(new SearchId(1), List.of(result("second.iso"))));
        awaitPage(() -> this.get("/search?id=1").body().contains("second.iso"));
        assertThat(this.get("/search?id=1").body()).contains("first.iso").doesNotContain("other.iso");
        assertThat(this.get("/search?id=100").body()).doesNotContain("results;");

        // Results may come before the request that started the search has its id.
        this.fake.answer("search", args -> {
            this.fake.events.submit(new EngineEvent.SearchResults(new SearchId(2), List.of(result("early.iso"))));
            return CompletableFuture.supplyAsync(
                    () -> new SearchId(2), CompletableFuture.delayedExecutor(500, TimeUnit.MILLISECONDS));
        });
        this.post("/search", Map.of("query", "early", "csrf", this.csrf()));
        assertThat(this.get("/search?id=2").body()).contains("early.iso");
    }

    @Test
    void twoInstancesOnOneComputerKeepTheirSessions() throws Exception {
        this.start();
        this.login();
        String mine = this.cookie;
        FakeEngine otherEngine = new FakeEngine();
        String hash = Passwords.hash(PASSWORD.toCharArray(), new byte[16], 1000);
        try (WebUi other = WebUi.start(otherEngine.engine(), new WebUiSettings(true, freePort(), false, hash))) {
            HttpResponse<String> in = this.http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + other.port() + "/login"))
                            .POST(form(Map.of("password", PASSWORD)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            String set = in.headers().firstValue("Set-Cookie").orElseThrow();
            String theirs = set.substring(0, set.indexOf(';'));
            assertThat(mine).startsWith("njmule-session-" + this.ui.port() + "=");
            assertThat(theirs).startsWith("njmule-session-" + other.port() + "=");

            // Cookies do not depend on the port: the browser sends both to each.
            this.cookie = theirs + "; " + mine;
            assertThat(this.get("/search").statusCode()).isEqualTo(200);
        } finally {
            otherEngine.events.close();
        }
    }

    @Test
    void startsOnlyWhenOnAndWithAPassword() {
        NjMuleEngine engine = this.fake.engine();
        WebUiSettings withoutPassword = new WebUiSettings(true, 4711, false, "");

        assertThatThrownBy(() -> WebUi.start(engine, WebUiSettings.DEFAULTS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebUi.start(engine, withoutPassword)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theStatusPageTellsHowEachNetworkIsReached() throws Exception {
        this.start();
        this.login();
        Queue<EngineStatus> states = new ConcurrentLinkedQueue<>(List.of(
                status(Optional.of(server("10.0.0.1:4661", "Big <server>")), true, Optional.empty()),
                status(Optional.of(server("10.0.0.1:4661", "Big")), false, Optional.of(kad(true, true))),
                status(Optional.empty(), false, Optional.of(kad(true, false))),
                status(Optional.empty(), false, Optional.of(kad(false, false)))));
        this.fake.answer("status", args -> CompletableFuture.completedFuture(states.poll()));

        assertThat(this.get("/").body()).contains("eD2K<b>Big &lt;server&gt; (LowID)</b>", "Kad<b>off</b>");
        assertThat(this.get("/").body()).contains("eD2K<b>Big (HighID)</b>", "Kad<b>firewalled</b>");
        assertThat(this.get("/").body()).contains("eD2K<b>not connected</b>", "Kad<b>open</b>");
        assertThat(this.get("/").body()).contains("Kad<b>not connected</b>");
    }

    @Test
    void listsTheServersAndConnectsOrDisconnects() throws Exception {
        this.start();
        this.login();
        this.fake.answer(
                "servers", args -> CompletableFuture.completedFuture(List.of(server("10.0.0.1:4661", "Fake <1>"))));
        List<Object> asked = new CopyOnWriteArrayList<>();
        this.fake.answer("connect", args -> {
            asked.add(args[0]);
            return CompletableFuture.completedFuture(null);
        });
        this.fake.answer("disconnect", args -> {
            asked.add("disconnect");
            return CompletableFuture.completedFuture(null);
        });

        assertThat(this.get("/servers").body())
                .contains(
                        "<h1>Servers</h1>",
                        "<td>Fake &lt;1&gt;</td><td>10.0.0.1:4661</td><td>1200</td><td>34000</td>",
                        "name=\"address\" value=\"10.0.0.1:4661\"");
        HttpResponse<String> one =
                this.post("/servers", Map.of("action", "connect", "address", "10.0.0.1:4661", "csrf", this.csrf()));
        assertThat(one.statusCode()).isEqualTo(303);
        assertThat(one.headers().firstValue("Location")).hasValue("/servers");
        this.post("/servers", Map.of("action", "connect", "address", " ", "csrf", this.csrf()));
        this.post("/servers", Map.of("action", "disconnect", "csrf", this.csrf()));
        assertThat(asked)
                .containsExactly(Optional.of(new ServerAddress("10.0.0.1", 4661)), Optional.empty(), "disconnect");

        HttpResponse<String> unknown = this.post("/servers", Map.of("action", "explode", "csrf", this.csrf()));
        assertThat(unknown.statusCode()).isEqualTo(400);
        assertThat(unknown.body()).contains("Unknown action.", "<a href=\"/servers\">Back</a>");
        assertThat(this.post("/servers", Map.of("action", "connect", "address", "nowhere", "csrf", this.csrf()))
                        .statusCode())
                .as("an address without a port")
                .isEqualTo(400);
        this.fake.answer(
                "connect", args -> CompletableFuture.failedFuture(new IllegalStateException("no server answers")));
        HttpResponse<String> failed = this.post("/servers", Map.of("action", "connect", "csrf", this.csrf()));
        assertThat(failed.statusCode()).isEqualTo(400);
        assertThat(failed.body()).contains("no server answers");
    }

    @Test
    void downloadsASearchResultWhileItIsKept() throws Exception {
        this.start();
        this.login();
        List<SearchRequest> searched = new CopyOnWriteArrayList<>();
        this.fake.answer("search", args -> {
            searched.add((SearchRequest) args[0]);
            return CompletableFuture.completedFuture(new SearchId(5));
        });
        List<Ed2kLink.FileLink> added = new CopyOnWriteArrayList<>();
        this.fake.answer("addDownload", args -> {
            added.add((Ed2kLink.FileLink) args[0]);
            return CompletableFuture.completedFuture(null);
        });

        assertThat(this.post("/search", Map.of("query", " ", "csrf", this.csrf()))
                        .headers()
                        .firstValue("Location"))
                .as("nothing to search")
                .hasValue("/search");
        HttpResponse<String> started =
                this.post("/search", Map.of("query", "linux", "scope", "KAD", "csrf", this.csrf()));
        assertThat(started.headers().firstValue("Location")).hasValue("/search?id=5");
        assertThat(searched).containsExactly(new SearchRequest("linux", SearchRequest.Scope.KAD));
        SearchResult found = result("found.iso");
        this.fake.events.submit(new EngineEvent.SearchResults(new SearchId(5), List.of(found)));
        awaitPage(() -> this.get("/search?id=5").body().contains("found.iso"));

        HttpResponse<String> taken = this.post(
                "/search", Map.of("search", "5", "download", found.hash().toHex(), "csrf", this.csrf()));
        assertThat(taken.headers().firstValue("Location")).hasValue("/transfers");
        assertThat(added).containsExactly(found.link());

        HttpResponse<String> gone =
                this.post("/search", Map.of("search", "5", "download", "AB".repeat(16), "csrf", this.csrf()));
        assertThat(gone.statusCode()).isEqualTo(400);
        assertThat(gone.body()).contains("search again");
        assertThat(this.post("/search", Map.of("query", "linux", "scope", "MARS", "csrf", this.csrf()))
                        .statusCode())
                .isEqualTo(400);
    }

    @Test
    void aSearchKeepsSoManyResultsAndUpdatesThem() throws Exception {
        this.start();
        this.login();
        this.fake.answer("search", args -> CompletableFuture.completedFuture(new SearchId(6)));
        this.post("/search", Map.of("query", "many", "csrf", this.csrf()));

        this.fake.events.submit(new EngineEvent.SearchResults(
                new SearchId(6),
                IntStream.range(0, 1005).mapToObj(i -> numbered(i, 1)).toList()));
        // A kept result gets its new sources, a new one finds no room.
        this.fake.events.submit(
                new EngineEvent.SearchResults(new SearchId(6), List.of(numbered(0, 99), numbered(2000, 1))));
        awaitPage(() -> this.get("/search?id=6").body().contains("<td>99</td>"));

        assertThat(this.get("/search?id=6").body())
                .contains("1000 results;", "n0.iso", "n999.iso")
                .doesNotContain("n1000.iso", "n2000.iso");
    }

    @Test
    void transfersShowAnEmptyFileAsNotStarted() throws Exception {
        this.start();
        this.login();
        this.fake.answer(
                "downloads",
                args -> CompletableFuture.completedFuture(List.of(new DownloadInfo(
                        Hash16.parseHex("AB".repeat(16)),
                        "empty.txt",
                        0,
                        0,
                        DownloadInfo.Status.STOPPED,
                        DownloadPriority.NORMAL,
                        0,
                        0,
                        0,
                        0,
                        PartMap.EMPTY))));
        this.fake.answer("categories", args -> CompletableFuture.completedFuture(List.of()));

        assertThat(this.get("/transfers").body())
                .contains("empty.txt", "value=\"0\"></progress> 0 %", "stopped", "Resume")
                .doesNotContain(">Stop<");
    }

    @Test
    void refusesLoginsThatAreNoForm() throws Exception {
        this.start();
        HttpResponse<String> put = this.http.send(
                this.request("/login")
                        .method("PUT", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(put.statusCode()).isEqualTo(405);

        HttpResponse<String> huge = this.post("/login", Map.of("password", "x".repeat(16 * 1024)));
        assertThat(huge.statusCode()).isEqualTo(400);
        assertThat(huge.body()).isEqualTo("Bad request.");

        HttpResponse<String> unreadable = this.http.send(
                this.request("/login")
                        .header("Origin", "http://bad host")
                        .POST(form(Map.of("password", PASSWORD)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(unreadable.statusCode()).isEqualTo(403);
        assertThat(this.post("/style.css", Map.of()).statusCode())
                .as("the style sheet is only read")
                .isEqualTo(403);
    }

    @Test
    void onlyItsOwnCookieOpensTheSession() throws Exception {
        this.start();
        this.login();
        String mine = this.cookie;

        this.cookie = "other=1; broken";
        assertThat(this.get("/search").statusCode()).isEqualTo(303);
        this.cookie = mine.substring(0, mine.indexOf('=')) + "=forged";
        assertThat(this.get("/search").statusCode()).isEqualTo(303);
        this.cookie = "other=1; broken; " + mine;
        assertThat(this.get("/search").statusCode()).isEqualTo(200);
    }

    @Test
    void readsFormsAndQueriesWithEmptyOrBareFields() throws Exception {
        this.start();
        this.login();
        List<Object> added = new CopyOnWriteArrayList<>();
        this.fake.answer("addDownload", args -> {
            added.add(args[0]);
            return CompletableFuture.completedFuture(null);
        });

        HttpResponse<String> bare = this.get("/search?&id&x=1");
        assertThat(bare.statusCode()).isEqualTo(200);
        assertThat(bare.body()).doesNotContain("results;");
        assertThat(this.post("/transfers", Map.of("action", "add", "link", "  ", "csrf", this.csrf()))
                        .statusCode())
                .as("no link, nothing to add")
                .isEqualTo(303);
        assertThat(added).isEmpty();
        HttpResponse<String> nowhere = this.post("/nowhere", Map.of("csrf", this.csrf()));
        assertThat(nowhere.statusCode()).isEqualTo(404);
        assertThat(nowhere.body()).isEqualTo("Not found.");
    }

    @Test
    void pagesStillAnswerOnceTheEventsEnd() throws Exception {
        this.start();
        this.login();
        this.fake.events.closeExceptionally(new IllegalStateException("engine stopped"));
        assertThat(this.get("/search").statusCode()).isEqualTo(200);

        FakeEngine stopped = new FakeEngine();
        String hash = Passwords.hash(PASSWORD.toCharArray(), new byte[16], 1000);
        try (WebUi other = WebUi.start(stopped.engine(), new WebUiSettings(true, freePort(), false, hash))) {
            stopped.events.close();
            HttpResponse<String> login = this.http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + other.port() + "/login"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(login.statusCode()).isEqualTo(200);
        }
    }

    private static EngineStatus status(Optional<ServerInfo> server, boolean lowId, Optional<KadInfo> kad) {
        return new EngineStatus(
                server, 1234, lowId, false, kad, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "", 4662, false);
    }

    private static KadInfo kad(boolean connected, boolean firewalled) {
        return new KadInfo(connected, 10, 5, firewalled, "", "", 4672);
    }

    private static ServerInfo server(String address, String name) {
        return new ServerInfo(
                address, name, "", 1200, 34000, 5000, 0, ServerInfo.Priority.NORMAL, 20, 0, 0, 0, "", false, false);
    }

    private static SearchResult numbered(int number, int sources) {
        return new SearchResult(
                Hash16.parseHex(String.format(Locale.ROOT, "%032X", number + 1)),
                "n" + number + ".iso",
                1000,
                sources,
                0,
                Optional.empty());
    }

    private static SearchResult result(String name) {
        return new SearchResult(
                Hash16.parseHex(
                        Integer.toHexString(name.hashCode() | 0x10000000).repeat(4)),
                name,
                1000,
                1,
                1,
                Optional.empty());
    }

    private static void awaitPage(Callable<Boolean> condition) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.call()) {
            assertThat(System.nanoTime()).as("waited too long").isLessThan(end);
            Thread.sleep(20);
        }
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
