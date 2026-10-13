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
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.KeyAgreement;
import javax.crypto.interfaces.DHPublicKey;
import javax.crypto.spec.DHParameterSpec;
import javax.crypto.spec.DHPublicKeySpec;
import org.bouncycastle.util.BigIntegers;
import org.njmule.core.protocol.io.DecodeException;

/**
 * Diffie-Hellman over the fixed 768-bit group of the server obfuscation (g = 2, 128-bit private exponents), on the
 * JDK's key agreement, which also rejects degenerate public values. Public values travel as 96-byte big-endian numbers.
 */
public final class Dh768 {

    public static final int SIZE = 96;

    static final BigInteger P = new BigInteger(
            1,
            HexFormat.of()
                    .parseHex("F2BF52C55F587ADD5371A936E886EB3C6217A33EC34CB40DC73A41A643AFFCE7"
                            + "21FC286366535BDBCE259F2286DA4A91B207CBAA5255D4F61CCEAED4"
                            + "5AD5E0747DF7781828105F340F762387F88B289142FB42688F05150F"
                            + "548B5F436AF70DF3"));

    private static final BigInteger G = BigInteger.TWO;
    private static final int PRIVATE_BITS = 128;
    private static final String ALGORITHM = "DH";

    private final PrivateKey privateKey;
    private final byte[] publicValue;

    private Dh768(KeyPair keys) {
        this.privateKey = keys.getPrivate();
        this.publicValue = toBytes(((DHPublicKey) keys.getPublic()).getY());
    }

    public static Dh768 generate(SecureRandom random) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM);
            generator.initialize(new DHParameterSpec(P, G, PRIVATE_BITS), random);
            return new Dh768(generator.generateKeyPair());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Diffie-Hellman unavailable", e);
        }
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
        try {
            PublicKey peer = KeyFactory.getInstance(ALGORITHM)
                    .generatePublic(new DHPublicKeySpec(new BigInteger(1, otherPublic), P, G));
            KeyAgreement agreement = KeyAgreement.getInstance(ALGORITHM);
            agreement.init(this.privateKey);
            agreement.doPhase(peer, true);
            return toBytes(new BigInteger(1, agreement.generateSecret()));
        } catch (InvalidKeyException | InvalidKeySpecException e) {
            throw new DecodeException("degenerate DH public value", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Diffie-Hellman unavailable", e);
        }
    }

    static byte[] toBytes(BigInteger value) {
        return BigIntegers.asUnsignedByteArray(SIZE, value);
    }

    @Override
    public String toString() {
        return "Dh768[public=" + HexFormat.of().formatHex(Arrays.copyOf(this.publicValue, 4)) + "…]";
    }
}
