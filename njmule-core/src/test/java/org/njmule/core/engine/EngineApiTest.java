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
package org.njmule.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.FreePorts;
import org.njmule.core.api.Category;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.ImportOptions;
import org.njmule.core.api.LogLine;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.api.SharedDirectory;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.config.Preferences;
import org.njmule.core.config.Schedule;
import org.njmule.core.persist.ConfigBackup;
import org.njmule.core.persist.PreferencesCodec;
import org.njmule.core.persist.ScheduleCodec;
import org.njmule.core.persist.SyntheticEmuleProfile;
import org.njmule.core.persist.format.IpFilterDat;

/** The engine's own API: what it does itself around the network, and what it refuses. */
@Timeout(60)
class EngineApiTest {

    private static final Category FILMS =
            new Category(1, "Films", Optional.empty(), "", 1, DownloadPriority.AUTO, "", false);
    private static final Category MUSIC =
            new Category(2, "Music", Optional.empty(), "", 2, DownloadPriority.AUTO, "", false);
    private static final Schedule.Rule STOP_FILMS = rule("films off", true, OptionalInt.of(1), OptionalInt.empty());
    private static final Schedule.Rule RESUME_MUSIC = rule("music on", false, OptionalInt.empty(), OptionalInt.of(2));
    private static final Schedule.Rule NIGHT = rule("night", true, OptionalInt.empty(), OptionalInt.empty());
    private static final Schedule.Rule LATE_NIGHT = rule("late night", true, OptionalInt.empty(), OptionalInt.empty());
    private static final String LOCAL_LIST = "http://127.0.0.1:9/list";

    @TempDir
    Path dir;

    private Engine engine;
    private List<SearchResult> rated = List.of();
    private final Ed2kLink.FileLink song = Ed2kLink.FileLink.of("song.mp3", 5000, Hash16.parseHex("f4".repeat(16)));
    private final Ed2kLink.FileLink film = Ed2kLink.FileLink.of("film.mkv", 5000, Hash16.parseHex("f3".repeat(16)));
    private List<Object> swapAnswers;
    private AppDirectories profile;
    private Path preferencesFile;
    private Preferences saved;
    private SyntheticEmuleProfile emule;
    private int kadPort;
    private Recorder recorder;
    private Recorder slow;
    private boolean firewallRechecked;
    private boolean stopped;
    private byte[] list;
    private List<IpFilterDat.Range> ranges;
    private Throwable exception;

    @AfterEach
    void closeEngine() {
        if (this.engine != null) {
            this.engine.close();
        }
    }

    @Test
    void shouldSwapSourcesAndTakeThemByItselfThroughTheEngine() throws Exception {
        givenADownloadWithoutSources();

        whenEverySwapIsAskedForTheDownload();

        thenTheSwapAnswersAre(0, 0, true, false);
        thenTheDownloadTakesA4afSourcesByItself();
    }

    private void givenADownloadWithoutSources() throws Exception {
        this.engine = start("");
        this.engine.addDownload(this.song).get(5, TimeUnit.SECONDS);
    }

    private void whenEverySwapIsAskedForTheDownload() throws Exception {
        Hash16 hash = this.song.hash();
        this.swapAnswers = List.of(
                this.engine.swapSourcesToDownload(hash).get(5, TimeUnit.SECONDS),
                this.engine.swapSourcesFromDownload(hash).get(5, TimeUnit.SECONDS),
                this.engine.setDownloadAutoSwap(hash, true).get(5, TimeUnit.SECONDS),
                this.engine
                        .swapSourceToDownload(hash, new ServerAddress("127.0.0.1", 4662))
                        .get(5, TimeUnit.SECONDS));
    }

    private void thenTheSwapAnswersAre(Object... expected) {
        assertThat(this.swapAnswers).containsExactly(expected);
    }

    private void thenTheDownloadTakesA4afSourcesByItself() throws Exception {
        assertThat(this.engine.downloads().get(5, TimeUnit.SECONDS))
                .singleElement()
                .satisfies(d -> assertThat(d.autoSwap()).isTrue());
    }

