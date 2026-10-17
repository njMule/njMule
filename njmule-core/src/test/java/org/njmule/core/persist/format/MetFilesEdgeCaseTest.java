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
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.njmule.core.Mutations;
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
    // The key size byte follows the 16-byte hash, five u32 and one u16 of the first record.
    private static final int KEY_SIZE_AT = 1 + 4 + 16 + 5 * 4 + 2;

    private Sample sample;
    private byte[] data;
    private Map<String, Throwable> failures;
    private Throwable exception;
    private PartMet.Contents written;
    private PartMet.Contents read;
    private List<Gap> gapsWritten;
    private List<Gap> gapsTaken;
    private List<ClientsMet.Credit> credits;
    private List<ClientsMet.Credit> creditsRead;

    /** Reads a file and uses what a caller would use of it; may only fail with a {@link DecodeException}. */
    @FunctionalInterface
    interface Use {
        void use(byte[] data) throws DecodeException;
    }

    record Sample(String name, Bytes data, Use use) {
        Sample(String name, byte[] data, Use use) {
            this(name, Bytes.of(data), use);
        }
    }

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

    // known2_64.met has no count: a cut between two entries is a shorter valid file.
    static Stream<Arguments> countedSamples() {
        return samples().stream()
                .filter(sample -> !sample.name().equals("known2_64.met"))
                .map(sample -> Arguments.of(Named.of(sample.name(), sample)));
    }

    @Property
    void shouldOnlyFailCleanlyOnAChangedByte(
            @ForAll("sample") Sample sample, @ForAll @IntRange(min = 0, max = 100_000) int at, @ForAll byte value) {
        givenTheSampleWithAChangedByte(sample, at, value);

        whenTheSampleUsesTheData();

        thenNothingButDecodeExceptionsOccurred();
    }

    @Property(tries = 3000)
    void shouldOnlyFailCleanlyOnASampleChangedAtRandom(@ForAll("sample") Sample sample, @ForAll long seed) {
        givenTheSampleChangedAtRandom(sample, seed);

        whenTheSampleUsesTheData();

        thenNothingButDecodeExceptionsOccurred();
    }

    @ParameterizedTest
    @MethodSource("countedSamples")
    void shouldFailCleanlyOnEveryCutOfACountedFile(Sample sample) {
        givenTheSample(sample);

        whenTheSampleIsCutToEveryShorterLength();

        thenEveryCutFailedWithADecodeException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"e0ffffffff", "0effffffff", "0e01ffff", "45ffffffff", "0f01ffff", "0fffffffff"})
    void shouldFailCleanlyOnHugeCountsWithNoData(String file) {
        givenTheData(file);

        whenEverySampleUsesTheData();

        thenNothingButDecodeExceptionsOccurred();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, Ed2k.MAX_FILE_SIZE + 1})
    void shouldRefuseAPartMetWithoutAUsableSize(long size) {
        givenAPartMetWith(new IntTag(TagName.id(FileTag.FILESIZE), size));

        whenThePartMetIsRead();

        thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void shouldRefuseAPartMetWithoutSize() {
        // Header, mtime, file hash, no part hashes, no tags.
        givenTheData("e0" + "00000000" + H.toHex() + "0000" + "00000000");

        whenThePartMetIsRead();

        thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void shouldWriteThePartMetOfTheLargestSizeInTheLargeVersion() {
        givenAPartMetWith(Tag.of(FileTag.FILESIZE, Ed2k.MAX_FILE_SIZE));

        whenThePartMetIsRead();

        thenThePartMetVersionIs(PartMet.VERSION_LARGE);
    }

    @Test
    void shouldPairTheGapTagsByTheirNumberNotItsSpelling() {
        givenAPartMetWithTheTags(
                Tag.of(FileTag.FILESIZE, 1000),
                new IntTag(TagName.of("\t01"), 100),
                new IntTag(TagName.of("\n1"), 200),
                new IntTag(TagName.of("\t000"), 300),
                new IntTag(TagName.of("\n0"), 400));

        whenItsGapsAreTaken();

        thenTheGapsAre(new Gap(100, 200), new Gap(300, 400));
    }

    @Test
    void shouldDropOrClampOddGaps() {
        givenAPartMetWithTheTags(
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

        whenItsGapsAreTaken();

        thenTheGapsAre(new Gap(100, 200), new Gap(900, 1000));
    }

    @Property
    void shouldKeepTheGapListsThroughAWriteAndARead(@ForAll("gaps") List<Gap> gaps) {
        givenAPartMetWithTheGaps(gaps);

        whenThePartMetIsRead();

        thenTheGapsReadAreTheOnesWritten();
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
    void shouldRefuseAPublicKeyOfMoreThanEightyBytes() {
        givenCreditsWithAPublicKeyOf(80);
        givenTheKeySizeByteSetTo(81);

        whenTheCreditsAreRead();

        thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void shouldKeepCreditsBeyondFourGigabytesThroughAWriteAndARead() {
        givenTheCreditsWritten(new ClientsMet.Credit(H, 5_000_000_000L, (1L << 40) + 7, 1_700_000_000L, Bytes.EMPTY));

        whenTheCreditsAreRead();

        thenTheCreditsReadAreTheOnesWritten();
    }

    private void givenTheSample(Sample value) {
        this.sample = value;
        this.data = value.data().toByteArray();
    }

    private void givenTheSampleChangedAtRandom(Sample value, long seed) {
        givenTheSample(value);
        this.data = Mutations.changed(this.data, seed);
    }

    private void givenTheSampleWithAChangedByte(Sample value, int at, byte changed) {
        givenTheSample(value);
        this.data[at % this.data.length] = changed;
    }

    private void givenTheData(String hex) {
        this.data = HEX.parseHex(hex);
    }

    private void givenAPartMetWith(Tag sizeTag) {
        this.written = partMet(sizeTag);
        this.data = PartMet.write(this.written);
    }

    private void givenAPartMetWithTheGaps(List<Gap> gaps) {
        this.gapsWritten = gaps;
        this.written = partMet(Tag.of(FileTag.FILESIZE, 1_000_000)).withGaps(gaps);
        this.data = PartMet.write(this.written);
    }

    private void givenAPartMetWithTheTags(Tag... tags) {
        this.written = partMet(tags);
    }

    private void givenCreditsWithAPublicKeyOf(int bytes) {
        this.data = ClientsMet.write(List.of(new ClientsMet.Credit(H, 0, 0, 0, Bytes.of(new byte[bytes]))));
        assertThat(this.data[KEY_SIZE_AT]).isEqualTo((byte) bytes);
    }

    private void givenTheKeySizeByteSetTo(int size) {
        this.data[KEY_SIZE_AT] = (byte) size;
    }

    private void givenTheCreditsWritten(ClientsMet.Credit... written) {
        this.credits = List.of(written);
        this.data = ClientsMet.write(this.credits);
    }

    private void whenTheSampleUsesTheData() {
        byte[] used = this.data;
        this.failures = new LinkedHashMap<>();
        this.failures.put(
                this.sample.name(), catchThrowable(() -> this.sample.use().use(used)));
    }

    private void whenTheSampleIsCutToEveryShorterLength() {
        // preferencesKad.dat ends with a byte nobody reads.
        int complete = this.sample.name().equals("preferencesKad.dat") ? 22 : this.data.length;
        this.failures = new LinkedHashMap<>();
        for (int length = 0; length < complete; length++) {
            byte[] cut = Arrays.copyOf(this.data, length);
            this.failures.put(
                    String.format("%s cut to %d bytes", this.sample.name(), length),
                    catchThrowable(() -> this.sample.use().use(cut)));
        }
    }

    private void whenEverySampleUsesTheData() {
        byte[] used = this.data;
        this.failures = new LinkedHashMap<>();
        for (Sample each : samples()) {
            this.failures.put(each.name(), catchThrowable(() -> each.use().use(used)));
        }
    }

    private void whenThePartMetIsRead() {
        this.exception = null;
        try {
            this.read = PartMet.read(this.data);
        } catch (DecodeException e) {
            this.exception = e;
        }
    }

    private void whenItsGapsAreTaken() {
        this.gapsTaken = this.written.gaps();
    }

    private void whenTheCreditsAreRead() {
        try {
            this.creditsRead = ClientsMet.read(this.data);
        } catch (DecodeException e) {
            this.exception = e;
        }
    }

    private void thenNothingButDecodeExceptionsOccurred() {
        this.failures.forEach((name, failure) -> {
            if (failure != null) {
                assertThat(failure).as(name).isInstanceOf(DecodeException.class);
            }
        });
    }

    private void thenEveryCutFailedWithADecodeException() {
        this.failures.forEach((cut, failure) -> assertThat(failure).as(cut).isInstanceOf(DecodeException.class));
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private void thenThePartMetVersionIs(int version) {
        assertThat(this.exception).isNull();
        assertThat(this.read.version()).isEqualTo(version);
    }

    private void thenTheGapsAre(Gap... gaps) {
        assertThat(this.gapsTaken).containsExactly(gaps);
    }

    private void thenTheGapsReadAreTheOnesWritten() {
        assertThat(this.exception).isNull();
        assertThat(this.read.gaps()).isEqualTo(this.gapsWritten);
    }

    private void thenTheCreditsReadAreTheOnesWritten() {
        assertThat(this.exception).isNull();
        assertThat(this.creditsRead).isEqualTo(this.credits);
    }

    private static PartMet.Contents partMet(Tag... tags) {
        return new PartMet.Contents(PartMet.VERSION, 0, H, List.of(), List.of(tags));
    }
}
