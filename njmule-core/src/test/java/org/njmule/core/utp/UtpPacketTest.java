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
package org.njmule.core.utp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.HexFormat;
import java.util.Optional;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.util.Bytes;

class UtpPacketTest {

    private static final HexFormat HEX = HexFormat.of();

    private UtpPacket packet;
    private byte[] encoded;
    private UtpPacket decoded;
    private Throwable exception;

    @Test
    void shouldWriteTheHeaderInNetworkOrder() {
        givenThePacket(new UtpPacket(
                UtpPacket.Type.SYN, 0x1234, 0x01020304, 0x05060708, 0x100000, 1, 0, Optional.empty(), Bytes.EMPTY));

        whenItIsEncoded();

        thenTheBytesAre("4100" + "1234" + "01020304" + "05060708" + "00100000" + "0001" + "0000");
    }

    @Test
    void shouldRoundTripADataPacketWithASelectiveAck() throws DecodeException {
        givenThePacket(new UtpPacket(
                UtpPacket.Type.DATA,
                7,
                -1,
                42,
                65_536,
                0xFFFF,
                0x8000,
                Optional.of(Bytes.of(new byte[] {5, 0, 0, (byte) 0x80})),
                Bytes.of(new byte[] {9, 8, 7})));
        givenItIsEncoded();

        whenTheBytesAreDecoded();

        thenTheDecodedPacketIsTheSame();
    }

    @Test
    void shouldSkipAnExtensionItDoesNotKnow() throws DecodeException {
        givenTheBytes("0102" + "0001" + "00000000" + "00000000" + "00001000" + "0002" + "0001"
                // An unknown extension, then the selective ack, then the payload.
                + "0102" + "abcd" + "0004" + "01000000" + "ff");

        whenTheBytesAreDecoded();

        thenNoExceptionOccurred();
        thenTheDecodedSelectiveAckIs("01000000");
        thenTheDecodedPayloadIs("ff");
    }

    @Test
    void shouldRefuseAnotherVersion() {
        givenTheBytes("0200" + "0001" + "00000000" + "00000000" + "00001000" + "0002" + "0001");

        whenTheBytesAreDecoded();

        thenExceptionOccurred(DecodeException.class);
    }

    @Property
    void shouldOnlyFailWithDecodeExceptionOnGarbage(@ForAll @Size(max = 100) byte[] data) {
        givenTheBytes(HEX.formatHex(data));

        whenTheBytesAreDecoded();

        thenAnyFailureIsADecodeException();
    }

    private void givenThePacket(UtpPacket p) {
        this.packet = p;
    }

    private void givenItIsEncoded() {
        whenItIsEncoded();
    }

    private void givenTheBytes(String hex) {
        this.encoded = HEX.parseHex(hex);
    }

    private void whenItIsEncoded() {
        this.encoded = this.packet.encode();
    }

    private void whenTheBytesAreDecoded() {
        byte[] bytes = this.encoded;
        this.exception = catchThrowable(() -> this.decoded = UtpPacket.decode(bytes, 0, bytes.length));
    }

    private void thenTheBytesAre(String hex) {
        assertThat(HEX.formatHex(this.encoded)).isEqualTo(hex);
    }

    private void thenTheDecodedPacketIsTheSame() {
        assertThat(this.exception).isNull();
        assertThat(this.decoded).isEqualTo(this.packet);
    }

    private void thenTheDecodedSelectiveAckIs(String hex) {
        assertThat(this.decoded.selectiveAck()).contains(Bytes.of(HEX.parseHex(hex)));
    }

    private void thenTheDecodedPayloadIs(String hex) {
        assertThat(this.decoded.payload()).isEqualTo(Bytes.of(HEX.parseHex(hex)));
    }

    private void thenNoExceptionOccurred() {
        assertThat(this.exception).isNull();
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private void thenAnyFailureIsADecodeException() {
        if (this.exception != null) {
            assertThat(this.exception).isInstanceOf(DecodeException.class);
        }
    }
}
