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
package org.njmule.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnownFoldersTest {

    // What reg.exe answers, as it does on Windows 11.
    private static final String ANSWER = "\r\nHKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\"
            + "Explorer\\User Shell Folders\r\n    " + KnownFolders.DOWNLOADS
            + "    REG_EXPAND_SZ    %USERPROFILE%\\My Downloads\r\n\r\n";

    // The Java that runs the tests stands for reg.exe, which only Windows has.
    private static final String JAVA = ProcessHandle.current().info().command().orElseThrow();

    @TempDir
    Path dir;

    private String answer = "";
    private Map<String, String> env = Map.of();
    private Optional<String> folder;
    private Optional<Path> found;
    private List<String> command = List.of();

    @Test
    void shouldTakeTheDownloadsFolderTheRegistryNames() {
        givenTheRegistryAnswers(ANSWER);
        givenTheVariable("UserProfile", "D:\\Users\\ann");

        whenTheFolderIsRead();

        thenTheFolderIs("D:\\Users\\ann\\My Downloads");
    }

    @Test
    void shouldTakeNoFolderWhoseVariableIsUnknown() {
        givenTheRegistryAnswers(ANSWER);

        whenTheFolderIsRead();

        thenThereIsNoFolder();
    }

    @Test
    void shouldTakeNoFolderTheRegistryDoesNotName() {
        givenTheRegistryAnswers("ERROR: The system was unable to find the specified registry key or value.\r\n");

        whenTheFolderIsRead();

        thenThereIsNoFolder();
    }

    @Test
    void shouldAskTheRegOfTheSystemFolder() {
        givenTheVariable("SYSTEMROOT", "D:\\Win");

        whenTheCommandIsMade();

        thenTheProgramIs(Path.of("D:\\Win", "System32", "reg.exe"));
    }

    @Test
    void shouldAskTheRegOfTheUsualSystemFolderWhenNoneIsNamed() {
        whenTheCommandIsMade();

        thenTheProgramIs(Path.of("C:\\Windows", "System32", "reg.exe"));
    }

    @Test
    void shouldReadTheValueTheProgramAnswers() throws IOException {
        givenAProgramThatAnswers(ANSWER);

        whenTheValueIsAsked();

        thenTheFolderIs("%USERPROFILE%\\My Downloads");
    }

    @Test
    void shouldReadNothingFromAProgramThatFails() {
        givenTheProgram(JAVA, "--no-such-option");

        whenTheValueIsAsked();

        thenThereIsNoFolder();
    }

    @Test
    void shouldReadNothingWithoutTheProgram() {
        givenTheProgram(this.dir.resolve("reg.exe").toString());

        whenTheValueIsAsked();

        thenThereIsNoFolder();
    }

    @Test
    void shouldTakeAFolderThatExists() {
        whenTheFolderIsFound(this.dir.toString());

        thenTheFolderFoundIs(Optional.of(this.dir));
    }

    @Test
    void shouldTakeNoFolderThatIsGone() {
        whenTheFolderIsFound(this.dir.resolve("gone").toString());

        thenTheFolderFoundIs(Optional.empty());
    }

    @Test
    void shouldTakeNoFolderOfARelativePath() {
        whenTheFolderIsFound("Downloads");

        thenTheFolderFoundIs(Optional.empty());
    }

    @Test
    void shouldTakeNoFolderOfAPathThatCannotBe() {
        whenTheFolderIsFound("D:\\Users\u0000\\Downloads");

        thenTheFolderFoundIs(Optional.empty());
    }

    // A program of a few lines that writes the answer, run from its source.
    private void givenAProgramThatAnswers(String text) throws IOException {
        Path source = this.dir.resolve("Reg.java");
        Files.writeString(
                source,
                "class Reg { public static void main(String[] args) { System.out.print(\""
                        + text.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n") + "\"); } }");
        givenTheProgram(JAVA, source.toString());
    }

    private void givenTheProgram(String... command) {
        this.command = List.of(command);
    }

    private void givenTheRegistryAnswers(String text) {
        this.answer = text;
    }

    private void givenTheVariable(String name, String value) {
        this.env = Map.of(name, value);
    }

    private void whenTheCommandIsMade() {
        this.command = KnownFolders.command(this.env, KnownFolders.DOWNLOADS);
    }

    private void whenTheValueIsAsked() {
        this.folder = KnownFolders.query(this.command, KnownFolders.DOWNLOADS);
    }

    private void whenTheFolderIsFound(String value) {
        this.found = KnownFolders.folder(Optional.of(value), Map.of());
    }

    private void whenTheFolderIsRead() {
        this.folder =
                KnownFolders.value(this.answer, KnownFolders.DOWNLOADS).flatMap(v -> KnownFolders.expand(v, this.env));
    }

    private void thenTheFolderIs(String expected) {
        assertThat(this.folder).contains(expected);
    }

    private void thenTheProgramIs(Path program) {
        assertThat(this.command)
                .containsExactly(
                        program.toString(),
                        "query",
                        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\User Shell Folders",
                        "/v",
                        KnownFolders.DOWNLOADS);
    }

    private void thenTheFolderFoundIs(Optional<Path> expected) {
        assertThat(this.found).isEqualTo(expected);
    }

    private void thenThereIsNoFolder() {
        assertThat(this.folder).isEmpty();
    }
}
