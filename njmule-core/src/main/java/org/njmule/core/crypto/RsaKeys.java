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

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import org.njmule.core.crypto.Rsa.PrivateKey;
import org.njmule.core.crypto.Rsa.PublicKey;
import org.njmule.core.persist.AtomicFiles;
import org.njmule.core.protocol.io.DecodeException;

/**
 * DER encodings of RSA keys: public keys as X.509 SubjectPublicKeyInfo (what peers exchange, at
 * most 80 bytes for 384-bit keys), private keys as PKCS#8 (PKCS#1 is accepted too), and the Base64
 * key file {@code cryptkey.dat}.
 */
public final class RsaKeys {

    /** Largest public key a peer may send. */
    public static final int MAX_PUBLIC_KEY_SIZE = 80;

    private static final int MAX_KEY_BITS = 4096;
    private static final int BASE64_LINE = 72;

    // AlgorithmIdentifier { rsaEncryption (1.2.840.113549.1.1.1), NULL }.
    private static final byte[] RSA_ALGORITHM = {
        0x30,
        0x0D,
        0x06,
        0x09,
        0x2A,
        (byte) 0x86,
        0x48,
        (byte) 0x86,
        (byte) 0xF7,
        0x0D,
        0x01,
        0x01,
        0x01,
        0x05,
        0x00
    };

    private RsaKeys() {}

    public static byte[] encodePublic(PublicKey key) {
        byte[] rsaPublicKey = Der.sequence(Der.integer(key.modulus()), Der.integer(key.exponent()));
        byte[] bits = new byte[rsaPublicKey.length + 1];
        System.arraycopy(rsaPublicKey, 0, bits, 1, rsaPublicKey.length);
        return Der.sequence(RSA_ALGORITHM, Der.element(Der.TAG_BIT_STRING, bits));
    }

    /** Decodes a SubjectPublicKeyInfo or a bare PKCS#1 RSAPublicKey. */
    public static PublicKey decodePublic(byte[] der) throws DecodeException {
        Der.Reader outer = new Der.Reader(der).element(Der.TAG_SEQUENCE);
        PublicKey key;
        if (outer.peekTag() == Der.TAG_SEQUENCE) {
            checkAlgorithm(outer.element(Der.TAG_SEQUENCE));
            byte[] bits = outer.element(Der.TAG_BIT_STRING).bytes();
            if (bits.length < 1 || bits[0] != 0) {
                throw new DecodeException("unexpected unused bits in the public key");
            }
            Der.Reader rsa =
                    new Der.Reader(Arrays.copyOfRange(bits, 1, bits.length))
                            .element(Der.TAG_SEQUENCE);
            key = publicKey(rsa.integer(), rsa.integer());
            rsa.expectEnd();
        } else {
            key = publicKey(outer.integer(), outer.integer());
        }
        outer.expectEnd();
        return key;
    }

    public static byte[] encodePrivate(PrivateKey key) {
        byte[] pkcs1 =
                Der.sequence(
                        Der.integer(BigInteger.ZERO),
                        Der.integer(key.modulus()),
                        Der.integer(key.publicExponent()),
                        Der.integer(key.privateExponent()),
                        Der.integer(key.p()),
                        Der.integer(key.q()),
                        Der.integer(key.dp()),
                        Der.integer(key.dq()),
                        Der.integer(key.qInv()));
        return Der.sequence(
                Der.integer(BigInteger.ZERO),
                RSA_ALGORITHM,
                Der.element(Der.TAG_OCTET_STRING, pkcs1));
    }

    /** Decodes a PKCS#8 PrivateKeyInfo or a PKCS#1 RSAPrivateKey. */
    public static PrivateKey decodePrivate(byte[] der) throws DecodeException {
        Der.Reader outer = new Der.Reader(der).element(Der.TAG_SEQUENCE);
        BigInteger version = outer.integer();
        if (version.signum() != 0) {
            throw new DecodeException("unsupported private key version " + version);
        }
        Der.Reader rsa;
        if (outer.peekTag() == Der.TAG_SEQUENCE) {
            checkAlgorithm(outer.element(Der.TAG_SEQUENCE));
            rsa =
                    new Der.Reader(outer.element(Der.TAG_OCTET_STRING).bytes())
                            .element(Der.TAG_SEQUENCE);
            if (rsa.integer().signum() != 0) {
                throw new DecodeException("unsupported RSA private key version");
            }
        } else {
            rsa = outer;
        }
        BigInteger n = rsa.integer();
        BigInteger e = rsa.integer();
        BigInteger d = rsa.integer();
        BigInteger p = rsa.integer();
        BigInteger q = rsa.integer();
        BigInteger dp = rsa.integer();
        BigInteger dq = rsa.integer();
        BigInteger qInv = rsa.integer();
        if (n.bitLength() > MAX_KEY_BITS || n.signum() <= 0 || p.signum() <= 0 || q.signum() <= 0) {
            throw new DecodeException("implausible RSA private key");
        }
        try {
            return new PrivateKey(n, e, d, p, q, dp, dq, qInv);
        } catch (IllegalArgumentException ex) {
            throw new DecodeException(ex.getMessage(), ex);
        }
    }

    /** Reads a Base64 key file such as {@code cryptkey.dat}; line breaks are ignored. */
    public static PrivateKey readKeyFile(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.US_ASCII).replaceAll("\\s+", "");
        try {
            return decodePrivate(Base64.getDecoder().decode(text));
        } catch (IllegalArgumentException e) {
            throw new DecodeException("key file is not Base64: " + file.getFileName(), e);
        }
    }

    /** Writes the key as Base64 in lines of 72 characters, crash-safely. */
    public static void writeKeyFile(Path file, PrivateKey key) throws IOException {
        String base64 = Base64.getEncoder().encodeToString(encodePrivate(key));
        StringBuilder text = new StringBuilder(base64.length() + base64.length() / BASE64_LINE + 2);
        for (int i = 0; i < base64.length(); i += BASE64_LINE) {
            text.append(base64, i, Math.min(base64.length(), i + BASE64_LINE)).append('\n');
        }
        AtomicFiles.writeBytes(file, text.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private static PublicKey publicKey(BigInteger n, BigInteger e) throws DecodeException {
        if (n.bitLength() > MAX_KEY_BITS || e.bitLength() > 64) {
            throw new DecodeException("implausible RSA public key");
        }
        try {
            return new PublicKey(n, e);
        } catch (IllegalArgumentException ex) {
            throw new DecodeException(ex.getMessage(), ex);
        }
    }

    private static void checkAlgorithm(Der.Reader algorithm) throws DecodeException {
        byte[] oid = algorithm.element(Der.TAG_OID).bytes();
        byte[] expected = Arrays.copyOfRange(RSA_ALGORITHM, 4, 13);
        if (!Arrays.equals(oid, expected)) {
            throw new DecodeException("not an RSA key");
        }
        if (algorithm.hasMore()) {
            algorithm.element(Der.TAG_NULL);
        }
        algorithm.expectEnd();
    }
}
