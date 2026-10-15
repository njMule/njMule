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

import java.nio.file.Path;
import java.util.OptionalInt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(60)
class RateFieldTest {

    @TempDir
    Path dir;

    private TestGui test;
    private RateField<Integer> field;
    private RateField<OptionalInt> optional;

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void aLimitOfAThousandKBAndMoreShowsInMB() throws Exception {
        this.givenALimit();
        this.whenShown(1536);
        this.thenItReads("1.5", RateField.Unit.MB);
        this.thenTheValueIs(1536);
    }

    @Test
    void aSmallLimitShowsInKB() throws Exception {
        this.givenALimit();
        this.whenShown(300);
        this.thenItReads("300", RateField.Unit.KB);
    }

    @Test
    void aLimitTypedInMBWithDecimalsIsKeptInKiB() throws Exception {
        this.givenALimit();
        this.whenShown(100);
        this.whenTheUnitIs(RateField.Unit.MB);
        this.whenTyped("2,5");
        this.thenTheValueIs(2560);
    }

    @Test
    void anUntouchedFieldGivesBackTheExactValueShown() throws Exception {
        this.givenALimit();
        this.whenShown(122_070);
        this.thenItReads("119.21", RateField.Unit.MB);
        this.thenTheValueIs(122_070);
    }

    @Test
    void anotherUnitKeepsTheSpeedTyped() throws Exception {
        this.givenALimit();
        this.whenShown(2048);
        this.whenTheUnitIs(RateField.Unit.KB);
        this.thenItReads("2048", RateField.Unit.KB);
        this.thenTheValueIs(2048);
    }

    @Test
    void noLimitIsZeroAndTurnsTheFieldOff() throws Exception {
        this.givenALimit();
        this.whenShown(0);
        this.thenTheValueIs(0);
        this.thenTheFieldIsOff(true);
        this.whenNoLimitIs(false);
        this.whenTyped("50");
        this.thenTheValueIs(50);
        this.thenTheFieldIsOff(false);
    }

    @Test
    void aLimitBelowOneKBIsNotTaken() throws Exception {
        this.givenALimit();
        this.whenShown(10);
        this.whenTyped("0.4");
        this.thenItIsValid(false);
    }

    @Test
    void aLineIsTypedInMbitAndToldInBytesToo() throws Exception {
        this.givenALine();
        this.whenShown(12_207);
        this.thenItReads("100", RateField.Unit.MBIT);
        this.thenItIsAbout("≈ " + this.test.gui.formats().rate(12_207 * 1024L));
        this.whenTyped("1000");
        this.thenTheValueIs(122_070);
    }

    @Test
    void anOptionalLimitIsNothingWhenEmpty() throws Exception {
        this.givenAnOptionalLimit();
        this.whenTheOptionalIsShown(OptionalInt.empty());
        this.thenTheOptionalIs(OptionalInt.empty());
        this.whenTheOptionalIsShown(OptionalInt.of(0));
        this.thenTheOptionalIs(OptionalInt.of(0));
    }

    private void givenALimit() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        this.field = FxTesting.onFx(() -> RateField.limit(this.test.gui, "limit"));
    }

    private void givenALine() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        this.field = FxTesting.onFx(() -> RateField.line(this.test.gui, "line"));
    }

    private void givenAnOptionalLimit() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        this.optional = FxTesting.onFx(() -> RateField.optionalLimit(this.test.gui, "rule"));
    }

    private void whenShown(int kib) throws Exception {
        FxTesting.onFx(() -> this.field.show(kib));
    }

    private void whenTheOptionalIsShown(OptionalInt kib) throws Exception {
        FxTesting.onFx(() -> this.optional.showOptional(kib));
    }

    private void whenTheUnitIs(RateField.Unit unit) throws Exception {
        FxTesting.onFx(() -> this.field.unitBox().setValue(unit));
    }

    private void whenTyped(String text) throws Exception {
        FxTesting.onFx(() -> this.field.field().setText(text));
    }

    private void whenNoLimitIs(boolean none) throws Exception {
        FxTesting.onFx(() -> this.field.noLimitBox().orElseThrow().setSelected(none));
    }

    private void thenItReads(String text, RateField.Unit unit) throws Exception {
        assertThat(FxTesting.onFx(() -> this.field.field().getText())).isEqualTo(text);
        assertThat(FxTesting.onFx(() -> this.field.unitBox().getValue())).isEqualTo(unit);
    }

    private void thenTheValueIs(int kib) throws Exception {
        assertThat(FxTesting.onFx(() -> this.field.input().value())).isEqualTo(kib);
    }

    private void thenTheOptionalIs(OptionalInt kib) throws Exception {
        assertThat(FxTesting.onFx(() -> this.optional.input().value())).isEqualTo(kib);
    }

    private void thenTheFieldIsOff(boolean off) throws Exception {
        assertThat(FxTesting.onFx(() -> this.field.field().isDisabled())).isEqualTo(off);
    }

    private void thenItIsValid(boolean valid) throws Exception {
        assertThat(FxTesting.onFx(() -> this.field.input().isValid())).isEqualTo(valid);
    }

    private void thenItIsAbout(String text) throws Exception {
        assertThat(FxTesting.onFx(() -> this.field.aboutLabel().getText())).isEqualTo(text);
    }
}
