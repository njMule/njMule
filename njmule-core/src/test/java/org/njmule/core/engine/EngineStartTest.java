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

import java.io.IOException;
import java.net.ServerSocket;
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
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
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
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.config.Schedule;

@Timeout(30)
class EngineStartTest {

    private static final Ed2kLink.FileLink FILM =
            Ed2kLink.FileLink.of("film.mkv", 5000, Hash16.parseHex("a1".repeat(16)));

    @TempDir
    Path dir;

    private NjMuleEngine engine;
    private Lines lines;

    @AfterEach
    void close() {
        if (this.engine != null) {
            this.engine.close();
        }
    }

    @Test
    void shouldHandTheLogOfTheStartToThoseWhoListenLater() throws Exception {
        givenARunningEngine();

        whenSomeoneListens();

        thenTheLogSays("started");
    }

    @Test
    void shouldActOnceOnARuleThatHoldsAtTheStart() throws Exception {
        givenARunningEngine();
        givenTheCategories(1, 2);
        givenTheFilmInCategory(1);
        givenARuleHoldingAllWeekThatStopsCategory("night", 1);

        whenTheEngineIsRestarted();

        thenTheLogSays("started");
        thenTheLogSaysOnce("category 1 stopped");
    }

    @Test
    void shouldNotRunTheActionsOfTheRuleInForceAgainWhenItIsEdited() throws Exception {
        givenARunningEngine();
        givenTheCategories(1);
        givenTheFilmInCategory(1);
        givenARuleHoldingAllWeekThatStopsCategory("night", 1);
        givenTheFilmWasStoppedByTheRule();
        givenTheFilmIsResumedByHand();

        whenTheRuleInForceBecomesARuleThatStopsCategory("late night", 1);

        thenTheFilmIsStillResumed();
    }

    @Test
    void shouldTakeADeletedCategoryOutOfTheRules() throws Exception {
        givenARunningEngine();
        givenTheCategories(1, 2);
        givenADisabledRuleThatStopsAndResumes(2, 1);

        whenTheCategoriesAre(1);

        thenTheRuleStopsNoCategory();
        thenTheRuleResumesCategory(1);
    }

    private void givenARunningEngine() throws IOException {
        this.engine = start();
    }

    private void givenTheCategories(int... ids) throws Exception {
        whenTheCategoriesAre(ids);
    }

    private void givenTheFilmInCategory(int category) throws Exception {
        this.engine.addDownload(FILM).get(5, TimeUnit.SECONDS);
        this.engine.setDownloadCategory(FILM.hash(), category).get(5, TimeUnit.SECONDS);
    }

    private void givenARuleHoldingAllWeekThatStopsCategory(String name, int category) throws Exception {
        whenTheRuleInForceBecomesARuleThatStopsCategory(name, category);
    }

    private void givenADisabledRuleThatStopsAndResumes(int stop, int resume) throws Exception {
        Schedule.Rule rule = new Schedule.Rule(
                "later",
                false,
                EnumSet.allOf(DayOfWeek.class),
                LocalTime.MIDNIGHT,
                LocalTime.MIDNIGHT,
                OptionalInt.empty(),
                OptionalInt.empty(),
                OptionalInt.empty(),
                OptionalInt.empty(),
                OptionalInt.of(stop),
                OptionalInt.of(resume));
        this.engine.updateSchedule(new Schedule(List.of(rule))).get(5, TimeUnit.SECONDS);
    }

    private void givenTheFilmWasStoppedByTheRule() throws Exception {
        assertThat(status(this.engine)).isEqualTo(DownloadInfo.Status.STOPPED);
    }

    private void givenTheFilmIsResumedByHand() throws Exception {
        this.engine.resumeDownload(FILM.hash()).get(5, TimeUnit.SECONDS);
    }

    private void whenSomeoneListens() {
        this.lines = new Lines(this.engine);
    }

    private void whenTheEngineIsRestarted() throws IOException {
        this.engine.close();
        this.engine = start();
        whenSomeoneListens();
    }

    private void whenTheRuleInForceBecomesARuleThatStopsCategory(String name, int category) throws Exception {
        this.engine
                .updateSchedule(new Schedule(List.of(always(name, category))))
                .get(5, TimeUnit.SECONDS);
    }

    private void whenTheCategoriesAre(int... ids) throws Exception {
        this.engine
                .setCategories(
                        Arrays.stream(ids).mapToObj(EngineStartTest::category).toList())
                .get(5, TimeUnit.SECONDS);
    }

    private void thenTheLogSays(String text) throws InterruptedException {
        assertThat(this.lines.await(line -> line.contains(text))).isTrue();
    }

    private void thenTheLogSaysOnce(String text) {
        assertThat(this.lines.lines).filteredOn(line -> line.contains(text)).hasSize(1);
    }

    private void thenTheFilmIsStillResumed() throws Exception {
        assertThat(status(this.engine)).as("resumed by hand").isNotEqualTo(DownloadInfo.Status.STOPPED);
    }

    private void thenTheRuleStopsNoCategory() {
        assertThat(this.engine.schedule().rules().getFirst().stopCategory())
                .as("a new category 2 is not stopped")
                .isEmpty();
    }

    private void thenTheRuleResumesCategory(int category) {
        assertThat(this.engine.schedule().rules().getFirst().resumeCategory()).hasValue(category);
    }

    private NjMuleEngine start() throws IOException {
        AppDirectories dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), false);
        Files.createDirectories(dirs.config());
        Path prefs = dirs.config().resolve("njmule.properties");
        if (!Files.exists(prefs)) {
            int port;
            try (ServerSocket probe = new ServerSocket(0)) {
                port = probe.getLocalPort();
            }
            Files.writeString(prefs, "net.tcpPort=" + port + "\nnet.kad.enabled=false\n");
        }
        return NjMuleEngine.start(new EngineConfig(dirs));
    }

    private static DownloadInfo.Status status(NjMuleEngine engine) throws Exception {
        return engine.downloads().get(5, TimeUnit.SECONDS).stream()
                .filter(d -> d.hash().equals(FILM.hash()))
                .findFirst()
                .orElseThrow()
                .status();
    }

    private static Category category(int id) {
        return new Category(id, "c" + id, Optional.empty(), "", id, DownloadPriority.AUTO, "", false);
    }

    // A rule that holds all week and stops the downloads of one category when it begins.
    private static Schedule.Rule always(String name, int stop) {
        return new Schedule.Rule(
                name,
                true,
                EnumSet.allOf(DayOfWeek.class),
                LocalTime.MIDNIGHT,
                LocalTime.MIDNIGHT,
                OptionalInt.empty(),
                OptionalInt.empty(),
                OptionalInt.empty(),
                OptionalInt.empty(),
                OptionalInt.of(stop),
                OptionalInt.empty());
    }

    /** The log lines an engine publishes. */
    private static final class Lines implements Flow.Subscriber<EngineEvent> {
        private final List<String> lines = new CopyOnWriteArrayList<>();

        Lines(NjMuleEngine engine) {
            engine.events().subscribe(this);
        }

        boolean await(Predicate<String> wanted) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                if (this.lines.stream().anyMatch(wanted)) {
                    return true;
                }
                Thread.sleep(20);
            }
            return false;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(EngineEvent item) {
            if (item instanceof EngineEvent.Log log) {
                this.lines.add(log.line().message());
            }
        }

        @Override
        public void onError(Throwable throwable) {}

        @Override
        public void onComplete() {}
    }
}
