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
package org.njmule.gui.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.njmule.core.api.DownloadPriority.AUTO;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import javafx.collections.ListChangeListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Category;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.ImportOptions;
import org.njmule.core.api.ImportReport;
import org.njmule.core.api.LogLine;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.PartMap;
import org.njmule.core.api.SearchId;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.api.ServerInfo;
import org.njmule.gui.Engines;
import org.njmule.gui.FxTesting;
import org.njmule.gui.Statuses;

@Timeout(60)
class EngineModelTest {

    private static final Hash16 FILE = Hash16.parseHex("0123456789ABCDEF0123456789ABCDEF");
    private static final EngineStatus NOTHING = Statuses.of(Optional.empty(), 0, false, Optional.empty(), false);
    private static final EngineStatus KAD_ONLY =
            Statuses.of(Optional.empty(), 0, false, Optional.of(Statuses.kad(true, false, "")), false);
    private static final ServerInfo BIG = new ServerInfo(
            "81.2.3.4:4661", "Big", "", 10, 20, 30, 0, ServerInfo.Priority.NORMAL, 0, 0, 0, 0, "", false, false);
    private static final ServerInfo SUNRISE = new ServerInfo(
            "176.123.5.89:4725",
            "eMule Sunrise",
            "",
            0,
            0,
            0,
            0,
            ServerInfo.Priority.NORMAL,
            0,
            0,
            0,
            0,
            "",
            true,
            false);
    private static final DownloadInfo DOWNLOAD =
            new DownloadInfo(FILE, "a.bin", 100, 0, DownloadInfo.Status.DOWNLOADING, AUTO, 0, 0, 0, 0, PartMap.EMPTY);
    private static final DownloadInfo DONE =
            new DownloadInfo(FILE, "a.bin", 100, 100, DownloadInfo.Status.COMPLETE, AUTO, 0, 0, 0, 0, PartMap.EMPTY);

    @TempDir
    Path dir;

    private NjMuleEngine engine;
    private EngineModel model;
    private EngineModel.Search opened;
    private final List<String> errors = new CopyOnWriteArrayList<>();
    private final List<CompletableFuture<EngineStatus>> statusPolls = new CopyOnWriteArrayList<>();
    private final CompletableFuture<Void> connecting = new CompletableFuture<>();
    private final List<String> added = new CopyOnWriteArrayList<>();
    private final List<String> finished = new CopyOnWriteArrayList<>();
    private final List<String> categoriesAdded = new CopyOnWriteArrayList<>();
    private final AtomicReference<EngineModel.Search> started = new AtomicReference<>();
    private final AtomicReference<ImportReport> report = new AtomicReference<>();
    private final AtomicReference<String> nick = new AtomicReference<>();
    private int logged;
    private Path folder;

    @AfterEach
    void close() {
        if (this.model != null) {
            this.model.close();
        }
        if (this.engine != null) {
            this.engine.close();
        }
    }

    // A direct executor: the model's state changes on the engine's threads, which tests poll.
    private EngineModel start() throws Exception {
        return start(UnaryOperator.identity());
    }

    // A model that hears no event of the engine: on this executor they would run beside the test's own.
    private EngineModel notListening() throws Exception {
        this.engine = FxTesting.engine(this.dir.resolve("njmule"));
        this.model = new EngineModel(this.engine, Runnable::run);
        this.model.onError(this.errors::add);
        return this.model;
    }

    // The model sees the engine through wrap.
    private EngineModel start(UnaryOperator<NjMuleEngine> wrap) throws Exception {
        this.engine = FxTesting.engine(this.dir.resolve("njmule"));
        this.model = new EngineModel(wrap.apply(this.engine), Runnable::run);
        this.model.onError(this.errors::add);
        this.model.start();
        return this.model;
    }

