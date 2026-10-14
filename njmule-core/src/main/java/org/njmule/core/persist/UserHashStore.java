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
package org.njmule.core.persist;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.SecureRandom;
import org.njmule.core.api.Hash16;
import org.njmule.core.persist.format.PreferencesDat;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The user hash that identifies this client on the network, kept in an eMule-compatible {@code preferences.dat}. It
 * must stay stable: peers bind credits and keys to it.
 */
public final class UserHashStore {

    public static final String FILE_NAME = "preferences.dat";

    private static final Logger logger = LoggerFactory.getLogger(UserHashStore.class);
    private static final int MARKER_5 = 14;
    private static final int MARKER_14 = 111;

    private UserHashStore() {}

    /**
     * Loads the hash, or creates and saves a new one when the file is missing or unusable. A file that cannot be read
     * now fails: the hash in it is the one peers know.
     */
    public static Hash16 loadOrCreate(Path configDir, SecureRandom random) throws IOException {
        Path file = configDir.resolve(FILE_NAME);
        AtomicFiles.cleanupInterruptedWrite(file);
        try {
            Hash16 hash = AtomicFiles.read(
                    file, in -> PreferencesDat.read(in.readAllBytes()).userHash(), false);
            if (isUsable(hash)) {
                return hash;
            }
            logger.warn("Unusable user hash in {}, creating a new one", file);
        } catch (NoSuchFileException e) {
            logger.info("Creating the user hash in {}", file);
        } catch (DecodeException e) {
            logger.warn("Corrupt {}, creating a new user hash", file, e);
        }
        Hash16 hash = generate(random);
        AtomicFiles.writeSecret(
                file, PreferencesDat.write(new PreferencesDat.Contents(PreferencesDat.VERSION, hash, Bytes.EMPTY)));
        return hash;
    }

    /** A random hash with the marker bytes of eMule-compatible clients ([5] = 14, [14] = 111). */
    public static Hash16 generate(SecureRandom random) {
        byte[] bytes = new byte[Hash16.LENGTH];
        Hash16 hash;
        do {
            random.nextBytes(bytes);
            bytes[5] = MARKER_5;
            bytes[14] = MARKER_14;
            hash = Hash16.of(bytes);
        } while (!isUsable(hash));
        return hash;
    }

    /** Peers ignore a hash that is all zero apart from the marker bytes. */
    public static boolean isUsable(Hash16 hash) {
        for (int i = 0; i < Hash16.LENGTH; i++) {
            if (i != 5 && i != 14 && hash.get(i) != 0) {
                return true;
            }
        }
        return false;
    }
}