    @Test
    void shouldNeverFetchAServerListFromThisMachine() throws Exception {
        givenARunningEngine();

        whenAServerListIsFetchedFrom(LOCAL_LIST);

        thenTheFetchFailsWithTheRootCause(IOException.class, "local or private");
    }

    @Test
    void shouldNeverFetchKadContactsFromThisMachine() throws Exception {
        givenARunningEngine();

        whenKadContactsAreFetchedFrom(LOCAL_LIST);

        thenTheFetchFailsWithTheRootCause(IOException.class, "local or private");
    }

    @Test
    void shouldNeverFetchAnIpFilterFromThisMachine() throws Exception {
        givenARunningEngine();

        whenAnIpFilterIsFetchedFrom(LOCAL_LIST);

        thenTheFetchFailsWithTheRootCause(IOException.class, "local or private");
    }

    @Test
    void shouldUpdateTheServerListsAtTheStartWhenThePreferencesAskForIt() throws Exception {
        givenAProfileWith("server.updateAtStart=true\n");
        givenTheServerListAddressesFile(
                "http://127.0.0.1:9/server.met\nftp://lists.example/server.met\nhttp://bad host/server.met\n");

        whenTheEngineStarts();

        thenTheServerListAddressesAreOnlyTheWebOnes("http://127.0.0.1:9/server.met");
        thenTheLogWarns("Cannot update the server list from 127.0.0.1");
    }

    @Test
    void shouldMarkSpamThroughTheEngine() throws Exception {
        givenARunningEngine();

        whenTheSearchResultIsMarkedAsSpam(Hash16.parseHex("b2".repeat(16)), "spam.exe");

        thenTheSpamIs(Hash16.parseHex("b2".repeat(16)));
    }

    @Test
    void shouldRateALookalikeOfASearchResultMarkedAsSpam() throws Exception {
        givenARunningEngine();
        givenTheSearchResultMarkedAsSpam(Hash16.parseHex("b2".repeat(16)), "Free movie player.exe");

        whenTheResultIsRated(new SearchResult(
                Hash16.parseHex("c3".repeat(16)), "free movie player.EXE", 10, 1, 0, Optional.empty()));

        thenTheRatedResultIsSpam(true);
    }

    @Test
    void shouldRateNothingAsSpamWithTheSpamFilterOff() throws Exception {
        givenARunningEngineWithoutTheSpamFilter();
        givenTheSearchResultMarkedAsSpam(Hash16.parseHex("b2".repeat(16)), "Free movie player.exe");

        whenTheResultIsRated(new SearchResult(
                Hash16.parseHex("b2".repeat(16)), "Free movie player.exe", 10, 1, 0, Optional.empty()));

        thenTheRatedResultIsSpam(false);
    }

    @Test
    void shouldLiftABanOnlyFromAnAddress() throws Exception {
        givenARunningEngine();

        whenTheBanIsLiftedFrom("not an address");

        thenExceptionOccurredWithTheCause(IllegalArgumentException.class);
    }

    @Test
    void shouldStopPrioritiseAndPreviewADownloadThroughTheEngine() throws Exception {
        givenARunningEngine();
        givenADownloadOfTheFilm();
        givenTheFilmPriorityIsSetTo(DownloadPriority.LOW);
        givenTheFilmPreviewIsTurnedOn();
        givenTheFilmHasNoDataToPreviewYet();

        whenTheFilmIsStopped();

        thenTheStopIsDone();
        thenTheOnlyDownloadIs(DownloadInfo.Status.STOPPED, DownloadPriority.LOW);
        thenTheDescriptionOfTheEngineMentions("film.mkv");
    }

    @Test
    void shouldShareAndUnshareFoldersThroughTheEngine() throws Exception {
        givenARunningEngine();
        givenTheFolderIsShared("films");

        whenTheFolderIsUnshared("films");

        thenNoFolderIsShared();
    }

    @Test
    void shouldNotRecheckTheKadFirewallNorListAnythingWhileKadDoesNotRun() throws Exception {
        givenARunningEngine();

        whenTheKadFirewallIsRechecked();

        thenTheFirewallRecheckIs(false);
        thenThereAreNoKadContacts();
        thenThereAreNoKadSearches();
    }

