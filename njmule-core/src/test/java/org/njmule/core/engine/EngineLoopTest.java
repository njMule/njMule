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
import static org.assertj.core.api.Assertions.catchThrowable;

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
    // Holds a task on the engine thread until the test lets it go.
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger runs = new AtomicInteger();
    private final List<Integer> order = Collections.synchronizedList(new ArrayList<>());
    private final List<Boolean> posts = new ArrayList<>();
    private final CountDownLatch oneShot = new CountDownLatch(1);
    private final CountDownLatch outcomes = new CountDownLatch(2);
    private Object answer;
    private Throwable exception;
    private int accepted;
    private boolean outcomeAccepted;

    @AfterEach
    void stop() {
        this.release.countDown();
        this.loop.close();
    }

    @Test
    void shouldRunASubmittedTaskOnTheEngineThread() throws Exception {
        whenATaskAsksWhetherItRunsOnTheEngineThread();

        thenTheAnswerIs(true);
    }

    @Test
    void shouldNotTakeTheCallerForTheEngineThread() {
        whenTheCallerAsksWhetherItIsTheEngineThread();

        thenTheAnswerIs(false);
    }

    @Test
    void shouldRunTasksInPostingOrder() throws Exception {
        whenTasksArePosted(10);

        thenEveryPostIsAccepted();
        thenTheTasksRanInTheOrder(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
    }

    @Test
    void shouldKeepRunningAfterAFailingTask() throws Exception {
        givenAPostedTaskThatFails();

        whenATaskIsSubmittedThatAnswers(42);

        thenTheAnswerIs(42);
    }

    @Test
    void shouldHandBackTheExceptionOfASubmittedTask() {
        whenATaskIsSubmittedThatThrows(new IllegalArgumentException("bad"));

        thenExceptionOccurred(ExecutionException.class, IllegalArgumentException.class);
    }

    @Test
    void shouldRefuseTasksWithoutBlockingOnceTheQueueIsFull() throws Exception {
        givenTheEngineThreadIsBusy();

        whenTasksArePostedUntilOneIsRefused();

        thenTheAcceptedTasksAre(16);
    }

    @Test
    void shouldStillTakeOutcomesAndFollowUpsWhenTheQueueIsFull() throws Exception {
        givenTheEngineThreadIsBusy();
        givenTheQueueIsFull();

        whenAnOutcomeAndAFollowUpArePostedBeforeTheBusyTaskEnds();

        thenTheOutcomeIsAccepted();
        thenTheOutcomeAndTheFollowUpRun();
    }

    @Test
    void shouldRunAScheduledJob() throws Exception {
        whenAJobIsScheduledIn(Duration.ofMillis(5));

        thenTheJobRuns();
    }

    @Test
    void shouldKeepRepeatingAJobWhoseFirstRunFails() throws Exception {
        whenAJobThatFailsItsFirstRunIsRepeatedEvery(Duration.ofMillis(5));

        thenTheJobRunsAtLeast(3);
    }

    @Test
    void shouldNotRunACancelledTimer() throws Exception {
        whenATimerIsCancelledBeforeItIsDue(Duration.ofMillis(50));

        thenTheTimerHasNotRunAfter(Duration.ofMillis(150));
    }

    @Test
    void shouldRunTheQueuedTasksThenRefuseNewOnesWhenShutDown() throws Exception {
        givenTheEngineThreadIsBusy();
        givenTasksAreQueued(5);

        whenTheLoopIsShutDown();

        thenTheQueuedTasksRan(5);
        thenANewTaskIsRefused();
        thenANewOutcomeIsRefused();
        thenASubmittedTaskIsRejected();
    }

    private void givenAPostedTaskThatFails() {
        this.loop.post(() -> {
            throw new IllegalStateException("boom");
        });
    }

    private void givenTheEngineThreadIsBusy() throws InterruptedException {
        CountDownLatch blocked = new CountDownLatch(1);
        this.loop.post(() -> {
            blocked.countDown();
            awaitQuietly(this.release);
        });
        blocked.await();
    }

    private void givenTheQueueIsFull() {
        whenTasksArePostedUntilOneIsRefused();
    }

    private void givenTasksAreQueued(int count) {
        for (int i = 0; i < count; i++) {
            this.loop.post(this.runs::incrementAndGet);
        }
    }

    private void whenATaskAsksWhetherItRunsOnTheEngineThread() throws Exception {
        this.answer = this.loop.submit(this.loop::isEngineThread).get();
    }

    private void whenTheCallerAsksWhetherItIsTheEngineThread() {
        this.answer = this.loop.isEngineThread();
    }

    private void whenTasksArePosted(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            int n = i;
            this.posts.add(this.loop.post(() -> this.order.add(n)));
        }
        this.loop.submit(() -> null).get();
    }

    private void whenATaskIsSubmittedThatAnswers(int value) throws Exception {
        this.answer = this.loop.submit(() -> value).get();
    }

    private void whenATaskIsSubmittedThatThrows(RuntimeException failure) {
        this.exception = catchThrowable(() -> this.loop
                .submit(() -> {
                    throw failure;
                })
                .get());
    }

    private void whenTasksArePostedUntilOneIsRefused() {
        while (this.loop.post(() -> {})) {
            this.accepted++;
        }
    }

    // The outcome of work in flight still gets in, as does a follow-up of finished work.
    private void whenAnOutcomeAndAFollowUpArePostedBeforeTheBusyTaskEnds() {
        this.outcomeAccepted = this.loop.postOutcome(this.outcomes::countDown);
        this.loop.whenDone(CompletableFuture.completedFuture(1), (value, failure) -> this.outcomes.countDown());
        this.release.countDown();
    }

    private void whenAJobIsScheduledIn(Duration delay) {
        this.loop.schedule("one", delay, this.oneShot::countDown);
    }

    private void whenAJobThatFailsItsFirstRunIsRepeatedEvery(Duration period) {
        this.loop.scheduleRepeating("tick", Duration.ZERO, period, () -> {
            if (this.runs.incrementAndGet() == 1) {
                throw new IllegalStateException("first run fails");
            }
        });
    }

    private void whenATimerIsCancelledBeforeItIsDue(Duration delay) {
        this.loop.schedule("never", delay, this.runs::incrementAndGet).cancel(false);
    }

    private void whenTheLoopIsShutDown() throws InterruptedException {
        Thread stopper = Thread.ofPlatform().start(() -> {
            try {
                this.loop.shutdown(Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        while (this.loop.isRunning()) {
            Thread.onSpinWait();
        }
        this.release.countDown();
        stopper.join();
    }

    private void thenTheAnswerIs(Object expected) {
        assertThat(this.answer).isEqualTo(expected);
    }

    private void thenEveryPostIsAccepted() {
        assertThat(this.posts).isNotEmpty().containsOnly(true);
    }

    private void thenTheTasksRanInTheOrder(Integer... expected) {
        assertThat(this.order).containsExactly(expected);
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type, Class<? extends Throwable> cause) {
        assertThat(this.exception).isInstanceOf(type).hasCauseInstanceOf(cause);
    }

    private void thenTheAcceptedTasksAre(int count) {
        assertThat(this.accepted).isEqualTo(count);
    }

    private void thenTheOutcomeIsAccepted() {
        assertThat(this.outcomeAccepted).isTrue();
    }

    private void thenTheOutcomeAndTheFollowUpRun() throws InterruptedException {
        assertThat(this.outcomes.await(10, TimeUnit.SECONDS)).isTrue();
    }

    private void thenTheJobRuns() throws InterruptedException {
        assertThat(this.oneShot.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private void thenTheJobRunsAtLeast(int times) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (this.runs.get() < times && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(this.runs.get()).isGreaterThanOrEqualTo(times);
    }

    private void thenTheTimerHasNotRunAfter(Duration wait) throws InterruptedException {
        Thread.sleep(wait.toMillis());
        assertThat(this.runs).hasValue(0);
    }

    private void thenTheQueuedTasksRan(int count) {
        assertThat(this.runs).hasValue(count);
    }

    private void thenANewTaskIsRefused() {
        assertThat(this.loop.post(() -> {})).isFalse();
    }

    private void thenANewOutcomeIsRefused() {
        assertThat(this.loop.postOutcome(() -> {})).isFalse();
    }

    private void thenASubmittedTaskIsRejected() {
        assertThatThrownBy(() -> this.loop.submit(() -> 1).get()).hasCauseInstanceOf(RejectedExecutionException.class);
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
