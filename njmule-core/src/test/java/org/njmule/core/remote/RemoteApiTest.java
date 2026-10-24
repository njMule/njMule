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
package org.njmule.core.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.PartMap;
import org.njmule.core.api.PortTest;
import org.njmule.core.api.SearchId;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.SharedDirectory;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.config.Preferences;

/** The remote-control API against an engine the test controls. */
@Timeout(60)
class RemoteApiTest {

    // Wrong tokens the guard of the test lets through.
    private static final int ALLOWED_ATTEMPTS = 3;

    private static final String HASH = "AB".repeat(16);
    private static final String LINK = "ed2k://|file|talk.bin|5000|" + HASH + "|/";

    @TempDir
    Path dir;

    private final FakeEngine fake = new FakeEngine();
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final CountDownLatch exited = new CountDownLatch(1);
    private final AtomicInteger attempts = new AtomicInteger();
    private RemoteApi api;
    private ApiServer server;
    private String token;
    private HttpResponse<String> answer;
    private Preferences preferences = Preferences.defaults();
    private BufferedReader stream;

    @AfterEach
    void stop() throws Exception {
        if (this.stream != null) {
            this.stream.close();
        }
        if (this.api != null) {
            this.api.close();
        }
        if (this.server != null) {
            this.server.close();
        }
        this.fake.events.close();
    }

    @Test
    void shouldRefuseACallWithoutTheToken() throws Exception {
        givenTheApiRuns();

        whenCalled("GET", "/api/v1/status", Optional.empty(), "");

        thenTheStatusIs(401);
        thenTheAnswerAsksForABearerToken();
    }

    @Test
    void shouldRefuseAnAddressItsGuardStops() throws Exception {
        givenTheApiRuns();
        givenWrongTokensSent(ALLOWED_ATTEMPTS);

        whenCalled("GET", "/api/v1/status", Optional.of("wrong"), "");

        thenTheStatusIs(429);
    }

    @Test
    void shouldTellItsNameAndVersionToATokenHolder() throws Exception {
        givenTheApiRuns();

        whenCalled("GET", "/api/v1", "");

        thenTheStatusIs(200);
        thenTheBodyContains("\"name\":\"njMule\"", "\"api\":1");
    }

    @Test
    void shouldListTheDownloadsAsJson() throws Exception {
        givenTheApiRuns();
        givenTheEngineDownloads(download());

        whenCalled("GET", "/api/v1/downloads", "");

        thenTheStatusIs(200);
        thenTheBodyContains(
                "\"hash\":\"" + HASH + "\"",
                "\"name\":\"talk.bin\"",
                "\"status\":\"paused\"",
                "\"priority\":\"normal\"");
    }

    @Test
    void shouldAddTheDownloadsOfTheLinksSent() throws Exception {
        givenTheApiRuns();
        givenTheEngineAddsDownloads();

        whenCalled("POST", "/api/v1/downloads", "{\"links\":[\"" + LINK + "\"]}");

        thenTheStatusIs(201);
        thenTheEngineWasAsked("addDownload talk.bin");
    }

    @Test
    void shouldRefuseABodyThatIsNotALink() throws Exception {
        givenTheApiRuns();

        whenCalled("POST", "/api/v1/downloads", "{\"links\":[\"http://example.org/\"]}");

        thenTheStatusIs(400);
        thenTheBodyContains("Not an eD2K file link");
    }

    @Test
    void shouldRefuseMalformedJson() throws Exception {
        givenTheApiRuns();

        whenCalled("POST", "/api/v1/downloads", "{\"links\":");

        thenTheStatusIs(400);
        thenTheBodyContains("Malformed JSON");
    }

    @Test
    void shouldPauseAKnownDownload() throws Exception {
        givenTheApiRuns();
        givenTheEngineDownloads(download());
        givenTheEnginePausesOnly(HASH);

        whenCalled("POST", "/api/v1/downloads/" + HASH + "/pause", "");

        thenTheStatusIs(200);
    }

