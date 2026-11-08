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
package org.njmule.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The files of the interface: those it has are kept once read, and the names it has not leave nothing behind. */
class WebAppTest {

    private final WebApp app = new WebApp();
    private final List<Optional<WebApp.File>> answers = new ArrayList<>();

    // Before the login anyone who reaches the port may ask: a name kept for each would let them fill the memory.
    @Test
    void shouldKeepNothingOfTheFilesItHasNot() {
        whenFilesItHasNotAreAsked(1000);

        thenNoneIsFound();
        thenItKeepsFiles(0);
    }

    @Test
    void shouldKeepAFileItHasOnceRead() {
        whenTheFileIsAsked("/i18n/en.json");

        thenItIsFound();
        thenItKeepsFiles(1);
    }

    private void whenFilesItHasNotAreAsked(int count) {
        for (int i = 0; i < count; i++) {
            this.answers.add(this.app.file("/assets/missing-" + i + ".js"));
        }
    }

    private void whenTheFileIsAsked(String path) {
        this.answers.add(this.app.file(path));
    }

    private void thenNoneIsFound() {
        assertThat(this.answers).isNotEmpty().allMatch(Optional::isEmpty);
    }

    private void thenItIsFound() {
        assertThat(this.answers)
                .singleElement()
                .satisfies(found -> assertThat(found).isPresent());
    }

    private void thenItKeepsFiles(int count) {
        assertThat(this.app.keptFiles()).isEqualTo(count);
    }
}
