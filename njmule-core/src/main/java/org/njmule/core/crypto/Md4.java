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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import org.njmule.core.api.Hash16;

/**
 * MD4 (RFC 1320), the eD2K file hash. The JDK does not expose MD4, and a plain loop over {@code
 * int}s keeps it fast also in the native image, where there is no JIT to rely on.
 */
public final class Md4 {

    private static final VarHandle LE_INT =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    private static final int BLOCK = 64;

    private final byte[] pending = new byte[BLOCK];
    private int pendingLength;
    private long totalLength;
    private int a;
    private int b;
    private int c;
    private int d;

    public Md4() {
        this.reset();
    }

    public static Hash16 hash(byte[] data) {
        return hash(data, 0, data.length);
    }

    public static Hash16 hash(byte[] data, int offset, int length) {
        Md4 md4 = new Md4();
        md4.update(data, offset, length);
        return md4.digest();
    }

    public void reset() {
        this.a = 0x67452301;
        this.b = 0xEFCDAB89;
        this.c = 0x98BADCFE;
        this.d = 0x10325476;
        this.pendingLength = 0;
        this.totalLength = 0;
    }

    public void update(byte[] data) {
        this.update(data, 0, data.length);
    }

    public void update(byte[] data, int offset, int length) {
        java.util.Objects.checkFromIndexSize(offset, length, data.length);
        this.totalLength += length;
        int pos = offset;
        int end = offset + length;
        if (this.pendingLength > 0) {
            int take = Math.min(BLOCK - this.pendingLength, length);
            System.arraycopy(data, pos, this.pending, this.pendingLength, take);
            this.pendingLength += take;
            pos += take;
            if (this.pendingLength < BLOCK) {
                return;
            }
            this.block(this.pending, 0);
            this.pendingLength = 0;
        }
        for (; end - pos >= BLOCK; pos += BLOCK) {
            this.block(data, pos);
        }
        System.arraycopy(data, pos, this.pending, 0, end - pos);
        this.pendingLength = end - pos;
    }

    /** Finishes the hash and resets this instance for reuse. */
    public Hash16 digest() {
        long bits = this.totalLength << 3;
        byte[] tail = new byte[this.pendingLength < 56 ? BLOCK : 2 * BLOCK];
        System.arraycopy(this.pending, 0, tail, 0, this.pendingLength);
        tail[this.pendingLength] = (byte) 0x80;
        LE_INT.set(tail, tail.length - 8, (int) bits);
        LE_INT.set(tail, tail.length - 4, (int) (bits >>> 32));
        for (int i = 0; i < tail.length; i += BLOCK) {
            this.block(tail, i);
        }
        byte[] out = new byte[Hash16.LENGTH];
        LE_INT.set(out, 0, this.a);
        LE_INT.set(out, 4, this.b);
        LE_INT.set(out, 8, this.c);
        LE_INT.set(out, 12, this.d);
        this.reset();
        return Hash16.of(out);
    }

