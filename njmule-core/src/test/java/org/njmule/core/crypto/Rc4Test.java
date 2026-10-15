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
package org.njmule.core.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.Arrays;
import java.util.HexFormat;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

class Rc4Test {

    private static final HexFormat HEX = HexFormat.of();

    private byte[] key;
    private Rc4 rc4;
    private byte[] output;
    private Throwable exception;

    @Test
    void shouldMatchTheRfc6229KeyStream() {
        // RFC 6229, 40-bit key 0x0102030405, offsets 0 and 16.
        givenAnRc4WithTheKey("0102030405");

        whenItIsAppliedToZeroBytes(32);

        thenTheOutputIs("b2396305f03dc027ccc3524a0a1118a8" + "6982944f18fc82d589c403a47a0d0919");
    }

    @Test
    void shouldRefuseToSkipBackwards() {
        givenAnRc4WithTheKey("0102030405");
        givenItSkipped(0);

        whenItSkips(-1);

        thenExceptionOccurred(IllegalArgumentException.class);
        thenTheNextKeyStreamByteIs((byte) 0xB2);
    }

    @Property
    void shouldSkipAsIfTheOutputWereDiscarded(
            @ForAll @Size(min = 5, max = 32) byte[] anyKey, @ForAll @IntRange(max = 10_000) int skip) {
        givenAnRc4WithTheKey(anyKey);

        whenItSkips(skip);

        thenTheNextBytesAreTheKeyStreamAfter(skip);
    }

    private void givenAnRc4WithTheKey(String hex) {
        givenAnRc4WithTheKey(HEX.parseHex(hex));
    }

    private void givenAnRc4WithTheKey(byte[] bytes) {
        this.key = bytes;
        this.rc4 = new Rc4(bytes);
    }

    private void givenItSkipped(int count) {
        this.rc4.skip(count);
    }

    private void whenItIsAppliedToZeroBytes(int count) {
        this.output = new byte[count];
        this.rc4.apply(this.output, 0, this.output.length);
    }

    private void whenItSkips(int count) {
        this.exception = catchThrowable(() -> this.rc4.skip(count));
    }

    private void thenTheOutputIs(String hex) {
        assertThat(HEX.formatHex(this.output)).isEqualTo(hex);
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private void thenTheNextKeyStreamByteIs(byte expected) {
        byte[] stream = new byte[1];
        this.rc4.apply(stream, 0, 1);
        assertThat(stream[0]).as("nothing was consumed").isEqualTo(expected);
    }

    private void thenTheNextBytesAreTheKeyStreamAfter(int skip) {
        assertThat(this.exception).isNull();
        byte[] full = new byte[skip + 16];
        new Rc4(this.key).apply(full, 0, full.length);
        byte[] tail = new byte[16];
        this.rc4.apply(tail, 0, tail.length);
        assertThat(tail).isEqualTo(Arrays.copyOfRange(full, skip, full.length));
    }
}
