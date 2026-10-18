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
package org.njmule.core.remote;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.NjMuleEngine;

/**
 * The engine's events as server-sent events (one stream per caller, {@code event:} the event's name, {@code data:} its
 * JSON). A caller that falls too far behind gets a {@code lost} event and is cut off: it reads the state again and
 * reconnects. Comments keep idle connections alive.
 */
final class EventStreams implements AutoCloseable {

    static final int MAX_STREAMS = 8;
    static final int BACKLOG = 2048;
    static final Duration HEARTBEAT = Duration.ofSeconds(15);

    // How long the end waits for the streams to say goodbye before the server drops their connections.
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(2);

    // Puts an end to a stream: the interface stops, or the caller fell behind.
    private enum Marker {
        END
    }

    private final NjMuleEngine engine;
    private final Duration heartbeatInterval;
    private final Set<Stream> open = ConcurrentHashMap.newKeySet();
    private final Semaphore slots = new Semaphore(MAX_STREAMS);
    private volatile boolean closed;

    EventStreams(NjMuleEngine engine, Duration heartbeat) {
        this.engine = engine;
        this.heartbeatInterval = heartbeat;
    }

    /** Streams with callers now. */
    int open() {
        return this.open.size();
    }

    /**
     * Answers {@code exchange} with the events whose names start with one of {@code wanted} (all when empty), until the
     * caller leaves, falls behind or the interface stops; false when too many streams are open already.
     */
    boolean serve(Exchange exchange, List<String> wanted) throws IOException {
        if (this.closed || !this.slots.tryAcquire()) {
            return false;
        }
        Stream stream = new Stream(wanted);
        this.open.add(stream);
        try {
            exchange.setHeader("Content-Type", "text/event-stream; charset=utf-8");
            exchange.setHeader("Cache-Control", "no-store");
            exchange.setHeader("X-Content-Type-Options", "nosniff");
            exchange.sendHeaders(200, 0);
            this.engine.events().subscribe(stream);
            try (OutputStream out = exchange.responseBody()) {
                stream.pump(out);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            stream.cancel();
            this.open.remove(stream);
            this.slots.release();
        }
        return true;
    }

    @Override
    public void close() {
        this.closed = true;
        for (Stream s : this.open) {
            s.end();
        }
        try {
            // Every slot free: every stream ended its answer.
            if (this.slots.tryAcquire(MAX_STREAMS, CLOSE_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                this.slots.release(MAX_STREAMS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private final class Stream implements Flow.Subscriber<EngineEvent> {
        private final List<String> wanted;
        // Unbounded, so that the end always fits; the count of events keeps it to the backlog.
        private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        private final AtomicInteger waiting = new AtomicInteger();
        private volatile Flow.Subscription subscription;
        private volatile boolean lost;
        private long id;

        Stream(List<String> wanted) {
            this.wanted = List.copyOf(wanted);
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            this.subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(EngineEvent event) {
            // Never blocks the engine's delivery: a full backlog ends the stream instead.
            if (this.lost) {
                return;
            }
            if (this.waiting.incrementAndGet() > BACKLOG) {
                this.lost = true;
                end();
                return;
            }
            this.queue.add(event);
        }

        @Override
        public void onError(Throwable throwable) {
            end();
        }

        @Override
        public void onComplete() {
            end();
        }

        void end() {
            this.queue.clear();
            this.queue.add(Marker.END);
        }

        void cancel() {
            Flow.Subscription s = this.subscription;
            if (s != null) {
                s.cancel();
            }
        }

        void pump(OutputStream out) throws IOException, InterruptedException {
            write(out, ": njMule events\n\n");
            while (!EventStreams.this.closed) {
                Object next = this.queue.poll(EventStreams.this.heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);
                if (next == null) {
                    write(out, ": keep-alive\n\n");
                } else if (next == Marker.END) {
                    if (this.lost) {
                        write(out, "event: lost\ndata: {}\n\n");
                    }
                    return;
                } else if (next instanceof EngineEvent event) {
                    this.waiting.decrementAndGet();
                    if (wants(ApiJson.eventName(event))) {
                        byte[] data = ApiJson.write(g -> ApiJson.event(g, event));
                        write(
                                out,
                                "event: " + ApiJson.eventName(event) + "\nid: " + ++this.id + "\ndata: "
                                        + new String(data, StandardCharsets.UTF_8) + "\n\n");
                    }
                }
            }
        }

        private boolean wants(String name) {
            if (this.wanted.isEmpty()) {
                return true;
            }
            for (String prefix : this.wanted) {
                if (name.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }
    }

    // JSON escapes line breaks, so a data line never ends early.
    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
