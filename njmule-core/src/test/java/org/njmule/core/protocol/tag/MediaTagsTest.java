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
package org.njmule.core.protocol.tag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.SearchResult;
import org.njmule.core.protocol.ed2k.FileEntry;
import org.njmule.core.protocol.ed2k.ServerMessages;

class MediaTagsTest {

    private final List<Tag> tags = new ArrayList<>();
    private SearchResult.Media media;
    private int seconds;

    @Test
    void shouldReadTheMediaTagsAServerSendsByName() throws Exception {
        givenAStringTag("Artist", "Some Band");
        givenAStringTag("ALBUM", "First Album");
        givenAStringTag("title", "Opening Song");
        givenAStringTag("length", "3:25");
        givenAnIntTag("bitrate", 192);
        givenAStringTag("Codec", "mp3");

        whenAServerAnswerWithTheTagsIsRead();

        thenTheMediaIs(new SearchResult.Media("Some Band", "First Album", "Opening Song", 205, 192, "mp3"));
    }

    @Test
    void shouldTakeTheFirstNonEmptyTagOfEachDetailByNumberOrName() {
        givenAStringTag("Artist", "Named");
        givenAStringTag(FileTag.MEDIA_ARTIST, "Numbered");
        givenAStringTag(FileTag.MEDIA_TITLE, "  ");
        givenAStringTag("Title", "Named title");
        givenAnIntTag(FileTag.MEDIA_BITRATE, 0);
        givenAnIntTag("bitrate", 320);
        givenAnIntTag(FileTag.MEDIA_LENGTH, 61);

        whenTheTagsAreRead();

        thenTheMediaIs(new SearchResult.Media("Named", "", "Named title", 61, 320, ""));
    }

    @Test
    void shouldTakeUnsignedHugeNumbersAsTheLargestOnes() {
        givenAnIntTag(FileTag.MEDIA_LENGTH, -1);
        givenAnIntTag(FileTag.MEDIA_BITRATE, 0x1_0000_0000L);

        whenTheTagsAreRead();

        thenTheMediaIs(new SearchResult.Media("", "", "", Integer.MAX_VALUE, Integer.MAX_VALUE, ""));
    }

    @ParameterizedTest
    @CsvSource({"'1:02:03', 3723", "' 4 : 05 ', 245", "'17 s', 17", "'12:ab', 12"})
    void shouldReadLengthTextsAsHoursMinutesAndSeconds(String text, int expected) {
        whenTheLengthTextIsRead(text);

        thenTheLengthIs(expected);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "long",
                "",
                "12345678901",
                "1:2:3 and then a long explanation of the length that goes beyond the cap"
            })
    void shouldRefuseLengthTextsThatAreNoLength(String text) {
        whenTheLengthTextIsRead(text);

        thenTheLengthIs(0);
    }

    @Test
    void shouldCleanAndCutTheTextsOfOtherUsers() {
        givenAStringTag(FileTag.MEDIA_ARTIST, " Bad\u0007\nArtist ");
        givenAStringTag(FileTag.MEDIA_ALBUM, "a".repeat(1000));

        whenTheTagsAreRead();

        thenTheArtistIs("Bad  Artist");
        thenTheAlbumHasCharacters(SearchResult.Media.MAX_TEXT);
    }

    @Test
    void shouldWriteTheMediaBackAsNumericTags() {
        givenAStringTag("Artist", "Some Band");
        givenAnIntTag("length", 200);
        givenAStringTag(FileTag.MEDIA_CODEC, "flac");

        whenTheTagsAreRead();
        whenTheMediaIsWrittenAndReadAgain();

        thenTheMediaIs(new SearchResult.Media("Some Band", "", "", 200, 0, "flac"));
    }

    @Test
    void shouldGiveNothingForAFileWithoutTags() {
        whenTheTagsAreRead();

        thenTheMediaIs(SearchResult.Media.NONE);
    }

    private void givenAStringTag(String name, String value) {
        this.tags.add(new Tag.StringTag(TagName.of(name), value));
    }

    private void givenAStringTag(int id, String value) {
        this.tags.add(Tag.of(id, value));
    }

    private void givenAnIntTag(String name, long value) {
        this.tags.add(new Tag.IntTag(TagName.of(name), value));
    }

    private void givenAnIntTag(int id, long value) {
        this.tags.add(Tag.of(id, value));
    }

    private void whenTheTagsAreRead() {
        this.media = MediaTags.read(this.tags);
    }

    private void whenAServerAnswerWithTheTagsIsRead() throws Exception {
        List<Tag> all = new ArrayList<>(List.of(Tag.of(FileTag.FILENAME, "song.mp3"), Tag.of(FileTag.FILESIZE, 10)));
        all.addAll(this.tags);
        FileEntry sent = new FileEntry(Hash16.parseHex("01".repeat(16)), 0, 0, all);
        ServerMessages.SearchResults answer =
                ServerMessages.SearchResults.decode(new ServerMessages.SearchResults(List.of(sent), false).encode());
        this.media = answer.files().getFirst().media();
    }

    private void whenTheMediaIsWrittenAndReadAgain() {
        this.media = MediaTags.read(MediaTags.of(this.media));
    }

    private void whenTheLengthTextIsRead(String text) {
        this.seconds = MediaTags.duration(text);
    }

    private void thenTheMediaIs(SearchResult.Media expected) {
        assertThat(this.media).isEqualTo(expected);
    }

    private void thenTheLengthIs(int expected) {
        assertThat(this.seconds).isEqualTo(expected);
    }

    private void thenTheArtistIs(String expected) {
        assertThat(this.media.artist()).isEqualTo(expected);
    }

    private void thenTheAlbumHasCharacters(int count) {
        assertThat(this.media.album()).hasSize(count);
    }
}
