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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;
import org.njmule.core.api.UploadPriority;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.files.hash.AichTree;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.persist.format.Known2Met;
import org.njmule.core.persist.format.KnownMet;
import org.njmule.core.persist.format.TextFiles;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;

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
    void aFileThatCannotBeReadDoesNotStopTheScanOfItsDirectory() throws Exception {
        Path incoming = Files.createDirectories(this.dir.resolve("incoming"));
        Path config = Files.createDirectories(this.dir.resolve("config"));
        for (int i = 0; i < 6; i++) {
            Files.write(incoming.resolve("f" + i + ".bin"), bytes(3000, 20 + i));
        }
        SharedFiles first = this.open(config, incoming);
        List<String> order = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            order.add(this.events.poll(30, TimeUnit.SECONDS).substring("shared ".length()));
        }
        this.onLoop(() -> {
            first.close();
            return null;
        });

        // The second file goes while the directory is read: it is listed, but gone.
        List<String> seen = new CopyOnWriteArrayList<>();
        SharedFiles again = this.onLoop(() -> SharedFiles.open(
                this.loop, config, () -> List.of(incoming), () -> List.of(), new SharedFiles.Listener() {
                    @Override
                    public void shared(SharedFiles.SharedFile file) {
                        if (seen.isEmpty()) {
                            try {
                                Files.delete(incoming.resolve(order.get(1)));
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        }
                        seen.add(file.name());
                    }

                    @Override
                    public void unshared(SharedFiles.SharedFile file) {
                        seen.add("unshared " + file.name());
                    }
                }));

        assertThat(this.onLoop(() ->
                        again.all().stream().map(SharedFiles.SharedFile::name).toList()))
                .containsExactlyInAnyOrderElementsOf(
                        order.stream().filter(n -> !n.equals(order.get(1))).toList());
        this.onLoop(() -> {
            again.close();
            return null;
        });
    }

    @Test
    void anotherCopyOfTheContentKeepsItSharedWhenOneGoes() throws Exception {
        Path incoming = Files.createDirectories(this.dir.resolve("incoming"));
        Path config = Files.createDirectories(this.dir.resolve("config"));
        byte[] a = bytes(4000, 30);
        Files.write(incoming.resolve("one.bin"), a);
        Files.write(incoming.resolve("two.bin"), a);
        Hash16 hash = Ed2k.md4(a, 0, a.length);
        SharedFiles shares = this.open(config, incoming);
        String shared = this.events.poll(30, TimeUnit.SECONDS);
        assertThat(shared).startsWith("shared ");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (this.onLoop(() -> shares.get(hash).orElseThrow().name()).equals(shared.substring(7))
                && System.nanoTime() < deadline) {
            // Until the second copy is hashed and tracked.
            Thread.sleep(50);
        }
        Path tracked = this.onLoop(() -> shares.get(hash).orElseThrow().path());

        Files.delete(tracked);
        this.events.clear();
        this.onLoop(() -> {
            shares.scan();
            return null;
        });

        assertThat(this.onLoop(() -> shares.get(hash)))
                .as("the other copy")
                .hasValueSatisfying(f -> assertThat(f.path()).isNotEqualTo(tracked));
        assertThat(this.events.poll(1, TimeUnit.SECONDS)).isNull();
        this.onLoop(() -> {
            shares.close();
            return null;
        });
    }

    @Test
    void aFileWhoseContentChangesIsUnsharedUnderItsOldHash() throws Exception {
        Path incoming = Files.createDirectories(this.dir.resolve("incoming"));
        Path config = Files.createDirectories(this.dir.resolve("config"));
        Path file = incoming.resolve("changing.bin");
        Files.write(file, bytes(4000, 31));
        SharedFiles shares = this.open(config, incoming);
        assertThat(this.events.poll(30, TimeUnit.SECONDS)).isEqualTo("shared changing.bin");
        Hash16 before = this.onLoop(() -> shares.all().getFirst().hash());

        Files.write(file, bytes(5000, 32));
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 10_000));
        this.onLoop(() -> {
            shares.scan();
            return null;
        });

        assertThat(List.of(this.events.poll(30, TimeUnit.SECONDS), this.events.poll(30, TimeUnit.SECONDS)))
                .containsExactly("unshared changing.bin", "shared changing.bin");
        assertThat(this.onLoop(() -> shares.get(before))).isEmpty();
        this.onLoop(() -> {
            shares.close();
            return null;
        });
    }

    @Test
    void aFileRenamedWhileHashedAgainStaysSharedUnderItsNewName() throws Exception {
        Path incoming = Files.createDirectories(this.dir.resolve("incoming"));
        Path config = Files.createDirectories(this.dir.resolve("config"));
        SharedFiles shares = this.open(config, incoming);
        byte[] data = bytes(600_000, 33);
        Hash16 hash = Ed2k.md4(data, 0, data.length);
        Path done = incoming.resolve("done.bin");
        Files.write(done, data);
        PartFile download = PartFile.create(1, this.dir, "done.bin", data.length, hash, Optional.empty(), List.of());

        this.onLoop(() -> {
            // Hashed again for its AICH tree, and renamed meanwhile.
            shares.finished(done, download);
            return shares.rename(hash, "renamed.bin");
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (this.onLoop(() -> shares.aichTree(hash)).isEmpty()) {
            assertThat(System.nanoTime()).as("hashed").isLessThan(deadline);
            Thread.sleep(50);
        }
        this.onLoop(() -> {
            shares.scan();
            return null;
        });

        assertThat(this.onLoop(() -> shares.get(hash)))
                .hasValueSatisfying(f -> assertThat(f.path()).isEqualTo(incoming.resolve("renamed.bin")));
        assertThat(this.onLoop(() -> shares.aichTree(hash))).isPresent();
        this.onLoop(() -> {
            shares.close();
            return null;
        });
    }

    @Test
    void aFileRenamedBeforeItIsHashedAgainGetsItsAichTreeUnderItsNewName() throws Exception {
        Path incoming = Files.createDirectories(this.dir.resolve("incoming"));
        Path config = Files.createDirectories(this.dir.resolve("config"));
        SharedFiles shares = this.open(config, incoming);
        // A large file hashed first keeps the hashing thread busy until the rename is done.
        Path large = Files.write(
                Files.createDirectories(this.dir.resolve("other")).resolve("large.bin"), bytes(32_000_000, 36));
        PartFile busy = PartFile.create(
                2,
                this.dir,
                "large.bin",
                Files.size(large),
                Hash16.parseHex("77".repeat(16)),
                Optional.empty(),
                List.of());
        byte[] data = bytes(600_000, 37);
        Hash16 hash = Ed2k.md4(data, 0, data.length);
        Path done = incoming.resolve("done.bin");
        Files.write(done, data);
        PartFile download = PartFile.create(1, this.dir, "done.bin", data.length, hash, Optional.empty(), List.of());

        this.onLoop(() -> {
            shares.finished(large, busy);
            shares.finished(done, download);
            return shares.rename(hash, "renamed.bin");
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (this.onLoop(() -> shares.aichTree(hash)).isEmpty()) {
            assertThat(System.nanoTime()).as("hashed under its new name").isLessThan(deadline);
            Thread.sleep(50);
        }

        assertThat(this.onLoop(() -> shares.get(hash)))
                .hasValueSatisfying(f -> assertThat(f.path()).isEqualTo(incoming.resolve("renamed.bin")));
        assertThat(this.onLoop(() -> shares.get(Hash16.parseHex("77".repeat(16)))))
                .as("a file that is not what was downloaded keeps no tree")
                .hasValueSatisfying(f -> assertThat(f.aichHash()).isEmpty());
        this.onLoop(() -> {
            shares.close();
            return null;
        });
    }

    @Test
    void unreadableListsAndRecordsAreLeftOutAndTheFilesHashedAgain() throws Exception {
        Path incoming = Files.createDirectories(this.dir.resolve("incoming"));
        Path config = Files.createDirectories(this.dir.resolve("config"));
        // known.met is a directory: it can be neither read nor written.
        Files.createDirectories(config.resolve(SharedFiles.KNOWN_MET).resolve("x"));
        Files.write(config.resolve(SharedFiles.KNOWN2_MET), new byte[] {9, 9, 9});
        Files.createDirectories(config.resolve(SharedFiles.SHARED_DIRS));
        Files.write(
                config.resolve(SharedFiles.SHARED_TREES),
                TextFiles.writeLines(
                        List.of("\u0000bad", this.dir.resolve("missing").toString())));
        Files.write(incoming.resolve("a.bin"), bytes(3000, 38));
        Files.write(incoming.resolve("empty.bin"), new byte[0]);
        Path hidden = Files.write(incoming.resolve(".hidden.bin"), bytes(3000, 39));
        if (Files.getFileStore(hidden).supportsFileAttributeView("dos")) {
            Files.setAttribute(hidden, "dos:hidden", true);
        }

        SharedFiles shares = this.open(config, incoming);

        assertThat(this.events.poll(30, TimeUnit.SECONDS)).isEqualTo("shared a.bin");
        assertThat(this.onLoop(shares::directories)).isEmpty();
        assertThat(this.onLoop(shares::trees)).containsExactly(this.dir.resolve("missing"));
        this.onLoop(() -> {
            shares.close();
            shares.scan();
            return null;
        });
        assertThat(this.events.poll(200, TimeUnit.MILLISECONDS)).isNull();
        assertThat(config.resolve(SharedFiles.KNOWN_MET)).isDirectory();
    }

    @Test
    void anotherClientsRecordsAreTakenWhenUsableWithTheirTrees() throws Exception {
        Path incoming = Files.createDirectories(this.dir.resolve("incoming"));
        Path config = Files.createDirectories(this.dir.resolve("config"));
        byte[] a = bytes(400_000, 40);
        byte[] b = bytes(400_000, 41);
        Path fileA = Files.write(incoming.resolve("a.bin"), a);
        Path fileB = Files.write(incoming.resolve("b.bin"), b);
        FileTime time = FileTime.fromMillis(1_700_000_000_000L);
        Files.setLastModifiedTime(fileA, time);
        Files.setLastModifiedTime(fileB, time);
        Hash20 treeA = Hash20.parseBase32("A".repeat(32));
        Hash20 treeB = Hash20.parseBase32("B".repeat(32));
        List<KnownMet.KnownFile> records = List.of(
                record("a.bin", a, treeA, time),
                record("b.bin", b, treeB, time),
                new KnownMet.KnownFile(0, Hash16.parseHex("21".repeat(16)), List.of(), List.of()),
                new KnownMet.KnownFile(
                        0,
                        Hash16.parseHex("22".repeat(16)),
                        List.of(Hash16.parseHex("23".repeat(16))),
                        List.of(
                                new Tag.StringTag(TagName.id(FileTag.FILENAME), "odd.bin"),
                                new Tag.IntTag(TagName.id(FileTag.FILESIZE), 10))));
        Hash20 sha = Hash20.of(AichTree.sha1().digest(new byte[1]));
        List<Known2Met.Entry> trees = List.of(
                new Known2Met.Entry(treeA, List.of(sha)),
                new Known2Met.Entry(treeB, List.of(sha, sha, sha)),
                new Known2Met.Entry(Hash20.parseBase32("C".repeat(32)), List.of(sha)));
        SharedFiles shares = this.open(config, Files.createDirectories(this.dir.resolve("empty")));

        int added = this.onLoop(() -> shares.addKnown(records, trees));
        assertThat(added).isEqualTo(2);
        this.onLoop(() -> {
            shares.addDirectory(incoming);
            return null;
        });

        Hash16 hashA = Ed2k.md4(a, 0, a.length);
        Hash16 hashB = Ed2k.md4(b, 0, b.length);
        assertThat(this.onLoop(() -> shares.get(hashA)))
                .as("shared without hashing")
                .hasValueSatisfying(f -> assertThat(f.aichHash()).contains(treeA));
        assertThat(this.onLoop(() -> shares.aichTree(hashA)))
                .as("one block hash for three blocks")
                .isEmpty();
        assertThat(this.onLoop(() -> shares.aichTree(hashB)))
                .as("block hashes of another tree")
                .isEmpty();
        assertThat(this.onLoop(() -> shares.rename(hashA, "a.bin")))
                .as("the same name")
                .isPresent();
        assertThat(this.onLoop(() -> shares.rename(hashA, "kept.bin")))
                .hasValueSatisfying(f -> assertThat(f.name()).isEqualTo("kept.bin"));
        assertThat(this.onLoop(() -> shares.rename(Hash16.parseHex("24".repeat(16)), "x.bin")))
                .isEmpty();
        this.onLoop(() -> {
            shares.close();
            return null;
        });
        assertThat(KnownMet.read(Files.readAllBytes(config.resolve(SharedFiles.KNOWN_MET))))
                .filteredOn(r -> r.fileHash().equals(hashA))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.name()).contains("kept.bin");
                    assertThat(r.tags())
                            .as("its other tags stay")
                            .anyMatch(t -> !t.name().isId());
                });
    }

    @Test
    void everyUploadPriorityIsKeptUnderTheCodeOtherClientsUse() throws Exception {
        Path incoming = Files.createDirectories(this.dir.resolve("incoming"));
        Path config = Files.createDirectories(this.dir.resolve("config"));
        byte[] a = bytes(2000, 42);
        Files.write(incoming.resolve("a.bin"), a);
        Hash16 hash = Ed2k.md4(a, 0, a.length);
        SharedFiles shares = this.open(config, incoming);
        assertThat(this.events.poll(30, TimeUnit.SECONDS)).isEqualTo("shared a.bin");

        for (UploadPriority priority : UploadPriority.values()) {
            assertThat(this.onLoop(() -> {
                        shares.setPriority(hash, priority);
                        return shares.priority(hash);
                    }))
                    .isEqualTo(priority);
        }
        assertThat(this.onLoop(() -> shares.priority(Hash16.parseHex("25".repeat(16)))))
                .isEqualTo(UploadPriority.AUTO);
        this.onLoop(() -> {
            shares.close();
            return null;
        });
    }

    @Test
    void aFinishedFileIsHashedForItsOwnTreeAndOneThatCannotBeHashedIsLeftOut() throws Exception {
        Path incoming = Files.createDirectories(this.dir.resolve("incoming"));
        Path config = Files.createDirectories(this.dir.resolve("config"));
        SharedFiles shares = this.open(config, incoming);
        Path other = Files.createDirectories(this.dir.resolve("other"));
        Path large = Files.write(other.resolve("large.bin"), bytes(32_000_000, 43));
        PartFile busy = PartFile.create(
                3,
                this.dir,
                "large.bin",
                Files.size(large),
                Hash16.parseHex("26".repeat(16)),
                Optional.empty(),
                List.of());
        byte[] data = bytes(400_000, 44);
        Hash16 hash = Ed2k.md4(data, 0, data.length);
        Path done = Files.write(other.resolve("done.bin"), data);
        Hash20 wrong = Hash20.parseBase32("D".repeat(32));
        PartFile download = PartFile.create(4, this.dir, "done.bin", data.length, hash, Optional.of(wrong), List.of());
        Path extra = Files.createDirectories(this.dir.resolve("extra"));
        Path gone = Files.write(extra.resolve("gone.bin"), bytes(3000, 45));

        this.onLoop(() -> {
            shares.finished(large, busy);
            shares.finished(this.dir.resolve("never.bin"), download);
            shares.finished(done, download);
            // Found by the scan, then gone before the hashing thread reads it.
            shares.addDirectory(extra);
            Files.delete(gone);
            return null;
        });

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (this.onLoop(() -> shares.aichTree(hash)).isEmpty()) {
            assertThat(System.nanoTime()).as("hashed").isLessThan(deadline);
            Thread.sleep(50);
        }
        assertThat(this.onLoop(() -> shares.get(hash).orElseThrow().aichHash()))
                .as("the file's own AICH hash, not the link's")
                .hasValueSatisfying(h -> assertThat(h).isNotEqualTo(wrong));
        assertThat(this.onLoop(() ->
                        shares.all().stream().map(SharedFiles.SharedFile::name).toList()))
                .doesNotContain("gone.bin", "never.bin");
        this.onLoop(() -> {
            shares.close();
            return null;
        });
    }

    private static KnownMet.KnownFile record(String name, byte[] data, Hash20 aich, FileTime time) {
        return new KnownMet.KnownFile(
                time.toMillis() / 1000,
                Ed2k.md4(data, 0, data.length),
                List.of(),
                List.of(
                        new Tag.StringTag(TagName.id(FileTag.FILENAME), name),
                        new Tag.IntTag(TagName.id(FileTag.FILESIZE), data.length),
                        new Tag.StringTag(TagName.id(FileTag.AICH_HASH), aich.toBase32()),
                        new Tag.StringTag(TagName.of("note"), "from another client")));
    }

    @Test
    void renamingChangesTheCaseOfANameToo() throws Exception {
        Path incoming = Files.createDirectories(this.dir.resolve("incoming"));
        Path config = Files.createDirectories(this.dir.resolve("config"));
        byte[] a = bytes(3000, 34);
        Files.write(incoming.resolve("movie.avi"), a);
        Hash16 hash = Ed2k.md4(a, 0, a.length);
        SharedFiles shares = this.open(config, incoming);
        assertThat(this.events.poll(30, TimeUnit.SECONDS)).isEqualTo("shared movie.avi");

        assertThat(this.onLoop(() -> shares.rename(hash, "Movie.avi")))
                .map(SharedFiles.SharedFile::name)
                .contains("Movie.avi");

        try (Stream<Path> names = Files.list(incoming)) {
            assertThat(names.map(p -> p.getFileName().toString())).containsExactly("Movie.avi");
        }
        assertThat(this.onLoop(() -> shares.get(hash).orElseThrow().name())).isEqualTo("Movie.avi");
        this.onLoop(() -> {
            shares.close();
            return null;
        });
    }

    @Test
    void refusesNamesThatAreNeverShared() throws Exception {
        Path incoming = Files.createDirectories(this.dir.resolve("incoming"));
        Path config = Files.createDirectories(this.dir.resolve("config"));
        byte[] a = bytes(3000, 35);
        Files.write(incoming.resolve("kept.bin"), a);
        Hash16 hash = Ed2k.md4(a, 0, a.length);
        SharedFiles shares = this.open(config, incoming);
        assertThat(this.events.poll(30, TimeUnit.SECONDS)).isEqualTo("shared kept.bin");

        for (String name : List.of("kept.bak", "kept.tmp", "001.part", "Desktop.ini", "link.lnk", ".hidden")) {
            assertThatThrownBy(() -> this.onLoop(() -> shares.rename(hash, name)))
                    .as(name)
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
        assertThat(incoming.resolve("kept.bin")).exists();
        assertThat(this.onLoop(() -> shares.get(hash).orElseThrow().name())).isEqualTo("kept.bin");
        this.onLoop(() -> {
            shares.close();
            return null;
        });
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aSharedTreeKeepsToItselfThroughJunctions() throws Exception {
        Path incoming = Files.createDirectories(this.dir.resolve("incoming"));
        Path config = Files.createDirectories(this.dir.resolve("config"));
        Path tree = Files.createDirectories(this.dir.resolve("tree").resolve("sub"));
        Files.write(tree.resolve("in.bin"), bytes(3000, 36));
        Path outside = Files.createDirectories(this.dir.resolve("outside"));
        Files.write(outside.resolve("secret.bin"), bytes(3000, 37));
        junction(this.dir.resolve("tree").resolve("escape"), outside);
        junction(this.dir.resolve("tree").resolve("loop"), this.dir.resolve("tree"));
        SharedFiles shares = this.open(config, incoming);

        this.onLoop(() -> {
            shares.addDirectory(this.dir.resolve("tree"), true);
            return null;
        });
        assertThat(this.events.poll(30, TimeUnit.SECONDS)).isEqualTo("shared in.bin");

        assertThat(this.events.poll(3, TimeUnit.SECONDS))
                .as("nothing from outside the tree")
                .isNull();
        assertThat(this.onLoop(shares::all))
                .singleElement()
                .satisfies(f -> assertThat(f.path()).isEqualTo(tree.resolve("in.bin")));
        this.onLoop(() -> {
            shares.close();
            return null;
        });
    }

    private static void junction(Path link, Path target) throws Exception {
        Process mklink = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true)
                .start();
        mklink.getInputStream().readAllBytes();
        assertThat(mklink.waitFor()).as("mklink /J").isZero();
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
