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
package org.njmule.core.search;

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
import org.njmule.core.Canonical;
import org.njmule.core.Corpus;
import org.njmule.core.protocol.io.PacketWriter;
import tools.jackson.databind.node.ObjectNode;

/**
 * The search box parser's answers to the texts of {@code golden/search-expressions.jsonl}, with the search as a server
 * gets it, which other engines replay.
 */
class SearchParserCorpusTest {

    private static final String NAME = "search-expressions";
    private static final String KIND = "search";
    private static final HexFormat HEX = HexFormat.of();

    // What people type, and what they get wrong.
    private static final List<String> WRITTEN_BY_HAND = List.of(
            "ubuntu",
            "ubuntu iso",
            "\"ubuntu desktop\" iso",
            "linux iso OR ubuntu NOT beta @size>700m",
            "a AND (b OR c) NOT d",
            "-beta ubuntu",
            "@type=video matrix",
            "@ext=mkv @length>=1:30:00 film",
            "@size<1.5g @size>.5k a",
            "@sources>10 @complete>2 a",
            "ed2k://|file|a.bin|1000|0123456789ABCDEF0123456789ABCDEF|/",
            "Città NOÏR ß",
            "(a",
            "a)",
            "\"a",
            "AND",
            "a OR",
            "NOT",
            "@size>",
            "@size>huge",
            "@nothing=1 a",
            "a b c d e f g h i j k l",
            "",
            "   ");

    private ObjectNode input;
    private ObjectNode answer;
    private List<ObjectNode> answers;

    @ParameterizedTest
    @MethodSource("recordedCases")
    void shouldGiveTheRecordedAnswers(ObjectNode recorded) {
        givenTheCorpusIsChecked();
        givenTheInputOf(recorded);
        whenItIsParsed();
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

    private void whenItIsParsed() {
        this.answer = answered(this.input);
    }

    private void whenTheInputsAreAnswered() throws IOException {
        List<ObjectNode> inputs =
                Corpus.read(NAME).stream().map(SearchParserCorpusTest::inputOf).toList();
        this.answers = (inputs.isEmpty() ? newInputs() : inputs)
                .stream().map(SearchParserCorpusTest::answered).toList();
        Corpus.write(NAME, this.answers);
    }

    private void thenTheAnswerIs(ObjectNode recorded) {
        assertThat(this.answer).hasToString(recorded.toString());
    }

    private void thenEveryInputHasAnAnswer() {
        assertThat(this.answers).isNotEmpty().allMatch(c -> c.has("value") || c.has("error"));
    }

    private static ObjectNode inputOf(ObjectNode recorded) {
        return Corpus.newTextCase(
                recorded.get("kind").asString(), recorded.get("text").asString());
    }

    // The search, and the bytes a server that takes 64-bit sizes gets for it; or the reason it is refused.
    private static ObjectNode answered(ObjectNode input) {
        ObjectNode result = input.deepCopy();
        try {
            SearchExpr search = SearchParser.parse(input.get("text").asString());
            PacketWriter wire = new PacketWriter();
            SearchTreeCodec.write(wire, search, true);
            result.set("value", Canonical.of(search));
            result.put("wire", HEX.formatHex(wire.toByteArray()));
        } catch (SearchSyntaxException e) {
            result.put("error", e.reason().name());
        }
        return result;
    }

    /** Searches written by hand and joined from the fragments of {@link SearchParserTest}. */
    private static List<ObjectNode> newInputs() {
        List<ObjectNode> inputs = new ArrayList<>();
        WRITTEN_BY_HAND.forEach(text -> inputs.add(Corpus.newTextCase(KIND, text)));
        new SearchParserTest()
                .fragments()
                .sampleStream()
                .limit(60)
                .forEach(parts -> inputs.add(Corpus.newTextCase(KIND, String.join("", parts))));
        return inputs;
    }
}
