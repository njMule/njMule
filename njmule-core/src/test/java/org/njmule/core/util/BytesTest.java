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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BytesTest {

    @Test
    void slicesMustLieInsideTheSource() {
        byte[] source = {1, 2, 3, 4};

        assertThat(Bytes.of(source, 1, 3).toByteArray()).containsExactly(2, 3, 4);
        assertThat(Bytes.of(source, 4, 0)).isEqualTo(Bytes.EMPTY);
        assertThatThrownBy(() -> Bytes.of(source, 2, 3)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> Bytes.of(source, 5, 0)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> Bytes.of(source, -1, 2)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> Bytes.of(source, 0, -1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void isImmutable() {
        byte[] source = {1, 2};
        Bytes bytes = Bytes.of(source);
        source[0] = 9;
        bytes.toByteArray()[1] = 9;

        assertThat(bytes.toByteArray()).containsExactly(1, 2);
        assertThat(bytes).isEqualTo(Bytes.of(new byte[] {1, 2})).hasSameHashCodeAs(Bytes.of(new byte[] {1, 2}));
    }
}
