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
package org.njmule.core.kad;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Objects;
import org.njmule.core.persist.AtomicFiles;
import org.njmule.core.persist.format.PreferencesKadDat;
import org.njmule.core.protocol.crypt.DatagramObfuscation;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Who we are on Kad: a random 128-bit id kept in {@code preferencesKad.dat}, and a secret from
 * which the verify key we give each address derives, kept in {@code kadudpkey.dat}.
 */
public final class KadIdentity {

    public static final String FILE_NAME = "preferencesKad.dat";
    public static final String KEY_FILE_NAME = "kadudpkey.dat";

    private static final Logger LOG = LoggerFactory.getLogger(KadIdentity.class);

    private final UInt128 id;
    private final int udpKey;

    public KadIdentity(UInt128 id, int udpKey) {
        this.id = Objects.requireNonNull(id, "id");
        if (id.isZero()) {
            throw new IllegalArgumentException("a Kad id cannot be zero");
        }
        this.udpKey = udpKey;
    }

    /** Reads our identity from {@code configDir}, creating what is missing. */
    public static KadIdentity loadOrCreate(Path configDir, SecureRandom random) throws IOException {
        Path file = configDir.resolve(FILE_NAME);
        UInt128 id = UInt128.ZERO;
        if (Files.exists(file)) {
            try {
                id = PreferencesKadDat.read(Files.readAllBytes(file)).kadId();
            } catch (IOException e) {
                LOG.warn("{} is unreadable, creating a new Kad id", file, e);
            }
        }
        if (id.isZero()) {
            id = UInt128.random(random);
            AtomicFiles.writeBytes(
                    file, PreferencesKadDat.write(new PreferencesKadDat.Contents(0, id)));
        }
        Path keyFile = configDir.resolve(KEY_FILE_NAME);
        int key = 0;
        if (Files.exists(keyFile) && Files.size(keyFile) == 4) {
            key = (int) PacketReader.of(Files.readAllBytes(keyFile)).u32();
        }
        if (key == 0) {
            do {
                key = random.nextInt();
            } while (key == 0);
            AtomicFiles.writeBytes(
                    keyFile, new PacketWriter().u32(Integer.toUnsignedLong(key)).toByteArray());
        }
        return new KadIdentity(id, key);
    }

    public UInt128 id() {
        return this.id;
    }

    /** The verify key we give the node at {@code ip} (host order). */
    public int verifyKey(int ip) {
        return DatagramObfuscation.kadVerifyKey(this.udpKey, ip);
    }

    @Override
    public String toString() {
        return "KadIdentity[" + this.id + "]";
    }
}
