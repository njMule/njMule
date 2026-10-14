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
package org.njmule.core.upload;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.Hash16;
import org.njmule.core.files.SharedFiles;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.protocol.ed2k.FileEntry;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;

class UploadSessionTest {

    @Test
    void filesAboveTheOldSizeLimitAreListedOnlyToClientsThatTakeLargeFiles() throws DecodeException {
        List<SharedFiles.SharedFile> files = List.of(
                file("small.bin", Ed2k.OLD_MAX_FILE_SIZE, 1),
                file("just-above.bin", Ed2k.OLD_MAX_FILE_SIZE + 1, 2),
                file("below-4-gib.bin", 0xFFFF_FFFFL, 3),
                file("large.bin", 0x1_0000_0000L, 4));

        assertThat(names(UploadSession.listing(files, false).toByteArray())).containsExactly("small.bin");
        assertThat(names(UploadSession.listing(files, true).toByteArray()))
                .containsExactly("small.bin", "just-above.bin", "below-4-gib.bin", "large.bin");
    }

    @Test
    void aListStopsGrowingPastItsLimit() throws DecodeException {
        List<SharedFiles.SharedFile> files = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            files.add(file("file number " + i + " with a rather long name.bin", 1000, i));
        }

        byte[] list = UploadSession.listing(files, false).toByteArray();

        assertThat(list.length).isBetween(UploadSession.MAX_SHARED_LIST, UploadSession.MAX_SHARED_LIST + 200);
        assertThat(names(list)).hasSizeLessThan(files.size());
    }

    private static List<String> names(byte[] list) throws DecodeException {
        PacketReader in = PacketReader.of(list);
        long count = in.u32();
        List<String> names = new ArrayList<>();
        for (long i = 0; i < count; i++) {
            names.add(FileEntry.read(in).name().orElseThrow());
        }
        in.expectEnd();
        return names;
    }

    private static SharedFiles.SharedFile file(String name, long size, int seed) {
        byte[] hash = new byte[16];
        hash[0] = (byte) seed;
        hash[1] = (byte) (seed >> 8);
        return new SharedFiles.SharedFile(Path.of(name), name, size, Hash16.of(hash), List.of(), Optional.empty(), 0);
    }
}