    @Test
    void shouldRefuseAKadBootstrapWhileKadDoesNotRun() throws Exception {
        givenARunningEngine();

        whenKadIsBootstrappedFrom("127.0.0.1", 4672);

        thenExceptionOccurredWithTheCause(IllegalStateException.class);
    }

    @Test
    void shouldFailTheHelloToAHostThatCannotBeResolved() throws Exception {
        givenARunningEngine();

        whenAHelloIsSentTo("[bad", 4662);

        thenExceptionOccurredWithTheCause(UnknownHostException.class);
    }

    @Test
    void shouldKeepNoCopyOfTheConfigurationWhenNoneIsWanted() throws Exception {
        givenAProfileWith("backup.copies=0\n");

        whenTheEngineStartsAndStops();

        thenNoExceptionOccurred();
        thenNoBackupFolderExists();
    }

    @Test
    void shouldLetGoOfTheConfigurationAfterAStartThatFails() throws Exception {
        givenAProfileWith("");
        givenATemporaryFolderThatIsAFile();
        givenAStartFailed();
        givenTheSavedPreferencesAreBack();

        whenTheEngineStartsAndStops();

        thenNoExceptionOccurred();
    }

    @Test
    void shouldReplayTheLogUpToItsLimitToALateSubscriber() throws Exception {
        givenARunningEngine();
        givenTheEngineLogged(LogLine.Level.DEBUG, "a detail");
        givenTheEngineLogged(LogLine.Level.ERROR, "a failure");
        givenTheEngineLoggedNumberedLines("line ", 150);

        whenSomeoneSubscribesAskingFor(Long.MAX_VALUE);

        thenTheSubscriberGetsTheLog("line 149");
        thenTheFirstEventIsTheFirstLog();
        thenTheFirstLogIs("line 50");
    }

    @Test
    void shouldLetASlowSubscriberMissEventsWhileTheOthersGetEverything() throws Exception {
        givenARunningEngine();
        givenSomeoneSubscribedAskingFor(Long.MAX_VALUE);
        givenASlowSubscriberAskingFor(1);

        whenTheEngineLogsNumberedLinesInSteps("more ", 3_000);

        thenTheOtherSubscriberStillGetsTheLog("more 2999");
        thenTheSlowSubscriberGetsOnly(1);
    }

    @Test
    void shouldFailASubscriberThatAsksForNothing() throws Exception {
        givenARunningEngine();

        whenSomeoneSubscribesAskingFor(0);

        thenTheSubscriberFailsWith(IllegalArgumentException.class);
    }

    @Test
    void shouldLetRulesGoOfDeletedCategoriesAndKeepTheActiveRuleActive() throws Exception {
        givenARunningEngine();
        givenTheCategories(FILMS, MUSIC);
        givenTheScheduleIs(STOP_FILMS, RESUME_MUSIC);
        givenTheRuleInForceIs(STOP_FILMS);

        whenTheCategoriesAre(MUSIC);

        thenTheFirstRuleStopsNoCategory();
        thenTheSecondRuleIs(RESUME_MUSIC);
        thenTheFirstRuleIsStillInForce();
        thenTheSavedScheduleIsTheOneOfTheEngine();
    }

    @Test
    void shouldGoOnWithoutActingAgainWhenARuleIsEditedWhileItHolds() throws Exception {
        givenARunningEngine();
        givenTheScheduleIs(NIGHT);
        givenSomeoneSubscribedAskingFor(Long.MAX_VALUE);
        givenTheScheduleIs(NIGHT);

        whenTheScheduleIs(LATE_NIGHT);

        thenTheRuleInForceIs(LATE_NIGHT);
        thenTheSchedulerHasLoggedOnly("Scheduler: \"night\" applies now");
    }

    @Test
    void shouldRefuseAScheduleThatCannotBeSaved() throws Exception {
        givenARunningEngine();
        givenTheScheduleCannotBeSaved();

        whenTheScheduleIsEmptied();

        thenExceptionOccurredWithTheCause(UncheckedIOException.class);
    }

