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
package org.njmule.core.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.njmule.core.Canonical;
import org.njmule.core.Corpus;
import org.njmule.core.Mutations;
import org.njmule.core.protocol.MutatedMessagesTest.Sample;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.packet.Packet;
import tools.jackson.databind.node.ObjectNode;

/** The message decoders' answers to the inputs of {@code golden/packets.jsonl}, which other engines replay. */
class PacketCorpusTest {

    private static final String NAME = "packets";
    private static final int CHANGES_PER_SAMPLE = 3;
    private static final HexFormat HEX = HexFormat.of();
    private static final Map<String, Sample> SAMPLES =
            MutatedMessagesTest.samples().stream().collect(Collectors.toMap(Sample::name, Function.identity()));

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
                Corpus.read(NAME).stream().map(PacketCorpusTest::inputOf).toList();
        this.answers = (inputs.isEmpty() ? newInputs() : inputs)
                .stream().map(PacketCorpusTest::answered).toList();
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
                        recorded.get("kind").asString(),
                        HEX.parseHex(recorded.get("in").asString()))
                .put("protocol", recorded.get("protocol").asInt())
                .put("opcode", recorded.get("opcode").asInt());
    }

    private static ObjectNode answered(ObjectNode input) {
        ObjectNode result = input.deepCopy();
        Sample sample = SAMPLES.get(input.get("kind").asString());
        Packet packet = Packet.of(
                input.get("protocol").asInt(),
                input.get("opcode").asInt(),
                HEX.parseHex(input.get("in").asString()));
        try {
            result.set("value", Canonical.of(sample.decoder().decode(packet)));
        } catch (DecodeException e) {
            result.put("error", "decode");
        }
        return result;
    }

    /** Every message of {@link MutatedMessagesTest} as it is made, then changed by {@link Mutations}. */
    private static List<ObjectNode> newInputs() {
        List<ObjectNode> inputs = new ArrayList<>();
        for (Sample sample : MutatedMessagesTest.samples()) {
            Packet packet = sample.packet();
            inputs.add(newInput(sample, packet.payload()));
            for (int seed = 1; seed <= CHANGES_PER_SAMPLE; seed++) {
                inputs.add(newInput(sample, Mutations.changed(packet.payload(), seed)));
            }
        }
        return inputs;
    }

    private static ObjectNode newInput(Sample sample, byte[] payload) {
        return Corpus.newCase(sample.name(), payload)
                .put("protocol", sample.packet().protocol())
                .put("opcode", sample.packet().opcode());
    }
}
