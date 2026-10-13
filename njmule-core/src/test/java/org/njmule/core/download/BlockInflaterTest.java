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
package org.njmule.core.download;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;
import org.njmule.core.protocol.io.DecodeException;

class BlockInflaterTest {

    @Test
    void inflatesAStreamSplitIntoChunks() throws DecodeException {
        byte[] block = new byte[184_320];
        new Random(7).nextBytes(block);
        Arrays.fill(block, 50_000, 120_000, (byte) 'x');
        byte[] stream = deflate(block);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (BlockInflater inflater = new BlockInflater(1_000_000, block.length)) {
            for (int at = 0; at < stream.length; at += 10_240) {
                assertThat(inflater.position()).isEqualTo(1_000_000 + out.size());
                byte[] chunk = Arrays.copyOfRange(stream, at, Math.min(stream.length, at + 10_240));
                out.writeBytes(inflater.inflate(chunk));
            }
            assertThat(inflater.finished()).isTrue();
        }
        assertThat(out.toByteArray()).isEqualTo(block);
    }

    @Test
    void givesEveryByteOfBlocksThatEndInLongRepeats() throws DecodeException {
        // A long repeat at the end leaves output pending in zlib once all input is read.
        Random random = new Random(3);
        for (int round = 0; round < 200; round++) {
            byte[] block = new byte[184_320];
            int noise = random.nextInt(block.length);
            for (int i = 0; i < noise; i++) {
                block[i] = (byte) random.nextInt();
            }
            Arrays.fill(block, noise, block.length, (byte) random.nextInt());
            byte[] stream = deflate(block);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (BlockInflater inflater = new BlockInflater(0, block.length)) {
                for (int at = 0; at < stream.length; at += 10_240) {
                    out.writeBytes(
                            inflater.inflate(
                                    Arrays.copyOfRange(
                                            stream, at, Math.min(stream.length, at + 10_240))));
                }
                assertThat(inflater.finished()).as("round %d", round).isTrue();
            }
            assertThat(out.toByteArray()).as("round %d", round).isEqualTo(block);
        }
    }

    @Test
    void refusesOutputBeyondTheRequestedBlock() {
        byte[] bomb = deflate(new byte[1_000_000]);
        try (BlockInflater inflater = new BlockInflater(0, 184_320)) {
            assertThatThrownBy(() -> inflater.inflate(bomb))
                    .isInstanceOf(DecodeException.class)
                    .hasMessageContaining("184320");
        }
    }

    @Test
    void refusesCorruptStreams() {
        try (BlockInflater inflater = new BlockInflater(0, 1000)) {
            assertThatThrownBy(() -> inflater.inflate(new byte[] {1, 2, 3, 4, 5, 6}))
                    .isInstanceOf(DecodeException.class);
        }
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(1);
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return out.toByteArray();
    }
}
