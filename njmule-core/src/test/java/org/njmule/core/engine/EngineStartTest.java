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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.config.AppDirectories;

@Timeout(30)
class EngineStartTest {

    @TempDir
    Path dir;

    @Test
    void theLogOfTheStartReachesThoseWhoListenLater() throws Exception {
        try (NjMuleEngine engine = this.start()) {
            Lines lines = new Lines(engine);

            assertThat(lines.await(line -> line.contains("started"))).isTrue();
        }
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
