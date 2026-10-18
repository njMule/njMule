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
package org.njmule.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class NightHoursTest {

    private NightHours hours;
    private String written;
    private boolean holds;
    private Exception failure;

    @Test
    void shouldReadTheHoursAsWritten() {
        whenRead(" 01:30 - 04:15 ");

        thenTheHoursAre(LocalTime.of(1, 30), LocalTime.of(4, 15));
    }

    @Test
    void shouldWriteTheHoursAsTheyAreRead() {
        givenTheHours("23:00-05:00");

        whenWritten();

        thenTheTextIs("23:00-05:00");
    }

    @ParameterizedTest
    @CsvSource({
        "03:00-05:00, 02:59, false",
        "03:00-05:00, 03:00, true",
        "03:00-05:00, 04:59, true",
        "03:00-05:00, 05:00, false",
        "23:00-02:00, 23:30, true",
        "23:00-02:00, 01:59, true",
        "23:00-02:00, 12:00, false"
    })
    void shouldHoldTheTimesOfItsWindowAlsoPastMidnight(String window, String time, boolean expected) {
        givenTheHours(window);

        whenAskedAbout(LocalTime.parse(time));

        thenItHolds(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "03:00", "03:00-05:00-07:00", "3-5", "03:00-03:00", "25:00-05:00"})
    void shouldRefuseWhatAreNotHoursFromTo(String text) {
        whenRead(text);

        thenItWasRefused();
    }

    private void givenTheHours(String text) {
        this.hours = NightHours.parse(text);
    }

    private void whenRead(String text) {
        try {
            this.hours = NightHours.parse(text);
        } catch (IllegalArgumentException e) {
            this.failure = e;
        }
    }

    private void whenWritten() {
        this.written = this.hours.toString();
    }

    private void whenAskedAbout(LocalTime time) {
        this.holds = this.hours.holds(time);
    }

    private void thenTheHoursAre(LocalTime start, LocalTime end) {
        assertThat(this.hours).isEqualTo(new NightHours(start, end));
    }

    private void thenTheTextIs(String expected) {
        assertThat(this.written).isEqualTo(expected);
    }

    private void thenItHolds(boolean expected) {
        assertThat(this.holds).isEqualTo(expected);
    }

    private void thenItWasRefused() {
        assertThat(this.failure).isInstanceOf(IllegalArgumentException.class);
    }
}
