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
package org.njmule.core.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.SpamMarks;

class SpamFilterTest {

    @TempDir
    Path dir;

    @Test
    void marksByHashAndNameAndKeepsThemInTheEmuleFile() throws Exception {
        Path file = this.dir.resolve(SpamFilter.FILE_NAME);
        SearchResult fake = result("ab", "Movie 2026 FULL.avi");
        SearchResult renamed = result("cd", "movie 2026 full.AVI");
        SearchResult other = result("ef", "Holiday.jpg");

        SpamFilter filter = SpamFilter.load(file);
        assertThat(filter.marks().isSpam(fake)).isFalse();
        filter.mark(fake.hash(), fake.name(), true);
        filter.save(file);

        SpamMarks marks = SpamFilter.load(file).marks();
        assertThat(marks.isSpam(fake)).isTrue();
        assertThat(marks.isSpam(renamed)).as("the same name, in any case").isTrue();
        assertThat(marks.isSpam(other)).isFalse();

        SpamFilter again = SpamFilter.load(file);
        again.mark(renamed.hash(), renamed.name(), false);
        assertThat(again.marks().isSpam(renamed)).isFalse();
        assertThat(again.marks().isSpam(fake)).as("still by its own hash").isTrue();
        assertThat(again.marks().names()).as("the name is cleared").isEmpty();
    }

    private static SearchResult result(String hex, String name) {
        return new SearchResult(Hash16.parseHex(hex.repeat(16)), name, 1000, 1, 0, Optional.empty());
    }
}
