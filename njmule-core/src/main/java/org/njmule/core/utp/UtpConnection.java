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
package org.njmule.core.utp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.njmule.core.net.StreamTransport;
import org.njmule.core.util.Bytes;

/**
 * One uTP connection: a reliable byte stream with blocking streams. Packets are acknowledged cumulatively and
 * selectively; a packet three later ones were acknowledged past, or whose retransmission timer runs out, is sent again.
 * The window grows in slow start, then by LEDBAT towards a queuing delay of 100 ms, and halves on a loss.
 */
public final class UtpConnection implements StreamTransport {

    /** Payload bytes per packet: within the smallest IPv6 MTU, after the UDP, framing, obfuscation and uTP headers. */
    public static final int MSS = 1100;

    static final int RECEIVE_BUFFER = 1 << 20;
    private static final int SEND_BUFFER = 1 << 20;
    private static final long TARGET_DELAY_MICROS = 100_000;
    // LEDBAT's largest growth of the window in a round trip.
    private static final long MAX_INCREASE_PER_RTT = 3_000;
    private static final long MAX_WINDOW = 8L << 20;
    private static final long INITIAL_RTO = Duration.ofSeconds(1).toNanos();
    private static final long MIN_RTO = Duration.ofMillis(500).toNanos();
    private static final long MAX_RTO = Duration.ofSeconds(30).toNanos();
    private static final int MAX_TRANSMISSIONS = 8;
    private static final int SYN_TRANSMISSIONS = 4;
    private static final int DUPLICATE_ACKS = 3;
    private static final int REORDER = 1_024;
    private static final int MAX_SACK_BYTES = 32;
    private static final long KEEPALIVE = Duration.ofSeconds(29).toNanos();
    private static final long SILENCE = Duration.ofSeconds(70).toNanos();
    private static final long LINGER = Duration.ofSeconds(10).toNanos();
    // The base delay is the lowest sample of the last two minutes, kept by the minute.
    private static final long DELAY_BUCKET = Duration.ofMinutes(1).toNanos();

    private enum State {
        SYN_SENT,
        CONNECTED,
        CLOSED
    }

    // A packet sent and not acknowledged yet.
    private static final class Out {
        final UtpPacket.Type type;
        final int seq;
        final byte[] payload;
        long sentAt;
        int transmissions;
        boolean acknowledged;
        // Acknowledged packets past this one, in selective acknowledgements.
        int passed;

        Out(UtpPacket.Type type, int seq, byte[] payload) {
            this.type = type;
            this.seq = seq;
            this.payload = payload;
        }
    }

    private final UtpSocket socket;
    private final InetSocketAddress remote;
    private final int receiveId;
    private final int sendId;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition readable = this.lock.newCondition();
    private final Condition writable = this.lock.newCondition();
    private final CompletableFuture<UtpConnection> connected = new CompletableFuture<>();
    private final Deque<Out> inFlight = new ArrayDeque<>();
    private final Deque<byte[]> toSend = new ArrayDeque<>();
    private final Map<Integer, byte[]> reordered = new HashMap<>();
    private final Deque<byte[]> toRead = new ArrayDeque<>();
    private final long[] delayMinutes = {Long.MAX_VALUE, Long.MAX_VALUE};
    private final InputStream input = new Input();
    private final OutputStream output = new Output();
    private State state;
    private int seq;
    private int ack;
    private int firstSeq;
    private long inFlightBytes;
    private long toSendBytes;
    private int toSendOffset;
    private long toReadBytes;
    private int toReadOffset;
    private long reorderedBytes;
    private int finSeq = -1;
    private boolean eof;
    private boolean writingClosed;
    private boolean finSent;
    private boolean finAcknowledged;
    private boolean readingClosed;
    private Optional<String> failure = Optional.empty();
    private long maxWindow = 2L * MSS;
    private long slowStartLimit = MAX_WINDOW;
    private boolean slowStart = true;
    private long peerWindow = MSS;
    private long rtt = -1;
    private long rttVariance;
    private long rto = INITIAL_RTO;
    private long lastLoss;
    private int lastAck;
    private int duplicateAcks;
    private int replyDelayMicros;
    private long lastSent;
    private long lastHeard;
    private long closedAt;
    private long delayMinuteStart;
    private long ourDelayMicros;
    private long advertised = RECEIVE_BUFFER;
    private long readTimeoutNanos;

