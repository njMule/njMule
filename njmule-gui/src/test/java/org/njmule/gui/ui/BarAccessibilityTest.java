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

import java.util.List;
import javafx.scene.AccessibleAttribute;
import javafx.scene.AccessibleRole;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.njmule.core.api.PartMap;
import org.njmule.gui.FxTesting;

/** The bars are read by a screen reader as progress, with how far they are filled. */
@Timeout(60)
class BarAccessibilityTest {

    private Region bar;

    @BeforeAll
    static void toolkit() throws Exception {
        FxTesting.start();
    }

    @Test
    void shouldReadAThinBarAsProgressFilledAsShown() throws Exception {
        givenAThinBar();

        whenItIsFilledTo(0.4);

        thenItIsReadAsProgressAt(0.4);
    }

    @Test
    void shouldReadThePartsBarAsProgressDoneSoFar() throws Exception {
        givenAPartsBar();

        whenAQuarterOfTheFileIsDone();

        thenItIsReadAsProgressAt(0.25);
    }

    private void givenAThinBar() throws Exception {
        this.bar = FxTesting.onFx(ThinBar::new);
    }

    private void givenAPartsBar() throws Exception {
        this.bar = FxTesting.onFx(PartBar::new);
    }

    private void whenItIsFilledTo(double fraction) throws Exception {
        FxTesting.onFx(() -> ((ThinBar) this.bar).set(fraction, "accent"));
    }

    private void whenAQuarterOfTheFileIsDone() throws Exception {
        long size = 4 * PartMap.PART_SIZE;
        FxTesting.onFx(() -> ((PartBar) this.bar).set(new PartMap(size, List.of(), List.of(), List.of()), size / 4));
    }

    private void thenItIsReadAsProgressAt(double fraction) throws Exception {
        assertThat(FxTesting.onFx(() -> this.bar.getAccessibleRole())).isEqualTo(AccessibleRole.PROGRESS_INDICATOR);
        assertThat(FxTesting.onFx(() -> this.bar.queryAccessibleAttribute(AccessibleAttribute.VALUE)))
                .isEqualTo(fraction);
    }
}
