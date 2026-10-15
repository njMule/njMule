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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.SearchResult;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.protocol.ed2k.FileEntry;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;

class SharedFilesBrowserResultsTest {

    private Packet answer;
    private List<SearchResult> files;

    @Test
    void shouldLeaveOutFilesThatCannotBeDownloaded() throws DecodeException {
        givenAnAnswerListing(
                file(1, "good.mkv", 1000),
                file(2, " ", 1000),
                file(3, "", 1000),
                file(4, "empty.bin", 0),
                file(5, "huge.bin", Ed2k.MAX_FILE_SIZE + 1),
                file(6, "largest.bin", Ed2k.MAX_FILE_SIZE));

        whenTheFilesAreRead();

        thenTheFilesAre("good.mkv", "largest.bin");
        thenEachLinkIsNamedAsItsFile();
    }

    private void givenAnAnswerListing(FileEntry... entries) {
        PacketWriter out = new PacketWriter().u32(entries.length);
        for (FileEntry entry : entries) {
            entry.write(out);
        }
        this.answer = Packet.of(Protocol.EDONKEY, Opcodes.Client.ASKSHAREDFILESANSWER.code(), out);
    }

    private void whenTheFilesAreRead() throws DecodeException {
        this.files = SharedFilesBrowser.files(this.answer);
    }

    private void thenTheFilesAre(String... names) {
        assertThat(this.files).extracting(SearchResult::name).containsExactly(names);
    }

    private void thenEachLinkIsNamedAsItsFile() {
        assertThat(this.files).allSatisfy(f -> assertThat(f.link().name()).isEqualTo(f.name()));
    }

    private static FileEntry file(int seed, String name, long size) {
        byte[] hash = new byte[Hash16.LENGTH];
        hash[0] = (byte) seed;
        return new FileEntry(
                Hash16.of(hash), 0, 0, List.of(Tag.of(FileTag.FILENAME, name), Tag.of(FileTag.FILESIZE, size)));
    }
}
