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
package org.njmule.core.protocol.tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.jqwik.api.Arbitrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.njmule.core.Corpus;
import org.njmule.core.Corpus.Case;
import org.njmule.core.Mutations;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.io.StringEncoding;

/** The tag codec's answers to the inputs of {@code golden/tag-codec.txt}, which other engines replay. */
class TagCodecCorpusTest {

    private static final String NAME = "tag-codec";
    private static final String HEADER = """
            Inputs of the tag decoders and the answers of njMule's Java engine, for other engines to replay.
            Kinds: ed2k-tag (one eD2K tag), ed2k-tags (count eD2K tags, at most 64), kad-tags (a Kad tag list).
            Answers: used (bytes read); the tags written again in the new eD2K encoding (new), the old one (old)
            or as a Kad list (kad), - where writing them is refused; error=decode where reading is refused.
            Answers again: ./gradlew :njmule-core:test --tests '*CorpusTest' -Pnjmule.corpus.write=true
            (new inputs once this file is deleted).""";
    private static final String ED2K_TAG = "ed2k-tag";
    private static final String ED2K_TAGS = "ed2k-tags";
    private static final String KAD_TAGS = "kad-tags";
    private static final int MAX_COUNT = 64;
    private static final int MAX_INPUT = 256;
    private static final HexFormat HEX = HexFormat.of();

    private Case input;
    private Case answer;
    private List<Case> answers;

    @ParameterizedTest
    @MethodSource("recordedCases")
    void shouldGiveTheRecordedAnswers(Case recorded) {
        givenTheCorpusIsChecked();
        givenTheInputOf(recorded);
        whenItIsDecoded();
        thenTheAnswerIs(recorded);
    }

    @Test
    void shouldWriteItsAnswersWhenAsked() throws IOException {
        givenTheCorpusIsWritten();
        whenTheInputsAreAnswered();
        thenEveryInputHasAnAnswer();
    }

    private static Stream<Arguments> recordedCases() {
        return Corpus.read(NAME).stream().map(c -> Arguments.argumentSet(c.kind(), c));
    }

    private void givenTheCorpusIsChecked() {
        assumeFalse(Corpus.writing(), "the corpus is being written");
    }

    private void givenTheCorpusIsWritten() {
        assumeTrue(Corpus.writing(), "-Pnjmule.corpus.write=true");
    }

    private void givenTheInputOf(Case recorded) {
        this.input = inputOf(recorded);
    }

    private void whenItIsDecoded() {
        this.answer = answered(this.input);
    }

    private void whenTheInputsAreAnswered() throws IOException {
        List<Case> inputs =
                Corpus.read(NAME).stream().map(TagCodecCorpusTest::inputOf).toList();
        this.answers = (inputs.isEmpty() ? newInputs() : inputs)
                .stream().map(TagCodecCorpusTest::answered).toList();
        Corpus.write(NAME, HEADER, this.answers);
    }

    private void thenTheAnswerIs(Case recorded) {
        assertThat(this.answer).hasToString(recorded.toString());
    }

    private void thenEveryInputHasAnAnswer() {
        assertThat(this.answers)
                .isNotEmpty()
                .allMatch(c -> c.fields().containsKey("used") || c.fields().containsKey("error"));
    }

    private static Case inputOf(Case recorded) {
        SequencedMap<String, String> fields = new LinkedHashMap<>();
        fields.put("in", recorded.field("in"));
        if (recorded.fields().containsKey("count")) {
            fields.put("count", recorded.field("count"));
        }
        return new Case(recorded.kind(), fields);
    }

