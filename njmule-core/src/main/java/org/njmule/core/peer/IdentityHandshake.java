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
package org.njmule.core.peer;

import java.net.Inet4Address;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.njmule.core.api.Hash16;
import org.njmule.core.crypto.Rsa;
import org.njmule.core.crypto.RsaKeys;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.security.SecureIdent;

/**
 * Secure identification over one connection, run by both sides independently: each asks the other
 * to sign a random challenge (sending its key first when the other may not know it) and checks the
 * signature with the key it pinned for that user hash. Engine thread only.
 */
public final class IdentityHandshake {

    /** Our own key, and what decides the address bound into version 2 signatures. */
    public record Identity(Rsa.PrivateKey key, byte[] publicKey) {
        public Identity {
            Objects.requireNonNull(key, "key");
            publicKey = publicKey.clone();
        }

        public static Identity of(Rsa.PrivateKey key) {
            return new Identity(key, RsaKeys.encodePublic(key.publicKey()));
        }

        @Override
        public byte[] publicKey() {
            return this.publicKey.clone();
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof Identity other
                    && this.key.equals(other.key)
                    && Arrays.equals(this.publicKey, other.publicKey);
        }

        @Override
        public int hashCode() {
            return this.key.hashCode() * 31 + Arrays.hashCode(this.publicKey);
        }

        @Override
        public String toString() {
            return "Identity[" + this.publicKey.length + "-byte public key]";
        }
    }

    /** The outcome, reported once. */
    public interface Listener {
        /** The peer signed our challenge with {@code publicKey}. */
        void identified(Hash16 userHash, byte[] publicKey);

        void failed(Hash16 userHash);
    }

    /** Both versions, announced in the hello. */
    public static final int SUPPORTED = 3;

    private static final int STATE_SIGNATURE = 1;
    private static final int STATE_KEY_AND_SIGNATURE = 2;
    private static final int MIN_PACKET = 10;
    private static final int MAX_PACKET = 250;
    private static final int V1 = 1;

    private final Identity own;
    private final Hello peer;
    private final Inet4Address peerAddress;
    private final Optional<Inet4Address> ourPublicAddress;
    private final Consumer<Packet> send;
    private final Listener listener;
    private byte[] peerKey;
    private final boolean pinned;
    private int challengeFor;
    private int challengeFrom;
    private boolean signatureWanted;
    private boolean done;

    /**
     * @param pinnedKey the key this user hash proved to own before, if any
     * @param ourPublicAddress our public address when a server gave us a HighID
     */
    public IdentityHandshake(
            Identity own,
            Hello peer,
            Inet4Address peerAddress,
            Optional<byte[]> pinnedKey,
            Optional<Inet4Address> ourPublicAddress,
            Consumer<Packet> send,
            Listener listener) {
        this.own = Objects.requireNonNull(own, "own");
        this.peer = Objects.requireNonNull(peer, "peer");
        this.peerAddress = Objects.requireNonNull(peerAddress, "peerAddress");
        this.ourPublicAddress = Objects.requireNonNull(ourPublicAddress, "ourPublicAddress");
        this.send = Objects.requireNonNull(send, "send");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.peerKey = pinnedKey.map(byte[]::clone).orElse(null);
        this.pinned = pinnedKey.isPresent();
    }

    /** The identification versions a hello announces. */
    public static int versions(Hello hello) {
        return hello.miscOptions1() >>> 16 & 0x0F;
    }

    /** Whether the peer takes part at all. */
    public boolean isSupported() {
        return versions(this.peer) != 0;
    }

    /** Asks the peer for a signature, after the hellos. */
    public void start(SecureRandom random) {
        if (!this.isSupported()) {
            return;
        }
        int challenge;
        do {
            challenge = random.nextInt();
        } while (challenge == 0);
        this.challengeFor = challenge;
        int state = this.peerKey == null ? STATE_KEY_AND_SIGNATURE : STATE_SIGNATURE;
        this.send.accept(
                Packet.of(
                        Protocol.EMULE,
                        Opcodes.Extended.SECIDENTSTATE.code(),
                        new PacketWriter().u8(state).u32(Integer.toUnsignedLong(challenge))));
    }

    /** Handles the packet if it belongs to the identification; returns whether it did. */
    public boolean received(Packet packet) throws DecodeException {
        if (packet.protocol() != Protocol.EMULE) {
            return false;
        }
        int opcode = packet.opcode();
        if (opcode == Opcodes.Extended.SECIDENTSTATE.code()) {
            this.stateReceived(packet);
        } else if (opcode == Opcodes.Extended.PUBLICKEY.code()) {
            this.keyReceived(packet);
        } else if (opcode == Opcodes.Extended.SIGNATURE.code()) {
            this.signatureReceived(packet);
        } else {
            return false;
        }
        return true;
    }

