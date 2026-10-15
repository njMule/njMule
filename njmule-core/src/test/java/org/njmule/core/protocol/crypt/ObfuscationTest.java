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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.njmule.core.api.Hash16;
import org.njmule.core.crypto.Rc4;
import org.njmule.core.protocol.crypt.DatagramObfuscation.Kind;
import org.njmule.core.protocol.crypt.DatagramObfuscation.Received;
import org.njmule.core.protocol.crypt.StreamObfuscation.Ciphers;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.packet.UdpFraming;

class ObfuscationTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Hash16 USER_HASH = Hash16.of(HEX.parseHex("000102030405060708090a0b0c0d0e0f"));
    private static final Hash16 KAD_ID = Hash16.of(HEX.parseHex("101112131415161718191a1b1c1d1e1f"));
    private static final byte[] SENDER_IP = {10, 0, 0, 7};
    private static final Map<String, String> GOLDEN = golden();

    private BigInteger prime;
    private Dh768 firstParty;
    private Dh768 secondParty;
    private byte[] firstSecret;
    private byte[] secondSecret;
    private byte[] keyPart;
    private byte[] secret;
    private Rc4 key;
    private byte[] request;
    private Duplex duplex;
    private Ciphers[] ciphers;
    private int marker;
    private Packet packet;
    private byte[] plain;
    private byte[] datagram;
    private Received received;
    private Throwable exception;

    @Test
    void shouldUseA768BitPrimeForTheDhGroup() {
        whenTheDhGroupPrimeIsTaken();

        thenThePrimeHasBits(768);
        thenThePrimeIsProbablyPrimeWithCertainty(64);
    }

    @Test
    void shouldGiveBothDhPartiesTheSameSharedSecret() throws DecodeException {
        givenTwoDhParties();

        whenEachPartyComputesTheSharedSecretWithTheOther();

        thenThePublicValueOfTheFirstPartyHasSize(Dh768.SIZE);
        thenBothSharedSecretsAreEqual();
    }

    @ParameterizedTest
    @MethodSource("degeneratePeerValues")
    void shouldRejectADegeneratePeerValue(byte[] peerValue) {
        givenTwoDhParties();

        whenTheFirstPartyComputesTheSharedSecretWith(peerValue);

        thenExceptionOccurred(DecodeException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {34, 203})
    void shouldDeriveTheGoldenClientKeyStream(int magic) {
        givenTheKeyPart("01234567");

        whenTheClientKeyIsDerivedWithTheMagic(magic);

        thenTheKeyStreamIsTheGoldenOne("tcp-client", magic);
    }

    @ParameterizedTest
    @ValueSource(ints = {34, 203})
    void shouldDeriveTheGoldenServerKeyStream(int magic) {
        givenTheTestSecretOfLength(96);

        whenTheServerKeyIsDerivedWithTheMagic(magic);

        thenTheKeyStreamIsTheGoldenOne("tcp-server", magic);
    }

    @Test
    void shouldStartTheClientRequestInClearAndMakeItReadableWithTheReceiverKey() {
        whenAClientConnectsToAPeerThatNeverAnswers();

        thenExceptionOccurred(DecodeException.class);
        thenTheRequestHasSize(1 + 4 + 7);
        thenTheRequestStartsWithoutATcpProtocolMarker();
        thenTheRequestBodyDecryptsTo("c46f5e83" + "00" + "00" + "00");
    }

    @Test
    void shouldCompleteTheClientHandshakeAndShareTheStream() throws IOException {
        givenTwoConnectedEndpoints();

        whenTheClientsShakeHands(USER_HASH, 128);

        thenNoExceptionOccurred();
        thenTheAcceptingSideReadsNoTcpProtocolMarker();
        thenTheConnectingSideCanSend("hello");
        thenTheAcceptingSideCanSend("answer");
    }

    @Test
    void shouldFailTheHandshakeWithAWrongUserHash() throws IOException {
        givenTwoConnectedEndpoints();

        whenTheClientsShakeHands(Hash16.of(new byte[16]), 0);

        thenExceptionOccurredWithTheRootCause(DecodeException.class);
    }

    @Test
    void shouldAgreeOnTheKeysInTheServerHandshake() throws Exception {
        givenTwoConnectedEndpoints();

        whenAClientShakesHandsWithAServer();

        thenTheConnectingSideCanSend("login");
        thenTheAcceptingSideCanSend("id");
    }

    @Test
    void shouldEncryptAClientDatagramAsTheGoldenVector() {
        givenTheGoldenPacket("udp-ed2k");

        whenItIsEncryptedForTheClient(new byte[] {(byte) 192, (byte) 168, 1, 10}, 0x5B, "3412");

        thenTheDatagramIsTheGoldenOne("udp-ed2k");
    }

    @Test
    void shouldEncryptAKadDatagramKeyedByTheReceiverIdAsTheGoldenVector() {
        givenTheGoldenPacket("udp-kad-id");

        whenItIsEncryptedForKad(Optional.of(KAD_ID), 0x11223344, 0x55667788, 0x58, "7856");

        thenTheDatagramIsTheGoldenOne("udp-kad-id");
    }

    @Test
    void shouldEncryptAKadDatagramKeyedByTheVerifyKeyAsTheGoldenVector() {
        givenTheGoldenPacket("udp-kad-verify");

        whenItIsEncryptedForKad(Optional.empty(), 0xCAFEBABE, 0x55667788, 0x5A, "7856");

        thenTheDatagramIsTheGoldenOne("udp-kad-verify");
    }

    @Test
    void shouldEncryptAServerDatagramAsTheGoldenVector() {
        givenTheGoldenPacket("udp-server");

        whenItIsEncryptedForTheServer(0xDEADBEEF, 0x42, "9abc");

        thenTheDatagramIsTheGoldenOne("udp-server");
    }

    @Test
    void shouldDecodeADatagramKeyedByTheUserHash() throws DecodeException {
        givenThePacket(Protocol.EMULE, 0x90, 16);
        givenItIsEncryptedForTheClient();

        whenTheDatagramIsDecodedByTheClientWithTheVerifyKey(7);

        thenTheDatagramKindIs(Kind.ED2K);
        thenTheDecodedPacketIsTheSentOne();
    }

    @Test
    void shouldDecodeADatagramKeyedByTheKadId() throws DecodeException {
        givenThePacket(Protocol.KAD, 0x11, 25);
        givenItIsEncryptedForKad(Optional.of(KAD_ID), 5, 6);

        whenTheDatagramIsDecodedByTheClientWithTheVerifyKey(7);

        thenTheDatagramKindIs(Kind.KAD_ID_KEY);
        thenTheDecodedPacketIsTheSentOne();
        thenTheVerifyKeysAre(5, 6);
    }

    @Test
    void shouldDecodeADatagramKeyedByTheVerifyKey() throws DecodeException {
        givenThePacket(Protocol.KAD, 0x11, 25);
        givenItIsEncryptedForKad(Optional.empty(), 7, 8);

        whenTheDatagramIsDecodedByTheClientWithTheVerifyKey(7);

        thenTheDatagramKindIs(Kind.KAD_VERIFY_KEY);
        thenTheDecodedPacketIsTheSentOne();
    }

    @Test
    void shouldDecodeAPlainDatagram() throws DecodeException {
        givenThePacket(Protocol.EMULE, 0x90, 16);
        givenItIsSentInClear();

        whenTheDatagramIsDecodedByTheClientWithTheVerifyKey(7);

        thenTheDatagramKindIs(Kind.PLAIN);
        thenTheDecodedPacketIsTheSentOne();
    }

    @Test
    void shouldAddTheObfuscationOverheadToADatagramForTheServer() {
        givenThePacket(Protocol.EDONKEY, 0x97, 8);

        whenItIsEncryptedForTheServerWithTheKey(99);

        thenTheDatagramLengthIs(UdpFraming.HEADER_SIZE + 8 + DatagramObfuscation.ED2K_OVERHEAD);
    }

    @Test
    void shouldKeyServerAnswersTheOtherWay() throws DecodeException {
        givenThePacket(Protocol.EDONKEY, 0x97, 8);
        givenTheServerEncryptsItWithTheKey(99);

        whenTheDatagramIsDecodedFromTheServerWithTheKey(99);

        thenTheDatagramKindIs(Kind.SERVER);
        thenTheDecodedPacketIsTheSentOne();
    }

    @Property
    void shouldDecodeRandomDatagramsOrFailCleanly(@ForAll @Size(min = 0, max = 80) byte[] data) {
        givenTheReceivedBytes(data);

        whenTheyAreDecodedFromAClientAndFromTheServer();

        thenTheyAreDecodedOrRefusedAsTooShort();
    }

    private static Stream<Named<byte[]>> degeneratePeerValues() {
        byte[] one = new byte[Dh768.SIZE];
        one[Dh768.SIZE - 1] = 1;
        byte[] aboveP = new byte[Dh768.SIZE];
        Arrays.fill(aboveP, (byte) 0xFF);
        return Stream.of(
                Named.of("zero", new byte[Dh768.SIZE]),
                Named.of("one", one),
                Named.of("p - 1", Dh768.toBytes(Dh768.P.subtract(BigInteger.ONE))),
                Named.of("above p", aboveP));
    }

    private void givenTwoDhParties() {
        this.firstParty = Dh768.generate(RANDOM);
        this.secondParty = Dh768.generate(RANDOM);
    }

    private void givenTheKeyPart(String hex) {
        this.keyPart = HEX.parseHex(hex);
    }

    private void givenTheTestSecretOfLength(int length) {
        this.secret = new byte[length];
        for (int i = 0; i < length; i++) {
            this.secret[i] = (byte) (3 * i + 1);
        }
    }

    private void givenTwoConnectedEndpoints() throws IOException {
        this.duplex = new Duplex();
    }

    private void givenTheGoldenPacket(String name) {
        this.plain = packet(name);
    }

    private void givenThePacket(int protocol, int opcode, int payloadSize) {
        this.packet = Packet.of(protocol, opcode, new byte[payloadSize]);
        this.plain = UdpFraming.encode(this.packet);
    }

    private void givenItIsEncryptedForTheClient() {
        this.datagram = DatagramObfuscation.encryptForClient(this.plain, USER_HASH, SENDER_IP, RANDOM);
    }

    private void givenItIsEncryptedForKad(Optional<Hash16> receiverKadId, int receiverVerifyKey, int senderVerifyKey) {
        this.datagram = DatagramObfuscation.encryptForKad(
                this.plain, receiverKadId, receiverVerifyKey, senderVerifyKey, RANDOM);
    }

    private void givenItIsSentInClear() {
        this.datagram = this.plain;
    }

    private void givenTheServerEncryptsItWithTheKey(int serverKey) {
        byte[] material = ByteBuffer.allocate(7)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(serverKey)
                .put((byte) DatagramObfuscation.MAGIC_FROM_SERVER)
                .put((byte) 1)
                .put((byte) 2)
                .array();
        Rc4 fromServer = new Rc4(StreamObfuscation.md5(material));
        byte[] body = new byte[5 + this.plain.length];
        body[0] = (byte) 0xD5;
        body[1] = 0x24;
        body[2] = (byte) 0xEF;
        body[3] = 0x13;
        System.arraycopy(this.plain, 0, body, 5, this.plain.length);
        fromServer.apply(body, 0, body.length);
        this.datagram = new byte[3 + body.length];
        this.datagram[0] = 0x42;
        this.datagram[1] = 1;
        this.datagram[2] = 2;
        System.arraycopy(body, 0, this.datagram, 3, body.length);
    }

    private void givenTheReceivedBytes(byte[] data) {
        this.datagram = data;
    }

    private void whenTheDhGroupPrimeIsTaken() {
        this.prime = Dh768.P;
    }

    private void whenEachPartyComputesTheSharedSecretWithTheOther() throws DecodeException {
        this.firstSecret = this.firstParty.sharedSecret(this.secondParty.publicValue());
        this.secondSecret = this.secondParty.sharedSecret(this.firstParty.publicValue());
    }

    private void whenTheFirstPartyComputesTheSharedSecretWith(byte[] peerValue) {
        this.exception = catchThrowable(() -> this.firstParty.sharedSecret(peerValue));
    }

    private void whenTheClientKeyIsDerivedWithTheMagic(int magic) {
        this.key = StreamObfuscation.clientKey(USER_HASH, magic, this.keyPart);
    }

    private void whenTheServerKeyIsDerivedWithTheMagic(int magic) {
        this.key = StreamObfuscation.serverKey(this.secret, magic);
    }

    private void whenAClientConnectsToAPeerThatNeverAnswers() {
        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        // The answer never comes: only the request matters here.
        this.exception = catchThrowable(() ->
                StreamObfuscation.connectToClient(new ByteArrayInputStream(new byte[0]), sent, USER_HASH, RANDOM, 0));
        this.request = sent.toByteArray();
    }

    private void whenTheClientsShakeHands(Hash16 connectingUserHash, int padding) {
        Duplex pipes = this.duplex;
        this.exception = catchThrowable(() -> this.ciphers = pipes.run(
                () -> StreamObfuscation.connectToClient(pipes.aIn, pipes.aOut, connectingUserHash, RANDOM, padding),
                () -> {
                    this.marker = pipes.bIn.read();
                    try {
                        return StreamObfuscation.acceptFromClient(pipes.bIn, pipes.bOut, USER_HASH, RANDOM, padding);
                    } finally {
                        // Lets the connecting side fail instead of waiting for an answer that never comes.
                        pipes.bOut.close();
                    }
                }));
    }

    private void whenAClientShakesHandsWithAServer() throws Exception {
        Duplex pipes = this.duplex;
        this.ciphers = pipes.run(
                () -> {
                    Ciphers c = StreamObfuscation.connectToServer(pipes.aIn, pipes.aOut, RANDOM);
                    pipes.aOut.flush();
                    return c;
                },
                () -> {
                    pipes.bIn.read();
                    return StreamObfuscation.acceptAsServer(pipes.bIn, pipes.bOut, RANDOM);
                });
    }

    private void whenItIsEncryptedForTheClient(byte[] senderIp, int marker, String keyPartHex) {
        this.datagram =
                DatagramObfuscation.encryptForClient(this.plain, USER_HASH, senderIp, marker, HEX.parseHex(keyPartHex));
    }

    private void whenItIsEncryptedForKad(
            Optional<Hash16> receiverKadId, int receiverVerifyKey, int senderVerifyKey, int marker, String keyPartHex) {
        this.datagram = DatagramObfuscation.encryptForKad(
                this.plain, receiverKadId, receiverVerifyKey, senderVerifyKey, marker, HEX.parseHex(keyPartHex));
    }

    private void whenItIsEncryptedForTheServer(int serverKey, int marker, String keyPartHex) {
        this.datagram = DatagramObfuscation.encryptForServer(this.plain, serverKey, marker, HEX.parseHex(keyPartHex));
    }

    private void whenItIsEncryptedForTheServerWithTheKey(int serverKey) {
        this.datagram = DatagramObfuscation.encryptForServer(this.plain, serverKey, RANDOM);
    }

    private void whenTheDatagramIsDecodedByTheClientWithTheVerifyKey(int verifyKey) throws DecodeException {
        this.received = fromClient(this.datagram, SENDER_IP, KAD_ID, verifyKey);
    }

    private void whenTheDatagramIsDecodedFromTheServerWithTheKey(int serverKey) throws DecodeException {
        this.received = DatagramObfuscation.decodeFromServer(this.datagram, 0, this.datagram.length, serverKey);
    }

    private void whenTheyAreDecodedFromAClientAndFromTheServer() {
        byte[] data = this.datagram;
        this.exception = catchThrowable(() -> {
            DatagramObfuscation.decodeFromClient(
                    data, 0, data.length, USER_HASH, new byte[4], Optional.of(USER_HASH), 1);
            DatagramObfuscation.decodeFromServer(data, 0, data.length, 1);
        });
    }

    private void thenThePrimeHasBits(int bits) {
        assertThat(this.prime.bitLength()).isEqualTo(bits);
    }

    private void thenThePrimeIsProbablyPrimeWithCertainty(int certainty) {
        assertThat(this.prime.isProbablePrime(certainty)).isTrue();
    }

    private void thenThePublicValueOfTheFirstPartyHasSize(int size) {
        assertThat(this.firstParty.publicValue()).hasSize(size);
    }

    private void thenBothSharedSecretsAreEqual() {
        assertThat(this.firstSecret).isEqualTo(this.secondSecret);
    }

    private void thenExceptionOccurred(Class<? extends Exception> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private void thenExceptionOccurredWithTheRootCause(Class<? extends Exception> type) {
        assertThat(this.exception).hasRootCauseInstanceOf(type);
    }

    private void thenNoExceptionOccurred() {
        assertThat(this.exception).isNull();
    }

    private void thenTheKeyStreamIsTheGoldenOne(String kind, int magic) {
        assertThat(keyStream(this.key)).isEqualTo(GOLDEN.get(kind + " magic=" + magic));
    }

    private void thenTheRequestHasSize(int size) {
        assertThat(this.request).hasSize(size);
    }

    private void thenTheRequestStartsWithoutATcpProtocolMarker() {
        assertThat(Protocol.isTcp(this.request[0] & 0xFF)).isFalse();
    }

    private void thenTheRequestBodyDecryptsTo(String hex) {
        byte[] requestKeyPart = Arrays.copyOfRange(this.request, 1, 5);
        byte[] body = Arrays.copyOfRange(this.request, 5, this.request.length);
        StreamObfuscation.clientKey(USER_HASH, StreamObfuscation.MAGIC_REQUESTER, requestKeyPart)
                .apply(body, 0, body.length);
        assertThat(HEX.formatHex(body)).isEqualTo(hex);
    }

    private void thenTheAcceptingSideReadsNoTcpProtocolMarker() {
        assertThat(Protocol.isTcp(this.marker)).isFalse();
    }

    private void thenTheConnectingSideCanSend(String text) {
        assertThat(roundTrip(this.ciphers[0].send(), this.ciphers[1].receive(), text))
                .isEqualTo(text);
    }

    private void thenTheAcceptingSideCanSend(String text) {
        assertThat(roundTrip(this.ciphers[1].send(), this.ciphers[0].receive(), text))
                .isEqualTo(text);
    }

    private void thenTheDatagramIsTheGoldenOne(String name) {
        assertThat(HEX.formatHex(this.datagram)).isEqualTo(datagram(name));
    }

    private void thenTheDatagramKindIs(Kind kind) {
        assertThat(this.received.kind()).isEqualTo(kind);
    }

    private void thenTheDecodedPacketIsTheSentOne() {
        assertThat(this.received.packet()).isEqualTo(this.packet);
    }

    private void thenTheVerifyKeysAre(int receiverVerifyKey, int senderVerifyKey) {
        assertThat(this.received.receiverVerifyKey()).isEqualTo(receiverVerifyKey);
        assertThat(this.received.senderVerifyKey()).isEqualTo(senderVerifyKey);
    }

    private void thenTheDatagramLengthIs(int length) {
        assertThat(this.datagram.length).isEqualTo(length);
    }

    private void thenTheyAreDecodedOrRefusedAsTooShort() {
        if (this.exception != null) {
            assertThat(this.exception).isInstanceOf(DecodeException.class);
        }
    }

    private static Received fromClient(byte[] datagram, byte[] ip, Hash16 kadId, int verifyKey) throws DecodeException {
        return DatagramObfuscation.decodeFromClient(
                datagram, 0, datagram.length, USER_HASH, ip, Optional.of(kadId), verifyKey);
    }

    private static String roundTrip(Rc4 send, Rc4 receive, String text) {
        byte[] data = text.getBytes(StandardCharsets.US_ASCII);
        send.apply(data, 0, data.length);
        receive.apply(data, 0, data.length);
        return new String(data, StandardCharsets.US_ASCII);
    }

    private static String keyStream(Rc4 rc4) {
        byte[] out = new byte[16];
        rc4.apply(out, 0, 16);
        return HEX.formatHex(out);
    }

    private static byte[] packet(String name) {
        return HEX.parseHex(GOLDEN.get(name + " packet"));
    }

    private static String datagram(String name) {
        return GOLDEN.get(name + " datagram");
    }

    private static Map<String, String> golden() {
        Map<String, String> map = new HashMap<>();
        try (InputStream in =
                        Objects.requireNonNull(ObfuscationTest.class.getResourceAsStream("/golden/obfuscation.txt"));
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII))) {
            reader.lines()
                    .filter(line -> !line.startsWith("#") && !line.isBlank())
                    .forEach(line -> {
                        String[] parts = line.split(" ", -1);
                        if (parts[0].startsWith("tcp")) {
                            map.put(parts[0] + " " + parts[1], parts[2].substring("keystream=".length()));
                        } else {
                            map.put(parts[0] + " packet", parts[1].substring("packet=".length()));
                            map.put(parts[0] + " datagram", parts[2].substring("datagram=".length()));
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return map;
    }

    /** Two connected endpoints, each side run on its own virtual thread. */
    private static final class Duplex {
        final PipedInputStream aIn = new PipedInputStream(1 << 16);
        final PipedInputStream bIn = new PipedInputStream(1 << 16);
        final PipedOutputStream aOut;
        final PipedOutputStream bOut;

        Duplex() throws IOException {
            this.aOut = new PipedOutputStream(this.bIn);
            this.bOut = new PipedOutputStream(this.aIn);
        }

        Ciphers[] run(Callable<Ciphers> a, Callable<Ciphers> b) throws Exception {
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<Ciphers> fa = pool.submit(a);
                Future<Ciphers> fb = pool.submit(b);
                return new Ciphers[] {fa.get(10, TimeUnit.SECONDS), fb.get(10, TimeUnit.SECONDS)};
            }
        }
    }
}
