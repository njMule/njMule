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
package org.njmule.gui.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The marks that give each word of right-to-left text a run of its own, so that Pango never splits one to wrap. */
class WordMarksTest {

    private static final String MARK = "‎";

    private String text;
    private String marked;

    @Test
    void shouldMarkEachWordAfterASpaceInRightToLeftText() {
        givenTheText("كلمات بعينها: ضعها");

        whenItsWordsAreMarked();

        thenItReads("كلمات " + MARK + "بعينها: " + MARK + "ضعها");
    }

    @Test
    void shouldLeaveLeftToRightTextAsItIs() {
        givenTheText("Some words of the name");

        whenItsWordsAreMarked();

        thenItReads("Some words of the name");
    }

    @Test
    void shouldMarkEachWordOnlyOnce() {
        givenTheText("כמה " + MARK + "מילים  נוספות");

        whenItsWordsAreMarked();

        thenItReads("כמה " + MARK + "מילים  " + MARK + "נוספות");
    }

    private void givenTheText(String words) {
        this.text = words;
    }

    private void whenItsWordsAreMarked() {
        this.marked = Fx.wordsMarked(this.text);
    }

    private void thenItReads(String words) {
        assertThat(this.marked).isEqualTo(words);
    }
}