    private void stateReceived(Packet packet) throws DecodeException {
        if (packet.length() != 5 || !this.isSupported()) {
            return;
        }
        PacketReader in = packet.reader();
        int state = in.u8();
        this.challengeFrom = (int) in.u32();
        if (state == STATE_KEY_AND_SIGNATURE) {
            byte[] key = this.own.publicKey();
            this.send.accept(
                    Packet.of(
                            Protocol.EMULE,
                            Opcodes.Extended.PUBLICKEY.code(),
                            new PacketWriter().u8(key.length).bytes(key)));
        }
        if (state == STATE_SIGNATURE || state == STATE_KEY_AND_SIGNATURE) {
            this.signatureWanted = true;
            this.trySign();
        }
    }

    private void keyReceived(Packet packet) throws DecodeException {
        int size = packet.length();
        if (size < MIN_PACKET || size > MAX_PACKET) {
            return;
        }
        PacketReader in = packet.reader();
        int length = in.u8();
        if (length != size - 1 || length > RsaKeys.MAX_PUBLIC_KEY_SIZE) {
            return;
        }
        byte[] key = in.bytes(length);
        // A pinned key never changes: a different one fails the check below.
        if (!this.pinned) {
            this.peerKey = key;
        }
        this.trySign();
    }

    private void signatureReceived(Packet packet) throws DecodeException {
        int size = packet.length();
        if (this.done
                || this.challengeFor == 0
                || this.peerKey == null
                || size < MIN_PACKET
                || size > MAX_PACKET) {
            return;
        }
        PacketReader in = packet.reader();
        int length = in.u8();
        byte[] signature;
        Optional<SecureIdent.IpBinding> binding;
        if (length == size - 1) {
            signature = in.bytes(length);
            binding = Optional.empty();
        } else if (length == size - 2 && (versions(this.peer) & 2) != 0) {
            signature = in.bytes(length);
            Optional<SecureIdent.IpKind> kind = SecureIdent.IpKind.of(in.u8());
            if (kind.isEmpty()) {
                return;
            }
            binding = this.bindingOf(kind.get());
            if (binding.isEmpty()) {
                this.finish(false);
                return;
            }
        } else {
            return;
        }
        boolean valid;
        try {
            valid =
                    SecureIdent.verify(
                            RsaKeys.decodePublic(this.peerKey),
                            signature,
                            this.own.publicKey(),
                            this.challengeFor,
                            binding);
        } catch (DecodeException | IllegalArgumentException e) {
            valid = false;
        }
        this.finish(valid);
    }

    // The address the peer bound its signature to, as we can check it.
    private Optional<SecureIdent.IpBinding> bindingOf(SecureIdent.IpKind kind) {
        return switch (kind) {
            case LOCAL_CLIENT -> Optional.of(new SecureIdent.IpBinding(this.peerAddress, kind));
            case REMOTE_CLIENT ->
                    this.ourPublicAddress.map(ip -> new SecureIdent.IpBinding(ip, kind));
            case NONE -> Optional.empty();
        };
    }

    private void finish(boolean valid) {
        this.done = true;
        if (valid) {
            this.listener.identified(this.peer.userHash(), this.peerKey.clone());
        } else {
            this.listener.failed(this.peer.userHash());
        }
    }

    private void trySign() {
        if (!this.signatureWanted || this.peerKey == null || this.challengeFrom == 0) {
            return;
        }
        this.signatureWanted = false;
        // Version 2 only for peers that do not take version 1.
        Optional<SecureIdent.IpBinding> binding =
                (versions(this.peer) & V1) != 0
                        ? Optional.empty()
                        : Optional.of(
                                this.ourPublicAddress
                                        .map(
                                                ip ->
                                                        new SecureIdent.IpBinding(
                                                                ip,
                                                                SecureIdent.IpKind.LOCAL_CLIENT))
                                        .orElseGet(
                                                () ->
                                                        new SecureIdent.IpBinding(
                                                                this.peerAddress,
                                                                SecureIdent.IpKind.REMOTE_CLIENT)));
        byte[] signature =
                SecureIdent.sign(this.own.key(), this.peerKey, this.challengeFrom, binding);
        PacketWriter out = new PacketWriter().u8(signature.length).bytes(signature);
        binding.ifPresent(b -> out.u8(b.kind().code()));
        this.send.accept(Packet.of(Protocol.EMULE, Opcodes.Extended.SIGNATURE.code(), out));
    }

    @Override
    public String toString() {
        return "IdentityHandshake[" + this.peer.userHash() + ", done=" + this.done + "]";
    }
}