    @Test
    void shouldKeepTheServerWeHadWhenAConnectionCannotStart() throws Exception {
        givenAModelWhoseEngineCannotConnectTo("nowhere.invalid");
        givenTheModelHears(new EngineEvent.ServerConnected(BIG, 0x12345678L, false, true));

        whenTheModelConnectsTo("nowhere.invalid", 4661);

        thenTheErrorsAre("nowhere.invalid");
        thenTheModelIsStillConnected();
        thenTheServerNameIs("Big");
    }

    @Test
    void shouldLeaveAConnectionBeingMadeOnAnUnrelatedFailure() throws Exception {
        givenAModelWhoseConnectionIsPendingAndWhoseKadCannotStart();
        givenTheModelConnects();

        whenKadIsBootstrappedFrom("127.0.0.1:4672");

        thenAnErrorIsTold();
        thenTheConnectionIs(EngineModel.Connection.CONNECTING);
    }

    @Test
    void shouldDisconnectOnceTheConnectionBeingMadeFails() throws Exception {
        givenAModelWhoseConnectionIsPendingAndWhoseKadCannotStart();
        givenTheModelConnects();
        givenKadFailedToBootstrapFrom("127.0.0.1:4672");

        whenTheConnectionFails("no known server");

        thenTheConnectionIs(EngineModel.Connection.DISCONNECTED);
    }

    @Test
    void shouldShowTheNameOfAnErrorWithoutAMessage() throws Exception {
        givenAModel();

        whenAnErrorWithoutAMessageIsTold();

        thenTheErrorsAre("IllegalStateException");
    }

    @Test
    void shouldTellADownloadAddedOnlyOnceTheEngineTookIt() throws Exception {
        givenAModelWhoseEngineCannotAddDownloads("disk full");

        whenTheDownloadIsAdded("a.bin", 5000);

        thenTheErrorsAre("disk full");
        thenNoDownloadWasToldAdded();
        thenTheModelHasNoDownload();
    }

    @Test
    void shouldPollTheStatusAndKeepTheRates() throws Exception {
        givenAModel();
        givenTheModelKeptRates(1);

        whenTheModelPollsAgain();

        thenTheModelKeepsRates(2);
        thenTheStatusHasNoServer();
        thenTheConnectionIs(EngineModel.Connection.DISCONNECTED);
        thenTheModelWorksOnTheEngine();
        thenThePreferencesAreThoseOfTheEngine();
    }

    @Test
    void shouldKeepTheMeanRatesOfEachMinuteOfPolls() throws Exception {
        givenAModel();
        givenTheModelKeptRates(1);

        whenTheModelPollsUntilItKeptRates(60);

        thenTheModelKeepsMinutes(1);
    }

    @Test
    void shouldAddPauseResumeAndCancelADownload() throws Exception {
        givenAModel();
        givenTheDownloadAdded("a.bin", 5000);
        givenTheDownloadIsListedAndToldAdded("a.bin");
        givenTheDownloadPaused();
        givenTheDownloadResumed();

        whenTheDownloadIsCancelled();

        thenTheModelSoonHasNoDownload();
        thenTheEngineHasNoDownload();
    }

    @Test
    void shouldSendFailuresToTheErrorSink() throws Exception {
        givenAModel();
        givenAServerSearchFor("ubuntu");

        whenKadIsBootstrappedFrom("127.0.0.1:4672");

        thenThereAreAtLeastErrors(2);
        thenTheSearchDidNotStart();
        thenAnErrorContains("Kad is not running");
    }

    @Test
    void shouldFollowTheServerConnectionThroughItsEvents() throws Exception {
        givenAModelThatHearsNoEventOfTheEngine();
        givenTheModelHears(new EngineEvent.ServerConnecting(BIG));
        givenTheConnectionIs(EngineModel.Connection.CONNECTING);
        givenTheServerNameIs("Big");
        givenTheModelHears(new EngineEvent.ServerConnected(BIG, 0x12345678L, false, true));
        givenTheConnectionIs(EngineModel.Connection.CONNECTED);
        givenTheEngineAnsweredTheServerListTheModelAskedFor();
        givenTheModelHears(new EngineEvent.ServerUpdated(BIG));
        givenTheServersListed(1);

        whenTheModelHears(new EngineEvent.ServerDisconnected(Optional.of(BIG), "bye"));

        thenTheConnectionIs(EngineModel.Connection.DISCONNECTED);
        thenThereIsNoServerName();
    }