    private UtpConnection(UtpSocket socket, InetSocketAddress remote, int receiveId, int sendId, State state) {
        this.socket = socket;
        this.remote = remote;
        this.receiveId = receiveId;
        this.sendId = sendId;
        this.state = state;
        long now = socket.nanos();
        this.lastHeard = now;
        this.lastSent = now;
        this.delayMinuteStart = now;
    }

    static UtpConnection outgoing(UtpSocket socket, InetSocketAddress remote, int receiveId) {
        UtpConnection c = new UtpConnection(socket, remote, receiveId, (receiveId + 1) & 0xFFFF, State.SYN_SENT);
        c.seq = 1;
        return c;
    }

    static UtpConnection incoming(UtpSocket socket, InetSocketAddress remote, UtpPacket syn) {
        UtpConnection c = new UtpConnection(
                socket, remote, (syn.connectionId() + 1) & 0xFFFF, syn.connectionId(), State.CONNECTED);
        c.ack = syn.seq();
        c.seq = socket.random16();
        c.firstSeq = c.seq;
        c.lastAck = (c.seq - 1) & 0xFFFF;
        c.peerWindow = syn.window();
        c.replyDelayMicros = c.nowMicros() - syn.timestamp();
        return c;
    }

    // Says the first word: the SYN, or the answer to it.
    void start() {
        this.lock.lock();
        try {
            if (this.state == State.SYN_SENT) {
                Out syn = new Out(UtpPacket.Type.SYN, this.seq, new byte[0]);
                this.seq = next(this.seq);
                this.inFlight.add(syn);
                transmit(syn);
            } else {
                sendState(this.firstSeq);
                this.connected.complete(this);
            }
        } finally {
            this.lock.unlock();
        }
    }

    /** Completes once the other side answered. */
    public CompletableFuture<UtpConnection> connected() {
        return this.connected.copy();
    }

    int receiveId() {
        return this.receiveId;
    }

    @Override
    public InputStream input() {
        return this.input;
    }

    @Override
    public OutputStream output() {
        return this.output;
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
        this.lock.lock();
        try {
            this.readTimeoutNanos = timeout.toNanos();
        } finally {
            this.lock.unlock();
        }
    }

    /** Sends what was written, then the end; reads fail from now on. */
    @Override
    public void close() {
        this.lock.lock();
        try {
            if (this.readingClosed) {
                return;
            }
            this.readingClosed = true;
            this.writingClosed = true;
            this.closedAt = this.socket.nanos();
            this.readable.signalAll();
            this.writable.signalAll();
            if (this.state == State.SYN_SENT) {
                end("closed before it connected", true);
                return;
            }
            pump();
        } finally {
            this.lock.unlock();
        }
    }

    /** Ends the connection at once, telling the other side. */
    void abort(String reason) {
        this.lock.lock();
        try {
            end(reason, true);
        } finally {
            this.lock.unlock();
        }
    }

    void received(UtpPacket packet) {
        this.lock.lock();
        try {
            if (this.state == State.CLOSED) {
                return;
            }
            long now = this.socket.nanos();
            this.lastHeard = now;
            if (packet.type() == UtpPacket.Type.RESET) {
                end("reset by " + this.remote, false);
                return;
            }
            if (packet.type() == UtpPacket.Type.SYN) {
                // Our answer was lost: the SYN comes again.
                sendState(this.firstSeq);
                return;
            }
            this.replyDelayMicros = nowMicros() - packet.timestamp();
            this.peerWindow = packet.window();
            if (this.state == State.SYN_SENT) {
                Out syn = this.inFlight.peekFirst();
                if (packet.type() != UtpPacket.Type.STATE || syn == null || packet.ack() != syn.seq) {
                    return;
                }
                // The answer to our SYN names the first sequence number to come.
                this.ack = (packet.seq() - 1) & 0xFFFF;
                this.lastAck = syn.seq;
                this.state = State.CONNECTED;
                this.connected.complete(this);
            }
            acknowledged(packet, now);
            if (packet.type() == UtpPacket.Type.DATA || packet.type() == UtpPacket.Type.FIN) {
                if (packet.type() == UtpPacket.Type.FIN) {
                    this.finSeq = packet.seq();
                }
                arrived(packet.seq(), packet.payload().toByteArray());
                sendState(this.seq);
            }
            pump();
            finishIfDone(now);
        } finally {
            this.lock.unlock();
        }
    }

