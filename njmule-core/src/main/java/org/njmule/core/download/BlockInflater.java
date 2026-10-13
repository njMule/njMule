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

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import org.njmule.core.protocol.io.DecodeException;

/**
 * Inflates the zlib stream of one compressed block as its chunks arrive. The output may not grow
 * beyond the block that was requested, so a small stream cannot claim large amounts of memory.
 */
final class BlockInflater implements AutoCloseable {

    private final long blockStart;
    private final int maxOutput;
    private final Inflater inflater = new Inflater();
    private final byte[] chunk = new byte[16 * 1024];
    private long produced;

    BlockInflater(long blockStart, int maxOutput) {
        this.blockStart = blockStart;
        this.maxOutput = maxOutput;
    }

    long blockStart() {
        return this.blockStart;
    }

    /** Where the next inflated bytes go in the file. */
    long position() {
        return this.blockStart + this.produced;
    }

    boolean finished() {
        return this.inflater.finished();
    }

    /** Feeds one chunk; returns the bytes it inflated to. */
    byte[] inflate(byte[] input) throws DecodeException {
        this.inflater.setInput(input);
        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length * 4);
        try {
            while (!this.inflater.finished() && !this.inflater.needsInput()) {
                int n = this.inflater.inflate(this.chunk);
                if (n == 0 && this.inflater.needsDictionary()) {
                    throw new DecodeException("compressed block needs a dictionary");
                }
                if (n == 0 && !this.inflater.needsInput() && !this.inflater.finished()) {
                    throw new DecodeException("compressed block makes no progress");
                }
                this.produced += n;
                if (this.produced > this.maxOutput) {
                    throw new DecodeException(
                            "compressed block inflates beyond the "
                                    + this.maxOutput
                                    + " bytes requested");
                }
                out.write(this.chunk, 0, n);
            }
        } catch (DataFormatException e) {
            throw new DecodeException("corrupt compressed block", e);
        }
        return out.toByteArray();
    }

    @Override
    public void close() {
        this.inflater.close();
    }
}
