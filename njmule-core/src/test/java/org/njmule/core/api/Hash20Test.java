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

import java.util.Locale;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

class Hash20Test {

    @Test
    void aichHashesAre32Base32Characters() {
        Hash20 zero = Hash20.of(new byte[20]);

        assertThat(zero.toBase32()).isEqualTo("A".repeat(32));
        assertThat(Hash20.parseBase32("a".repeat(32))).isEqualTo(zero);
        assertThatThrownBy(() -> Hash20.parseBase32("A".repeat(31) + "1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Hash20.parseBase32("A".repeat(31)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Property
    void base32RoundTripsInEitherCase(@ForAll @Size(20) byte[] data) {
        Hash20 hash = Hash20.of(data);

        assertThat(Hash20.parseBase32(hash.toBase32())).isEqualTo(hash);
        assertThat(Hash20.parseBase32(hash.toBase32().toLowerCase(Locale.ROOT))).isEqualTo(hash);
    }
}