    @Test
    void shouldShowTheLastInfoLineAndLogEveryLine() throws Exception {
        givenAModelThatHearsNoEventOfTheEngine();
        givenALogLine(LogLine.Level.INFO, "one");

        whenALogLineComes(LogLine.Level.DEBUG, "two");

        thenTheLastLogLineIs("one");
        thenTheLogEndsWith("one", "two");
    }

    @Test
    void shouldKeepOnlyTheLatestLogLines() throws Exception {
        givenAModelThatHearsNoEventOfTheEngine();

        whenLogLinesCome(EngineModel.LOG_LINES + 5);

        thenTheLogHasLines(EngineModel.LOG_LINES);
    }

    @Test
    void shouldListADownloadThroughItsEventsUntilRemoved() throws Exception {
        givenAModelThatHearsNoEventOfTheEngine();
        givenTheModelHears(new EngineEvent.DownloadAdded(DOWNLOAD));
        givenTheModelHears(new EngineEvent.DownloadProgress(DOWNLOAD));
        givenTheModelHears(new EngineEvent.DownloadFailed(DOWNLOAD, "disk"));
        givenTheDownloadsListed(1);

        whenTheModelHears(new EngineEvent.DownloadRemoved(FILE));

        thenTheModelHasNoDownload();
    }

    @Test
    void shouldKeepAFinishedDownloadListedAndTellIt() throws Exception {
        givenAModelThatHearsNoEventOfTheEngine();
        givenTheFinishedDownloadsAreNoted();
        givenTheModelHears(new EngineEvent.DownloadAdded(DOWNLOAD));

        whenTheDownloadCompletes();

        thenTheFinishedDownloadsStayListed(1);
        thenTheFinishedDownloadsToldAre("a.bin");
    }

    @Test
    void shouldClearTheFinishedDownloads() throws Exception {
        givenAModelThatHearsNoEventOfTheEngine();
        givenTheModelHears(new EngineEvent.DownloadAdded(DOWNLOAD));
        givenTheDownloadCompleted();

        whenTheFinishedDownloadsAreCleared();

        thenTheModelHasNoDownload();
    }

    @Test
    void shouldNoteTheSessionsAndMessagesOfTheServerWithoutLoggingThem() throws Exception {
        givenAModelThatHearsNoEventOfTheEngine();
        givenTheEngineStartedWithItsPreferences();
        givenTheLogLinesCounted();
        givenTheFirstServerNoteIsTheTitle();
        givenTheServerNotesCleared();
        givenTheModelHears(new EngineEvent.ServerConnected(SUNRISE, 7_603_580, true, true));

        whenTheModelHears(new EngineEvent.ServerMessage(SUNRISE, "Welcome to eMule Sunrise!"));

        thenTheServerNotesAre(
                EngineModel.ServerNote.Kind.GAP,
                EngineModel.ServerNote.Kind.CONNECTED_OBFUSCATED,
                EngineModel.ServerNote.Kind.MESSAGE);
        thenTheServerNoteReads(1, "eMule Sunrise (176.123.5.89:4725)");
        thenTheServerAddressIs("176.123.5.89:4725");
        thenTheServerLinesAreNotLogged();
    }

    @Test
    void shouldClearTheServerNotes() throws Exception {
        givenAModelThatHearsNoEventOfTheEngine();
        givenTheServerNotesCleared();
        givenTheModelHears(new EngineEvent.ServerConnected(SUNRISE, 7_603_580, true, true));
        givenTheModelHears(new EngineEvent.ServerMessage(SUNRISE, "Welcome to eMule Sunrise!"));

        whenTheServerNotesAreCleared();

        thenThereIsNoServerNote();
    }

