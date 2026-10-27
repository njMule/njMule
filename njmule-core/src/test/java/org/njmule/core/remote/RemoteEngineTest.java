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
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.SearchId;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.config.Preferences;
import org.njmule.core.config.Schedule;
import org.njmule.core.persist.PreferencesCodec;

class RemoteEngineTest {

    private static final Hash16 HASH = Hash16.parseHex(ApiSamples.HASH);

    @TempDir
    Path dir;

    private final FakeEngine fake = new FakeEngine();
    private final List<String> asked = new CopyOnWriteArrayList<>();
    private final BlockingQueue<EngineEvent> received = new LinkedBlockingQueue<>();
    private ApiServer server;
    private String token;
    private RemoteEngine remote;
    private Object answer;
    private Throwable exception;
    private CompletableFuture<Void> closed;

    @AfterEach
    void stop() throws IOException {
        if (this.remote != null) {
            this.remote.close();
        }
        if (this.server != null) {
            this.server.close();
        }
    }

    @Test
    void shouldShowTheDownloadsOfTheOtherNjmuleWithTheirParts() throws Exception {
        givenTheOtherNjmuleRuns();
        givenItDownloads(ApiSamples.download());
        givenConnected();

        whenAsked(this.remote.downloads());

        thenTheAnswerIs(List.of(ApiSamples.download()));
    }

    @Test
    void shouldPauseADownloadOfTheOtherNjmule() throws Exception {
        givenTheOtherNjmuleRuns();
        givenItDownloads(ApiSamples.download());
        givenItKnowsTheDownload(true);
        givenConnected();

        whenAsked(this.remote.pauseDownload(HASH));

        thenTheAnswerIs(true);
        thenTheOtherNjmuleWasAsked("pauseDownload " + HASH);
    }

    @Test
    void shouldTellThatTheOtherNjmuleDoesNotKnowADownload() throws Exception {
        givenTheOtherNjmuleRuns();
        givenItKnowsTheDownload(false);
        givenConnected();

        whenAsked(this.remote.pauseDownload(HASH));

        thenTheAnswerIs(false);
    }

    @Test
    void shouldChangeThePreferencesOfTheOtherNjmule() throws Exception {
        givenTheOtherNjmuleRuns();
        givenConnected();

        whenAsked(this.remote.updatePreferences(p -> PreferencesCodec.apply(p, Map.of("user.nick", "Night Owl"))));

        thenTheNickIs("Night Owl");
    }

    @Test
    void shouldPassOnTheEventsOfTheOtherNjmule() throws Exception {
        givenTheOtherNjmuleRuns();
        givenItDownloads(ApiSamples.download());
        givenConnected();
        givenTheDownloadsWereRead();
        givenTheEventsAreFollowed();

        whenTheOtherNjmuleTells(new EngineEvent.DownloadProgress(ApiSamples.download()));

        thenTheEventCame(new EngineEvent.DownloadProgress(ApiSamples.download()));
    }

    @Test
    void shouldRefuseAWrongToken() throws Exception {
        givenTheOtherNjmuleRuns();

        whenConnectedWith("wrong");

        thenExceptionOccurred(IOException.class, "401");
    }

    @Test
    void shouldSayWhatOnlyTheOwnComputerCanDo() throws Exception {
        givenTheOtherNjmuleRuns();
        givenConnected();

        whenAsked(this.remote.addServers(this.dir.resolve("server.met")));

        thenExceptionOccurred(UnsupportedOperationException.class, "a server.met of this computer");
    }

    @Test
    void shouldReadTheSearchesTheOtherNjmuleKept() throws Exception {
        givenTheOtherNjmuleRuns();
        givenItAnswers("savedSearches", List.of(ApiSamples.savedSearch()));
        givenConnected();

        whenAsked(this.remote.savedSearches());

        thenTheAnswerIs(List.of(ApiSamples.savedSearch()));
    }

