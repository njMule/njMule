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
package org.njmule.core.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.FileComment;
import org.njmule.core.api.Hash16;

class FileCommentsTest {

    private static final Hash16 FILM = Hash16.parseHex("0123456789ABCDEF0123456789ABCDEF");

    @TempDir
    Path dir;

    @Test
    void readsTheCommentsOfAnImportedProfileAndKeepsOurs() throws Exception {
        Path file = this.dir.resolve(FileComments.FILE_NAME);
        Files.writeString(
                file,
                "[" + FILM.toHex() + "]\r\nComment=Great città\r\nRate=9\r\n[not a hash]\r\nComment=x\r\n",
                StandardCharsets.UTF_8);

        FileComments comments = FileComments.load(file);

        assertThat(comments.get(FILM)).contains(new FileComment("Great città", FileComment.MAX_RATING));
        Hash16 song = Hash16.parseHex("00".repeat(16));
        comments.set(song, FileComment.of("Nice\nline", 3));
        assertThat(FileComments.load(file).get(song)).contains(new FileComment("Nice line", 3));
        comments.set(song, FileComment.NONE);
        assertThat(FileComments.load(file).get(song)).isEmpty();
        assertThat(FileComments.load(this.dir.resolve("none")).get(FILM)).isEmpty();
    }

    @Test
    void aMissingFileIsReadFromItsBackup() throws Exception {
        Path file = this.dir.resolve(FileComments.FILE_NAME);
        FileComments comments = FileComments.load(file);
        comments.set(FILM, FileComment.of("kept", 4));
        comments.set(FILM, FileComment.of("changed", 5));
        Files.delete(file);

        assertThat(FileComments.load(file).get(FILM)).contains(FileComment.of("kept", 4));
    }
}
