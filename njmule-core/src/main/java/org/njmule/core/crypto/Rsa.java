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
import java.security.SecureRandom;
import java.util.Objects;
import org.bouncycastle.crypto.CryptoException;
import org.bouncycastle.crypto.digests.SHA1Digest;
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator;
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters;
import org.bouncycastle.crypto.params.RSAKeyParameters;
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters;
import org.bouncycastle.crypto.signers.RSADigestSigner;

/**
 * RSA signatures with PKCS#1 v1.5 padding over SHA-1, for the secure identification of peers. The
 * network uses 384-bit keys, which the JDK refuses (its minimum is 512 bits), so the work is done
 * by Bouncy Castle. Such keys only identify a peer for credits; they protect nothing secret.
 */
public final class Rsa {

    /** Key size used on the eD2K network. */
    public static final int NETWORK_KEY_BITS = 384;

    private static final BigInteger PUBLIC_EXPONENT = BigInteger.valueOf(65_537);
    private static final int PRIME_CERTAINTY = 100;

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

        RSAKeyParameters parameters() {
            return new RSAKeyParameters(false, this.modulus, this.exponent);
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

        static PrivateKey of(RSAPrivateCrtKeyParameters k) {
            return new PrivateKey(
                    k.getModulus(),
                    k.getPublicExponent(),
                    k.getExponent(),
                    k.getP(),
                    k.getQ(),
                    k.getDP(),
                    k.getDQ(),
                    k.getQInv());
        }

        RSAPrivateCrtKeyParameters parameters() {
            return new RSAPrivateCrtKeyParameters(
                    this.modulus,
                    this.publicExponent,
                    this.privateExponent,
                    this.p,
                    this.q,
                    this.dp,
                    this.dq,
                    this.qInv);
        }

        @Override
        public String toString() {
            return "PrivateKey[" + this.modulus.bitLength() + " bits]";
        }
    }

    private Rsa() {}

    public static PrivateKey generate(int bits, SecureRandom random) {
        RSAKeyPairGenerator generator = new RSAKeyPairGenerator();
        generator.init(
                new RSAKeyGenerationParameters(PUBLIC_EXPONENT, random, bits, PRIME_CERTAINTY));
        return PrivateKey.of((RSAPrivateCrtKeyParameters) generator.generateKeyPair().getPrivate());
    }

    /** RSASSA-PKCS1-v1_5 with SHA-1; the signature has the size of the modulus. */
    public static byte[] sign(PrivateKey key, byte[] message) {
        RSADigestSigner signer = new RSADigestSigner(new SHA1Digest());
        signer.init(true, key.parameters());
        signer.update(message, 0, message.length);
        try {
            return signer.generateSignature();
        } catch (CryptoException e) {
            throw new IllegalStateException("RSA signing failed", e);
        }
    }

    public static boolean verify(PublicKey key, byte[] message, byte[] signature) {
        if (signature.length != key.sizeBytes()) {
            return false;
        }
        RSADigestSigner verifier = new RSADigestSigner(new SHA1Digest());
        verifier.init(false, key.parameters());
        verifier.update(message, 0, message.length);
        return verifier.verifySignature(signature);
    }
}
