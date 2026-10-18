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
package org.njmule.core.quic;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.njmule.core.net.StreamTransport;
import tech.kwik.core.QuicConnection;
import tech.kwik.core.QuicStream;

/**
 * A QUIC connection with another client as a byte stream: each side writes on the one stream it opened and reads the
 * other's. Reads wait in a queue of their own, so that they can time out as a socket's do.
 */
public final class QuicTransport implements StreamTransport {

    private static final int CHUNK = 16 * 1024;
    // Up to 1 MiB read ahead; then the other side's flow control holds it back.
    private static final int CHUNKS = 64;
    // Marks the end of the other side's stream; what it sent is never empty.
    private static final byte[] END = new byte[0];

    private final QuicConnection connection;
    private final InetSocketAddress remote;
    private final Consumer<QuicTransport> onClose;
    private final AtomicBoolean theirsOpened = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final BlockingQueue<byte[]> received = new ArrayBlockingQueue<>(CHUNKS);
    private final Input input = new Input();
    private volatile QuicStream ours;
    private volatile long readTimeoutNanos;
    private volatile IOException failure;

    QuicTransport(QuicConnection connection, InetSocketAddress remote, Consumer<QuicTransport> onClose) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.remote = Objects.requireNonNull(remote, "remote");
        this.onClose = Objects.requireNonNull(onClose, "onClose");
    }

    /** A stream the other side opened: the first bidirectional one is read, others are refused. */
    void peerOpened(QuicStream stream) {
        if (stream.isBidirectional() && this.theirsOpened.compareAndSet(false, true)) {
            Thread.ofVirtual().name("njmule-quic-" + this.remote + "-read").start(() -> pump(stream));
        } else {
            stream.abortReading(0);
        }
    }

    /** Opens our stream and writes {@code first} on it. */
    void open(byte[] first) throws IOException {
        QuicStream stream = this.connection.createStream(true);
        OutputStream out = stream.getOutputStream();
        out.write(first);
        out.flush();
        this.ours = stream;
    }

    /** The first {@code length} bytes of the other side's stream, each read waiting up to {@code wait}. */
    byte[] readFirst(int length, Duration wait) throws IOException {
        long before = this.readTimeoutNanos;
        this.readTimeoutNanos = wait.toNanos();
        try {
            byte[] first = this.input.readNBytes(length);
            if (first.length < length) {
                throw new SocketException("closed after " + first.length + " bytes");
            }
            return first;
        } finally {
            this.readTimeoutNanos = before;
        }
    }

    // Until the other side's stream ends, or fails as the connection ends; what came before is still read.
    private void pump(QuicStream stream) {
        try {
            try (InputStream in = stream.getInputStream()) {
                byte[] buffer = new byte[CHUNK];
                for (int n = in.read(buffer); n >= 0 && !this.closed.get(); n = in.read(buffer)) {
                    if (n > 0) {
                        this.received.put(Arrays.copyOf(buffer, n));
                    }
                }
            } catch (IOException e) {
                this.failure = e;
            }
            this.received.put(END);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public InputStream input() {
        return this.input;
    }

    @Override
    public OutputStream output() throws IOException {
        QuicStream stream = this.ours;
        if (stream == null) {
            throw new SocketException("not open");
        }
        return stream.getOutputStream();
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return this.remote;
    }

    @Override
    public Optional<InetAddress> localAddress() {
        return Optional.empty();
    }

    @Override
    public void setReadTimeout(Duration timeout) {
        this.readTimeoutNanos = timeout.toNanos();
    }

    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        QuicStream stream = this.ours;
        if (stream != null) {
            try {
                stream.getOutputStream().close();
            } catch (IOException e) {
                // Closing the connection anyway.
            }
        }
        this.connection.close();
        this.received.clear();
        this.received.add(END);
        this.onClose.accept(this);
    }

    // Read by one thread at a time, the proof's then the connection's reader: the lock is never contended.
    private final class Input extends InputStream {
        private byte[] chunk = new byte[0];
        private int at;
        private boolean finished;

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            if (len == 0) {
                return 0;
            }
            if (this.at == this.chunk.length) {
                if (this.finished || !next()) {
                    return -1;
                }
            }
            int n = Math.min(len, this.chunk.length - this.at);
            System.arraycopy(this.chunk, this.at, b, off, n);
            this.at += n;
            return n;
        }

        @Override
        public synchronized int available() {
            return this.chunk.length - this.at;
        }

        // The next chunk; false at the end of the other side's stream.
        private synchronized boolean next() throws IOException {
            QuicTransport t = QuicTransport.this;
            long timeout = t.readTimeoutNanos;
            byte[] next;
            try {
                next = timeout > 0 ? t.received.poll(timeout, TimeUnit.NANOSECONDS) : t.received.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted");
            }
            if (next == null) {
                throw new SocketTimeoutException("read timed out");
            }
            if (next.length == 0) {
                this.finished = true;
                if (t.closed.get()) {
                    throw new SocketException("closed");
                }
                IOException failed = t.failure;
                if (failed != null) {
                    throw new IOException("QUIC: " + failed.getMessage(), failed);
                }
                return false;
            }
            this.chunk = next;
            this.at = 0;
            return true;
        }
    }
}