    @Test
    void shouldSendResultsToTheirSearch() throws Exception {
        givenAModel();
        givenASearchTheEngineAccepted(new SearchId(7), "x");

        whenTheSameResultComesFor(new SearchId(7), new SearchId(8));

        thenTheSearchHasResults(1);
        thenTheSearchRuns(true);
    }

    @Test
    void shouldEndASearchWhenTheEngineSaysSo() throws Exception {
        givenAModel();
        givenASearchTheEngineAccepted(new SearchId(7), "x");
        givenTheSameResultCameFor(new SearchId(7), new SearchId(8));

        whenTheSearchEnds(new SearchId(7));

        thenTheSearchRuns(false);
        thenTheSearchesAreOnlyTheOneOpened();
        thenTheSearchIs(new SearchId(7), "x");
    }

    @Test
    void shouldForgetAStoppedSearch() throws Exception {
        givenAModel();
        givenASearchTheEngineAccepted(new SearchId(7), "x");
        givenTheSearchEnded(new SearchId(7));
        givenMoreResultsAsked();

        whenTheSearchIsStopped();

        thenThereIsNoSearch();
    }

    @Test
    void shouldSendResultsThatComeBeforeTheirSearchOpensToItOnceItDoes() throws Exception {
        givenAModel();
        givenResultsAndTheEndOfASearchCameBeforeItsId(new SearchId(9));

        whenTheSearchOpens(new SearchId(9));

        thenTheSearchHasTheResultsAndHasEnded();
    }

    @Test
    void shouldNotHideANewerStateWithALateAnswerToAnEarlierPoll() throws Exception {
        givenAModelWhoseEngineAnswersStatusPollsWhenTold();
        givenTheModelPolledAgain();
        givenTheSecondPollAnsweredFirst(KAD_ONLY);

        whenTheFirstPollIsAnswered(NOTHING);

        thenTheModelShows(KAD_ONLY);
    }

    @Test
    void shouldShareADirectory() throws Exception {
        givenAModel();
        givenAFolderWithAFile("share", "b.txt", 100);

        whenTheFolderIsShared();

        thenTheSharedFilesAre(1);
    }

    @Test
    void shouldUpdateThePreferences() throws Exception {
        givenAModel();

        whenTheNickIsChangedTo("Changed");

        thenTheNickToldIs("Changed");
    }

    @Test
    void shouldImportAnEmuleProfile() throws Exception {
        givenAModel();
        givenAnEmuleProfileWith("preferences.ini", "[eMule]\r\nNick=Imported\r\n");

        whenTheProfileIsImported();

        thenTheImportReported();
        thenTheNickIs("Imported");
    }

    @Test
    void shouldStillTellTheChangesOfTheCategoriesAfterAGarbageCollection() throws Exception {
        givenAModel();
        givenTheNamesOfTheCategoriesAddedAreNoted();
        givenTheGarbageCollected();

        whenTheCategoriesAreSetTo("Films");

        thenTheCategoriesAddedInclude("Films");
        thenTheCategoriesAreAlwaysTheSameList();
    }

    private void givenAModel() throws Exception {
        this.model = start();
    }

    private void givenAModelThatHearsNoEventOfTheEngine() throws Exception {
        notListening();
    }

    private void givenAModelWhoseEngineCannotConnectTo(String host) throws Exception {
        start(real -> Engines.overriding(
                real, Map.of("connect", args -> CompletableFuture.failedFuture(new UnknownHostException(host)))));
    }

    private void givenAModelWhoseConnectionIsPendingAndWhoseKadCannotStart() throws Exception {
        start(real -> Engines.overriding(
                real,
                Map.of(
                        "connect",
                        args -> this.connecting,
                        "kadBootstrap",
                        args -> CompletableFuture.failedFuture(new IllegalStateException("Kad is not running")))));
    }

