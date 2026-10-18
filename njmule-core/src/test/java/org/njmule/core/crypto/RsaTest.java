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
import static org.assertj.core.api.Assertions.catchThrowable;

import com.google.common.net.InetAddresses;
import java.io.IOException;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

    private byte[] message;
    private byte[] signature;
    private boolean accepted;
    private byte[] encoded;
    private PublicKey publicKey;
    private KeyPair jdk;
    private PrivateKey ours;
    private PublicKey oursPublic;
    private byte[] encodedPrivate;
    private PrivateKey peer;
    private byte[] ourPublic;
    private Path keyFile;
    private Throwable exception;

    @Test
    void shouldFitNetworkKeysInTheEightyBytePublicKeyLimit() throws DecodeException {
        whenTheNetworkPublicKeyIsEncoded();

        thenTheNetworkModulusHasBits(384);
        thenTheEncodedKeyFitsThePublicKeyLimit();
        thenTheEncodedKeyDecodesBackToTheNetworkPublicKey();
    }

    @Test
    void shouldMakeSignaturesThatVerify() {
        givenTheMessage("public key + challenge");

        whenTheMessageIsSignedWithTheNetworkKey();

        thenTheSignatureHasBytes(48);
        thenTheSignatureVerifiesWithTheNetworkKey();
    }

    @Test
    void shouldDetectATamperedSignature() {
        givenTheMessage("public key + challenge");
        givenItsSignatureWithTheNetworkKey();
        givenABitOfTheSignatureFlippedInByte(10);

        whenTheSignatureIsVerifiedWithTheNetworkKey();

        thenTheSignatureIsRefused();
    }

    @Test
    void shouldRefuseASignatureOfTheWrongSize() {
        givenTheMessage("public key + challenge");
        givenTheSignature(new byte[47]);

        whenTheSignatureIsVerifiedWithTheNetworkKey();

        thenTheSignatureIsRefused();
    }

    @Test
    void shouldMakeSignaturesTheJdkVerifiesOnLargerKeys() throws GeneralSecurityException, DecodeException {
        givenAJdkKeyPairOfBits(1024);
        givenTheMessage(new byte[] {1, 2, 3, 4, 5});

        whenTheMessageIsSignedWithOurCopyOfTheJdkPrivateKey();

        thenTheJdkVerifiesTheSignature();
    }

    @Test
    void shouldVerifySignaturesOfTheJdkOnLargerKeys() throws GeneralSecurityException, DecodeException {
        givenAJdkKeyPairOfBits(1024);
        givenTheMessage(new byte[] {1, 2, 3, 4, 5});
        givenTheJdkSignedTheMessage();

        whenTheSignatureIsVerifiedWithOurCopyOfTheJdkPublicKey();

        thenTheSignatureIsAccepted();
    }

    @Test
    void shouldEncodeLargerKeysAsTheJdkDoes() throws GeneralSecurityException, DecodeException {
        givenAJdkKeyPairOfBits(1024);

        whenOurCopiesOfTheJdkKeysAreEncoded();

        thenTheEncodedKeysAreTheJdkOnes();
    }

    @Test
    void shouldRoundTripKeyFiles() throws IOException {
        whenTheNetworkKeyIsWrittenToTheFile("cryptkey.dat");

        thenNoLineOfTheFileIsLongerThan(72);
        thenEveryLineButTheLastIsExactly(72);
        thenTheFileEndsWithALineEnd();
        thenTheFileReadsBackTheNetworkKey();
    }

    @Test
    void shouldAcceptASecureIdentSignatureOfTheSameChallengeAndAddress() {
        givenAPeerThatSignedOurKeyFor(0x12345678, boundTo(10, 0, 0, 1));

        whenItsSignatureIsVerifiedFor(0x12345678, boundTo(10, 0, 0, 1));

        thenTheSignatureIsAccepted();
    }

    @Test
    void shouldRefuseASecureIdentSignatureForAnotherChallenge() {
        givenAPeerThatSignedOurKeyFor(0x12345678, boundTo(10, 0, 0, 1));

        whenItsSignatureIsVerifiedFor(0x12345679, boundTo(10, 0, 0, 1));

        thenTheSignatureIsRefused();
    }

    @Test
    void shouldRefuseASecureIdentSignatureWithoutTheAddress() {
        givenAPeerThatSignedOurKeyFor(0x12345678, boundTo(10, 0, 0, 1));

        whenItsSignatureIsVerifiedFor(0x12345678, Optional.empty());

        thenTheSignatureIsRefused();
    }

    @Test
    void shouldRefuseASecureIdentSignatureForAnotherAddress() {
        givenAPeerThatSignedOurKeyFor(0x12345678, boundTo(10, 0, 0, 1));

        whenItsSignatureIsVerifiedFor(0x12345678, boundTo(10, 0, 0, 2));

        thenTheSignatureIsRefused();
    }

    @Test
    void shouldBindTheChallengeAndTheAddressInTheSecureIdentMessage() {
        whenTheSecureIdentMessageIsMadeFor(new byte[] {7}, 0x01020304, boundTo(10, 0, 0, 1));

        thenTheMessageIs(7, 4, 3, 2, 1, 10, 0, 0, 1, 10);
    }

    @Test
    void shouldBindAllSixteenBytesOfAnIpv6AddressInTheSecureIdentMessage() {
        whenTheSecureIdentMessageIsMadeFor(new byte[] {7}, 0x01020304, boundToIpv6("2001:db8::1"));

        thenTheMessageIs(7, 4, 3, 2, 1, 0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 10);
    }

    @Test
    void shouldRefuseASecureIdentSignatureForAnotherIpv6Address() {
        givenAPeerThatSignedOurKeyFor(0x12345678, boundToIpv6("2001:db8::1"));

        whenItsSignatureIsVerifiedFor(0x12345678, boundToIpv6("2001:db8::2"));

        thenTheSignatureIsRefused();
    }

    @Test
    void shouldRejectKeysThatAreNotRsa() {
        whenAPublicKeyIsDecodedFrom(new byte[] {0x30, 0x03, 0x02, 0x01, 0x00});

        thenExceptionOccurred(DecodeException.class);
    }

    @ParameterizedTest
    @MethodSource("publicKeysTheSignatureCheckCannotUse")
    void shouldRejectPublicKeysTheSignatureCheckCannotUse(BigInteger modulus, BigInteger exponent) throws IOException {
        whenAPkcs1PublicKeyIsDecoded(modulus, exponent);

        thenExceptionOccurred(DecodeException.class);
    }

    @ParameterizedTest
    @MethodSource("forgedSignatures")
    void shouldRefuseForgedSignaturesOfAnyShapeWithoutAnError(byte[] forged) {
        givenTheMessage(new byte[] {1, 2, 3});
        givenTheSignature(forged);

        whenTheSignatureIsVerifiedWithTheNetworkKey();

        thenTheSignatureIsRefused();
    }

    @Property(tries = 300)
    void shouldAlwaysVerifyWithDecodedKeysWithoutThrowing(
            @ForAll @Size(min = 1, max = 48) byte[] modulus, @ForAll @IntRange(min = 1) int exponent)
            throws IOException {
        whenAPkcs1PublicKeyIsDecoded(new BigInteger(1, modulus), BigInteger.valueOf(exponent));

        thenEitherTheKeyIsUnusableOrItRefusesAZeroSignature();
    }

    @Property
    void shouldOnlyFailWithDecodeExceptionForGarbagePublicKeys(@ForAll @Size(max = 120) byte[] data) {
        whenAPublicKeyIsDecodedFrom(data);

        thenAnyFailureIsADecodeException();
    }

    @Property
    void shouldOnlyFailWithDecodeExceptionForGarbagePrivateKeys(@ForAll @Size(max = 120) byte[] data) {
        whenAPrivateKeyIsDecodedFrom(data);

        thenAnyFailureIsADecodeException();
    }

    static Stream<Arguments> publicKeysTheSignatureCheckCannotUse() {
        return Stream.of(
                Arguments.of(KEY.modulus(), BigInteger.valueOf(65_536)),
                Arguments.of(BigInteger.valueOf(15), BigInteger.valueOf(65_537)),
                Arguments.of(BigInteger.probablePrime(384, RANDOM), BigInteger.valueOf(65_537)),
                Arguments.of(BigInteger.ONE, BigInteger.valueOf(3)));
    }

    static Stream<Arguments> forgedSignatures() {
        PublicKey key = KEY.publicKey();
        byte[] ones = new byte[key.sizeBytes()];
        Arrays.fill(ones, (byte) 0xFF);
        return Stream.of(
                Arguments.of(Named.of("all ones", ones)),
                Arguments.of(Named.of("all zeros", new byte[key.sizeBytes()])),
                Arguments.of(Named.of("one byte too long", new byte[key.sizeBytes() + 1])),
                Arguments.of(Named.of("empty", new byte[0])),
                Arguments.of(Named.of("the modulus", key.modulus().toByteArray())));
    }

    private void givenTheMessage(String text) {
        this.message = text.getBytes(StandardCharsets.US_ASCII);
    }

    private void givenTheMessage(byte[] bytes) {
        this.message = bytes;
    }

    private void givenItsSignatureWithTheNetworkKey() {
        this.signature = Rsa.sign(KEY, this.message);
    }

    private void givenABitOfTheSignatureFlippedInByte(int index) {
        this.signature[index] ^= 1;
    }

    private void givenTheSignature(byte[] bytes) {
        this.signature = bytes;
    }

    private void givenAJdkKeyPairOfBits(int bits) throws GeneralSecurityException, DecodeException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits, RANDOM);
        this.jdk = generator.generateKeyPair();
        this.ours = RsaKeys.decodePrivate(this.jdk.getPrivate().getEncoded());
        this.oursPublic = RsaKeys.decodePublic(this.jdk.getPublic().getEncoded());
    }

    private void givenTheJdkSignedTheMessage() throws GeneralSecurityException {
        Signature signer = Signature.getInstance("SHA1withRSA");
        signer.initSign(this.jdk.getPrivate());
        signer.update(this.message);
        this.signature = signer.sign();
    }

    private void givenAPeerThatSignedOurKeyFor(int challenge, Optional<IpBinding> binding) {
        this.peer = Rsa.generate(Rsa.NETWORK_KEY_BITS, RANDOM);
        this.ourPublic = RsaKeys.encodePublic(KEY.publicKey());
        this.signature = SecureIdent.sign(this.peer, this.ourPublic, challenge, binding);
    }

    private void whenTheNetworkPublicKeyIsEncoded() {
        this.encoded = RsaKeys.encodePublic(KEY.publicKey());
    }

    private void whenTheMessageIsSignedWithTheNetworkKey() {
        this.signature = Rsa.sign(KEY, this.message);
    }

    private void whenTheSignatureIsVerifiedWithTheNetworkKey() {
        this.accepted = Rsa.verify(KEY.publicKey(), this.message, this.signature);
    }

    private void whenTheMessageIsSignedWithOurCopyOfTheJdkPrivateKey() {
        this.signature = Rsa.sign(this.ours, this.message);
    }

    private void whenTheSignatureIsVerifiedWithOurCopyOfTheJdkPublicKey() {
        this.accepted = Rsa.verify(this.oursPublic, this.message, this.signature);
    }

    private void whenOurCopiesOfTheJdkKeysAreEncoded() {
        this.encoded = RsaKeys.encodePublic(this.oursPublic);
        this.encodedPrivate = RsaKeys.encodePrivate(this.ours);
    }

    private void whenTheNetworkKeyIsWrittenToTheFile(String name) throws IOException {
        this.keyFile = this.dir.resolve(name);
        RsaKeys.writeKeyFile(this.keyFile, KEY);
    }

    private void whenItsSignatureIsVerifiedFor(int challenge, Optional<IpBinding> binding) {
        this.accepted = SecureIdent.verify(this.peer.publicKey(), this.signature, this.ourPublic, challenge, binding);
    }

    private void whenTheSecureIdentMessageIsMadeFor(
            byte[] verifierPublicKey, int challenge, Optional<IpBinding> binding) {
        this.message = SecureIdent.message(verifierPublicKey, challenge, binding);
    }

    private void whenAPublicKeyIsDecodedFrom(byte[] der) {
        this.encoded = der;
        this.publicKey = null;
        this.exception = catchThrowable(() -> this.publicKey = RsaKeys.decodePublic(der));
    }

    private void whenAPkcs1PublicKeyIsDecoded(BigInteger modulus, BigInteger exponent) throws IOException {
        byte[] der = pkcs1(modulus, exponent);
        this.encoded = der;
        this.publicKey = null;
        this.exception = catchThrowable(() -> this.publicKey = RsaKeys.decodePublic(der));
    }

    private void whenAPrivateKeyIsDecodedFrom(byte[] der) {
        this.exception = catchThrowable(() -> RsaKeys.decodePrivate(der));
    }

    private void thenTheNetworkModulusHasBits(int bits) {
        assertThat(KEY.modulus().bitLength()).isEqualTo(bits);
    }

    private void thenTheEncodedKeyFitsThePublicKeyLimit() {
        assertThat(this.encoded.length).isLessThanOrEqualTo(RsaKeys.MAX_PUBLIC_KEY_SIZE);
    }

    private void thenTheEncodedKeyDecodesBackToTheNetworkPublicKey() throws DecodeException {
        assertThat(RsaKeys.decodePublic(this.encoded)).isEqualTo(KEY.publicKey());
    }

    private void thenTheSignatureHasBytes(int size) {
        assertThat(this.signature).hasSize(size);
    }

    private void thenTheSignatureVerifiesWithTheNetworkKey() {
        assertThat(Rsa.verify(KEY.publicKey(), this.message, this.signature)).isTrue();
    }

    private void thenTheSignatureIsAccepted() {
        assertThat(this.accepted).isTrue();
    }

    private void thenTheSignatureIsRefused() {
        assertThat(this.accepted).isFalse();
    }

    private void thenTheJdkVerifiesTheSignature() throws GeneralSecurityException {
        Signature verifier = Signature.getInstance("SHA1withRSA");
        verifier.initVerify(this.jdk.getPublic());
        verifier.update(this.message);
        assertThat(verifier.verify(this.signature)).isTrue();
    }

    private void thenTheEncodedKeysAreTheJdkOnes() {
        assertThat(this.encoded).isEqualTo(this.jdk.getPublic().getEncoded());
        assertThat(this.encodedPrivate).isEqualTo(this.jdk.getPrivate().getEncoded());
    }

    private void thenNoLineOfTheFileIsLongerThan(int length) throws IOException {
        assertThat(Files.readAllLines(this.keyFile))
                .allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(length));
    }

    private void thenEveryLineButTheLastIsExactly(int length) throws IOException {
        List<String> lines = Files.readAllLines(this.keyFile);
        assertThat(lines.subList(0, lines.size() - 1))
                .allSatisfy(line -> assertThat(line).hasSize(length));
    }

    private void thenTheFileEndsWithALineEnd() throws IOException {
        assertThat(Files.readString(this.keyFile)).endsWith("\n").doesNotEndWith("\n\n");
    }

    private void thenTheFileReadsBackTheNetworkKey() throws IOException {
        assertThat(RsaKeys.readKeyFile(this.keyFile)).isEqualTo(KEY);
    }

    private void thenTheMessageIs(int... expected) {
        assertThat(this.message).containsExactly(expected);
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private void thenEitherTheKeyIsUnusableOrItRefusesAZeroSignature() {
        if (this.exception == null) {
            assertThat(Rsa.verify(this.publicKey, this.encoded, new byte[this.publicKey.sizeBytes()]))
                    .isFalse();
        } else {
            assertThat(this.exception).isInstanceOf(DecodeException.class);
        }
    }

    private void thenAnyFailureIsADecodeException() {
        if (this.exception != null) {
            assertThat(this.exception).isInstanceOf(DecodeException.class);
        }
    }

    private static Optional<IpBinding> boundTo(int a, int b, int c, int d) {
        return Optional.of(new IpBinding(ip(a, b, c, d), IpKind.REMOTE_CLIENT));
    }

    private static Optional<IpBinding> boundToIpv6(String address) {
        return Optional.of(new IpBinding(InetAddresses.forString(address), IpKind.REMOTE_CLIENT));
    }

    private static Inet4Address ip(int a, int b, int c, int d) {
        try {
            return (Inet4Address) InetAddress.getByAddress(new byte[] {(byte) a, (byte) b, (byte) c, (byte) d});
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] pkcs1(BigInteger modulus, BigInteger exponent) throws IOException {
        return new org.bouncycastle.asn1.pkcs.RSAPublicKey(modulus, exponent).getEncoded();
    }
}
