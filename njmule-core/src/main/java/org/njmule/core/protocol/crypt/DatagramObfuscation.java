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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Optional;
import org.njmule.core.api.Hash16;
import org.njmule.core.crypto.Rc4;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.packet.UdpFraming;
import org.njmule.core.util.Ipv4;
import org.njmule.core.util.LittleEndian;

/**
 * UDP obfuscation. An obfuscated datagram is {@code <marker 1><random part 2>} in clear followed by RC4-encrypted
 * {@code <sync 4><padding length 1><padding>[<receiver key 4><sender key 4>]<packet>}; the key stream is not discarded
 * and the bracketed verify keys exist only for Kad.
 *
 * <p>RC4 key = MD5 of:
 *
 * <ul>
 *   <li>eD2K client: receiver user hash, sender IP, 91, random part (sync {@code 0x395F2EC1});
 *   <li>Kad: receiver Kad id or the receiver's verify key, random part (sync {@code 0x395F2EC1});
 *   <li>server: server key, 0x6B towards the server or 0xA5 from it, random part (sync {@code 0x13EF24D5}).
 * </ul>
 *
 * <p>The marker's low bits hint which key to try first: {@code x1} eD2K, {@code 00} Kad id, {@code 10} Kad verify key.
 */
public final class DatagramObfuscation {

    /** How a received datagram was protected. */
    public enum Kind {
        PLAIN,
        ED2K,
        KAD_ID_KEY,
        KAD_VERIFY_KEY,
        SERVER
    }

    /** A received packet; the verify keys are those carried by a Kad datagram (0 otherwise). */
    public record Received(Packet packet, Kind kind, int receiverVerifyKey, int senderVerifyKey) {}

    public static final int ED2K_OVERHEAD = 8;
    public static final int KAD_OVERHEAD = 16;

    static final int MAGIC_ED2K = 91;
    static final int SYNC_CLIENT = 0x395F2EC1;
    static final int SYNC_SERVER = 0x13EF24D5;
    static final int MAGIC_TO_SERVER = 0x6B;
    static final int MAGIC_FROM_SERVER = 0xA5;

    // Looking up MD5 and RC4 for every datagram cost more than using them (njmule-bench DatagramKeyBench).
    private static final ThreadLocal<Keys> KEYS = ThreadLocal.withInitial(Keys::new);

    private DatagramObfuscation() {}

    /**
     * The verify key we give the Kad node at {@code peerIp} (host order), derived from our secret {@code udpKey}: 1 to
     * 0xFFFFFFFE, and the same for that address until the secret changes.
     */
    public static int kadVerifyKey(int udpKey, int peerIp) {
        byte[] material = concat(Ipv4.toBytes(peerIp), LittleEndian.ofInt(udpKey));
        byte[] digest = KEYS.get().md5(material);
        long folded = Integer.toUnsignedLong(LittleEndian.getInt(digest, 0)
                ^ LittleEndian.getInt(digest, 4)
                ^ LittleEndian.getInt(digest, 8)
                ^ LittleEndian.getInt(digest, 12));
        return (int) (folded % 0xFFFF_FFFEL + 1);
    }

    public static byte[] encryptForClient(
            byte[] datagram, Hash16 receiverUserHash, byte[] ownPublicIpv4, SecureRandom random) {
        return encryptForClient(
                datagram, receiverUserHash, ownPublicIpv4, clientMarker(random, false, 0), keyPart(random));
    }

    static byte[] encryptForClient(
            byte[] datagram, Hash16 receiverUserHash, byte[] ownPublicIpv4, int marker, byte[] keyPart) {
        Rc4 rc4 = KEYS.get().rc4(ed2kMaterial(receiverUserHash, ownPublicIpv4, keyPart));
        return seal(datagram, marker, keyPart, SYNC_CLIENT, rc4, null);
    }

    /**
     * Kad datagram, keyed with the receiver's Kad id when known, otherwise with the verify key the receiver gave us
     * ({@code receiverVerifyKey}).
     */
    public static byte[] encryptForKad(
            byte[] datagram,
            Optional<Hash16> receiverKadId,
            int receiverVerifyKey,
            int senderVerifyKey,
            SecureRandom random) {
        boolean useVerifyKey = receiverKadId.isEmpty() || receiverKadId.get().isZero();
        return encryptForKad(
                datagram,
                receiverKadId,
                receiverVerifyKey,
                senderVerifyKey,
                clientMarker(random, true, useVerifyKey ? 2 : 0),
                keyPart(random));
    }

