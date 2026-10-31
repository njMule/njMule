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
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
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
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.config.Preferences;
import org.njmule.core.config.Schedule;
import org.njmule.core.persist.PreferencesCodec;
import org.njmule.core.persist.format.ServerMet;
import org.njmule.core.protocol.tag.ServerTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Ipv4;

@Timeout(20)
class EngineTest {

    private static final Ed2kLink.FileLink FILM =
            Ed2kLink.FileLink.of("film.mkv", 5000, Hash16.parseHex("a1".repeat(16)));
    private static final Ed2kLink.FileLink SONG =
            Ed2kLink.FileLink.of("song.mp3", 5000, Hash16.parseHex("a2".repeat(16)));
    private static final Category FILMS =
            new Category(1, "Films", Optional.empty(), "", 1, DownloadPriority.AUTO, "", false);
    private static final Category MUSIC =
            new Category(2, "Music", Optional.empty(), "", 2, DownloadPriority.AUTO, "", false);
    // Holds all week: stops the downloads of category 1 and resumes those of category 2.
    private static final Schedule.Rule NIGHT = new Schedule.Rule(
            "night",
            true,
            EnumSet.allOf(DayOfWeek.class),
            LocalTime.MIDNIGHT,
            LocalTime.MIDNIGHT,
            OptionalInt.empty(),
            OptionalInt.empty(),
            OptionalInt.empty(),
            OptionalInt.empty(),
            OptionalInt.of(1),
            OptionalInt.of(2));
    // Holds all week with an upload limit of 7 KiB/s.
    private static final Schedule.Rule ALWAYS = new Schedule.Rule(
            "always",
            true,
            EnumSet.allOf(DayOfWeek.class),
            LocalTime.MIDNIGHT,
            LocalTime.MIDNIGHT,
            OptionalInt.of(7),
            OptionalInt.empty(),
            OptionalInt.empty(),
            OptionalInt.empty());

    @TempDir
    Path root;

    private final Recorder recorder = new Recorder();
    private Engine engine;
    private Engine second;
    private AppDirectories dirs;
    private Path met;
    private Preferences updated;
    private int added;
    private boolean removed;
    private EngineStatus status;
    private Throwable exception;

    private static final class Recorder implements Flow.Subscriber<EngineEvent> {
        private final List<EngineEvent> events = new CopyOnWriteArrayList<>();
        private final CountDownLatch completed = new CountDownLatch(1);

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(EngineEvent item) {
            this.events.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            this.completed.countDown();
        }

        @Override
        public void onComplete() {
            this.completed.countDown();
        }
    }

    private NjMuleEngine start() throws IOException {
        return NjMuleEngine.start(new EngineConfig(AppDirectories.underRoot(this.root, false)));
    }

    @AfterEach
    void close() {
        if (this.engine != null) {
            this.engine.close();
        }
        if (this.second != null) {
            this.second.close();
        }
    }

    @Test
    void shouldCreateTheLayoutAndSaveTheDefaultPreferencesAtTheStart() throws IOException {
        whenTheEngineStarts();

        thenTheEngineRuns();
        thenTheLogFolderExists();
        thenThePreferencesAreSaved();
        thenThePreferencesAreTheDefaults();
    }

    @Test
    void shouldRunOnThisComputer() throws IOException {
        whenTheEngineStarts();

        thenItRunsOnThisComputer();
    }

    @Test
    void shouldWaitToBeSetUpWhenItsStartCreatedTheConfiguration() throws Exception {
        whenTheEngineStarts();

        thenTheSetupIsPending(true);
    }

    @Test
    void shouldStillWaitToBeSetUpAfterARestart() throws Exception {
        givenARunningEngine();

        whenTheEngineIsRestarted();

        thenTheSetupIsPending(true);
    }

    @Test
    void shouldNoLongerWaitToBeSetUpOnceTheSetupIsDone() throws Exception {
        givenARunningEngine();
        givenTheSetupIsDone();

        whenTheEngineIsRestarted();

        thenTheSetupIsPending(false);
    }

    @Test
    void shouldNotWaitToBeSetUpOnAConfigurationOfBefore() throws Exception {
        givenPreferencesSavedBefore();

        whenTheEngineStarts();

        thenTheSetupIsPending(false);
    }

