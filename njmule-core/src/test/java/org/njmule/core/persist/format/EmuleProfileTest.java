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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
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
 * {@code NJMULE_EMULE_TEMP}). Run with {@code -Ptest.excludedTags=interop,perf -Ptest.tags=private-fixtures}; it prints
 * only counts, never names or hashes.
 */
@Tag("private-fixtures")
@EnabledIfEnvironmentVariable(named = "NJMULE_EMULE_CONFIG", matches = ".+")
class EmuleProfileTest {

    // NJMULE_PARTS_TO_CHECK raises the number of complete parts verified per download.
    private static final int PARTS_TO_CHECK =
            Integer.parseInt(System.getenv().getOrDefault("NJMULE_PARTS_TO_CHECK", "3"));

    private final Path config = Path.of(System.getenv("NJMULE_EMULE_CONFIG"));

    private List<KnownMet.KnownFile> known;
    private long consistent;
    private Rsa.PrivateKey privateKey;
    private byte[] message;
    private byte[] signature;
    private Path temp;
    private final List<CheckedPart> checkedParts = new ArrayList<>();

    private record CheckedPart(Hash16 expected, Hash16 actual) {}

    @Test
    void shouldReadEveryDataFile() throws IOException {
        whenEveryDataFileIsRead();

        thenEveryKnownFileIsConsistent();
    }

    @Test
    void shouldSignAndVerifyWithTheSecureIdentKey() throws IOException {
        givenTheSecureIdentKeyOfTheProfile();

        whenTheMessageIsSigned(new byte[] {1, 2, 3, 4});

        thenTheKeyHasTheNetworkSize();
        thenThePublicKeyFitsItsLimit();
        thenTheSignatureVerifies();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NJMULE_EMULE_TEMP", matches = ".+")
    void shouldMatchTheCompletePartsWithTheirHashes() throws IOException {
        givenTheDownloadsInProgressOfTheEnvironment();

        whenTheCompletePartsOfEveryDownloadAreHashed();

        thenEveryCompletePartMatchesItsHash();
    }

    private void givenTheSecureIdentKeyOfTheProfile() throws IOException {
        Path key = this.config.resolve("cryptkey.dat");
        assumeTrue(Files.exists(key), "no cryptkey.dat in the profile");
        this.privateKey = RsaKeys.readKeyFile(key);
    }

    private void givenTheDownloadsInProgressOfTheEnvironment() {
        this.temp = Path.of(System.getenv("NJMULE_EMULE_TEMP"));
    }

    private void whenEveryDataFileIsRead() throws IOException {
        report("server.met", ServerMet.read(read("server.met")).size());
        this.known = KnownMet.read(read("known.met"));
        this.consistent =
                this.known.stream().filter(KnownMet.KnownFile::isConsistent).count();
        report("known.met", this.known.size() + " (" + this.consistent + " consistent)");
        report("known2_64.met", Known2Met.read(read("known2_64.met")).size());
        report("cancelled.met", CancelledMet.read(read("cancelled.met")).keys().size());
        report("clients.met", ClientsMet.read(read("clients.met")).size());
        report("emfriends.met", FriendsMet.read(read("emfriends.met")).size());
        report("nodes.dat", NodesDat.read(read("nodes.dat")).contacts().size());
        report(
                "preferencesKad.dat",
                PreferencesKadDat.read(read("preferencesKad.dat")).kadId().isZero() ? "zero id" : "ok");
        report(
                "preferences.dat",
                PreferencesDat.isMarkedUserHash(
                                PreferencesDat.read(read("preferences.dat")).userHash())
                        ? "marked hash"
                        : "unmarked hash");
        report(
                "load_index.dat",
                KadIndexFiles.readLoad(read("load_index.dat")).loads().size());
        report(
                "src_index.dat",
                KadIndexFiles.readSources(read("src_index.dat")).keys().size());
        report(
                "key_index.dat",
                KadIndexFiles.readKeywords(read("key_index.dat")).keywords().size());
        report(
                "StoredSearches.met",
                SearchFiles.readStored(read("StoredSearches.met")).size());
        report("SearchSpam.met", SearchFiles.readSpam(read("SearchSpam.met")).size());
        report(
                "staticservers.dat",
                TextFiles.staticServers(read("staticservers.dat")).size());
        report("shareddir.dat", TextFiles.entries(read("shareddir.dat")).size());
        report("sharedfiles.dat", TextFiles.sharedFiles(read("sharedfiles.dat")).size());
        report("addresses.dat", TextFiles.entries(read("addresses.dat")).size());
        report(
                "preferences.ini",
                IniFile.parse(read("preferences.ini")).sections().size() + " sections");
        report("Category.ini", IniFile.parse(read("Category.ini")).sections().size() + " sections");
        report(
                "statistics.ini",
                IniFile.parse(read("statistics.ini")).sections().size() + " sections");
    }

    private void whenTheMessageIsSigned(byte[] content) {
        this.message = content;
        this.signature = Rsa.sign(this.privateKey, content);
    }

    private void whenTheCompletePartsOfEveryDownloadAreHashed() throws IOException {
        try (Stream<Path> files = Files.list(this.temp)) {
            for (Path met : files.filter(p -> p.getFileName().toString().endsWith(".part.met"))
                    .toList()) {
                PartMet.Contents contents = PartMet.read(Files.readAllBytes(met));
                Path data = this.temp.resolve(met.getFileName().toString().replace(".part.met", ".part"));
                long size = contents.size().orElseThrow();
                List<Gap> gaps = contents.gaps();
                int checked = 0;
                for (int part = 0; part < Ed2k.partCount(size) && checked < PARTS_TO_CHECK; part++) {
                    long start = Ed2k.partStart(part);
                    long end = start + Ed2k.partLength(size, part);
                    boolean complete = gaps.stream().noneMatch(g -> g.start() < end && g.end() > start);
                    if (complete && !contents.partHashes().isEmpty() && Files.exists(data)) {
                        this.checkedParts.add(
                                new CheckedPart(contents.partHashes().get(part), md4(data, start, end - start)));
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

    private void thenEveryKnownFileIsConsistent() {
        assertThat(this.consistent).isEqualTo(this.known.size());
    }

    private void thenTheKeyHasTheNetworkSize() {
        assertThat(this.privateKey.modulus().bitLength()).isEqualTo(Rsa.NETWORK_KEY_BITS);
    }

    private void thenThePublicKeyFitsItsLimit() {
        assertThat(RsaKeys.encodePublic(this.privateKey.publicKey()).length)
                .isLessThanOrEqualTo(RsaKeys.MAX_PUBLIC_KEY_SIZE);
    }

    private void thenTheSignatureVerifies() {
        assertThat(Rsa.verify(this.privateKey.publicKey(), this.message, this.signature))
                .isTrue();
        report("cryptkey.dat", "signs and verifies");
    }

    private void thenEveryCompletePartMatchesItsHash() {
        for (CheckedPart part : this.checkedParts) {
            assertThat(part.actual()).isEqualTo(part.expected());
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