    static byte[] encryptForKad(
            byte[] datagram,
            Optional<Hash16> receiverKadId,
            int receiverVerifyKey,
            int senderVerifyKey,
            int marker,
            byte[] keyPart) {
        boolean useVerifyKey = receiverKadId.isEmpty() || receiverKadId.get().isZero();
        if (useVerifyKey && receiverVerifyKey == 0) {
            throw new IllegalArgumentException("a Kad id or a receiver verify key is required");
        }
        byte[] material = useVerifyKey
                ? concat(LittleEndian.ofInt(receiverVerifyKey), keyPart)
                : concat(receiverKadId.get().toByteArray(), keyPart);
        Rc4 rc4 = KEYS.get().rc4(material);
        byte[] keys = concat(LittleEndian.ofInt(receiverVerifyKey), LittleEndian.ofInt(senderVerifyKey));
        return seal(datagram, marker, keyPart, SYNC_CLIENT, rc4, keys);
    }

    public static byte[] encryptForServer(byte[] datagram, int serverKey, SecureRandom random) {
        int marker;
        do {
            marker = random.nextInt(256);
        } while (marker == Protocol.EDONKEY);
        return encryptForServer(datagram, serverKey, marker, keyPart(random));
    }

    static byte[] encryptForServer(byte[] datagram, int serverKey, int marker, byte[] keyPart) {
        Rc4 rc4 = KEYS.get().rc4(serverMaterial(serverKey, MAGIC_TO_SERVER, keyPart));
        return seal(datagram, marker, keyPart, SYNC_SERVER, rc4, null);
    }

    /**
     * Decodes a datagram from a client or Kad node, trying the possible keys in the order hinted by the marker.
     * Datagrams that are not obfuscated, or that match no key, are decoded as plain.
     *
     * @param ownKadId our Kad id, empty when Kad is not running
     * @param ownVerifyKeyForSender the verify key we hand out to the sender's IP (0 if unknown)
     */
    public static Received decodeFromClient(
            byte[] data,
            int offset,
            int length,
            Hash16 ownUserHash,
            byte[] senderIpv4,
            Optional<Hash16> ownKadId,
            int ownVerifyKeyForSender)
            throws DecodeException {
        if (length <= ED2K_OVERHEAD || isPlainClientProtocol(data[offset] & 0xFF)) {
            return plain(data, offset, length);
        }
        int marker = data[offset] & 0xFF;
        byte[] keyPart = {data[offset + 1], data[offset + 2]};
        int tries = ownKadId.isPresent() ? 3 : 1;
        int attempt = ownKadId.isPresent() ? ((marker & 1) != 0 ? 1 : (marker & 2)) : 1;
        for (; tries > 0; tries--, attempt = (attempt + 1) % 3) {
            Kind kind;
            byte[] material;
            switch (attempt) {
                case 0 -> {
                    kind = Kind.KAD_ID_KEY;
                    material = concat(ownKadId.orElseThrow().toByteArray(), keyPart);
                }
                case 1 -> {
                    kind = Kind.ED2K;
                    material = ed2kMaterial(ownUserHash, senderIpv4, keyPart);
                }
                default -> {
                    kind = Kind.KAD_VERIFY_KEY;
                    material = concat(LittleEndian.ofInt(ownVerifyKeyForSender), keyPart);
                }
            }
            Optional<Received> opened = open(data, offset, length, KEYS.get().rc4(material), SYNC_CLIENT, kind);
            if (opened.isPresent()) {
                return opened.get();
            }
        }
        return plain(data, offset, length);
    }

    /** Decodes a datagram from a server whose UDP key is {@code serverKey} (0: none). */
    public static Received decodeFromServer(byte[] data, int offset, int length, int serverKey) throws DecodeException {
        return decodeServerDatagram(data, offset, length, serverKey, MAGIC_FROM_SERVER);
    }

    /** The server's side, as the test kit's server plays it: decodes what a client holding {@code serverKey} sent. */
    public static Received decodeAtServer(byte[] data, int offset, int length, int serverKey) throws DecodeException {
        return decodeServerDatagram(data, offset, length, serverKey, MAGIC_TO_SERVER);
    }

    /** The server's side: an answer for a client that holds {@code serverKey}. */
    public static byte[] encryptFromServer(byte[] datagram, int serverKey, SecureRandom random) {
        int marker;
        do {
            marker = random.nextInt(256);
        } while (marker == Protocol.EDONKEY);
        byte[] keyPart = keyPart(random);
        Rc4 rc4 = KEYS.get().rc4(serverMaterial(serverKey, MAGIC_FROM_SERVER, keyPart));
        return seal(datagram, marker, keyPart, SYNC_SERVER, rc4, null);
    }

    private static Received decodeServerDatagram(byte[] data, int offset, int length, int serverKey, int magic)
            throws DecodeException {
        if (length <= ED2K_OVERHEAD || serverKey == 0 || (data[offset] & 0xFF) == Protocol.EDONKEY) {
            return plain(data, offset, length);
        }
        byte[] keyPart = {data[offset + 1], data[offset + 2]};
        Rc4 rc4 = KEYS.get().rc4(serverMaterial(serverKey, magic, keyPart));
        Optional<Received> opened = open(data, offset, length, rc4, SYNC_SERVER, Kind.SERVER);
        return opened.isPresent() ? opened.get() : plain(data, offset, length);
    }