    private void givenAModelWhoseEngineCannotAddDownloads(String message) throws Exception {
        start(real -> Engines.overriding(
                real, Map.of("addDownload", args -> CompletableFuture.failedFuture(new IOException(message)))));
    }

    private void givenAModelWhoseEngineAnswersStatusPollsWhenTold() throws Exception {
        start(real -> Engines.overriding(real, Map.of("status", args -> {
            CompletableFuture<EngineStatus> answer = new CompletableFuture<>();
            this.statusPolls.add(answer);
            return answer;
        })));
    }

    private void givenTheModelHears(EngineEvent event) {
        this.model.handle(event);
    }

    private void givenTheModelConnects() {
        this.model.connect(Optional.empty());
    }

    private void givenKadFailedToBootstrapFrom(String node) throws Exception {
        this.model.kadBootstrap(ServerAddress.parse(node));
        until(() -> !this.errors.isEmpty());
    }

    private void givenTheConnectionIs(EngineModel.Connection connection) {
        assertThat(this.model.connectionProperty().get()).isEqualTo(connection);
    }

    private void givenTheServerNameIs(String name) {
        assertThat(this.model.serverNameProperty().get()).isEqualTo(name);
    }

    // On a connection the model asks the engine for its servers; the answer, coming later, would replace the list.
    // The engine answers in turn: once a later question is answered, the model's is too.
    private void givenTheEngineAnsweredTheServerListTheModelAskedFor() throws Exception {
        this.engine.servers().get(10, TimeUnit.SECONDS);
    }

    private void givenTheServersListed(int count) {
        assertThat(this.model.servers().size()).isEqualTo(count);
    }

    private void givenTheModelKeptRates(int count) throws Exception {
        until(() -> this.model.rates().size() == count);
    }

    private void givenTheDownloadAdded(String name, long size) {
        addDownload(name, size);
    }

    private void givenTheDownloadIsListedAndToldAdded(String name) throws Exception {
        until(() -> this.model.downloads().size() == 1 && !this.added.isEmpty());
        assertThat(this.added).containsExactly(name);
    }

    private void givenTheDownloadPaused() throws Exception {
        this.model.pause(FILE);
        until(() -> this.model.downloads().rows().getFirst().get().status() == DownloadInfo.Status.PAUSED);
    }

    private void givenTheDownloadResumed() throws Exception {
        this.model.resume(FILE);
        until(() -> this.model.downloads().rows().getFirst().get().status() == DownloadInfo.Status.DOWNLOADING);
    }

    private void givenAServerSearchFor(String expression) {
        this.model.search(new SearchRequest(expression, SearchRequest.Scope.SERVER), this.started::set);
    }

    private void givenALogLine(LogLine.Level level, String message) {
        this.model.handle(new EngineEvent.Log(new LogLine(Instant.now(), level, message)));
    }

    private void givenTheDownloadsListed(int count) {
        assertThat(this.model.downloads().size()).isEqualTo(count);
    }

    private void givenTheFinishedDownloadsAreNoted() {
        this.model.onCompleted(d -> this.finished.add(d.name()));
    }

    private void givenTheDownloadCompleted() {
        complete();
    }

    private void givenTheEngineStartedWithItsPreferences() {
        this.model.handle(new EngineEvent.PreferencesChanged(this.engine.preferences()));
        this.model.handle(new EngineEvent.Started("x"));
    }

    private void givenTheLogLinesCounted() {
        this.logged = this.model.log().size();
    }

    private void givenTheFirstServerNoteIsTheTitle() {
        assertThat(this.model.serverInfo().getFirst().kind()).isEqualTo(EngineModel.ServerNote.Kind.TITLE);
    }

    private void givenTheServerNotesCleared() {
        this.model.clearServerInfo();
    }

    // As if the engine had accepted the search.
    private void givenASearchTheEngineAccepted(SearchId id, String expression) {
        this.opened = this.model.open(id, new SearchRequest(expression, SearchRequest.Scope.KAD));
    }

    private void givenTheSameResultCameFor(SearchId... ids) {
        resultFor(ids);
    }

