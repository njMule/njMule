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

import java.util.HexFormat;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class LittleEndianTest {

    private static final HexFormat HEX = HexFormat.of();

    private byte[] bytes;
    private int value;
    private Throwable exception;

    @Test
    void shouldWriteAShortLowByteFirst() {
        givenBytes(4);

        whenAShortIsWrittenAt(1, 0x10302);

        thenTheBytesAre("00020300");
    }

    @Test
    void shouldWriteAnIntLowByteFirstAtAnyOffset() {
        givenBytes(6);

        whenAnIntIsWrittenAt(1, 0x04030201);

        thenTheBytesAre("000102030400");
    }

    @Test
    void shouldWriteALongLowByteFirst() {
        givenBytes(9);

        whenALongIsWrittenAt(1, 0x0807060504030201L);

        thenTheBytesAre("000102030405060708");
    }

    @Test
    void shouldGiveTheFourBytesOfAnInt() {
        whenTheBytesOfAnIntAreTaken(0xCAFEBABE);

        thenTheBytesAre("bebafeca");
    }

    @Property
    void shouldReadBackTheIntWrittenAtAnyOffset(@ForAll int written, @ForAll @IntRange(max = 4) int at) {
        givenBytes(8);
        givenAnIntIsWrittenAt(at, written);

        whenAnIntIsReadAt(at);

        thenTheValueIs(written);
    }

    @Test
    void shouldRefuseToReadPastTheEnd() {
        givenBytes(4);

        whenAnIntIsReadAt(1);

        thenExceptionOccurred(IndexOutOfBoundsException.class);
    }

    private void givenBytes(int length) {
        this.bytes = new byte[length];
    }

    private void givenAnIntIsWrittenAt(int at, int written) {
        whenAnIntIsWrittenAt(at, written);
    }

    private void whenAShortIsWrittenAt(int at, int written) {
        LittleEndian.putShort(this.bytes, at, written);
    }

    private void whenAnIntIsWrittenAt(int at, int written) {
        LittleEndian.putInt(this.bytes, at, written);
    }

    private void whenALongIsWrittenAt(int at, long written) {
        LittleEndian.putLong(this.bytes, at, written);
    }

    private void whenTheBytesOfAnIntAreTaken(int written) {
        this.bytes = LittleEndian.ofInt(written);
    }

    private void whenAnIntIsReadAt(int at) {
        this.exception = catchThrowable(() -> this.value = LittleEndian.getInt(this.bytes, at));
    }

    private void thenTheBytesAre(String hex) {
        assertThat(HEX.formatHex(this.bytes)).isEqualTo(hex);
    }

    private void thenTheValueIs(int expected) {
        assertThat(this.exception).isNull();
        assertThat(this.value).isEqualTo(expected);
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).isInstanceOf(type);
    }
}
