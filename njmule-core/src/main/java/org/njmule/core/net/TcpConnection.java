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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.packet.FrameLimit;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.TcpFraming;

/**
 * A framed eD2K TCP connection over a blocking socket, served by a reading and a writing virtual
 * thread. Sending never blocks: frames wait in a bounded queue, and a peer that does not drain it
 * is disconnected.
 */
public final class TcpConnection implements AutoCloseable {

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
    private static final long WRITER_STACK = 256 * 1024;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final boolean obfuscated;
    private final InetSocketAddress remote;
    private final BlockingQueue<byte[]> outbox = new ArrayBlockingQueue<>(OUTBOX_CAPACITY);
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile FrameLimit frameLimit;
    private volatile Unpacker unpacker = frame -> frame;
    private volatile Handler handler;
    private volatile Thread writer;

    TcpConnection(
            Socket socket,
            InputStream in,
            OutputStream out,
            boolean obfuscated,
            FrameLimit frameLimit) {
        this.socket = Objects.requireNonNull(socket, "socket");
        this.in = Objects.requireNonNull(in, "in");
        this.out = Objects.requireNonNull(out, "out");
        this.obfuscated = obfuscated;
        this.remote = (InetSocketAddress) socket.getRemoteSocketAddress();
        this.frameLimit = Objects.requireNonNull(frameLimit, "frameLimit");
    }

    /** Starts the reading and writing threads; packets go to {@code handler}. */
    public void start(Handler handler, Unpacker unpacker) {
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(unpacker, "unpacker");
        if (!this.started.compareAndSet(false, true)) {
            throw new IllegalStateException("already started");
        }
        this.handler = handler;
        this.unpacker = unpacker;
        String name = "njmule-tcp-" + this.remote;
        // On Windows a virtual thread blocked in a socket write can miss the wakeup and hang the
        // connection for good, so writes run on a small platform thread.
        this.writer =
                Thread.ofPlatform()
                        .name(name + "-out")
                        .daemon(true)
                        .stackSize(WRITER_STACK)
                        .start(this::writeLoop);
        Thread.ofVirtual().name(name + "-in").start(this::readLoop);
    }

    /**
     * Queues a packet; returns {@code false} if the connection is closed. A full queue closes the
     * connection: the peer is not reading.
     */
    public boolean send(Packet packet) {
        if (this.closed.get()) {
            return false;
        }
        if (!this.outbox.offer(TcpFraming.encode(packet))) {
            this.fail(new IOException("send queue full, peer not reading"));
            return false;
        }
        return true;
    }

    /** Frame size limits for the next frames, e.g. once a handshake completes. */
    public void setFrameLimit(FrameLimit limit) {
        this.frameLimit = Objects.requireNonNull(limit, "limit");
    }

    /** Fails reads that wait longer than {@code timeout}; zero waits forever. */
    public void setReadTimeout(Duration timeout) throws SocketException {
        this.socket.setSoTimeout(Math.toIntExact(timeout.toMillis()));
    }

    public InetSocketAddress remoteAddress() {
        return this.remote;
    }

    public boolean isObfuscated() {
        return this.obfuscated;
    }

    public boolean isOpen() {
        return !this.closed.get();
    }

    @Override
    public void close() {
        this.shutdown(Optional.empty());
    }

    private void fail(IOException cause) {
        this.shutdown(Optional.of(cause));
    }

    private void shutdown(Optional<IOException> cause) {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        this.outbox.clear();
        Thread w = this.writer;
        if (w != null) {
            w.interrupt();
        }
        try {
            this.socket.close();
        } catch (IOException e) {
            // Closing anyway; nothing else to release.
        }
        Handler h = this.handler;
        if (h != null) {
            h.onClosed(this, cause);
        }
    }

    private void readLoop() {
        try {
            while (!this.closed.get()) {
                Packet frame = TcpFraming.read(this.in, this.frameLimit);
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
            while (!this.closed.get()) {
                byte[] frame = this.outbox.take();
                this.out.write(frame);
                if (this.outbox.isEmpty()) {
                    this.out.flush();
                }
            }
        } catch (IOException e) {
            this.fail(e);
        } catch (InterruptedException e) {
            // close() stops the writer this way.
            Thread.currentThread().interrupt();
            this.close();
        }
    }
}
