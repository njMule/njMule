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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
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
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
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
    private static final Map<String, String> GOLDEN = golden();

    @Test
    void dhGroupIsA768BitPrime() {
        assertThat(Dh768.P.bitLength()).isEqualTo(768);
        assertThat(Dh768.P.isProbablePrime(64)).isTrue();
    }

    @Test
    void dhPartiesAgreeAndDegenerateValuesAreRejected() throws DecodeException {
        Dh768 a = Dh768.generate(RANDOM);
        Dh768 b = Dh768.generate(RANDOM);

        assertThat(a.publicValue()).hasSize(Dh768.SIZE);
        assertThat(a.sharedSecret(b.publicValue())).isEqualTo(b.sharedSecret(a.publicValue()));
        assertThatThrownBy(() -> a.sharedSecret(new byte[Dh768.SIZE])).isInstanceOf(DecodeException.class);
        byte[] one = new byte[Dh768.SIZE];
        one[Dh768.SIZE - 1] = 1;
        assertThatThrownBy(() -> a.sharedSecret(one)).isInstanceOf(DecodeException.class);
        byte[] pMinusOne = Dh768.toBytes(Dh768.P.subtract(java.math.BigInteger.ONE));
        assertThatThrownBy(() -> a.sharedSecret(pMinusOne)).isInstanceOf(DecodeException.class);
        byte[] aboveP = new byte[Dh768.SIZE];
        java.util.Arrays.fill(aboveP, (byte) 0xFF);
        assertThatThrownBy(() -> a.sharedSecret(aboveP)).isInstanceOf(DecodeException.class);
    }

    @Test
    void tcpKeysMatchTheGoldenKeyStreams() {
        byte[] keyPart = HEX.parseHex("01234567");
        assertThat(keyStream(StreamObfuscation.clientKey(USER_HASH, 34, keyPart)))
                .isEqualTo(GOLDEN.get("tcp-client magic=34"));
        assertThat(keyStream(StreamObfuscation.clientKey(USER_HASH, 203, keyPart)))
                .isEqualTo(GOLDEN.get("tcp-client magic=203"));

        byte[] secret = new byte[96];
        for (int i = 0; i < 96; i++) {
            secret[i] = (byte) (3 * i + 1);
        }
        assertThat(keyStream(StreamObfuscation.serverKey(secret, 34))).isEqualTo(GOLDEN.get("tcp-server magic=34"));
        assertThat(keyStream(StreamObfuscation.serverKey(secret, 203))).isEqualTo(GOLDEN.get("tcp-server magic=203"));
    }

    @Test
    void clientRequestStartsInClearAndIsReadableWithTheReceiverKey() throws Exception {
        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        // The answer never comes: only the request matters here.
        assertThatThrownBy(() -> StreamObfuscation.connectToClient(
                        new ByteArrayInputStream(new byte[0]), sent, USER_HASH, RANDOM, 0))
                .isInstanceOf(DecodeException.class);
        byte[] request = sent.toByteArray();

        assertThat(request).hasSize(1 + 4 + 7);
        assertThat(Protocol.isTcp(request[0] & 0xFF)).isFalse();
        byte[] keyPart = java.util.Arrays.copyOfRange(request, 1, 5);
        byte[] body = java.util.Arrays.copyOfRange(request, 5, request.length);
        StreamObfuscation.clientKey(USER_HASH, StreamObfuscation.MAGIC_REQUESTER, keyPart)
                .apply(body, 0, body.length);
        assertThat(HEX.formatHex(body)).isEqualTo("c46f5e83" + "00" + "00" + "00");
    }

    @Test
    void clientsCompleteTheHandshakeAndShareTheStream() throws Exception {
        Duplex duplex = new Duplex();
        Ciphers[] result = duplex.run(
                () -> StreamObfuscation.connectToClient(duplex.aIn, duplex.aOut, USER_HASH, RANDOM, 128), () -> {
                    int marker = duplex.bIn.read();
                    assertThat(Protocol.isTcp(marker)).isFalse();
                    return StreamObfuscation.acceptFromClient(duplex.bIn, duplex.bOut, USER_HASH, RANDOM, 128);
                });

        assertThat(roundTrip(result[0].send(), result[1].receive(), "hello")).isEqualTo("hello");
        assertThat(roundTrip(result[1].send(), result[0].receive(), "answer")).isEqualTo("answer");
    }

    @Test
    void aWrongUserHashFailsTheHandshake() throws Exception {
        Duplex duplex = new Duplex();
        Hash16 other = Hash16.of(new byte[16]);

        assertThatThrownBy(() -> duplex.run(
                        () -> StreamObfuscation.connectToClient(duplex.aIn, duplex.aOut, other, RANDOM, 0), () -> {
                            duplex.bIn.read();
                            try {
                                return StreamObfuscation.acceptFromClient(
                                        duplex.bIn, duplex.bOut, USER_HASH, RANDOM, 0);
                            } finally {
                                duplex.bOut.close();
                            }
                        }))
                .hasRootCauseInstanceOf(DecodeException.class);
    }

    @Test
    void serverHandshakeAgreesOnKeys() throws Exception {
        Duplex duplex = new Duplex();
        Ciphers[] result = duplex.run(
                () -> {
                    Ciphers c = StreamObfuscation.connectToServer(duplex.aIn, duplex.aOut, RANDOM);
                    duplex.aOut.flush();
                    return c;
                },
                () -> {
                    duplex.bIn.read();
                    return StreamObfuscation.acceptAsServer(duplex.bIn, duplex.bOut, RANDOM);
                });

        assertThat(roundTrip(result[0].send(), result[1].receive(), "login")).isEqualTo("login");
        assertThat(roundTrip(result[1].send(), result[0].receive(), "id")).isEqualTo("id");
    }

    @Test
    void datagramsMatchTheGoldenVectors() {
        byte[] ip = {(byte) 192, (byte) 168, 1, 10};
        assertThat(HEX.formatHex(DatagramObfuscation.encryptForClient(
                        packet("udp-ed2k"), USER_HASH, ip, 0x5B, HEX.parseHex("3412"))))
                .isEqualTo(datagram("udp-ed2k"));

        Hash16 kadId = Hash16.of(HEX.parseHex("101112131415161718191a1b1c1d1e1f"));
        assertThat(HEX.formatHex(DatagramObfuscation.encryptForKad(
                        packet("udp-kad-id"), Optional.of(kadId), 0x11223344, 0x55667788, 0x58, HEX.parseHex("7856"))))
                .isEqualTo(datagram("udp-kad-id"));
        assertThat(HEX.formatHex(DatagramObfuscation.encryptForKad(
                        packet("udp-kad-verify"),
                        Optional.empty(),
                        0xCAFEBABE,
                        0x55667788,
                        0x5A,
                        HEX.parseHex("7856"))))
                .isEqualTo(datagram("udp-kad-verify"));
        assertThat(HEX.formatHex(DatagramObfuscation.encryptForServer(
                        packet("udp-server"), 0xDEADBEEF, 0x42, HEX.parseHex("9abc"))))
                .isEqualTo(datagram("udp-server"));
    }

    @Test
    void datagramsDecodeWithEveryKeyKind() throws DecodeException {
        byte[] ip = {10, 0, 0, 7};
        Hash16 kadId = Hash16.of(HEX.parseHex("101112131415161718191a1b1c1d1e1f"));
        Packet ping = Packet.of(Protocol.EMULE, 0x90, new byte[16]);
        Packet hello = Packet.of(Protocol.KAD, 0x11, new byte[25]);
        byte[] plainPing = UdpFraming.encode(ping);
        byte[] plainHello = UdpFraming.encode(hello);

        Received ed2k =
                fromClient(DatagramObfuscation.encryptForClient(plainPing, USER_HASH, ip, RANDOM), ip, kadId, 7);
        assertThat(ed2k.kind()).isEqualTo(Kind.ED2K);
        assertThat(ed2k.packet()).isEqualTo(ping);

        Received byId = fromClient(
                DatagramObfuscation.encryptForKad(plainHello, Optional.of(kadId), 5, 6, RANDOM), ip, kadId, 7);
        assertThat(byId.kind()).isEqualTo(Kind.KAD_ID_KEY);
        assertThat(byId.packet()).isEqualTo(hello);
        assertThat(byId.receiverVerifyKey()).isEqualTo(5);
        assertThat(byId.senderVerifyKey()).isEqualTo(6);

        Received byKey =
                fromClient(DatagramObfuscation.encryptForKad(plainHello, Optional.empty(), 7, 8, RANDOM), ip, kadId, 7);
        assertThat(byKey.kind()).isEqualTo(Kind.KAD_VERIFY_KEY);
        assertThat(byKey.packet()).isEqualTo(hello);

        Received plain = fromClient(plainPing, ip, kadId, 7);
        assertThat(plain.kind()).isEqualTo(Kind.PLAIN);
        assertThat(plain.packet()).isEqualTo(ping);

        Packet status = Packet.of(Protocol.EDONKEY, 0x97, new byte[8]);
        byte[] toServer = DatagramObfuscation.encryptForServer(UdpFraming.encode(status), 99, RANDOM);
        assertThat(toServer.length).isEqualTo(UdpFraming.HEADER_SIZE + 8 + DatagramObfuscation.ED2K_OVERHEAD);
    }

    @Test
    void serverAnswersAreKeyedTheOtherWay() throws DecodeException {
        Packet status = Packet.of(Protocol.EDONKEY, 0x97, new byte[8]);
        byte[] material = new byte[] {99, 0, 0, 0, (byte) DatagramObfuscation.MAGIC_FROM_SERVER, 1, 2};
        Rc4 fromServer = new Rc4(StreamObfuscation.md5(material));
        byte[] body = new byte[5 + 10];
        body[0] = (byte) 0xD5;
        body[1] = 0x24;
        body[2] = (byte) 0xEF;
        body[3] = 0x13;
        System.arraycopy(UdpFraming.encode(status), 0, body, 5, 10);
        fromServer.apply(body, 0, body.length);
        byte[] datagram = new byte[3 + body.length];
        datagram[0] = 0x42;
        datagram[1] = 1;
        datagram[2] = 2;
        System.arraycopy(body, 0, datagram, 3, body.length);

        Received received = DatagramObfuscation.decodeFromServer(datagram, 0, datagram.length, 99);
        assertThat(received.kind()).isEqualTo(Kind.SERVER);
        assertThat(received.packet()).isEqualTo(status);
    }

    @Property
    void randomDatagramsDecodeOrFailCleanly(@ForAll @Size(min = 0, max = 80) byte[] data) {
        try {
            DatagramObfuscation.decodeFromClient(
                    data, 0, data.length, USER_HASH, new byte[4], Optional.of(USER_HASH), 1);
            DatagramObfuscation.decodeFromServer(data, 0, data.length, 1);
        } catch (DecodeException expected) {
            // too short to be a packet
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
