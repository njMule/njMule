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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.google.common.base.Ticker;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(120)
class UtpSocketTest {

    private static final InetSocketAddress NEAR = new InetSocketAddress(InetAddress.getLoopbackAddress(), 1001);
    private static final InetSocketAddress FAR = new InetSocketAddress(InetAddress.getLoopbackAddress(), 1002);

    private final ScheduledExecutorService wire = Executors.newSingleThreadScheduledExecutor();
    private final Random chance = new Random(7);
    private final BlockingQueue<UtpConnection> accepted = new LinkedBlockingQueue<>();
    private volatile double loss;
    private volatile int jitterMillis;
    private volatile boolean duplicates;
    private UtpSocket near;
    private UtpSocket far;
    private UtpConnection nearSide;
    private UtpConnection farSide;
    private byte[] sent;
    private byte[] got;
    private Throwable exception;
    private CompletableFuture<UtpConnection> attempt;

    @AfterEach
    void stop() {
        for (UtpSocket s : new UtpSocket[] {this.near, this.far}) {
            if (s != null) {
                s.close();
            }
        }
        this.wire.shutdownNow();
    }

    @Test
    void shouldConnectAndCarryBytesBothWays() throws Exception {
        givenTwoSocketsOnAWire(true);
        givenTheNearSideConnected();

        whenEachSideSendsAMessage();

        thenEachSideReadsTheOther();
    }

    @Test
    void shouldCarryMegabytesIntactOverALossyReorderingWire() throws Exception {
        givenTwoSocketsOnAWire(true);
        givenTheWireLosesReordersAndDuplicates(0.05, 15);
        givenTheNearSideConnected();

        whenTheNearSideSendsBytes(4 << 20);

        thenTheFarSideGetsThemAll();
    }

    @Test
    void shouldEndTheStreamOnceTheOtherSideClosesAfterItsData() throws Exception {
        givenTwoSocketsOnAWire(true);
        givenTheNearSideConnected();

        whenTheNearSideSendsAndCloses(300_000);

        thenTheFarSideGetsThemAllThenTheEnd();
    }

    @Test
    void shouldTakeNoConnectionFromAHostNobodyExpects() throws Exception {
        givenTwoSocketsOnAWire(false);

        whenTheNearSideTriesToConnect();

        thenNothingIsAcceptedWithin(Duration.ofSeconds(2));
    }

    @Test
    void shouldFailTheReadsOfTheOtherSideOnceOneSideAborts() throws Exception {
        givenTwoSocketsOnAWire(true);
        givenTheNearSideConnected();

        whenTheNearSideAborts();

        thenAReadOnTheFarSideFails();
    }

    @Test
    void shouldTimeOutAReadThatWaitsTooLong() throws Exception {
        givenTwoSocketsOnAWire(true);
        givenTheNearSideConnected();

        whenTheFarSideReadsWithATimeoutOf(Duration.ofMillis(300));

        thenTheReadTimedOut();
    }

    private void givenTwoSocketsOnAWire(boolean farExpectsNear) {
        SecureRandom random = new SecureRandom();
        this.near = new UtpSocket((to, packet) -> carry(packet, NEAR, () -> this.far), Ticker.systemTicker(), random);
        this.far = new UtpSocket((to, packet) -> carry(packet, FAR, () -> this.near), Ticker.systemTicker(), random);
        this.far.accept(from -> farExpectsNear && from.equals(NEAR), this.accepted::add);
    }

    private void givenTheWireLosesReordersAndDuplicates(double lossRate, int jitter) {
        this.loss = lossRate;
        this.jitterMillis = jitter;
        this.duplicates = true;
    }

    private void givenTheNearSideConnected() throws Exception {
        this.nearSide = this.near.connect(FAR).get(20, TimeUnit.SECONDS);
        this.farSide = this.accepted.poll(20, TimeUnit.SECONDS);
        assertThat(this.farSide).as("accepted").isNotNull();
    }

