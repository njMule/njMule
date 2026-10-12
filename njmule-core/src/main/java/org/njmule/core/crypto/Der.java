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

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.Arrays;
import org.njmule.core.protocol.io.DecodeException;

/** The tiny subset of DER needed for RSA keys, with bounds checks on every length. */
final class Der {

    static final int SEQUENCE = 0x30;
    static final int INTEGER = 0x02;
    static final int BIT_STRING = 0x03;
    static final int OCTET_STRING = 0x04;
    static final int NULL = 0x05;
    static final int OID = 0x06;

    private Der() {}

    /** A cursor over DER-encoded elements. */
    static final class Reader {
        private final byte[] data;
        private int pos;
        private final int end;

        Reader(byte[] data) {
            this(data, 0, data.length);
        }

        private Reader(byte[] data, int from, int to) {
            this.data = data;
            this.pos = from;
            this.end = to;
        }

        boolean hasMore() {
            return this.pos < this.end;
        }

        int peekTag() throws DecodeException {
            if (this.pos >= this.end) {
                throw new DecodeException("DER data ended early");
            }
            return this.data[this.pos] & 0xFF;
        }

        /** The content of the next element, which must have {@code tag}. */
        Reader element(int tag) throws DecodeException {
            int actual = this.peekTag();
            if (actual != tag) {
                throw new DecodeException(
                        String.format("DER tag 0x%02X, expected 0x%02X", actual, tag));
            }
            this.pos++;
            int length = this.length();
            if (length > this.end - this.pos) {
                throw new DecodeException("DER element longer than its container");
            }
            Reader inner = new Reader(this.data, this.pos, this.pos + length);
            this.pos += length;
            return inner;
        }

        BigInteger integer() throws DecodeException {
            Reader content = this.element(INTEGER);
            if (content.end == content.pos) {
                throw new DecodeException("empty DER integer");
            }
            return new BigInteger(Arrays.copyOfRange(content.data, content.pos, content.end));
        }

        byte[] bytes() {
            return Arrays.copyOfRange(this.data, this.pos, this.end);
        }

        void expectEnd() throws DecodeException {
            if (this.pos != this.end) {
                throw new DecodeException("trailing DER data");
            }
        }

        private int length() throws DecodeException {
            if (this.pos >= this.end) {
                throw new DecodeException("DER length missing");
            }
            int first = this.data[this.pos++] & 0xFF;
            if (first < 0x80) {
                return first;
            }
            int count = first & 0x7F;
            if (count == 0 || count > 3) {
                throw new DecodeException("unsupported DER length");
            }
            int length = 0;
            for (int i = 0; i < count; i++) {
                if (this.pos >= this.end) {
                    throw new DecodeException("DER length truncated");
                }
                length = (length << 8) | (this.data[this.pos++] & 0xFF);
            }
            return length;
        }
    }

    static byte[] element(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 6);
        out.write(tag);
        int length = content.length;
        if (length < 0x80) {
            out.write(length);
        } else if (length < 0x100) {
            out.write(0x81);
            out.write(length);
        } else if (length < 0x10000) {
            out.write(0x82);
            out.write(length >>> 8);
            out.write(length);
        } else {
            out.write(0x83);
            out.write(length >>> 16);
            out.write(length >>> 8);
            out.write(length);
        }
        out.writeBytes(content);
        return out.toByteArray();
    }

    static byte[] integer(BigInteger value) {
        return element(INTEGER, value.toByteArray());
    }

    static byte[] sequence(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return element(SEQUENCE, out.toByteArray());
    }
}
