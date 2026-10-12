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
import java.util.Base64;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.pkcs.RSAPrivateKey;
import org.bouncycastle.asn1.pkcs.RSAPublicKey;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory;
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory;
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
    // Bounds the DER parser input: a 4096-bit PKCS#8 key is about 2.4 KB.
    private static final int MAX_ENCODED_SIZE = 4096;
    private static final int BASE64_LINE = 72;

    private RsaKeys() {}

    public static byte[] encodePublic(PublicKey key) {
        try {
            return SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(key.parameters())
                    .getEncoded(ASN1Encoding.DER);
        } catch (IOException e) {
            throw new IllegalStateException("cannot encode an RSA public key", e);
        }
    }

    /** Decodes a SubjectPublicKeyInfo or a bare PKCS#1 RSAPublicKey. */
    public static PublicKey decodePublic(byte[] der) throws DecodeException {
        try {
            ASN1Sequence outer = sequence(der);
            RSAPublicKey rsa;
            if (outer.getObjectAt(0) instanceof ASN1Sequence) {
                SubjectPublicKeyInfo info = SubjectPublicKeyInfo.getInstance(outer);
                checkAlgorithm(info.getAlgorithm());
                rsa = RSAPublicKey.getInstance(info.parsePublicKey());
            } else {
                rsa = RSAPublicKey.getInstance(outer);
            }
            return publicKey(rsa.getModulus(), rsa.getPublicExponent());
        } catch (IOException | RuntimeException e) {
            throw malformed(e);
        }
    }

    public static byte[] encodePrivate(PrivateKey key) {
        try {
            return PrivateKeyInfoFactory.createPrivateKeyInfo(key.parameters())
                    .getEncoded(ASN1Encoding.DER);
        } catch (IOException e) {
            throw new IllegalStateException("cannot encode an RSA private key", e);
        }
    }

    /** Decodes a PKCS#8 PrivateKeyInfo or a PKCS#1 RSAPrivateKey. */
    public static PrivateKey decodePrivate(byte[] der) throws DecodeException {
        try {
            ASN1Sequence outer = sequence(der);
            RSAPrivateKey rsa;
            if (outer.size() > 1 && outer.getObjectAt(1) instanceof ASN1Sequence) {
                PrivateKeyInfo info = PrivateKeyInfo.getInstance(outer);
                checkAlgorithm(info.getPrivateKeyAlgorithm());
                rsa = RSAPrivateKey.getInstance(info.parsePrivateKey());
            } else {
                rsa = RSAPrivateKey.getInstance(outer);
            }
            if (rsa.getModulus().bitLength() > MAX_KEY_BITS) {
                throw new DecodeException("implausible RSA private key");
            }
            return new PrivateKey(
                    rsa.getModulus(),
                    rsa.getPublicExponent(),
                    rsa.getPrivateExponent(),
                    rsa.getPrime1(),
                    rsa.getPrime2(),
                    rsa.getExponent1(),
                    rsa.getExponent2(),
                    rsa.getCoefficient());
        } catch (IOException | RuntimeException e) {
            throw malformed(e);
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

    // The parser reads only from the array, so lengths inside the DER cannot make it allocate more.
    private static ASN1Sequence sequence(byte[] der) throws IOException {
        if (der.length > MAX_ENCODED_SIZE) {
            throw new DecodeException("RSA key of " + der.length + " bytes is too large");
        }
        return ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(der));
    }

    private static PublicKey publicKey(BigInteger n, BigInteger e) throws DecodeException {
        if (n.bitLength() > MAX_KEY_BITS || e.bitLength() > 64) {
            throw new DecodeException("implausible RSA public key");
        }
        return new PublicKey(n, e);
    }

    private static void checkAlgorithm(AlgorithmIdentifier algorithm) throws DecodeException {
        if (!PKCSObjectIdentifiers.rsaEncryption.equals(algorithm.getAlgorithm())) {
            throw new DecodeException("not an RSA key");
        }
    }

    private static DecodeException malformed(Exception cause) {
        return cause instanceof DecodeException d
                ? d
                : new DecodeException("malformed RSA key: " + cause.getMessage(), cause);
    }
}