    @Test
    void shouldTakeTheCategoriesOfAnImportWhenWeHaveNone() throws Exception {
        givenAnEmuleProfileWithTheCategory("Films");
        givenARunningEngine();

        whenTheEmuleProfileIsImported();

        thenTheCategoryNamesAre("Films");
    }

    @Test
    void shouldHandKadCommandsToARunningKad() throws Exception {
        givenARunningEngineWithKad();
        givenKadRuns();
        givenKadIsBootstrappedFromAClosedPortOfThisMachine();

        whenTheKadFirewallIsRechecked();

        thenTheFirewallRecheckIs(true);
    }

    @Test
    void shouldLeaveTheWayClearWhenABackupCannotBeWritten() throws Exception {
        givenAProfileWith("");
        givenAFileWhereTheBackupFolderGoes();
        givenTheEngineRanOnce();
        givenTheFileIsLeftWhereTheBackupFolderGoes();

        whenTheEngineStartsAndStops();

        thenNoExceptionOccurred();
    }

    @Test
    void shouldSendNowhereWhatIsLoggedAfterTheEnd() throws Exception {
        givenARunningEngine();
        givenSomeoneSubscribedAskingFor(Long.MAX_VALUE);
        givenTheEngineIsClosed();

        whenTheEngineLogs("too late");

        thenTheEventsAreCompleted();
        thenTheLastEventIs(EngineEvent.Stopping.class);
    }

    @Test
    void shouldReadAnIpFilterAtAnAddressWithoutAPathInEitherForm() {
        givenTheIpFilterList("1.2.3.4 - 1.2.3.5 , 100 , one\n");

        whenTheListIsReadFrom("urn:lists:ipfilter");

        thenTheOnlyRangeIsDescribedAs("one");
    }

    private void givenARunningEngine() throws IOException {
        this.engine = start("");
    }

    private void givenARunningEngineWithoutTheSpamFilter() throws IOException {
        this.engine = start("search.spamFilter=false\n");
    }

    private void givenTheSearchResultMarkedAsSpam(Hash16 hash, String name) throws Exception {
        whenTheSearchResultIsMarkedAsSpam(hash, name);
    }

    private void whenTheResultIsRated(SearchResult result) throws Exception {
        this.rated = this.engine.rateSpam(List.of(result)).get(5, TimeUnit.SECONDS);
    }

    private void thenTheRatedResultIsSpam(boolean spam) {
        assertThat(this.rated)
                .singleElement()
                .satisfies(r -> assertThat(r.isSpam()).isEqualTo(spam));
    }

    // A port free when probed may be taken by a test running beside before Kad binds it: then Kad gets another.
    private void givenARunningEngineWithKad() throws Exception {
        for (int attempt = 1; ; attempt++) {
            this.kadPort = FreePorts.udp();
            this.engine = start("net.kad.enabled=true\nnet.udpPort=" + this.kadPort + "\n");
            if (attempt == 5 || this.engine.kad().get(5, TimeUnit.SECONDS).isPresent()) {
                return;
            }
            this.engine.close();
        }
    }

    private void givenAProfileWith(String preferences) throws IOException {
        this.profile = dirs(preferences);
    }

    private void givenTheServerListAddressesFile(String content) throws IOException {
        Files.writeString(this.profile.config().resolve("addresses.dat"), content, StandardCharsets.UTF_8);
    }

    private void givenADownloadOfTheFilm() throws Exception {
        this.engine.addDownload(this.film).get(5, TimeUnit.SECONDS);
    }

    private void givenTheFilmPriorityIsSetTo(DownloadPriority priority) throws Exception {
        assertThat(this.engine.setDownloadPriority(this.film.hash(), priority).get(5, TimeUnit.SECONDS))
                .isTrue();
    }

    private void givenTheFilmPreviewIsTurnedOn() throws Exception {
        assertThat(this.engine.setDownloadPreview(this.film.hash(), true).get(5, TimeUnit.SECONDS))
                .isTrue();
    }

    private void givenTheFilmHasNoDataToPreviewYet() throws Exception {
        assertThat(this.engine.downloadData(this.film.hash()).get(5, TimeUnit.SECONDS))
                .isEmpty();
    }

