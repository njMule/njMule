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

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javafx.scene.Node;
import javafx.scene.control.Label;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

/** The search parameters keep their labels whole, in longer languages and narrow windows too. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SearchParametersLayoutTest {

    @TempDir
    Path dir;

    private TestGui test;
    private Node view;

    @AfterEach
    void close() throws Exception {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldShowTheFrenchLabelsWholeInANarrowWindow() throws Exception {
        this.givenTheSearchScreenIn(Locale.FRENCH);

        this.whenTheWindowIsNarrowedTo(640);

        this.thenNoParameterLabelIsCut();
    }

    private void givenTheSearchScreenIn(Locale language) throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"), language);
        SearchScreen screen = FxTesting.onFx(() -> new SearchScreen(this.test.gui));
        this.view = FxTesting.onFx(screen::view);
        this.test.show(this.view);
    }

    private void whenTheWindowIsNarrowedTo(double width) throws Exception {
        FxTesting.onFx(() -> {
            this.view.getScene().getWindow().setWidth(width);
            this.view.getScene().getRoot().applyCss();
            this.view.getScene().getRoot().layout();
        });
    }

    private void thenNoParameterLabelIsCut() throws Exception {
        List<double[]> widths = FxTesting.onFx(() -> this.view.lookupAll(".search-parameters .label").stream()
                .map(n -> (Label) n)
                .map(l -> new double[] {l.getWidth(), l.prefWidth(-1)})
                .toList());
        assertThat(widths).hasSize(6);
        assertThat(widths).allSatisfy(w -> assertThat(w[0]).isGreaterThanOrEqualTo(w[1] - 0.5));
    }
}
