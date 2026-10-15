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
package org.njmule.gui.screens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;

class SearchParametersTest {

    private String words;
    private SearchScreen.FileType type = SearchScreen.FileType.ANY;
    private OptionalInt minimumMb = OptionalInt.empty();
    private OptionalInt maximumMb = OptionalInt.empty();
    private OptionalInt sources = OptionalInt.empty();
    private OptionalInt complete = OptionalInt.empty();
    private String extension = "";
    private String expression;
    private SearchResult result;
    private Predicate<SearchResult> filter;

    @Test
    void shouldTurnTheParametersIntoAttributesTheParserTakes() {
        givenTheWords(" ubuntu iso ");
        givenTheType(SearchScreen.FileType.CD_IMAGE);
        givenTheSizeInMbBetween(100, 5000);
        givenAtLeastSources(5);
        givenAtLeastCompleteSources(1);
        givenTheExtension(".iso");

        whenTheExpressionIsMade();

        thenTheExpressionIs("ubuntu iso @type=cd @size>=100m @size<=5000m @sources>=5 @complete>=1 @ext=iso");
        thenTheParserTakesTheExpression();
    }

    @Test
    void shouldLeaveTheWordsAloneWithoutParameters() {
        givenTheWords("x");
        givenTheExtension(" ");

        whenTheExpressionIsMade();

        thenTheExpressionIs("x");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ubuntu desktop"})
    void shouldKeepAResultWhoseNameHasEveryWordOfTheFilter(String filterText) {
        givenAResultNamed("Ubuntu 24.04 Desktop.iso");

        whenTheResultsAreFilteredBy(filterText);

        thenTheResultIsKept();
    }

    @Test
    void shouldDropAResultWhoseNameMissesAWordOfTheFilter() {
        givenAResultNamed("Ubuntu 24.04 Desktop.iso");

        whenTheResultsAreFilteredBy("ubuntu server");

        thenTheResultIsDropped();
    }

    private void givenTheWords(String text) {
        this.words = text;
    }

    private void givenTheType(SearchScreen.FileType fileType) {
        this.type = fileType;
    }

    private void givenTheSizeInMbBetween(int minimum, int maximum) {
        this.minimumMb = OptionalInt.of(minimum);
        this.maximumMb = OptionalInt.of(maximum);
    }

    private void givenAtLeastSources(int count) {
        this.sources = OptionalInt.of(count);
    }

    private void givenAtLeastCompleteSources(int count) {
        this.complete = OptionalInt.of(count);
    }

    private void givenTheExtension(String text) {
        this.extension = text;
    }

    private void givenAResultNamed(String name) {
        this.result =
                new SearchResult(Hash16.parseHex("0123456789abcdef0123456789abcdef"), name, 1, 1, 1, Optional.empty());
    }

    private void whenTheExpressionIsMade() {
        this.expression = SearchScreen.expression(
                this.words, this.type, this.minimumMb, this.maximumMb, this.sources, this.complete, this.extension);
    }

    private void whenTheResultsAreFilteredBy(String text) {
        this.filter = SearchScreen.matching(text);
    }

    private void thenTheExpressionIs(String expected) {
        assertThat(this.expression).isEqualTo(expected);
    }

    private void thenTheParserTakesTheExpression() {
        assertThatCode(() -> SearchRequest.checkExpression(this.expression)).doesNotThrowAnyException();
    }

    private void thenTheResultIsKept() {
        assertThat(this.filter).accepts(this.result);
    }

    private void thenTheResultIsDropped() {
        assertThat(this.filter).rejects(this.result);
    }
}
