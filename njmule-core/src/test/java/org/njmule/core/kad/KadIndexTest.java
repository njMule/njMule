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

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.njmule.core.protocol.kad.KadMessages;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.protocol.tag.Tags;
import org.njmule.core.search.SearchExpr;
import org.njmule.core.search.SearchExpr.Comparison;
import org.njmule.core.util.Ipv4;

class KadIndexTest {

    private static final long NOW = 1_000_000_000_000L;
    private static final int IP = Ipv4.of(81, 2, 3, 4);

    private final SecureRandom random = new SecureRandom();
    private final KadIndex index = new KadIndex();
    private final UInt128 keyword = UInt128.random(this.random);

    @Test
    void filesUnderAKeywordAreFoundWithTheirMostPopularName() {
        UInt128 file = UInt128.random(this.random);

        assertThat(
                        this.index.publishKeyword(
                                this.keyword, entry(file, "Some Movie.avi", 700), IP, NOW))
                .isEqualTo(1);
        this.index.publishKeyword(this.keyword, entry(file, "Other name.avi", 700), IP + 1, NOW);
        this.index.publishKeyword(this.keyword, entry(file, "Other name.avi", 700), IP + 2, NOW);

        List<KadMessages.Entry> found =
                this.index.searchKeyword(this.keyword, Optional.empty(), 0, NOW);
        assertThat(found).hasSize(1);
        assertThat(Tags.stringValue(found.getFirst().tags(), FileTag.FILENAME))
                .contains("Other name.avi");
        assertThat(Tags.intValue(found.getFirst().tags(), FileTag.FILESIZE)).hasValue(700);
        long info = Tags.intValue(found.getFirst().tags(), FileTag.PUBLISHINFO).orElseThrow();
        assertThat(info >>> 24).isEqualTo(2);
        assertThat(info >>> 16 & 0xFF).isEqualTo(3);
        assertThat(this.index.keywordEntries()).isEqualTo(1);
    }

    @Test
    void entriesWithoutNameOrSizeAreNotKept() {
        UInt128 file = UInt128.random(this.random);
        this.index.publishKeyword(
                this.keyword,
                new KadMessages.Entry(
                        file, List.of(new Tag.IntTag(TagName.id(FileTag.FILESIZE), 5))),
                IP,
                NOW);
        this.index.publishKeyword(this.keyword, entry(file, "", 5), IP, NOW);
        this.index.publishKeyword(this.keyword, entry(file, "name", 0), IP, NOW);

        assertThat(this.index.keywordEntries()).isZero();
        assertThat(this.index.searchKeyword(this.keyword, Optional.empty(), 0, NOW)).isEmpty();
        assertThat(this.index.searchKeyword(UInt128.random(this.random), Optional.empty(), 0, NOW))
                .isEmpty();
    }

    @Test
    void aFileOfAnotherSizeIsAnotherEntry() {
        UInt128 file = UInt128.random(this.random);
        this.index.publishKeyword(this.keyword, entry(file, "a.avi", 10), IP, NOW);
        this.index.publishKeyword(this.keyword, entry(file, "a.avi", 20), IP, NOW);

        List<KadMessages.Entry> found =
                this.index.searchKeyword(this.keyword, Optional.empty(), 0, NOW);
        assertThat(found).hasSize(1);
        assertThat(Tags.intValue(found.getFirst().tags(), FileTag.FILESIZE)).hasValue(20);
    }

