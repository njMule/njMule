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

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.KadInfo;
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
    private static final String HASH = "AB".repeat(16);
    private static final String ED2K_LINK = "ed2k://|file|a.bin|1000|" + HASH + "|/";
    private static final String MAGNET_LINK = "magnet:?xt=urn:ed2k:" + "CD".repeat(16) + "&xl=1000&dn=b.bin";

    private final FakeEngine fake = new FakeEngine();
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<Ed2kLink.FileLink> added = new CopyOnWriteArrayList<>();
    private final List<SearchRequest> searched = new CopyOnWriteArrayList<>();
    private final List<Object> asked = new CopyOnWriteArrayList<>();
    private WebUi ui;
    private String cookie;
    private String origin;
    private HttpResponse<String> answer;
    private CompletableFuture<HttpResponse<String>> firstLogin;
    private Pattern linkPattern;
    private int linkMaxLength;
    private Optional<ServerInfo> server = Optional.empty();
    private boolean lowId;
    private Optional<KadInfo> kad = Optional.empty();
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
    void shouldAnswerUnavailableWhenTheEngineFails() throws Exception {
        givenALoggedInUser();

        whenThePageIsAsked("/");

        thenTheStatusIs(503);
        thenTheBodyContains("status failed");
    }

    @Test
    void shouldAnswerAnInternalErrorForAFaultOfThePage() throws Exception {
        givenALoggedInUser();
        givenTheEngineAnswersNoStatus();

        whenThePageIsAsked("/");

        thenTheStatusIs(500, "a fault of the page");
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

        whenTheHeadIsAsked("/search");

        thenTheStatusIs(303);
    }

    @Test
    void shouldAnswerTheHeadOfAPageToAUserLoggedIn() throws Exception {
        givenALoggedInUser();

        whenTheHeadIsAsked("/search");

        thenTheStatusIs(200);
    }

    @Test
    void shouldAnswerTheHeadOfAnUnknownPageWithNotFound() throws Exception {
        givenALoggedInUser();

        whenTheHeadIsAsked("/nowhere");

        thenTheStatusIs(404);
    }

    @Test
    void shouldDoAnActionOnADownload() throws Exception {
        givenALoggedInUser();
        givenTheEnginePausesTheDownload();

        whenTheFormIsPosted("/transfers", Map.of("action", "pause", "hash", HASH));

        thenTheStatusIs(303);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pause", "resume", "stop", "cancel", "priority"})
    void shouldReportAnActionOnADownloadThatIsGone(String action) throws Exception {
        givenALoggedInUser();
        givenTheEngineNoLongerHasTheDownload();

        whenTheFormIsPosted("/transfers", Map.of("action", action, "hash", HASH, "priority", "high"));

        thenTheStatusIs(400);
        thenTheBodyContains("That download is gone");
    }

    @Test
    void shouldLetTheLinkFieldTakeAnEd2kLinkAndAMagnetLink() throws Exception {
        givenALoggedInUser();
        givenTheEngineAddsDownloads();

        whenTheLinkFieldOfTheTransfersIsRead();

        thenTheLinkFieldTakes(ED2K_LINK);
        thenTheLinkFieldTakes(" " + MAGNET_LINK);
    }

    @Test
    void shouldAddTheDownloadsOfAnEd2kLinkAndAMagnetLink() throws Exception {
        givenALoggedInUser();
        givenTheEngineAddsDownloads();

        whenTheLinesAreAdded(ED2K_LINK, MAGNET_LINK);

        thenTheStatusIs(303);
        thenTheDownloadsAddedAre("a.bin", "b.bin");
    }

    @Test
    void shouldAddTheLongestLinkTheFieldTakes() throws Exception {
        givenALoggedInUser();
        givenTheEngineAddsDownloads();
        givenTheLinkFieldOfTheTransfers();

        whenLinksAsLongAsTheFieldTakesAreAdded(1);

        thenTheStatusIs(303);
        thenTheDownloadsAddedAre(1);
    }

    @Test
    void shouldRefuseMoreTextThanTheLinkFieldTakes() throws Exception {
        givenALoggedInUser();
        givenTheEngineAddsDownloads();
        givenTheLinkFieldOfTheTransfers();

        whenLinksAsLongAsTheFieldTakesAreAdded(2);

        thenTheStatusIs(413);
        thenTheHeaderIs("Content-Type", "text/html; charset=utf-8");
        thenTheBodyContains("Too much text");
    }

    @Test
    void shouldRefuseAFormWithMoreFieldsThanAnyPageHas() throws Exception {
        givenALoggedInUser();

        whenTheFormIsPosted("/transfers", fieldsNamedUpTo(40));

        thenTheStatusIs(413);
        thenTheBodyContains("Too much text");
    }

    @Test
    void shouldReadNoFieldsFromAPostThatIsNoForm() throws Exception {
        givenALoggedInUser();

        whenTheFieldsArePostedAsPlainText("/transfers", Map.of("action", "pause", "hash", HASH));

        thenTheStatusIs(403);
        thenTheBodyContains("Expired form");
    }

    @Test
    void shouldAnswerBadRequestToAQueryThatIsNotUtf8() throws Exception {
        givenALoggedInUser();

        whenThePageIsAsked("/search?id=%C3");

        thenTheStatusIs(400);
    }

    @Test
    void shouldLeaveTheResultsOfTheSearchesOfTheWindowOutOfThePages() throws Exception {
        givenALoggedInUser();
        givenTheEngineSearchesAs(1);
        givenASearchStarted("linux", "/search?id=1");
        givenTheEngineFinds(1, "first.iso");
        givenTheEngineFindsForTheSearches(100, 120, "other.iso");
        givenTheEngineFinds(1, "second.iso");

        whenThePageIsAskedUntilItShows("/search?id=1", "second.iso");

        thenTheBodyContains("first.iso");
        thenTheBodyDoesNotContain("other.iso");
        thenThePageDoesNotShow("/search?id=100", "results;");
    }

    @Test
    void shouldKeepTheResultsThatComeBeforeTheSearchHasItsId() throws Exception {
        givenALoggedInUser();
        givenTheEngineFindsBeforeTheSearchHasItsId(2, "early.iso");
        givenASearchStarted("early");

        whenThePageIsAsked("/search?id=2");

        thenTheBodyContains("early.iso");
    }

    @Test
    void shouldKeepTheSessionsOfTwoInstancesOnOneComputer() throws Exception {
        givenALoggedInUser();
        givenAnotherInstanceWithTheUserLoggedIn();

        whenThePageIsAskedWithTheCookiesOfBothInstances("/search");

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
    void shouldShowALowIdOnAServerAndKadOff() throws Exception {
        givenALoggedInUser();
        givenConnectedToTheServer("Big <server>", true);

        whenThePageIsAsked("/");

        thenTheBodyContains("eD2K<b>Big &lt;server&gt; (LowID)</b>", "Kad<b>off</b>");
    }

    @Test
    void shouldShowAHighIdOnAServerAndKadFirewalled() throws Exception {
        givenALoggedInUser();
        givenConnectedToTheServer("Big", false);
        givenKad(true, true);

        whenThePageIsAsked("/");

        thenTheBodyContains("eD2K<b>Big (HighID)</b>", "Kad<b>firewalled</b>");
    }

    @Test
    void shouldShowNoServerAndKadOpen() throws Exception {
        givenALoggedInUser();
        givenKad(true, false);

        whenThePageIsAsked("/");

        thenTheBodyContains("eD2K<b>not connected</b>", "Kad<b>open</b>");
    }

    @Test
    void shouldShowKadNotConnected() throws Exception {
        givenALoggedInUser();
        givenKad(false, false);

        whenThePageIsAsked("/");

        thenTheBodyContains("Kad<b>not connected</b>");
    }

    @Test
    void shouldListTheServers() throws Exception {
        givenALoggedInUser();
        givenTheEngineKnowsTheServer("10.0.0.1:4661", "Fake <1>");

        whenThePageIsAsked("/servers");

        thenTheBodyContains(
                "<h1>Servers</h1>",
                "<td>Fake &lt;1&gt;</td><td>10.0.0.1:4661</td><td>1200</td><td>34000</td>",
                "name=\"address\" value=\"10.0.0.1:4661\"");
    }

    @Test
    void shouldConnectToTheServerChosen() throws Exception {
        givenALoggedInUser();
        givenTheEngineConnectsAndDisconnects();

        whenTheFormIsPosted("/servers", Map.of("action", "connect", "address", "10.0.0.1:4661"));

        thenTheStatusIs(303);
        thenTheAnswerLeadsTo("servers");
        thenTheEngineWasAskedToConnectTo("10.0.0.1", 4661);
    }

    @Test
    void shouldConnectToAnyServerWithoutAnAddress() throws Exception {
        givenALoggedInUser();
        givenTheEngineConnectsAndDisconnects();

        whenTheFormIsPosted("/servers", Map.of("action", "connect", "address", " "));

        thenTheEngineWasAskedToConnectToAnyServer();
    }

    @Test
    void shouldDisconnectFromTheServer() throws Exception {
        givenALoggedInUser();
        givenTheEngineConnectsAndDisconnects();

        whenTheFormIsPosted("/servers", Map.of("action", "disconnect"));

        thenTheEngineWasAskedToDisconnect();
    }

    @Test
    void shouldRefuseAnUnknownActionOnTheServers() throws Exception {
        givenALoggedInUser();
        givenTheEngineConnectsAndDisconnects();

        whenTheFormIsPosted("/servers", Map.of("action", "explode"));

        thenTheStatusIs(400);
        thenTheBodyContains("Unknown action.", "<a href=\"/servers\">Back</a>");
    }

    @Test
    void shouldRefuseAServerAddressWithoutAPort() throws Exception {
        givenALoggedInUser();
        givenTheEngineConnectsAndDisconnects();

        whenTheFormIsPosted("/servers", Map.of("action", "connect", "address", "nowhere"));

        thenTheStatusIs(400, "an address without a port");
    }

    @Test
    void shouldReportAConnectionThatFails() throws Exception {
        givenALoggedInUser();
        givenTheEngineFailsToConnect("no server answers");

        whenTheFormIsPosted("/servers", Map.of("action", "connect"));

        thenTheStatusIs(400);
        thenTheBodyContains("no server answers");
    }

    @Test
    void shouldStayOnTheSearchPageWithNothingToSearch() throws Exception {
        givenALoggedInUser();
        givenTheEngineSearchesAs(5);

        whenTheFormIsPosted("/search", Map.of("query", " "));

        thenTheAnswerLeadsTo("search", "nothing to search");
    }

    @Test
    void shouldSearchInTheScopeChosen() throws Exception {
        givenALoggedInUser();
        givenTheEngineSearchesAs(5);

        whenTheFormIsPosted("/search", Map.of("query", "linux", "scope", "KAD"));

        thenTheAnswerLeadsTo("search?id=5");
        thenTheEngineSearched("linux", SearchRequest.Scope.KAD);
    }

    @Test
    void shouldSearchKadAutomaticallyWhenBothNetworksAreConnected() throws Exception {
        givenALoggedInUser();
        givenTheEngineSearchesAs(5);
        givenTheEngineConnectedTo(Optional.of(server("81.2.3.4:4661", "Ordinary")), true);

        whenTheFormIsPosted("/search", Map.of("query", "linux", "scope", "AUTOMATIC"));

        thenTheAnswerLeadsTo("search?id=5");
        thenTheEngineSearched("linux", SearchRequest.Scope.KAD);
    }

    @Test
    void shouldSearchTheServerAutomaticallyWithoutKad() throws Exception {
        givenALoggedInUser();
        givenTheEngineSearchesAs(5);
        givenTheEngineConnectedTo(Optional.of(server("81.2.3.4:4661", "Ordinary")), false);

        whenTheFormIsPosted("/search", Map.of("query", "linux", "scope", "AUTOMATIC"));

        thenTheAnswerLeadsTo("search?id=5");
        thenTheEngineSearched("linux", SearchRequest.Scope.SERVER);
    }

    @Test
    void shouldRefuseAnAutomaticSearchWithoutANetwork() throws Exception {
        givenALoggedInUser();
        givenTheEngineSearchesAs(5);
        givenTheEngineConnectedTo(Optional.empty(), false);

        whenTheFormIsPosted("/search", Map.of("query", "linux", "scope", "AUTOMATIC"));

        thenTheStatusIs(400);
        thenTheBodyContains("Not connected");
    }

    @Test
    void shouldDownloadASearchResultWhileItIsKept() throws Exception {
        givenALoggedInUser();
        givenTheEngineSearchesAs(5);
        givenTheEngineAddsDownloads();
        givenASearchStarted("linux", "/search?id=5");
        givenTheEngineFinds(5, "found.iso");
        givenThePageShows("/search?id=5", "found.iso");

        whenTheResultIsDownloaded(5, "found.iso");

        thenTheAnswerLeadsTo("transfers");
        thenTheResultWasAdded("found.iso");
    }

    @Test
    void shouldAskToSearchAgainForAResultThatIsNotKept() throws Exception {
        givenALoggedInUser();
        givenTheEngineSearchesAs(5);
        givenTheEngineAddsDownloads();
        givenASearchStarted("linux", "/search?id=5");
        givenTheEngineFinds(5, "found.iso");
        givenThePageShows("/search?id=5", "found.iso");

        whenTheFormIsPosted("/search", Map.of("search", "5", "download", HASH));

        thenTheStatusIs(400);
        thenTheBodyContains("search again");
    }

    @Test
    void shouldRefuseAnUnknownSearchScope() throws Exception {
        givenALoggedInUser();
        givenTheEngineSearchesAs(5);

        whenTheFormIsPosted("/search", Map.of("query", "linux", "scope", "MARS"));

        thenTheStatusIs(400);
    }

    @Test
    void shouldKeepSoManyResultsOfASearchAndUpdateThem() throws Exception {
        givenALoggedInUser();
        givenTheEngineSearchesAs(6);
        givenASearchStarted("many");
        givenTheEngineFindsNumberedResults(6, 1005);
        // A kept result gets its new sources, a new one finds no room.
        givenTheEngineFinds(6, numbered(0, 99), numbered(2000, 1));

        whenThePageIsAskedUntilItShows("/search?id=6", "<td>99</td>");

        thenTheBodyContains("1000 results;", "n0.iso", "n999.iso");
        thenTheBodyDoesNotContain("n1000.iso", "n2000.iso");
    }

    @Test
    void shouldShowAnEmptyFileAsNotStarted() throws Exception {
        givenALoggedInUser();
        givenTheEngineDownloadsAnEmptyStoppedFile("empty.txt");

        whenThePageIsAsked("/transfers");

        thenTheBodyContains("empty.txt", "value=\"0\"></progress> 0 %", "stopped", "Resume");
        thenTheBodyDoesNotContain(">Stop<");
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

        whenThePageIsAsked("/search");

        thenTheStatusIs(303);
    }

    @Test
    void shouldNotOpenTheSessionWithAForgedCookie() throws Exception {
        givenALoggedInUser();
        givenTheSessionCookieForged("forged");

        whenThePageIsAsked("/search");

        thenTheStatusIs(303);
    }

    @Test
    void shouldOpenTheSessionWithItsOwnCookieAmongOthers() throws Exception {
        givenALoggedInUser();
        givenOtherCookiesBeforeTheSessionOne("other=1; broken");

        whenThePageIsAsked("/search");

        thenTheStatusIs(200);
    }

    @Test
    void shouldReadAQueryWithEmptyOrBareFields() throws Exception {
        givenALoggedInUser();

        whenThePageIsAsked("/search?&id&x=1");

        thenTheStatusIs(200);
        thenTheBodyDoesNotContain("results;");
    }

    @Test
    void shouldAddNothingForAnEmptyLink() throws Exception {
        givenALoggedInUser();
        givenTheEngineAddsDownloads();

        whenTheLinesAreAdded("  ");

        thenTheStatusIs(303, "no link, nothing to add");
        thenTheDownloadsAddedAre(0);
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

        whenThePageIsAsked("/search");

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

    private void givenTheEngineAnswersNoStatus() {
        this.fake.answer("status", args -> CompletableFuture.completedFuture(null));
    }

    private void givenTheEnginePausesTheDownload() {
        this.fake.answer("pauseDownload", args -> CompletableFuture.completedFuture(true));
    }

    private void givenTheEngineNoLongerHasTheDownload() {
        for (String action : new String[] {"pauseDownload", "resumeDownload", "stopDownload", "cancelDownload"}) {
            this.fake.answer(action, args -> CompletableFuture.completedFuture(false));
        }
        this.fake.answer("setDownloadPriority", args -> CompletableFuture.completedFuture(false));
    }

    private void givenTheEngineAddsDownloads() {
        this.fake.answer("downloads", args -> CompletableFuture.completedFuture(List.of()));
        this.fake.answer("addDownload", args -> {
            this.added.add((Ed2kLink.FileLink) args[0]);
            return CompletableFuture.completedFuture(null);
        });
    }

    private void givenTheLinkFieldOfTheTransfers() throws Exception {
        readTheLinkFieldOfTheTransfers();
    }

    private void givenTheEngineConnectedTo(Optional<ServerInfo> server, boolean kad) {
        EngineStatus connected = status(server, false, Optional.of(kad(kad, false)));
        this.fake.answer("status", args -> CompletableFuture.completedFuture(connected));
        this.fake.answer(
                "servers",
                args -> CompletableFuture.completedFuture(server.stream().toList()));
    }

    private void givenTheEngineSearchesAs(long id) {
        this.fake.answer("search", args -> {
            this.searched.add((SearchRequest) args[0]);
            return CompletableFuture.completedFuture(new SearchId(id));
        });
    }

    // Results may come before the request that started the search has its id.
    private void givenTheEngineFindsBeforeTheSearchHasItsId(long id, String name) {
        this.fake.answer("search", args -> {
            this.fake.events.submit(new EngineEvent.SearchResults(new SearchId(id), List.of(result(name))));
            return CompletableFuture.supplyAsync(
                    () -> new SearchId(id), CompletableFuture.delayedExecutor(500, TimeUnit.MILLISECONDS));
        });
    }

    private void givenASearchStarted(String query) throws Exception {
        whenTheFormIsPosted("/search", Map.of("query", query));
    }

    // The answer leads to the page relative to the one asked.
    private void givenASearchStarted(String query, String page) throws Exception {
        whenTheFormIsPosted("/search", Map.of("query", query));
        thenTheAnswerLeadsTo(page.substring(1));
    }

    private void givenTheEngineFinds(long search, String name) {
        givenTheEngineFinds(search, result(name));
    }

    private void givenTheEngineFinds(long search, SearchResult... results) {
        this.fake.events.submit(new EngineEvent.SearchResults(new SearchId(search), List.of(results)));
    }

    private void givenTheEngineFindsForTheSearches(long from, long to, String name) {
        for (long search = from; search < to; search++) {
            givenTheEngineFinds(search, name);
        }
    }

    private void givenTheEngineFindsNumberedResults(long search, int count) {
        this.fake.events.submit(new EngineEvent.SearchResults(
                new SearchId(search),
                IntStream.range(0, count).mapToObj(i -> numbered(i, 1)).toList()));
    }

    private void givenThePageShows(String path, String text) throws Exception {
        askUntilItShows(path, text);
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

    private void givenConnectedToTheServer(String name, boolean low) {
        this.server = Optional.of(server("10.0.0.1:4661", name));
        this.lowId = low;
        answerTheStatus();
    }

    private void givenKad(boolean connected, boolean firewalled) {
        this.kad = Optional.of(kad(connected, firewalled));
        answerTheStatus();
    }

    private void givenTheEngineKnowsTheServer(String address, String name) {
        this.fake.answer("servers", args -> CompletableFuture.completedFuture(List.of(server(address, name))));
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

    private void givenTheEngineFailsToConnect(String message) {
        this.fake.answer("connect", args -> CompletableFuture.failedFuture(new IllegalStateException(message)));
    }

    private void givenTheEngineDownloadsAnEmptyStoppedFile(String name) {
        this.fake.answer(
                "downloads",
                args -> CompletableFuture.completedFuture(List.of(new DownloadInfo(
                        Hash16.parseHex(HASH),
                        name,
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

    private void whenThePageIsAskedUntilItShows(String path, String text) throws Exception {
        askUntilItShows(path, text);
    }

    private void whenTheFormIsPosted(String path, Map<String, String> fields) throws Exception {
        Map<String, String> all = new LinkedHashMap<>(fields);
        all.put("csrf", csrf());
        this.answer = post(path, all);
    }

    private void whenTheFieldsArePostedAsPlainText(String path, Map<String, String> fields) throws Exception {
        Map<String, String> all = new LinkedHashMap<>(fields);
        all.put("csrf", csrf());
        HttpRequest.Builder request = request(path).header("Content-Type", "text/plain");
        if (this.origin != null) {
            request.header("Origin", this.origin);
        }
        this.answer = this.http.send(request.POST(form(all)).build(), HttpResponse.BodyHandlers.ofString());
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

    private void whenTheLinkFieldOfTheTransfersIsRead() throws Exception {
        readTheLinkFieldOfTheTransfers();
    }

    private void whenTheLinesAreAdded(String... lines) throws Exception {
        whenTheFormIsPosted("/transfers", Map.of("action", "add", "link", String.join("\n", lines)));
    }

    // The longest text the field takes, each character taking 9 bytes once encoded.
    private void whenLinksAsLongAsTheFieldTakesAreAdded(int count) throws Exception {
        String head = "ed2k://|file|";
        String tail = "|1000|" + HASH + "|/";
        String longest = head + "\u20AC".repeat(this.linkMaxLength - head.length() - tail.length()) + tail;
        whenTheFormIsPosted(
                "/transfers", Map.of("action", "add", "link", String.join(" ", Collections.nCopies(count, longest))));
    }

    private void whenTheResultIsDownloaded(long search, String name) throws Exception {
        whenTheFormIsPosted(
                "/search",
                Map.of(
                        "search",
                        Long.toString(search),
                        "download",
                        result(name).hash().toHex()));
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

    private void thenTheAnswerLeadsTo(String location) {
        assertThat(this.answer.headers().firstValue("Location")).hasValue(location);
    }

    private void thenTheAnswerLeadsTo(String location, String why) {
        assertThat(this.answer.headers().firstValue("Location")).as(why).hasValue(location);
    }

    private void thenTheContentLengthIsPositive() {
        assertThat(this.answer.headers().firstValueAsLong("Content-Length").orElse(0))
                .isPositive();
    }

    private void thenTheBodyIs(String body) {
        assertThat(this.answer.body()).isEqualTo(body);
    }

    private void thenTheBodyContains(String... parts) {
        assertThat(this.answer.body()).contains(parts);
    }

    private void thenTheBodyDoesNotContain(String... parts) {
        assertThat(this.answer.body()).doesNotContain(parts);
    }

    private void thenThePageDoesNotShow(String path, String text) throws Exception {
        assertThat(get(path).body()).doesNotContain(text);
    }

    private void thenTheFirstLoginGets(int status) throws Exception {
        assertThat(this.firstLogin.get().statusCode()).isEqualTo(status);
    }

    private void thenTheLinkFieldTakes(String text) {
        assertThat(this.linkPattern.matcher(text).matches()).isTrue();
    }

    private void thenTheDownloadsAddedAre(String... names) {
        assertThat(this.added).extracting(Ed2kLink.FileLink::name).containsExactly(names);
    }

    private void thenTheDownloadsAddedAre(int count) {
        assertThat(this.added).hasSize(count);
    }

    private void thenTheResultWasAdded(String name) {
        assertThat(this.added).containsExactly(result(name).link());
    }

    private void thenTheEngineSearched(String expression, SearchRequest.Scope scope) {
        assertThat(this.searched).containsExactly(new SearchRequest(expression, scope));
    }

    private void thenTheCookiesAreNamedAfterThePortsOfTheirInstances() {
        assertThat(this.mine).startsWith("njmule-session-" + this.ui.port() + "=");
        assertThat(this.otherCookie).startsWith("njmule-session-" + this.other.port() + "=");
    }

    private void thenTheEngineWasAskedToConnectTo(String host, int port) {
        assertThat(this.asked).containsExactly(Optional.of(new ServerAddress(host, port)));
    }

    private void thenTheEngineWasAskedToConnectToAnyServer() {
        assertThat(this.asked).containsExactly(Optional.empty());
    }

    private void thenTheEngineWasAskedToDisconnect() {
        assertThat(this.asked).containsExactly("disconnect");
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private void answerTheStatus() {
        this.fake.answer(
                "status", args -> CompletableFuture.completedFuture(status(this.server, this.lowId, this.kad)));
    }

    private void readTheLinkFieldOfTheTransfers() throws Exception {
        String page = get("/transfers").body();
        Matcher field = Pattern.compile("name=\"link\"[^>]* pattern=\"([^\"]+)\" maxlength=\"(\\d+)\"")
                .matcher(page);
        assertThat(field.find()).isTrue();
        this.linkPattern = Pattern.compile(field.group(1));
        this.linkMaxLength = Integer.parseInt(field.group(2));
    }

    private void askUntilItShows(String path, String text) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        for (this.answer = get(path); !this.answer.body().contains(text); this.answer = get(path)) {
            assertThat(System.nanoTime()).as("waited too long").isLessThan(end);
            Thread.sleep(20);
        }
    }

    private void startAnotherInstance() throws IOException {
        this.otherEngine = new FakeEngine();
        String hash = Passwords.hash(PASSWORD.toCharArray(), new byte[16], 1000);
        this.other = WebUi.start(this.otherEngine.engine(), new WebUiSettings(true, FreePorts.tcp(), false, hash));
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

    // The search page asks nothing of the engine.
    private String csrf() throws Exception {
        Matcher m = CSRF.matcher(get("/search").body());
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

    private static Map<String, String> fieldsNamedUpTo(int count) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 1; i <= count; i++) {
            fields.put("field" + i, "x");
        }
        return fields;
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
