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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;

/** Compares with vectors from the independent Python implementation (tools/golden). */
class GoldenFileHashTest {

    @ParameterizedTest(name = "{0} bytes")
    @MethodSource("vectors")
    void matchesTheGoldenVectors(long size, Map<String, String> v) throws IOException {
        FileHasher.Result result = FileHasher.hash(new Pattern(size), size, b -> {});

        assertThat(result.ed2k().fileHash()).isEqualTo(Hash16.parseHex(v.get("md4")));
        assertThat(result.ed2k().partHashes())
                .isEqualTo(list(v.get("parts")).stream().map(Hash16::parseHex).toList());
        AichTree aich = result.aich().orElseThrow();
        assertThat(aich.masterHash()).isEqualTo(Hash20.parseBase32(v.get("aich")));
        assertThat(aich.partHashes())
                .isEqualTo(list(v.get("aichparts")).stream()
                        .map(Hash20::parseBase32)
                        .toList());
    }

    static Stream<Arguments> vectors() throws IOException {
        try (InputStream in = Objects.requireNonNull(
                        GoldenFileHashTest.class.getResourceAsStream("/golden/file-hashes.txt"));
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII))) {
            return reader
                    .lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .map(GoldenFileHashTest::fields)
                    .map(f -> Arguments.of(Long.parseLong(f.get("size")), f))
                    .toList()
                    .stream();
        }
    }

    private static Map<String, String> fields(String line) {
        Map<String, String> map = new HashMap<>();
        for (String field : line.split(" ", -1)) {
            int eq = field.indexOf('=');
            map.put(field.substring(0, eq), field.substring(eq + 1));
        }
        return map;
    }

    private static List<String> list(String value) {
        return value.equals("-") ? List.of() : Arrays.asList(value.split(":", -1));
    }

    /** The generator's data: byte i is (7 i + i / 251) mod 256. */
    private static final class Pattern implements ReadableByteChannel {
        private final long size;
        private long position;

        Pattern(long size) {
            this.size = size;
        }

        @Override
        public int read(ByteBuffer dst) {
            if (this.position == this.size) {
                return -1;
            }
            int n = (int) Math.min(dst.remaining(), this.size - this.position);
            for (int k = 0; k < n; k++) {
                long i = this.position + k;
                dst.put((byte) (7 * i + i / 251));
            }
            this.position += n;
            return n;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {}
    }
}
