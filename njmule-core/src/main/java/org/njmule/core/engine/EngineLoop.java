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

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single thread that owns all mutable engine state: a one-thread {@link ScheduledThreadPoolExecutor} running posted
 * tasks and timers in order. Posting never blocks: when too many tasks wait, a task from the network is refused and the
 * caller decides (e.g. disconnects a flooding peer), while the outcome of work the engine started is always queued. A
 * failing task is logged and does not stop the loop.
 */
public final class EngineLoop implements AutoCloseable {

    public static final int DEFAULT_QUEUE_CAPACITY = 65_536;

    private static final Logger logger = LoggerFactory.getLogger(EngineLoop.class);

    private final ScheduledThreadPoolExecutor executor;
    private final int capacity;
    private volatile Thread thread;

    private EngineLoop(int capacity, String threadName) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread t = Thread.ofPlatform().name(threadName).daemon(false).unstarted(task);
            this.thread = t;
            return t;
        });
        // On shutdown, tasks already posted still run; timers are dropped.
        this.executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        this.executor.setRemoveOnCancelPolicy(true);
        this.executor.prestartCoreThread();
    }

    public static EngineLoop start(int capacity, String threadName) {
        return new EngineLoop(capacity, Objects.requireNonNull(threadName, "threadName"));
    }

    public static EngineLoop start() {
        return start(DEFAULT_QUEUE_CAPACITY, "njmule-engine");
    }

    /** Queues a task; returns {@code false} if the loop is stopping or too many tasks wait. */
    public boolean post(Runnable task) {
        Objects.requireNonNull(task, "task");
        if (this.executor.getQueue().size() >= this.capacity) {
            return false;
        }
        try {
            this.executor.execute(() -> runTask("task", task));
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    /**
     * Queues the outcome of work the engine started, such as a connection that closed or a write that finished. It is
     * never refused for a full queue, since there are no more of them than work in flight, so state that waits for it
     * cannot hang; it is refused only once the loop stops.
     */
    public boolean postOutcome(Runnable task) {
        Objects.requireNonNull(task, "task");
        try {
            this.executor.execute(() -> runTask("task", task));
            return true;
        } catch (RejectedExecutionException e) {
            logger.debug("Engine loop stopped, an outcome is dropped");
            return false;
        }
    }

    /**
     * Runs a computation on the engine thread. The future fails with {@link RejectedExecutionException} if the task
     * cannot be queued.
     */
    public <T> CompletableFuture<T> submit(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        CompletableFuture<T> future = new CompletableFuture<>();
        boolean queued = this.post(() -> {
            try {
                future.complete(task.call());
            } catch (Exception | Error e) {
                future.completeExceptionally(e);
            }
        });
        if (!queued) {
            future.completeExceptionally(new RejectedExecutionException("engine loop not accepting"));
        }
        return future;
    }

    /** Like {@link #submit}, for a task that starts asynchronous work: the future completes as that work does. */
    public <T> CompletableFuture<T> submitAsync(Callable<? extends CompletionStage<T>> task) {
        return this.submit(task).thenCompose(work -> work);
    }

    /** Runs {@code job} once after {@code delay}, from any thread. */
    @CanIgnoreReturnValue
    public ScheduledFuture<?> schedule(String name, Duration delay, Runnable job) {
        Objects.requireNonNull(job, "job");
        return this.executor.schedule(() -> runTask(name, job), delay.toNanos(), TimeUnit.NANOSECONDS);
    }

    /**
     * Runs {@code job} repeatedly, {@code period} after the end of the previous run, so a stalled loop never fires a
     * burst of missed runs. A failing run is logged and the job continues.
     */
    @CanIgnoreReturnValue
    public ScheduledFuture<?> scheduleRepeating(String name, Duration initialDelay, Duration period, Runnable job) {
        Objects.requireNonNull(job, "job");
        return this.executor.scheduleWithFixedDelay(
                () -> runTask(name, job), initialDelay.toNanos(), period.toNanos(), TimeUnit.NANOSECONDS);
    }

    /**
     * Runs {@code then} on the engine thread with the outcome of {@code work}, like {@link #postOutcome}: dropped only
     * once the loop has stopped.
     */
    @SuppressWarnings("FutureReturnValueIgnored") // the follow-up reports its own failures
    public <T> void whenDone(CompletableFuture<T> work, BiConsumer<T, Throwable> then) {
        work.whenComplete((value, failure) -> this.postOutcome(() -> then.accept(value, failure)));
    }

    public boolean isEngineThread() {
        return this.thread.equals(Thread.currentThread());
    }

    public boolean isRunning() {
        return !this.executor.isShutdown();
    }

    /**
     * Stops accepting tasks, runs those already queued and terminates; pending timers are dropped.
     *
     * @return {@code true} if the loop terminated within the timeout
     */
    public boolean shutdown(Duration timeout) throws InterruptedException {
        this.executor.shutdown();
        if (this.isEngineThread()) {
            return false;
        }
        return this.executor.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    @Override
    public void close() {
        try {
            if (!this.shutdown(Duration.ofSeconds(10))) {
                logger.warn("Engine loop did not terminate within 10 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void runTask(String name, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            logger.error("Engine {} failed", name, e);
        }
    }
}