    private static Optional<Received> open(byte[] data, int offset, int length, Rc4 rc4, int sync, Kind kind)
            throws DecodeException {
        byte[] head = new byte[5];
        rc4.apply(data, offset + 3, head, 0, 5);
        if (LittleEndian.getInt(head, 0) != sync) {
            return Optional.empty();
        }
        int padding = head[4] & 0x0F;
        int rest = length - ED2K_OVERHEAD;
        if (rest <= padding) {
            return Optional.empty();
        }
        rc4.skip(padding);
        rest -= padding;
        int receiverKey = 0;
        int senderKey = 0;
        int from = offset + ED2K_OVERHEAD + padding;
        if (kind == Kind.KAD_ID_KEY || kind == Kind.KAD_VERIFY_KEY) {
            if (rest <= 8) {
                return Optional.empty();
            }
            byte[] keys = new byte[8];
            rc4.apply(data, from, keys, 0, 8);
            receiverKey = LittleEndian.getInt(keys, 0);
            senderKey = LittleEndian.getInt(keys, 4);
            from += 8;
            rest -= 8;
        }
        byte[] packet = new byte[rest];
        rc4.apply(data, from, packet, 0, rest);
        return Optional.of(new Received(UdpFraming.decode(packet, 0, rest), kind, receiverKey, senderKey));
    }

    private static byte[] seal(byte[] datagram, int marker, byte[] keyPart, int sync, Rc4 rc4, byte[] kadKeys) {
        int extra = kadKeys == null ? 0 : kadKeys.length;
        byte[] out = new byte[ED2K_OVERHEAD + extra + datagram.length];
        out[0] = (byte) marker;
        out[1] = keyPart[0];
        out[2] = keyPart[1];
        LittleEndian.putInt(out, 3, sync);
        out[7] = 0; // no padding
        if (kadKeys != null) {
            System.arraycopy(kadKeys, 0, out, ED2K_OVERHEAD, extra);
        }
        System.arraycopy(datagram, 0, out, ED2K_OVERHEAD + extra, datagram.length);
        rc4.apply(out, 3, out.length - 3);
        return out;
    }

    private static int clientMarker(SecureRandom random, boolean kad, int kadBits) {
        for (int i = 0; i < 32; i++) {
            int b = random.nextInt(256);
            b = kad ? (b & ~3) | kadBits : b | 1;
            if (!isPlainClientProtocol(b)) {
                return b;
            }
        }
        return (kad ? 0 : 1) | kadBits;
    }

    private static boolean isPlainClientProtocol(int marker) {
        return switch (marker) {
            case Protocol.EMULE,
                    Protocol.KAD_PACKED,
                    Protocol.KAD,
                    Protocol.UDP_RESERVED_1,
                    Protocol.UDP_RESERVED_2,
                    Protocol.PACKED -> true;
            default -> false;
        };
    }

    private static Received plain(byte[] data, int offset, int length) throws DecodeException {
        return new Received(UdpFraming.decode(data, offset, length), Kind.PLAIN, 0, 0);
    }

    private static byte[] ed2kMaterial(Hash16 userHash, byte[] ipv4, byte[] keyPart) {
        if (ipv4.length != 4) {
            throw new IllegalArgumentException("IPv4 addresses have 4 bytes");
        }
        byte[] m = new byte[Hash16.LENGTH + 4 + 1 + 2];
        userHash.copyTo(m, 0);
        System.arraycopy(ipv4, 0, m, Hash16.LENGTH, 4);
        m[Hash16.LENGTH + 4] = (byte) MAGIC_ED2K;
        m[Hash16.LENGTH + 5] = keyPart[0];
        m[Hash16.LENGTH + 6] = keyPart[1];
        return m;
    }

    private static byte[] serverMaterial(int serverKey, int magic, byte[] keyPart) {
        byte[] m = new byte[7];
        LittleEndian.putInt(m, 0, serverKey);
        m[4] = (byte) magic;
        m[5] = keyPart[0];
        m[6] = keyPart[1];
        return m;
    }

    private static byte[] keyPart(SecureRandom random) {
        byte[] part = new byte[2];
        random.nextBytes(part);
        return part;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** The MD5 and the RC4 cipher of one thread. Each use ends before the next one starts, so one of each is enough. */
    private static final class Keys {

        private final MessageDigest md5;
        private final Rc4 rc4 = new Rc4(new byte[16]);

        Keys() {
            try {
                this.md5 = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("MD5 is required by the Java platform", e);
            }
        }

        byte[] md5(byte[] material) {
            return this.md5.digest(material);
        }

        /** The RC4 key stream keyed with the MD5 of {@code material}. */
        Rc4 rc4(byte[] material) {
            this.rc4.rekey(this.md5.digest(material));
            return this.rc4;
        }
    }
}
