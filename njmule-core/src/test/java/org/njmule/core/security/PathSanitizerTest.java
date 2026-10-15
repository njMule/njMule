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
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class PathSanitizerTest {

    @TempDir
    Path dir;

    private final List<Path> reserved = new ArrayList<>();
    private String reservedName;
    private String sanitized;
    private Path directory;
    private Path path;

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
    void shouldMakeNamesSafe(String raw, String expected) {
        whenTheNameIsSanitized(raw);

        thenTheNameIs(expected);
    }

    @Test
    void shouldReplaceControlCharactersAndKeepUnicode() {
        whenTheNameIsSanitized("a\u0000b\nc\u007Fé✓.txt");

        thenTheNameIs("a_b_c_é✓.txt");
    }

    @Test
    void shouldTruncateLongNamesButKeepTheExtension() {
        whenTheNameIsSanitized("è".repeat(300) + ".mkv");

        thenTheNameEndsWith(".mkv");
        thenTheNameFitsTheLimit();
    }

    @Test
    void shouldPickAFreeNameInsideTheDirectory() throws IOException {
        givenTheFiles("file.iso", "file (2).iso");

        whenAFreeNameIsPickedFor("file.iso");

        thenThePathIs("file (3).iso");
    }

    @Test
    void shouldResolveANameInsideTheDirectory() throws IOException {
        whenTheNameIsResolvedInsideTheDirectory("../x");

        thenThePathIsDirectlyInsideTheDirectory();
    }

    @ParameterizedTest
    @MethodSource("namesEndingInDotsOrSpacesOnceCut")
    void shouldNeverLeaveTrailingDotsOrSpacesWhenCutting(String raw, String expected) {
        whenTheNameIsSanitized(raw);

        thenTheNameIs(expected);
    }

    @ParameterizedTest
    @MethodSource("reservedNamesOnceCut")
    void shouldCheckTheCutNameForReservedNames(String raw, String expected) {
        whenTheNameIsSanitized(raw);

        thenTheNameIs(expected);
    }

    @Test
    void shouldEscapeALongReservedNameAndStillCutIt() {
        whenTheNameIsSanitized("NUL." + "x".repeat(300));

        thenTheNameStartsWith("_NUL.");
        thenTheNameFitsTheLimit();
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "COM¹.txt|_COM¹.txt",
                "lpt³|_lpt³",
                "COM0.log|_COM0.log",
                "lpt0|_lpt0",
                "CONIN$|_CONIN$",
                "conout$.txt|_conout$.txt",
                "CoN .txt|_CoN .txt",
                "con.|_con",
                "COM10|COM10",
                "LPT10.txt|LPT10.txt"
            })
    void shouldEscapeReservedDeviceNames(String raw, String expected) {
        whenTheNameIsSanitized(raw);

        thenTheNameIs(expected);
    }

    @ParameterizedTest
    @MethodSource("longNames")
    void shouldReserveDistinctNumberedNamesForLongNames(String longName) throws IOException {
        whenTheNameIsReservedTimes(longName, 12);

        thenTheReservedPathsAreDistinct();
        thenEveryReservedNameFitsTheLimit();
    }

    @ParameterizedTest
    @MethodSource("longNames")
    void shouldPickTheNumberAfterTheReservedNamesOfALongName(String longName) throws IOException {
        givenTheNameReservedTimes(longName, 12);

        whenAFreeNameIsPickedFor(longName);

        thenThePathIsNoneOfTheReserved();
        thenTheFileNameContains(" (13)");
    }

    @Property
    void shouldMakeNamesSafeShortAndStable(@ForAll String raw) {
        whenTheNameIsSanitized(raw);

        thenTheNameIsNotEmpty();
        thenTheNameFitsTheLimit();
        thenTheNameHasNoForbiddenCharacter();
        thenTheNameEndsNeitherWithADotNorWithASpace();
        thenSanitizingTheNameAgainChangesNothing();
    }

    @Property
    void shouldKeepAnyNameInsideTheDirectory(@ForAll String raw) throws IOException {
        whenTheNameIsResolvedInsideTheDownloads(raw);

        thenThePathIsDirectlyInsideTheDirectory();
        thenTheFileNameIsNotBlank();
    }

    static Stream<Arguments> namesEndingInDotsOrSpacesOnceCut() {
        return Stream.of(
                arguments("a" + ".".repeat(300) + "b".repeat(20), "a"),
                arguments("a" + " ".repeat(300) + "b".repeat(20), "a"),
                arguments(".".repeat(300) + "b".repeat(20), "_"),
                arguments("a" + Character.toString(0x3000) + ".", "a"),
                arguments("a." + Character.toString(0x3000), "a"));
    }

    static Stream<Arguments> reservedNamesOnceCut() {
        return Stream.of(
                arguments("CON" + " ".repeat(300) + "x", "_CON"),
                arguments("AUX" + " ".repeat(200) + "x.mkv", "_AUX.mkv"));
    }

    static Stream<String> longNames() {
        return Stream.of("x".repeat(250) + ".mkv", "y".repeat(300), "z".repeat(197) + ".a");
    }

    private void givenTheFiles(String... names) throws IOException {
        for (String file : names) {
            Files.createFile(this.dir.resolve(file));
        }
    }

    private void givenTheNameReservedTimes(String raw, int times) throws IOException {
        reserveTimes(raw, times);
    }

    private void whenTheNameIsSanitized(String raw) {
        this.sanitized = PathSanitizer.fileName(raw);
    }

    private void whenAFreeNameIsPickedFor(String raw) throws IOException {
        this.path = PathSanitizer.unique(this.dir, raw);
    }

    private void whenTheNameIsResolvedInsideTheDirectory(String raw) throws IOException {
        this.directory = this.dir.toAbsolutePath().normalize();
        this.path = PathSanitizer.resolveInside(this.dir, raw);
    }

    private void whenTheNameIsResolvedInsideTheDownloads(String raw) throws IOException {
        // Nothing is created: resolving only computes the path.
        this.directory = Path.of("downloads").toAbsolutePath().normalize();
        this.path = PathSanitizer.resolveInside(this.directory, raw);
    }

    private void whenTheNameIsReservedTimes(String raw, int times) throws IOException {
        reserveTimes(raw, times);
    }

    private void thenTheNameIs(String expected) {
        assertThat(this.sanitized).isEqualTo(expected);
    }

    private void thenTheNameEndsWith(String end) {
        assertThat(this.sanitized).endsWith(end);
    }

    private void thenTheNameStartsWith(String start) {
        assertThat(this.sanitized).startsWith(start);
    }

    private void thenTheNameFitsTheLimit() {
        assertThat(this.sanitized.getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(PathSanitizer.MAX_NAME_BYTES);
    }

    private void thenTheNameIsNotEmpty() {
        assertThat(this.sanitized).isNotEmpty();
    }

    private void thenTheNameHasNoForbiddenCharacter() {
        assertThat(this.sanitized.chars()).noneMatch(c -> c < 0x20 || c == 0x7F || "\\/:*?\"<>|".indexOf(c) >= 0);
    }

    private void thenTheNameEndsNeitherWithADotNorWithASpace() {
        assertThat(this.sanitized).doesNotEndWith(".").doesNotEndWith(" ");
    }

    private void thenSanitizingTheNameAgainChangesNothing() {
        assertThat(PathSanitizer.fileName(this.sanitized)).isEqualTo(this.sanitized);
    }

    private void thenThePathIs(String fileName) {
        assertThat(this.path).isEqualTo(this.dir.resolve(fileName));
    }

    private void thenThePathIsDirectlyInsideTheDirectory() {
        assertThat(this.path.getParent()).isEqualTo(this.directory);
    }

    private void thenTheFileNameIsNotBlank() {
        assertThat(this.path.getFileName().toString()).isNotBlank();
    }

    private void thenTheFileNameContains(String part) {
        assertThat(this.path.getFileName().toString()).contains(part);
    }

    private void thenTheReservedPathsAreDistinct() {
        Set<Path> taken = new HashSet<>();
        for (int i = 0; i < this.reserved.size(); i++) {
            assertThat(taken.add(this.reserved.get(i)))
                    .as("%s, copy %d", this.reservedName, i)
                    .isTrue();
        }
    }

    private void thenEveryReservedNameFitsTheLimit() {
        for (Path copy : this.reserved) {
            assertThat(copy.getFileName().toString().getBytes(StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(PathSanitizer.MAX_NAME_BYTES);
        }
    }

    private void thenThePathIsNoneOfTheReserved() {
        assertThat(this.reserved).doesNotContain(this.path);
    }

    private void reserveTimes(String raw, int times) throws IOException {
        this.reservedName = raw;
        for (int i = 0; i < times; i++) {
            this.reserved.add(PathSanitizer.reserve(this.dir, raw));
        }
    }
}
