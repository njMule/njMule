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
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.EngineStatus;
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

    @TempDir
    Path root;

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

    @Test
    void startCreatesTheLayoutAndPersistsDefaultPreferences() throws IOException {
        try (NjMuleEngine engine = this.start()) {
            assertThat(engine.isRunning()).isTrue();
            assertThat(engine.directories().logs()).isDirectory();
            assertThat(engine.directories().config().resolve(PreferencesCodec.FILE_NAME))
                    .exists();
            assertThat(engine.preferences()).isEqualTo(Preferences.defaults());
        }
    }

    @Test
    void updatePersistsPublishesAndSurvivesARestart() throws Exception {
        Recorder recorder = new Recorder();
        try (NjMuleEngine engine = this.start()) {
            engine.events().subscribe(recorder);
            Preferences updated = engine.updatePreferences(
                            p -> p.toBuilder().nick("renamed").build())
                    .get(5, TimeUnit.SECONDS);
            assertThat(updated.nick()).isEqualTo("renamed");
            assertThat(engine.preferences()).isEqualTo(updated);

            // A no-op change does not publish again.
            engine.updatePreferences(p -> p).get(5, TimeUnit.SECONDS);
        }
        assertThat(recorder.completed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(recorder.events)
                .filteredOn(EngineEvent.PreferencesChanged.class::isInstance)
                .hasSize(1);
        assertThat(recorder.events).last().isInstanceOf(EngineEvent.Stopping.class);

        try (NjMuleEngine engine = this.start()) {
            assertThat(engine.preferences().nick()).isEqualTo("renamed");
        }
    }

    @Test
    void invalidUpdateFailsAndKeepsThePreviousPreferences() throws IOException {
        try (NjMuleEngine engine = this.start()) {
            assertThatThrownBy(() -> engine.updatePreferences(
                                    p -> p.toBuilder().tcpPort(0).build())
                            .get())
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class);
            assertThat(engine.preferences()).isEqualTo(Preferences.defaults());
        }
    }

    @Test
    void listsAndRemovesServersAndTellsItsStatus() throws Exception {
        Path met = this.root.resolve("other-server.met");
        Files.write(
                met,
                ServerMet.write(List.of(
                        new ServerMet.Server(
                                Ipv4.of(81, 2, 3, 4),
                                4661,
                                List.of(
                                        new Tag.StringTag(TagName.id(ServerTag.SERVERNAME), "One"),
                                        new Tag.IntTag(TagName.id(ServerTag.PREFERENCE), 1))),
                        new ServerMet.Server(Ipv4.of(81, 2, 3, 5), 4242, List.of()))));
        try (NjMuleEngine engine = this.start()) {
            assertThat(engine.addServers(met).get(5, TimeUnit.SECONDS)).isEqualTo(2);

            List<ServerInfo> servers = engine.servers().get(5, TimeUnit.SECONDS);
            assertThat(servers).extracting(ServerInfo::name).containsExactly("One", "81.2.3.5:4242");
            assertThat(servers.getFirst().priority()).isEqualTo(ServerInfo.Priority.HIGH);
            assertThat(servers.get(1).priority()).isEqualTo(ServerInfo.Priority.NORMAL);
            assertThat(engine.removeServer(ServerAddress.parse("81.2.3.4:4661")).get())
                    .isTrue();
            assertThat(engine.removeServer(ServerAddress.parse("81.2.3.4:4661")).get())
                    .isFalse();
            assertThat(engine.servers().get(5, TimeUnit.SECONDS)).hasSize(1);

            EngineStatus status = engine.status().get(5, TimeUnit.SECONDS);
            assertThat(status.server()).isEmpty();
            assertThat(status.clientId()).isZero();
            assertThat(status.downloads()).isZero();
            assertThat(status.downloadBytesPerSecond()).isZero();
            assertThat(engine.uploads().get(5, TimeUnit.SECONDS)).isEmpty();
        }
    }

    @Test
    void theSchedulerAppliesItsRuleOverThePreferences() throws Exception {
        Schedule.Rule always = new Schedule.Rule(
                "always",
                true,
                EnumSet.allOf(DayOfWeek.class),
                LocalTime.MIDNIGHT,
                LocalTime.MIDNIGHT,
                OptionalInt.of(7),
                OptionalInt.empty(),
                OptionalInt.empty(),
                OptionalInt.empty());
        try (Engine engine = (Engine) this.start()) {
            assertThat(engine.activeRule()).isEmpty();
            engine.updateSchedule(new Schedule(List.of(always))).get(5, TimeUnit.SECONDS);

            assertThat(engine.activeRule()).contains(always);
            assertThat(engine.schedule().rules()).containsExactly(always);
            assertThat(engine.effective().maxUploadKiBps()).isEqualTo(7);
            assertThat(engine.preferences().maxUploadKiBps()).isZero();
            engine.updatePreferences(p -> p.toBuilder().nick("other").build()).get(5, TimeUnit.SECONDS);
            assertThat(engine.effective().nick()).isEqualTo("other");
            assertThat(engine.effective().maxUploadKiBps()).isEqualTo(7);
        }
        try (Engine engine = (Engine) this.start()) {
            assertThat(engine.activeRule()).contains(always);
            engine.updateSchedule(Schedule.EMPTY).get(5, TimeUnit.SECONDS);
            assertThat(engine.activeRule()).isEmpty();
            assertThat(engine.effective().maxUploadKiBps()).isZero();
        }
    }

    @Test
    void totalsLastAcrossSessions() throws Exception {
        try (NjMuleEngine engine = this.start()) {
            EngineStatus status = engine.status().get(5, TimeUnit.SECONDS);
            assertThat(status.sessions()).isEqualTo(1);
            assertThat(status.totalDownloaded()).isZero();
            assertThat(status.totalSeconds()).isEqualTo(status.sessionSeconds());
        }
        try (NjMuleEngine engine = this.start()) {
            assertThat(engine.status().get(5, TimeUnit.SECONDS).sessions()).isEqualTo(2);
        }
        Files.writeString(
                this.root.resolve("config").resolve("statistics.properties"),
                "downloaded=12\nuploaded=x\nsessions=-4\n");
        try (NjMuleEngine engine = this.start()) {
            EngineStatus status = engine.status().get(5, TimeUnit.SECONDS);
            assertThat(status.totalDownloaded()).isEqualTo(12);
            assertThat(status.totalUploaded()).isZero();
            assertThat(status.sessions()).isEqualTo(1);
        }
    }

    @Test
    void keepsTheServerListAddressesInAddressesDat() throws Exception {
        URI list = URI.create("https://example.org/server.met");
        try (NjMuleEngine engine = this.start()) {
            assertThat(engine.serverListAddresses().get(5, TimeUnit.SECONDS)).isEmpty();
            engine.setServerListAddresses(List.of(list)).get(5, TimeUnit.SECONDS);
        }
        assertThat(this.root.resolve("config").resolve("addresses.dat")).exists();
        try (NjMuleEngine engine = this.start()) {
            assertThat(engine.serverListAddresses().get(5, TimeUnit.SECONDS)).containsExactly(list);
        }
    }

    @Test
    void closeIsIdempotent() throws IOException {
        NjMuleEngine engine = this.start();
        engine.close();
        engine.close();
        assertThat(engine.isRunning()).isFalse();
    }

    @Test
    void aSecondEngineCannotUseTheSameConfiguration() throws Exception {
        AppDirectories dirs = AppDirectories.underRoot(this.root.resolve("locked"), false);
        try (Engine first = Engine.start(new EngineConfig(dirs), () -> false)) {
            assertThatThrownBy(() -> Engine.start(new EngineConfig(dirs), () -> false))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("already running");
            assertThat(first.isRunning()).isTrue();
        }
        // Closing the first engine lets the next one start.
        Engine.start(new EngineConfig(dirs), () -> false).close();
    }
}
