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
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.SourceInfo;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.files.PartFile;
import org.njmule.core.peer.Identities;
import org.njmule.core.peer.IdentityHandshake;
import org.njmule.core.peer.PeerLinks;
import org.njmule.core.protocol.ed2k.ClientMessages;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.ed2k.ServerMessages;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.util.Ipv4;

/** Sources are asked only in ways that suit both our obfuscation setting and theirs. */
@Timeout(90)
class DownloadObfuscationTest {

    private static final int PORT = 4662;
    private static final int OUR_IP = Ipv4.of(198, 51, 100, 7);
    private static final Hash16 PEER = Hash16.parseHex("5c".repeat(16));
    private static final Hash16 FILE = Hash16.parseHex("64".repeat(16));
    private static final long FIREWALLED = 4242;
    // The connect options of a hello: supports, requests and requires obfuscation.
    private static final int REQUIRES_OBFUSCATION = 0x07 << 7;
    private static final int SUPPORTS = 0x01;
    // Long enough for a few ticks of the downloads.
    private static final Duration QUIET = Duration.ofSeconds(3);

    @TempDir
    Path dir;

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final Ticker ticker = new Ticker() {
        @Override
        public long read() {
            return DownloadObfuscationTest.this.now.get();
        }
    };
    private final List<AutoCloseable> resources = new ArrayList<>();
    private final List<Long> callbacks = new CopyOnWriteArrayList<>();
    private volatile ObfuscationMode mode = ObfuscationMode.SUPPORTED;
    private DownloadManager manager;
    private ScriptedSource source;

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
    void shouldAskASourceThatCannotObfuscateAgainWhileObfuscationIsOnlySupported() throws Exception {
        givenASourceAskedOnceThatAnnounces(0);

        whenTheSourceIsDueAgainWithObfuscation(ObfuscationMode.SUPPORTED);

        thenTheSourceIsConnectedTo(true);
    }

    @Test
    void shouldNotConnectPlainToASourceThatCannotObfuscateWhenObfuscationIsRequired() throws Exception {
        givenASourceAskedOnceThatAnnounces(0);

        whenTheSourceIsDueAgainWithObfuscation(ObfuscationMode.REQUIRED);

        thenTheSourceIsConnectedTo(false);
    }

    @Test
    void shouldNotConnectToASourceThatRequiresObfuscationWhenObfuscationIsDisabled() throws Exception {
        givenASourceAskedOnceThatAnnounces(REQUIRES_OBFUSCATION);

        whenTheSourceIsDueAgainWithObfuscation(ObfuscationMode.DISABLED);

        thenTheSourceIsConnectedTo(false);
    }

    @Test
    void shouldAskAFirewalledSourceAbleToObfuscateToCallBackWhenObfuscationIsRequired() throws Exception {
        givenObfuscation(ObfuscationMode.REQUIRED);
        givenADownload();

        whenTheServerFindsAFirewalledSourceWithTheConnectOptions(SUPPORTS);

        thenTheServerIsAskedForCallbacks(1);
    }

    @Test
    void shouldNotAskAFirewalledSourceThatCannotObfuscateToCallBackWhenObfuscationIsRequired() throws Exception {
        givenObfuscation(ObfuscationMode.REQUIRED);
        givenADownload();

        whenTheServerFindsAFirewalledSourceWithTheConnectOptions(0);

        thenTheServerIsAskedForCallbacks(0);
    }

    private void givenObfuscation(ObfuscationMode wanted) {
        this.mode = wanted;
    }

    private void givenADownload() throws Exception {
        this.manager = open();
        onLoop(() -> this.manager.add(Ed2kLink.FileLink.of("firewalled.bin", 5000, FILE)));
    }

    // Asked once, plain: it queues us and the connection closes, leaving its user hash and connect options known.
    private void givenASourceAskedOnceThatAnnounces(int misc2) throws Exception {
        this.source = new ScriptedSource(PEER, 0, misc2);
        this.resources.add(this.source);
        this.manager = open();
        onLoop(() -> this.manager.add(this.source.link("plain.bin", 5000, FILE)));
        try (ScriptedSource.Link link = this.source.accept()) {
            link.until(Opcodes.Client.SETREQFILEID);
            link.send(new ClientMessages.FileStatus(FILE, 0, new BitSet()).encode());
            link.until(Opcodes.Client.STARTUPLOADREQ);
            link.send(Packet.of(
                    Protocol.EMULE,
                    Opcodes.Extended.QUEUERANKING.code(),
                    new PacketWriter().u16(5).bytes(new byte[10])));
            await("queued", () -> sourceIs(SourceInfo.State.QUEUED));
        }
        await("waiting", () -> sourceIs(SourceInfo.State.WAITING));
    }

    private void whenTheSourceIsDueAgainWithObfuscation(ObfuscationMode wanted) {
        this.mode = wanted;
        this.now.addAndGet(DownloadManager.REASK_INTERVAL.toNanos());
    }

    private void whenTheServerFindsAFirewalledSourceWithTheConnectOptions(int cryptOptions) throws Exception {
        onLoop(() -> {
            this.manager.foundSources(new ServerMessages.FoundSources(
                    FILE, List.of(new ServerMessages.Source(FIREWALLED, PORT, cryptOptions, Optional.of(PEER)))));
            return null;
        });
    }

    private void thenTheSourceIsConnectedTo(boolean expected) throws Exception {
        assertThat(this.source.isConnectedToWithin(expected ? ScriptedSource.WAIT : QUIET))
                .isEqualTo(expected);
    }

    private void thenTheServerIsAskedForCallbacks(int count) throws Exception {
        if (count > 0) {
            await(count + " callbacks", () -> this.callbacks.size() >= count);
        } else {
            Thread.sleep(QUIET.toMillis());
        }
        assertThat(this.callbacks).hasSize(count);
    }

    private boolean sourceIs(SourceInfo.State state) {
        return this.manager.sources().stream().anyMatch(s -> s.state() == state);
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
                () -> new DownloadManager.Settings(this.mode, 100, PORT, 0, true),
                new HighId(),
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

    // Our HighID on a server that takes every callback request.
    private final class HighId implements DownloadManager.ServerLink {
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
            DownloadObfuscationTest.this.callbacks.add(lowId);
            return true;
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
