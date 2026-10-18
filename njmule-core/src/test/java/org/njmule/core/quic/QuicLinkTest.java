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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.google.common.net.InetAddresses;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.njmule.core.api.Hash16;

@Timeout(60)
class QuicLinkTest {

    private static final InetSocketAddress NEAR = endpoint("198.51.100.10", 4672);
    private static final InetSocketAddress FAR = endpoint("203.0.113.20", 4682);
    private static final Hash16 NEAR_HASH = Hash16.parseHex("aa".repeat(16));
    private static final Hash16 FAR_HASH = Hash16.parseHex("bb".repeat(16));
    private static final Hash16 OTHER_HASH = Hash16.parseHex("cc".repeat(16));

    private final ScheduledExecutorService wire = Executors.newSingleThreadScheduledExecutor();
    private final Map<InetSocketAddress, QuicLink> links = new ConcurrentHashMap<>();
    private final BlockingQueue<QuicTransport> accepted = new LinkedBlockingQueue<>();
    private volatile boolean farTakesConnections = true;
    private volatile Optional<Hash16> farExpects = Optional.empty();
    private CompletableFuture<QuicTransport> connecting;
    private QuicTransport nearSide;
    private QuicTransport farSide;
    private Throwable exception;

    @AfterEach
    void stop() {
        for (QuicLink link : this.links.values()) {
            link.close();
        }
        this.wire.shutdownNow();
    }

    @Test
    void shouldCarryBytesBothWaysOnceBothSidesProvedWhoTheyAre() throws Exception {
        givenTwoClients();
        givenTheFarOneExpects(NEAR_HASH);

        whenTheNearOneConnects(FAR_HASH);

        thenBothSidesHoldTheConnection();
        thenBytesCross(this.nearSide, this.farSide, "file request");
        thenBytesCross(this.farSide, this.nearSide, "file answer");
    }

    @Test
    void shouldHandNothingOnWhenTheClientProvesAnotherHashThanExpected() throws Exception {
        givenTwoClients();
        givenTheFarOneExpects(OTHER_HASH);

        whenTheNearOneConnects(FAR_HASH);

        thenTheFarOneTakesNothingWithinSeconds(3);
        thenTheNearOneEndsWithoutAConnection();
    }

    @Test
    void shouldFailWhenTheOtherSideProvesAnotherHashThanExpected() throws Exception {
        givenTwoClients();

        whenTheNearOneConnects(OTHER_HASH);

        thenTheNearOneEndsWithoutAConnection();
        thenTheFarOneTakesNothingWithinSeconds(1);
    }

    @Test
    void shouldTakeNoConnectionFromAHostItDoesNotWaitFor() throws Exception {
        givenTwoClients();
        givenTheFarOneTakesNoConnections();

        whenTheNearOneConnects(FAR_HASH);

        thenTheFarOneTakesNothingWithinSeconds(3);
        thenTheConnectionIsNotOpen();
    }

    @Test
    void shouldTimeOutAReadThatWaitsTooLongAndStayOpen() throws Exception {
        givenTwoClients();
        givenAConnection();
        givenTheFarSideWaitsAtMost(Duration.ofMillis(200));

        whenTheFarSideReads();

        thenTheReadTimedOut();
        thenBytesCross(this.nearSide, this.farSide, "later");
    }

    private void givenTwoClients() {
        link(NEAR, NEAR_HASH, false);
        link(FAR, FAR_HASH, true);
    }

    private void givenTheFarOneExpects(Hash16 hash) {
        this.farExpects = Optional.of(hash);
    }

    private void givenTheFarOneTakesNoConnections() {
        this.farTakesConnections = false;
    }

    private void givenAConnection() throws Exception {
        whenTheNearOneConnects(FAR_HASH);
        thenBothSidesHoldTheConnection();
    }

    private void givenTheFarSideWaitsAtMost(Duration timeout) {
        this.farSide.setReadTimeout(timeout);
    }

    private void whenTheNearOneConnects(Hash16 expected) {
        this.connecting = this.links.get(NEAR).connect(FAR, Optional.of(expected));
    }

    private void whenTheFarSideReads() {
        this.exception = catchThrowable(() -> this.farSide.input().read());
    }

    private void thenBothSidesHoldTheConnection() throws Exception {
        this.nearSide = this.connecting.get(30, TimeUnit.SECONDS);
        this.farSide = this.accepted.poll(30, TimeUnit.SECONDS);
        assertThat(this.farSide).isNotNull();
        assertThat(this.nearSide.remoteAddress()).isEqualTo(FAR);
        assertThat(this.farSide.remoteAddress()).isEqualTo(NEAR);
    }

    private void thenBytesCross(QuicTransport from, QuicTransport to, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        from.output().write(bytes);
        from.output().flush();
        to.setReadTimeout(Duration.ofSeconds(10));
        assertThat(new String(to.input().readNBytes(bytes.length), StandardCharsets.US_ASCII))
                .isEqualTo(text);
    }

    private void thenTheFarOneTakesNothingWithinSeconds(int seconds) throws InterruptedException {
        assertThat(this.accepted.poll(seconds, TimeUnit.SECONDS)).isNull();
    }

    // Either the connection fails, or it closes once the other side refused the proof.
    private void thenTheNearOneEndsWithoutAConnection() throws InterruptedException {
        QuicTransport opened;
        try {
            opened = this.connecting.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            return;
        }
        opened.setReadTimeout(Duration.ofSeconds(20));
        Throwable ended = catchThrowable(() -> assertThat(opened.input().read()).isEqualTo(-1));
        if (ended != null) {
            assertThat(ended).isInstanceOf(IOException.class).isNotInstanceOf(SocketTimeoutException.class);
        }
    }

    private void thenTheConnectionIsNotOpen() {
        assertThat(this.connecting).isNotCompleted();
    }

    private void thenTheReadTimedOut() {
        assertThat(this.exception).isInstanceOf(SocketTimeoutException.class);
    }

    private void link(InetSocketAddress at, Hash16 hash, boolean far) {
        QuicLink link = new QuicLink(
                (to, datagram) -> send(at, to, datagram), () -> hash, far ? new Far() : new Near(), new SecureRandom());
        this.links.put(at, link);
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private void send(InetSocketAddress from, InetSocketAddress to, byte[] datagram) {
        this.wire.schedule(
                () -> {
                    QuicLink link = this.links.get(to);
                    if (link != null) {
                        link.received(from, datagram);
                    }
                },
                1,
                TimeUnit.MILLISECONDS);
    }

    private static InetSocketAddress endpoint(String ip, int port) {
        return new InetSocketAddress(InetAddresses.forString(ip), port);
    }

    // Opens connections only.
    private static final class Near implements QuicLink.Peers {
        @Override
        public boolean mayConnect(InetSocketAddress from) {
            return false;
        }

        @Override
        public Optional<Hash16> expected(InetSocketAddress from) {
            return Optional.empty();
        }

        @Override
        public void accepted(QuicTransport connection) {
            connection.close();
        }
    }

    private final class Far implements QuicLink.Peers {
        @Override
        public boolean mayConnect(InetSocketAddress from) {
            return QuicLinkTest.this.farTakesConnections;
        }

        @Override
        public Optional<Hash16> expected(InetSocketAddress from) {
            return QuicLinkTest.this.farExpects;
        }

        @Override
        public void accepted(QuicTransport connection) {
            QuicLinkTest.this.accepted.add(connection);
        }
    }
}
