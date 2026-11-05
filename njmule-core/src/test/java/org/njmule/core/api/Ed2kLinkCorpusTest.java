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
package org.njmule.core.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.njmule.core.Canonical;
import org.njmule.core.Corpus;
import org.njmule.core.Mutations;
import org.njmule.core.api.Ed2kLink.FileLink;
import org.njmule.core.api.Ed2kLink.ServerLink;
import org.njmule.core.api.Ed2kLink.Source;
import tools.jackson.databind.node.ObjectNode;

/** The ed2k link parser's answers to the texts of {@code golden/ed2k-links.jsonl}, which other engines replay. */
class Ed2kLinkCorpusTest {

    private static final String NAME = "ed2k-links";
    private static final String KIND = "ed2k-link";
    private static final int CHANGES_PER_LINK = 2;
    private static final String HASH = "0123456789ABCDEF0123456789ABCDEF";
    private static final String AICH = "FZCHXAPFSLCOLJA6GFFMR5DURDLVVNBD";

    // Links as browsers and sites write them, with the mistakes they make.
    private static final List<String> WRITTEN_BY_HAND = List.of(
            "ed2k://|file|a.bin|1000|" + HASH + "|/",
            "ed2k://|file|a.bin|1000|" + HASH.toLowerCase(Locale.ROOT) + "|/",
            "ED2K://|FILE|a.bin|1000|" + HASH + "|/",
            "ed2k://|file|a.bin|1000|" + HASH + "|",
            "ed2k://|file|a.bin|1000|" + HASH,
            "ed2k://%7Cfile%7Ca.bin%7C1000%7C" + HASH + "%7C/",
            "ed2k://|file|Caf%C3%A9%20au%20lait.mkv|734003200|" + HASH + "|h=" + AICH + "|/",
            "ed2k://|file|a+b.avi|1000|" + HASH + "|p=" + HASH + ":" + HASH + "|/",
            "ed2k://|file|a.bin|1000|" + HASH + "|/|sources,1.2.3.4:4662,host.example:4242|/",
            "ed2k://|file|a.bin|1000|" + HASH + "|s=http://host.example/a.bin|/",
            "ed2k://|file|a.bin|0|" + HASH + "|/",
            "ed2k://|file|a.bin|-1|" + HASH + "|/",
            "ed2k://|file|a.bin|274877906945|" + HASH + "|/",
            "ed2k://|file||1000|" + HASH + "|/",
            "ed2k://|file|a.bin|1000|0123|/",
            "ed2k://|server|1.2.3.4|4661|/",
            "ed2k://|server|host.example|65535|/",
            "ed2k://|server|1.2.3.4|0|/",
            "ed2k://|serverlist|http://host.example/server.met|/",
            "ed2k://|nodeslist|http://host.example/nodes.dat|/",
            "ed2k://|search|ubuntu iso|/",
            "magnet:?xt=urn:ed2k:" + HASH + "&xl=1000&dn=a.bin",
            "  ed2k://|file|a.bin|1000|" + HASH + "|/  ",
            "ed2k://|file|a.bin|1000|" + HASH + "|/\n",
            "http://host.example/a.bin",
            "",
            "ed2k://|file|a.bin|1000|" + HASH + "|/|sources,[fe80::1%nothere]:4662,1.2.3.4:4662|/",
            "ed2k://|file|a%0Ab.bin|1000|" + HASH + "|/",
            "ed2k://|file|a.bin|1000|" + HASH + "|s=HTTPS://host.example/a.bin|/",
            "ed2k://|file|a.bin|1000|" + HASH + "||/");

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
                Corpus.read(NAME).stream().map(Ed2kLinkCorpusTest::inputOf).toList();
        this.answers = (inputs.isEmpty() ? newInputs() : inputs)
                .stream().map(Ed2kLinkCorpusTest::answered).toList();
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

    private static ObjectNode answered(ObjectNode input) {
        ObjectNode result = input.deepCopy();
        Optional<Ed2kLink> link = Ed2kLink.tryParse(input.get("text").asString());
        if (link.isPresent()) {
            result.set("value", Canonical.of(link.get()));
        } else {
            result.put("error", "invalid");
        }
        return result;
    }

    /** Links written by hand, links the codec writes from generated names and hosts, each also changed. */
    private static List<ObjectNode> newInputs() {
        Ed2kLinkTest generators = new Ed2kLinkTest();
        List<String> links = new ArrayList<>(WRITTEN_BY_HAND);
        generators
                .anyNames()
                .sampleStream()
                .limit(30)
                .forEach(name -> links.add(fileLink(name).toUri()));
        generators.hosts().sampleStream().limit(10).forEach(host -> links.add(new ServerLink(host, 4661).toUri()));
        List<ObjectNode> inputs = new ArrayList<>();
        for (String link : links) {
            inputs.add(Corpus.newTextCase(KIND, link));
            for (int seed = 1; seed <= CHANGES_PER_LINK && !link.isEmpty(); seed++) {
                String changed = new String(Mutations.changed(link.getBytes(UTF_8), seed), UTF_8);
                if (Corpus.wellFormed(changed)) {
                    inputs.add(Corpus.newTextCase(KIND, changed));
                }
            }
        }
        return inputs;
    }

    private static FileLink fileLink(String name) {
        return new FileLink(
                name,
                734_003_200,
                Hash16.parseHex(HASH),
                Optional.of(Hash20.parseBase32(AICH)),
                List.of(),
                List.of(new Source("1.2.3.4", 4662)),
                List.of());
    }
}