    @Test
    void shouldRenameADownload() throws Exception {
        givenTheApiRuns();
        givenTheEngineDownloads(download());
        givenTheEngineRenamesDownloads();

        whenCalled("PUT", "/api/v1/downloads/" + HASH + "/name", "{\"name\":\"Debian 13.iso\"}");

        thenTheStatusIs(200);
        thenTheEngineWasAsked("renameDownload Debian 13.iso");
    }

    @Test
    void shouldRefuseABlankNameForADownload() throws Exception {
        givenTheApiRuns();
        givenTheEngineDownloads(download());
        givenTheEngineRenamesDownloads();

        whenCalled("PUT", "/api/v1/downloads/" + HASH + "/name", "{\"name\":\" \"}");

        thenTheStatusIs(400);
    }

    @Test
    void shouldReportAnUnknownDownloadToPause() throws Exception {
        givenTheApiRuns();
        givenTheEngineDownloads(download());
        givenTheEnginePausesOnly(HASH);

        whenCalled("POST", "/api/v1/downloads/" + "CD".repeat(16) + "/pause", "");

        thenTheStatusIs(404);
    }

    @Test
    void shouldRefuseAMethodACallDoesNotTake() throws Exception {
        givenTheApiRuns();

        whenCalled("DELETE", "/api/v1/status", "");

        thenTheStatusIs(405);
    }

    @Test
    void shouldTellTheMethodsAPathTakes() throws Exception {
        givenTheApiRuns();

        whenCalled("DELETE", "/api/v1/limits", "");

        thenTheStatusIs(405);
        thenTheHeaderIs("Allow", "GET, PUT");
    }

    @Test
    void shouldServeItsOpenApiDocument() throws Exception {
        givenTheApiRuns();

        whenCalled("GET", "/api/v1/openapi.yaml", "");

        thenTheStatusIs(200);
        thenTheHeaderIs("Content-Type", "application/yaml; charset=utf-8");
        thenTheBodyContains("openapi: 3.2.0", "operationId: getStatus");
    }

    @Test
    void shouldRefuseAParameterTheDocumentDoesNotAllow() throws Exception {
        givenTheApiRuns();

        whenCalled("GET", "/api/v1/downloads/talk", "");

        thenTheStatusIs(400);
        thenTheBodyContains("Parameter hash does not follow the API");
    }

    @Test
    void shouldRefuseABodyTheDocumentDoesNotAllow() throws Exception {
        givenTheApiRuns();
        givenTheEngineKeepsPreferences();

        whenCalled("PUT", "/api/v1/limits", "{\"uploadKiBps\":\"fast\"}");

        thenTheStatusIs(400);
        thenTheBodyContains("Body does not follow the API");
        thenTheUploadLimitIs(Preferences.defaults().maxUploadKiBps());
    }

    @Test
    void shouldRefuseAMissingBody() throws Exception {
        givenTheApiRuns();

        whenCalled("POST", "/api/v1/searches", "");

        thenTheStatusIs(400);
        thenTheBodyContains("needs a JSON body");
    }

    @Test
    void shouldGiveTheResultsOfASearchItStarted() throws Exception {
        givenTheApiRuns();
        givenTheEngineSearchesAs(7);
        givenASearchStarted("{\"query\":\"talk\",\"scope\":\"kad\"}", "\"id\":7");
        givenTheEngineFinds(7, "talk.bin");

        whenTheResultsAreAskedUntilTheyCome("/api/v1/searches/7", "talk.bin");

        thenTheStatusIs(200);
        thenTheBodyContains("\"name\":\"talk.bin\"");
        thenTheEngineWasAsked("search talk KAD");
    }

    @Test
    void shouldSearchKadAutomaticallyFromASmallServerAmongMany() throws Exception {
        givenTheApiRuns();
        givenTheEngineSearchesAs(8);
        givenTheEngineConnected(true);
        givenTheEngineKnowsServers(40);

        whenCalled("POST", "/api/v1/searches", "{\"query\":\"talk\",\"scope\":\"automatic\"}");

        thenTheStatusIs(201);
        thenTheEngineWasAsked("search talk KAD");
    }

