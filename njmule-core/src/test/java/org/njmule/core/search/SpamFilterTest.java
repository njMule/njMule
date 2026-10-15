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
package org.njmule.core.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.SpamMarks;
import org.njmule.core.persist.format.SearchFiles;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.io.StringEncoding;
import org.njmule.core.protocol.tag.Ed2kTagFormat;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagCodec;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.protocol.tag.TagType;

class SpamFilterTest {

    private static final byte[] DAMAGED = {0x0F, 5, 0, 0, 0, (byte) 0x99};

    @TempDir
    Path dir;

    private SpamFilter filter;
    private SpamMarks marks;

    @Test
    void shouldKeepTheMarksByHashAndNameInTheFile() throws IOException {
        givenTheFilterOfTheFile();
        givenAFileThatIsNotSpamYet("ab", "Movie 2026 FULL.avi");
        givenAFileMarkedAsSpam("ab", "Movie 2026 FULL.avi");

        whenTheFilterIsSavedAndLoadedAgain();

        thenAFileIsSpam("ab", "Movie 2026 FULL.avi");
        thenAFileIsSpam("cd", "movie 2026 full.AVI", "the same name, in any case");
        thenAFileIsNotSpam("ef", "Holiday.jpg");
    }

    @Test
    void shouldClearTheNameButKeepTheHashWhenACopyWithTheSameNameIsUnmarked() throws IOException {
        givenTheFilterOfTheFile();
        givenAFileMarkedAsSpam("ab", "Movie 2026 FULL.avi");
        givenTheFilterIsSavedAndLoadedAgain();

        whenAFileIsUnmarked("cd", "movie 2026 full.AVI");

        thenAFileIsNotSpam("cd", "movie 2026 full.AVI");
        thenAFileIsSpam("ab", "Movie 2026 FULL.avi", "still by its own hash");
        thenNoNameIsMarked();
    }

    @Test
    void shouldNotMarkOtherFilesByAShortNameAlone() {
        givenANewFilter();
        givenAFileMarkedAsSpam("ab", "setup.exe");
        givenAFileMarkedAsSpam("ac", "abcdef.zip");
        givenAFileMarkedAsSpam("ad", "abcdefg.zip");

        whenTheMarksAreTaken();

        thenAFileIsSpam("ab", "setup.exe", "by its hash");
        thenAFileIsNotSpam("cd", "Setup.exe");
        thenAFileIsNotSpam("ce", "abcdef.zip", "10 characters");
        thenAFileIsSpam("cf", "ABCDEFG.zip", "11 characters");
    }

    @Test
    void shouldDropTheMarkersTheFileCannotHoldSoSavingWorks() throws IOException {
        givenASpamFileWithAMarkerItCannotHoldNextToTheHash("ab");
        givenTheFilterOfTheFile();
        givenAFileMarkedAsSpam("cd", "another fake file.avi");

        whenTheFilterIsSaved();

        thenTheSavedFileHasTheSpamHashes("ab", "cd");
    }

    @Test
    void shouldSetADamagedFileAsideInsteadOfOverwritingIt() throws IOException {
        givenADamagedSpamFile();
        givenTheFilterOfTheFile();
        givenTheFilterHasNoSpamYet();
        givenAFileMarkedAsSpam("ab", "a fake movie.avi");

        whenTheFilterIsSavedTwice();

        thenOneCopyOfTheDamagedFileIsSetAside();
        thenTheSavedFileHasTheSpamHashes("ab");
    }

    private void givenANewFilter() {
        this.filter = new SpamFilter();
    }

    private void givenTheFilterOfTheFile() throws IOException {
        this.filter = SpamFilter.load(spamFile());
    }

    private void givenASpamFileWithAMarkerItCannotHoldNextToTheHash(String hex) throws IOException {
        PacketWriter out = new PacketWriter().u8(0x0F).u32(2);
        out.u8(TagType.BSOB | TagType.NEW_ED2K_FLAG).u8(0x09).u8(2).u8(1).u8(2);
        TagCodec.writeEd2k(
                out,
                new Tag.HashTag(TagName.id(SearchFiles.SPAM_HASH), hash(hex)),
                Ed2kTagFormat.NEW,
                StringEncoding.UTF8);
        Files.write(spamFile(), out.toByteArray());
    }

    private void givenADamagedSpamFile() throws IOException {
        Files.write(spamFile(), DAMAGED);
    }

    private void givenAFileThatIsNotSpamYet(String hex, String name) {
        assertThat(this.filter.marks().isSpam(result(hex, name))).isFalse();
    }

    private void givenTheFilterHasNoSpamYet() {
        assertThat(this.filter.marks().spam()).isEmpty();
    }

    private void givenAFileMarkedAsSpam(String hex, String name) {
        this.filter.mark(hash(hex), name, true);
    }

    private void givenTheFilterIsSavedAndLoadedAgain() throws IOException {
        this.filter.save(spamFile());
        this.filter = SpamFilter.load(spamFile());
    }

    private void whenTheFilterIsSavedAndLoadedAgain() throws IOException {
        this.filter.save(spamFile());
        this.marks = SpamFilter.load(spamFile()).marks();
    }

    private void whenTheFilterIsSaved() throws IOException {
        this.filter.save(spamFile());
    }

    private void whenTheFilterIsSavedTwice() throws IOException {
        this.filter.save(spamFile());
        this.filter.save(spamFile());
    }

    private void whenAFileIsUnmarked(String hex, String name) {
        this.filter.mark(hash(hex), name, false);
        this.marks = this.filter.marks();
    }

    private void whenTheMarksAreTaken() {
        this.marks = this.filter.marks();
    }

    private void thenAFileIsSpam(String hex, String name) {
        assertThat(this.marks.isSpam(result(hex, name))).isTrue();
    }

    private void thenAFileIsSpam(String hex, String name, String why) {
        assertThat(this.marks.isSpam(result(hex, name))).as(why).isTrue();
    }

    private void thenAFileIsNotSpam(String hex, String name) {
        assertThat(this.marks.isSpam(result(hex, name))).isFalse();
    }

    private void thenAFileIsNotSpam(String hex, String name, String why) {
        assertThat(this.marks.isSpam(result(hex, name))).as(why).isFalse();
    }

    private void thenNoNameIsMarked() {
        assertThat(this.marks.names()).as("the name is cleared").isEmpty();
    }

    private void thenTheSavedFileHasTheSpamHashes(String... hexes) throws IOException {
        assertThat(SpamFilter.load(spamFile()).marks().spam())
                .containsExactlyInAnyOrder(
                        Arrays.stream(hexes).map(SpamFilterTest::hash).toArray(Hash16[]::new));
    }

    private void thenOneCopyOfTheDamagedFileIsSetAside() throws IOException {
        List<Path> setAside;
        try (Stream<Path> files = Files.list(this.dir)) {
            setAside = files.filter(p -> p.getFileName().toString().startsWith(SpamFilter.FILE_NAME + ".corrupt-"))
                    .toList();
        }
        assertThat(setAside).hasSize(1);
        assertThat(Files.readAllBytes(setAside.getFirst())).isEqualTo(DAMAGED);
    }

    private Path spamFile() {
        return this.dir.resolve(SpamFilter.FILE_NAME);
    }

    private static Hash16 hash(String hex) {
        return Hash16.parseHex(hex.repeat(16));
    }

    private static SearchResult result(String hex, String name) {
        return new SearchResult(hash(hex), name, 1000, 1, 0, Optional.empty());
    }
}