    private void whenEachSideSendsAMessage() throws IOException {
        write(this.nearSide, "hello far".getBytes(StandardCharsets.US_ASCII));
        write(this.farSide, "hello near".getBytes(StandardCharsets.US_ASCII));
    }

    private void whenTheNearSideSendsBytes(int size) throws Exception {
        this.sent = new byte[size];
        new Random(3).nextBytes(this.sent);
        CompletableFuture<byte[]> reading = CompletableFuture.supplyAsync(() -> readExactly(this.farSide, size));
        write(this.nearSide, this.sent);
        this.got = reading.get(100, TimeUnit.SECONDS);
    }

    private void whenTheNearSideSendsAndCloses(int size) throws Exception {
        this.sent = new byte[size];
        new Random(4).nextBytes(this.sent);
        CompletableFuture<byte[]> reading = CompletableFuture.supplyAsync(() -> readToTheEnd(this.farSide));
        write(this.nearSide, this.sent);
        this.nearSide.close();
        this.got = reading.get(60, TimeUnit.SECONDS);
    }

    private void whenTheNearSideTriesToConnect() {
        this.attempt = this.near.connect(FAR);
    }

    private void whenTheNearSideAborts() {
        this.nearSide.abort("test");
    }

    private void whenTheFarSideReadsWithATimeoutOf(Duration timeout) {
        this.farSide.setReadTimeout(timeout);
        this.exception = catchThrowable(() -> this.farSide.input().read());
    }

    private void thenEachSideReadsTheOther() {
        assertThat(new String(readExactly(this.farSide, 9), StandardCharsets.US_ASCII))
                .isEqualTo("hello far");
        assertThat(new String(readExactly(this.nearSide, 10), StandardCharsets.US_ASCII))
                .isEqualTo("hello near");
    }

    private void thenTheFarSideGetsThemAll() throws Exception {
        assertThat(digest(this.got)).isEqualTo(digest(this.sent));
    }

    private void thenTheFarSideGetsThemAllThenTheEnd() throws Exception {
        assertThat(this.got).hasSize(this.sent.length);
        assertThat(digest(this.got)).isEqualTo(digest(this.sent));
    }

    private void thenNothingIsAcceptedWithin(Duration wait) throws InterruptedException {
        assertThat(this.accepted.poll(wait.toMillis(), TimeUnit.MILLISECONDS)).isNull();
        assertThat(this.attempt).isNotDone();
    }

    private void thenAReadOnTheFarSideFails() {
        this.farSide.setReadTimeout(Duration.ofSeconds(10));
        Throwable failure = catchThrowable(() -> this.farSide.input().read());
        assertThat(failure).isInstanceOf(IOException.class).isNotInstanceOf(SocketTimeoutException.class);
    }

    private void thenTheReadTimedOut() {
        assertThat(this.exception).isInstanceOf(SocketTimeoutException.class);
    }

    // The wire: a delay of a few milliseconds, some loss and jitter, and now and then a packet twice. A delivery that
    // fails is a packet lost, as on a real one.
    @SuppressWarnings("FutureReturnValueIgnored")
    private void carry(byte[] packet, InetSocketAddress from, Supplier<UtpSocket> to) {
        if (this.chance.nextDouble() < this.loss) {
            return;
        }
        int copies = this.duplicates && this.chance.nextDouble() < 0.01 ? 2 : 1;
        for (int i = 0; i < copies; i++) {
            long delay = 2L + (this.jitterMillis > 0 ? this.chance.nextInt(this.jitterMillis) : 0);
            this.wire.schedule(() -> to.get().received(from, packet, 0, packet.length), delay, TimeUnit.MILLISECONDS);
        }
    }

    private static void write(UtpConnection connection, byte[] bytes) throws IOException {
        OutputStream out = connection.output();
        out.write(bytes);
        out.flush();
    }

    private static byte[] readExactly(UtpConnection connection, int size) {
        try {
            return connection.input().readNBytes(size);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] readToTheEnd(UtpConnection connection) {
        try (InputStream in = connection.input()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] digest(byte[] bytes) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
    }
}