    @Test
    void shouldStayOnABigServerAutomatically() throws Exception {
        givenTheApiRuns();
        givenTheEngineSearchesAs(8);
        givenTheEngineConnected(true);
        givenTheEngineKnowsServers(1);

        whenCalled("POST", "/api/v1/searches", "{\"query\":\"talk\",\"scope\":\"automatic\"}");

        thenTheStatusIs(201);
        thenTheEngineWasAsked("search talk SERVER");
    }

    @Test
    void shouldRefuseAnAutomaticSearchWithNoNetwork() throws Exception {
        givenTheApiRuns();
        givenTheEngineSearchesAs(8);
        givenTheEngineConnected(false);
        givenTheEngineKnowsServers(1);

        whenCalled("POST", "/api/v1/searches", "{\"query\":\"talk\",\"scope\":\"automatic\"}");

        thenTheStatusIs(409);
        thenTheBodyContains("Not connected");
    }

    @Test
    void shouldShareAFolderWithItsSubfolders() throws Exception {
        givenTheApiRuns();
        givenTheEngineShares();
        givenTheTemporaryFolderHoldsNothingPersonal();

        whenCalled("POST", "/api/v1/shared/directories", folderBody(this.dir, false));

        thenTheStatusIs(200);
        thenTheEngineWasAsked("share " + this.dir + " true");
    }

    @Test
    void shouldAskFirstBeforeSharingAWholeDrive() throws Exception {
        givenTheApiRuns();
        givenTheEngineShares();

        whenCalled("POST", "/api/v1/shared/directories", folderBody(this.dir.getRoot(), false));

        thenTheStatusIs(409);
        thenTheEngineWasNotAsked();
    }

    @Test
    void shouldShareAWholeDriveWhenToldEverything() throws Exception {
        givenTheApiRuns();
        givenTheEngineShares();

        whenCalled("POST", "/api/v1/shared/directories", folderBody(this.dir.getRoot(), true));

        thenTheStatusIs(200);
        thenTheEngineWasAsked("share " + this.dir.getRoot() + " true");
    }

    @Test
    void shouldRefuseToShareWhatIsNoFolder() throws Exception {
        givenTheApiRuns();
        givenTheEngineShares();

        whenCalled("POST", "/api/v1/shared/directories", folderBody(this.dir.resolve("missing"), false));

        thenTheStatusIs(400);
        thenTheEngineWasNotAsked();
    }

    @Test
    void shouldStopSharingASharedFolder() throws Exception {
        givenTheApiRuns();
        givenTheEngineShares(this.dir);

        whenCalled("DELETE", "/api/v1/shared/directories?path=" + encoded(this.dir.toString()), "");

        thenTheStatusIs(200);
        thenTheEngineWasAsked("unshare " + this.dir);
    }

    @Test
    void shouldNotFindAFolderThatIsNotShared() throws Exception {
        givenTheApiRuns();
        givenTheEngineShares();

        whenCalled("DELETE", "/api/v1/shared/directories?path=" + encoded(this.dir.toString()), "");

        thenTheStatusIs(404);
    }

    @Test
    void shouldKeepTheMessagesSentInTheirConversation() throws Exception {
        givenTheApiRuns();
        givenTheEngineSendsMessages();
        givenAMessageSent("203.0.113.5:4662", "hello");

        whenCalled("GET", "/api/v1/conversations", "");

        thenTheStatusIs(200);
        thenTheBodyContains("\"address\":\"203.0.113.5:4662\"", "\"author\":\"us\"", "\"text\":\"hello\"");
    }

    @Test
    void shouldForgetAConversationThatIsClosed() throws Exception {
        givenTheApiRuns();
        givenTheEngineSendsMessages();
        givenAMessageSent("203.0.113.5:4662", "hello");

        whenCalled("DELETE", "/api/v1/conversations/203.0.113.5:4662", "");

        thenTheStatusIs(200);
        thenTheConversationsAre("[]");
    }

