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
package org.njmule.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DownloadPriorityTest {

    private DownloadPriority priority;
    private DownloadPriority effective;

    @ParameterizedTest
    @CsvSource({"0, HIGH", "20, HIGH", "21, NORMAL", "100, NORMAL", "101, LOW"})
    void shouldPutRareFilesFirstWhenAutomatic(int sources, DownloadPriority expected) {
        givenThePriority(DownloadPriority.AUTO);

        whenItActsForAFileWithSources(sources);

        thenItActsWith(expected);
    }

    @Test
    void shouldKeepAChosenPriorityWhateverTheSources() {
        givenThePriority(DownloadPriority.LOW);

        whenItActsForAFileWithSources(0);

        thenItActsWith(DownloadPriority.LOW);
    }

    private void givenThePriority(DownloadPriority chosen) {
        this.priority = chosen;
    }

    private void whenItActsForAFileWithSources(int sources) {
        this.effective = this.priority.effective(sources);
    }

    private void thenItActsWith(DownloadPriority expected) {
        assertThat(this.effective).isEqualTo(expected);
    }
}