    // What the other side acknowledged, cumulatively and selectively; a loss halves the window.
    private void acknowledged(UtpPacket packet, long now) {
        int upTo = packet.ack();
        long bytes = 0;
        long newestSample = -1;
        boolean advanced = after(upTo, this.lastAck);
        while (!this.inFlight.isEmpty() && atMost(this.inFlight.peekFirst().seq, upTo)) {
            Out out = this.inFlight.removeFirst();
            if (!out.acknowledged) {
                bytes += out.payload.length;
                this.inFlightBytes -= out.payload.length;
                if (out.transmissions == 1) {
                    newestSample = now - out.sentAt;
                }
            }
            if (out.type == UtpPacket.Type.FIN) {
                this.finAcknowledged = true;
            }
        }
        if (packet.selectiveAck().isPresent()) {
            bytes += selectivelyAcknowledged(upTo, packet.selectiveAck().get(), now);
        }
        if (newestSample >= 0) {
            roundTrip(newestSample);
        }
        if (advanced) {
            this.lastAck = upTo;
            this.duplicateAcks = 0;
        } else if (bytes == 0
                && packet.type() == UtpPacket.Type.STATE
                && upTo == this.lastAck
                && !this.inFlight.isEmpty()
                && ++this.duplicateAcks == DUPLICATE_ACKS) {
            lost(this.inFlight.peekFirst(), now);
        }
        if (bytes > 0) {
            if (packet.timestampDifference() != 0) {
                delaySample(Integer.toUnsignedLong(packet.timestampDifference()), now);
            }
            grow(bytes);
            this.writable.signalAll();
        }
    }

    private static boolean bit(Bytes mask, int i) {
        return ((mask.get(i / 8) & 0xFF) >>> (i % 8) & 1) != 0;
    }

    // Bit i of the mask is the packet at upTo + 2 + i; three acknowledged past one we wait for mean it was lost.
    private long selectivelyAcknowledged(int upTo, Bytes mask, long now) {
        long bytes = 0;
        int newest = -1;
        for (int i = 0; i < mask.length() * 8; i++) {
            if (bit(mask, i)) {
                newest = i;
            }
        }
        for (Out out : this.inFlight) {
            int offset = diff(out.seq, upTo) - 2;
            if (offset < 0 || offset >= mask.length() * 8) {
                continue;
            }
            if (bit(mask, offset) && !out.acknowledged) {
                out.acknowledged = true;
                bytes += out.payload.length;
                this.inFlightBytes -= out.payload.length;
            }
        }
        for (Out out : this.inFlight) {
            if (out.acknowledged) {
                continue;
            }
            int offset = diff(out.seq, upTo) - 2;
            int passed = 0;
            for (int i = Math.max(offset + 1, 0); i <= newest; i++) {
                if (bit(mask, i)) {
                    passed++;
                }
            }
            if (passed >= DUPLICATE_ACKS && out.passed < DUPLICATE_ACKS) {
                lost(out, now);
            }
            out.passed = passed;
        }
        return bytes;
    }

    // RFC 6298's estimator.
    private void roundTrip(long sample) {
        if (this.rtt < 0) {
            this.rtt = sample;
            this.rttVariance = sample / 2;
        } else {
            this.rttVariance += (Math.abs(this.rtt - sample) - this.rttVariance) / 4;
            this.rtt += (sample - this.rtt) / 8;
        }
        this.rto = Math.clamp(this.rtt + 4 * this.rttVariance, MIN_RTO, MAX_RTO);
    }

