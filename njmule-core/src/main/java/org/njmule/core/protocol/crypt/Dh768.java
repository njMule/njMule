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

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import org.njmule.core.protocol.io.DecodeException;

/**
 * Diffie-Hellman over the fixed 768-bit group of the server obfuscation (g = 2, 128-bit private
 * exponents). Public values travel as 96-byte big-endian numbers. Unlike a plain implementation,
 * degenerate public values (0, 1, p - 1 and above) are rejected.
 */
public final class Dh768 {

    public static final int SIZE = 96;

    static final BigInteger P =
            new BigInteger(
                    1,
                    HexFormat.of()
                            .parseHex(
                                    "F2BF52C55F587ADD5371A936E886EB3C6217A33EC34CB40DC73A41A643AFFCE7"
                                        + "21FC286366535BDBCE259F2286DA4A91B207CBAA5255D4F61CCEAED4"
                                        + "5AD5E0747DF7781828105F340F762387F88B289142FB42688F05150F"
                                        + "548B5F436AF70DF3"));

    private static final BigInteger G = BigInteger.TWO;
    private static final int PRIVATE_BITS = 128;

    private final BigInteger privateKey;
    private final byte[] publicValue;

    private Dh768(BigInteger privateKey) {
        this.privateKey = privateKey;
        this.publicValue = toBytes(G.modPow(privateKey, P));
    }

    public static Dh768 generate(SecureRandom random) {
        BigInteger a;
        do {
            a = new BigInteger(PRIVATE_BITS, random);
        } while (a.signum() == 0);
        return new Dh768(a);
    }

    /** {@code g^a mod p}, 96 bytes. */
    public byte[] publicValue() {
        return this.publicValue.clone();
    }

    /** {@code B^a mod p}, 96 bytes, after checking that {@code B} is a usable public value. */
    public byte[] sharedSecret(byte[] otherPublic) throws DecodeException {
        if (otherPublic.length != SIZE) {
            throw new DecodeException("DH public values have 96 bytes");
        }
        BigInteger b = new BigInteger(1, otherPublic);
        if (b.compareTo(BigInteger.ONE) <= 0 || b.compareTo(P.subtract(BigInteger.ONE)) >= 0) {
            throw new DecodeException("degenerate DH public value");
        }
        return toBytes(b.modPow(this.privateKey, P));
    }

    static byte[] toBytes(BigInteger value) {
        byte[] raw = value.toByteArray();
        if (raw.length == SIZE) {
            return raw;
        }
        byte[] out = new byte[SIZE];
        if (raw.length > SIZE) {
            // A leading sign byte.
            System.arraycopy(raw, raw.length - SIZE, out, 0, SIZE);
        } else {
            System.arraycopy(raw, 0, out, SIZE - raw.length, raw.length);
        }
        return out;
    }

    @Override
    public String toString() {
        return "Dh768[public="
                + HexFormat.of().formatHex(Arrays.copyOf(this.publicValue, 4))
                + "…]";
    }
}