    private void givenTheSearchEnded(SearchId id) {
        this.model.handle(new EngineEvent.SearchEnded(id, 1));
    }

    private void givenMoreResultsAsked() {
        this.model.moreResults(this.opened);
    }

    private void givenResultsAndTheEndOfASearchCameBeforeItsId(SearchId id) {
        SearchResult result = new SearchResult(FILE, "x.iso", 10, 3, 1, Optional.of("Iso"));
        this.model.handle(new EngineEvent.SearchResults(id, List.of(result)));
        this.model.handle(new EngineEvent.SearchEnded(id, 1));
    }

    private void whenTheModelPollsUntilItKeptRates(int count) throws Exception {
        for (int i = this.model.rates().size(); i < count; i++) {
            this.model.tick();
        }
        until(() -> this.model.rates().size() == count);
    }

    private void thenTheModelKeepsMinutes(int count) throws Exception {
        until(() -> this.model.minuteRates().size() == count);
        assertThat(this.model.minuteRates()).hasSize(count);
    }

    private void givenTheModelPolledAgain() {
        this.model.tick();
    }

    private void givenTheSecondPollAnsweredFirst(EngineStatus status) {
        this.statusPolls.get(1).complete(status);
    }

    private void givenAFolderWithAFile(String name, String file, int size) throws IOException {
        this.folder = Files.createDirectories(this.dir.resolve(name));
        Files.write(this.folder.resolve(file), new byte[size]);
    }

    private void givenAnEmuleProfileWith(String file, String content) throws IOException {
        this.folder = Files.createDirectories(this.dir.resolve("emule"));
        Files.writeString(this.folder.resolve(file), content);
    }

    private void givenTheNamesOfTheCategoriesAddedAreNoted() {
        this.model.categories().addListener((ListChangeListener<Category>) c -> {
            while (c.next()) {
                c.getAddedSubList().forEach(category -> this.categoriesAdded.add(category.name()));
            }
        });
    }

    private void givenTheGarbageCollected() throws InterruptedException {
        collectGarbage();
    }

    private void whenTheModelConnectsTo(String host, int port) {
        this.model.connect(Optional.of(new ServerAddress(host, port)));
    }

    private void whenKadIsBootstrappedFrom(String node) {
        this.model.kadBootstrap(ServerAddress.parse(node));
    }

    private void whenTheConnectionFails(String reason) {
        this.connecting.completeExceptionally(new IllegalStateException(reason));
    }

    private void whenAnErrorWithoutAMessageIsTold() {
        this.model.onError((Throwable) new IllegalStateException());
    }

    private void whenTheDownloadIsAdded(String name, long size) {
        addDownload(name, size);
    }

    private void whenTheModelPollsAgain() {
        this.model.tick();
    }

    private void whenTheDownloadIsCancelled() {
        this.model.cancel(FILE);
    }

    private void whenTheModelHears(EngineEvent event) {
        this.model.handle(event);
    }

    private void whenALogLineComes(LogLine.Level level, String message) {
        this.model.handle(new EngineEvent.Log(new LogLine(Instant.now(), level, message)));
    }

    private void whenLogLinesCome(int count) {
        for (int i = 0; i < count; i++) {
            this.model.handle(new EngineEvent.Log(new LogLine(Instant.now(), LogLine.Level.INFO, "x")));
        }
    }

    private void whenTheDownloadCompletes() {
        complete();
    }

    private void whenTheFinishedDownloadsAreCleared() {
        this.model.clearCompleted();
    }

    private void whenTheServerNotesAreCleared() {
        this.model.clearServerInfo();
    }

    private void whenTheSameResultComesFor(SearchId... ids) {
        resultFor(ids);
    }

    private void whenTheSearchEnds(SearchId id) {
        this.model.handle(new EngineEvent.SearchEnded(id, 1));
    }

    private void whenTheSearchIsStopped() {
        this.model.stopSearch(this.opened);
    }

