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
package org.njmule.bench;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.ShortBufferException;
import org.bouncycastle.crypto.engines.RC4Engine;
import org.bouncycastle.crypto.params.KeyParameter;
import org.njmule.core.crypto.Rc4;
import org.njmule.core.protocol.packet.FrameLimit;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.packet.TcpFraming;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Reading and writing the 10 KB data frames of an obfuscated connection, as the TCP reader and writer do; one operation
 * is 100 frames (about 1 MiB), so ops/s is about MiB/s.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class ObfuscatedStreamBench {

    private static final int FRAMES = 100;
    private static final int PAYLOAD = 10_240;
    private static final int BUFFER = 16 * 1024;
    private static final FrameLimit LIMIT = FrameLimit.fixed(PAYLOAD);

    private final byte[] key = new byte[16];
    private final List<Packet> packets = new ArrayList<>();
    private final ByteArrayOutputStream sink = new ByteArrayOutputStream(FRAMES * (PAYLOAD + 16));
    private byte[] wire;

    @Setup
    public void setUp() throws IOException {
        SplittableRandom random = new SplittableRandom(42);
        random.nextBytes(this.key);
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        for (int i = 0; i < FRAMES; i++) {
            byte[] payload = new byte[PAYLOAD];
            random.nextBytes(payload);
            Packet packet = Packet.of(Protocol.EMULE, 0x40, payload);
            this.packets.add(packet);
            frames.write(TcpFraming.encode(packet));
        }
        this.wire = frames.toByteArray();
        new Rc4(this.key).apply(this.wire, 0, this.wire.length);
    }

    /** Today's code: the JDK's CipherInputStream, which decrypts 512 bytes at a time into its own buffer. */
    @Benchmark
    public int readJdkCipherInputStream() throws IOException {
        return readAll(new CipherInputStream(socket(), new Rc4(this.key).cipher()));
    }

    /** The same JDK cipher applied in place on what the buffered stream returns. */
    @Benchmark
    public int readJdkCipherInPlace() throws IOException {
        Rc4 rc4 = new Rc4(this.key);
        return readAll(new InPlace(socket(), rc4::apply));
    }

    @Benchmark
    public int readBouncyCastleInPlace() throws IOException {
        RC4Engine rc4 = new RC4Engine();
        rc4.init(false, new KeyParameter(this.key));
        return readAll(new InPlace(socket(), (b, off, len) -> rc4.processBytes(b, off, len, b, off)));
    }

    /** Today's code: the JDK's CipherOutputStream over the buffered socket stream. */
    @Benchmark
    public int writeJdkCipherOutputStream() throws IOException {
        this.sink.reset();
        return writeAll(new CipherOutputStream(socketOut(), new Rc4(this.key).cipher()), (b, off, len) -> {});
    }

    /** Each new frame encrypted in place with the same JDK cipher, then written. */
    @Benchmark
    public int writeJdkCipherInPlace() throws IOException {
        this.sink.reset();
        Rc4 rc4 = new Rc4(this.key);
        return writeAll(socketOut(), rc4::apply);
    }

    @Benchmark
    public int writeBouncyCastleInPlace() throws IOException {
        this.sink.reset();
        RC4Engine rc4 = new RC4Engine();
        rc4.init(true, new KeyParameter(this.key));
        return writeAll(socketOut(), (b, off, len) -> rc4.processBytes(b, off, len, b, off));
    }

    private int writeAll(OutputStream out, KeyStream keyStream) throws IOException {
        try {
            for (Packet packet : this.packets) {
                byte[] frame = TcpFraming.encode(packet);
                keyStream.apply(frame, 0, frame.length);
                out.write(frame);
            }
            out.flush();
        } catch (ShortBufferException e) {
            throw new IOException(e);
        }
        return this.sink.size();
    }

    private OutputStream socketOut() {
        return new BufferedOutputStream(this.sink, BUFFER);
    }

    private InputStream socket() {
        return new BufferedInputStream(new ByteArrayInputStream(this.wire), BUFFER);
    }

    private static int readAll(InputStream in) throws IOException {
        int total = 0;
        for (int i = 0; i < FRAMES; i++) {
            total += TcpFraming.read(in, LIMIT).length();
        }
        return total;
    }

    @FunctionalInterface
    private interface KeyStream {
        void apply(byte[] data, int offset, int length) throws ShortBufferException;
    }

    private static final class InPlace extends FilterInputStream {

        private final KeyStream keyStream;

        InPlace(InputStream in, KeyStream keyStream) {
            super(in);
            this.keyStream = keyStream;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = this.in.read(b, off, len);
            if (n > 0) {
                try {
                    this.keyStream.apply(b, off, n);
                } catch (ShortBufferException e) {
                    throw new IOException(e);
                }
            }
            return n;
        }
    }
}
