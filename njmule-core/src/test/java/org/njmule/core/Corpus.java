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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import java.util.stream.Collectors;

/**
 * Inputs of a decoder with the answers this engine gives them, in {@code golden/<name>.txt}: one case per line, its
 * kind then {@code key=value} fields. Another engine that replays them answers as this one does.
 */
public final class Corpus {

    private static final Path SOURCES = Path.of("src", "test", "resources", "golden");

    private Corpus() {}

    /** True with {@code -Pnjmule.corpus.write=true}: the corpus tests then write this engine's answers. */
    public static boolean writing() {
        return Boolean.getBoolean("njmule.corpus.write");
    }

    /** The cases of {@code golden/<name>.txt}, none if it does not exist yet. */
    public static List<Case> read(String name) {
        try (InputStream in = Corpus.class.getResourceAsStream("/golden/" + name + ".txt")) {
            if (in == null) {
                return List.of();
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .map(Case::parse)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes {@code golden/<name>.txt} in the module's test sources, run from the module's folder as Gradle does. */
    public static void write(String name, String header, List<Case> cases) throws IOException {
        List<String> lines = new ArrayList<>();
        header.lines().map(line -> "# " + line).forEach(lines::add);
        cases.stream().map(Case::toString).forEach(lines::add);
        Files.createDirectories(SOURCES);
        Files.writeString(SOURCES.resolve(name + ".txt"), String.join("\n", lines) + "\n");
    }

    /** One case: what it decodes, and its fields in order. */
    public record Case(String kind, SequencedMap<String, String> fields) {

        public Case {
            fields = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(fields));
        }

        public String field(String key) {
            String value = this.fields.get(key);
            if (value == null) {
                throw new IllegalArgumentException("No " + key + " in " + this);
            }
            return value;
        }

        private static Case parse(String line) {
            String[] parts = line.split(" ", -1);
            SequencedMap<String, String> fields = new LinkedHashMap<>();
            for (int i = 1; i < parts.length; i++) {
                int eq = parts[i].indexOf('=');
                fields.put(parts[i].substring(0, eq), parts[i].substring(eq + 1));
            }
            return new Case(parts[0], fields);
        }

        @Override
        public String toString() {
            return this.fields.entrySet().stream()
                    .map(field -> field.getKey() + "=" + field.getValue())
                    .collect(Collectors.joining(" ", this.kind + " ", ""));
        }
    }
}
