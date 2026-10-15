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
    void shouldShowALimitOfAThousandKBAndMoreInMB() throws Exception {
        givenALimit();

        whenShown(1536);

        thenItReads("1.5", RateField.Unit.MB);
        thenTheValueIs(1536);
    }

    @Test
    void shouldShowASmallLimitInKB() throws Exception {
        givenALimit();

        whenShown(300);

        thenItReads("300", RateField.Unit.KB);
    }

    @Test
    void shouldKeepALimitTypedInMBWithDecimalsInKiB() throws Exception {
        givenALimit();
        givenShown(100);
        givenTheUnit(RateField.Unit.MB);

        whenTyped("2,5");

        thenTheValueIs(2560);
    }

    @Test
    void shouldGiveBackTheExactValueShownWhenUntouched() throws Exception {
        givenALimit();

        whenShown(122_070);

        thenItReads("119.21", RateField.Unit.MB);
        thenTheValueIs(122_070);
    }

    @Test
    void shouldKeepTheSpeedWhenAnotherUnitIsChosen() throws Exception {
        givenALimit();
        givenShown(2048);

        whenTheUnitIs(RateField.Unit.KB);

        thenItReads("2048", RateField.Unit.KB);
        thenTheValueIs(2048);
    }

    @Test
    void shouldTakeNoLimitAsZeroAndTurnTheFieldOff() throws Exception {
        givenALimit();

        whenShown(0);

        thenTheValueIs(0);
        thenTheFieldIsOff(true);
    }

    @Test
    void shouldTakeTheLimitTypedOnceNoLimitIsUnticked() throws Exception {
        givenALimit();
        givenShown(0);
        givenNoLimitIs(false);

        whenTyped("50");

        thenTheValueIs(50);
        thenTheFieldIsOff(false);
    }

    @Test
    void shouldNotTakeALimitBelowOneKB() throws Exception {
        givenALimit();
        givenShown(10);

        whenTyped("0.4");

        thenItIsValid(false);
    }

    @Test
    void shouldShowALineInMbitAndTellItInBytesToo() throws Exception {
        givenALine();

        whenShown(12_207);

        thenItReads("100", RateField.Unit.MBIT);
        thenItIsAboutTheRateOf(12_207);
    }

    @Test
    void shouldTakeALineTypedInMbit() throws Exception {
        givenALine();
        givenShown(12_207);

        whenTyped("1000");

        thenTheValueIs(122_070);
    }

    @Test
    void shouldGiveNothingForAnEmptyOptionalLimit() throws Exception {
        givenAnOptionalLimit();

        whenTheOptionalIsShown(OptionalInt.empty());

        thenTheOptionalIs(OptionalInt.empty());
    }

    @Test
    void shouldGiveZeroForAnOptionalLimitOfZero() throws Exception {
        givenAnOptionalLimit();
        givenTheOptionalShown(OptionalInt.empty());

        whenTheOptionalIsShown(OptionalInt.of(0));

        thenTheOptionalIs(OptionalInt.of(0));
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

    private void givenShown(int kib) throws Exception {
        FxTesting.onFx(() -> this.field.show(kib));
    }

    private void givenTheOptionalShown(OptionalInt kib) throws Exception {
        FxTesting.onFx(() -> this.optional.showOptional(kib));
    }

    private void givenTheUnit(RateField.Unit unit) throws Exception {
        FxTesting.onFx(() -> this.field.unitBox().setValue(unit));
    }

    private void givenNoLimitIs(boolean none) throws Exception {
        FxTesting.onFx(() -> this.field.noLimitBox().orElseThrow().setSelected(none));
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

    private void thenItIsAboutTheRateOf(int kib) throws Exception {
        assertThat(FxTesting.onFx(() -> this.field.aboutLabel().getText()))
                .isEqualTo("≈ " + this.test.gui.formats().rate(kib * 1024L));
    }
}
