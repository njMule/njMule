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
package org.njmule.gui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NjMuleFxAppTest {

    private NjMuleFxApp app;

    // The start opens no desktop app of its own, so the toolkit is not needed to tell why.
    @ParameterizedTest
    @CsvSource({
        "true, 'already runs without a window with this configuration, and the links went to it.'",
        "false, 'already runs without a window with this configuration. Use its web interface'"
    })
    void shouldTellInsteadOfOpeningWhileANjmuleWithoutAWindowRuns(boolean links, String told) throws Exception {
        givenANjmuleWithoutAWindowTook(links);

        whenTheAppStarts();

        thenItTells(told);
    }

    private void givenANjmuleWithoutAWindowTook(boolean links) {
        NjMuleFxApp.headlessRuns(links);
    }

    private void whenTheAppStarts() throws Exception {
        this.app = new NjMuleFxApp();
        this.app.init();
    }

    private void thenItTells(String part) {
        assertThat(this.app.failure()).hasMessageContaining(part);
    }
}