    private void block(byte[] in, int off) {
        int x0 = (int) LE_INT.get(in, off);
        int x1 = (int) LE_INT.get(in, off + 4);
        int x2 = (int) LE_INT.get(in, off + 8);
        int x3 = (int) LE_INT.get(in, off + 12);
        int x4 = (int) LE_INT.get(in, off + 16);
        int x5 = (int) LE_INT.get(in, off + 20);
        int x6 = (int) LE_INT.get(in, off + 24);
        int x7 = (int) LE_INT.get(in, off + 28);
        int x8 = (int) LE_INT.get(in, off + 32);
        int x9 = (int) LE_INT.get(in, off + 36);
        int x10 = (int) LE_INT.get(in, off + 40);
        int x11 = (int) LE_INT.get(in, off + 44);
        int x12 = (int) LE_INT.get(in, off + 48);
        int x13 = (int) LE_INT.get(in, off + 52);
        int x14 = (int) LE_INT.get(in, off + 56);
        int x15 = (int) LE_INT.get(in, off + 60);
        int aa = this.a;
        int bb = this.b;
        int cc = this.c;
        int dd = this.d;

        aa = r1(aa, bb, cc, dd, x0, 3);
        dd = r1(dd, aa, bb, cc, x1, 7);
        cc = r1(cc, dd, aa, bb, x2, 11);
        bb = r1(bb, cc, dd, aa, x3, 19);
        aa = r1(aa, bb, cc, dd, x4, 3);
        dd = r1(dd, aa, bb, cc, x5, 7);
        cc = r1(cc, dd, aa, bb, x6, 11);
        bb = r1(bb, cc, dd, aa, x7, 19);
        aa = r1(aa, bb, cc, dd, x8, 3);
        dd = r1(dd, aa, bb, cc, x9, 7);
        cc = r1(cc, dd, aa, bb, x10, 11);
        bb = r1(bb, cc, dd, aa, x11, 19);
        aa = r1(aa, bb, cc, dd, x12, 3);
        dd = r1(dd, aa, bb, cc, x13, 7);
        cc = r1(cc, dd, aa, bb, x14, 11);
        bb = r1(bb, cc, dd, aa, x15, 19);

        aa = r2(aa, bb, cc, dd, x0, 3);
        dd = r2(dd, aa, bb, cc, x4, 5);
        cc = r2(cc, dd, aa, bb, x8, 9);
        bb = r2(bb, cc, dd, aa, x12, 13);
        aa = r2(aa, bb, cc, dd, x1, 3);
        dd = r2(dd, aa, bb, cc, x5, 5);
        cc = r2(cc, dd, aa, bb, x9, 9);
        bb = r2(bb, cc, dd, aa, x13, 13);
        aa = r2(aa, bb, cc, dd, x2, 3);
        dd = r2(dd, aa, bb, cc, x6, 5);
        cc = r2(cc, dd, aa, bb, x10, 9);
        bb = r2(bb, cc, dd, aa, x14, 13);
        aa = r2(aa, bb, cc, dd, x3, 3);
        dd = r2(dd, aa, bb, cc, x7, 5);
        cc = r2(cc, dd, aa, bb, x11, 9);
        bb = r2(bb, cc, dd, aa, x15, 13);

        aa = r3(aa, bb, cc, dd, x0, 3);
        dd = r3(dd, aa, bb, cc, x8, 9);
        cc = r3(cc, dd, aa, bb, x4, 11);
        bb = r3(bb, cc, dd, aa, x12, 15);
        aa = r3(aa, bb, cc, dd, x2, 3);
        dd = r3(dd, aa, bb, cc, x10, 9);
        cc = r3(cc, dd, aa, bb, x6, 11);
        bb = r3(bb, cc, dd, aa, x14, 15);
        aa = r3(aa, bb, cc, dd, x1, 3);
        dd = r3(dd, aa, bb, cc, x9, 9);
        cc = r3(cc, dd, aa, bb, x5, 11);
        bb = r3(bb, cc, dd, aa, x13, 15);
        aa = r3(aa, bb, cc, dd, x3, 3);
        dd = r3(dd, aa, bb, cc, x11, 9);
        cc = r3(cc, dd, aa, bb, x7, 11);
        bb = r3(bb, cc, dd, aa, x15, 15);

        this.a += aa;
        this.b += bb;
        this.c += cc;
        this.d += dd;
    }

    private static int r1(int a, int b, int c, int d, int x, int s) {
        return Integer.rotateLeft(a + ((b & c) | (~b & d)) + x, s);
    }

    private static int r2(int a, int b, int c, int d, int x, int s) {
        return Integer.rotateLeft(a + ((b & c) | (b & d) | (c & d)) + x + 0x5A827999, s);
    }

    private static int r3(int a, int b, int c, int d, int x, int s) {
        return Integer.rotateLeft(a + (b ^ c ^ d) + x + 0x6ED9EBA1, s);
    }
}