    @Test
    void shouldPublishAChangeOfThePreferencesOnceAndTheStopLast() throws Exception {
        givenARunningEngine();
        givenSomeoneListens();
        givenTheNickIsChangedTo("renamed");
        givenTheSamePreferencesAreSetAgain();

        whenTheEngineIsClosed();

        thenTheEventsEnd();
        thenTheChangesOfThePreferencesPublishedAre(1);
        thenTheLastEventIs(EngineEvent.Stopping.class);
    }

    @Test
    void shouldKeepAChangedNickAfterARestart() throws Exception {
        givenARunningEngine();
        givenTheNickIsChangedTo("renamed");

        whenTheEngineIsRestarted();

        thenTheNickIs("renamed");
    }

    @Test
    void shouldRefuseAnInvalidChangeAndKeepThePreviousPreferences() throws IOException {
        givenARunningEngine();

        whenTheTcpPortIsChangedTo(0);

        thenExceptionOccurred(ExecutionException.class, IllegalArgumentException.class);
        thenThePreferencesAreTheDefaults();
    }

    @Test
    void shouldAddTheServersOfAServerMetWithTheirNamesAndPriorities() throws Exception {
        givenAServerMetOfTwoServers();
        givenARunningEngine();

        whenTheServersOfTheFileAreAdded();

        thenTheServersAddedAre(2);
        thenTheServerNamesAre("One", "81.2.3.5:4242");
        thenTheServerPrioritiesAre(ServerInfo.Priority.HIGH, ServerInfo.Priority.NORMAL);
    }

    @Test
    void shouldRemoveAServerOnlyOnce() throws Exception {
        givenAServerMetOfTwoServers();
        givenARunningEngine();
        givenTheServersOfTheFileWereAdded(2);
        givenTheServerWasRemoved("81.2.3.4:4661");

        whenTheServerIsRemoved("81.2.3.4:4661");

        thenNothingIsRemoved();
        thenTheServerCountIs(1);
    }

    @Test
    void shouldTellAStatusWithoutServerTrafficOrUploads() throws Exception {
        givenAServerMetOfTwoServers();
        givenARunningEngine();
        givenTheServersOfTheFileWereAdded(2);
        givenTheServerWasRemoved("81.2.3.4:4661");

        whenTheEngineStatusIsAsked();

        thenTheStatusHasNoServerNoIdNoDownloadAndNoTraffic();
        thenThereAreNoUploads();
    }

    @Test
    void shouldKeepServersKnownByHostNameAndActOnThemByIt() throws Exception {
        givenAServerMetOfServersKnownByName("localhost", "b.example");
        givenARunningEngine();
        givenTheServersOfTheFileWereAdded(2);
        givenTheServerAddressesAre("localhost:4661", "b.example:4661");
        givenTheServerIsMadeStatic("localhost:4661");
        givenTheServerPriorityIsSet("localhost:4661", ServerInfo.Priority.LOW);

        whenTheServerIsRemoved("localhost:4661");

        thenTheServerIsRemoved();
        thenTheServerAddressesAre("b.example:4661");
    }

    @Test
    void shouldStopTheDownloadsOfOneCategoryAndResumeThoseOfAnotherByARule() throws Exception {
        givenARunningEngine();
        givenTheCategories(FILMS, MUSIC);
        givenTheDownloadInCategory(FILM, 1);
        givenTheDownloadInCategory(SONG, 2);
        givenTheDownloadIsPaused(SONG);

        whenTheScheduleHolds(NIGHT);

        thenTheStatusOfTheDownloadIs(FILM, DownloadInfo.Status.STOPPED);
        thenTheStatusOfTheDownloadIs(SONG, DownloadInfo.Status.DOWNLOADING);
    }

    @Test
    void shouldApplyTheRuleInForceOverThePreferences() throws Exception {
        givenARunningEngine();
        givenNoRuleIsInForce();

        whenTheScheduleHolds(ALWAYS);

        thenTheRuleInForceIs(ALWAYS);
        thenTheRulesAre(ALWAYS);
        thenTheEffectiveUploadLimitIs(7);
        thenTheUploadLimitOfThePreferencesIs(0);
    }

