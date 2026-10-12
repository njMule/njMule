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

/**
 * RC4 stream cipher, used by the eD2K protocol obfuscation (not for security). The JCE {@code
 * ARCFOUR} cipher would copy every buffer; this one works in place on the hot path.
 */
public final class Rc4 {

    /** Bytes of key stream discarded by the TCP obfuscation. */
    public static final int TCP_DISCARD = 1024;

    private final byte[] state = new byte[256];
    private int x;
    private int y;

    public Rc4(byte[] key) {
        this(key, 0, key.length);
    }

    public Rc4(byte[] key, int offset, int length) {
        if (length <= 0 || length > 256) {
            throw new IllegalArgumentException("RC4 keys have 1-256 bytes, got " + length);
        }
        byte[] s = this.state;
        for (int i = 0; i < 256; i++) {
            s[i] = (byte) i;
        }
        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j + s[i] + key[offset + i % length]) & 0xFF;
            byte t = s[i];
            s[i] = s[j];
            s[j] = t;
        }
    }

    /** Encrypts or decrypts {@code length} bytes of {@code data} in place. */
    public void apply(byte[] data, int offset, int length) {
        this.apply(data, offset, data, offset, length);
    }

    public void apply(byte[] in, int inOffset, byte[] out, int outOffset, int length) {
        java.util.Objects.checkFromIndexSize(inOffset, length, in.length);
        java.util.Objects.checkFromIndexSize(outOffset, length, out.length);
        byte[] s = this.state;
        int i = this.x;
        int j = this.y;
        for (int k = 0; k < length; k++) {
            i = (i + 1) & 0xFF;
            byte si = s[i];
            j = (j + si) & 0xFF;
            byte sj = s[j];
            s[i] = sj;
            s[j] = si;
            out[outOffset + k] = (byte) (in[inOffset + k] ^ s[(si + sj) & 0xFF]);
        }
        this.x = i;
        this.y = j;
    }

    /** Advances the key stream by {@code length} bytes. */
    public void skip(int length) {
        byte[] s = this.state;
        int i = this.x;
        int j = this.y;
        for (int k = 0; k < length; k++) {
            i = (i + 1) & 0xFF;
            byte si = s[i];
            j = (j + si) & 0xFF;
            s[i] = s[j];
            s[j] = si;
        }
        this.x = i;
        this.y = j;
    }
}
