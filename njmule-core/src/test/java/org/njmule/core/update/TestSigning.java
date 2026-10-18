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
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

/** A minisign key made for a test, which signs as minisign does: the hashed kind, with a trusted comment. */
final class TestSigning {

    private final Ed25519PrivateKeyParameters secret;
    private final byte[] id = new byte[8];

    TestSigning() {
        SecureRandom random = new SecureRandom();
        this.secret = new Ed25519PrivateKeyParameters(random);
        random.nextBytes(this.id);
    }

    /** The public key as the line of a minisign public key file. */
    String publicKeyLine() {
        byte[] key = new byte[42];
        key[0] = 'E';
        key[1] = 'd';
        System.arraycopy(this.id, 0, key, 2, 8);
        this.secret.generatePublicKey().encode(key, 10);
        return Base64.getEncoder().encodeToString(key);
    }

    Map<String, Minisign.PublicKey> keys() {
        return Minisign.publicKeys(publicKeyLine());
    }

    /** The signature file of {@code data}. */
    String sign(byte[] data) {
        return sign(data, "timestamp:1793000000\tfile:njmule-update.json\thashed");
    }

    String sign(byte[] data, String trustedComment) {
        Blake2bDigest blake2b = new Blake2bDigest(512);
        blake2b.update(data, 0, data.length);
        byte[] hash = new byte[64];
        blake2b.doFinal(hash, 0);
        byte[] signature = ed25519(hash);
        byte[] line = new byte[74];
        line[0] = 'E';
        line[1] = 'D';
        System.arraycopy(this.id, 0, line, 2, 8);
        System.arraycopy(signature, 0, line, 10, 64);
        byte[] comment = trustedComment.getBytes(StandardCharsets.UTF_8);
        byte[] signed = new byte[64 + comment.length];
        System.arraycopy(signature, 0, signed, 0, 64);
        System.arraycopy(comment, 0, signed, 64, comment.length);
        Base64.Encoder base64 = Base64.getEncoder();
        return "untrusted comment: signature from minisign secret key\n"
                + base64.encodeToString(line)
                + "\ntrusted comment: "
                + trustedComment
                + "\n"
                + base64.encodeToString(ed25519(signed))
                + "\n";
    }

    String sign(String data) {
        return sign(data.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] ed25519(byte[] message) {
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, this.secret);
        signer.update(message, 0, message.length);
        return signer.generateSignature();
    }
}
