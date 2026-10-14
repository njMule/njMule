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
package org.njmule.core.net;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.packet.FrameLimit;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.TcpFraming;

/**
 * A framed eD2K TCP connection over a blocking socket, served by a reading and a writing virtual thread. Sending never
 * blocks: frames wait in a bounded queue, and a peer that does not drain it is disconnected. File data waits behind
 * every other packet.
 */
public final class TcpConnection implements PeerChannel, AutoCloseable {

    /** Callbacks, invoked on the connection's reading thread. */
    public interface Handler {
        void onPacket(TcpConnection connection, Packet packet);

        /** Called once; the cause is empty after {@link #close()} or a clean end of stream. */
        void onClosed(TcpConnection connection, Optional<IOException> cause);
    }

    /** Turns raw frames (for example zlib-packed ones) into the packets the handler sees. */
    @FunctionalInterface
    public interface Unpacker {
        Packet unpack(Packet frame) throws DecodeException;
    }

    private static final int OUTBOX_CAPACITY = 1_024;
    // What a peer may leave unread, whatever the number of frames.
    private static final long OUTBOX_BYTES = 16L << 20;
    // A clean close sends what is queued, for this long at most.
    private static final Duration FLUSH_WAIT = Duration.ofSeconds(5);
    private static final long WRITER_STACK = 256 * 1024;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final boolean obfuscated;
    private final InetSocketAddress remote;
    private final Queue<byte[]> control = new ConcurrentLinkedQueue<>();
    private final Queue<byte[]> data = new ConcurrentLinkedQueue<>();
    // One permit for each frame queued, taken by the writer, and one more from the close that wakes it.
    private final Semaphore ready = new Semaphore(0);
    private final AtomicInteger frames = new AtomicInteger();
    private final AtomicLong queued = new AtomicLong();
    private final AtomicLong queuedData = new AtomicLong();
    private final AtomicBoolean started = new AtomicBoolean();
    // Null while open.
    private final AtomicReference<Closure> closure = new AtomicReference<>();
    private final AtomicBoolean reported = new AtomicBoolean();
    private volatile FrameLimit frameLimit;
    private volatile Unpacker unpacker = frame -> frame;
    private volatile Handler handler;
    private volatile Thread writer;

    TcpConnection(Socket socket, InputStream in, OutputStream out, boolean obfuscated, FrameLimit frameLimit) {
        this.socket = Objects.requireNonNull(socket, "socket");
        this.in = Objects.requireNonNull(in, "in");
        this.out = Objects.requireNonNull(out, "out");
        this.obfuscated = obfuscated;
        this.remote = (InetSocketAddress) socket.getRemoteSocketAddress();
        this.frameLimit = Objects.requireNonNull(frameLimit, "frameLimit");
    }

    /** Starts the reading and writing threads; packets go to {@code handler}, told at once if already closed. */
    public void start(Handler handler, Unpacker unpacker) {
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(unpacker, "unpacker");
        if (!this.started.compareAndSet(false, true)) {
            throw new IllegalStateException("already started");
        }
        this.handler = handler;
        this.unpacker = unpacker;
        if (!this.isOpen()) {
            this.report();
            return;
        }
        String name = "njmule-tcp-" + this.remote;
        // On Windows a virtual thread blocked in a socket write can miss the wakeup and hang the
        // connection for good, so writes run on a small platform thread.
        this.writer = Thread.ofPlatform()
                .name(name + "-out")
                .daemon(true)
                .stackSize(WRITER_STACK)
                .start(this::writeLoop);
        Thread.ofVirtual().name(name + "-in").start(this::readLoop);
    }

    /**
     * Queues a packet; returns {@code false} if the connection is closed. A full queue, in frames or in bytes, closes
     * the connection: the peer is not reading.
     */
    @Override
    public boolean send(Packet packet) {
        return this.enqueue(packet, false);
    }

    /** As {@link #send(Packet)}, for file data: it goes out after the other packets waiting. */
    @Override
    public boolean sendData(Packet packet) {
        return this.enqueue(packet, true);
    }

    /** Bytes of file data waiting to be written. */
    @Override
    public long queuedData() {
        return this.queuedData.get();
    }

