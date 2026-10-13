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
package org.njmule.core.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.persist.format.TextFiles;

@Timeout(60)
class SharedFilesTest {

    @TempDir
    Path dir;

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final BlockingQueue<String> events = new LinkedBlockingQueue<>();

    @AfterEach
    void stop() {
        this.loop.close();
    }

    @Test
    void sharesTheIncomingAndSharedDirectoriesAndRemembersTheHashes() throws Exception {
        Path incoming = Files.createDirectories(this.dir.resolve("incoming"));
        Path extra = Files.createDirectories(this.dir.resolve("extra"));
        Path config = Files.createDirectories(this.dir.resolve("config"));
        byte[] a = bytes(100_000, 1);
        byte[] b = bytes(50_000, 2);
        Files.write(incoming.resolve("a.bin"), a);
        Files.write(extra.resolve("b.bin"), b);
        Files.write(incoming.resolve("desktop.ini"), new byte[] {1});
        Files.write(incoming.resolve("001.part"), new byte[] {1});
        Files.createDirectories(incoming.resolve("sub"));
        Files.write(incoming.resolve("sub").resolve("c.bin"), new byte[] {1});

        SharedFiles shares = this.open(config, incoming);
        assertThat(this.events.poll(30, TimeUnit.SECONDS)).isEqualTo("shared a.bin");
        this.onLoop(() -> {
            shares.addDirectory(extra);
            return null;
        });
        assertThat(this.events.poll(30, TimeUnit.SECONDS)).isEqualTo("shared b.bin");
        assertThat(this.onLoop(
                        () -> shares.get(Ed2k.md4(a, 0, a.length)).orElseThrow().size()))
                .isEqualTo(a.length);
        assertThat(this.onLoop(shares::all)).hasSize(2);
        this.onLoop(() -> {
            shares.close();
            return null;
        });
        assertThat(config.resolve(SharedFiles.KNOWN_MET)).exists();

        // Known files are shared again at once, without hashing.
        SharedFiles again = this.open(config, incoming);
        assertThat(this.onLoop(() -> {
                    again.scan();
                    return again.all().size();
                }))
                .isEqualTo(2);

        Files.delete(extra.resolve("b.bin"));
        this.events.clear();
        this.onLoop(() -> {
            again.scan();
            return null;
        });
        assertThat(this.events.poll(10, TimeUnit.SECONDS)).isEqualTo("unshared b.bin");
        assertThat(this.onLoop(again::all)).hasSize(1);
        this.onLoop(() -> {
            again.close();
            return null;
        });
    }

    @Test
    void neverSharesPrivateDirectoriesOrBackups() throws Exception {
        Path config = Files.createDirectories(this.dir.resolve("config"));
        Path temp = Files.createDirectories(this.dir.resolve("temp"));
        Path incoming = Files.createDirectories(this.dir.resolve("incoming"));
        Path emule = Files.createDirectories(this.dir.resolve("emule-config"));
        Files.write(config.resolve("cryptkey.dat"), bytes(300, 3));
        Files.write(temp.resolve("x.bin"), bytes(300, 4));
        Files.write(emule.resolve(SharedFiles.CLIENT_KEY), bytes(300, 5));
        Files.write(emule.resolve("known.met"), bytes(300, 6));
        Files.write(incoming.resolve("001.part.met.bak"), bytes(300, 7));
        Files.write(incoming.resolve("real.bin"), bytes(300, 8));

        SharedFiles shares = this.open(config, incoming);
        assertThat(this.events.poll(30, TimeUnit.SECONDS)).isEqualTo("shared real.bin");
        for (Path dir : List.of(config, config.resolve("sub"), temp, emule, Path.of(System.getProperty("user.home")))) {
            assertThat(this.onLoop(() -> shares.isPrivate(dir)))
                    .as(dir.toString())
                    .isTrue();
            assertThatThrownBy(() -> this.onLoop(() -> {
                        shares.addDirectory(dir);
                        return null;
                    }))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
        assertThat(this.onLoop(() -> shares.isPrivate(incoming))).isFalse();
        assertThat(this.events.poll(2, TimeUnit.SECONDS)).isNull();
        assertThat(this.onLoop(shares::all)).hasSize(1);
        assertThat(this.onLoop(shares::directories)).isEmpty();
        this.onLoop(() -> {
            shares.close();
            return null;
        });

        // A private directory already in the list, from an older version, stays unshared.
        Files.write(config.resolve(SharedFiles.SHARED_DIRS), TextFiles.writeLines(List.of(emule.toString())));
        SharedFiles again = this.open(config, incoming);
        assertThat(this.events.poll(30, TimeUnit.SECONDS)).isEqualTo("shared real.bin");
        assertThat(this.events.poll(2, TimeUnit.SECONDS)).isNull();
        this.onLoop(() -> {
            again.close();
            return null;
        });
    }

    private SharedFiles open(Path config, Path incoming) throws Exception {
        return this.onLoop(() -> SharedFiles.open(
                this.loop, config, () -> incoming, () -> List.of(this.dir.resolve("temp")), new SharedFiles.Listener() {
                    @Override
                    public void shared(SharedFiles.SharedFile file) {
                        SharedFilesTest.this.events.add("shared " + file.name());
                    }

                    @Override
                    public void unshared(SharedFiles.SharedFile file) {
                        SharedFilesTest.this.events.add("unshared " + file.name());
                    }
                }));
    }

    private <T> T onLoop(Callable<T> task) throws Exception {
        return this.loop.submit(task).get(30, TimeUnit.SECONDS);
    }

    private static byte[] bytes(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }
}