    @Test
    void shouldKeepTheRuleInForceWhenThePreferencesChange() throws Exception {
        givenARunningEngine();
        givenTheScheduleHolds(ALWAYS);

        whenTheNickIsChangedTo("other");

        thenTheEffectiveNickIs("other");
        thenTheEffectiveUploadLimitIs(7);
    }

    @Test
    void shouldLiftTheRuleKeptAcrossARestartWhenTheScheduleIsEmptied() throws Exception {
        givenARunningEngine();
        givenTheScheduleHolds(ALWAYS);
        givenTheEngineWasRestarted();
        givenTheRuleInForceIsStill(ALWAYS);

        whenTheScheduleIsEmptied();

        thenNoRuleIsInForce();
        thenTheEffectiveUploadLimitIs(0);
    }

    @Test
    void shouldCountTheFirstSession() throws Exception {
        whenTheEngineStarts();

        thenTheSessionsAre(1);
        thenTheTotalDownloadedIs(0);
        thenTheTotalTimeIsThatOfTheSession();
    }

    @Test
    void shouldCountTheSessionsAcrossRestarts() throws Exception {
        givenARunningEngine();

        whenTheEngineIsRestarted();

        thenTheSessionsAre(2);
    }

    @Test
    void shouldTakeOnlyTheValidTotalsOfTheStatistics() throws Exception {
        givenARunningEngine();
        givenTheEngineWasRestarted();
        givenTheEngineWasClosed();
        givenTheStatistics("downloaded=12\nuploaded=x\nsessions=-4\n");

        whenTheEngineStarts();

        thenTheTotalDownloadedIs(12);
        thenTheTotalUploadedIs(0);
        thenTheSessionsAre(1);
    }

    @Test
    void shouldSaveTheServerListAddressesInAddressesDat() throws Exception {
        givenARunningEngine();
        givenNoServerListAddressIsKnown();
        givenTheServerListAddresses("https://example.org/server.met");

        whenTheEngineIsClosed();

        thenTheConfigurationHasTheFile("addresses.dat");
    }

    @Test
    void shouldReadTheServerListAddressesBackAtTheNextStart() throws Exception {
        givenARunningEngine();
        givenTheServerListAddresses("https://example.org/server.met");
        givenTheEngineWasClosed();

        whenTheEngineStarts();

        thenTheServerListAddressesAre("https://example.org/server.met");
    }

    @Test
    void shouldStayClosedWhenClosedAgain() throws IOException {
        givenARunningEngine();
        givenTheEngineWasClosed();

        whenTheEngineIsClosed();

        thenTheEngineDoesNotRun();
    }

    @Test
    void shouldRefuseASecondEngineOnTheSameConfiguration() throws IOException {
        givenAnEngineOnTheConfigurationIn("locked");

        whenASecondEngineStartsOnTheSameConfiguration();

        thenExceptionOccurred(IOException.class, "already running");
        thenTheEngineRuns();
    }

    @Test
    void shouldLetTheNextEngineStartOnceTheFirstIsClosed() throws IOException {
        givenAnEngineOnTheConfigurationIn("locked");
        givenASecondEngineWasRefused();
        givenTheEngineWasClosed();

        whenASecondEngineStartsOnTheSameConfiguration();

        thenNoExceptionOccurred();
    }

    private void givenARunningEngine() throws IOException {
        whenTheEngineStarts();
    }

    private void givenAnEngineOnTheConfigurationIn(String folder) throws IOException {
        this.dirs = AppDirectories.underRoot(this.root.resolve(folder), false);
        this.engine = Engine.start(new EngineConfig(this.dirs), () -> false);
    }

    private void givenTheSetupIsDone() throws Exception {
        this.engine.finishSetup().get(5, TimeUnit.SECONDS);
    }

    private void givenPreferencesSavedBefore() throws IOException {
        Path config = AppDirectories.underRoot(this.root, false).config();
        Files.createDirectories(config);
        PreferencesCodec.save(config.resolve(PreferencesCodec.FILE_NAME), Preferences.defaults());
    }

    private void thenTheSetupIsPending(boolean pending) throws Exception {
        assertThat(this.engine.setupPending().get(5, TimeUnit.SECONDS)).isEqualTo(pending);
    }