    @Test
    void searchesFilterByExpressionAndSkip() {
        for (int i = 0; i < 5; i++) {
            List<Tag> tags = new ArrayList<>();
            tags.add(new Tag.StringTag(TagName.id(FileTag.FILENAME), "linux distro " + i + ".iso"));
            tags.add(new Tag.IntTag(TagName.id(FileTag.FILESIZE), 1_000L * (i + 1)));
            tags.add(
                    new Tag.StringTag(
                            TagName.id(FileTag.MEDIA_ARTIST), i % 2 == 0 ? "Even" : "Odd"));
            tags.add(new Tag.IntTag(TagName.id(FileTag.MEDIA_BITRATE), 128 * (i + 1)));
            tags.add(new Tag.IntTag(TagName.id(FileTag.PUBLISHINFO), 7));
            this.index.publishKeyword(
                    this.keyword,
                    new KadMessages.Entry(UInt128.random(this.random), tags),
                    IP,
                    NOW);
        }

        assertThat(this.search(new SearchExpr.Keyword("Linux ISO"))).hasSize(5);
        assertThat(this.search(new SearchExpr.Keyword("windows"))).isEmpty();
        assertThat(this.search(new SearchExpr.StringAttr(FileTag.FILEFORMAT, "ISO"))).hasSize(5);
        assertThat(this.search(new SearchExpr.StringAttr(FileTag.FILEFORMAT, "avi"))).isEmpty();
        assertThat(this.search(new SearchExpr.StringAttr(FileTag.MEDIA_ARTIST, "even"))).hasSize(3);
        assertThat(this.search(new SearchExpr.StringAttr(FileTag.DESCRIPTION, "x"))).isEmpty();
        assertThat(this.search(size(Comparison.EQUAL, 2_000))).hasSize(1);
        assertThat(this.search(size(Comparison.GREATER, 2_000))).hasSize(3);
        assertThat(this.search(size(Comparison.LESS, 2_000))).hasSize(1);
        assertThat(this.search(size(Comparison.GREATER_EQUAL, 2_000))).hasSize(4);
        assertThat(this.search(size(Comparison.LESS_EQUAL, 2_000))).hasSize(2);
        assertThat(this.search(size(Comparison.NOT_EQUAL, 2_000))).hasSize(4);
        SearchExpr bitrate =
                new SearchExpr.NumericAttr(FileTag.MEDIA_BITRATE, Comparison.GREATER, 300);
        assertThat(this.search(bitrate)).hasSize(3);
        assertThat(
                        this.search(
                                new SearchExpr.NumericAttr(
                                        FileTag.MEDIA_LENGTH, Comparison.GREATER, 0)))
                .isEmpty();
        SearchExpr even = new SearchExpr.StringAttr(FileTag.MEDIA_ARTIST, "Even");
        assertThat(this.search(new SearchExpr.And(even, bitrate))).hasSize(2);
        assertThat(this.search(new SearchExpr.Or(even, bitrate))).hasSize(4);
        assertThat(this.search(new SearchExpr.Not(new SearchExpr.Keyword("linux"), even)))
                .hasSize(2);
        assertThat(this.index.searchKeyword(this.keyword, Optional.empty(), 3, NOW)).hasSize(2);
        // What only answers carry is not stored.
        assertThat(
                        this.index
                                .searchKeyword(this.keyword, Optional.empty(), 0, NOW)
                                .getFirst()
                                .tags())
                .filteredOn(t -> t.name().equals(TagName.id(FileTag.PUBLISHINFO)))
                .hasSize(1);
    }

    @Test
    void keywordEntriesExpire() {
        this.index.publishKeyword(
                this.keyword, entry(UInt128.random(this.random), "a.avi", 10), IP, NOW);
        long later = NOW + KadIndex.KEYWORD_LIFETIME.toNanos();

        assertThat(this.index.searchKeyword(this.keyword, Optional.empty(), 0, later)).isEmpty();
        this.index.clean(later - 1);
        assertThat(this.index.keywordEntries()).isEqualTo(1);
        this.index.clean(later);
        assertThat(this.index.keywordEntries()).isZero();
    }

    @Test
    void sourcesAreStoredWithTheAddressTheyCameFrom() {
        UInt128 file = UInt128.random(this.random);
        UInt128 source = UInt128.random(this.random);
        List<Tag> tags =
                List.of(
                        new Tag.IntTag(TagName.id(FileTag.SOURCETYPE), 1),
                        new Tag.IntTag(TagName.id(FileTag.SOURCEIP), Ipv4.of(1, 1, 1, 1)),
                        new Tag.IntTag(TagName.id(FileTag.SOURCEPORT), 4662),
                        new Tag.IntTag(TagName.id(FileTag.FILESIZE), 500));

        OptionalLong load = this.index.publishSource(file, source, tags, IP, 4672, NOW);

        assertThat(load).isPresent();
        List<KadMessages.Entry> found = this.index.searchSources(file, 500, 0, NOW);
        assertThat(found).hasSize(1);
        assertThat(Tags.intValue(found.getFirst().tags(), FileTag.SOURCEIP))
                .hasValue(Integer.toUnsignedLong(IP));
        assertThat(Tags.intValue(found.getFirst().tags(), FileTag.SOURCEUPORT)).hasValue(4672);
        assertThat(this.index.searchSources(file, 0, 0, NOW)).hasSize(1);
        assertThat(this.index.searchSources(file, 501, 0, NOW)).isEmpty();
        assertThat(this.index.searchSources(file, 500, 1, NOW)).isEmpty();
        assertThat(this.index.searchSources(UInt128.random(this.random), 0, 0, NOW)).isEmpty();
    }

