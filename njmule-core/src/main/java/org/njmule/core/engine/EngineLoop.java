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

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single thread that owns all mutable engine state. Other threads {@link #post(Runnable)} tasks
 * to a bounded queue drained here together with the {@link Timers}. Posting never blocks: a full
 * queue refuses the task and the caller decides (e.g. disconnects a flooding peer).
 */
public final class EngineLoop implements AutoCloseable {

    public static final int DEFAULT_QUEUE_CAPACITY = 65_536;

    private static final Logger LOG = LoggerFactory.getLogger(EngineLoop.class);
    private static final long MAX_IDLE_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final int MAX_TASKS_PER_TURN = 1_024;

    private final BlockingQueue<Runnable> queue;
    private final Timers timers = new Timers();
    private final Ticker ticker;
    private final Thread thread;
    private final CountDownLatch terminated = new CountDownLatch(1);
    // Posters take the read lock (no mutual blocking); shutdown takes the write lock so that no
    // task is accepted after the final drain.
    private final ReadWriteLock stateLock = new ReentrantReadWriteLock();
    private volatile boolean running = true;

    private EngineLoop(Ticker ticker, int capacity, String threadName) {
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.thread = Thread.ofPlatform().name(threadName).daemon(false).unstarted(this::run);
    }

    public static EngineLoop start(Ticker ticker, int capacity, String threadName) {
        EngineLoop loop = new EngineLoop(ticker, capacity, threadName);
        loop.thread.start();
        return loop;
    }

    public static EngineLoop start() {
        return start(Ticker.system(), DEFAULT_QUEUE_CAPACITY, "njmule-engine");
    }

    /** Queues a task; returns {@code false} if the loop is stopping or the queue is full. */
    public boolean post(Runnable task) {
        Objects.requireNonNull(task, "task");
        this.stateLock.readLock().lock();
        try {
            return this.running && this.queue.offer(task);
        } finally {
            this.stateLock.readLock().unlock();
        }
    }

    /**
     * Runs a computation on the engine thread. The future fails with {@link
     * RejectedExecutionException} if the task cannot be queued.
     */
    public <T> CompletableFuture<T> submit(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        CompletableFuture<T> future = new CompletableFuture<>();
        boolean queued =
                this.post(
                        () -> {
                            if (future.isDone()) {
                                return;
                            }
                            try {
                                future.complete(task.call());
                            } catch (Exception | Error e) {
                                future.completeExceptionally(e);
                            }
                        });
        if (!queued) {
            future.completeExceptionally(
                    new RejectedExecutionException("engine loop not accepting"));
        }
        return future;
    }

    /** Schedules a one-shot job from any thread. */
    public CompletableFuture<Timers.Timer> schedule(String name, Duration delay, Runnable job) {
        return this.submit(() -> this.timers.schedule(name, delay, this.ticker.nanoTime(), job));
    }

    /** Schedules a fixed-rate job from any thread. */
    public CompletableFuture<Timers.Timer> scheduleAtFixedRate(
            String name, Duration initialDelay, Duration period, Runnable job) {
        return this.submit(
                () ->
                        this.timers.scheduleAtFixedRate(
                                name, initialDelay, period, this.ticker.nanoTime(), job));
    }

    public boolean isEngineThread() {
        return this.thread.equals(Thread.currentThread());
    }

    public boolean isRunning() {
        return this.running;
    }

    /**
     * Stops accepting tasks, runs those already queued and terminates; pending timers are dropped.
     *
     * @return {@code true} if the loop terminated within the timeout
     */
    public boolean shutdown(Duration timeout) throws InterruptedException {
        this.stateLock.writeLock().lock();
        try {
            this.running = false;
        } finally {
            this.stateLock.writeLock().unlock();
        }
        if (this.isEngineThread()) {
            return false;
        }
        this.thread.interrupt();
        return this.terminated.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    @Override
    public void close() {
        try {
            if (!this.shutdown(Duration.ofSeconds(10))) {
                LOG.warn("Engine loop did not terminate within 10 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        try {
            while (this.running) {
                long next = this.timers.runDue(this.ticker.nanoTime());
                long waitNanos =
                        next == Timers.NO_DEADLINE
                                ? MAX_IDLE_NANOS
                                : Math.min(
                                        MAX_IDLE_NANOS,
                                        Math.max(0L, next - this.ticker.nanoTime()));
                Runnable task;
                try {
                    task = this.queue.poll(waitNanos, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    continue;
                }
                for (int i = 0; task != null && i < MAX_TASKS_PER_TURN; i++) {
                    runTask(task);
                    task = i + 1 < MAX_TASKS_PER_TURN ? this.queue.poll() : null;
                }
            }
            // Run what was accepted before shutdown so that no submitted future is left pending.
            Thread.interrupted();
            Runnable task;
            while ((task = this.queue.poll()) != null) {
                runTask(task);
            }
        } finally {
            this.timers.clear();
            this.terminated.countDown();
        }
    }

    private static void runTask(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            LOG.error("Engine task failed", e);
        }
    }
}
