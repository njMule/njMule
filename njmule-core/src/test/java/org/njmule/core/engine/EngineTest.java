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
import java.nio.file.Path;
import java.util.List;
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
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.config.Preferences;
import org.njmule.core.persist.PreferencesCodec;

@Timeout(20)
class EngineTest {

    @TempDir Path root;

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
            assertThat(engine.directories().config().resolve(PreferencesCodec.FILE_NAME)).exists();
            assertThat(engine.preferences()).isEqualTo(Preferences.defaults());
        }
    }

    @Test
    void updatePersistsPublishesAndSurvivesARestart() throws Exception {
        Recorder recorder = new Recorder();
        try (NjMuleEngine engine = this.start()) {
            engine.events().subscribe(recorder);
            Preferences updated =
                    engine.updatePreferences(p -> p.toBuilder().nick("renamed").build())
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
            assertThatThrownBy(
                            () ->
                                    engine.updatePreferences(p -> p.toBuilder().tcpPort(0).build())
                                            .get())
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class);
            assertThat(engine.preferences()).isEqualTo(Preferences.defaults());
        }
    }

    @Test
    void closeIsIdempotent() throws IOException {
        NjMuleEngine engine = this.start();
        engine.close();
        engine.close();
        assertThat(engine.isRunning()).isFalse();
    }
}