    private void delaySample(long micros, long now) {
        if (now - this.delayMinuteStart > DELAY_BUCKET) {
            this.delayMinutes[0] = this.delayMinutes[1];
            this.delayMinutes[1] = Long.MAX_VALUE;
            this.delayMinuteStart = now;
        }
        this.delayMinutes[1] = Math.min(this.delayMinutes[1], micros);
        long base = Math.min(this.delayMinutes[0], this.delayMinutes[1]);
        this.ourDelayMicros = micros - base;
    }

    // Slow start until the delay nears the target or a loss; then LEDBAT, at most a few kilobytes more a round trip.
    private void grow(long bytes) {
        if (this.slowStart) {
            if (this.ourDelayMicros > TARGET_DELAY_MICROS * 9 / 10 || this.maxWindow >= this.slowStartLimit) {
                this.slowStart = false;
            } else {
                this.maxWindow = Math.min(this.maxWindow + bytes, MAX_WINDOW);
                return;
            }
        }
        double offTarget = (double) (TARGET_DELAY_MICROS - this.ourDelayMicros) / TARGET_DELAY_MICROS;
        double windowFactor = (double) Math.min(bytes, this.maxWindow) / Math.max(this.maxWindow, bytes);
        long change = Math.round(MAX_INCREASE_PER_RTT * windowFactor * offTarget);
        this.maxWindow = Math.clamp(this.maxWindow + change, MSS, MAX_WINDOW);
    }

    // At most one loss a round trip shrinks the window.
    private void lost(Out out, long now) {
        if (now - this.lastLoss > Math.max(this.rtt, MIN_RTO)) {
            this.lastLoss = now;
            this.maxWindow = Math.max(this.maxWindow / 2, 2L * MSS);
            this.slowStartLimit = this.maxWindow;
            this.slowStart = false;
        }
        transmit(out);
    }

    private void arrived(int at, byte[] payload) {
        int distance = diff(at, this.ack);
        if (distance == 0 || distance >= 0x8000) {
            return;
        }
        if (distance == 1) {
            if (!take(payload)) {
                return;
            }
            this.ack = at;
            byte[] waiting;
            while ((waiting = this.reordered.remove(next(this.ack))) != null) {
                this.reorderedBytes -= waiting.length;
                take(waiting);
                this.ack = next(this.ack);
            }
            if (this.finSeq >= 0 && this.ack == this.finSeq) {
                this.eof = true;
            }
            this.readable.signalAll();
        } else if (distance <= REORDER
                && this.toReadBytes + this.reorderedBytes + payload.length <= RECEIVE_BUFFER
                && this.reordered.putIfAbsent(at, payload) == null) {
            this.reorderedBytes += payload.length;
        }
    }

    // Data with no room is dropped: the other side sends it again.
    private boolean take(byte[] payload) {
        if (this.toReadBytes + this.reorderedBytes + payload.length > RECEIVE_BUFFER) {
            return false;
        }
        if (payload.length > 0) {
            this.toRead.add(payload);
            this.toReadBytes += payload.length;
        }
        return true;
    }

    // Sends what the windows allow; with nothing in flight one packet goes whatever they say, as a probe.
    private void pump() {
        if (this.state != State.CONNECTED) {
            return;
        }
        long window = Math.min(this.maxWindow, this.peerWindow);
        while (this.toSendBytes > 0 && this.inFlight.size() < REORDER) {
            int size = (int) Math.min(MSS, this.toSendBytes);
            if (this.inFlightBytes > 0 && this.inFlightBytes + size > window) {
                break;
            }
            Out out = new Out(UtpPacket.Type.DATA, this.seq, takeToSend(size));
            this.seq = next(this.seq);
            this.inFlight.add(out);
            this.inFlightBytes += out.payload.length;
            transmit(out);
            this.writable.signalAll();
        }
        if (this.writingClosed && !this.finSent && this.toSendBytes == 0 && this.failure.isEmpty()) {
            Out fin = new Out(UtpPacket.Type.FIN, this.seq, new byte[0]);
            this.seq = next(this.seq);
            this.inFlight.add(fin);
            this.finSent = true;
            transmit(fin);
        }
    }

