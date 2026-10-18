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
package org.njmule.core.update;

import java.nio.charset.StandardCharsets;
import java.security.SignatureException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

/**
 * Checks minisign signatures (https://jedisct1.github.io/minisign/): Ed25519 over the BLAKE2b-512 hash of the file,
 * then over that signature and its trusted comment. Legacy signatures, over the file itself, are refused as the format
 * asks of new verifiers. The format is small and no maintained Java library reads it; the cryptography is Bouncy
 * Castle's.
 */
final class Minisign {

    private static final String UNTRUSTED = "untrusted comment: ";
    private static final String TRUSTED = "trusted comment: ";
    private static final int ID_LENGTH = 8;
    private static final int KEY_LENGTH = 2 + ID_LENGTH + Ed25519PublicKeyParameters.KEY_SIZE;
    private static final int SIGNATURE_LENGTH = 64;
    private static final int SIGNATURE_LINE_LENGTH = 2 + ID_LENGTH + SIGNATURE_LENGTH;
    private static final int HASH_LENGTH = 64;
    // A signature file is four short lines; anything much longer is not one.
    private static final int MAX_SIGNATURE_TEXT = 4_096;

    private Minisign() {}

    /** A public key: the id signatures name it by, in hexadecimal, and its Ed25519 key. */
    record PublicKey(String id, Ed25519PublicKeyParameters key) {
        PublicKey {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(key, "key");
        }
    }

    /** A parsed signature file. */
    static final class Signature {
        private final String keyId;
        private final byte[] signature;
        private final String trustedComment;
        private final byte[] globalSignature;

        private Signature(String keyId, byte[] signature, String trustedComment, byte[] globalSignature) {
            this.keyId = keyId;
            this.signature = signature;
            this.trustedComment = trustedComment;
            this.globalSignature = globalSignature;
        }

        String keyId() {
            return this.keyId;
        }
    }

    /**
     * The keys of {@code text}: minisign public keys, one base64 line each, after their optional comment lines.
     *
     * @throws IllegalArgumentException for a line that is no public key
     */
    static Map<String, PublicKey> publicKeys(String text) {
        Map<String, PublicKey> keys = new LinkedHashMap<>();
        for (String line : text.lines().map(String::strip).toList()) {
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(UNTRUSTED)) {
                continue;
            }
            PublicKey key = publicKey(line);
            keys.put(key.id(), key);
        }
        return keys;
    }

    /**
     * The key of a public key line.
     *
     * @throws IllegalArgumentException for a line that is no Ed25519 public key
     */
    static PublicKey publicKey(String base64) {
        byte[] bytes = Base64.getDecoder().decode(base64.strip());
        if (bytes.length != KEY_LENGTH || bytes[0] != 'E' || bytes[1] != 'd') {
            throw new IllegalArgumentException("not a minisign public key");
        }
        return new PublicKey(
                HexFormat.of().formatHex(bytes, 2, 2 + ID_LENGTH), new Ed25519PublicKeyParameters(bytes, 10));
    }

    /**
     * Reads a signature file.
     *
     * @throws SignatureException when {@code text} is no signature of the hashed kind
     */
    static Signature signature(String text) throws SignatureException {
        if (text.length() > MAX_SIGNATURE_TEXT) {
            throw new SignatureException("not a minisign signature");
        }
        List<String> lines = text.lines().toList();
        if (lines.size() < 4
                || !lines.get(0).startsWith(UNTRUSTED)
                || !lines.get(2).startsWith(TRUSTED)) {
            throw new SignatureException("not a minisign signature");
        }
        byte[] line = decode(lines.get(1), SIGNATURE_LINE_LENGTH);
        if (line[0] != 'E' || line[1] != 'D') {
            throw new SignatureException(
                    line[0] == 'E' && line[1] == 'd' ? "a legacy signature, over the file itself" : "not Ed25519");
        }
        return new Signature(
                HexFormat.of().formatHex(line, 2, 2 + ID_LENGTH),
                Arrays.copyOfRange(line, 2 + ID_LENGTH, line.length),
                lines.get(2).substring(TRUSTED.length()),
                decode(lines.get(3), SIGNATURE_LENGTH));
    }

    /**
     * Checks that one of {@code keys} signed {@code data} and the trusted comment; completes with that comment.
     *
     * @throws SignatureException when the key is not one of them or either signature does not match
     */
    static String verify(byte[] data, Signature signature, Map<String, PublicKey> keys) throws SignatureException {
        PublicKey key = keys.get(signature.keyId);
        if (key == null) {
            throw new SignatureException("signed with an unknown key " + signature.keyId);
        }
        Blake2bDigest blake2b = new Blake2bDigest(HASH_LENGTH * 8);
        blake2b.update(data, 0, data.length);
        byte[] hash = new byte[HASH_LENGTH];
        blake2b.doFinal(hash, 0);
        if (!ed25519(key, hash, signature.signature)) {
            throw new SignatureException("the signature does not match");
        }
        byte[] comment = signature.trustedComment.getBytes(StandardCharsets.UTF_8);
        byte[] signed = Arrays.copyOf(signature.signature, signature.signature.length + comment.length);
        System.arraycopy(comment, 0, signed, signature.signature.length, comment.length);
        if (!ed25519(key, signed, signature.globalSignature)) {
            throw new SignatureException("the trusted comment does not match");
        }
        return signature.trustedComment;
    }

    private static boolean ed25519(PublicKey key, byte[] message, byte[] signature) {
        Ed25519Signer verifier = new Ed25519Signer();
        verifier.init(false, key.key());
        verifier.update(message, 0, message.length);
        return verifier.verifySignature(signature);
    }

    private static byte[] decode(String line, int length) throws SignatureException {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(line.strip());
        } catch (IllegalArgumentException e) {
            throw new SignatureException("not a minisign signature", e);
        }
        if (bytes.length != length) {
            throw new SignatureException("not a minisign signature");
        }
        return bytes;
    }
}
