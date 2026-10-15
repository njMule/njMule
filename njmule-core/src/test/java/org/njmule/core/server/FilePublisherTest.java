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
package org.njmule.core.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.base.Ticker;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.njmule.core.api.Hash16;
import org.njmule.core.config.Preferences;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.persist.format.ServerMet;
import org.njmule.core.protocol.ed2k.FileEntry;
import org.njmule.core.protocol.ed2k.ServerMessages;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.ServerTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.protocol.tag.Tags;
import org.njmule.core.util.Ipv4;

/** The files offered to a server a test scripts, with a manual clock between batches. */
@Timeout(30)
class FilePublisherTest {

    private static final long HIGH_ID = Ipv4.toEd2k(Ipv4.parse("1.2.3.4"));
    private static final int LISTEN_PORT = 4662;
    private static final Duration WAIT = Duration.ofSeconds(10);

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final AtomicLong time = new AtomicLong(1_000_000_000_000L);
    private final List<FilePublisher.Offer> files = new ArrayList<>();
    private final CountDownLatch connected = new CountDownLatch(1);
    private final List<FileEntry> offered = new ArrayList<>();
    private ScriptedServer server;
    private Optional<Packet> nextOffer;
    private final ServerSession session = new ServerSession(
            this.loop,
            new ServerList(),
            Preferences::defaults,
            Hash16.parseHex("01".repeat(16)),
            () -> LISTEN_PORT,
            new Connected(),
            () -> false,
            new SecureRandom());
    private final FilePublisher publisher =
            new FilePublisher(this.session, () -> List.copyOf(this.files), () -> LISTEN_PORT, new Ticker() {
                @Override
                public long read() {
                    return FilePublisherTest.this.time.get();
                }
            });

    @AfterEach
    void stop() throws Exception {
        onLoop(() -> {
            this.session.disconnect();
            return null;
        });
        this.loop.close();
        if (this.server != null) {
            this.server.close();
        }
    }

    @Test
    void shouldOfferAHighIdAddressAndLargeFilesOnlyToServersThatTakeThem() throws Exception {
        givenTheSharedFiles(
                new FilePublisher.Offer(hash(1), "film.avi", 1000, true),
                new FilePublisher.Offer(hash(2), "huge.iso", 5_000_000_000L, true));
        givenALoginToAServerGiving(HIGH_ID, 0);
        givenTheServerWasOfferedOnlyTheFile(hash(1), HIGH_ID, LISTEN_PORT, "Video");

        whenTheFilesAreDueBeforeTheInterval();

        thenNothingIsOffered("nothing new");
    }

    @Test
    void shouldOfferALowIdWithoutAddressAndInBatchesThatWaitTheirTurn() throws Exception {
        givenLargeSharedFilesOfWhichTheFirstIsIncomplete(FilePublisher.BATCH + 1);
        givenALoginToAServerGiving(1234, ServerMessages.TCP_LARGEFILES | ServerMessages.TCP_TYPE_TAG_INTEGER);
        givenTheServerWasOfferedAFullBatchWithoutOurAddress();
        givenNothingWasOfferedBeforeTheInterval("the next batch waits a minute");
        givenTheIntervalPassed();
        givenTheServerWasOffered(hash(FilePublisher.BATCH + 10));
        givenTheIntervalPassed();
        givenTheFileCompleted(hash(10));

        whenTheFilesAreDue();

        thenTheServerIsOffered(hash(10), "a file that completed is offered again");
    }

    @Test
    void shouldOfferNoMoreFilesThanTheServerTakes() throws Exception {
        givenSharedFiles(5);
        givenALoginToAServerTakingFiles(3);

        whenTwoBatchesAreDue();

        thenTheServerWasOffered(3);
    }

    private void givenSharedFiles(int count) {
        for (int i = 0; i < count; i++) {
            this.files.add(new FilePublisher.Offer(hash(i + 100), "song " + i + ".mp3", 1000 + i, true));
        }
    }

    private void givenALoginToAServerTakingFiles(long softLimit) throws Exception {
        this.server = ScriptedServer.start(HIGH_ID, 0);
        ServerMet.Server target = new ServerMet.Server(this.server.ip(), this.server.port(), List.of())
                .with(new Tag.IntTag(TagName.id(ServerTag.UDPFLAGS), 0))
                .with(new Tag.IntTag(TagName.id(ServerTag.SOFTFILES), softLimit));
        onLoop(() -> {
            this.session.connect(Optional.of(target));
            return null;
        });
        assertThat(this.connected.await(10, TimeUnit.SECONDS)).isTrue();
    }

    private void whenTwoBatchesAreDue() throws Exception {
        for (int batch = 0; batch < 2; batch++) {
            tick();
            this.server
                    .next(Opcodes.Server.OFFERFILES.code(), Duration.ofSeconds(2))
                    .ifPresent(packet -> this.offered.addAll(offersOf(packet)));
            this.time.addAndGet(FilePublisher.INTERVAL.toNanos());
        }
    }

    private void thenTheServerWasOffered(int files) {
        assertThat(this.offered).hasSize(files);
    }

