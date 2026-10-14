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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.kad.UInt128;
import org.njmule.core.persist.format.PartMet.Gap;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.ServerTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.Tag.IntTag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Bytes;
import org.njmule.core.util.Ipv4;

/** Corner cases of the binary files of an eMule profile. */
class MetFilesEdgeCaseTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final Hash16 H = Hash16.parseHex("000102030405060708090A0B0C0D0E0F");

    /** Reads a file and uses what a caller would use of it; may only fail with a {@link DecodeException}. */
    @FunctionalInterface
    interface Use {
        void use(byte[] data) throws DecodeException;
    }

    record Sample(String name, byte[] data, Use use) {}

    // Valid files of every counted format, each read and then used.
    private static List<Sample> samples() {
        Hash20 aich = Hash20.of(new byte[Hash20.LENGTH]);
        List<Sample> samples = new ArrayList<>();
        samples.add(new Sample(
                "server.met",
                ServerMet.write(List.of(new ServerMet.Server(
                        Ipv4.of(1, 2, 3, 4),
                        4661,
                        List.of(Tag.of(ServerTag.SERVERNAME, "Big"), Tag.of(ServerTag.PING, 20))))),
                data -> ServerMet.read(data).forEach(s -> {
                    s.name();
                    s.version();
                    s.lastPing();
                    s.obfuscatedTcpPort();
                })));
        samples.add(new Sample(
                "known.met",
                KnownMet.write(List.of(new KnownMet.KnownFile(
                        1, H, List.of(), List.of(Tag.of(FileTag.FILENAME, "a.bin"), Tag.of(FileTag.FILESIZE, 100))))),
                data -> KnownMet.read(data).forEach(f -> {
                    f.name();
                    f.size();
                    f.aichHash();
                    f.transferred();
                    f.isConsistent();
                })));
        samples.add(new Sample(
                "part.met",
                PartMet.write(partMet(Tag.of(FileTag.FILENAME, "x"), Tag.of(FileTag.FILESIZE, 1000))
                        .withGaps(List.of(new Gap(0, 100), new Gap(500, 1000)))),
                data -> {
                    PartMet.Contents c = PartMet.read(data);
                    c.gaps();
                    c.name();
                    c.aichHash();
                    c.paused();
                    c.category();
                }));
        samples.add(new Sample(
                "clients.met",
                ClientsMet.write(List.of(
                        new ClientsMet.Credit(H, 1, 2, 3, Bytes.of(new byte[76])),
                        new ClientsMet.Credit(H, 5_000_000_000L, 0, 0, Bytes.EMPTY))),
                ClientsMet::read));
        samples.add(new Sample(
                "emfriends.met",
                FriendsMet.write(List.of(new FriendsMet.Friend(H, Ipv4.of(1, 2, 3, 4), 4662, 1, 2, List.of()))),
                data -> FriendsMet.read(data).forEach(f -> {
                    f.name();
                    f.kadId();
                })));
        samples.add(new Sample(
                "cancelled.met", CancelledMet.write(7, List.of(CancelledMet.key(7, H))), CancelledMet::read));
        samples.add(new Sample(
                "StoredSearches.met",
                SearchFiles.writeStored(List.of(new SearchFiles.StoredSearch(
                        1,
                        1,
                        false,
                        "title",
                        "expression",
                        "",
                        List.of(new SearchFiles.Result(H, 0, 0, List.of(Tag.of(FileTag.FILENAME, "r"))))))),
                SearchFiles::readStored));
        samples.add(new Sample(
                "SearchSpam.met",
                SearchFiles.writeSpam(List.of(new IntTag(TagName.id(SearchFiles.SPAM_SIZE), 5))),
                SearchFiles::readSpam));
        samples.add(new Sample(
                "known2_64.met", Known2Met.write(List.of(new Known2Met.Entry(aich, List.of(aich)))), Known2Met::read));
        samples.add(new Sample(
                "preferencesKad.dat",
                PreferencesKadDat.write(
                        new PreferencesKadDat.Contents(1, UInt128.parseHex("0102030405060708090A0B0C0D0E0F10"))),
                PreferencesKadDat::read));
        return samples;
    }

    @Provide
    Arbitrary<Sample> sample() {
        return Arbitraries.of(samples());
    }

    @Property
    void aChangedByteOnlyFailsCleanly(
            @ForAll("sample") Sample sample, @ForAll @IntRange(min = 0, max = 100_000) int at, @ForAll byte value)
            throws Exception {
        byte[] data = sample.data().clone();
        data[at % data.length] = value;
        try {
            sample.use().use(data);
        } catch (DecodeException expected) {
            // malformed
        }
    }

    @Test
    void everyCutOfACountedFileFailsCleanly() throws Exception {
        for (Sample sample : samples()) {
            // known2_64.met has no count: a cut between two entries is a shorter valid file.
            if (sample.name().equals("known2_64.met")) {
                continue;
            }
            // preferencesKad.dat ends with a byte nobody reads.
            int complete = sample.name().equals("preferencesKad.dat") ? 22 : sample.data().length;
            for (int length = 0; length < complete; length++) {
                byte[] cut = Arrays.copyOf(sample.data(), length);
                assertThatThrownBy(() -> sample.use().use(cut))
                        .as("%s cut to %d bytes", sample.name(), length)
                        .isInstanceOf(DecodeException.class);
            }
        }
    }

    @Test
    void hugeCountsWithNoDataFailCleanly() {
        for (String file : List.of("e0ffffffff", "0effffffff", "0e01ffff", "45ffffffff", "0f01ffff", "0fffffffff")) {
            byte[] data = HEX.parseHex(file);
            for (Sample sample : samples()) {
                try {
                    sample.use().use(data);
                } catch (DecodeException expected) {
                    // malformed
                }
            }
        }
    }

    @Test
    void partMetWithoutAUsableSizeIsRefused() throws DecodeException {
        for (long size : new long[] {0, Ed2k.MAX_FILE_SIZE + 1}) {
            byte[] data = PartMet.write(partMet(new IntTag(TagName.id(FileTag.FILESIZE), size)));
            assertThatThrownBy(() -> PartMet.read(data)).as("size %d", size).isInstanceOf(DecodeException.class);
        }
        // Header, mtime, file hash, no part hashes, no tags.
        byte[] sizeless = HEX.parseHex("e0" + "00000000" + H.toHex() + "0000" + "00000000");
        assertThatThrownBy(() -> PartMet.read(sizeless)).isInstanceOf(DecodeException.class);
        assertThat(PartMet.read(PartMet.write(partMet(Tag.of(FileTag.FILESIZE, Ed2k.MAX_FILE_SIZE))))
                        .version())
                .isEqualTo(PartMet.VERSION_LARGE);
    }

    @Test
    void gapTagsPairByTheirNumberNotItsSpelling() {
        PartMet.Contents contents = partMet(
                Tag.of(FileTag.FILESIZE, 1000),
                new IntTag(TagName.of("\t01"), 100),
                new IntTag(TagName.of("\n1"), 200),
                new IntTag(TagName.of("\t000"), 300),
                new IntTag(TagName.of("\n0"), 400));

        assertThat(contents.gaps()).containsExactly(new Gap(100, 200), new Gap(300, 400));
    }

    @Test
    void oddGapsAreDroppedOrClamped() {
        PartMet.Contents contents = partMet(
                Tag.of(FileTag.FILESIZE, 1000),
                // Inverted, empty, past the end, clamped, duplicate start, and a gap tag that is not a number.
                new IntTag(TagName.of("\t1"), 50),
                new IntTag(TagName.of("\n1"), 40),
                new IntTag(TagName.of("\t2"), 60),
                new IntTag(TagName.of("\n2"), 60),
                new IntTag(TagName.of("\t3"), 1000),
                new IntTag(TagName.of("\n3"), 2000),
                new IntTag(TagName.of("\t4"), 900),
                new IntTag(TagName.of("\n4"), 5000),
                new IntTag(TagName.of("\t5"), 100),
                new IntTag(TagName.of("\n5"), 150),
                new IntTag(TagName.of("\t6"), 100),
                new IntTag(TagName.of("\n6"), 200),
                new Tag.StringTag(TagName.of("\t7"), "300"),
                new IntTag(TagName.of("\n7"), 400));

        assertThat(contents.gaps()).containsExactly(new Gap(100, 200), new Gap(900, 1000));
    }

    @Property
    void gapListsSurviveAWriteAndARead(@ForAll("gaps") List<Gap> gaps) throws DecodeException {
        PartMet.Contents written = partMet(Tag.of(FileTag.FILESIZE, 1_000_000)).withGaps(gaps);

        assertThat(PartMet.read(PartMet.write(written)).gaps()).isEqualTo(gaps);
    }

    @Provide
    Arbitrary<List<Gap>> gaps() {
        // Sorted, disjoint gaps within the file: cut points two by two.
        return Arbitraries.longs().between(0, 1_000_000).set().ofMaxSize(40).map(points -> {
            List<Long> sorted = points.stream().sorted().toList();
            List<Gap> gaps = new ArrayList<>();
            for (int i = 0; i + 1 < sorted.size(); i += 2) {
                gaps.add(new Gap(sorted.get(i), sorted.get(i + 1)));
            }
            return gaps;
        });
    }

    @Test
    void aPublicKeyOfMoreThanEightyBytesIsRefused() {
        byte[] data = ClientsMet.write(List.of(new ClientsMet.Credit(H, 0, 0, 0, Bytes.of(new byte[80]))));
        // The key size byte follows the 16-byte hash, five u32 and one u16 of the first record.
        int keySize = 1 + 4 + 16 + 5 * 4 + 2;
        assertThat(data[keySize]).isEqualTo((byte) 80);

        data[keySize] = 81;

        assertThatThrownBy(() -> ClientsMet.read(data)).isInstanceOf(DecodeException.class);
    }

    @Test
    void creditsBeyondFourGigabytesSurviveAWriteAndARead() throws DecodeException {
        List<ClientsMet.Credit> credits =
                List.of(new ClientsMet.Credit(H, 5_000_000_000L, (1L << 40) + 7, 1_700_000_000L, Bytes.EMPTY));

        assertThat(ClientsMet.read(ClientsMet.write(credits))).isEqualTo(credits);
    }

    private static PartMet.Contents partMet(Tag... tags) {
        return new PartMet.Contents(PartMet.VERSION, 0, H, List.of(), List.of(tags));
    }
}
