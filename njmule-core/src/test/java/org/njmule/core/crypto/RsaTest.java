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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Optional;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.crypto.Rsa.PrivateKey;
import org.njmule.core.crypto.Rsa.PublicKey;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.security.SecureIdent;
import org.njmule.core.security.SecureIdent.IpBinding;
import org.njmule.core.security.SecureIdent.IpKind;

class RsaTest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final PrivateKey KEY = Rsa.generate(Rsa.NETWORK_KEY_BITS, RANDOM);

    @TempDir
    Path dir;

    @Test
    void networkKeysFitTheEightyBytePublicKeyLimit() throws DecodeException {
        byte[] encoded = RsaKeys.encodePublic(KEY.publicKey());

        assertThat(KEY.modulus().bitLength()).isEqualTo(384);
        assertThat(encoded.length).isLessThanOrEqualTo(RsaKeys.MAX_PUBLIC_KEY_SIZE);
        assertThat(RsaKeys.decodePublic(encoded)).isEqualTo(KEY.publicKey());
    }

    @Test
    void signaturesVerifyAndDetectTampering() {
        byte[] message = "public key + challenge".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] signature = Rsa.sign(KEY, message);

        assertThat(signature).hasSize(48);
        assertThat(Rsa.verify(KEY.publicKey(), message, signature)).isTrue();
        signature[10] ^= 1;
        assertThat(Rsa.verify(KEY.publicKey(), message, signature)).isFalse();
        assertThat(Rsa.verify(KEY.publicKey(), message, new byte[47])).isFalse();
    }

    @Test
    void interoperatesWithTheJdkOnLargerKeys() throws GeneralSecurityException, DecodeException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024, RANDOM);
        KeyPair jdk = generator.generateKeyPair();
        PrivateKey ours = RsaKeys.decodePrivate(jdk.getPrivate().getEncoded());
        PublicKey oursPublic = RsaKeys.decodePublic(jdk.getPublic().getEncoded());
        byte[] message = {1, 2, 3, 4, 5};

        Signature verifier = Signature.getInstance("SHA1withRSA");
        verifier.initVerify(jdk.getPublic());
        verifier.update(message);
        assertThat(verifier.verify(Rsa.sign(ours, message))).isTrue();

        Signature signer = Signature.getInstance("SHA1withRSA");
        signer.initSign(jdk.getPrivate());
        signer.update(message);
        assertThat(Rsa.verify(oursPublic, message, signer.sign())).isTrue();

        assertThat(RsaKeys.encodePublic(oursPublic)).isEqualTo(jdk.getPublic().getEncoded());
        assertThat(RsaKeys.encodePrivate(ours)).isEqualTo(jdk.getPrivate().getEncoded());
    }

    @Test
    void keyFilesRoundTrip() throws IOException {
        Path file = this.dir.resolve("cryptkey.dat");
        RsaKeys.writeKeyFile(file, KEY);

        assertThat(Files.readAllLines(file))
                .allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(72));
        assertThat(RsaKeys.readKeyFile(file)).isEqualTo(KEY);
    }

    @Test
    void secureIdentBindsTheChallengeAndTheAddress() {
        PrivateKey peer = Rsa.generate(Rsa.NETWORK_KEY_BITS, RANDOM);
        byte[] ourPublic = RsaKeys.encodePublic(KEY.publicKey());
        Optional<IpBinding> v2 = Optional.of(new IpBinding(ip(10, 0, 0, 1), IpKind.REMOTE_CLIENT));
        byte[] signature = SecureIdent.sign(peer, ourPublic, 0x12345678, v2);

        assertThat(SecureIdent.verify(peer.publicKey(), signature, ourPublic, 0x12345678, v2))
                .isTrue();
        assertThat(SecureIdent.verify(peer.publicKey(), signature, ourPublic, 0x12345679, v2))
                .isFalse();
        assertThat(SecureIdent.verify(peer.publicKey(), signature, ourPublic, 0x12345678, Optional.empty()))
                .isFalse();
        assertThat(SecureIdent.verify(
                        peer.publicKey(),
                        signature,
                        ourPublic,
                        0x12345678,
                        Optional.of(new IpBinding(ip(10, 0, 0, 2), IpKind.REMOTE_CLIENT))))
                .isFalse();
        assertThat(SecureIdent.message(new byte[] {7}, 0x01020304, v2)).containsExactly(7, 4, 3, 2, 1, 10, 0, 0, 1, 10);
    }

    private static Inet4Address ip(int a, int b, int c, int d) {
        try {
            return (Inet4Address) InetAddress.getByAddress(new byte[] {(byte) a, (byte) b, (byte) c, (byte) d});
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void rejectsKeysThatAreNotRsa() {
        assertThatThrownBy(() -> RsaKeys.decodePublic(new byte[] {0x30, 0x03, 0x02, 0x01, 0x00}))
                .isInstanceOf(DecodeException.class);
    }

    @Property
    void garbageKeysOnlyFailWithDecodeException(@ForAll @Size(max = 120) byte[] data) {
        try {
            RsaKeys.decodePublic(data);
        } catch (DecodeException expected) {
            // malformed
        }
        try {
            RsaKeys.decodePrivate(data);
        } catch (DecodeException expected) {
            // malformed
        }
    }
}