    private void givenSomeoneListens() {
        this.engine.events().subscribe(this.recorder);
    }

    private void givenTheNickIsChangedTo(String nick) throws Exception {
        whenTheNickIsChangedTo(nick);
        assertThat(this.updated.nick()).isEqualTo(nick);
        assertThat(this.engine.preferences()).isEqualTo(this.updated);
    }

    // A no-op change does not publish again.
    private void givenTheSamePreferencesAreSetAgain() throws Exception {
        this.engine.updatePreferences(p -> p).get(5, TimeUnit.SECONDS);
    }

    private void givenTheEngineWasClosed() {
        whenTheEngineIsClosed();
    }

    private void givenTheEngineWasRestarted() throws IOException {
        whenTheEngineIsRestarted();
    }

    private void givenAServerMetOfTwoServers() throws IOException {
        this.met = this.root.resolve("other-server.met");
        Files.write(
                this.met,
                ServerMet.write(List.of(
                        new ServerMet.Server(
                                Ipv4.of(81, 2, 3, 4),
                                4661,
                                List.of(
                                        new Tag.StringTag(TagName.id(ServerTag.SERVERNAME), "One"),
                                        new Tag.IntTag(TagName.id(ServerTag.PREFERENCE), 1))),
                        new ServerMet.Server(Ipv4.of(81, 2, 3, 5), 4242, List.of()))));
    }

    // localhost resolves without any network: acting on it by name must not resolve it.
    private void givenAServerMetOfServersKnownByName(String host, String otherHost) throws IOException {
        this.met = this.root.resolve("named-server.met");
        Files.write(
                this.met,
                ServerMet.write(List.of(
                        new ServerMet.Server(0, 4661, List.of(new Tag.StringTag(TagName.id(ServerTag.DYNIP), host))),
                        new ServerMet.Server(
                                0, 4661, List.of(new Tag.StringTag(TagName.id(ServerTag.DYNIP), otherHost))))));
    }

    private void givenTheServersOfTheFileWereAdded(int count) throws Exception {
        whenTheServersOfTheFileAreAdded();
        assertThat(this.added).isEqualTo(count);
    }

    private void givenTheServerWasRemoved(String address) throws Exception {
        whenTheServerIsRemoved(address);
        assertThat(this.removed).isTrue();
    }

    private void givenTheServerAddressesAre(String... addresses) throws Exception {
        thenTheServerAddressesAre(addresses);
    }

    private void givenTheServerIsMadeStatic(String address) throws Exception {
        assertThat(this.engine
                        .setStaticServer(ServerAddress.parse(address), true)
                        .get(5, TimeUnit.SECONDS))
                .isTrue();
    }

    private void givenTheServerPriorityIsSet(String address, ServerInfo.Priority priority) throws Exception {
        assertThat(this.engine
                        .setServerPriority(ServerAddress.parse(address), priority)
                        .get(5, TimeUnit.SECONDS))
                .isTrue();
    }

    private void givenTheCategories(Category... categories) throws Exception {
        this.engine.setCategories(Arrays.asList(categories)).get(5, TimeUnit.SECONDS);
    }

    private void givenTheDownloadInCategory(Ed2kLink.FileLink link, int category) throws Exception {
        this.engine.addDownload(link).get(5, TimeUnit.SECONDS);
        this.engine.setDownloadCategory(link.hash(), category).get(5, TimeUnit.SECONDS);
    }

    private void givenTheDownloadIsPaused(Ed2kLink.FileLink link) throws Exception {
        this.engine.pauseDownload(link.hash()).get(5, TimeUnit.SECONDS);
    }

    private void givenNoRuleIsInForce() {
        thenNoRuleIsInForce();
    }

    private void givenTheScheduleHolds(Schedule.Rule rule) throws Exception {
        whenTheScheduleHolds(rule);
    }

    private void givenTheRuleInForceIsStill(Schedule.Rule rule) {
        thenTheRuleInForceIs(rule);
    }

    private void givenTheStatistics(String content) throws IOException {
        Files.writeString(this.root.resolve("config").resolve("statistics.properties"), content);
    }

