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

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.njmule.core.api.Hash16;
import org.njmule.core.crypto.Rsa;
import org.njmule.core.crypto.RsaKeys;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.packet.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Secure identification on every client connection: our key from {@code cryptkey.dat}, the keys
 * peers proved to own, and how far each connection's user hash can be trusted for credits. Engine
 * thread only.
 */
public final class Identities {

    public static final String KEY_FILE = "cryptkey.dat";

    private static final Logger LOG = LoggerFactory.getLogger(Identities.class);

    /** How far a connection's user hash is trusted. */
    public enum Trust {
        /** The peer does not take part and has no key pinned: credits apply, unsecured. */
        UNSECURED,
        /** Not proven yet. */
        NEEDED,
        IDENTIFIED,
        FAILED;

        /** Whether credits are given and counted. */
        public boolean credits() {
            return this == UNSECURED || this == IDENTIFIED;
        }
    }

    /** The identification over one connection. */
    public static final class Session {
        private final Optional<IdentityHandshake> handshake;
        private Trust trust;

        private Session(Optional<IdentityHandshake> handshake, Trust trust) {
            this.handshake = handshake;
            this.trust = trust;
        }

        /** Handles the packet if it belongs to the identification; returns whether it did. */
        public boolean received(Packet packet) throws DecodeException {
            return this.handshake.isPresent() && this.handshake.get().received(packet);
        }

        public Trust trust() {
            return this.trust;
        }

        /** Whether the outcome is known: identified, failed, or the peer does not take part. */
        public boolean isSettled() {
            return this.trust != Trust.NEEDED || this.handshake.isEmpty();
        }

        public boolean isSupported() {
            return this.handshake.map(IdentityHandshake::isSupported).orElse(false);
        }
    }

    private final IdentityHandshake.Identity own;
    private final Function<Hash16, Optional<byte[]>> pinnedKeys;
    private final BiConsumer<Hash16, byte[]> identified;
    private final Supplier<Optional<Inet4Address>> ourPublicAddress;
    private final SecureRandom random;

    /**
     * @param pinnedKeys the key a user hash proved to own before, if any
     * @param identified called when a user hash proves it owns a key
     * @param ourPublicAddress our public address when a server gave us a HighID
     */
    public Identities(
            IdentityHandshake.Identity own,
            Function<Hash16, Optional<byte[]>> pinnedKeys,
            BiConsumer<Hash16, byte[]> identified,
            Supplier<Optional<Inet4Address>> ourPublicAddress,
            SecureRandom random) {
        this.own = Objects.requireNonNull(own, "own");
        this.pinnedKeys = Objects.requireNonNull(pinnedKeys, "pinnedKeys");
        this.identified = Objects.requireNonNull(identified, "identified");
        this.ourPublicAddress = Objects.requireNonNull(ourPublicAddress, "ourPublicAddress");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Reads our key from {@code configDir}, creating it on the first start. */
    public static Rsa.PrivateKey loadOrCreateKey(Path configDir, SecureRandom random)
            throws IOException {
        Path file = configDir.resolve(KEY_FILE);
        if (Files.exists(file) && Files.size(file) > 0) {
            try {
                return RsaKeys.readKeyFile(file);
            } catch (DecodeException e) {
                LOG.warn("{} is unreadable, creating a new key", file, e);
            }
        }
        Rsa.PrivateKey key = Rsa.generate(Rsa.NETWORK_KEY_BITS, random);
        RsaKeys.writeKeyFile(file, key);
        return key;
    }

    /**
     * Starts the identification with a peer whose hello we have, asking it to sign our challenge.
     */
    public Session start(Hello peer, InetAddress address, Consumer<Packet> send) {
        Optional<byte[]> pinned = this.pinnedKeys.apply(peer.userHash());
        if (!(address instanceof Inet4Address ip)) {
            return new Session(Optional.empty(), Trust.FAILED);
        }
        Listener listener = new Listener(peer.userHash());
        IdentityHandshake handshake =
                new IdentityHandshake(
                        this.own, peer, ip, pinned, this.ourPublicAddress.get(), send, listener);
        Trust initial =
                handshake.isSupported() || pinned.isPresent() ? Trust.NEEDED : Trust.UNSECURED;
        Session session = new Session(Optional.of(handshake), initial);
        listener.session = session;
        handshake.start(this.random);
        return session;
    }

    private final class Listener implements IdentityHandshake.Listener {
        private final Hash16 userHash;
        private Session session;

        Listener(Hash16 userHash) {
            this.userHash = userHash;
        }

        @Override
        public void identified(Hash16 hash, byte[] publicKey) {
            if (hash.equals(this.userHash)) {
                LOG.debug("{} proved it owns its key", hash);
                this.session.trust = Trust.IDENTIFIED;
                Identities.this.identified.accept(hash, publicKey);
            }
        }

        @Override
        public void failed(Hash16 hash) {
            LOG.debug("{} failed to prove it owns its key", hash);
            this.session.trust = Trust.FAILED;
        }
    }
}