    @Test
    void shouldGiveTheFriendSlotToAFriend() throws Exception {
        givenTheApiRuns();
        givenTheEngineKeepsTheFriendSlot();

        whenCalled("PUT", "/api/v1/friends/slot", "{\"userHash\":\"" + "A1".repeat(16) + "\"}");

        thenTheStatusIs(200);
        thenTheBodyContains("\"userHash\":\"" + "A1".repeat(16) + "\"");
    }

    @Test
    void shouldTakeTheFriendSlotBackWithNull() throws Exception {
        givenTheApiRuns();
        givenTheEngineKeepsTheFriendSlot();

        whenCalled("PUT", "/api/v1/friends/slot", "{\"userHash\":null}");

        thenTheStatusIs(200);
        thenTheBodyContains("\"userHash\":null");
    }

    @Test
    void shouldGiveThePreferencesByTheirKeysButNotTheProxyPassword() throws Exception {
        givenTheApiRuns();
        givenTheEngineKeepsPreferences();

        whenCalled("GET", "/api/v1/preferences", "");

        thenTheStatusIs(200);
        thenTheBodyContains("\"user.nick\":\"njMule\"", "\"net.tcpPort\":\"4662\"");
        thenTheBodyDoesNotContain("proxy.password");
    }

    @Test
    void shouldChangeOnlyThePreferencesNamed() throws Exception {
        givenTheApiRuns();
        givenTheEngineKeepsPreferences();

        whenCalled("PATCH", "/api/v1/preferences", "{\"user.nick\":\"Night Owl\"}");

        thenTheStatusIs(200);
        thenTheBodyContains("\"user.nick\":\"Night Owl\"", "\"net.tcpPort\":\"4662\"");
    }

    @Test
    void shouldRefuseAWrongPreferenceNamingIt() throws Exception {
        givenTheApiRuns();
        givenTheEngineKeepsPreferences();

        whenCalled("PATCH", "/api/v1/preferences", "{\"user.nick\":\"ok\",\"net.tcpPort\":\"99999\"}");

        thenTheStatusIs(400);
        thenTheBodyContains("net.tcpPort");
        thenTheNickIsStill("njMule");
    }

    @Test
    void shouldRefuseAnUnknownPreference() throws Exception {
        givenTheApiRuns();
        givenTheEngineKeepsPreferences();

        whenCalled("PATCH", "/api/v1/preferences", "{\"no.such.key\":\"1\"}");

        thenTheStatusIs(400);
        thenTheBodyContains("Unknown preference: no.such.key");
    }

    @Test
    void shouldSetThePriorityOfAServer() throws Exception {
        givenTheApiRuns();
        givenTheEngineKnowsTheServer("203.0.113.10:4661");

        whenCalled("PUT", "/api/v1/servers/203.0.113.10:4661/priority", "{\"priority\":\"high\"}");

        thenTheStatusIs(200);
        thenTheEngineWasAsked("priority 203.0.113.10:4661 HIGH");
    }

    @Test
    void shouldPutAServerInTheStaticList() throws Exception {
        givenTheApiRuns();
        givenTheEngineKnowsTheServer("203.0.113.10:4661");

        whenCalled("PUT", "/api/v1/servers/203.0.113.10:4661/static", "{\"static\":true}");

        thenTheStatusIs(200);
        thenTheEngineWasAsked("static 203.0.113.10:4661 true");
    }

    @Test
    void shouldNotFindTheStaticFlagOfAnUnknownServer() throws Exception {
        givenTheApiRuns();
        givenTheEngineKnowsTheServer("203.0.113.10:4661");

        whenCalled("PUT", "/api/v1/servers/198.51.100.1:4661/static", "{\"static\":true}");

        thenTheStatusIs(404);
    }

