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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.njmule.core.protocol.kad.KadMessages;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.protocol.tag.Tags;
import org.njmule.core.search.SearchExpr;
import org.njmule.core.util.Ipv4;

/** The files other nodes publish to us under a keyword: who counts for a name, for how long, and how much is kept. */
class KadIndexKeywordsTest {

    private static final long NOW = 1_000_000_000_000L;
    private static final long HOUR = 3_600_000_000_000L;
    private static final long SIZE = 700_000_000L;
    private static final int IP = Ipv4.of(81, 2, 3, 4);

    private final KadIndex index = new KadIndex();
    private final UInt128 keyword = id(1);
    private final UInt128 file = id(2);
    private List<KadMessages.Entry> found;

    @Test
    void shouldForgetPublishersSilentForADaySoThatNewOnesCount() {
        this.givenTheFileIsPublishedByAddresses(IP, 99, "Stale.avi", NOW);
        this.givenTheFileIsPublishedByAddresses(IP + 1_000, 1, "Kept.avi", NOW);
        this.givenTheFileIsPublishedByAddresses(IP + 1_000, 1, "Kept.avi", NOW + 12 * HOUR);

        this.whenTheFileIsPublishedByAddresses(IP + 2_000, 1, "Kept.avi", NOW + 24 * HOUR + 1);
        this.whenTheKeywordIsSearchedAt(NOW + 24 * HOUR + 2);

        this.thenTheNameFoundIs("Kept.avi");
        this.thenThePublishersCountedAre(2);
    }

    @Test
    void shouldForgetSilentPublishersWhenTheIndexIsCleaned() {
        this.givenTheFileIsPublishedByAddresses(IP, 50, "Stale.avi", NOW);
        this.givenTheFileIsPublishedByAddresses(IP + 1_000, 1, "Kept.avi", NOW + 12 * HOUR);

        this.whenTheIndexIsCleanedAt(NOW + 24 * HOUR);
        this.whenTheKeywordIsSearchedAt(NOW + 24 * HOUR);

        this.thenTheNameFoundIs("Kept.avi");
        this.thenThePublishersCountedAre(1);
    }

    @Test
    void shouldKeepOfANameNoMoreThanAFileSystemTakes() {
        this.givenTheFileIsPublishedByAddresses(IP, 1, "x".repeat(10_000) + ".avi", NOW);

        this.whenTheKeywordIsSearchedAt(NOW);

        this.thenTheNameFoundIs("x".repeat(KadIndex.MAX_KEYWORD_NAME));
    }

    @Test
    void shouldNotKeepAFileThatNoAnswerCouldCarry() {
        this.givenTheFileIsPublishedWithTags(
                List.of(new Tag.StringTag(TagName.id(FileTag.MEDIA_ARTIST), "a".repeat(2_000))));

        this.whenTheKeywordIsSearchedAt(NOW);

        this.thenNothingIsFound();
        this.thenTheIndexHoldsKeywordEntries(0);
    }

    @Test
    void shouldAnswerWithTheNameMostPublishersGiveWhileTheyChange() {
        this.givenTheFileIsPublishedByAddresses(IP, 1, "First Name.avi", NOW);
        this.givenTheKeywordWasSearchedFor(new SearchExpr.Keyword("first"));

        this.whenTheFileIsPublishedByAddresses(IP + 10, 2, "Second Name.avi", NOW + HOUR);
        this.whenTheKeywordIsSearchedFor(new SearchExpr.Keyword("second"), NOW + HOUR);

        this.thenTheNameFoundIs("Second Name.avi");

        this.whenTheFileIsPublishedByAddresses(IP, 1, "First Name.avi", NOW + 23 * HOUR);
        this.whenTheKeywordIsSearchedFor(new SearchExpr.Keyword("FIRST name"), NOW + 25 * HOUR);

        this.thenTheNameFoundIs("First Name.avi");
    }

    private void givenTheKeywordWasSearchedFor(SearchExpr expression) {
        this.whenTheKeywordIsSearchedFor(expression, NOW);
        assertThat(this.found).hasSize(1);
    }

    private void whenTheKeywordIsSearchedFor(SearchExpr expression, long at) {
        this.found = this.index.searchKeyword(this.keyword, Optional.of(expression), 0, at);
    }

    private void givenTheFileIsPublishedByAddresses(int firstIp, int count, String name, long at) {
        for (int i = 0; i < count; i++) {
            this.index.publishKeyword(this.keyword, entry(this.file, name, List.of()), firstIp + i, at);
        }
    }

    private void givenTheFileIsPublishedWithTags(List<Tag> tags) {
        this.index.publishKeyword(this.keyword, entry(this.file, "big.avi", tags), IP, NOW);
    }

    private void whenTheFileIsPublishedByAddresses(int firstIp, int count, String name, long at) {
        this.givenTheFileIsPublishedByAddresses(firstIp, count, name, at);
    }

    private void whenTheIndexIsCleanedAt(long at) {
        this.index.clean(at);
    }

    private void whenTheKeywordIsSearchedAt(long at) {
        this.found = this.index.searchKeyword(this.keyword, Optional.empty(), 0, at);
    }

    private void thenTheNameFoundIs(String name) {
        assertThat(this.found)
                .singleElement()
                .satisfies(e ->
                        assertThat(Tags.stringValue(e.tags(), FileTag.FILENAME)).contains(name));
    }

    private void thenThePublishersCountedAre(int publishers) {
        long info =
                Tags.intValue(this.found.getFirst().tags(), FileTag.PUBLISHINFO).orElseThrow();
        assertThat(info >>> 16 & 0xFF).isEqualTo(publishers);
    }

    private void thenNothingIsFound() {
        assertThat(this.found).isEmpty();
    }

    private void thenTheIndexHoldsKeywordEntries(int entries) {
        assertThat(this.index.keywordEntries()).isEqualTo(entries);
    }

    private static KadMessages.Entry entry(UInt128 id, String name, List<Tag> more) {
        List<Tag> tags = new ArrayList<>();
        tags.add(new Tag.StringTag(TagName.id(FileTag.FILENAME), name));
        tags.add(new Tag.IntTag(TagName.id(FileTag.FILESIZE), SIZE));
        tags.addAll(more);
        return new KadMessages.Entry(id, tags);
    }

    private static UInt128 id(long n) {
        return new UInt128(n, n);
    }
}