    private void givenNoServerListAddressIsKnown() throws Exception {
        assertThat(this.engine.serverListAddresses().get(5, TimeUnit.SECONDS)).isEmpty();
    }

    private void givenTheServerListAddresses(String... addresses) throws Exception {
        this.engine
                .setServerListAddresses(
                        Arrays.stream(addresses).map(URI::create).toList())
                .get(5, TimeUnit.SECONDS);
    }

    private void givenASecondEngineWasRefused() {
        whenASecondEngineStartsOnTheSameConfiguration();
        thenExceptionOccurred(IOException.class, "already running");
    }

    private void whenTheEngineStarts() throws IOException {
        this.engine = (Engine) start();
    }

    private void whenTheEngineIsClosed() {
        this.engine.close();
    }

    private void whenTheEngineIsRestarted() throws IOException {
        this.engine.close();
        whenTheEngineStarts();
    }

    private void whenTheNickIsChangedTo(String nick) throws Exception {
        this.updated = this.engine
                .updatePreferences(p -> p.toBuilder().nick(nick).build())
                .get(5, TimeUnit.SECONDS);
    }

    private void whenTheTcpPortIsChangedTo(int port) {
        this.exception = catchThrowable(() -> this.engine
                .updatePreferences(p -> p.toBuilder().tcpPort(port).build())
                .get());
    }

    private void whenTheServersOfTheFileAreAdded() throws Exception {
        this.added = this.engine.addServers(this.met).get(5, TimeUnit.SECONDS);
    }

    private void whenTheServerIsRemoved(String address) throws Exception {
        this.removed = this.engine.removeServer(ServerAddress.parse(address)).get(5, TimeUnit.SECONDS);
    }

    private void whenTheEngineStatusIsAsked() throws Exception {
        this.status = this.engine.status().get(5, TimeUnit.SECONDS);
    }

    private void whenTheScheduleHolds(Schedule.Rule rule) throws Exception {
        this.engine.updateSchedule(new Schedule(List.of(rule))).get(5, TimeUnit.SECONDS);
    }

    private void whenTheScheduleIsEmptied() throws Exception {
        this.engine.updateSchedule(Schedule.EMPTY).get(5, TimeUnit.SECONDS);
    }

    private void whenASecondEngineStartsOnTheSameConfiguration() {
        this.exception = catchThrowable(() -> this.second = Engine.start(new EngineConfig(this.dirs), () -> false));
    }

    private void thenTheEngineRuns() {
        assertThat(this.engine.isRunning()).isTrue();
    }

    private void thenItRunsOnThisComputer() {
        assertThat(this.engine.onThisComputer()).isTrue();
    }

    private void thenTheEngineDoesNotRun() {
        assertThat(this.engine.isRunning()).isFalse();
    }

    private void thenTheLogFolderExists() {
        assertThat(this.engine.directories().logs()).isDirectory();
    }

    private void thenThePreferencesAreSaved() {
        assertThat(this.engine.directories().config().resolve(PreferencesCodec.FILE_NAME))
                .exists();
    }

    private void thenThePreferencesAreTheDefaults() {
        assertThat(this.engine.preferences()).isEqualTo(Preferences.defaults());
    }