    private void givenTheFolderIsShared(String name) throws Exception {
        Path folder = Files.createDirectories(this.dir.resolve(name));
        this.engine.shareDirectory(folder).get(5, TimeUnit.SECONDS);
        assertThat(this.engine.sharedDirectories().get(5, TimeUnit.SECONDS))
                .extracting(SharedDirectory::path)
                .containsExactly(folder);
    }

    private void givenATemporaryFolderThatIsAFile() throws IOException {
        Path notAFolder = Files.writeString(this.dir.resolve("not-a-folder"), "x");
        this.preferencesFile = this.profile.config().resolve(PreferencesCodec.FILE_NAME);
        this.saved = PreferencesCodec.load(this.preferencesFile);
        PreferencesCodec.save(
                this.preferencesFile,
                this.saved.toBuilder().tempDir(Optional.of(notAFolder)).build());
    }

    private void givenAStartFailed() {
        whenTheEngineStartsAndStops();
        assertThat(this.exception).isInstanceOf(IOException.class);
    }

    private void givenTheSavedPreferencesAreBack() throws IOException {
        PreferencesCodec.save(this.preferencesFile, this.saved);
    }

    private void givenTheEngineLogged(LogLine.Level level, String line) {
        whenTheEngineLogs(level, line);
    }

    private void givenTheEngineLoggedNumberedLines(String prefix, int count) {
        for (int i = 0; i < count; i++) {
            this.engine.userLog(LogLine.Level.INFO, prefix + i);
        }
    }

    private void givenSomeoneSubscribedAskingFor(long count) {
        whenSomeoneSubscribesAskingFor(count);
    }

    private void givenASlowSubscriberAskingFor(long count) {
        this.slow = new Recorder(count);
        this.engine.events().subscribe(this.slow);
    }

    private void givenTheCategories(Category... categories) throws Exception {
        whenTheCategoriesAre(categories);
    }

    private void givenTheScheduleIs(Schedule.Rule... rules) throws Exception {
        whenTheScheduleIs(rules);
    }

    private void givenTheRuleInForceIs(Schedule.Rule rule) {
        thenTheRuleInForceIs(rule);
    }

    private void givenTheScheduleCannotBeSaved() throws IOException {
        Path tmp = this.engine.directories().config().resolve(ScheduleCodec.FILE_NAME + ".tmp");
        Files.createDirectories(tmp);
        Files.writeString(tmp.resolve("keep"), "x");
    }

    private void givenAnEmuleProfileWithTheCategory(String title) throws IOException {
        this.emule = SyntheticEmuleProfile.create(this.dir.resolve("emule"));
        Files.writeString(this.emule.config.resolve("Category.ini"), "[Cat#1]\r\nTitle=" + title + "\r\n");
    }

    private void givenKadRuns() throws Exception {
        assertThat(this.engine.kad().get(5, TimeUnit.SECONDS)).isPresent();
    }

    // A bootstrap request to a closed port on this machine.
    private void givenKadIsBootstrappedFromAClosedPortOfThisMachine() throws Exception {
        int closed = this.kadPort == 65_535 ? this.kadPort - 1 : this.kadPort + 1;
        this.engine.kadBootstrap(new ServerAddress("127.0.0.1", closed)).get(5, TimeUnit.SECONDS);
    }

    private void givenAFileWhereTheBackupFolderGoes() throws IOException {
        Files.createDirectories(this.profile.data());
        Files.writeString(this.profile.data().resolve(ConfigBackup.FOLDER), "not a folder");
    }

    private void givenTheEngineRanOnce() {
        whenTheEngineStartsAndStops();
        thenNoExceptionOccurred();
    }

    private void givenTheFileIsLeftWhereTheBackupFolderGoes() {
        assertThat(this.profile.data().resolve(ConfigBackup.FOLDER)).isRegularFile();
    }

    private void givenTheEngineIsClosed() {
        this.engine.close();
    }

    private void givenTheIpFilterList(String content) {
        this.list = content.getBytes(StandardCharsets.US_ASCII);
    }