    private boolean enqueue(Packet packet, boolean fileData) {
        if (!this.isOpen()) {
            return false;
        }
        byte[] frame = TcpFraming.encode(packet);
        if (this.queued.addAndGet(frame.length) > OUTBOX_BYTES || this.frames.incrementAndGet() > OUTBOX_CAPACITY) {
            this.fail(new IOException("send queue full, peer not reading"));
            return false;
        }
        if (fileData) {
            this.queuedData.addAndGet(frame.length);
            this.data.add(frame);
        } else {
            this.control.add(frame);
        }
        this.ready.release();
        return true;
    }

    /** Frame size limits for the next frames, e.g. once a handshake completes. */
    public void setFrameLimit(FrameLimit limit) {
        this.frameLimit = Objects.requireNonNull(limit, "limit");
    }

    /** Fails reads that wait longer than {@code timeout}; zero waits forever. */
    @Override
    public void setReadTimeout(Duration timeout) throws SocketException {
        this.socket.setSoTimeout(Math.toIntExact(timeout.toMillis()));
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return this.remote;
    }

    /** Our address on the connection, while the socket is open. */
    public Optional<InetAddress> localAddress() {
        return Optional.ofNullable(this.socket.getLocalAddress()).filter(a -> !a.isAnyLocalAddress());
    }

    public boolean isObfuscated() {
        return this.obfuscated;
    }

    @Override
    public boolean isOpen() {
        return this.closure.get() == null;
    }

    /** Closes the connection once the packets already queued are sent, within a few seconds. */
    @Override
    public void close() {
        this.shutdown(Optional.empty());
    }

    @Override
    public void disconnect() {
        this.close();
    }

    private void fail(IOException cause) {
        this.shutdown(Optional.of(cause));
    }

    private void shutdown(Optional<IOException> cause) {
        if (!this.closure.compareAndSet(null, new Closure(cause))) {
            return;
        }
        Thread w = this.writer;
        if (cause.isPresent() || w == null) {
            this.control.clear();
            this.data.clear();
            this.ready.release();
            this.closeSocket();
        } else {
            // The writer sends what is queued, then closes; a peer that does not read is cut off.
            this.ready.release();
            Thread.ofVirtual().name("njmule-tcp-" + this.remote + "-flush").start(() -> {
                try {
                    w.join(FLUSH_WAIT);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                this.closeSocket();
            });
        }
        this.report();
    }

    // Tells the handler once, when there is one.
    private void report() {
        Handler h = this.handler;
        Closure closed = this.closure.get();
        if (h != null && closed != null && this.reported.compareAndSet(false, true)) {
            h.onClosed(this, closed.cause());
        }
    }

    private void closeSocket() {
        try {
            this.socket.close();
        } catch (IOException e) {
            // Closing anyway; nothing else to release.
        }
    }

    private void readLoop() {
        try {
            while (this.isOpen()) {
                Packet frame = TcpFraming.read(this.in, this.frameLimit);
                // The handler heard of the close: nothing more for it.
                if (!this.isOpen()) {
                    return;
                }
                this.handler.onPacket(this, this.unpacker.unpack(frame));
            }
        } catch (EOFException e) {
            this.close();
        } catch (IOException e) {
            this.fail(e);
        } catch (RuntimeException e) {
            // A handler bug must not leave a half-dead connection behind.
            this.fail(new IOException("packet handler failed", e));
        }
    }

    private void writeLoop() {
        try {
            for (byte[] frame = this.nextFrame(); frame != null; frame = this.nextFrame()) {
                this.out.write(frame);
                if (this.frames.get() == 0) {
                    this.out.flush();
                }
            }
        } catch (IOException e) {
            this.fail(e);
        }
        this.closeSocket();
    }

    // The next frame to write; null once closed and the queues are empty. What is queued when a clean close comes still
    // goes out.
    private byte[] nextFrame() {
        while (this.isOpen()) {
            try {
                this.ready.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            byte[] frame = this.poll();
            if (frame != null) {
                return frame;
            }
            // The close's permit, which comes with no frame.
        }
        return this.ready.tryAcquire() ? this.poll() : null;
    }

    // A frame stands behind every permit but the close's, unless a failure emptied the queues.
    private byte[] poll() {
        byte[] frame = this.control.poll();
        if (frame == null) {
            frame = this.data.poll();
            if (frame == null) {
                return null;
            }
            this.queuedData.addAndGet(-frame.length);
        }
        this.queued.addAndGet(-frame.length);
        this.frames.decrementAndGet();
        return frame;
    }

    // How the connection ended: the failure, or none after a clean close.
    private record Closure(Optional<IOException> cause) {}
}
