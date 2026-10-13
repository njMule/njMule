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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import org.njmule.core.api.Hash16;
import org.njmule.core.crypto.Rc4;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.packet.Protocol;

/**
 * The obfuscated TCP handshakes (protocol obfuscation, not security). Each one runs in blocking style on the
 * connection's virtual thread and yields the RC4 ciphers for the rest of the stream.
 *
 * <ul>
 *   <li>Client to client: keys {@code MD5(<receiver user hash><34|203><random part>)}; the caller sends
 *       {@code <marker><random part 4>} in clear, then encrypted {@code <sync 4><methods supported 1><method preferred
 *       1><padding length 1><padding>}; the receiver answers {@code <sync 4><method selected 1><padding length
 *       1><padding>}.
 *   <li>Client to server: Diffie-Hellman ({@link Dh768}), keys {@code MD5(<secret 96><34|203>)}; the client sends
 *       {@code <marker><g^a 96><padding length 0-15><padding>} in clear, the server answers {@code <g^b 96>} and the
 *       encrypted negotiation, the client confirms.
 * </ul>
 */
public final class StreamObfuscation {

    /** The RC4 states for each direction once the handshake is complete. */
    public record Ciphers(Rc4 send, Rc4 receive) {}

    static final int MAGIC_REQUESTER = 34;
    static final int MAGIC_SERVER = 203;
    static final int SYNC = 0x835E6FC4;

    /** The only method: plain obfuscation. */
    static final int METHOD_OBFUSCATION = 0;

    private static final int MAX_SERVER_PADDING = 16;

    private StreamObfuscation() {}

    /** Outgoing connection to a client whose user hash is known. */
    public static Ciphers connectToClient(
            InputStream in, OutputStream out, Hash16 receiverUserHash, SecureRandom random, int maxPadding)
            throws IOException {
        byte[] keyPart = new byte[4];
        random.nextBytes(keyPart);
        Rc4 send = clientKey(receiverUserHash, MAGIC_REQUESTER, keyPart);
        Rc4 receive = clientKey(receiverUserHash, MAGIC_SERVER, keyPart);

        byte[] tail = negotiation(random, maxPadding);
        send.apply(tail, 0, tail.length);
        byte[] request = new byte[5 + tail.length];
        request[0] = (byte) marker(random);
        System.arraycopy(keyPart, 0, request, 1, 4);
        System.arraycopy(tail, 0, request, 5, tail.length);
        out.write(request);
        out.flush();

        byte[] answer = readDecrypted(in, receive, 6);
        checkSync(answer);
        if ((answer[4] & 0xFF) != METHOD_OBFUSCATION) {
            throw new DecodeException("peer selected unsupported obfuscation method " + answer[4]);
        }
        skipDecrypted(in, receive, answer[5] & 0xFF);
        return new Ciphers(send, receive);
    }

    /**
     * Incoming connection whose first byte ({@code marker}, already consumed) is not a protocol byte. The keys derive
     * from our own user hash.
     */
    public static Ciphers acceptFromClient(
            InputStream in, OutputStream out, Hash16 ownUserHash, SecureRandom random, int maxPadding)
            throws IOException {
        byte[] keyPart = readFully(in, 4);
        Rc4 receive = clientKey(ownUserHash, MAGIC_REQUESTER, keyPart);
        Rc4 send = clientKey(ownUserHash, MAGIC_SERVER, keyPart);

        byte[] request = readDecrypted(in, receive, 7);
        checkSync(request);
        skipDecrypted(in, receive, request[6] & 0xFF);

        byte[] answer = new byte[6 + paddingLength(random, maxPadding)];
        putInt(answer, 0, SYNC);
        answer[4] = METHOD_OBFUSCATION;
        answer[5] = (byte) (answer.length - 6);
        fillPadding(random, answer, 6);
        send.apply(answer, 0, answer.length);
        out.write(answer);
        out.flush();
        return new Ciphers(send, receive);
    }

    /**
     * Outgoing connection to a server. The final confirmation is written but not flushed, so that it leaves together
     * with the first packet.
     */
    public static Ciphers connectToServer(InputStream in, OutputStream out, SecureRandom random) throws IOException {
        Dh768 dh = Dh768.generate(random);
        int padding = random.nextInt(MAX_SERVER_PADDING);
        byte[] request = new byte[1 + Dh768.SIZE + 1 + padding];
        request[0] = (byte) marker(random);
        System.arraycopy(dh.publicValue(), 0, request, 1, Dh768.SIZE);
        request[1 + Dh768.SIZE] = (byte) padding;
        fillPadding(random, request, 2 + Dh768.SIZE);
        out.write(request);
        out.flush();

        byte[] secret = dh.sharedSecret(readFully(in, Dh768.SIZE));
        Rc4 send = serverKey(secret, MAGIC_REQUESTER);
        Rc4 receive = serverKey(secret, MAGIC_SERVER);
        byte[] answer = readDecrypted(in, receive, 7);
        checkSync(answer);
        skipDecrypted(in, receive, answer[6] & 0xFF);

        byte[] confirm = new byte[6 + random.nextInt(MAX_SERVER_PADDING)];
        putInt(confirm, 0, SYNC);
        confirm[4] = METHOD_OBFUSCATION;
        confirm[5] = (byte) (confirm.length - 6);
        fillPadding(random, confirm, 6);
        send.apply(confirm, 0, confirm.length);
        out.write(confirm);
        return new Ciphers(send, receive);
    }

