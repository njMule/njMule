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
package org.njmule.core.upload;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;

/** A block of a file cut into the packets that carry it, compressed when that is worth it. */
class BlocksTest {

    private static final Hash16 FILE = Hash16.parseHex("0123456789abcdef0123456789abcdef");
    private static final int BLOCK = 184_320;

    private byte[] data;
    private List<Packet> packets;

    @Test
    void shouldSendABlockThatShrinksCompressed() {
        givenABlockOfText();

        whenTheBlockIsCut(true);

        thenEveryPacketIs(Opcodes.Extended.COMPRESSEDPART.code());
    }

    @Test
    void shouldSendABlockOfCompressedDataAsItIs() {
        givenABlockOfRandomBytes(BLOCK);

        whenTheBlockIsCut(true);

        thenEveryPacketIs(Opcodes.Client.SENDINGPART.code());
    }

    // The first 16 KiB tell: the zeros after them are not compressed either.
    @Test
    void shouldSendAsItIsABlockWhoseStartDoesNotShrink() {
        givenABlockOfRandomBytesThenZeros(16 * 1024);

        whenTheBlockIsCut(true);

        thenEveryPacketIs(Opcodes.Client.SENDINGPART.code());
    }

    @Test
    void shouldSendABlockAsItIsWhenTheClientTakesNoCompression() {
        givenABlockOfText();

        whenTheBlockIsCut(false);

        thenEveryPacketIs(Opcodes.Client.SENDINGPART.code());
    }

    private void givenABlockOfText() {
        StringBuilder text = new StringBuilder();
        for (int line = 0; text.length() < BLOCK; line++) {
            text.append("Line ").append(line).append(" of a text file that compresses well.\n");
        }
        this.data = text.substring(0, BLOCK).getBytes(StandardCharsets.US_ASCII);
    }

    private void givenABlockOfRandomBytes(int length) {
        this.data = new byte[BLOCK];
        byte[] random = new byte[length];
        new SplittableRandom(7).nextBytes(random);
        System.arraycopy(random, 0, this.data, 0, length);
    }

    private void givenABlockOfRandomBytesThenZeros(int randomLength) {
        givenABlockOfRandomBytes(randomLength);
    }

    private void whenTheBlockIsCut(boolean compress) {
        this.packets = Blocks.packets(FILE, 0, this.data, compress);
    }

    private void thenEveryPacketIs(int opcode) {
        assertThat(this.packets).isNotEmpty().allMatch(p -> p.opcode() == opcode);
    }
}