    private void whenTheSearchOpens(SearchId id) {
        this.opened = this.model.open(id, new SearchRequest("x", SearchRequest.Scope.SERVER));
    }

    private void whenTheFirstPollIsAnswered(EngineStatus status) {
        this.statusPolls.getFirst().complete(status);
    }

    private void whenTheFolderIsShared() {
        this.model.shareDirectory(this.folder);
    }

    private void whenTheNickIsChangedTo(String nick) {
        this.model.updatePreferences(p -> p.toBuilder().nick(nick).build(), p -> this.nick.set(p.nick()));
    }

    private void whenTheProfileIsImported() {
        this.model.importFromEmule(
                this.folder, new ImportOptions(false, false, false), this.report::set, this.errors::add);
    }

    private void whenTheCategoriesAreSetTo(String name) throws Exception {
        AtomicBoolean done = new AtomicBoolean();
        this.model.setCategories(
                List.of(new Category(1, name, Optional.empty(), "", 1, AUTO, "*.mkv", false)), () -> done.set(true));
        until(done::get);
    }

    private void thenTheErrorsAre(String... errors) throws Exception {
        until(() -> !this.errors.isEmpty());
        assertThat(this.errors).containsExactly(errors);
    }

    private void thenAnErrorIsTold() throws Exception {
        until(() -> !this.errors.isEmpty());
    }

    private void thenThereAreAtLeastErrors(int count) throws Exception {
        until(() -> this.errors.size() >= count);
    }

    private void thenAnErrorContains(String part) {
        assertThat(this.errors).anyMatch(e -> e.contains(part));
    }

    private void thenTheModelIsStillConnected() {
        assertThat(this.model.connectionProperty().get())
                .as("still connected to Big, not connecting for ever")
                .isEqualTo(EngineModel.Connection.CONNECTED);
    }

    private void thenTheConnectionIs(EngineModel.Connection connection) {
        assertThat(this.model.connectionProperty().get()).isEqualTo(connection);
    }

    private void thenTheServerNameIs(String name) {
        assertThat(this.model.serverNameProperty().get()).isEqualTo(name);
    }

    private void thenThereIsNoServerName() {
        assertThat(this.model.serverNameProperty().get()).isEmpty();
    }

    private void thenNoDownloadWasToldAdded() {
        assertThat(this.added).isEmpty();
    }

    private void thenTheModelHasNoDownload() {
        assertThat(this.model.downloads().size()).isZero();
    }

    private void thenTheModelSoonHasNoDownload() throws Exception {
        until(() -> this.model.downloads().size() == 0);
    }

    private void thenTheEngineHasNoDownload() throws Exception {
        assertThat(this.engine.downloads().get(5, TimeUnit.SECONDS)).isEmpty();
    }

    private void thenTheModelKeepsRates(int count) throws Exception {
        until(() -> this.model.rates().size() == count);
    }

    private void thenTheStatusHasNoServer() {
        assertThat(this.model.statusProperty().get().server()).isEmpty();
    }

    private void thenTheModelWorksOnTheEngine() {
        assertThat(this.model.engine()).isSameAs(this.engine);
    }

    private void thenThePreferencesAreThoseOfTheEngine() {
        assertThat(this.model.preferencesProperty().get()).isEqualTo(this.engine.preferences());
    }

    private void thenTheSearchDidNotStart() {
        assertThat(this.started.get()).isNull();
    }

    private void thenTheLastLogLineIs(String message) {
        assertThat(this.model.lastLogProperty().get()).isEqualTo(message);
    }

    private void thenTheLogEndsWith(String... messages) {
        assertThat(this.model.log()).extracting(LogLine::message).endsWith(messages);
    }

    private void thenTheLogHasLines(int count) {
        assertThat(this.model.log()).hasSize(count);
    }

    private void thenTheFinishedDownloadsStayListed(int count) {
        assertThat(this.model.downloads().size())
                .as("finished downloads stay listed")
                .isEqualTo(count);
    }