    private static Case answered(Case input) {
        SequencedMap<String, String> fields = new LinkedHashMap<>(input.fields());
        PacketReader in = PacketReader.of(HEX.parseHex(input.field("in")));
        try {
            switch (input.kind()) {
                case ED2K_TAG -> {
                    List<Tag> tags = List.of(TagCodec.readEd2k(in));
                    fields.put("used", Integer.toString(in.position()));
                    fields.put("new", hexWritten(out -> writeNewEd2k(out, tags)));
                    fields.put("old", hexWritten(out -> writeOldEd2k(out, tags)));
                }
                case ED2K_TAGS -> {
                    List<Tag> tags = TagCodec.readEd2kList(in, Long.parseLong(input.field("count")), MAX_COUNT);
                    fields.put("used", Integer.toString(in.position()));
                    fields.put("new", hexWritten(out -> writeNewEd2k(out, tags)));
                }
                case KAD_TAGS -> {
                    List<Tag> tags = TagCodec.readKadList(in);
                    fields.put("used", Integer.toString(in.position()));
                    fields.put("kad", hexWritten(out -> TagCodec.writeKadList(out, tags)));
                }
                default -> throw new IllegalArgumentException("Unknown kind: " + input.kind());
            }
        } catch (DecodeException e) {
            fields.put("error", "decode");
        }
        return new Case(input.kind(), fields);
    }

    /** Tags, lists and Kad lists as the codec writes them, each also changed by {@link Mutations}. */
    private static List<Case> newInputs() {
        TagCodecTest generators = new TagCodecTest();
        List<Case> inputs = new ArrayList<>();
        for (List<Tag> tags : samples(generators.ed2kTags().map(List::of), TagCodecCorpusTest::writeNewEd2k, 40)) {
            byte[] data = bytes(out -> writeNewEd2k(out, tags));
            inputs.add(newInput(ED2K_TAG, data));
            inputs.add(newInput(ED2K_TAG, bytes(out -> writeOldEd2k(out, tags))));
            inputs.add(newInput(ED2K_TAG, Mutations.changed(data, inputs.size())));
        }
        for (List<Tag> tags :
                samples(generators.ed2kTags().list().ofMaxSize(4), TagCodecCorpusTest::writeNewEd2k, 20)) {
            byte[] data = bytes(out -> writeNewEd2k(out, tags));
            inputs.add(newInput(ED2K_TAGS, data, tags.size()));
            inputs.add(newInput(ED2K_TAGS, Mutations.changed(data, inputs.size()), tags.size()));
        }
        for (List<Tag> tags : samples(generators.kadTags(), TagCodec::writeKadList, 20)) {
            byte[] data = bytes(out -> TagCodec.writeKadList(out, tags));
            inputs.add(newInput(KAD_TAGS, data));
            inputs.add(newInput(KAD_TAGS, Mutations.changed(data, inputs.size())));
        }
        return inputs;
    }

    /** {@code count} values of {@code arbitrary} that take at most {@link #MAX_INPUT} bytes once written. */
    private static List<List<Tag>> samples(
            Arbitrary<List<Tag>> arbitrary, BiConsumer<PacketWriter, List<Tag>> write, int count) {
        return arbitrary
                .sampleStream()
                .filter(tags -> bytes(out -> write.accept(out, tags)).length <= MAX_INPUT)
                .limit(count)
                .toList();
    }

    private static Case newInput(String kind, byte[] data) {
        SequencedMap<String, String> fields = new LinkedHashMap<>();
        fields.put("in", HEX.formatHex(data));
        return new Case(kind, fields);
    }

    private static Case newInput(String kind, byte[] data, int count) {
        SequencedMap<String, String> fields =
                new LinkedHashMap<>(newInput(kind, data).fields());
        fields.put("count", Integer.toString(count));
        return new Case(kind, fields);
    }

    private static void writeNewEd2k(PacketWriter out, List<Tag> tags) {
        TagCodec.writeEd2kList(out, tags, Ed2kTagFormat.NEW, StringEncoding.UTF8);
    }

    private static void writeOldEd2k(PacketWriter out, List<Tag> tags) {
        TagCodec.writeEd2kList(out, tags, Ed2kTagFormat.OLD, StringEncoding.UTF8_BOM_IF_NEEDED);
    }

    private static String hexWritten(Consumer<PacketWriter> write) {
        try {
            return HEX.formatHex(bytes(write));
        } catch (IllegalArgumentException e) {
            return "-";
        }
    }

    private static byte[] bytes(Consumer<PacketWriter> write) {
        PacketWriter out = new PacketWriter();
        write.accept(out);
        return out.toByteArray();
    }
}