    private void whenAServerListIsFetchedFrom(String address) {
        this.exception =
                catchThrowable(() -> this.engine.addServers(URI.create(address)).get(10, TimeUnit.SECONDS));
    }

    private void whenKadContactsAreFetchedFrom(String address) {
        this.exception = catchThrowable(
                () -> this.engine.addKadContacts(URI.create(address)).get(10, TimeUnit.SECONDS));
    }

    private void whenAnIpFilterIsFetchedFrom(String address) {
        this.exception = catchThrowable(
                () -> this.engine.updateIpFilter(URI.create(address)).get(10, TimeUnit.SECONDS));
    }

    private void whenTheEngineStarts() throws IOException {
        this.engine = Engine.start(new EngineConfig(this.profile), () -> false);
    }

    private void whenTheEngineStartsAndStops() {
        this.exception = catchThrowable(
                () -> Engine.start(new EngineConfig(this.profile), () -> false).close());
    }

    private void whenTheSearchResultIsMarkedAsSpam(Hash16 hash, String name) throws Exception {
        this.engine
                .markSpam(List.of(new SearchResult(hash, name, 10, 1, 0, Optional.empty())), true)
                .get(5, TimeUnit.SECONDS);
    }

    private void whenTheBanIsLiftedFrom(String address) {
        this.exception = catchThrowable(() -> this.engine.unban(address).get(5, TimeUnit.SECONDS));
    }

    private void whenTheFilmIsStopped() throws Exception {
        this.stopped = this.engine.stopDownload(this.film.hash()).get(5, TimeUnit.SECONDS);
    }

    private void whenTheFolderIsUnshared(String name) throws Exception {
        this.engine.unshareDirectory(this.dir.resolve(name)).get(5, TimeUnit.SECONDS);
    }

    private void whenTheKadFirewallIsRechecked() throws Exception {
        this.firewallRechecked = this.engine.recheckKadFirewall().get(5, TimeUnit.SECONDS);
    }

    private void whenKadIsBootstrappedFrom(String host, int port) {
        this.exception = catchThrowable(
                () -> this.engine.kadBootstrap(new ServerAddress(host, port)).get(5, TimeUnit.SECONDS));
    }

    // Not even looked up: no name server is asked.
    private void whenAHelloIsSentTo(String host, int port) {
        this.exception = catchThrowable(
                () -> this.engine.hello(new ServerAddress(host, port)).get(5, TimeUnit.SECONDS));
    }

    private void whenSomeoneSubscribesAskingFor(long count) {
        this.recorder = new Recorder(count);
        this.engine.events().subscribe(this.recorder);
    }

    // In steps the others can follow: any subscriber that falls a buffer behind misses events.
    private void whenTheEngineLogsNumberedLinesInSteps(String prefix, int count) throws InterruptedException {
        for (int i = 0; i < count; i++) {
            this.engine.userLog(LogLine.Level.INFO, prefix + i);
            if (i % 500 == 499) {
                assertThat(this.recorder.awaitLog(prefix + i)).isPresent();
            }
        }
    }

    private void whenTheEngineLogs(String line) {
        whenTheEngineLogs(LogLine.Level.INFO, line);
    }

    private void whenTheEngineLogs(LogLine.Level level, String line) {
        this.engine.userLog(level, line);
    }

    private void whenTheCategoriesAre(Category... categories) throws Exception {
        this.engine.setCategories(Arrays.asList(categories)).get(5, TimeUnit.SECONDS);
    }

    private void whenTheScheduleIs(Schedule.Rule... rules) throws Exception {
        this.engine.updateSchedule(new Schedule(List.of(rules))).get(5, TimeUnit.SECONDS);
    }

    private void whenTheScheduleIsEmptied() {
        this.exception =
                catchThrowable(() -> this.engine.updateSchedule(Schedule.EMPTY).get(5, TimeUnit.SECONDS));
    }

    private void whenTheEmuleProfileIsImported() throws Exception {
        this.engine
                .importFromEmule(this.emule.config, new ImportOptions(false, false, false))
                .get(30, TimeUnit.SECONDS);
    }

    private void whenTheListIsReadFrom(String address) {
        this.ranges = Engine.ipRanges(this.list, URI.create(address));
    }

