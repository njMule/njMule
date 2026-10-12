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
package org.njmule.core.protocol.crypt;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.njmule.core.crypto.Rc4;

/** Streams that apply an RC4 key stream to everything that passes through them. */
public final class Rc4Streams {

    private static final int CHUNK = 16 * 1024;

    private Rc4Streams() {}

    public static InputStream decrypting(InputStream in, Rc4 cipher) {
        return new FilterInputStream(in) {
            @Override
            public int read() throws IOException {
                int b = this.in.read();
                if (b < 0) {
                    return b;
                }
                byte[] one = {(byte) b};
                cipher.apply(one, 0, 1);
                return one[0] & 0xFF;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int n = this.in.read(buffer, offset, length);
                if (n > 0) {
                    cipher.apply(buffer, offset, n);
                }
                return n;
            }

            @Override
            public long skip(long n) throws IOException {
                // Skipped bytes must still pass through the key stream.
                byte[] scratch = new byte[(int) Math.min(n, CHUNK)];
                long done = 0;
                while (done < n) {
                    int r = this.read(scratch, 0, (int) Math.min(scratch.length, n - done));
                    if (r < 0) {
                        break;
                    }
                    done += r;
                }
                return done;
            }

            @Override
            public boolean markSupported() {
                return false;
            }
        };
    }

    public static OutputStream encrypting(OutputStream out, Rc4 cipher) {
        return new FilterOutputStream(out) {
            private final byte[] scratch = new byte[CHUNK];

            @Override
            public void write(int b) throws IOException {
                byte[] one = {(byte) b};
                cipher.apply(one, 0, 1);
                this.out.write(one[0] & 0xFF);
            }

            @Override
            public void write(byte[] buffer, int offset, int length) throws IOException {
                // The caller's array is left untouched.
                for (int done = 0; done < length; ) {
                    int n = Math.min(this.scratch.length, length - done);
                    cipher.apply(buffer, offset + done, this.scratch, 0, n);
                    this.out.write(this.scratch, 0, n);
                    done += n;
                }
            }
        };
    }
}
