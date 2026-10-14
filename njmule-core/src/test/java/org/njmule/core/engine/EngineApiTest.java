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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalTime;
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

    @TempDir
    Path dir;

    private Engine engine;
    private final Ed2kLink.FileLink song = Ed2kLink.FileLink.of("song.mp3", 5000, Hash16.parseHex("f4".repeat(16)));
    private List<Object> swapAnswers;

    @AfterEach
    void closeEngine() {
        if (this.engine != null) {
            this.engine.close();
        }
    }

    @Test
    void shouldSwapSourcesAndTakeThemByItselfThroughTheEngine() throws Exception {
        this.givenADownloadWithoutSources();

        this.whenEverySwapIsAskedForTheDownload();

        this.thenTheSwapAnswersAre(0, 0, true, false);
        this.thenTheDownloadTakesA4afSourcesByItself();
    }

    private void givenADownloadWithoutSources() throws Exception {
        this.engine = this.start("");
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
    void listsAreNeverFetchedFromThisMachine() throws Exception {
        URI local = URI.create("http://127.0.0.1:9/list");
        try (Engine engine = this.start("")) {
            for (CompletableFuture<?> fetch :
                    List.of(engine.addServers(local), engine.addKadContacts(local), engine.updateIpFilter(local))) {
                assertThatThrownBy(() -> fetch.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .rootCause()
                        .isInstanceOf(IOException.class)
                        .hasMessageContaining("local or private");
            }
        }
    }

    @Test
    void theServerListsAreUpdatedAtTheStartWhenThePreferencesAskForIt() throws Exception {
        AppDirectories dirs = this.dirs("server.updateAtStart=true\n");
        Files.writeString(
                dirs.config().resolve("addresses.dat"),
                "http://127.0.0.1:9/server.met\nftp://lists.example/server.met\nhttp://bad host/server.met\n",
                StandardCharsets.UTF_8);

        try (Engine engine = Engine.start(new EngineConfig(dirs), () -> false)) {
            assertThat(engine.serverListAddresses().get(5, TimeUnit.SECONDS))
                    .as("only web addresses")
                    .containsExactly(URI.create("http://127.0.0.1:9/server.met"));
            Recorder recorder = new Recorder(Long.MAX_VALUE);
            engine.events().subscribe(recorder);

            assertThat(recorder.awaitLog("Cannot update the server list from 127.0.0.1"))
                    .hasValueSatisfying(l -> assertThat(l.level()).isEqualTo(LogLine.Level.WARNING));
        }
    }

    @Test
    void spamIsMarkedThroughTheEngine() throws Exception {
        Hash16 hash = Hash16.parseHex("b2".repeat(16));
        try (Engine engine = this.start("")) {
            engine.markSpam(List.of(new SearchResult(hash, "spam.exe", 10, 1, 0, Optional.empty())), true)
                    .get(5, TimeUnit.SECONDS);

            assertThat(engine.spamMarks().get(5, TimeUnit.SECONDS).spam()).containsExactly(hash);
        }
    }

    @Test
    void aBanIsLiftedOnlyFromAnAddress() throws Exception {
        try (Engine engine = this.start("")) {
            assertThatThrownBy(() -> engine.unban("not an address").get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aDownloadIsStoppedPrioritisedAndPreviewedThroughTheEngine() throws Exception {
        Ed2kLink.FileLink film = Ed2kLink.FileLink.of("film.mkv", 5000, Hash16.parseHex("f3".repeat(16)));
        try (Engine engine = this.start("")) {
            engine.addDownload(film).get(5, TimeUnit.SECONDS);

            assertThat(engine.setDownloadPriority(film.hash(), DownloadPriority.LOW)
                            .get(5, TimeUnit.SECONDS))
                    .isTrue();
            assertThat(engine.setDownloadPreview(film.hash(), true).get(5, TimeUnit.SECONDS))
                    .isTrue();
            assertThat(engine.downloadData(film.hash()).get(5, TimeUnit.SECONDS))
                    .isEmpty();
            assertThat(engine.stopDownload(film.hash()).get(5, TimeUnit.SECONDS))
                    .isTrue();

            assertThat(engine.downloads().get(5, TimeUnit.SECONDS))
                    .singleElement()
                    .satisfies(d -> {
                        assertThat(d.status()).isEqualTo(DownloadInfo.Status.STOPPED);
                        assertThat(d.priority()).isEqualTo(DownloadPriority.LOW);
                    });
            assertThat(engine.describe()).contains("film.mkv");
        }
    }

    @Test
    void foldersAreSharedAndUnsharedThroughTheEngine() throws Exception {
        Path films = Files.createDirectories(this.dir.resolve("films"));
        try (Engine engine = this.start("")) {
            engine.shareDirectory(films).get(5, TimeUnit.SECONDS);
            assertThat(engine.sharedDirectories().get(5, TimeUnit.SECONDS))
                    .extracting(SharedDirectory::path)
                    .containsExactly(films);

            engine.unshareDirectory(films).get(5, TimeUnit.SECONDS);

            assertThat(engine.sharedDirectories().get(5, TimeUnit.SECONDS)).isEmpty();
        }
    }

    @Test
    void kadCommandsNeedKadRunning() throws Exception {
        try (Engine engine = this.start("")) {
            assertThat(engine.recheckKadFirewall().get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(engine.kadContacts().get(5, TimeUnit.SECONDS)).isEmpty();
            assertThat(engine.kadSearches().get(5, TimeUnit.SECONDS)).isEmpty();
            assertThatThrownBy(() -> engine.kadBootstrap(new ServerAddress("127.0.0.1", 4672))
                            .get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void aHostThatCannotBeResolvedFailsTheHello() throws Exception {
        try (Engine engine = this.start("")) {
            // Not even looked up: no name server is asked.
            assertThatThrownBy(
                            () -> engine.hello(new ServerAddress("[bad", 4662)).get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(UnknownHostException.class);
        }
    }

    @Test
    void noCopyOfTheConfigurationIsKeptWhenNoneIsWanted() throws Exception {
        AppDirectories dirs = this.dirs("backup.copies=0\n");
        Engine.start(new EngineConfig(dirs), () -> false).close();

        assertThat(dirs.data().resolve(ConfigBackup.FOLDER)).doesNotExist();
    }

    @Test
    void aStartThatFailsLetsGoOfTheConfiguration() throws Exception {
        AppDirectories dirs = this.dirs("");
        Path notAFolder = Files.writeString(this.dir.resolve("not-a-folder"), "x");
        Path prefs = dirs.config().resolve(PreferencesCodec.FILE_NAME);
        Preferences saved = PreferencesCodec.load(prefs);
        PreferencesCodec.save(
                prefs, saved.toBuilder().tempDir(Optional.of(notAFolder)).build());

        assertThatThrownBy(() -> Engine.start(new EngineConfig(dirs), () -> false))
                .isInstanceOf(IOException.class);

        PreferencesCodec.save(prefs, saved);
        Engine.start(new EngineConfig(dirs), () -> false).close();
    }

    @Test
    void theLogIsReplayedUpToItsLimitAndASlowSubscriberMissesEvents() throws Exception {
        try (Engine engine = this.start("")) {
            engine.userLog(LogLine.Level.DEBUG, "a detail");
            engine.userLog(LogLine.Level.ERROR, "a failure");
            for (int i = 0; i < 150; i++) {
                engine.userLog(LogLine.Level.INFO, "line " + i);
            }

            Recorder late = new Recorder(Long.MAX_VALUE);
            engine.events().subscribe(late);
            assertThat(late.awaitLog("line 149")).isPresent();
            assertThat(late.events.getFirst()).isEqualTo(late.logs().getFirst());
            assertThat(late.logs().getFirst().line().message()).isEqualTo("line 50");

            Recorder slow = new Recorder(1);
            engine.events().subscribe(slow);
            // In steps the others can follow: any subscriber that falls a buffer behind misses events.
            for (int i = 0; i < 3_000; i++) {
                engine.userLog(LogLine.Level.INFO, "more " + i);
                if (i % 500 == 499) {
                    assertThat(late.awaitLog("more " + i)).isPresent();
                }
            }
            assertThat(late.awaitLog("more 2999"))
                    .as("the others still get everything")
                    .isPresent();
            assertThat(slow.events).as("only what it asked for").hasSize(1);

            Recorder wrong = new Recorder(0);
            engine.events().subscribe(wrong);
            assertThat(wrong.error.get(5, TimeUnit.SECONDS)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rulesLetGoOfDeletedCategoriesAndTheActiveRuleStaysActive() throws Exception {
        Schedule.Rule stopFilms = rule("films off", true, OptionalInt.of(1), OptionalInt.empty());
        Schedule.Rule resumeMusic = rule("music on", false, OptionalInt.empty(), OptionalInt.of(2));
        try (Engine engine = this.start("")) {
            engine.setCategories(List.of(FILMS, MUSIC)).get(5, TimeUnit.SECONDS);
            engine.updateSchedule(new Schedule(List.of(stopFilms, resumeMusic))).get(5, TimeUnit.SECONDS);
            assertThat(engine.activeRule()).contains(stopFilms);

            engine.setCategories(List.of(MUSIC)).get(5, TimeUnit.SECONDS);

            Schedule.Rule kept = engine.schedule().rules().getFirst();
            assertThat(kept.stopCategory()).isEmpty();
            assertThat(engine.schedule().rules().get(1)).isEqualTo(resumeMusic);
            assertThat(engine.activeRule()).contains(kept);
            assertThat(ScheduleCodec.load(engine.directories().config().resolve(ScheduleCodec.FILE_NAME)))
                    .isEqualTo(engine.schedule());
        }
    }

    @Test
    void aRuleEditedWhileItHoldsGoesOnWithoutActingAgain() throws Exception {
        Schedule.Rule night = rule("night", true, OptionalInt.empty(), OptionalInt.empty());
        Schedule.Rule renamed = rule("late night", true, OptionalInt.empty(), OptionalInt.empty());
        try (Engine engine = this.start("")) {
            engine.updateSchedule(new Schedule(List.of(night))).get(5, TimeUnit.SECONDS);
            Recorder recorder = new Recorder(Long.MAX_VALUE);
            engine.events().subscribe(recorder);

            engine.updateSchedule(new Schedule(List.of(night))).get(5, TimeUnit.SECONDS);
            engine.updateSchedule(new Schedule(List.of(renamed))).get(5, TimeUnit.SECONDS);

            assertThat(engine.activeRule()).contains(renamed);
            engine.userLog(LogLine.Level.INFO, "done");
            assertThat(recorder.awaitLog("done")).isPresent();
            assertThat(recorder.logs())
                    .extracting(l -> l.line().message())
                    .filteredOn(m -> m.startsWith("Scheduler"))
                    .as("only the first rule acted, at the first update")
                    .containsExactly("Scheduler: \"night\" applies now");
        }
    }

    @Test
    void aScheduleThatCannotBeSavedIsRefused() throws Exception {
        try (Engine engine = this.start("")) {
            Path tmp = engine.directories().config().resolve(ScheduleCodec.FILE_NAME + ".tmp");
            Files.createDirectories(tmp);
            Files.writeString(tmp.resolve("keep"), "x");

            assertThatThrownBy(() -> engine.updateSchedule(Schedule.EMPTY).get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(UncheckedIOException.class);
        }
    }

    @Test
    void theCategoriesOfAnImportAreTakenWhenWeHaveNone() throws Exception {
        SyntheticEmuleProfile emule = SyntheticEmuleProfile.create(this.dir.resolve("emule"));
        Files.writeString(emule.config.resolve("Category.ini"), "[Cat#1]\r\nTitle=Films\r\n");
        try (Engine engine = this.start("")) {
            engine.importFromEmule(emule.config, new ImportOptions(false, false, false))
                    .get(30, TimeUnit.SECONDS);

            assertThat(engine.categories().get(5, TimeUnit.SECONDS))
                    .extracting(Category::name)
                    .containsExactly("Films");
        }
    }

    @Test
    void kadCommandsGoToARunningKad() throws Exception {
        int port;
        try (DatagramSocket probe = new DatagramSocket(0)) {
            port = probe.getLocalPort();
        }
        try (Engine engine = this.start("net.kad.enabled=true\nnet.udpPort=" + port + "\n")) {
            assertThat(engine.kad().get(5, TimeUnit.SECONDS)).isPresent();

            // A bootstrap request to a closed port on this machine.
            engine.kadBootstrap(new ServerAddress("127.0.0.1", port == 65_535 ? port - 1 : port + 1))
                    .get(5, TimeUnit.SECONDS);

            assertThat(engine.recheckKadFirewall().get(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void aBackupThatCannotBeWrittenLeavesTheWayClear() throws Exception {
        AppDirectories dirs = this.dirs("");
        Files.createDirectories(dirs.data());
        Path blocked = Files.writeString(dirs.data().resolve(ConfigBackup.FOLDER), "not a folder");

        Engine.start(new EngineConfig(dirs), () -> false).close();

        assertThat(blocked).isRegularFile();
        Engine.start(new EngineConfig(dirs), () -> false).close();
    }

    @Test
    void whatIsLoggedAfterTheEndGoesNowhere() throws Exception {
        Engine engine = this.start("");
        Recorder recorder = new Recorder(Long.MAX_VALUE);
        engine.events().subscribe(recorder);
        engine.close();

        engine.userLog(LogLine.Level.INFO, "too late");

        assertThat(recorder.completed.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(recorder.events).last().isInstanceOf(EngineEvent.Stopping.class);
    }

    @Test
    void anIpFilterAtAnAddressWithoutAPathIsReadInEitherForm() {
        byte[] data = "1.2.3.4 - 1.2.3.5 , 100 , one\n".getBytes(StandardCharsets.US_ASCII);

        List<IpFilterDat.Range> ranges = Engine.ipRanges(data, URI.create("urn:lists:ipfilter"));

        assertThat(ranges)
                .singleElement()
                .satisfies(r -> assertThat(r.description()).isEqualTo("one"));
    }

    private Engine start(String preferences) throws IOException {
        return Engine.start(new EngineConfig(this.dirs(preferences)), () -> false);
    }

    private AppDirectories dirs(String preferences) throws IOException {
        AppDirectories dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), false);
        Files.createDirectories(dirs.config());
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
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
                for (EngineEvent.Log log : this.logs()) {
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
