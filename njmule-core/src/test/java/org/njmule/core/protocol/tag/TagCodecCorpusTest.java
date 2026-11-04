/*
 * njMule - New Jack Mule
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
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.jqwik.api.Arbitrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.njmule.core.Corpus;
import org.njmule.core.Mutations;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.io.StringEncoding;
import tools.jackson.databind.node.ObjectNode;

/** The tag codec's answers to the inputs of {@code golden/tag-codec.jsonl}, which other engines replay. */
class TagCodecCorpusTest {

    private static final String NAME = "tag-codec";
    private static final String ED2K_TAG = "ed2k-tag";
    private static final String ED2K_TAGS = "ed2k-tags";
    private static final String KAD_TAGS = "kad-tags";
    private static final int MAX_COUNT = 64;
    private static final int MAX_INPUT = 256;
    private static final HexFormat HEX = HexFormat.of();

    private ObjectNode input;
    private ObjectNode answer;
    private List<ObjectNode> answers;

    @ParameterizedTest
    @MethodSource("recordedCases")
    void shouldGiveTheRecordedAnswers(ObjectNode recorded) {
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

    private void whenItIsDecoded() {
        this.answer = answered(this.input);
    }

    private void whenTheInputsAreAnswered() throws IOException {
        List<ObjectNode> inputs =
                Corpus.read(NAME).stream().map(TagCodecCorpusTest::inputOf).toList();
        this.answers = (inputs.isEmpty() ? newInputs() : inputs)
                .stream().map(TagCodecCorpusTest::answered).toList();
        Corpus.write(NAME, this.answers);
    }

    private void thenTheAnswerIs(ObjectNode recorded) {
        assertThat(this.answer).hasToString(recorded.toString());
    }

    private void thenEveryInputHasAnAnswer() {
        assertThat(this.answers).isNotEmpty().allMatch(c -> c.has("used") || c.has("error"));
    }

    private static ObjectNode inputOf(ObjectNode recorded) {
        ObjectNode fresh = Corpus.newCase(
                recorded.get("kind").asString(), HEX.parseHex(recorded.get("in").asString()));
        if (recorded.has("count")) {
            fresh.put("count", recorded.get("count").asInt());
        }
        return fresh;
    }

    private static ObjectNode answered(ObjectNode input) {
        ObjectNode result = input.deepCopy();
        PacketReader in = PacketReader.of(HEX.parseHex(input.get("in").asString()));
        try {
            switch (input.get("kind").asString()) {
                case ED2K_TAG -> {
                    List<Tag> tags = List.of(TagCodec.readEd2k(in));
                    result.put("used", in.position());
                    result.put("new", hexWritten(out -> writeNewEd2k(out, tags)));
                    result.put("old", hexWritten(out -> writeOldEd2k(out, tags)));
                }
                case ED2K_TAGS -> {
                    List<Tag> tags =
                            TagCodec.readEd2kList(in, input.get("count").asLong(), MAX_COUNT);
                    result.put("used", in.position());
                    result.put("new", hexWritten(out -> writeNewEd2k(out, tags)));
                }
                case KAD_TAGS -> {
                    List<Tag> tags = TagCodec.readKadList(in);
                    result.put("used", in.position());
                    result.put("kad", hexWritten(out -> TagCodec.writeKadList(out, tags)));
                }
                default -> throw new IllegalArgumentException("Unknown kind: " + input.get("kind"));
            }
        } catch (DecodeException e) {
            result.put("error", "decode");
        }
        return result;
    }

    /** Tags, lists and Kad lists as the codec writes them, each also changed by {@link Mutations}. */
    private static List<ObjectNode> newInputs() {
        TagCodecTest generators = new TagCodecTest();
        List<ObjectNode> inputs = new ArrayList<>();
        for (List<Tag> tags : samples(generators.ed2kTags().map(List::of), TagCodecCorpusTest::writeNewEd2k, 40)) {
            byte[] data = bytes(out -> writeNewEd2k(out, tags));
            inputs.add(Corpus.newCase(ED2K_TAG, data));
            inputs.add(Corpus.newCase(ED2K_TAG, bytes(out -> writeOldEd2k(out, tags))));
            inputs.add(Corpus.newCase(ED2K_TAG, Mutations.changed(data, inputs.size())));
        }
        for (List<Tag> tags :
                samples(generators.ed2kTags().list().ofMaxSize(4), TagCodecCorpusTest::writeNewEd2k, 20)) {
            byte[] data = bytes(out -> writeNewEd2k(out, tags));
            inputs.add(Corpus.newCase(ED2K_TAGS, data).put("count", tags.size()));
            inputs.add(Corpus.newCase(ED2K_TAGS, Mutations.changed(data, inputs.size()))
                    .put("count", tags.size()));
        }
        for (List<Tag> tags : samples(generators.kadTags(), TagCodec::writeKadList, 20)) {
            byte[] data = bytes(out -> TagCodec.writeKadList(out, tags));
            inputs.add(Corpus.newCase(KAD_TAGS, data));
            inputs.add(Corpus.newCase(KAD_TAGS, Mutations.changed(data, inputs.size())));
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

    private static void writeNewEd2k(PacketWriter out, List<Tag> tags) {
        TagCodec.writeEd2kList(out, tags, Ed2kTagFormat.NEW, StringEncoding.UTF8);
    }

    private static void writeOldEd2k(PacketWriter out, List<Tag> tags) {
        TagCodec.writeEd2kList(out, tags, Ed2kTagFormat.OLD, StringEncoding.UTF8_BOM_IF_NEEDED);
    }

    /** The bytes {@code write} writes in hex, or null where it refuses the tags. */
    private static String hexWritten(Consumer<PacketWriter> write) {
        try {
            return HEX.formatHex(bytes(write));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static byte[] bytes(Consumer<PacketWriter> write) {
        PacketWriter out = new PacketWriter();
        write.accept(out);
        return out.toByteArray();
    }
}
