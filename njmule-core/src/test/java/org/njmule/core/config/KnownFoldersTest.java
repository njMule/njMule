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

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class KnownFoldersTest {

    // What reg.exe answers, as it does on Windows 11.
    private static final String ANSWER = "\r\nHKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\"
            + "Explorer\\User Shell Folders\r\n    " + KnownFolders.DOWNLOADS
            + "    REG_EXPAND_SZ    %USERPROFILE%\\My Downloads\r\n\r\n";

    private String answer = "";
    private Map<String, String> env = Map.of();
    private Optional<String> folder;

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

    private void givenTheRegistryAnswers(String text) {
        this.answer = text;
    }

    private void givenTheVariable(String name, String value) {
        this.env = Map.of(name, value);
    }

    private void whenTheFolderIsRead() {
        this.folder =
                KnownFolders.value(this.answer, KnownFolders.DOWNLOADS).flatMap(v -> KnownFolders.expand(v, this.env));
    }

    private void thenTheFolderIs(String expected) {
        assertThat(this.folder).contains(expected);
    }

    private void thenThereIsNoFolder() {
        assertThat(this.folder).isEmpty();
    }
}