    private byte[] takeToSend(int size) {
        byte[] payload = new byte[size];
        int filled = 0;
        while (filled < size) {
            byte[] head = this.toSend.peekFirst();
            int n = Math.min(size - filled, head.length - this.toSendOffset);
            System.arraycopy(head, this.toSendOffset, payload, filled, n);
            filled += n;
            this.toSendOffset += n;
            if (this.toSendOffset == head.length) {
                this.toSend.removeFirst();
                this.toSendOffset = 0;
            }
        }
        this.toSendBytes -= size;
        return payload;
    }

    private void transmit(Out out) {
        out.sentAt = this.socket.nanos();
        out.transmissions++;
        send(out.type, out.type == UtpPacket.Type.SYN ? this.receiveId : this.sendId, out.seq, out.payload);
    }

    private void sendState(int seqNr) {
        send(UtpPacket.Type.STATE, this.sendId, seqNr, new byte[0]);
    }

    private void send(UtpPacket.Type type, int connectionId, int seqNr, byte[] payload) {
        long window = receiveWindow();
        this.advertised = window;
        this.lastSent = this.socket.nanos();
        this.socket.send(
                this.remote,
                new UtpPacket(
                        type,
                        connectionId,
                        nowMicros(),
                        this.replyDelayMicros,
                        window,
                        seqNr,
                        this.ack,
                        selectiveAck(),
                        Bytes.of(payload)));
    }

    private long receiveWindow() {
        return Math.max(0, RECEIVE_BUFFER - this.toReadBytes - this.reorderedBytes);
    }

    private Optional<Bytes> selectiveAck() {
        if (this.reordered.isEmpty()) {
            return Optional.empty();
        }
        int highest = -1;
        for (int at : this.reordered.keySet()) {
            highest = Math.max(highest, diff(at, this.ack) - 2);
        }
        if (highest < 0) {
            return Optional.empty();
        }
        int length = Math.min(MAX_SACK_BYTES, (highest / 32 + 1) * 4);
        byte[] mask = new byte[length];
        for (int at : this.reordered.keySet()) {
            int bit = diff(at, this.ack) - 2;
            if (bit >= 0 && bit < length * 8) {
                mask[bit / 8] |= (byte) (1 << (bit % 8));
            }
        }
        return Optional.of(Bytes.of(mask));
    }

    void tick() {
        this.lock.lock();
        try {
            if (this.state == State.CLOSED) {
                return;
            }
            long now = this.socket.nanos();
            Out oldest = firstUnacknowledged();
            if (oldest != null && now - oldest.sentAt > this.rto) {
                int limit = oldest.type == UtpPacket.Type.SYN ? SYN_TRANSMISSIONS : MAX_TRANSMISSIONS;
                if (oldest.transmissions >= limit) {
                    end("no answer from " + this.remote, true);
                    return;
                }
                // A timeout: the window starts again from one packet, and the timer doubles.
                this.slowStartLimit = Math.max(this.maxWindow / 2, 2L * MSS);
                this.maxWindow = MSS;
                this.slowStart = true;
                this.rto = Math.min(this.rto * 2, MAX_RTO);
                transmit(oldest);
            }
            if (this.state == State.CONNECTED && now - this.lastSent > KEEPALIVE) {
                sendState(this.seq);
            }
            if (now - this.lastHeard > SILENCE) {
                end("silent " + this.remote, true);
                return;
            }
            pump();
            finishIfDone(now);
        } finally {
            this.lock.unlock();
        }
    }

    private Out firstUnacknowledged() {
        for (Out out : this.inFlight) {
            if (!out.acknowledged) {
                return out;
            }
        }
        return null;
    }

    // Closed here, and the end acknowledged and the other side's received, or lingered long enough: forgotten.
    private void finishIfDone(long now) {
        if (!this.readingClosed) {
            return;
        }
        boolean done = this.finAcknowledged && this.eof;
        if (done || now - this.closedAt > LINGER) {
            this.state = State.CLOSED;
            this.socket.forget(this);
        }
    }

