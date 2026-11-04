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
package org.njmule.core.files.hash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.njmule.core.Canonical;
import org.njmule.core.Corpus;
import org.njmule.core.Mutations;
import org.njmule.core.api.Hash20;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * How files split into parts and how their AICH trees are built and proven, for the inputs of
 * {@code golden/hashes.jsonl}, which other engines replay; the hashes of the data itself are the golden vectors'.
 */
class HashCorpusTest {

    private static final String NAME = "hashes";
    private static final String LAYOUT = "ed2k-layout";
    private static final String TREE = "aich-tree";
    private static final String RECOVERY = "aich-recovery";
    private static final int CHANGES_PER_RECOVERY = 2;
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final HexFormat HEX = HexFormat.of();

    // Sizes at the edges of blocks, parts, the empty last part and 32 bits.
    private static final List<Long> LAYOUT_SIZES = List.of(
            0L,
            1L,
            (long) Ed2k.EMBLOCKSIZE,
            Ed2k.PARTSIZE - 1,
            Ed2k.PARTSIZE,
            Ed2k.PARTSIZE + 1,
            2 * Ed2k.PARTSIZE,
            2 * Ed2k.PARTSIZE + 1,
            0xFFFF_FFFFL,
            0x1_0000_0000L,
            1L << 38,
            (1L << 38) + 1);

    // Sizes whose trees split unevenly: a short last block, a short last part, a part of one block.
    private static final List<Long> TREE_SIZES = List.of(
            1L,
            (long) Ed2k.EMBLOCKSIZE,
            Ed2k.EMBLOCKSIZE + 1L,
            5L * Ed2k.EMBLOCKSIZE + 7,
            Ed2k.PARTSIZE,
            Ed2k.PARTSIZE + 1,
            Ed2k.PARTSIZE + Ed2k.EMBLOCKSIZE + 1,
            2 * Ed2k.PARTSIZE + 1000,
            3 * Ed2k.PARTSIZE);

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
                Corpus.read(NAME).stream().map(HashCorpusTest::inputOf).toList();
        this.answers = (inputs.isEmpty() ? newInputs() : inputs)
                .stream().map(HashCorpusTest::answered).toList();
        Corpus.write(NAME, this.answers);
    }

    private void thenTheAnswerIs(ObjectNode recorded) {
        assertThat(this.answer).hasToString(recorded.toString());
    }

    private void thenEveryInputHasAnAnswer() {
        assertThat(this.answers).isNotEmpty().allMatch(c -> c.has("value") || c.has("error"));
    }

    // The input fields of each kind, in their order.
    private static ObjectNode inputOf(ObjectNode recorded) {
        ObjectNode fresh = NODES.objectNode();
        String kind = recorded.get("kind").asString();
        List<String> fields = switch (kind) {
            case LAYOUT -> List.of("kind", "size");
            case TREE -> List.of("kind", "size", "blocks");
            case RECOVERY -> List.of("kind", "size", "part", "master", "in");
            default -> throw new IllegalArgumentException("Unknown kind: " + kind);
        };
        fields.forEach(field -> fresh.set(field, recorded.get(field).deepCopy()));
        return fresh;
    }

    private static ObjectNode answered(ObjectNode input) {
        ObjectNode result = input.deepCopy();
        long size = input.get("size").asLong();
        switch (input.get("kind").asString()) {
            case LAYOUT -> {
                try {
                    Ed2k.checkSize(size);
                    int parts = Ed2k.partCount(size);
                    ObjectNode layout = result.putObject("value")
                            .put("parts", parts)
                            .put("ed2kParts", Ed2k.ed2kPartCount(size))
                            .put("hashSet", Ed2k.hashSetSize(size));
                    // An empty file has no last part.
                    if (parts > 0) {
                        layout.put("lastPartLength", Ed2k.partLength(size, parts - 1));
                    }
                } catch (IllegalArgumentException e) {
                    result.put("error", "invalid");
                }
            }
            case TREE -> {
                AichTree tree = AichTree.fromBlockHashes(size, hashes(input.get("blocks")));
                result.set(
                        "value",
                        NODES.objectNode()
                                .set("master", Canonical.of(tree.masterHash()))
                                .set("parts", Canonical.of(tree.partHashes())));
            }
            case RECOVERY -> {
                Optional<List<Hash20>> proven = AichRecovery.verify(
                        size,
                        input.get("part").asInt(),
                        Hash20.of(HEX.parseHex(input.get("master").asString())),
                        HEX.parseHex(input.get("in").asString()));
                if (proven.isPresent()) {
                    result.set("value", Canonical.of(proven.get()));
                } else {
                    result.put("error", "unproven");
                }
            }
            default -> throw new IllegalArgumentException("Unknown kind: " + input.get("kind"));
        }
        return result;
    }

    /** Layouts of edge sizes, trees of random leaves, and the recovery data of their parts, each also changed. */
    private static List<ObjectNode> newInputs() {
        List<ObjectNode> inputs = new ArrayList<>();
        LAYOUT_SIZES.forEach(
                size -> inputs.add(NODES.objectNode().put("kind", LAYOUT).put("size", size)));
        for (int i = 0; i < TREE_SIZES.size(); i++) {
            long size = TREE_SIZES.get(i);
            AichTree tree = tree(size, i);
            ArrayNode blocks = NODES.arrayNode();
            tree.blockHashes().forEach(hash -> blocks.add(Canonical.of(hash)));
            inputs.add(NODES.objectNode().put("kind", TREE).put("size", size).set("blocks", blocks));
            for (int part = 0; AichRecovery.hasRecovery(size, part); part++) {
                byte[] data = AichRecovery.create(tree, part);
                inputs.add(recovery(tree, part, data));
                for (int seed = 1; seed <= CHANGES_PER_RECOVERY; seed++) {
                    inputs.add(recovery(tree, part, Mutations.changed(data, seed)));
                }
            }
        }
        return inputs;
    }

    private static ObjectNode recovery(AichTree tree, int part, byte[] data) {
        return NODES.objectNode()
                .put("kind", RECOVERY)
                .put("size", tree.size())
                .put("part", part)
                .put("master", HEX.formatHex(tree.masterHash().toByteArray()))
                .put("in", HEX.formatHex(data));
    }

    private static AichTree tree(long size, long seed) {
        Random random = new Random(seed);
        List<Hash20> leaves = new ArrayList<>();
        for (long i = 0; i < AichTree.blockCount(size); i++) {
            byte[] hash = new byte[Hash20.LENGTH];
            random.nextBytes(hash);
            leaves.add(Hash20.of(hash));
        }
        return AichTree.fromBlockHashes(size, leaves);
    }

    private static List<Hash20> hashes(JsonNode hexes) {
        List<Hash20> hashes = new ArrayList<>();
        hexes.values().forEach(hex -> hashes.add(Hash20.of(HEX.parseHex(hex.asString()))));
        return hashes;
    }
}