    @Test
    void shouldKeepTheSearchesOnTheOtherNjmule() throws Exception {
        givenTheOtherNjmuleRuns();
        givenItKeepsTheSearches();
        givenConnected();

        whenAsked(this.remote.saveSearches(List.of(ApiSamples.savedSearch())));

        thenTheOtherNjmuleWasAsked("saveSearches " + List.of(ApiSamples.savedSearch()));
    }

    @Test
    void shouldReadTheSpamMarksOfTheOtherNjmule() throws Exception {
        givenTheOtherNjmuleRuns();
        givenItAnswers("spamMarks", ApiSamples.spamMarks());
        givenConnected();

        whenAsked(this.remote.spamMarks());

        thenTheAnswerIs(ApiSamples.spamMarks());
    }

    @Test
    void shouldMarkAResultAsSpamThroughItsSearchOnTheOtherNjmule() throws Exception {
        givenTheOtherNjmuleRuns();
        givenItMarksSpam();
        givenItAnswers("spamMarks", ApiSamples.spamMarks());
        givenItSearches(new SearchId(7));
        givenConnected();
        givenTheEventsAreFollowed();
        givenASearchStartedThroughTheRemote();
        givenTheOtherNjmuleFound(new SearchId(7), ApiSamples.result());

        whenAsked(this.remote.markSpam(List.of(ApiSamples.result()), true));

        thenTheAnswerIs(ApiSamples.spamMarks());
        thenTheOtherNjmuleWasAsked("markSpam " + List.of(ApiSamples.result().hash()) + " true");
    }

    @Test
    void shouldSayHelloToAClientThroughTheOtherNjmule() throws Exception {
        givenTheOtherNjmuleRuns();
        givenItAnswers("hello", ApiSamples.peer());
        givenConnected();

        whenAsked(this.remote.hello(ServerAddress.parse("198.51.100.9:4662")));

        thenTheAnswerIs(ApiSamples.peer());
    }

    private void givenTheOtherNjmuleRuns() throws Exception {
        AppDirectories dirs = AppDirectories.underRoot(this.dir.resolve("other"), true);
        this.fake.value("directories", dirs);
        this.fake.value("preferences", Preferences.defaults());
        this.fake.value("schedule", Schedule.EMPTY);
        this.fake.value("activeRule", Optional.empty());
        this.fake.value("updateStatus", ApiSamples.updateStatus());
        this.fake.answer("updatePreferences", args -> {
            @SuppressWarnings("unchecked")
            UnaryOperator<Preferences> change = (UnaryOperator<Preferences>) args[0];
            Preferences changed = change.apply(Preferences.defaults());
            this.fake.value("preferences", changed);
            return CompletableFuture.completedFuture(changed);
        });
        this.token = ApiToken.loadOrCreate(dirs.config(), new SecureRandom());
        NjMuleEngine engine = this.fake.engine();
        RemoteApi api = new RemoteApi(
                engine, RecentEvents.follow(engine), client -> true, Optional.empty(), Duration.ofSeconds(1));
        this.server = ApiServer.start(api);
    }

    private void givenItAnswers(String method, Object answer) {
        this.fake.answer(method, args -> CompletableFuture.completedFuture(answer));
    }

    private void givenItKeepsTheSearches() {
        this.fake.answer("saveSearches", args -> {
            this.asked.add("saveSearches " + args[0]);
            return CompletableFuture.completedFuture(null);
        });
    }

    private void givenItMarksSpam() {
        this.fake.answer("markSpam", args -> {
            @SuppressWarnings("unchecked")
            List<SearchResult> results = (List<SearchResult>) args[0];
            this.asked.add(
                    "markSpam " + results.stream().map(SearchResult::hash).toList() + " " + args[1]);
            return CompletableFuture.completedFuture(ApiSamples.spamMarks());
        });
    }

    private void givenItSearches(SearchId id) {
        this.fake.answer("search", args -> CompletableFuture.completedFuture(id));
    }

    private void givenASearchStartedThroughTheRemote() {
        this.remote
                .search(new SearchRequest("talk", SearchRequest.Scope.GLOBAL))
                .join();
    }

