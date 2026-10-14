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
package org.njmule.core.kad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.HexFormat;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;
import org.njmule.core.protocol.io.DecodeException;

class UInt128Test {

    private static final UInt128 SAMPLE = UInt128.parseHex("00112233445566778899AABBCCDDEEFF");

    @Test
    void onTheWireEachWordIsLittleEndianMostSignificantFirst() throws Exception {
        assertThat(HexFormat.of().formatHex(SAMPLE.toWireBytes()))
                .isEqualTo("33221100" + "77665544" + "bbaa9988" + "ffeeddcc");
        assertThat(UInt128.fromWireBytes(SAMPLE.toWireBytes())).isEqualTo(SAMPLE);
        assertThat(HexFormat.of().formatHex(SAMPLE.toBigEndian())).isEqualTo("00112233445566778899aabbccddeeff");
    }

    @Test
    void wrongLengthsAreRefused() {
        assertThatThrownBy(() -> UInt128.fromWireBytes(new byte[15])).isInstanceOf(DecodeException.class);
        assertThatThrownBy(() -> UInt128.fromBigEndian(new byte[17])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UInt128.parseHex("00")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void orderIsUnsignedAndBitZeroIsTheMostSignificant() {
        UInt128 top = new UInt128(Long.MIN_VALUE, 0);
        UInt128 lowSign = new UInt128(0, Long.MIN_VALUE);
        UInt128 ones = new UInt128(-1, -1);

        assertThat(top).isGreaterThan(new UInt128(Long.MAX_VALUE, -1));
        assertThat(lowSign).isGreaterThan(new UInt128(0, Long.MAX_VALUE));
        assertThat(ones).isGreaterThan(top);
        assertThat(top.bit(0)).isEqualTo(1);
        assertThat(lowSign.bit(64)).isEqualTo(1);
        assertThat(lowSign.bit(63)).isZero();
        assertThat(ones.bit(127)).isEqualTo(1);
        assertThat(ones.top32()).isEqualTo(0xFFFF_FFFFL);
        assertThatThrownBy(() -> ones.bit(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> ones.bit(128)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Property
    void xorIsADistance(@ForAll long a1, @ForAll long a2, @ForAll long b1, @ForAll long b2, @ForAll long c1) {
        UInt128 a = new UInt128(a1, a2);
        UInt128 b = new UInt128(b1, b2);
        UInt128 c = new UInt128(c1, a2 ^ b2);

        assertThat(a.xor(a).isZero()).isTrue();
        assertThat(a.xor(b)).isEqualTo(b.xor(a));
        assertThat(a.xor(b).xor(b.xor(c))).isEqualTo(a.xor(c));
    }

    @Property
    void orderAndBitsAgreeWithBigInteger(@ForAll long a1, @ForAll long a2, @ForAll long b1, @ForAll long b2) {
        UInt128 a = new UInt128(a1, a2);
        UInt128 b = new UInt128(b1, b2);

        assertThat(Integer.signum(a.compareTo(b))).isEqualTo(Integer.signum(big(a).compareTo(big(b))));
        for (int i = 0; i < UInt128.BITS; i++) {
            assertThat(a.bit(i) == 1).isEqualTo(big(a).testBit(UInt128.BITS - 1 - i));
        }
    }

    @Property
    void formsRoundTrip(@ForAll long high, @ForAll long low) throws Exception {
        UInt128 id = new UInt128(high, low);

        assertThat(UInt128.parseHex(id.toHex())).isEqualTo(id);
        assertThat(UInt128.fromWireBytes(id.toWireBytes())).isEqualTo(id);
        assertThat(UInt128.fromBigEndian(id.toBigEndian())).isEqualTo(id);
        assertThat(UInt128.of(id.toHash16())).isEqualTo(id);
    }

    @Property
    void topBitsAreTheFirst32(@ForAll long high, @ForAll @IntRange(min = 0, max = 31) int bit) {
        UInt128 id = new UInt128(high, 0);

        assertThat(id.top32() >>> (31 - bit) & 1).isEqualTo((long) id.bit(bit));
    }

    private static BigInteger big(UInt128 id) {
        return new BigInteger(1, id.toBigEndian());
    }
}
