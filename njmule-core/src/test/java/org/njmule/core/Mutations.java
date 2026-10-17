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
package org.njmule.core;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * Valid data changed as a broken or hostile peer, or a damaged disk, could change it: one to three changes of the kinds
 * that reach past the first checks of a decoder.
 */
public final class Mutations {

    private static final int[] SPECIAL_BYTES = {0x00, 0x01, 0x7F, 0x80, 0xFF};
    private static final long[] SPECIAL_NUMBERS = {0, 1, 0xFF, 0xFFFF, 0x7FFF_FFFFL, 0x8000_0000L, 0xFFFF_FFFFL};

    private Mutations() {}

    /**
     * {@code valid} changed one to three times, the same way for the same {@code seed}; {@code valid} is left alone.
     */
    public static byte[] changed(byte[] valid, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        byte[] data = valid.clone();
        int changes = 1 + random.nextInt(3);
        for (int i = 0; i < changes; i++) {
            data = changedOnce(data, random);
        }
        return data;
    }

    private static byte[] changedOnce(byte[] data, SplittableRandom random) {
        int at = data.length == 0 ? 0 : random.nextInt(data.length);
        switch (random.nextInt(7)) {
            case 0 -> {
                if (data.length > 0) {
                    data[at] ^= (byte) (1 << random.nextInt(8));
                }
            }
            case 1 -> {
                if (data.length > 0) {
                    data[at] = (byte) SPECIAL_BYTES[random.nextInt(SPECIAL_BYTES.length)];
                }
            }
            case 2 -> {
                // A length or a count: two or four bytes, little endian.
                long value = SPECIAL_NUMBERS[random.nextInt(SPECIAL_NUMBERS.length)];
                int width = random.nextBoolean() ? 2 : 4;
                for (int b = 0; b < width && at + b < data.length; b++) {
                    data[at + b] = (byte) (value >>> (8 * b));
                }
            }
            case 3 -> data = Arrays.copyOf(data, data.length == 0 ? 0 : random.nextInt(data.length));
            case 4 -> {
                byte[] junk = new byte[1 + random.nextInt(16)];
                random.nextBytes(junk);
                data = spliced(data, at, 0, junk);
            }
            case 5 -> data = spliced(data, at, Math.min(data.length - at, 1 + random.nextInt(8)), new byte[0]);
            default -> {
                int length = Math.min(data.length - at, 1 + random.nextInt(32));
                data = spliced(data, at, 0, Arrays.copyOfRange(data, at, at + length));
            }
        }
        return data;
    }

    private static byte[] spliced(byte[] data, int at, int removed, byte[] inserted) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + inserted.length);
        out.write(data, 0, at);
        out.write(inserted, 0, inserted.length);
        out.write(data, at + removed, data.length - at - removed);
        return out.toByteArray();
    }
}