    // The result reaches both ends: the API keeps it under its search, and the remote engine learns the search.
    private void givenTheOtherNjmuleFound(SearchId search, SearchResult result) throws InterruptedException {
        EngineEvent found = new EngineEvent.SearchResults(search, List.of(result));
        whenTheOtherNjmuleTells(found);
        thenTheEventCame(found);
    }

    private void givenItDownloads(DownloadInfo download) {
        this.fake.answer("downloads", args -> CompletableFuture.completedFuture(List.of(download)));
    }

    private void givenItKnowsTheDownload(boolean known) {
        this.fake.answer("pauseDownload", args -> {
            this.asked.add("pauseDownload " + args[0]);
            return CompletableFuture.completedFuture(known);
        });
    }

    @Test
    void shouldCloseAtOnceWhileItFollowsTheEvents() throws Exception {
        givenTheOtherNjmuleRuns();
        givenItDownloads(ApiSamples.download());
        givenConnected();
        givenTheDownloadsWereRead();
        givenTheEventsAreFollowed();
        givenTheEventStreamIsOpen();

        whenClosed();

        thenItClosedWithin(Duration.ofSeconds(10));
    }

    // An event has come through the stream, which stays open.
    private void givenTheEventStreamIsOpen() throws InterruptedException {
        EngineEvent event = new EngineEvent.DownloadProgress(ApiSamples.download());
        for (int i = 0; i < 100 && this.received.isEmpty(); i++) {
            this.fake.events.submit(event);
            Thread.sleep(100);
        }
        assertThat(this.received.poll(10, TimeUnit.SECONDS)).isEqualTo(event);
    }

    private void whenClosed() {
        RemoteEngine closing = this.remote;
        this.closed = CompletableFuture.runAsync(closing::close);
        this.remote = null;
    }

    private void thenItClosedWithin(Duration time) {
        assertThat(this.closed).succeedsWithin(time);
    }

    private void givenConnected() throws IOException {
        this.remote = RemoteEngine.connect(api(), this.token, AppDirectories.underRoot(this.dir.resolve("here"), true));
    }

    private void givenTheDownloadsWereRead() {
        this.remote.downloads().join();
    }

    private void givenTheEventsAreFollowed() {
        this.remote.events().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(EngineEvent item) {
                RemoteEngineTest.this.received.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
                // The test waits for the event and fails without it.
            }

            @Override
            public void onComplete() {
                // Nothing more comes.
            }
        });
    }

    private void whenAsked(CompletableFuture<?> work) {
        this.exception = catchThrowable(() -> this.answer = work.join());
        if (this.exception != null && this.exception.getCause() != null) {
            this.exception = this.exception.getCause();
        }
    }

    private void whenConnectedWith(String wrong) {
        this.exception = catchThrowable(
                () -> RemoteEngine.connect(api(), wrong, AppDirectories.underRoot(this.dir.resolve("here"), true)));
    }

    // The stream of the other njMule opens a moment after the connection: the event is told until it comes.
    private void whenTheOtherNjmuleTells(EngineEvent event) throws InterruptedException {
        for (int i = 0; i < 100 && this.received.isEmpty(); i++) {
            this.fake.events.submit(event);
            Thread.sleep(100);
        }
    }

    private void thenTheAnswerIs(Object expected) {
        assertThat(this.exception).isNull();
        assertThat(this.answer).isEqualTo(expected);
    }

    private void thenTheOtherNjmuleWasAsked(String call) {
        assertThat(this.asked).containsExactly(call);
    }

    private void thenTheNickIs(String nick) {
        assertThat(this.exception).isNull();
        assertThat(PreferencesCodec.values(this.remote.preferences())).containsEntry("user.nick", nick);
    }

    private void thenTheEventCame(EngineEvent expected) throws InterruptedException {
        assertThat(this.received.poll(10, TimeUnit.SECONDS)).isEqualTo(expected);
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type, String part) {
        assertThat(this.exception).isInstanceOf(type).hasMessageContaining(part);
    }

    private URI api() {
        return URI.create("http://127.0.0.1:" + this.server.port() + "/api/v1");
    }
}