    private void thenTheFinishedDownloadsToldAre(String... names) {
        assertThat(this.finished).containsExactly(names);
    }

    private void thenTheServerNotesAre(EngineModel.ServerNote.Kind... kinds) {
        assertThat(this.model.serverInfo())
                .extracting(EngineModel.ServerNote::kind)
                .containsExactly(kinds);
    }

    private void thenTheServerNoteReads(int index, String text) {
        assertThat(this.model.serverInfo().get(index).text()).isEqualTo(text);
    }

    private void thenTheServerAddressIs(String address) {
        assertThat(this.model.serverAddressProperty().get()).isEqualTo(address);
    }

    private void thenTheServerLinesAreNotLogged() {
        assertThat(this.model.log()).as("the server's lines are not events").hasSize(this.logged);
    }

    private void thenThereIsNoServerNote() {
        assertThat(this.model.serverInfo()).isEmpty();
    }

    private void thenTheSearchHasResults(int count) {
        assertThat(this.opened.results().size()).isEqualTo(count);
    }

    private void thenTheSearchRuns(boolean running) {
        assertThat(this.opened.runningProperty().get()).isEqualTo(running);
    }

    private void thenTheSearchesAreOnlyTheOneOpened() {
        assertThat(this.model.searches()).containsExactly(this.opened);
    }

    private void thenTheSearchIs(SearchId id, String expression) {
        assertThat(this.opened.id()).isEqualTo(id);
        assertThat(this.opened.request().expression()).isEqualTo(expression);
    }

    private void thenThereIsNoSearch() {
        assertThat(this.model.searches()).isEmpty();
    }

    private void thenTheSearchHasTheResultsAndHasEnded() {
        assertThat(this.opened.results().size()).isEqualTo(1);
        assertThat(this.opened.runningProperty().get()).isFalse();
    }

    private void thenTheModelShows(EngineStatus status) {
        assertThat(this.model.statusProperty().get()).isEqualTo(status);
    }

    private void thenTheSharedFilesAre(int count) throws InterruptedException {
        for (int i = 0; i < 80 && this.model.sharedFiles().size() == 0; i++) {
            Thread.sleep(100);
            for (int t = 0; t < 15; t++) {
                this.model.tick();
            }
        }
        assertThat(this.model.sharedFiles().size()).isEqualTo(count);
    }

    private void thenTheNickToldIs(String nick) throws Exception {
        until(() -> nick.equals(this.nick.get()));
    }

    private void thenTheImportReported() throws Exception {
        until(() -> this.report.get() != null);
    }

    private void thenTheNickIs(String nick) {
        assertThat(this.model.preferencesProperty().get().nick()).isEqualTo(nick);
    }

    private void thenTheCategoriesAddedInclude(String... names) {
        assertThat(this.categoriesAdded).contains(names);
    }

    private void thenTheCategoriesAreAlwaysTheSameList() {
        assertThat(this.model.categories()).isSameAs(this.model.categories());
    }

    private void addDownload(String name, long size) {
        this.model.addDownload(Ed2kLink.FileLink.of(name, size, FILE), d -> this.added.add(d.name()));
    }

    private void complete() {
        this.model.handle(new EngineEvent.DownloadCompleted(DONE, this.dir.resolve("a.bin")));
    }

    private void resultFor(SearchId... ids) {
        SearchResult result = new SearchResult(FILE, "x.iso", 10, 3, 1, Optional.of("Iso"));
        for (SearchId id : ids) {
            this.model.handle(new EngineEvent.SearchResults(id, List.of(result)));
        }
    }

    // Returns once a weak reference was cleared a few times: collections ran.
    private static void collectGarbage() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            WeakReference<Object> probe = new WeakReference<>(new Object());
            for (int tries = 0; tries < 100 && probe.get() != null; tries++) {
                System.gc();
                Thread.sleep(10);
            }
        }
    }

    private static void until(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out");
            }
            Thread.sleep(20);
        }
    }
}
