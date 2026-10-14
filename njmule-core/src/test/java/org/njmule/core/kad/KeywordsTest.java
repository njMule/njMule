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
package org.njmule.core.kad;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

class KeywordsTest {

    @Test
    void textWithoutWordsHasNoKeyword() {
        assertThat(Keywords.of("")).isEmpty();
        assertThat(Keywords.of(Keywords.DELIMITERS)).isEmpty();
        assertThat(Keywords.of("ab cd")).as("words under three bytes").isEmpty();
    }

    @Test
    void everyDelimiterSplitsAndOtherSignsDoNot() {
        for (char d : Keywords.DELIMITERS.toCharArray()) {
            assertThat(Keywords.of("alpha" + d + "beta" + d)).as("%s", d).containsExactly("alpha", "beta");
        }
        assertThat(Keywords.of("rock&roll mp3+ost it's #one.")).containsExactly("rock&roll", "mp3+ost", "it's", "#one");
    }

    @Test
    void shortWordsCountTheirBytesNotTheirCharacters() {
        assertThat(Keywords.of("日 x")).as("one CJK character is three bytes").containsExactly("日");
        assertThat(Keywords.of("éa")).as("two characters, three bytes").containsExactly("éa");
        assertThat(Keywords.of("abc éa")).as("not taken for an extension").containsExactly("abc", "éa");
    }

    @Test
    void theExtensionRuleLooksAtTheLastPartOnly() {
        assertThat(Keywords.of("abc def abc"))
                .as("the repeated last word moves to the end, then goes as the extension")
                .containsExactly("def");
        assertThat(Keywords.of("Movie MOVIE movie")).containsExactly("movie");
        assertThat(Keywords.of("film.mkv")).containsExactly("film");
        assertThat(Keywords.of("film.mpeg")).as("four letters stay").containsExactly("film", "mpeg");
        assertThat(Keywords.of("film.avi.")).containsExactly("film", "avi");
    }

    @Test
    void theNetworkLowerCaseIsKeptAsItIs() {
        assertThat(Keywords.of("STRASSE Straße")).containsExactly("strasse", "straße");
        assertThat(Keywords.of("İSTANBUL")).as("dotted capital I unchanged").containsExactly("İstanbul");
        assertThat(Keywords.of("Ünïcode")).containsExactly("ünïcode");
        assertThat(Keywords.of("école")).as("combining marks are not composed").containsExactly("école");
        assertThat(Keywords.of("🎵music")).containsExactly("🎵music");
    }

    @Test
    void theLowerCaseTableIsWholeAndStable() {
        int mapped = 0;
        for (int c = 0; c <= 0xFFFF; c++) {
            char lower = KadLowerCase.toLowerCase((char) c);
            if (lower != c) {
                mapped++;
            }
            assertThat(KadLowerCase.toLowerCase(lower)).as("U+%04X", c).isEqualTo(lower);
        }
        assertThat(mapped).as("every unit of the table is found").isEqualTo(873);
        for (char c = 'A'; c <= 'Z'; c++) {
            assertThat(KadLowerCase.toLowerCase(c)).isEqualTo(Character.toLowerCase(c));
        }
    }

    @Property
    void keywordsAreLowerCaseLongEnoughAndDistinct(@ForAll String text) {
        var words = Keywords.of(text);

        assertThat(words).doesNotHaveDuplicates();
        for (String w : words) {
            assertThat(w.getBytes(StandardCharsets.UTF_8).length).isGreaterThanOrEqualTo(3);
            assertThat(w).isEqualTo(KadLowerCase.toLowerCase(w));
            assertThat(w.chars()).noneMatch(c -> Keywords.DELIMITERS.indexOf(c) >= 0);
        }
        assertThat(Keywords.of(String.join(" ", words) + " ")).containsExactlyElementsOf(words);
    }
}