    private void thenTheEventsEnd() throws InterruptedException {
        assertThat(this.recorder.completed.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private void thenTheChangesOfThePreferencesPublishedAre(int count) {
        assertThat(this.recorder.events)
                .filteredOn(EngineEvent.PreferencesChanged.class::isInstance)
                .hasSize(count);
    }

    private void thenTheLastEventIs(Class<? extends EngineEvent> type) {
        assertThat(this.recorder.events).last().isInstanceOf(type);
    }

    private void thenTheNickIs(String nick) {
        assertThat(this.engine.preferences().nick()).isEqualTo(nick);
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type, Class<? extends Throwable> cause) {
        assertThat(this.exception).isInstanceOf(type).hasCauseInstanceOf(cause);
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type, String messagePart) {
        assertThat(this.exception).isInstanceOf(type).hasMessageContaining(messagePart);
    }

    private void thenNoExceptionOccurred() {
        assertThat(this.exception).isNull();
    }

    private void thenTheServersAddedAre(int count) {
        assertThat(this.added).isEqualTo(count);
    }

    private void thenTheServerNamesAre(String... names) throws Exception {
        assertThat(this.engine.servers().get(5, TimeUnit.SECONDS))
                .extracting(ServerInfo::name)
                .containsExactly(names);
    }

    private void thenTheServerPrioritiesAre(ServerInfo.Priority... priorities) throws Exception {
        assertThat(this.engine.servers().get(5, TimeUnit.SECONDS))
                .extracting(ServerInfo::priority)
                .containsExactly(priorities);
    }

    private void thenTheServerAddressesAre(String... addresses) throws Exception {
        assertThat(this.engine.servers().get(5, TimeUnit.SECONDS))
                .extracting(ServerInfo::address)
                .containsExactly(addresses);
    }

    private void thenTheServerIsRemoved() {
        assertThat(this.removed).isTrue();
    }

    private void thenNothingIsRemoved() {
        assertThat(this.removed).isFalse();
    }

    private void thenTheServerCountIs(int count) throws Exception {
        assertThat(this.engine.servers().get(5, TimeUnit.SECONDS)).hasSize(count);
    }

    private void thenTheStatusHasNoServerNoIdNoDownloadAndNoTraffic() {
        assertThat(this.status.server()).isEmpty();
        assertThat(this.status.clientId()).isZero();
        assertThat(this.status.downloads()).isZero();
        assertThat(this.status.downloadBytesPerSecond()).isZero();
    }

    private void thenThereAreNoUploads() throws Exception {
        assertThat(this.engine.uploads().get(5, TimeUnit.SECONDS)).isEmpty();
    }

    private void thenTheStatusOfTheDownloadIs(Ed2kLink.FileLink link, DownloadInfo.Status status) throws Exception {
        assertThat(this.engine.downloads().get(5, TimeUnit.SECONDS))
                .filteredOn(d -> d.hash().equals(link.hash()))
                .singleElement()
                .extracting(DownloadInfo::status)
                .isEqualTo(status);
    }

    private void thenNoRuleIsInForce() {
        assertThat(this.engine.activeRule()).isEmpty();
    }

    private void thenTheRuleInForceIs(Schedule.Rule rule) {
        assertThat(this.engine.activeRule()).contains(rule);
    }

    private void thenTheRulesAre(Schedule.Rule... rules) {
        assertThat(this.engine.schedule().rules()).containsExactly(rules);
    }

    private void thenTheEffectiveUploadLimitIs(int kibps) {
        assertThat(this.engine.effective().maxUploadKiBps()).isEqualTo(kibps);
    }

    private void thenTheUploadLimitOfThePreferencesIs(int kibps) {
        assertThat(this.engine.preferences().maxUploadKiBps()).isEqualTo(kibps);
    }

    private void thenTheEffectiveNickIs(String nick) {
        assertThat(this.engine.effective().nick()).isEqualTo(nick);
    }

    private void thenTheSessionsAre(int count) throws Exception {
        assertThat(this.engine.status().get(5, TimeUnit.SECONDS).sessions()).isEqualTo(count);
    }

    private void thenTheTotalDownloadedIs(long bytes) throws Exception {
        assertThat(this.engine.status().get(5, TimeUnit.SECONDS).totalDownloaded())
                .isEqualTo(bytes);
    }

    private void thenTheTotalUploadedIs(long bytes) throws Exception {
        assertThat(this.engine.status().get(5, TimeUnit.SECONDS).totalUploaded())
                .isEqualTo(bytes);
    }

    private void thenTheTotalTimeIsThatOfTheSession() throws Exception {
        EngineStatus status = this.engine.status().get(5, TimeUnit.SECONDS);
        assertThat(status.totalSeconds()).isEqualTo(status.sessionSeconds());
    }

    private void thenTheConfigurationHasTheFile(String name) {
        assertThat(this.root.resolve("config").resolve(name)).exists();
    }

    private void thenTheServerListAddressesAre(String... addresses) throws Exception {
        assertThat(this.engine.serverListAddresses().get(5, TimeUnit.SECONDS))
                .containsExactly(Arrays.stream(addresses).map(URI::create).toArray(URI[]::new));
    }
}