    private static List<FileEntry> offersOf(Packet packet) {
        try {
            return offers(packet);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void shouldOfferNothingWithoutASession() throws Exception {
        givenTheSharedFiles(new FilePublisher.Offer(hash(1), "film.avi", 1000, true));

        whenThePublisherRunsWithoutASession();

        thenNoSessionIsOpen();
    }

    private void givenTheSharedFiles(FilePublisher.Offer... offers) {
        this.files.addAll(List.of(offers));
    }

    private void givenLargeSharedFilesOfWhichTheFirstIsIncomplete(int count) {
        for (int i = 0; i < count; i++) {
            this.files.add(new FilePublisher.Offer(hash(i + 10), "file " + i + ".iso", 5_000_000_000L + i, i > 0));
        }
    }

    private void givenALoginToAServerGiving(long clientId, int flags) throws Exception {
        this.server = ScriptedServer.start(clientId, flags);
        connect(this.server);
    }

    private void givenTheServerWasOfferedOnlyTheFile(Hash16 hash, long clientId, int port, String typeName)
            throws Exception {
        tick();
        List<FileEntry> first =
                offers(this.server.next(Opcodes.Server.OFFERFILES.code(), WAIT).orElseThrow());
        assertThat(first).singleElement().satisfies(entry -> {
            assertThat(entry.hash()).isEqualTo(hash);
            assertThat(entry.clientId()).isEqualTo(clientId);
            assertThat(entry.port()).isEqualTo(port);
            assertThat(Tags.stringValue(entry.tags(), FileTag.FILETYPE))
                    .as("a type name")
                    .hasValue(typeName);
        });
    }

    private void givenTheServerWasOfferedAFullBatchWithoutOurAddress() throws Exception {
        tick();
        List<FileEntry> first =
                offers(this.server.next(Opcodes.Server.OFFERFILES.code(), WAIT).orElseThrow());
        assertThat(first).hasSize(FilePublisher.BATCH);
        assertThat(first.getFirst().clientId()).isZero();
        assertThat(first.getFirst().port()).isZero();
        assertThat(Tags.intValue(first.getFirst().tags(), 0x3A))
                .as("the size's high bits")
                .hasValue(1);
        assertThat(Tags.intValue(first.getFirst().tags(), FileTag.FILETYPE))
                .as("a type code")
                .isPresent();
    }

    private void givenNothingWasOfferedBeforeTheInterval(String why) throws Exception {
        tick();
        assertThat(this.server.next(Opcodes.Server.OFFERFILES.code(), Duration.ofMillis(500)))
                .as(why)
                .isEmpty();
    }

    private void givenTheIntervalPassed() {
        this.time.addAndGet(FilePublisher.INTERVAL.toNanos());
    }

    private void givenTheServerWasOffered(Hash16... hashes) throws Exception {
        tick();
        assertThat(offers(
                        this.server.next(Opcodes.Server.OFFERFILES.code(), WAIT).orElseThrow()))
                .extracting(FileEntry::hash)
                .containsExactly(hashes);
    }

    private void givenTheFileCompleted(Hash16 hash) throws Exception {
        onLoop(() -> {
            this.publisher.changed(Optional.of(hash));
            return null;
        });
    }

    private void whenTheFilesAreDue() throws Exception {
        tick();
        this.offered.addAll(
                offers(this.server.next(Opcodes.Server.OFFERFILES.code(), WAIT).orElseThrow()));
    }

    private void whenTheFilesAreDueBeforeTheInterval() throws Exception {
        tick();
        this.nextOffer = this.server.next(Opcodes.Server.OFFERFILES.code(), Duration.ofMillis(500));
    }

    private void whenThePublisherRunsWithoutASession() throws Exception {
        onLoop(() -> {
            this.publisher.changed(Optional.empty());
            this.publisher.tick();
            this.publisher.disconnected();
            this.publisher.tick();
            return null;
        });
    }

    private void thenNothingIsOffered(String why) {
        assertThat(this.nextOffer).as(why).isEmpty();
    }

    private void thenTheServerIsOffered(Hash16 hash, String why) {
        assertThat(this.offered).as(why).extracting(FileEntry::hash).containsExactly(hash);
    }

    private void thenNoSessionIsOpen() throws Exception {
        assertThat(onLoop(this.session::isConnected)).isFalse();
    }

    private void connect(ScriptedServer server) throws Exception {
        onLoop(() -> {
            this.session.connect(Optional.of(new ServerMet.Server(server.ip(), server.port(), List.of())));
            return null;
        });
        assertThat(this.connected.await(10, TimeUnit.SECONDS)).isTrue();
    }

    private void tick() throws Exception {
        onLoop(() -> {
            this.publisher.tick();
            return null;
        });
    }

    private <T> T onLoop(Callable<T> task) throws Exception {
        return this.loop.submit(task).get(5, TimeUnit.SECONDS);
    }

    private static List<FileEntry> offers(Packet packet) throws Exception {
        PacketReader in = packet.reader();
        long count = in.u32();
        List<FileEntry> entries = new ArrayList<>();
        for (long i = 0; i < count; i++) {
            entries.add(FileEntry.read(in));
        }
        return entries;
    }

    private static Hash16 hash(int n) {
        byte[] bytes = new byte[16];
        bytes[0] = (byte) (n >> 8);
        bytes[1] = (byte) n;
        bytes[15] = 1;
        return Hash16.of(bytes);
    }

    /** Offers files once logged in, as the engine does. */
    private final class Connected implements ServerSession.Listener {
        @Override
        public void connecting(ServerMet.Server server) {}

        @Override
        public void connected(ServerMet.Server server, ServerMessages.IdChange id) {
            FilePublisherTest.this.publisher.connected();
            FilePublisherTest.this.connected.countDown();
        }

        @Override
        public void disconnected(Optional<ServerMet.Server> server, String reason) {
            FilePublisherTest.this.publisher.disconnected();
        }

        @Override
        public void message(ServerMet.Server server, String line) {}

        @Override
        public void updated(ServerMet.Server server) {}

        @Override
        public void searchResults(ServerMet.Server server, ServerMessages.SearchResults results) {}

        @Override
        public void callbackRequested(ServerMessages.CallbackRequested request) {}

        @Override
        public void foundSources(ServerMessages.FoundSources sources) {}

        @Override
        public void serverList(ServerMet.Server server, List<ServerMessages.Endpoint> servers) {}
    }
}
