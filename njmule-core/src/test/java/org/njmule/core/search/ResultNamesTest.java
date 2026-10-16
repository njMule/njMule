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

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.SearchResult.Name;

/** The names a file is found under, the counts each gives and the one the file shows. */
class ResultNamesTest {

    private static final Hash16 HASH = Hash16.parseHex("00112233445566778899aabbccddeeff");

    private SearchResult known;
    private SearchResult merged;

    @Test
    void shouldAddUpTheSourcesOfANameFoundAgainInAnotherCase() {
        givenTheFileFoundAs("holiday.avi", 3, 1);

        whenItIsFoundAgainAs("HOLIDAY.AVI", 2, 1, true);

        thenTheFileShows("holiday.avi", 5, 2);
        thenTheFileListsNoNames();
    }

    @Test
    void shouldKeepTheLargerCountOfANameKadNodesReportAgain() {
        givenTheFileFoundAs("holiday.avi", 3, SearchResult.UNKNOWN);

        whenItIsFoundAgainAs("holiday.avi", 2, SearchResult.UNKNOWN, false);

        thenTheFileShows("holiday.avi", 3, SearchResult.UNKNOWN);
    }

    @Test
    void shouldShowTheNameMostSourcesGiveAndListEveryName() {
        givenTheFileFoundAs("holiday.avi", 2, 0);

        whenItIsFoundAgainAs("Holiday HD.avi", 5, 1, true);

        thenTheFileShows("Holiday HD.avi", 7, 1);
        thenTheFileListsTheNames(new Name("Holiday HD.avi", 5, 1), new Name("holiday.avi", 2, 0));
    }

    @Test
    void shouldKeepShowingTheFirstOfNamesAsManySourcesGive() {
        givenTheFileFoundAs("holiday.avi", 3, 0);

        whenItIsFoundAgainAs("Holiday HD.avi", 3, 0, true);

        thenTheFileShows("holiday.avi", 6, 0);
    }

    @Test
    void shouldTakeTheTypeOfTheNameShown() {
        givenTheFileFoundAs("holiday.avi", 1, 0);

        whenItIsFoundAgainAs("holiday.exe", 4, 0, true);

        thenTheFileHasTheType("Pro");
    }

    @Test
    void shouldCountTheSourcesOfNamesBeyondTheLimitWithoutListingThem() {
        givenTheFileFoundUnderTheMostNamesKept();

        whenItIsFoundAgainAs("one too many.avi", 1, 0, true);

        thenTheFileShows("name0.avi", ResultNames.MAX_NAMES + 2, 0);
        thenTheFileListsNamesCounting(ResultNames.MAX_NAMES);
    }

    @Test
    void shouldAddNothingForACountNotTold() {
        givenTheFileFoundAs("holiday.avi", 3, SearchResult.UNKNOWN);

        whenItIsFoundAgainAs("holiday.avi", 2, 1, true);

        thenTheFileShows("holiday.avi", 5, 1);
    }

    private void givenTheFileFoundAs(String name, int sources, int complete) {
        this.known = result(name, sources, complete);
    }

    // The first name has two sources, the others one.
    private void givenTheFileFoundUnderTheMostNamesKept() {
        this.known = result("name0.avi", 2, 0);
        for (int i = 1; i < ResultNames.MAX_NAMES; i++) {
            this.known = ResultNames.foundAgain(this.known, result("name" + i + ".avi", 1, 0), true);
        }
        assertThat(this.known.names()).hasSize(ResultNames.MAX_NAMES);
    }

    private void whenItIsFoundAgainAs(String name, int sources, int complete, boolean addCounts) {
        this.merged = ResultNames.foundAgain(this.known, result(name, sources, complete), addCounts);
    }

    private void thenTheFileShows(String name, int sources, int complete) {
        assertThat(this.merged.name()).isEqualTo(name);
        assertThat(this.merged.sources()).isEqualTo(sources);
        assertThat(this.merged.completeSources()).isEqualTo(complete);
    }

    private void thenTheFileListsNoNames() {
        assertThat(this.merged.names()).isEmpty();
    }

    private void thenTheFileListsTheNames(Name... names) {
        assertThat(this.merged.names()).containsExactly(names);
    }

    private void thenTheFileListsNamesCounting(int count) {
        assertThat(this.merged.names()).hasSize(count).noneMatch(n -> n.name().equals("one too many.avi"));
    }

    private void thenTheFileHasTheType(String type) {
        assertThat(this.merged.type()).contains(type);
    }

    private static SearchResult result(String name, int sources, int complete) {
        return new SearchResult(HASH, name, 700_000_000L, sources, complete, Optional.empty());
    }
}