    @Test
    void shouldStartATestOfThePorts() throws Exception {
        givenTheApiRuns();
        givenTheEngineTestsThePorts();

        whenCalled("POST", "/api/v1/ports/test", "");

        thenTheStatusIs(202);
        thenTheBodyContains("\"forwarding\":\"asking\"", "\"tcp\":\"testing\"", "\"done\":false");
    }

    @Test
    void shouldSetTheSpeedLimits() throws Exception {
        givenTheApiRuns();
        givenTheEngineKeepsPreferences();

        whenCalled("PUT", "/api/v1/limits", "{\"uploadKiBps\":50,\"downloadKiBps\":0}");

        thenTheStatusIs(200);
        thenTheBodyContains("\"uploadKiBps\":50", "\"downloadKiBps\":0");
        thenTheUploadLimitIs(50);
    }

    @Test
    void shouldStreamTheEngineEvents() throws Exception {
        givenTheApiRuns();
        givenAnEventStreamOpen("download");

        whenTheEngineAnnounces(new EngineEvent.DownloadAdded(download()));

        thenTheStreamCarries("event: download.added", "\"name\":\"talk.bin\"");
    }

    @Test
    void shouldEndTheStreamsWhenItCloses() throws Exception {
        givenTheApiRuns();
        givenAnEventStreamOpen("");

        whenTheApiCloses();

        thenTheStreamEnds();
    }

    @Test
    void shouldStopNjMuleWhenAsked() throws Exception {
        givenTheApiRuns();

        whenCalled("POST", "/api/v1/shutdown", "");

        thenTheStatusIs(202);
        thenNjMuleWasStopped();
    }

    // Given

    private void givenTheApiRuns() throws Exception {
        AppDirectories dirs = AppDirectories.underRoot(this.dir, true);
        this.fake.value("directories", dirs);
        this.token = ApiToken.loadOrCreate(dirs.config(), new SecureRandom());
        NjMuleEngine engine = this.fake.engine();
        this.api = new RemoteApi(
                engine,
                RecentEvents.follow(engine),
                client -> this.attempts.incrementAndGet() <= ALLOWED_ATTEMPTS,
                Optional.of(this.exited::countDown),
                Duration.ofSeconds(1));
        this.server = ApiServer.start(this.api);
    }

