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
package org.njmule.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RemoteRatesTest {

    private long rate;
    private String limit;
    private Exception failure;

    @ParameterizedTest
    @CsvSource({"50, 50", "0, 0", "500K, 500", "500k, 500", "2M, 2048", "1.5M, 1536", "1G, 1048576", " 3m , 3072"})
    void aRateIsTypedInKiBOrWithAUnit(String typed, long kib) {
        this.whenTyped(typed);
        this.thenTheRateIs(kib);
    }

    @ParameterizedTest
    @CsvSource({"-5", "1.5", "fast", "M", "2X"})
    void anythingElseIsNoRate(String typed) {
        this.whenTyped(typed);
        this.thenItWasRefused();
    }

    @ParameterizedTest
    @CsvSource({"0, unlimited", "50, 50 KiB/s", "1536, 1.50 MiB/s", "1153434, 1.10 GiB/s"})
    void aLimitReadsInTheUnitThatSuitsIt(long kib, String text) {
        this.whenTheLimitIsWritten(kib);
        this.thenItReads(text);
    }

    private void whenTyped(String typed) {
        try {
            this.rate = RemoteControl.rate(typed);
        } catch (Exception e) {
            this.failure = e;
        }
    }

    private void whenTheLimitIsWritten(long kib) {
        this.limit = RemoteControl.limit(kib);
    }

    private void thenTheRateIs(long kib) {
        assertThat(this.failure).isNull();
        assertThat(this.rate).isEqualTo(kib);
    }

    private void thenItWasRefused() {
        assertThat(this.failure).isNotNull();
    }

    private void thenItReads(String text) {
        assertThat(this.limit).isEqualTo(text);
    }
}