    private void thenTheFetchFailsWithTheRootCause(Class<? extends Throwable> type, String messagePart) {
        assertThat(this.exception)
                .isInstanceOf(ExecutionException.class)
                .rootCause()
                .isInstanceOf(type)
                .hasMessageContaining(messagePart);
    }

    private void thenExceptionOccurredWithTheCause(Class<? extends Throwable> cause) {
        assertThat(this.exception).hasCauseInstanceOf(cause);
    }

    private void thenNoExceptionOccurred() {
        assertThat(this.exception).isNull();
    }

    private void thenTheServerListAddressesAreOnlyTheWebOnes(String... addresses) throws Exception {
        assertThat(this.engine.serverListAddresses().get(5, TimeUnit.SECONDS))
                .as("only web addresses")
                .containsExactly(Arrays.stream(addresses).map(URI::create).toArray(URI[]::new));
    }

    private void thenTheLogWarns(String start) throws InterruptedException {
        Recorder listener = new Recorder(Long.MAX_VALUE);
        this.engine.events().subscribe(listener);
        assertThat(listener.awaitLog(start))
                .hasValueSatisfying(l -> assertThat(l.level()).isEqualTo(LogLine.Level.WARNING));
    }

    private void thenTheSpamIs(Hash16... hashes) throws Exception {
        assertThat(this.engine.spamMarks().get(5, TimeUnit.SECONDS).spam()).containsExactly(hashes);
    }

    private void thenTheStopIsDone() {
        assertThat(this.stopped).isTrue();
    }

