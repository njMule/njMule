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
package org.njmule.core.kad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.njmule.core.Corpus;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The keywords Kad publishes a name under and their ids, and the network's lower case they rest on, for the texts of
 * {@code golden/kad-keywords.jsonl}, which other engines replay.
 */
class KeywordsCorpusTest {

    private static final String NAME = "kad-keywords";
    private static final String KEYWORDS = "keywords";
    private static final String LOWER_CASE = "lower-case";
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final HexFormat HEX = HexFormat.of();

    // Names whose lower case or byte count a port gets wrong first.
    private static final List<String> WRITTEN_BY_HAND = List.of(
            "Ubuntu 24.04 Desktop amd64.iso",
            "The.Matrix.1999.1080p.mkv",
            "İstanbul ISTANBUL ıi",
            "Straße STRASSE ß ẞ",
            "ΣΊΣΥΦΟΣ σίσυφος ς",
            "Æble Øl Ål",
            "Ɫ Ɽ ⱥ ⱦ Ⱥ Ⱦ",
            "Ǆ ǅ ǆ ǈ ǋ",
            "ＦＵＬＬＷＩＤＴＨ ｆｕｌｌｗｉｄｔｈ",
            "Кино ФИЛЬМ",
            "日本語のファイル 中文 한국어",
            "music 🎵 song 😀.mp3",
            "a-b_c.d,e;f:g!h?i(j)k[l]m{n}o<p>q/r\\s\"t",
            "ab abc ABC abc.abc",
            "...",
            "");

    private ObjectNode input;
    private ObjectNode answer;
    private List<ObjectNode> answers;

    @ParameterizedTest
    @MethodSource("recordedCases")
    void shouldGiveTheRecordedAnswers(ObjectNode recorded) {
        givenTheCorpusIsChecked();
        givenTheInputOf(recorded);
        whenItIsAnswered();
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

    private void whenItIsAnswered() {
        this.answer = answered(this.input);
    }

    private void whenTheInputsAreAnswered() throws IOException {
        List<ObjectNode> inputs =
                Corpus.read(NAME).stream().map(KeywordsCorpusTest::inputOf).toList();
        this.answers = (inputs.isEmpty() ? newInputs() : inputs)
                .stream().map(KeywordsCorpusTest::answered).toList();
        Corpus.write(NAME, this.answers);
    }

    private void thenTheAnswerIs(ObjectNode recorded) {
        assertThat(this.answer).hasToString(recorded.toString());
    }

    private void thenEveryInputHasAnAnswer() {
        assertThat(this.answers).isNotEmpty().allMatch(c -> c.has("value"));
    }

    private static ObjectNode inputOf(ObjectNode recorded) {
        return recorded.get("kind").asString().equals(LOWER_CASE)
                ? NODES.objectNode().put("kind", LOWER_CASE)
                : Corpus.newTextCase(KEYWORDS, recorded.get("text").asString());
    }

    private static ObjectNode answered(ObjectNode input) {
        ObjectNode result = input.deepCopy();
        ArrayNode value = result.putArray("value");
        if (input.get("kind").asString().equals(LOWER_CASE)) {
            // Every UTF-16 unit the network's lower case changes, as [unit, lowered].
            for (int c = 0; c <= Character.MAX_VALUE; c++) {
                char lowered = KadLowerCase.toLowerCase((char) c);
                if (lowered != c) {
                    value.add(NODES.arrayNode().add(c).add(lowered));
                }
            }
        } else {
            for (String word : Keywords.of(input.get("text").asString())) {
                value.add(NODES.objectNode()
                        .put("word", word)
                        .put("id", HEX.formatHex(Keywords.id(word).toBigEndian())));
            }
        }
        return result;
    }

    /** The lower case table, names written by hand and names drawn from the generators of {@link KeywordsTest}. */
    private static List<ObjectNode> newInputs() {
        List<ObjectNode> inputs = new ArrayList<>();
        inputs.add(NODES.objectNode().put("kind", LOWER_CASE));
        WRITTEN_BY_HAND.forEach(text -> inputs.add(Corpus.newTextCase(KEYWORDS, text)));
        new KeywordsTest()
                .namesKeepingTheirBytes()
                .sampleStream()
                .filter(text -> text.length() <= 60 && Corpus.wellFormed(text))
                .limit(40)
                .forEach(text -> inputs.add(Corpus.newTextCase(KEYWORDS, text)));
        return inputs;
    }
}
