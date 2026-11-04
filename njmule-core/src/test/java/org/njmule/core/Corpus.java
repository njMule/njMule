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
package org.njmule.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Inputs of a decoder with the answers this engine gives them, in {@code golden/<name>.jsonl}: one JSON object per
 * line, its {@code kind} first. Another engine that replays them answers as this one does.
 */
public final class Corpus {

    private static final Path SOURCES = Path.of("src", "test", "resources", "golden");
    private static final JsonMapper MAPPER = JsonMapper.shared();

    private Corpus() {}

    /** True with {@code -Pnjmule.corpus.write=true}: the corpus tests then write this engine's answers. */
    public static boolean writing() {
        return Boolean.getBoolean("njmule.corpus.write");
    }

    /** The cases of {@code golden/<name>.jsonl}, none if it does not exist yet. */
    public static List<ObjectNode> read(String name) {
        try (InputStream in = Corpus.class.getResourceAsStream("/golden/" + name + ".jsonl")) {
            if (in == null) {
                return List.of();
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .filter(line -> !line.isBlank())
                    .map(line -> (ObjectNode) MAPPER.readTree(line))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes {@code golden/<name>.jsonl} in the module's test sources, run from the module's folder as Gradle does. */
    public static void write(String name, List<ObjectNode> cases) throws IOException {
        Files.createDirectories(SOURCES);
        Files.writeString(
                SOURCES.resolve(name + ".jsonl"),
                cases.stream().map(ObjectNode::toString).collect(Collectors.joining("\n", "", "\n")));
    }

    /** A case of {@code kind} that parses {@code text}, which must be {@link #wellFormed}. */
    public static ObjectNode newTextCase(String kind, String text) {
        if (!wellFormed(text)) {
            throw new IllegalArgumentException("A lone surrogate does not fit a corpus: " + text);
        }
        return MAPPER.createObjectNode().put("kind", kind).put("text", text);
    }

    /** True unless {@code text} holds a lone surrogate, which neither JSON in UTF-8 nor another engine's text holds. */
    public static boolean wellFormed(String text) {
        return text.codePoints().noneMatch(c -> c >= Character.MIN_SURROGATE && c <= Character.MAX_SURROGATE);
    }

    /** A case of {@code kind} that decodes {@code in}. */
    public static ObjectNode newCase(String kind, byte[] in) {
        return MAPPER.createObjectNode()
                .put("kind", kind)
                .put("in", HexFormat.of().formatHex(in));
    }
}
