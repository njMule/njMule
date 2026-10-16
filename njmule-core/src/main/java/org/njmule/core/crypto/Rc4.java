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

import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.SecretKeySpec;

/**
 * RC4 key stream of the eD2K protocol obfuscation (not for security), on the JDK's {@code ARCFOUR} cipher. Not
 * thread-safe: each direction of a connection owns one.
 */
public final class Rc4 {

    /** Bytes of key stream discarded by the TCP obfuscation. */
    public static final int TCP_DISCARD = 1024;

    private static final String ALGORITHM = "ARCFOUR";
    private static final int SKIP_CHUNK = 4096;

    private final Cipher cipher;

    /** {@code key} has 5 to 128 bytes, the range the JDK accepts. */
    public Rc4(byte[] key) {
        this(key, 0, key.length);
    }

    public Rc4(byte[] key, int offset, int length) {
        try {
            this.cipher = Cipher.getInstance(ALGORITHM);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("cannot set up RC4: " + e.getMessage(), e);
        }
        rekey(key, offset, length);
    }

    /** Starts the key stream again from {@code key}, as a new instance would, without looking the cipher up again. */
    public void rekey(byte[] key) {
        rekey(key, 0, key.length);
    }

    private void rekey(byte[] key, int offset, int length) {
        try {
            this.cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, offset, length, ALGORITHM));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("cannot set up RC4: " + e.getMessage(), e);
        }
    }

    /** XORs the key stream into {@code data} in place. */
    public void apply(byte[] data, int offset, int length) {
        apply(data, offset, data, offset, length);
    }

    public void apply(byte[] in, int inOffset, byte[] out, int outOffset, int length) {
        try {
            this.cipher.update(in, inOffset, length, out, outOffset);
        } catch (ShortBufferException e) {
            throw new IllegalArgumentException("output buffer too small", e);
        }
    }

    /** Advances the key stream by {@code length} bytes. */
    public void skip(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("negative skip " + length);
        }
        byte[] scratch = new byte[Math.min(length, SKIP_CHUNK)];
        for (int left = length; left > 0; left -= scratch.length) {
            apply(scratch, 0, Math.min(left, scratch.length));
        }
    }

    /** The underlying cipher, for {@link javax.crypto.CipherInputStream} and its output twin. */
    public Cipher cipher() {
        return this.cipher;
    }
}
