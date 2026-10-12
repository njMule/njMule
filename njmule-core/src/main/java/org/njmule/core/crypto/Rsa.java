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

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

/**
 * RSA signatures with PKCS#1 v1.5 padding over SHA-1, for the secure identification of peers. The
 * network uses 384-bit keys, which the JDK refuses (its minimum is 512 bits), so this is a small
 * implementation on {@link BigInteger}. Such keys only identify a peer for credits; they protect
 * nothing secret.
 */
public final class Rsa {

    /** Key size used on the eD2K network. */
    public static final int NETWORK_KEY_BITS = 384;

    /** DigestInfo prefix for SHA-1 (RFC 8017, section 9.2). */
    private static final byte[] SHA1_DIGEST_INFO = {
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2B, 0x0E, 0x03, 0x02, 0x1A, 0x05, 0x00, 0x04, 0x14
    };

    private static final BigInteger PUBLIC_EXPONENT = BigInteger.valueOf(65_537);

    /** A public key; {@code n} has the bit length of the key. */
    public record PublicKey(BigInteger modulus, BigInteger exponent) {
        public PublicKey {
            Objects.requireNonNull(modulus, "modulus");
            Objects.requireNonNull(exponent, "exponent");
            if (modulus.signum() <= 0 || exponent.signum() <= 0 || !modulus.testBit(0)) {
                throw new IllegalArgumentException("not an RSA public key");
            }
        }

        public int sizeBytes() {
            return (this.modulus.bitLength() + 7) / 8;
        }
    }

    /** A private key with its CRT parameters. */
    public record PrivateKey(
            BigInteger modulus,
            BigInteger publicExponent,
            BigInteger privateExponent,
            BigInteger p,
            BigInteger q,
            BigInteger dp,
            BigInteger dq,
            BigInteger qInv) {

        public PrivateKey {
            Objects.requireNonNull(modulus, "modulus");
            Objects.requireNonNull(publicExponent, "publicExponent");
            Objects.requireNonNull(privateExponent, "privateExponent");
            Objects.requireNonNull(p, "p");
            Objects.requireNonNull(q, "q");
            Objects.requireNonNull(dp, "dp");
            Objects.requireNonNull(dq, "dq");
            Objects.requireNonNull(qInv, "qInv");
            if (!p.multiply(q).equals(modulus)) {
                throw new IllegalArgumentException("inconsistent RSA private key");
            }
        }

        public PublicKey publicKey() {
            return new PublicKey(this.modulus, this.publicExponent);
        }

        @Override
        public String toString() {
            return "PrivateKey[" + this.modulus.bitLength() + " bits]";
        }
    }

    private Rsa() {}

    public static PrivateKey generate(int bits, SecureRandom random) {
        if (bits < 256 || bits % 2 != 0) {
            throw new IllegalArgumentException("unsupported key size " + bits);
        }
        while (true) {
            BigInteger p = BigInteger.probablePrime(bits / 2, random);
            BigInteger q = BigInteger.probablePrime(bits / 2, random);
            if (p.equals(q)) {
                continue;
            }
            BigInteger n = p.multiply(q);
            BigInteger phi = p.subtract(BigInteger.ONE).multiply(q.subtract(BigInteger.ONE));
            if (n.bitLength() != bits || !phi.gcd(PUBLIC_EXPONENT).equals(BigInteger.ONE)) {
                continue;
            }
            if (p.compareTo(q) < 0) {
                BigInteger t = p;
                p = q;
                q = t;
            }
            return fromFactors(p, q, PUBLIC_EXPONENT);
        }
    }

    static PrivateKey fromFactors(BigInteger p, BigInteger q, BigInteger e) {
        BigInteger n = p.multiply(q);
        BigInteger phi = p.subtract(BigInteger.ONE).multiply(q.subtract(BigInteger.ONE));
        BigInteger d = e.modInverse(phi);
        return new PrivateKey(
                n,
                e,
                d,
                p,
                q,
                d.mod(p.subtract(BigInteger.ONE)),
                d.mod(q.subtract(BigInteger.ONE)),
                q.modInverse(p));
    }

    /** RSASSA-PKCS1-v1_5 with SHA-1; the signature has the size of the modulus. */
    public static byte[] sign(PrivateKey key, byte[] message) {
        int k = (key.modulus().bitLength() + 7) / 8;
        BigInteger m = new BigInteger(1, encode(sha1(message), k));
        // CRT: m^d mod n from m^dp mod p and m^dq mod q.
        BigInteger s1 = m.modPow(key.dp(), key.p());
        BigInteger s2 = m.modPow(key.dq(), key.q());
        BigInteger h = key.qInv().multiply(s1.subtract(s2)).mod(key.p());
        BigInteger s = s2.add(h.multiply(key.q()));
        if (!s.modPow(key.publicExponent(), key.modulus()).equals(m)) {
            throw new IllegalStateException("RSA signature self-check failed");
        }
        return toFixed(s, k);
    }

    public static boolean verify(PublicKey key, byte[] message, byte[] signature) {
        int k = key.sizeBytes();
        if (signature.length != k) {
            return false;
        }
        BigInteger s = new BigInteger(1, signature);
        if (s.compareTo(key.modulus()) >= 0) {
            return false;
        }
        byte[] em = toFixed(s.modPow(key.exponent(), key.modulus()), k);
        byte[] expected = encode(sha1(message), k);
        return MessageDigest.isEqual(em, expected);
    }

    // EMSA-PKCS1-v1_5: 00 01 FF..FF 00 || DigestInfo || hash.
    private static byte[] encode(byte[] hash, int k) {
        int tLen = SHA1_DIGEST_INFO.length + hash.length;
        if (k < tLen + 11) {
            throw new IllegalArgumentException("key too short for a SHA-1 signature");
        }
        byte[] em = new byte[k];
        em[1] = 0x01;
        Arrays.fill(em, 2, k - tLen - 1, (byte) 0xFF);
        System.arraycopy(SHA1_DIGEST_INFO, 0, em, k - tLen, SHA1_DIGEST_INFO.length);
        System.arraycopy(hash, 0, em, k - hash.length, hash.length);
        return em;
    }

    static byte[] toFixed(BigInteger value, int length) {
        byte[] raw = value.toByteArray();
        if (raw.length == length) {
            return raw;
        }
        byte[] out = new byte[length];
        if (raw.length > length) {
            System.arraycopy(raw, raw.length - length, out, 0, length);
        } else {
            System.arraycopy(raw, 0, out, length - raw.length, raw.length);
        }
        return out;
    }

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required by the Java platform", e);
        }
    }
}
