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
package org.njmule.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class Ipv4Test {

    private int address;
    private boolean answer;

    @ParameterizedTest
    @CsvSource({"10, 0, 0, 1", "172, 16, 0, 1", "172, 31, 255, 255", "192, 168, 1, 1", "0, 1, 2, 3"})
    void shouldTellALanAddress(int a, int b, int c, int d) {
        givenTheAddress(a, b, c, d);

        whenItIsCheckedForTheLan();

        thenTheAnswerIs(true);
    }

    @ParameterizedTest
    @CsvSource({"172, 32, 0, 1", "192, 169, 0, 1", "11, 0, 0, 1"})
    void shouldTellAnAddressOutsideTheLan(int a, int b, int c, int d) {
        givenTheAddress(a, b, c, d);

        whenItIsCheckedForTheLan();

        thenTheAnswerIs(false);
    }

    @Test
    void shouldKeepLoopbackApartFromTheLan() {
        givenTheAddress(127, 0, 0, 1);

        whenItIsCheckedForTheLan();

        thenTheAnswerIs(false, "loopback is apart");
    }

    @Test
    void shouldTakeAPublicUnicastAddressForAPeer() {
        givenTheAddress(93, 184, 216, 34);

        whenItIsCheckedForAPeer();

        thenTheAnswerIs(true);
    }

    @ParameterizedTest
    @CsvSource({"127, 0, 0, 1", "224, 0, 0, 1", "0, 0, 0, 0"})
    void shouldRefuseAnAddressThatNoPeerCanHave(int a, int b, int c, int d) {
        givenTheAddress(a, b, c, d);

        whenItIsCheckedForAPeer();

        thenTheAnswerIs(false);
    }

    private void givenTheAddress(int a, int b, int c, int d) {
        this.address = Ipv4.of(a, b, c, d);
    }

    private void whenItIsCheckedForTheLan() {
        this.answer = Ipv4.isLan(this.address);
    }

    private void whenItIsCheckedForAPeer() {
        this.answer = Ipv4.isUnicastPublicCandidate(this.address);
    }

    private void thenTheAnswerIs(boolean expected) {
        assertThat(this.answer).isEqualTo(expected);
    }

    private void thenTheAnswerIs(boolean expected, String description) {
        assertThat(this.answer).as(description).isEqualTo(expected);
    }
}
