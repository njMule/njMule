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
package org.njmule.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PathSanitizerTest {

    @TempDir
    Path dir;

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "movie.mkv|movie.mkv",
                "../../etc/passwd|.._.._etc_passwd",
                "a\\b/c:d*e?f\"g<h>i|a_b_c_d_e_f_g_h_i",
                "con|_con",
                "NUL.txt|_NUL.txt",
                "com1.tar.gz|_com1.tar.gz",
                "console.txt|console.txt",
                "name. . .|name",
                "...|_",
                "'  '|_"
            })
    void makesNamesSafe(String raw, String expected) {
        assertThat(PathSanitizer.fileName(raw)).isEqualTo(expected);
    }

    @Test
    void replacesControlCharactersAndKeepsUnicode() {
        assertThat(PathSanitizer.fileName("a\u0000b\nc\u007Fé✓.txt")).isEqualTo("a_b_c_é✓.txt");
    }

    @Test
    void truncatesLongNamesButKeepsTheExtension() {
        String name = PathSanitizer.fileName("è".repeat(300) + ".mkv");

        assertThat(name).endsWith(".mkv");
        assertThat(name.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(PathSanitizer.MAX_NAME_BYTES);
    }

    @Test
    void picksAFreeNameInsideTheDirectory() throws Exception {
        Files.createFile(this.dir.resolve("file.iso"));
        Files.createFile(this.dir.resolve("file (2).iso"));

        assertThat(PathSanitizer.unique(this.dir, "file.iso")).isEqualTo(this.dir.resolve("file (3).iso"));
        assertThat(PathSanitizer.resolveInside(this.dir, "../x").getParent())
                .isEqualTo(this.dir.toAbsolutePath().normalize());
    }

    @Property
    void anyNameStaysInsideTheDirectory(@ForAll String name) throws Exception {
        // Nothing is created: resolving only computes the path.
        Path downloads = Path.of("downloads").toAbsolutePath().normalize();
        Path resolved = PathSanitizer.resolveInside(downloads, name);

        assertThat(resolved.getParent()).isEqualTo(downloads);
        assertThat(resolved.getFileName().toString()).isNotBlank();
    }
}
