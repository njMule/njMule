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

import java.nio.charset.StandardCharsets;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.njmule.core.api.Hash20;

class Base32Test {

    // RFC 4648 section 10, without padding.
    @ParameterizedTest
    @CsvSource({
        "f,MY",
        "fo,MZXQ",
        "foo,MZXW6",
        "foob,MZXW6YQ",
        "fooba,MZXW6YTB",
        "foobar,MZXW6YTBOI"
    })
    void matchesTheRfcVectors(String plain, String encoded) {
        byte[] raw = plain.getBytes(StandardCharsets.US_ASCII);

        assertThat(Base32.encode(raw)).isEqualTo(encoded);
        assertThat(Base32.decode(encoded)).isEqualTo(raw);
        assertThat(Base32.decode(encoded.toLowerCase())).isEqualTo(raw);
    }

    @Test
    void rejectsCharactersOutsideTheAlphabet() {
        assertThatThrownBy(() -> Base32.decode("MZ1Q"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aichHashesAre32Characters() {
        Hash20 hash = Hash20.of(new byte[20]);

        assertThat(hash.toBase32()).isEqualTo("A".repeat(32));
        assertThat(Hash20.parseBase32("a".repeat(32))).isEqualTo(hash);
    }

    @Property
    void roundTrips(@ForAll byte[] data) {
        assertThat(Base32.decode(Base32.encode(data))).isEqualTo(data);
    }
}