    @Test
    void unusableSourcesAreRefused() {
        UInt128 file = UInt128.random(this.random);
        UInt128 source = UInt128.random(this.random);
        List<Tag> noType = List.of(new Tag.IntTag(TagName.id(FileTag.SOURCEPORT), 4662));
        List<Tag> noPort = List.of(new Tag.IntTag(TagName.id(FileTag.SOURCETYPE), 1));

        assertThat(this.index.publishSource(file, source, noType, IP, 4672, NOW)).isEmpty();
        assertThat(this.index.publishSource(file, source, noPort, IP, 4672, NOW)).isEmpty();
        assertThat(this.index.publishSource(file, source, sourceTags(4662), 0, 4672, NOW))
                .isEmpty();
        assertThat(this.index.sourceEntries()).isZero();
    }

    @Test
    void aSourcePublishedAgainReplacesItselfAndTheOldestMakesRoom() {
        UInt128 file = UInt128.random(this.random);
        this.index.publishSource(
                file, UInt128.random(this.random), sourceTags(4662), IP, 4672, NOW);
        this.index.publishSource(
                file, UInt128.random(this.random), sourceTags(4662), IP, 4672, NOW);
        assertThat(this.index.sourceEntries()).isEqualTo(1);

        for (int i = 1; i < KadIndex.MAX_SOURCES_PER_FILE + 5; i++) {
            this.index.publishSource(
                    file, UInt128.random(this.random), sourceTags(4662), IP + i, 4672, NOW);
        }
        assertThat(this.index.sourceEntries()).isEqualTo(KadIndex.MAX_SOURCES_PER_FILE);
        assertThat(this.index.searchSources(file, 0, 0, NOW)).hasSize(KadIndex.MAX_RESULTS);

        long later = NOW + KadIndex.SOURCE_LIFETIME.toNanos();
        assertThat(this.index.searchSources(file, 0, 0, later)).isEmpty();
        this.index.clean(later);
        assertThat(this.index.sourceEntries()).isZero();
    }

    @Test
    void aFullKeywordReportsItsLoad() {
        int loaded = 0;
        for (int i = 0; i < 600; i++) {
            loaded =
                    this.index.publishKeyword(
                            this.keyword,
                            entry(UInt128.random(this.random), "file " + i, i + 1),
                            IP,
                            NOW);
        }
        assertThat(loaded).isEqualTo(600 * 100 / KadIndex.MAX_FILES_PER_KEYWORD);
        assertThat(this.index.searchKeyword(this.keyword, Optional.empty(), 0, NOW))
                .hasSize(KadIndex.MAX_RESULTS);
    }

    private List<KadMessages.Entry> search(SearchExpr expression) {
        return this.index.searchKeyword(this.keyword, Optional.of(expression), 0, NOW);
    }

    private static SearchExpr size(Comparison comparison, long value) {
        return new SearchExpr.NumericAttr(FileTag.FILESIZE, comparison, value);
    }

    private static KadMessages.Entry entry(UInt128 id, String name, long size) {
        return new KadMessages.Entry(
                id,
                List.of(
                        new Tag.StringTag(TagName.id(FileTag.FILENAME), name),
                        new Tag.IntTag(TagName.id(FileTag.FILESIZE), size)));
    }

    private static List<Tag> sourceTags(int tcpPort) {
        return List.of(
                new Tag.IntTag(TagName.id(FileTag.SOURCETYPE), 1),
                new Tag.IntTag(TagName.id(FileTag.SOURCEPORT), tcpPort));
    }
}
