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

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.njmule.core.Canonical;
import org.njmule.core.Corpus;
import org.njmule.core.Mutations;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;
import org.njmule.core.kad.UInt128;
import org.njmule.core.persist.format.IpFilterDat.Range;
import org.njmule.core.persist.format.KadIndexFiles.Entry;
import org.njmule.core.persist.format.KadIndexFiles.IndexKey;
import org.njmule.core.persist.format.KadIndexFiles.Keyword;
import org.njmule.core.persist.format.KadIndexFiles.KeywordEntry;
import org.njmule.core.persist.format.KadIndexFiles.KeywordIndex;
import org.njmule.core.persist.format.KadIndexFiles.KeywordSource;
import org.njmule.core.persist.format.KadIndexFiles.Load;
import org.njmule.core.persist.format.KadIndexFiles.LoadIndex;
import org.njmule.core.persist.format.KadIndexFiles.PublishedName;
import org.njmule.core.persist.format.KadIndexFiles.Publisher;
import org.njmule.core.persist.format.KadIndexFiles.Source;
import org.njmule.core.persist.format.KadIndexFiles.SourceIndex;
import org.njmule.core.persist.format.KadIndexFiles.Tracking;
import org.njmule.core.persist.format.NodesDat.Contact;
import org.njmule.core.persist.format.NodesDat.UdpKey;
import org.njmule.core.persist.format.TextFiles.StaticServer;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.util.Bytes;
import org.njmule.core.util.Ipv4;
import tools.jackson.databind.node.ObjectNode;

/** The readers of the profile's files and their answers to the inputs of {@code golden/profile-files.jsonl}. */
class ProfileFilesCorpusTest {

    private static final String NAME = "profile-files";
    private static final int CHANGES_PER_FILE = 3;
    private static final HexFormat HEX = HexFormat.of();
    private static final Hash16 H = Hash16.parseHex("000102030405060708090A0B0C0D0E0F");
    private static final UInt128 K1 = UInt128.parseHex("0102030405060708090A0B0C0D0E0F10");
    private static final UInt128 K2 = UInt128.parseHex("F0E0D0C0B0A090807060504030201000");

    @FunctionalInterface
    private interface Reader {
        Object read(byte[] data) throws DecodeException;
    }

    // Each kind is the file's name, or its name and the way of reading a damaged one.
    private static final Map<String, Reader> READERS = Map.ofEntries(
            entry("server.met", ServerMet::read),
            entry("known.met", KnownMet::read),
            entry("known.met salvaged", KnownMet::salvage),
            entry("part.met", PartMet::read),
            entry("clients.met", ClientsMet::read),
            entry("emfriends.met", FriendsMet::read),
            entry("cancelled.met", CancelledMet::read),
            entry("StoredSearches.met", SearchFiles::readStored),
            entry("SearchSpam.met", SearchFiles::readSpam),
            entry("known2_64.met", Known2Met::read),
            entry("known2_64.met salvaged", Known2Met::salvage),
            entry("preferencesKad.dat", PreferencesKadDat::read),
            entry("nodes.dat", NodesDat::read),
            entry("preferences.dat", PreferencesDat::read),
            entry("load_index.dat", KadIndexFiles::readLoad),
            entry("src_index.dat", KadIndexFiles::readSources),
            entry("key_index.dat", KadIndexFiles::readKeywords),
            entry("ipfilter.dat", data -> IpFilterDat.read(data, "ipfilter.dat")),
            entry("staticservers.dat", TextFiles::staticServers),
            entry("shareddir.dat", TextFiles::sharedFiles));

    private ObjectNode input;
    private ObjectNode answer;
    private List<ObjectNode> answers;

    @ParameterizedTest
    @MethodSource("recordedCases")
    void shouldGiveTheRecordedAnswers(ObjectNode recorded) {
        givenTheCorpusIsChecked();
        givenTheInputOf(recorded);
        whenItIsRead();
        thenTheAnswerIs(recorded);
    }

    @Test
    void shouldWriteItsAnswersWhenAsked() throws IOException {
        givenTheCorpusIsWritten();
        whenTheInputsAreAnswered();
        thenEveryInputHasAnAnswer();
    }

    private static Stream<Arguments> recordedCases() {
        return Corpus.read(NAME).stream()
                .map(c -> Arguments.argumentSet(c.get("kind").asString(), c));
    }

    private void givenTheCorpusIsChecked() {
        assumeFalse(Corpus.writing(), "the corpus is being written");
    }

    private void givenTheCorpusIsWritten() {
        assumeTrue(Corpus.writing(), "-Pnjmule.corpus.write=true");
    }

    private void givenTheInputOf(ObjectNode recorded) {
        this.input = inputOf(recorded);
    }

    private void whenItIsRead() {
        this.answer = answered(this.input);
    }