    private void end(String reason, boolean tell) {
        if (this.state == State.CLOSED) {
            return;
        }
        if (tell && this.state == State.CONNECTED) {
            send(UtpPacket.Type.RESET, this.sendId, this.seq, new byte[0]);
        }
        this.state = State.CLOSED;
        if (this.failure.isEmpty()) {
            this.failure = Optional.of(reason);
        }
        this.connected.completeExceptionally(new IOException("uTP: " + reason));
        this.readable.signalAll();
        this.writable.signalAll();
        this.socket.forget(this);
    }

    private int nowMicros() {
        return (int) TimeUnit.NANOSECONDS.toMicros(this.socket.nanos());
    }

    static int next(int s) {
        return (s + 1) & 0xFFFF;
    }

    // How far a lies past b, modulo 2^16.
    static int diff(int a, int b) {
        return (a - b) & 0xFFFF;
    }

    static boolean after(int a, int b) {
        int d = diff(a, b);
        return d != 0 && d < 0x8000;
    }

    static boolean atMost(int a, int b) {
        return !after(a, b);
    }

    private final class Input extends InputStream {
        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            if (len == 0) {
                return 0;
            }
            UtpConnection c = UtpConnection.this;
            c.lock.lock();
            try {
                long deadline = c.readTimeoutNanos > 0 ? System.nanoTime() + c.readTimeoutNanos : 0;
                while (c.toReadBytes == 0) {
                    if (c.eof) {
                        return -1;
                    }
                    if (c.readingClosed) {
                        throw new SocketException("closed");
                    }
                    if (c.failure.isPresent()) {
                        throw new IOException("uTP: " + c.failure.get());
                    }
                    if (deadline == 0) {
                        c.readable.await();
                    } else {
                        long left = deadline - System.nanoTime();
                        if (left <= 0 || !c.readable.await(left, TimeUnit.NANOSECONDS)) {
                            throw new SocketTimeoutException("read timed out");
                        }
                    }
                }
                int copied = 0;
                while (copied < len && !c.toRead.isEmpty()) {
                    byte[] head = c.toRead.peekFirst();
                    int n = Math.min(len - copied, head.length - c.toReadOffset);
                    System.arraycopy(head, c.toReadOffset, b, off + copied, n);
                    copied += n;
                    c.toReadOffset += n;
                    if (c.toReadOffset == head.length) {
                        c.toRead.removeFirst();
                        c.toReadOffset = 0;
                    }
                }
                c.toReadBytes -= copied;
                // A window that was nearly shut opens again: the other side hears it.
                if (c.advertised < 2L * MSS && c.receiveWindow() >= 4L * MSS && c.state == State.CONNECTED) {
                    c.sendState(c.seq);
                }
                return copied;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            } finally {
                c.lock.unlock();
            }
        }
    }

    private final class Output extends OutputStream {
        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            UtpConnection c = UtpConnection.this;
            c.lock.lock();
            try {
                int at = off;
                int left = len;
                while (left > 0) {
                    if (c.failure.isPresent()) {
                        throw new IOException("uTP: " + c.failure.get());
                    }
                    if (c.writingClosed) {
                        throw new SocketException("closed");
                    }
                    long room = SEND_BUFFER - c.toSendBytes;
                    if (room <= 0) {
                        c.writable.await();
                        continue;
                    }
                    int n = (int) Math.min(room, left);
                    byte[] chunk = new byte[n];
                    System.arraycopy(b, at, chunk, 0, n);
                    c.toSend.add(chunk);
                    c.toSendBytes += n;
                    at += n;
                    left -= n;
                    c.pump();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            } finally {
                c.lock.unlock();
            }
        }

        @Override
        public void flush() {
            UtpConnection c = UtpConnection.this;
            c.lock.lock();
            try {
                c.pump();
            } finally {
                c.lock.unlock();
            }
        }
    }

    @Override
    public String toString() {
        return "uTP " + this.remote + " " + this.receiveId;
    }
}
