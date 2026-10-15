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
import static org.assertj.core.api.Assertions.catchThrowable;

import com.google.common.primitives.Ints;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BytesTest {

    private byte[] source;
    private Bytes bytes;
    private Throwable exception;

    @Test
    void shouldSliceInsideTheSource() {
        givenTheSource(1, 2, 3, 4);

        whenASliceIsTaken(1, 3);

        thenTheBytesAre(2, 3, 4);
    }

    @Test
    void shouldGiveTheEmptyBytesForAnEmptySliceAtTheEnd() {
        givenTheSource(1, 2, 3, 4);

        whenASliceIsTaken(4, 0);

        thenTheBytesAreTheEmptyOnes();
    }

    @ParameterizedTest
    @CsvSource({"2, 3", "5, 0", "-1, 2", "0, -1"})
    void shouldRefuseASliceOutsideTheSource(int offset, int length) {
        givenTheSource(1, 2, 3, 4);

        whenASliceIsTaken(offset, length);

        thenExceptionOccurred(IndexOutOfBoundsException.class);
    }

    @Test
    void shouldKeepItsContentWhenTheSourceChanges() {
        givenTheSource(1, 2);
        givenTheBytesOfTheWholeSource();

        whenAByteOfTheSourceIsOverwritten(0, 9);

        thenTheBytesAre(1, 2);
        thenTheBytesEqualAndHashLike(1, 2);
    }

    @Test
    void shouldKeepItsContentWhenTheArrayItGaveChanges() {
        givenTheSource(1, 2);
        givenTheBytesOfTheWholeSource();

        whenAByteOfTheArrayItGivesIsOverwritten(1, 9);

        thenTheBytesAre(1, 2);
        thenTheBytesEqualAndHashLike(1, 2);
    }

    private void givenTheSource(int... values) {
        this.source = toBytes(values);
    }

    private void givenTheBytesOfTheWholeSource() {
        this.bytes = Bytes.of(this.source);
    }

    private void whenASliceIsTaken(int offset, int length) {
        this.exception = catchThrowable(() -> this.bytes = Bytes.of(this.source, offset, length));
    }

    private void whenAByteOfTheSourceIsOverwritten(int index, int value) {
        this.source[index] = (byte) value;
    }

    private void whenAByteOfTheArrayItGivesIsOverwritten(int index, int value) {
        this.bytes.toByteArray()[index] = (byte) value;
    }

    private void thenTheBytesAre(int... expected) {
        assertThat(this.exception).isNull();
        assertThat(this.bytes.toByteArray()).containsExactly(expected);
    }

    private void thenTheBytesAreTheEmptyOnes() {
        assertThat(this.exception).isNull();
        assertThat(this.bytes).isEqualTo(Bytes.EMPTY);
    }

    private void thenTheBytesEqualAndHashLike(int... values) {
        assertThat(this.bytes).isEqualTo(Bytes.of(toBytes(values))).hasSameHashCodeAs(Bytes.of(toBytes(values)));
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private static byte[] toBytes(int... values) {
        return com.google.common.primitives.Bytes.toArray(Ints.asList(values));
    }
}
