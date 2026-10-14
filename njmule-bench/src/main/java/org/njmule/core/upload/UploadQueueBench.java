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
package org.njmule.core.upload;

import com.google.common.base.Ticker;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.UploadInfo;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.files.SharedFiles;
import org.njmule.core.peer.Identities;
import org.njmule.core.peer.IdentityHandshake;
import org.njmule.core.peer.PeerLinks;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.util.Ipv4;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * The engine-thread cost of a full upload queue: the list front ends poll, the place told to a client that asks, and
 * one step of the queue. In the core's package, as the queue is reached through package-private hooks.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 2)
@Fork(1)
public class UploadQueueBench {

    private static final int FILES = 50;

    @Param({"1000", "5000"})
    public int waiting;

    private final List<Hash16> users = new ArrayList<>();
    private Path dir;
    private EngineLoop loop;
    private UploadManager manager;
    private int next;

    @Setup(Level.Trial)
    public void setUp() throws IOException {
        this.dir = Files.createTempDirectory("njmule-bench");
        this.loop = EngineLoop.start(1024, "bench-engine");
        SecureRandom random = new SecureRandom();
        Map<Hash16, SharedFiles.SharedFile> shared = new HashMap<>();
        List<Uploadable> files = new ArrayList<>();
        for (int i = 0; i < FILES; i++) {
            Hash16 hash = hash(0x10_0000 + i);
            SharedFiles.SharedFile file = new SharedFiles.SharedFile(
                    this.dir.resolve("file" + i), "file" + i + ".bin", 1_000_000, hash, List.of(), Optional.empty(), 0);
            shared.put(hash, file);
            files.add(Uploadable.of(file));
        }
        Identities identities = new Identities(
                IdentityHandshake.Identity.of(Identities.loadOrCreateKey(this.dir, random)),
                hash -> Optional.empty(),
                (hash, key) -> {},
                Optional::empty,
                random);
        Hello ours = Hello.of(hash(1), 0, 4662, "bench", 0, 0, 0, 0, 0, 0);
        UploadManager.Settings settings = new UploadManager.Settings(100, ObfuscationMode.SUPPORTED, 10_000);
        this.manager = new UploadManager(
                this.loop,
                hash -> Optional.ofNullable(shared.get(hash)),
                hash -> Optional.empty(),
                (hash, proven) -> Optional.empty(),
                hash -> false,
                hash -> Optional.empty(),
                hash -> Optional.empty(),
                hash -> List.of(),
                () -> ours,
                () -> settings,
                new PeerLinks(this.loop, identities, link -> Optional.empty()),
                Credits.load(this.dir, Clock.systemUTC()),
                ip -> {},
                random,
                Ticker.systemTicker());
        long now = System.nanoTime();
        for (int i = 0; i < this.waiting; i++) {
            Hash16 user = hash(i + 2);
            this.users.add(user);
            // Firewalled and gone: the queue scores them and calls none.
            Hello hello = Hello.of(user, 1234, 4662, "client " + i, 0, 0, 0, 0, 0, 0);
            this.manager.enqueue(
                    hello,
                    InetAddress.getByAddress(Ipv4.toBytes(Ipv4.of(10, i >> 16 & 0xFF, i >> 8 & 0xFF, i & 0xFF))),
                    files.get(i % FILES),
                    now - (long) i * 1_000_000_000L);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        this.loop.close();
        try (var paths = Files.walk(this.dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Benchmark
    public List<UploadInfo> uploads() {
        return this.manager.uploads();
    }

    @Benchmark
    public int rank() {
        this.next = (this.next + 1) % this.users.size();
        return this.manager.rankOf(this.users.get(this.next));
    }

    @Benchmark
    public int tick() {
        this.manager.tick();
        return this.manager.waitingCount();
    }

    private static Hash16 hash(int seed) {
        byte[] bytes = new byte[16];
        bytes[0] = (byte) (seed >> 24);
        bytes[1] = (byte) (seed >> 16);
        bytes[2] = (byte) (seed >> 8);
        bytes[3] = (byte) seed;
        return Hash16.of(bytes);
    }
}
