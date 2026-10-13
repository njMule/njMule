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

import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;

class SearchParametersTest {

    @Test
    void theParametersBecomeAttributesTheParserTakes() {
        String expression = SearchScreen.expression(
                " ubuntu iso ",
                SearchScreen.FileType.CD_IMAGE,
                OptionalInt.of(100),
                OptionalInt.of(5000),
                OptionalInt.of(5),
                OptionalInt.of(1),
                ".iso");

        assertThat(expression)
                .isEqualTo("ubuntu iso @type=cd @size>=100m @size<=5000m @sources>=5 @complete>=1 @ext=iso");
        SearchRequest.checkExpression(expression);
        assertThat(SearchScreen.expression(
                        "x",
                        SearchScreen.FileType.ANY,
                        OptionalInt.empty(),
                        OptionalInt.empty(),
                        OptionalInt.empty(),
                        OptionalInt.empty(),
                        " "))
                .isEqualTo("x");
    }

    @Test
    void theResultFilterTakesEveryWordOfTheName() {
        SearchResult result = new SearchResult(
                Hash16.parseHex("0123456789abcdef0123456789abcdef"),
                "Ubuntu 24.04 Desktop.iso",
                1,
                1,
                1,
                Optional.empty());

        assertThat(SearchScreen.matching("")).accepts(result);
        assertThat(SearchScreen.matching("ubuntu desktop")).accepts(result);
        assertThat(SearchScreen.matching("ubuntu server")).rejects(result);
    }
}
