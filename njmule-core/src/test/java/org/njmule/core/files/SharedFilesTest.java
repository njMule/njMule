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

import java.nio.file.FileAlreadyExistsException;
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
import org.njmule.core.api.Hash16;
import org.njmule.core.api.UploadPriority;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.files.hash.AichTree;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.persist.format.KnownMet;
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

    @Test
    void sharesATreeWithItsSubdirectoriesAlsoLaterOnesUntilItIsUnshared() throws Exception {
        Path incoming = Files.createDirectories(this.dir.resolve("incoming"));
        Path config = Files.createDirectories(this.dir.resolve("config"));
        Path tree =
                Files.createDirectories(this.dir.resolve("tree").resolve("sub").resolve("deeper"));
        Files.write(this.dir.resolve("tree").resolve("sub").resolve("c.bin"), bytes(3000, 4));
        Files.write(tree.resolve("d.bin"), bytes(4000, 5));
        SharedFiles shares = this.open(config, incoming);

        this.onLoop(() -> {
            shares.addDirectory(this.dir.resolve("tree"), true);
            return null;
        });
        assertThat(List.of(this.events.poll(30, TimeUnit.SECONDS), this.events.poll(30, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("shared c.bin", "shared d.bin");
        assertThat(this.onLoop(shares::trees))
                .containsExactly(this.dir.resolve("tree").toAbsolutePath());
        assertThat(this.onLoop(shares::directories)).isEmpty();

        // A directory made later is watched and shared without a rescan.
        Path later = Files.createDirectories(this.dir.resolve("tree").resolve("later"));
        Files.write(later.resolve("e.bin"), bytes(5000, 6));
        assertThat(this.events.poll(30, TimeUnit.SECONDS)).isEqualTo("shared e.bin");

        this.onLoop(() -> {
            shares.removeDirectory(this.dir.resolve("tree"));
            return null;
        });
        assertThat(List.of(
                        this.events.poll(30, TimeUnit.SECONDS),
                        this.events.poll(30, TimeUnit.SECONDS),
                        this.events.poll(30, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("unshared c.bin", "unshared d.bin", "unshared e.bin");
        assertThat(this.onLoop(shares::trees)).isEmpty();
        this.onLoop(() -> {
            shares.close();
            return null;
        });
    }

    @Test
    void keepsTheUploadPriorityOfEachFileInKnownMet() throws Exception {
        Path incoming = Files.createDirectories(this.dir.resolve("incoming"));
        Path config = Files.createDirectories(this.dir.resolve("config"));
        byte[] a = bytes(20_000, 3);
        Files.write(incoming.resolve("a.bin"), a);
        Hash16 hash = Ed2k.md4(a, 0, a.length);

        SharedFiles shares = this.open(config, incoming);
        assertThat(this.events.poll(30, TimeUnit.SECONDS)).isEqualTo("shared a.bin");
        assertThat(this.onLoop(() -> shares.priority(hash))).isEqualTo(UploadPriority.AUTO);
        assertThat(this.onLoop(() -> shares.setPriority(hash, UploadPriority.RELEASE)))
                .isTrue();
        assertThat(this.onLoop(() -> shares.setPriority(Hash16.parseHex("00".repeat(16)), UploadPriority.LOW)))
                .isFalse();
        this.onLoop(() -> {
            shares.close();
            return null;
        });

        assertThat(KnownMet.read(Files.readAllBytes(config.resolve(SharedFiles.KNOWN_MET))))
                .singleElement()
                .satisfies(record -> assertThat(record.uploadPriority())
                        .as("the value other clients write")
                        .hasValue(3));
        SharedFiles again = this.open(config, incoming);
        assertThat(this.onLoop(() -> {
                    again.scan();
                    return again.priority(hash);
                }))
                .isEqualTo(UploadPriority.RELEASE);
        this.onLoop(() -> {
            again.close();
            return null;
        });
    }

    @Test
    void renamesASharedFileAndKeepsWhatIsKnownOfIt() throws Exception {
        Path incoming = Files.createDirectories(this.dir.resolve("incoming"));
        Path config = Files.createDirectories(this.dir.resolve("config"));
        byte[] a = bytes(20_000, 6);
        Files.write(incoming.resolve("a.bin"), a);
        Hash16 hash = Ed2k.md4(a, 0, a.length);
        SharedFiles shares = this.open(config, incoming);
        assertThat(this.events.poll(30, TimeUnit.SECONDS)).isEqualTo("shared a.bin");
        this.onLoop(() -> shares.setPriority(hash, UploadPriority.HIGH));

        assertThat(this.onLoop(() -> shares.rename(hash, "b?.bin")))
                .map(SharedFiles.SharedFile::name)
                .as("a name the file system takes")
                .contains("b_.bin");
        assertThat(incoming.resolve("b_.bin")).hasBinaryContent(a);
        assertThat(incoming.resolve("a.bin")).doesNotExist();
        assertThat(List.of(this.events.poll(10, TimeUnit.SECONDS), this.events.poll(10, TimeUnit.SECONDS)))
                .containsExactly("unshared a.bin", "shared b_.bin");
        assertThat(this.onLoop(() -> shares.priority(hash))).isEqualTo(UploadPriority.HIGH);

        Files.write(incoming.resolve("c.bin"), new byte[] {1});
        assertThatThrownBy(() -> this.onLoop(() -> shares.rename(hash, "c.bin")))
                .hasRootCauseInstanceOf(FileAlreadyExistsException.class);
        assertThat(this.onLoop(() -> shares.rename(Hash16.parseHex("00".repeat(16)), "d.bin")))
                .isEmpty();
        this.onLoop(() -> {
            shares.close();
            return null;
        });
    }

    @Test
    void theAichTreeOfAFileIsBuiltOnceForAllItsRequests() throws Exception {
        Path incoming = Files.createDirectories(this.dir.resolve("incoming"));
        Path config = Files.createDirectories(this.dir.resolve("config"));
        byte[] a = bytes(500_000, 7);
        Files.write(incoming.resolve("a.bin"), a);
        Hash16 hash = Ed2k.md4(a, 0, a.length);
        SharedFiles shares = this.open(config, incoming);
        assertThat(this.events.poll(30, TimeUnit.SECONDS)).isEqualTo("shared a.bin");

        AichTree first = this.onLoop(() -> shares.aichTree(hash)).orElseThrow();

        assertThat(this.onLoop(() -> shares.aichTree(hash))).containsSame(first);
        assertThat(first.size()).isEqualTo(a.length);
        this.onLoop(() -> {
            shares.close();
            return null;
        });
    }

    @Test
    void forgetsTheFilesNoLongerSharedWhenTheyAreNotToBeRemembered() throws Exception {
        Path incoming = Files.createDirectories(this.dir.resolve("incoming"));
        Path config = Files.createDirectories(this.dir.resolve("config"));
        byte[] a = bytes(20_000, 4);
        byte[] b = bytes(30_000, 5);
        Files.write(incoming.resolve("a.bin"), a);
        Files.write(incoming.resolve("b.bin"), b);
        Hash16 kept = Ed2k.md4(a, 0, a.length);
        Hash16 gone = Ed2k.md4(b, 0, b.length);

        SharedFiles shares = this.open(config, incoming);
        assertThat(List.of(this.events.poll(30, TimeUnit.SECONDS), this.events.poll(30, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("shared a.bin", "shared b.bin");
        Files.delete(incoming.resolve("b.bin"));
        this.onLoop(() -> {
            shares.scan();
            return null;
        });
        assertThat(this.events.poll(10, TimeUnit.SECONDS)).isEqualTo("unshared b.bin");
        assertThat(this.onLoop(shares::knownHashes)).as("downloaded before").containsExactlyInAnyOrder(kept, gone);

        this.onLoop(() -> {
            shares.rememberDownloaded(false);
            shares.close();
            return null;
        });

        assertThat(KnownMet.read(Files.readAllBytes(config.resolve(SharedFiles.KNOWN_MET))))
                .singleElement()
                .satisfies(record -> assertThat(record.fileHash()).isEqualTo(kept));
    }

    private SharedFiles open(Path config, Path incoming) throws Exception {
        return this.onLoop(() -> SharedFiles.open(
                this.loop,
                config,
                () -> List.of(incoming),
                () -> List.of(this.dir.resolve("temp")),
                new SharedFiles.Listener() {
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
