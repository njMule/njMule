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
package org.njmule.webui;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Password hashes for the web interface: PBKDF2 with HMAC-SHA-512 from the JDK, a random salt and many rounds, written
 * as {@code pbkdf2-sha512$<rounds>$<salt>$<hash>}.
 */
public final class Passwords {

    /** Rounds of new hashes. */
    public static final int ROUNDS = 210_000;

    /** The shortest password accepted for the web interface. */
    public static final int MIN_LENGTH = 8;

    private static final String SCHEME = "pbkdf2-sha512";
    private static final String ALGORITHM = "PBKDF2WithHmacSHA512";
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 512;

    private Passwords() {}

    public static String hash(char[] password, SecureRandom random) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        return hash(password, salt, ROUNDS);
    }

    static String hash(char[] password, byte[] salt, int rounds) {
        Base64.Encoder base64 = Base64.getEncoder().withoutPadding();
        return SCHEME
                + "$"
                + rounds
                + "$"
                + base64.encodeToString(salt)
                + "$"
                + base64.encodeToString(derive(password, salt, rounds));
    }

    /** Whether {@code password} matches {@code stored}; a malformed hash matches nothing. */
    public static boolean matches(char[] password, String stored) {
        String[] parts = stored.split("\\$", -1);
        if (parts.length != 4 || !parts[0].equals(SCHEME)) {
            return false;
        }
        try {
            int rounds = Integer.parseInt(parts[1]);
            if (rounds < 1 || rounds > 10_000_000) {
                return false;
            }
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            return MessageDigest.isEqual(derive(password, salt, rounds), expected);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int rounds) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, rounds, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(ALGORITHM + " is part of every JDK", e);
        } finally {
            spec.clearPassword();
        }
    }

    /** Overwrites a password once used. */
    public static void wipe(char[] password) {
        Arrays.fill(password, '\0');
    }
}