    /** The server side of {@link #connectToServer}, after the marker byte; used by test servers. */
    public static Ciphers acceptAsServer(InputStream in, OutputStream out, SecureRandom random) throws IOException {
        byte[] clientPublic = readFully(in, Dh768.SIZE);
        readFully(in, readFully(in, 1)[0] & 0xFF);
        Dh768 dh = Dh768.generate(random);
        byte[] secret = dh.sharedSecret(clientPublic);
        Rc4 receive = serverKey(secret, MAGIC_REQUESTER);
        Rc4 send = serverKey(secret, MAGIC_SERVER);

        byte[] tail = negotiation(random, MAX_SERVER_PADDING - 1);
        send.apply(tail, 0, tail.length);
        byte[] answer = new byte[Dh768.SIZE + tail.length];
        System.arraycopy(dh.publicValue(), 0, answer, 0, Dh768.SIZE);
        System.arraycopy(tail, 0, answer, Dh768.SIZE, tail.length);
        out.write(answer);
        out.flush();

        byte[] confirm = readDecrypted(in, receive, 6);
        checkSync(confirm);
        skipDecrypted(in, receive, confirm[5] & 0xFF);
        return new Ciphers(send, receive);
    }

    /** A random first byte that cannot be mistaken for a plain TCP protocol byte. */
    static int marker(SecureRandom random) {
        int b;
        do {
            b = random.nextInt(256);
        } while (Protocol.isTcp(b));
        return b;
    }

    static Rc4 clientKey(Hash16 userHash, int magic, byte[] keyPart) {
        byte[] material = new byte[Hash16.LENGTH + 1 + 4];
        userHash.copyTo(material, 0);
        material[Hash16.LENGTH] = (byte) magic;
        System.arraycopy(keyPart, 0, material, Hash16.LENGTH + 1, 4);
        return tcpCipher(md5(material));
    }

    static Rc4 serverKey(byte[] secret, int magic) {
        byte[] material = new byte[Dh768.SIZE + 1];
        System.arraycopy(secret, 0, material, 0, Dh768.SIZE);
        material[Dh768.SIZE] = (byte) magic;
        return tcpCipher(md5(material));
    }

    private static Rc4 tcpCipher(byte[] key) {
        Rc4 rc4 = new Rc4(key);
        rc4.skip(Rc4.TCP_DISCARD);
        return rc4;
    }

    static byte[] md5(byte[] data) {
        try {
            return MessageDigest.getInstance("MD5").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is required by the Java platform", e);
        }
    }

    // <sync><methods supported><method preferred><padding length><padding>, not yet encrypted.
    private static byte[] negotiation(SecureRandom random, int maxPadding) {
        int header = 7;
        byte[] out = new byte[header + paddingLength(random, maxPadding)];
        putInt(out, 0, SYNC);
        out[4] = METHOD_OBFUSCATION;
        out[5] = METHOD_OBFUSCATION;
        out[6] = (byte) (out.length - header);
        fillPadding(random, out, header);
        return out;
    }

    private static int paddingLength(SecureRandom random, int maxPadding) {
        if (maxPadding < 0 || maxPadding > 255) {
            throw new IllegalArgumentException("padding is 0-255 bytes");
        }
        return random.nextInt(maxPadding + 1);
    }

    private static void fillPadding(SecureRandom random, byte[] buffer, int from) {
        byte[] padding = new byte[buffer.length - from];
        random.nextBytes(padding);
        System.arraycopy(padding, 0, buffer, from, padding.length);
    }

    private static void checkSync(byte[] decrypted) throws DecodeException {
        int sync = (decrypted[0] & 0xFF)
                | (decrypted[1] & 0xFF) << 8
                | (decrypted[2] & 0xFF) << 16
                | (decrypted[3] & 0xFF) << 24;
        if (sync != SYNC) {
            throw new DecodeException("wrong obfuscation sync value: not an obfuscated stream");
        }
    }

    private static void putInt(byte[] buffer, int at, int value) {
        buffer[at] = (byte) value;
        buffer[at + 1] = (byte) (value >>> 8);
        buffer[at + 2] = (byte) (value >>> 16);
        buffer[at + 3] = (byte) (value >>> 24);
    }

    private static byte[] readDecrypted(InputStream in, Rc4 cipher, int length) throws IOException {
        byte[] data = readFully(in, length);
        cipher.apply(data, 0, length);
        return data;
    }

    private static void skipDecrypted(InputStream in, Rc4 cipher, int length) throws IOException {
        readDecrypted(in, cipher, length);
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] data = in.readNBytes(length);
        if (data.length < length) {
            throw new DecodeException("connection closed during the obfuscation handshake");
        }
        return data;
    }
}
