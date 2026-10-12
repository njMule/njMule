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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class EngineLoopTest {

    private final EngineLoop loop = EngineLoop.start(16, "test-engine");

    @AfterEach
    void stop() {
        this.loop.close();
    }

    @Test
    void submitRunsOnTheEngineThread() throws Exception {
        assertThat(this.loop.submit(this.loop::isEngineThread).get()).isTrue();
        assertThat(this.loop.isEngineThread()).isFalse();
    }

    @Test
    void tasksRunInPostingOrder() throws Exception {
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 10; i++) {
            int n = i;
            assertThat(this.loop.post(() -> order.add(n))).isTrue();
        }
        this.loop.submit(() -> null).get();
        assertThat(order).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
    }

    @Test
    void failingTaskDoesNotKillTheLoop() throws Exception {
        this.loop.post(
                () -> {
                    throw new IllegalStateException("boom");
                });
        assertThat(this.loop.submit(() -> 42).get()).isEqualTo(42);
    }

    @Test
    void submitPropagatesExceptions() {
        CompletableFuture<Object> f =
                this.loop.submit(
                        () -> {
                            throw new IllegalArgumentException("bad");
                        });
        assertThatThrownBy(f::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fullQueueRefusesTasksWithoutBlocking() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch blocked = new CountDownLatch(1);
        this.loop.post(
                () -> {
                    blocked.countDown();
                    awaitQuietly(release);
                });
        blocked.await();

        int accepted = 0;
        while (this.loop.post(() -> {})) {
            accepted++;
        }
        assertThat(accepted).isEqualTo(16);
        release.countDown();
    }

    @Test
    void scheduledJobsRunAndFailingRunsDoNotStopRepeatingOnes() throws Exception {
        CountDownLatch oneShot = new CountDownLatch(1);
        CountDownLatch repeating = new CountDownLatch(3);
        AtomicInteger runs = new AtomicInteger();
        this.loop.schedule("one", Duration.ofMillis(5), oneShot::countDown);
        this.loop.scheduleRepeating(
                "tick",
                Duration.ZERO,
                Duration.ofMillis(5),
                () -> {
                    repeating.countDown();
                    if (runs.incrementAndGet() == 1) {
                        throw new IllegalStateException("first run fails");
                    }
                });

        assertThat(oneShot.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(repeating.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void cancelledTimersDoNotRun() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        this.loop.schedule("never", Duration.ofMillis(50), runs::incrementAndGet).cancel(false);
        Thread.sleep(150);

        assertThat(runs).hasValue(0);
    }

    @Test
    void shutdownRunsQueuedTasksThenRefusesNewOnes() throws Exception {
        AtomicInteger ran = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        this.loop.post(() -> awaitQuietly(release));
        for (int i = 0; i < 5; i++) {
            this.loop.post(ran::incrementAndGet);
        }

        Thread stopper =
                Thread.ofPlatform()
                        .start(
                                () -> {
                                    try {
                                        this.loop.shutdown(Duration.ofSeconds(5));
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                });
        while (this.loop.isRunning()) {
            Thread.onSpinWait();
        }
        release.countDown();
        stopper.join();

        assertThat(ran).hasValue(5);
        assertThat(this.loop.post(() -> {})).isFalse();
        assertThatThrownBy(() -> this.loop.submit(() -> 1).get())
                .hasCauseInstanceOf(RejectedExecutionException.class);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            // The loop interrupts its thread on shutdown; keep waiting for the test to release us.
            try {
                latch.await();
            } catch (InterruptedException again) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