    private void whenTheInputsAreAnswered() throws IOException {
        List<ObjectNode> inputs =
                Corpus.read(NAME).stream().map(ProfileFilesCorpusTest::inputOf).toList();
        this.answers = (inputs.isEmpty() ? newInputs() : inputs)
                .stream().map(ProfileFilesCorpusTest::answered).toList();
        Corpus.write(NAME, this.answers);
    }

    private void thenTheAnswerIs(ObjectNode recorded) {
        assertThat(this.answer).hasToString(recorded.toString());
    }

    private void thenEveryInputHasAnAnswer() {
        assertThat(this.answers).isNotEmpty().allMatch(c -> c.has("value") || c.has("error"));
    }

    private static ObjectNode inputOf(ObjectNode recorded) {
        return Corpus.newCase(
                recorded.get("kind").asString(), HEX.parseHex(recorded.get("in").asString()));
    }

    private static ObjectNode answered(ObjectNode input) {
        ObjectNode result = input.deepCopy();
        Reader reader = READERS.get(input.get("kind").asString());
        try {
            result.set(
                    "value",
                    Canonical.of(reader.read(HEX.parseHex(input.get("in").asString()))));
        } catch (DecodeException e) {
            result.put("error", "decode");
        }
        return result;
    }

    /** A file of every kind as the engine writes it, then changed by {@link Mutations}. */
    private static List<ObjectNode> newInputs() {
        List<ObjectNode> inputs = new ArrayList<>();
        newFiles().forEach((kind, data) -> {
            inputs.add(Corpus.newCase(kind, data));
            for (int seed = 1; seed <= CHANGES_PER_FILE; seed++) {
                inputs.add(Corpus.newCase(kind, Mutations.changed(data, seed)));
            }
        });
        return inputs;
    }

    private static Map<String, byte[]> newFiles() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        MetFilesEdgeCaseTest.samples()
                .forEach(sample -> files.put(sample.name(), sample.data().toByteArray()));
        files.put("known.met salvaged", files.get("known.met"));
        files.put("known2_64.met salvaged", files.get("known2_64.met"));
        files.put(
                "nodes.dat",
                NodesDat.write(List.of(new Contact(
                        K1,
                        Ipv4.of(5, 6, 7, 8),
                        4672,
                        4662,
                        10,
                        0,
                        new UdpKey(0x12345678, Ipv4.of(9, 9, 9, 9)),
                        true))));
        files.put(
                "preferences.dat",
                PreferencesDat.write(new PreferencesDat.Contents(PreferencesDat.VERSION, H, Bytes.of(new byte[44]))));
        files.put(
                "load_index.dat",
                KadIndexFiles.writeLoad(new LoadIndex(1_700_000_000L, List.of(new Load(K1, 1_700_000_100L)))));
        files.put(
                "src_index.dat",
                KadIndexFiles.writeSources(new SourceIndex(
                        2,
                        1_700_018_000L,
                        List.of(new IndexKey(
                                K1,
                                List.of(new Source(
                                        K2,
                                        List.of(new Entry(
                                                1_700_001_000L, List.of(Tag.of(FileTag.SOURCEPORT, 4662)))))))))));
        files.put("key_index.dat", KadIndexFiles.writeKeywords(keywordIndex()));
        files.put(
                "ipfilter.dat",
                IpFilterDat.write(List.of(
                        new Range(Ipv4.of(1, 2, 3, 0), Ipv4.of(1, 2, 3, 255), 0, "Bad people"),
                        new Range(Ipv4.of(10, 0, 0, 0), Ipv4.of(10, 0, 1, 255), 100, ""))));
        files.put(
                "staticservers.dat",
                TextFiles.writeStaticServers(List.of(
                        new StaticServer("server.example.org", 4661, TextFiles.PRIORITY_NORMAL, "Normal one"),
                        new StaticServer("1.2.3.4", 4242, TextFiles.PRIORITY_HIGH, "Name, with comma"))));
        files.put("shareddir.dat", TextFiles.writeLines(List.of("C:\\Shared\\", "-C:\\Shared\\Private\\")));
        return files;
    }

    private static KeywordIndex keywordIndex() {
        Tracking tracking = new Tracking(
                List.of(Hash20.of(new byte[Hash20.LENGTH])),
                List.of(new PublishedName("Città.mkv", 3)),
                List.of(
                        new Publisher(Ipv4.of(1, 2, 3, 4), 1_700_000_500L, 0),
                        new Publisher(Ipv4.of(5, 6, 7, 8), 1_700_000_600L, 0xFFFF)));
        return new KeywordIndex(
                4,
                1_700_086_400L,
                K2,
                List.of(new Keyword(
                        K1,
                        List.of(new KeywordSource(
                                K2,
                                List.of(new KeywordEntry(
                                        1_700_002_000L, tracking, List.of(Tag.of(FileTag.FILENAME, "a.mkv")))))))));
    }
}
