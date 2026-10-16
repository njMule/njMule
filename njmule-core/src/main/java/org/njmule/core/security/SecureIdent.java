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
package org.njmule.core.security;

import java.net.Inet4Address;
import java.util.Objects;
import java.util.Optional;
import org.njmule.core.crypto.Rsa;
import org.njmule.core.crypto.Rsa.PrivateKey;
import org.njmule.core.crypto.Rsa.PublicKey;
import org.njmule.core.util.LittleEndian;

/**
 * Secure identification of peers for the credit system. A peer proves it owns its key by signing {@code <verifier's
 * public key><verifier's challenge u32>}, plus {@code <IP 4><kind 1>} in version 2, which binds the signature to an
 * address so that it cannot be replayed elsewhere.
 */
public final class SecureIdent {

    /** Which address a version 2 signature is bound to. */
    public enum IpKind {
        /** The verifier's public IP (as seen by the signer). */
        REMOTE_CLIENT(10),
        /** The signer's own IP. */
        LOCAL_CLIENT(20),
        /** No address (0). */
        NONE(30);

        private final int code;

        IpKind(int code) {
            this.code = code;
        }

        public int code() {
            return this.code;
        }

        public static Optional<IpKind> of(int code) {
            for (IpKind kind : values()) {
                if (kind.code == code) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }

    /** The address part of a version 2 signature. */
    public record IpBinding(Inet4Address address, IpKind kind) {
        public IpBinding {
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(kind, "kind");
        }
    }

    private SecureIdent() {}

    public static byte[] message(byte[] verifierPublicKey, int challenge, Optional<IpBinding> binding) {
        int length = verifierPublicKey.length + 4 + (binding.isPresent() ? 5 : 0);
        byte[] message = new byte[length];
        System.arraycopy(verifierPublicKey, 0, message, 0, verifierPublicKey.length);
        int at = verifierPublicKey.length;
        LittleEndian.putInt(message, at, challenge);
        if (binding.isPresent()) {
            System.arraycopy(binding.get().address().getAddress(), 0, message, at + 4, 4);
            message[at + 8] = (byte) binding.get().kind().code();
        }
        return message;
    }

    /** Signs the challenge of a verifier whose public key is {@code verifierPublicKey}. */
    public static byte[] sign(PrivateKey ownKey, byte[] verifierPublicKey, int challenge, Optional<IpBinding> binding) {
        return Rsa.sign(ownKey, message(verifierPublicKey, challenge, binding));
    }

    /** Checks a signature of our challenge made with the peer's key. */
    public static boolean verify(
            PublicKey peerKey, byte[] signature, byte[] ownPublicKey, int challenge, Optional<IpBinding> binding) {
        return Rsa.verify(peerKey, message(ownPublicKey, challenge, binding), signature);
    }
}
