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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The secret callers of the remote-control API send as a bearer token, kept in {@code api-token} of the config
 * directory, readable by its owner only where the file system can say so. A command line on the same computer reads it
 * from there; other computers are given it by the user.
 */
public final class ApiToken {

    public static final String FILE_NAME = "api-token";

    private static final int BYTES = 32;
    private static final Pattern FORM = Pattern.compile("[A-Za-z0-9_-]{43}");

    private ApiToken() {}

    /** The token kept in {@code configDir}, if there is a well-formed one. */
    public static Optional<String> load(Path configDir) throws IOException {
        try {
            String text = Files.readString(configDir.resolve(FILE_NAME), StandardCharsets.US_ASCII)
                    .strip();
            return FORM.matcher(text).matches() ? Optional.of(text) : Optional.empty();
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
    }

    /** The token, made when there is none yet. */
    public static String loadOrCreate(Path configDir, SecureRandom random) throws IOException {
        Optional<String> kept = load(configDir);
        return kept.isPresent() ? kept.get() : renew(configDir, random);
    }

    /** Replaces the token: whoever holds the old one is refused from now on. */
    public static String renew(Path configDir, SecureRandom random) throws IOException {
        byte[] secret = new byte[BYTES];
        random.nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        Files.createDirectories(configDir);
        Path tmp = configDir.resolve(FILE_NAME + ".tmp");
        Files.deleteIfExists(tmp);
        if (tmp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        Files.writeString(tmp, token + "\n", StandardCharsets.US_ASCII);
        Files.move(
                tmp, configDir.resolve(FILE_NAME), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return token;
    }

    /** Whether {@code given} is {@code token}, compared in constant time. */
    static boolean matches(String token, String given) {
        return given != null
                && MessageDigest.isEqual(
                        token.getBytes(StandardCharsets.US_ASCII), given.getBytes(StandardCharsets.US_ASCII));
    }
}
