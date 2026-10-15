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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.FileComment;
import org.njmule.core.api.Hash16;

class FileCommentsTest {

    private static final Hash16 FILM = Hash16.parseHex("0123456789ABCDEF0123456789ABCDEF");
    private static final Hash16 SONG = Hash16.parseHex("00".repeat(16));

    @TempDir
    Path dir;

    private Path file;
    private FileComments comments;

    @BeforeEach
    void nameTheFile() {
        this.file = this.dir.resolve(FileComments.FILE_NAME);
    }

    @Test
    void shouldReadTheCommentsOfAnImportedProfile() throws Exception {
        givenTheCommentsFileOfAnImportedProfile();

        whenTheCommentsAreLoaded();

        thenTheCommentOfIs(FILM, new FileComment("Great città", FileComment.MAX_RATING));
    }

    @Test
    void shouldKeepOurCommentOnAnImportedProfile() throws Exception {
        givenTheCommentsFileOfAnImportedProfile();
        givenTheCommentsAreLoaded();

        whenWeCommentTheFile(SONG, "Nice\nline", 3);

        thenTheSavedCommentOfIs(SONG, new FileComment("Nice line", 3));
    }

    @Test
    void shouldForgetOurCommentOnceRemoved() throws Exception {
        givenTheCommentsFileOfAnImportedProfile();
        givenTheCommentsAreLoaded();
        givenWeCommentedTheFile(SONG, "Nice\nline", 3);

        whenWeRemoveTheCommentOf(SONG);

        thenNoCommentIsSavedFor(SONG);
    }

    @Test
    void shouldHaveNoCommentsWithoutAFile() throws Exception {
        whenTheCommentsAreLoadedFrom("none");

        thenThereIsNoCommentFor(FILM);
    }

    @Test
    void shouldReadAMissingFileFromItsBackup() throws Exception {
        givenTheCommentsAreLoaded();
        givenWeCommentedTheFile(FILM, "kept", 4);
        givenWeCommentedTheFile(FILM, "changed", 5);
        givenTheFileIsDeleted();

        whenTheCommentsAreLoaded();

        thenTheCommentOfIs(FILM, FileComment.of("kept", 4));
    }

    private void givenTheCommentsFileOfAnImportedProfile() throws IOException {
        Files.writeString(
                this.file,
                "[" + FILM.toHex() + "]\r\nComment=Great città\r\nRate=9\r\n[not a hash]\r\nComment=x\r\n",
                StandardCharsets.UTF_8);
    }

    private void givenTheCommentsAreLoaded() throws IOException {
        this.comments = FileComments.load(this.file);
    }

    private void givenWeCommentedTheFile(Hash16 hash, String text, int rating) throws IOException {
        this.comments.set(hash, FileComment.of(text, rating));
    }

    private void givenTheFileIsDeleted() throws IOException {
        Files.delete(this.file);
    }

    private void whenTheCommentsAreLoaded() throws IOException {
        this.comments = FileComments.load(this.file);
    }

    private void whenTheCommentsAreLoadedFrom(String fileName) throws IOException {
        this.comments = FileComments.load(this.dir.resolve(fileName));
    }

    private void whenWeCommentTheFile(Hash16 hash, String text, int rating) throws IOException {
        this.comments.set(hash, FileComment.of(text, rating));
    }

    private void whenWeRemoveTheCommentOf(Hash16 hash) throws IOException {
        this.comments.set(hash, FileComment.NONE);
    }

    private void thenTheCommentOfIs(Hash16 hash, FileComment expected) {
        assertThat(this.comments.get(hash)).contains(expected);
    }

    private void thenThereIsNoCommentFor(Hash16 hash) {
        assertThat(this.comments.get(hash)).isEmpty();
    }

    private void thenTheSavedCommentOfIs(Hash16 hash, FileComment expected) throws IOException {
        assertThat(FileComments.load(this.file).get(hash)).contains(expected);
    }

    private void thenNoCommentIsSavedFor(Hash16 hash) throws IOException {
        assertThat(FileComments.load(this.file).get(hash)).isEmpty();
    }
}
