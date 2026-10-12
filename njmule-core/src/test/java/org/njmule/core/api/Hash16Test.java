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
package org.njmule.core.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class Hash16Test {

    @Test
    void parsesAndFormatsUpperCaseHex() {
        Hash16 hash = Hash16.parseHex("31d6cfe0d16ae931b73c59d7e0c089c0");

        assertThat(hash.toHex()).isEqualTo("31D6CFE0D16AE931B73C59D7E0C089C0");
        assertThat(hash).isEqualTo(Hash16.parseHex(hash.toHex())).hasSameHashCodeAs(hash);
        assertThat(hash.isZero()).isFalse();
        assertThat(Hash16.ZERO.isZero()).isTrue();
    }

    @Test
    void isImmutable() {
        byte[] raw = new byte[16];
        Hash16 hash = Hash16.of(raw);
        raw[0] = 1;
        hash.toByteArray()[1] = 1;

        assertThat(hash).isEqualTo(Hash16.ZERO);
    }

    @Test
    void comparesUnsigned() {
        assertThat(Hash16.parseHex("80" + "00".repeat(15)))
                .isGreaterThan(Hash16.parseHex("7F" + "FF".repeat(15)));
    }

    @Test
    void rejectsWrongLengths() {
        assertThatThrownBy(() -> Hash16.of(new byte[15]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Hash16.parseHex("00"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
