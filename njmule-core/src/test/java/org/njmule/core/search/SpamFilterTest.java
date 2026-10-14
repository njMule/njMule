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

import java.nio.file.Files;
import java.nio.file.Path;
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

    @TempDir
    Path dir;

    @Test
    void marksByHashAndNameAndKeepsThemInTheEmuleFile() throws Exception {
        Path file = this.dir.resolve(SpamFilter.FILE_NAME);
        SearchResult fake = result("ab", "Movie 2026 FULL.avi");
        SearchResult renamed = result("cd", "movie 2026 full.AVI");
        SearchResult other = result("ef", "Holiday.jpg");

        SpamFilter filter = SpamFilter.load(file);
        assertThat(filter.marks().isSpam(fake)).isFalse();
        filter.mark(fake.hash(), fake.name(), true);
        filter.save(file);

        SpamMarks marks = SpamFilter.load(file).marks();
        assertThat(marks.isSpam(fake)).isTrue();
        assertThat(marks.isSpam(renamed)).as("the same name, in any case").isTrue();
        assertThat(marks.isSpam(other)).isFalse();

        SpamFilter again = SpamFilter.load(file);
        again.mark(renamed.hash(), renamed.name(), false);
        assertThat(again.marks().isSpam(renamed)).isFalse();
        assertThat(again.marks().isSpam(fake)).as("still by its own hash").isTrue();
        assertThat(again.marks().names()).as("the name is cleared").isEmpty();
    }

    @Test
    void aShortNameAloneDoesNotMarkOtherFiles() {
        SpamFilter filter = new SpamFilter();
        filter.mark(Hash16.parseHex("ab".repeat(16)), "setup.exe", true);
        filter.mark(Hash16.parseHex("ac".repeat(16)), "abcdef.zip", true);
        filter.mark(Hash16.parseHex("ad".repeat(16)), "abcdefg.zip", true);
        SpamMarks marks = filter.marks();

        assertThat(marks.isSpam(result("ab", "setup.exe"))).as("by its hash").isTrue();
        assertThat(marks.isSpam(result("cd", "Setup.exe"))).isFalse();
        assertThat(marks.isSpam(result("ce", "abcdef.zip"))).as("10 characters").isFalse();
        assertThat(marks.isSpam(result("cf", "ABCDEFG.zip")))
                .as("11 characters")
                .isTrue();
    }

    @Test
    void markersTheFileCannotHoldAreDroppedSoSavingWorks() throws Exception {
        Path file = this.dir.resolve(SpamFilter.FILE_NAME);
        PacketWriter out = new PacketWriter().u8(0x0F).u32(2);
        out.u8(TagType.BSOB | TagType.NEW_ED2K_FLAG).u8(0x09).u8(2).u8(1).u8(2);
        TagCodec.writeEd2k(
                out,
                new Tag.HashTag(TagName.id(SearchFiles.SPAM_HASH), Hash16.parseHex("ab".repeat(16))),
                Ed2kTagFormat.NEW,
                StringEncoding.UTF8);
        Files.write(file, out.toByteArray());

        SpamFilter filter = SpamFilter.load(file);
        filter.mark(Hash16.parseHex("cd".repeat(16)), "another fake file.avi", true);
        filter.save(file);

        SpamMarks marks = SpamFilter.load(file).marks();
        assertThat(marks.spam())
                .containsExactlyInAnyOrder(Hash16.parseHex("ab".repeat(16)), Hash16.parseHex("cd".repeat(16)));
    }

    @Test
    void aDamagedFileIsSetAsideNotOverwritten() throws Exception {
        Path file = this.dir.resolve(SpamFilter.FILE_NAME);
        byte[] damaged = {0x0F, 5, 0, 0, 0, (byte) 0x99};
        Files.write(file, damaged);

        SpamFilter filter = SpamFilter.load(file);
        assertThat(filter.marks().spam()).isEmpty();
        filter.mark(Hash16.parseHex("ab".repeat(16)), "a fake movie.avi", true);
        filter.save(file);
        filter.save(file);

        List<Path> setAside;
        try (Stream<Path> files = Files.list(this.dir)) {
            setAside = files.filter(p -> p.getFileName().toString().startsWith(SpamFilter.FILE_NAME + ".corrupt-"))
                    .toList();
        }
        assertThat(setAside).hasSize(1);
        assertThat(Files.readAllBytes(setAside.getFirst())).isEqualTo(damaged);
        assertThat(SpamFilter.load(file).marks().spam()).containsExactly(Hash16.parseHex("ab".repeat(16)));
    }

    private static SearchResult result(String hex, String name) {
        return new SearchResult(Hash16.parseHex(hex.repeat(16)), name, 1000, 1, 0, Optional.empty());
    }
}
