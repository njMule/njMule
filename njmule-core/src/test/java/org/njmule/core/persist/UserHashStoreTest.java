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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Hash16;
import org.njmule.core.persist.format.PreferencesDat;

class UserHashStoreTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    @TempDir Path dir;

    @Test
    void createsAMarkedHashOnceAndKeepsIt() throws Exception {
        Hash16 first = UserHashStore.loadOrCreate(this.dir, RANDOM);
        Hash16 again = UserHashStore.loadOrCreate(this.dir, RANDOM);

        assertThat(again).isEqualTo(first);
        assertThat(PreferencesDat.isMarkedUserHash(first)).isTrue();
        byte[] file = Files.readAllBytes(this.dir.resolve(UserHashStore.FILE_NAME));
        assertThat(PreferencesDat.read(file).userHash()).isEqualTo(first);
        assertThat(file[0]).isEqualTo((byte) PreferencesDat.VERSION);
    }

    @Test
    void replacesAnUnusableOrCorruptHash() throws Exception {
        Path file = this.dir.resolve(UserHashStore.FILE_NAME);
        byte[] zero = new byte[17];
        zero[0] = PreferencesDat.VERSION;
        zero[1 + 5] = 14;
        zero[1 + 14] = 111;
        Files.write(file, zero);

        Hash16 hash = UserHashStore.loadOrCreate(this.dir, RANDOM);
        assertThat(UserHashStore.isUsable(hash)).isTrue();

        Files.write(file, new byte[3]);
        Files.deleteIfExists(AtomicFiles.bakPath(file));
        assertThat(UserHashStore.isUsable(UserHashStore.loadOrCreate(this.dir, RANDOM))).isTrue();
    }
}