    private void thenTheOnlyDownloadIs(DownloadInfo.Status status, DownloadPriority priority) throws Exception {
        assertThat(this.engine.downloads().get(5, TimeUnit.SECONDS))
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.status()).isEqualTo(status);
                    assertThat(d.priority()).isEqualTo(priority);
                });
    }

    private void thenTheDescriptionOfTheEngineMentions(String text) throws Exception {
        assertThat(this.engine.describe()).contains(text);
    }

    private void thenNoFolderIsShared() throws Exception {
        assertThat(this.engine.sharedDirectories().get(5, TimeUnit.SECONDS)).isEmpty();
    }

    private void thenTheFirewallRecheckIs(boolean done) {
        assertThat(this.firewallRechecked).isEqualTo(done);
    }

    private void thenThereAreNoKadContacts() throws Exception {
        assertThat(this.engine.kadContacts().get(5, TimeUnit.SECONDS)).isEmpty();
    }

    private void thenThereAreNoKadSearches() throws Exception {
        assertThat(this.engine.kadSearches().get(5, TimeUnit.SECONDS)).isEmpty();
    }

    private void thenNoBackupFolderExists() {
        assertThat(this.profile.data().resolve(ConfigBackup.FOLDER)).doesNotExist();
    }

    private void thenTheSubscriberGetsTheLog(String start) throws InterruptedException {
        assertThat(this.recorder.awaitLog(start)).isPresent();
    }

    private void thenTheFirstEventIsTheFirstLog() {
        assertThat(this.recorder.events.getFirst())
                .isEqualTo(this.recorder.logs().getFirst());
    }

    private void thenTheFirstLogIs(String message) {
        assertThat(this.recorder.logs().getFirst().line().message()).isEqualTo(message);
    }

    private void thenTheOtherSubscriberStillGetsTheLog(String start) throws InterruptedException {
        assertThat(this.recorder.awaitLog(start))
                .as("the others still get everything")
                .isPresent();
    }

    private void thenTheSlowSubscriberGetsOnly(int count) {
        assertThat(this.slow.events).as("only what it asked for").hasSize(count);
    }

    private void thenTheSubscriberFailsWith(Class<? extends Throwable> type) throws Exception {
        assertThat(this.recorder.error.get(5, TimeUnit.SECONDS)).isInstanceOf(type);
    }

    private void thenTheFirstRuleStopsNoCategory() {
        assertThat(this.engine.schedule().rules().getFirst().stopCategory()).isEmpty();
    }

    private void thenTheSecondRuleIs(Schedule.Rule rule) {
        assertThat(this.engine.schedule().rules().get(1)).isEqualTo(rule);
    }

    private void thenTheFirstRuleIsStillInForce() {
        assertThat(this.engine.activeRule())
                .contains(this.engine.schedule().rules().getFirst());
    }

    private void thenTheSavedScheduleIsTheOneOfTheEngine() throws IOException {
        assertThat(ScheduleCodec.load(this.engine.directories().config().resolve(ScheduleCodec.FILE_NAME)))
                .isEqualTo(this.engine.schedule());
    }

    private void thenTheRuleInForceIs(Schedule.Rule rule) {
        assertThat(this.engine.activeRule()).contains(rule);
    }

    // A line of our own marks the end of what was logged before it.
    private void thenTheSchedulerHasLoggedOnly(String... messages) throws InterruptedException {
        this.engine.userLog(LogLine.Level.INFO, "done");
        assertThat(this.recorder.awaitLog("done")).isPresent();
        assertThat(this.recorder.logs())
                .extracting(l -> l.line().message())
                .filteredOn(m -> m.startsWith("Scheduler"))
                .as("only the first rule acted, at the first update")
                .containsExactly(messages);
    }

    private void thenTheCategoryNamesAre(String... names) throws Exception {
        assertThat(this.engine.categories().get(5, TimeUnit.SECONDS))
                .extracting(Category::name)
                .containsExactly(names);
    }

    private void thenTheEventsAreCompleted() throws Exception {
        assertThat(this.recorder.completed.get(5, TimeUnit.SECONDS)).isTrue();
    }

    private void thenTheLastEventIs(Class<? extends EngineEvent> type) {
        assertThat(this.recorder.events).last().isInstanceOf(type);
    }

    private void thenTheOnlyRangeIsDescribedAs(String description) {
        assertThat(this.ranges)
                .singleElement()
                .satisfies(r -> assertThat(r.description()).isEqualTo(description));
    }

    private Engine start(String preferences) throws IOException {
        return Engine.start(new EngineConfig(dirs(preferences)), () -> false);
    }

    private AppDirectories dirs(String preferences) throws IOException {
        AppDirectories dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), false);
        Files.createDirectories(dirs.config());
        int port = FreePorts.tcp();
        Files.writeString(
                dirs.config().resolve(PreferencesCodec.FILE_NAME),
                "net.tcpPort=" + port + "\nnet.kad.enabled=false\n" + preferences);
        return dirs;
    }

    // Holds all day, every day.
    private static Schedule.Rule rule(String name, boolean enabled, OptionalInt stop, OptionalInt resume) {
        return new Schedule.Rule(
                name,
                enabled,
                EnumSet.allOf(DayOfWeek.class),
                LocalTime.MIDNIGHT,
                LocalTime.MIDNIGHT,
                OptionalInt.empty(),
                OptionalInt.empty(),
                OptionalInt.empty(),
                OptionalInt.empty(),
                stop,
                resume);
    }

    /** Takes the events it asks for at the start, and no more. */
    private static final class Recorder implements Flow.Subscriber<EngineEvent> {
        private final long initial;
        private final List<EngineEvent> events = new CopyOnWriteArrayList<>();
        private final CompletableFuture<Throwable> error = new CompletableFuture<>();
        private final CompletableFuture<Boolean> completed = new CompletableFuture<>();

        Recorder(long initial) {
            this.initial = initial;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(this.initial);
        }

        @Override
        public void onNext(EngineEvent item) {
            this.events.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            this.error.complete(throwable);
        }

        @Override
        public void onComplete() {
            this.completed.complete(true);
        }

        List<EngineEvent.Log> logs() {
            return this.events.stream()
                    .filter(EngineEvent.Log.class::isInstance)
                    .map(EngineEvent.Log.class::cast)
                    .toList();
        }

        Optional<LogLine> awaitLog(String start) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                for (EngineEvent.Log log : logs()) {
                    if (log.line().message().startsWith(start)) {
                        return Optional.of(log.line());
                    }
                }
                Thread.sleep(20);
            }
            return Optional.empty();
        }
    }
}
