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
package org.njmule.core.persist.format;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Stream;
import org.bouncycastle.crypto.digests.MD4Digest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.njmule.core.api.Hash16;
import org.njmule.core.crypto.Rsa;
import org.njmule.core.crypto.RsaKeys;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.persist.format.PartMet.Gap;

/**
 * Reads a real eMule profile, read-only, from {@code NJMULE_EMULE_CONFIG} (and the downloads in progress from
 * {@code NJMULE_EMULE_TEMP}). Run with {@code -Dtest.excludedGroups=interop,perf -Dgroups=private-fixtures}; it prints
 * only counts, never names or hashes.
 */
@Tag("private-fixtures")
@EnabledIfEnvironmentVariable(named = "NJMULE_EMULE_CONFIG", matches = ".+")
class EmuleProfileTest {

    // NJMULE_PARTS_TO_CHECK raises the number of complete parts verified per download.
    private static final int PARTS_TO_CHECK =
            Integer.parseInt(System.getenv().getOrDefault("NJMULE_PARTS_TO_CHECK", "3"));

    private final Path config = Path.of(System.getenv("NJMULE_EMULE_CONFIG"));

    @Test
    void readsEveryDataFile() throws IOException {
        report("server.met", ServerMet.read(this.read("server.met")).size());
        List<KnownMet.KnownFile> known = KnownMet.read(this.read("known.met"));
        long consistent =
                known.stream().filter(KnownMet.KnownFile::isConsistent).count();
        report("known.met", known.size() + " (" + consistent + " consistent)");
        assertThat(consistent).isEqualTo(known.size());
        report("known2_64.met", Known2Met.read(this.read("known2_64.met")).size());
        report(
                "cancelled.met",
                CancelledMet.read(this.read("cancelled.met")).keys().size());
        report("clients.met", ClientsMet.read(this.read("clients.met")).size());
        report("emfriends.met", FriendsMet.read(this.read("emfriends.met")).size());
        report("nodes.dat", NodesDat.read(this.read("nodes.dat")).contacts().size());
        report(
                "preferencesKad.dat",
                PreferencesKadDat.read(this.read("preferencesKad.dat")).kadId().isZero() ? "zero id" : "ok");
        report(
                "preferences.dat",
                PreferencesDat.isMarkedUserHash(PreferencesDat.read(this.read("preferences.dat"))
                                .userHash())
                        ? "marked hash"
                        : "unmarked hash");
        report(
                "load_index.dat",
                KadIndexFiles.readLoad(this.read("load_index.dat")).loads().size());
        report(
                "src_index.dat",
                KadIndexFiles.readSources(this.read("src_index.dat")).keys().size());
        report(
                "key_index.dat",
                KadIndexFiles.readKeywords(this.read("key_index.dat"))
                        .keywords()
                        .size());
        report(
                "StoredSearches.met",
                SearchFiles.readStored(this.read("StoredSearches.met")).size());
        report(
                "SearchSpam.met",
                SearchFiles.readSpam(this.read("SearchSpam.met")).size());
        report(
                "staticservers.dat",
                TextFiles.staticServers(this.read("staticservers.dat")).size());
        report("shareddir.dat", TextFiles.entries(this.read("shareddir.dat")).size());
        report(
                "sharedfiles.dat",
                TextFiles.sharedFiles(this.read("sharedfiles.dat")).size());
        report("addresses.dat", TextFiles.entries(this.read("addresses.dat")).size());
        report(
                "preferences.ini",
                IniFile.parse(this.read("preferences.ini")).sections().size() + " sections");
        report(
                "Category.ini",
                IniFile.parse(this.read("Category.ini")).sections().size() + " sections");
        report(
                "statistics.ini",
                IniFile.parse(this.read("statistics.ini")).sections().size() + " sections");
    }

    @Test
    void secureIdentKeyWorks() throws IOException {
        Path key = this.config.resolve("cryptkey.dat");
        if (!Files.exists(key)) {
            return;
        }
        Rsa.PrivateKey privateKey = RsaKeys.readKeyFile(key);
        byte[] message = {1, 2, 3, 4};

        assertThat(privateKey.modulus().bitLength()).isEqualTo(Rsa.NETWORK_KEY_BITS);
        assertThat(RsaKeys.encodePublic(privateKey.publicKey()).length)
                .isLessThanOrEqualTo(RsaKeys.MAX_PUBLIC_KEY_SIZE);
        assertThat(Rsa.verify(privateKey.publicKey(), message, Rsa.sign(privateKey, message)))
                .isTrue();
        report("cryptkey.dat", "signs and verifies");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NJMULE_EMULE_TEMP", matches = ".+")
    void completePartsMatchTheirHashes() throws IOException {
        Path temp = Path.of(System.getenv("NJMULE_EMULE_TEMP"));
        try (Stream<Path> files = Files.list(temp)) {
            for (Path met : files.filter(p -> p.getFileName().toString().endsWith(".part.met"))
                    .toList()) {
                PartMet.Contents contents = PartMet.read(Files.readAllBytes(met));
                Path data = temp.resolve(met.getFileName().toString().replace(".part.met", ".part"));
                long size = contents.size().orElseThrow();
                List<Gap> gaps = contents.gaps();
                int checked = 0;
                for (int part = 0; part < Ed2k.partCount(size) && checked < PARTS_TO_CHECK; part++) {
                    long start = Ed2k.partStart(part);
                    long end = start + Ed2k.partLength(size, part);
                    boolean complete = gaps.stream().noneMatch(g -> g.start() < end && g.end() > start);
                    if (complete && !contents.partHashes().isEmpty() && Files.exists(data)) {
                        assertThat(md4(data, start, end - start))
                                .isEqualTo(contents.partHashes().get(part));
                        checked++;
                    }
                }
                report(
                        met.getFileName().toString(),
                        Ed2k.partCount(size)
                                + " parts, "
                                + gaps.size()
                                + " gaps, "
                                + checked
                                + " complete parts verified");
            }
        }
    }

    private static Hash16 md4(Path file, long start, long length) throws IOException {
        MD4Digest md4 = new MD4Digest();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(1 << 20);
            long position = start;
            long left = length;
            while (left > 0) {
                buffer.clear().limit((int) Math.min(buffer.capacity(), left));
                int n = channel.read(buffer, position);
                if (n < 0) {
                    throw new IOException("part file shorter than its metadata");
                }
                md4.update(buffer.array(), 0, n);
                position += n;
                left -= n;
            }
        }
        byte[] digest = new byte[Hash16.LENGTH];
        md4.doFinal(digest, 0);
        return Hash16.of(digest);
    }

    private byte[] read(String name) throws IOException {
        Path file = this.config.resolve(name);
        return Files.exists(file) ? Files.readAllBytes(file) : new byte[0];
    }

    private static void report(String file, Object summary) {
        System.out.println("[private-fixtures] " + file + ": " + summary);
    }
}
