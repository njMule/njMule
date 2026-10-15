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
package org.njmule.core.download;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.base.Ticker;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.SourceInfo;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.files.PartFile;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.peer.Identities;
import org.njmule.core.peer.IdentityHandshake;
import org.njmule.core.peer.PeerLinks;
import org.njmule.core.protocol.ed2k.ClientMessages;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.util.Ipv4;

/** A source that gave us an upload slot, on a clock the test moves: the slot lasts while its data comes. */
@Timeout(90)
class DownloadSlotTest {

    private static final int PORT = 4662;
    private static final int OUR_IP = Ipv4.of(198, 51, 100, 7);
    private static final Hash16 PEER = Hash16.parseHex("5b".repeat(16));
    private static final int CHUNK = 10_240;
    // Long enough for a few ticks of the downloads.
    private static final Duration QUIET = Duration.ofSeconds(3);

    @TempDir
    Path dir;

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final Ticker ticker = new Ticker() {
        @Override
        public long read() {
            return DownloadSlotTest.this.now.get();
        }
    };
    private final List<AutoCloseable> resources = new ArrayList<>();
    // Five blocks of one part, three asked for at a time.
    private final byte[] data = random(4 * Ed2k.EMBLOCKSIZE + 30_000);
    private final Hash16 hash = Ed2k.md4(this.data, 0, this.data.length);
    private DownloadManager manager;
    private ScriptedSource source;
    private ScriptedSource.Link link;
    private List<ClientMessages.Range> requested;
    private long sent;

    @AfterEach
    void stop() throws Exception {
        if (this.manager != null) {
            onLoop(() -> {
                this.manager.close();
                return null;
            });
        }
        this.loop.close();
        Collections.reverse(this.resources);
        for (AutoCloseable resource : this.resources) {
            resource.close();
        }
    }

    @Test
    void shouldLeaveASlotWhoseBlocksBringNoDataForTooLongWhateverElseTheSourceSends() throws Exception {
        givenASourceThatGaveUsASlot();

        whenTheSourceTalksWithoutSendingDataFor(DownloadSession.DATA_TIMEOUT);

        thenWeCancelTheTransfer();
        thenTheConnectionCloses();
    }

    @Test
    void shouldKeepASlotWhoseBlocksBringDataInTime() throws Exception {
        givenASourceThatGaveUsASlot();

        whenTheSourceSendsSomeDataAfter(DownloadSession.DATA_TIMEOUT.minusSeconds(10));
        whenTheSourceSendsSomeDataAfter(DownloadSession.DATA_TIMEOUT.minusSeconds(10));

        thenTheSlotIsKept();
    }

    @Test
    void shouldCancelATransferThatBringsBlocksWeDidNotAskFor() throws Exception {
        givenASourceThatGaveUsASlot();

        whenTheSourceSendsDataWeDidNotAskFor(DownloadSession.MAX_UNEXPECTED);

        thenWeCancelTheTransfer();
        thenTheConnectionCloses();
    }

    @Test
    void shouldBearDataWeDidNotAskForThatComesNowAndThen() throws Exception {
        givenASourceThatGaveUsASlot();

        whenTheSourceSendsDataWeDidNotAskForEvery(
                DownloadSession.UNEXPECTED_GAP.plusSeconds(1), DownloadSession.MAX_UNEXPECTED);

        thenTheSlotIsKept();
    }

    @Test
    void shouldAskAgainForABlockWhoseCompressedDataDoesNotInflateAndDropTheRestOfItQuietly() throws Exception {
        givenASourceThatGaveUsASlot();

        whenTheSourceSendsACompressedBlockThatDoesNotInflate(0, 4);

        thenTheBlockIsAskedForAgain(0);
        thenTheSlotIsKept();
    }

    @Test
    void shouldDropASourceWhoseCompressedDataDoesNotInflateAgainAndAgain() throws Exception {
        givenASourceThatGaveUsASlot();

        whenTheSourceSendsACompressedBlockThatDoesNotInflate(0, 1);
        whenTheSourceSendsACompressedBlockThatDoesNotInflate(1, 1);
        whenTheSourceSendsACompressedBlockThatDoesNotInflate(2, 1);

        thenTheConnectionCloses();
        thenTheDownloadHasNoSource();
    }

