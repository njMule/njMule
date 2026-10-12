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
import java.util.PriorityQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deadline-ordered heap of one-shot and fixed-rate jobs. Not thread-safe: only the engine thread
 * uses it (see {@link EngineLoop}).
 */
public final class Timers {

    public static final long NO_DEADLINE = Long.MAX_VALUE;

    private static final Logger LOG = LoggerFactory.getLogger(Timers.class);

    private final PriorityQueue<Timer> queue = new PriorityQueue<>();
    private long sequence;

    /** Handle of a scheduled job. */
    public static final class Timer implements Comparable<Timer> {
        private final String name;
        private final Runnable job;
        private final long periodNanos;
        private final long sequence;
        private long deadlineNanos;
        private boolean cancelled;

        private Timer(
                String name, Runnable job, long deadlineNanos, long periodNanos, long sequence) {
            this.name = name;
            this.job = job;
            this.deadlineNanos = deadlineNanos;
            this.periodNanos = periodNanos;
            this.sequence = sequence;
        }

        public void cancel() {
            this.cancelled = true;
        }

        public boolean isCancelled() {
            return this.cancelled;
        }

        public String name() {
            return this.name;
        }

        @Override
        public int compareTo(Timer other) {
            int byDeadline = Long.compare(this.deadlineNanos, other.deadlineNanos);
            return byDeadline != 0 ? byDeadline : Long.compare(this.sequence, other.sequence);
        }

        // Identity semantics, consistent with compareTo (the sequence is unique).
        @Override
        public boolean equals(Object obj) {
            return this == obj;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(this.sequence);
        }

        @Override
        public String toString() {
            return "Timer[" + this.name + "]";
        }
    }

    public Timer schedule(String name, Duration delay, long nowNanos, Runnable job) {
        return this.add(name, job, nowNanos + Math.max(0L, delay.toNanos()), 0L);
    }

    public Timer scheduleAtFixedRate(
            String name, Duration initialDelay, Duration period, long nowNanos, Runnable job) {
        long periodNanos = period.toNanos();
        if (periodNanos <= 0) {
            throw new IllegalArgumentException("period must be positive: " + period);
        }
        return this.add(name, job, nowNanos + Math.max(0L, initialDelay.toNanos()), periodNanos);
    }

    /**
     * Runs the due jobs. A failing job is logged and, if periodic, rescheduled.
     *
     * @return the next deadline, or {@link #NO_DEADLINE}
     */
    public long runDue(long nowNanos) {
        Timer timer;
        while ((timer = this.queue.peek()) != null && timer.deadlineNanos - nowNanos <= 0) {
            this.queue.poll();
            if (timer.cancelled) {
                continue;
            }
            try {
                timer.job.run();
            } catch (RuntimeException e) {
                LOG.error("Timer {} failed", timer.name, e);
            }
            if (timer.periodNanos > 0 && !timer.cancelled) {
                long next = timer.deadlineNanos + timer.periodNanos;
                if (next - nowNanos <= 0) {
                    // Fell behind: skip missed runs instead of running them in a burst.
                    long missed = (nowNanos - timer.deadlineNanos) / timer.periodNanos;
                    next = timer.deadlineNanos + (missed + 1) * timer.periodNanos;
                }
                timer.deadlineNanos = next;
                this.queue.add(timer);
            }
        }
        return this.nextDeadline();
    }

    public long nextDeadline() {
        Timer timer;
        while ((timer = this.queue.peek()) != null && timer.cancelled) {
            this.queue.poll();
        }
        return timer == null ? NO_DEADLINE : timer.deadlineNanos;
    }

    public int size() {
        return this.queue.size();
    }

    public void clear() {
        this.queue.forEach(Timer::cancel);
        this.queue.clear();
    }

    private Timer add(String name, Runnable job, long deadlineNanos, long periodNanos) {
        Timer timer =
                new Timer(
                        Objects.requireNonNull(name, "name"),
                        Objects.requireNonNull(job, "job"),
                        deadlineNanos,
                        periodNanos,
                        this.sequence++);
        this.queue.add(timer);
        return timer;
    }
}
