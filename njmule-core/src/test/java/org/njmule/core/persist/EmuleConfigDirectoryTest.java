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
package org.njmule.core.persist;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Finding the configuration folder of the eMule of this computer's user. */
class EmuleConfigDirectoryTest {

    @TempDir
    Path dir;

    private final Map<String, String> env = new HashMap<>();
    private Optional<Path> found;

    @Test
    void shouldFindTheProfileInTheLocalApplicationData() throws IOException {
        givenAnEmuleProfileIn("local");
        givenTheEnvironmentNames("LOCALAPPDATA", "local");
        givenTheEnvironmentNames("APPDATA", "roaming");

        whenTheProfileIsLookedFor();

        thenTheProfileFoundIsIn("local");
    }

    @Test
    void shouldFindTheProfileInTheRoamingApplicationDataWhenTheLocalHasNone() throws IOException {
        givenAnEmuleProfileIn("roaming");
        givenTheEnvironmentNames("LOCALAPPDATA", "local");
        givenTheEnvironmentNames("APPDATA", "roaming");

        whenTheProfileIsLookedFor();

        thenTheProfileFoundIsIn("roaming");
    }

    @Test
    void shouldFindNoProfileWhereThereIsNone() {
        givenTheEnvironmentNames("LOCALAPPDATA", "local");

        whenTheProfileIsLookedFor();

        thenNoProfileIsFound();
    }

    @Test
    void shouldFindNoProfileWithoutTheApplicationData() {
        whenTheProfileIsLookedFor();

        thenNoProfileIsFound();
    }

    private void givenAnEmuleProfileIn(String base) throws IOException {
        Files.createDirectories(this.dir.resolve(base).resolve("eMule").resolve("config"));
    }

    private void givenTheEnvironmentNames(String variable, String base) {
        this.env.put(variable, this.dir.resolve(base).toString());
    }

    private void whenTheProfileIsLookedFor() {
        this.found = EmuleImporter.findConfigDirectory(this.env);
    }

    private void thenTheProfileFoundIsIn(String base) {
        assertThat(this.found).contains(this.dir.resolve(base).resolve("eMule").resolve("config"));
    }

    private void thenNoProfileIsFound() {
        assertThat(this.found).isEmpty();
    }
}