    private void givenASourceThatGaveUsASlot() throws Exception {
        this.source = new ScriptedSource(PEER, 0, 0);
        this.resources.add(this.source);
        this.manager = open();
        onLoop(() -> this.manager.add(this.source.link("slot.bin", this.data.length, this.hash)));
        this.link = this.source.accept();
        this.link.until(Opcodes.Client.SETREQFILEID);
        this.link.send(new ClientMessages.FileStatus(this.hash, 0, new BitSet()).encode());
        this.link.until(Opcodes.Client.STARTUPLOADREQ);
        this.link.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.ACCEPTUPLOADREQ.code()));
        List<Packet> read = this.link.until(Opcodes.Client.REQUESTPARTS);
        this.requested = ClientMessages.RequestParts.decode(read.getLast()).ranges();
        assertThat(this.requested).allMatch(r -> !r.isEmpty() && r.end() <= 3L * Ed2k.EMBLOCKSIZE);
    }

    private void whenTheSourceTalksWithoutSendingDataFor(Duration silence) throws Exception {
        this.link.send(Packet.of(
                Protocol.EMULE,
                Opcodes.Extended.QUEUERANKING.code(),
                new PacketWriter().u16(1).bytes(new byte[10])));
        this.now.addAndGet(silence.toNanos());
        this.link.send(Packet.of(Protocol.EDONKEY, Opcodes.Client.QUEUERANK.code(), new PacketWriter().u32(1)));
    }

    // The next chunk of the first block asked for, once the clock moved on.
    private void whenTheSourceSendsSomeDataAfter(Duration wait) throws Exception {
        this.now.addAndGet(wait.toNanos());
        long start = this.requested.getFirst().start() + this.sent;
        this.link.send(new ClientMessages.SendingPart(
                        this.hash,
                        start,
                        start + CHUNK,
                        Arrays.copyOfRange(this.data, (int) start, (int) start + CHUNK))
                .encode(false));
        this.sent += CHUNK;
        await("the data received", () -> theSource().received() == this.sent);
    }

    // A chunk of the last block, which is not asked for while the first three are.
    private void whenTheSourceSendsDataWeDidNotAskFor(int times) throws Exception {
        int start = 4 * Ed2k.EMBLOCKSIZE;
        for (int i = 0; i < times; i++) {
            this.link.send(new ClientMessages.SendingPart(
                            this.hash, start, start + CHUNK, Arrays.copyOfRange(this.data, start, start + CHUNK))
                    .encode(false));
        }
    }

    private void whenTheSourceSendsDataWeDidNotAskForEvery(Duration gap, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            whenTheSourceSendsDataWeDidNotAskFor(1);
            // Data asked for, behind it, tells when it was read.
            whenTheSourceSendsSomeDataAfter(Duration.ZERO);
            this.now.addAndGet(gap.toNanos());
        }
    }

    // A stream of {@code chunks} chunks for the block, whose first one is no zlib data.
    private void whenTheSourceSendsACompressedBlockThatDoesNotInflate(int block, int chunks) throws Exception {
        long start = this.requested.get(block).start();
        for (int i = 0; i < chunks; i++) {
            byte[] chunk = new byte[64];
            Arrays.fill(chunk, (byte) 0xFF);
            this.link.send(new ClientMessages.CompressedPart(this.hash, start, 64L * chunks, chunk).encode(false));
        }
    }

    private void thenTheBlockIsAskedForAgain(int block) throws Exception {
        List<Packet> read = this.link.until(Opcodes.Client.REQUESTPARTS);
        assertThat(ClientMessages.RequestParts.decode(read.getLast()).ranges()).contains(this.requested.get(block));
    }

    private void thenTheDownloadHasNoSource() throws Exception {
        await("no source", () -> this.manager.sources().isEmpty());
    }

    private void thenWeCancelTheTransfer() throws Exception {
        this.link.until(Opcodes.Client.CANCELTRANSFER);
    }

    private void thenTheConnectionCloses() throws Exception {
        assertThat(this.link.closesWithin(ScriptedSource.WAIT)).isTrue();
    }

    private void thenTheSlotIsKept() throws Exception {
        Thread.sleep(QUIET.toMillis());
        assertThat(onLoop(() -> theSource().state())).isEqualTo(SourceInfo.State.DOWNLOADING);
    }

    private SourceInfo theSource() {
        return this.manager.sources().getFirst();
    }

    private DownloadManager open() throws Exception {
        SecureRandom random = new SecureRandom();
        Identities identities = new Identities(
                IdentityHandshake.Identity.of(Identities.loadOrCreateKey(this.dir, random)),
                hash -> Optional.empty(),
                (hash, key) -> {},
                Optional::empty,
                random);
        Hello hello = Hello.of(Hash16.parseHex("ab".repeat(16)), Ipv4.toEd2k(OUR_IP), PORT, "test", 0, 0, 0, 0, 0, 0);
        return onLoop(() -> DownloadManager.open(
                this.loop,
                this.dir.resolve("temp"),
                () -> this.dir.resolve("incoming"),
                () -> hello,
                () -> new DownloadManager.Settings(ObfuscationMode.SUPPORTED, 100, PORT, 0, true),
                new NoServer(),
                ip -> false,
                random,
                this.ticker,
                new PeerLinks(this.loop, identities, link -> Optional.empty()),
                (hash, bytes) -> {},
                Optional::empty,
                List::of,
                new Quiet()));
    }

    private void await(String what, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + ScriptedSource.WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (onLoop(condition)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("waited in vain for " + what);
    }

    private <T> T onLoop(Callable<T> task) throws Exception {
        return this.loop.submit(task).get(30, TimeUnit.SECONDS);
    }

    private static byte[] random(int size) {
        byte[] bytes = new byte[size];
        new Random(size).nextBytes(bytes);
        return bytes;
    }

    private static final class NoServer implements DownloadManager.ServerLink {
        @Override
        public boolean askSources(Hash16 file, long size) {
            return false;
        }

        @Override
        public long clientId() {
            return Ipv4.toEd2k(OUR_IP);
        }

        @Override
        public int serverIp() {
            return 0;
        }

        @Override
        public boolean requestCallback(long lowId) {
            return false;
        }
    }

    private static final class Quiet implements DownloadManager.Listener {
        @Override
        public void added(DownloadInfo download) {}

        @Override
        public void progress(DownloadInfo download) {}

        @Override
        public void completed(DownloadInfo download, Path file, PartFile part) {}

        @Override
        public void failed(DownloadInfo download, String reason) {}

        @Override
        public void removed(Hash16 hash) {}
    }
}