    private void givenWrongTokensSent(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            whenCalled("GET", "/api/v1/status", Optional.of("wrong"), "");
            thenTheStatusIs(401);
        }
    }

    private void givenTheEngineDownloads(DownloadInfo download) {
        this.fake.answer("downloads", args -> CompletableFuture.completedFuture(List.of(download)));
    }

    private void givenTheEngineAddsDownloads() {
        this.fake.answer("addDownload", args -> {
            Ed2kLink.FileLink link = (Ed2kLink.FileLink) args[0];
            this.calls.add("addDownload " + link.name());
            return CompletableFuture.completedFuture(download());
        });
    }

    // As the engine does: a blank name is refused.
    private void givenTheEngineRenamesDownloads() {
        this.fake.answer("renameDownload", args -> {
            String name = (String) args[1];
            if (name.isBlank()) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("a download needs a name"));
            }
            this.calls.add("renameDownload " + name);
            return CompletableFuture.completedFuture(true);
        });
    }

    private void givenTheEnginePausesOnly(String hash) {
        this.fake.answer(
                "pauseDownload", args -> CompletableFuture.completedFuture(args[0].equals(Hash16.parseHex(hash))));
    }

    private void givenTheEngineConnected(boolean connected) {
        this.fake.answer("status", args -> CompletableFuture.completedFuture(ApiSamples.status(connected)));
    }

    private void givenTheEngineKnowsServers(int count) {
        this.fake.answer(
                "servers", args -> CompletableFuture.completedFuture(Collections.nCopies(count, ApiSamples.server())));
    }

    private void givenTheEngineShares(Path... shared) {
        this.fake.answer(
                "sharedDirectories",
                args -> CompletableFuture.completedFuture(Arrays.stream(shared)
                        .map(p -> new SharedDirectory(p, true))
                        .toList()));
        this.fake.answer("shareDirectory", args -> {
            this.calls.add("share " + args[0] + " " + args[1]);
            return CompletableFuture.completedFuture(null);
        });
        this.fake.answer("unshareDirectory", args -> {
            this.calls.add("unshare " + args[0]);
            return CompletableFuture.completedFuture(null);
        });
    }

    // Where the system keeps temporary folders inside the user's own, such a folder is shared only on request.
    private void givenTheTemporaryFolderHoldsNothingPersonal() {
        assumeFalse(SharedDirectory.isSensitive(this.dir), "the temporary folder lies in a personal folder");
    }

    private static String folderBody(Path folder, boolean everything) {
        return "{\"path\":\"" + folder.toString().replace("\\", "\\\\") + "\",\"everything\":" + everything + "}";
    }

    private static String encoded(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    private void thenTheEngineWasNotAsked() {
        assertThat(this.calls).isEmpty();
    }

    private void givenTheEngineSendsMessages() {
        this.fake.answer("sendMessage", args -> CompletableFuture.completedFuture(null));
    }

    private void givenAMessageSent(String to, String text) throws Exception {
        whenCalled("POST", "/api/v1/messages", "{\"to\":\"" + to + "\",\"text\":\"" + text + "\"}");
        thenTheStatusIs(200);
    }

    private void givenTheEngineKeepsTheFriendSlot() {
        AtomicReference<Optional<Hash16>> slot = new AtomicReference<>(Optional.empty());
        this.fake.answer("friendSlot", args -> CompletableFuture.completedFuture(slot.get()));
        this.fake.answer("setFriendSlot", args -> {
            @SuppressWarnings("unchecked")
            Optional<Hash16> friend = (Optional<Hash16>) args[0];
            slot.set(friend);
            return CompletableFuture.completedFuture(true);
        });
    }

    private void thenTheConversationsAre(String body) throws Exception {
        whenCalled("GET", "/api/v1/conversations", "");
        assertThat(this.answer.body()).isEqualTo(body);
    }

    private void thenTheNickIsStill(String nick) throws Exception {
        whenCalled("GET", "/api/v1/preferences", "");
        thenTheBodyContains("\"user.nick\":\"" + nick + "\"");
    }

    private void givenTheEngineKnowsTheServer(String known) {
        this.fake.answer("setServerPriority", args -> {
            this.calls.add("priority " + args[0] + " " + args[1]);
            return CompletableFuture.completedFuture(args[0].toString().equals(known));
        });
        this.fake.answer("setStaticServer", args -> {
            this.calls.add("static " + args[0] + " " + args[1]);
            return CompletableFuture.completedFuture(args[0].toString().equals(known));
        });
    }

    private void givenTheEngineTestsThePorts() {
        this.fake.answer(
                "testPorts",
                args -> CompletableFuture.completedFuture(new PortTest(
                        4662,
                        4672,
                        PortTest.Forwarding.ASKING,
                        "",
                        PortTest.Reach.TESTING,
                        PortTest.Reach.TESTING,
                        false)));
    }

    private void givenTheEngineSearchesAs(long id) {
        this.fake.answer("search", args -> {
            SearchRequest request = (SearchRequest) args[0];
            this.calls.add("search " + request.expression() + " " + request.scope());
            return CompletableFuture.completedFuture(new SearchId(id));
        });
    }

    private void givenASearchStarted(String body, String answered) throws Exception {
        whenCalled("POST", "/api/v1/searches", body);
        thenTheStatusIs(201);
        thenTheBodyContains(answered);
    }

    private void givenTheEngineFinds(long search, String name) {
        this.fake.events.submit(new EngineEvent.SearchResults(
                new SearchId(search),
                List.of(new SearchResult(Hash16.parseHex(HASH), name, 5000, 3, 1, Optional.empty()))));
    }

    @SuppressWarnings("unchecked")
    private void givenTheEngineKeepsPreferences() {
        this.fake.value("preferences", this.preferences);
        this.fake.answer("updatePreferences", args -> {
            this.preferences = ((UnaryOperator<Preferences>) args[0]).apply(this.preferences);
            this.fake.value("preferences", this.preferences);
            return CompletableFuture.completedFuture(this.preferences);
        });
    }

    private void givenAnEventStreamOpen(String types) throws Exception {
        HttpResponse<InputStream> opened = this.http.send(
                request("/api/v1/events?types=" + types, Optional.of(this.token))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(opened.statusCode()).isEqualTo(200);
        assertThat(opened.headers().firstValue("Content-Type")).hasValue("text/event-stream; charset=utf-8");
        this.stream = new BufferedReader(new InputStreamReader(opened.body(), StandardCharsets.UTF_8));
        assertThat(this.stream.readLine()).startsWith(":");
        // Subscribed once the stream is open.
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (this.fake.events.getNumberOfSubscribers() < 2 && System.nanoTime() < end) {
            Thread.sleep(10);
        }
    }

    // When

    private void whenCalled(String method, String path, String body) throws Exception {
        whenCalled(method, path, Optional.of(this.token), body);
    }

    private void whenCalled(String method, String path, Optional<String> token, String body) throws Exception {
        HttpRequest.BodyPublisher content =
                body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        this.answer = this.http.send(
                request(path, token).method(method, content).build(), HttpResponse.BodyHandlers.ofString());
    }

    // Results come through the engine's events, delivered on the publisher's threads.
    private void whenTheResultsAreAskedUntilTheyCome(String path, String name) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            whenCalled("GET", path, "");
        } while (!this.answer.body().contains(name) && System.nanoTime() < end);
    }

    private void whenTheEngineAnnounces(EngineEvent event) {
        this.fake.events.submit(event);
    }

    private void whenTheApiCloses() {
        this.api.close();
    }

    // Then

    private void thenTheStatusIs(int status) {
        assertThat(this.answer.statusCode()).as(this.answer.body()).isEqualTo(status);
    }

    private void thenTheHeaderIs(String name, String value) {
        assertThat(this.answer.headers().firstValue(name)).hasValue(value);
    }

    private void thenTheAnswerAsksForABearerToken() {
        assertThat(this.answer.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");
        assertThat(this.answer.headers().firstValue("Content-Type")).hasValue("application/json; charset=utf-8");
    }

    private void thenTheBodyDoesNotContain(String text) {
        assertThat(this.answer.body()).doesNotContain(text);
    }

    private void thenTheBodyContains(String... parts) {
        assertThat(this.answer.body()).contains(parts);
    }

    private void thenTheEngineWasAsked(String call) {
        assertThat(this.calls).contains(call);
    }

    private void thenTheStreamCarries(String... parts) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String line = this.stream.readLine(); line != null; line = this.stream.readLine()) {
            if (line.startsWith(":") || line.isEmpty()) {
                continue;
            }
            lines.add(line);
            if (line.startsWith("data:")) {
                break;
            }
        }
        assertThat(String.join("\n", lines)).contains(parts);
    }

    private void thenTheStreamEnds() throws IOException {
        String line = this.stream.readLine();
        while (line != null) {
            line = this.stream.readLine();
        }
        assertThat(line).isNull();
    }

    private void thenTheUploadLimitIs(int kibps) {
        assertThat(this.preferences.maxUploadKiBps()).isEqualTo(kibps);
    }

    private void thenNjMuleWasStopped() throws InterruptedException {
        assertThat(this.exited.await(10, TimeUnit.SECONDS)).isTrue();
    }

    private DownloadInfo download() {
        return new DownloadInfo(
                Hash16.parseHex(HASH),
                "talk.bin",
                5000,
                0,
                DownloadInfo.Status.PAUSED,
                DownloadPriority.NORMAL,
                0,
                0,
                0,
                0,
                PartMap.EMPTY);
    }

    private HttpRequest.Builder request(String path, Optional<String> token) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.server.port() + path));
        token.ifPresent(t -> builder.header("Authorization", "Bearer " + t));
        return builder;
    }
}
