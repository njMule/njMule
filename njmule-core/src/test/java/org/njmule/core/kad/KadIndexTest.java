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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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
    private static final long KEYWORD_END = NOW + KadIndex.KEYWORD_LIFETIME.toNanos();
    private static final long SOURCE_END = NOW + KadIndex.SOURCE_LIFETIME.toNanos();
    private static final int IP = Ipv4.of(81, 2, 3, 4);

    private final SecureRandom random = new SecureRandom();
    private final KadIndex index = new KadIndex();
    private final UInt128 keyword = UInt128.random(this.random);
    private final UInt128 otherKeyword = UInt128.random(this.random);
    private final UInt128 file = UInt128.random(this.random);
    private final UInt128 source = UInt128.random(this.random);
    private UInt128 first;
    private List<KadMessages.Entry> found;
    private int load;
    private OptionalLong sourceLoad;

    @Test
    void shouldFindAFileUnderAKeywordWithItsMostPopularName() {
        givenTheFileIsPublishedWithTheLoad("Some Movie.avi", 700, IP, 1);
        givenTheFileIsPublished("Other name.avi", 700, IP + 1);
        givenTheFileIsPublished("Other name.avi", 700, IP + 2);

        whenTheKeywordIsSearched();

        thenTheEntriesFoundAre(1);
        thenTheNameFoundIs("Other name.avi");
        thenTheSizeFoundIs(700);
        thenTheDistinctNamesAre(2);
        thenThePublishersAre(3);
        thenTheIndexHoldsKeywordEntries(1);
    }

    @Test
    void shouldNotKeepEntriesWithoutNameOrSize() {
        givenTheFileIsPublishedWithItsSizeOnly(5);
        givenTheFileIsPublished("", 5, IP);
        givenTheFileIsPublished("name", 0, IP);

        whenTheKeywordIsSearched();

        thenNothingIsFound();
        thenTheIndexHoldsKeywordEntries(0);
    }

    @Test
    void shouldFindNothingUnderAnotherKeyword() {
        givenTheFileIsPublishedWithItsSizeOnly(5);
        givenTheFileIsPublished("", 5, IP);
        givenTheFileIsPublished("name", 0, IP);

        whenAnotherKeywordIsSearched();

        thenNothingIsFound();
    }

    @Test
    void shouldTreatAFileOfAnotherSizeAsAnotherEntry() {
        givenTheFileIsPublished("a.avi", 10, IP);
        givenTheFileIsPublished("a.avi", 20, IP);

        whenTheKeywordIsSearched();

        thenTheEntriesFoundAre(1);
        thenTheSizeFoundIs(20);
    }

    @ParameterizedTest
    @MethodSource("expressions")
    void shouldFilterSearchesByExpression(SearchExpr expression, int count) {
        givenFiveLinuxDistrosArePublished();

        whenTheKeywordIsSearchedFor(expression);

        thenTheEntriesFoundAre(count);
    }

    @Test
    void shouldSkipTheFirstResults() {
        givenFiveLinuxDistrosArePublished();

        whenTheKeywordIsSearchedSkipping(3);

        thenTheEntriesFoundAre(2);
    }

    @Test
    void shouldNotStoreWhatOnlyAnswersCarry() {
        givenFiveLinuxDistrosArePublished();

        whenTheKeywordIsSearched();

        thenTheFirstEntryFoundCarriesOnePublishInfo();
    }

    @Test
    void shouldNotFindAKeywordEntryPastItsLifetime() {
        givenAnotherFileIsPublished("a.avi", 10);

        whenTheKeywordIsSearchedAt(KEYWORD_END);

        thenNothingIsFound();
    }

    @Test
    void shouldKeepAKeywordEntryUntilItsLifetimeEnds() {
        givenAnotherFileIsPublished("a.avi", 10);

        whenTheIndexIsCleanedAt(KEYWORD_END - 1);

        thenTheIndexHoldsKeywordEntries(1);
    }

    @Test
    void shouldDropAKeywordEntryWhenItsLifetimeEnds() {
        givenAnotherFileIsPublished("a.avi", 10);
        givenTheIndexIsCleanedAt(KEYWORD_END - 1);

        whenTheIndexIsCleanedAt(KEYWORD_END);

        thenTheIndexHoldsKeywordEntries(0);
    }

    @Test
    void shouldTakeASourcePublication() {
        whenASourceClaimingAnotherAddressIsPublishedFrom(IP, 4672);

        thenTheSourceLoadIsReported();
    }

    @Test
    void shouldStoreASourceWithTheAddressItCameFrom() {
        givenASourceClaimingAnotherAddressIsPublishedFrom(IP, 4672);

        whenTheSourcesAreSearched(500, 0);

        thenTheEntriesFoundAre(1);
        thenTheSourceFoundComesFrom(IP);
        thenTheSourceFoundHasTheUdpPort(4672);
    }

    @ParameterizedTest
    @CsvSource({"0, 0, 1", "501, 0, 0", "500, 1, 0"})
    void shouldFindTheSourcesOfTheSizeAskedFor(long size, int start, int count) {
        givenASourceClaimingAnotherAddressIsPublishedFrom(IP, 4672);

        whenTheSourcesAreSearched(size, start);

        thenTheEntriesFoundAre(count);
    }

    @Test
    void shouldFindNoSourceOfAnotherFile() {
        givenASourceClaimingAnotherAddressIsPublishedFrom(IP, 4672);

        whenTheSourcesOfAnotherFileAreSearched();

        thenNothingIsFound();
    }

    @ParameterizedTest(name = "{2}")
    @MethodSource("unusableSources")
    void shouldRefuseUnusableSources(List<Tag> tags, int ip, String why) {
        whenASourceIsPublishedWithTheTagsFrom(tags, ip);

        thenTheSourceIsRefused(why);
        thenTheIndexHoldsSourceEntries(0);
    }

    @Test
    void shouldReplaceASourcePublishedAgainFromItsAddress() {
        givenANewSourceIsPublishedFrom(IP);

        whenANewSourceIsPublishedFrom(IP);

        thenTheIndexHoldsSourceEntries(1);
    }

    @Test
    void shouldMakeRoomForNewSourcesWithTheOldest() {
        givenANewSourceIsPublishedFrom(IP);
        givenANewSourceIsPublishedFrom(IP);

        whenNewSourcesArePublishedFromTheAddressesAfter(IP, KadIndex.MAX_SOURCES_PER_FILE + 4);

        thenTheIndexHoldsSourceEntries(KadIndex.MAX_SOURCES_PER_FILE);
        thenASearchFindsSources(KadIndex.MAX_RESULTS);
    }

    @Test
    void shouldDropSourcesWhenTheirLifetimeEnds() {
        givenANewSourceIsPublishedFrom(IP);
        givenANewSourceIsPublishedFrom(IP);
        givenNewSourcesArePublishedFromTheAddressesAfter(IP, KadIndex.MAX_SOURCES_PER_FILE + 4);
        givenNoSourceIsFoundAt(SOURCE_END);

        whenTheIndexIsCleanedAt(SOURCE_END);

        thenTheIndexHoldsSourceEntries(0);
    }

    @Test
    void shouldReportTheLoadOfAFullKeyword() {
        whenFilesArePublishedUnderTheKeyword(600);

        thenTheLoadIs(600 * 100 / KadIndex.MAX_FILES_PER_KEYWORD);
        thenASearchFinds(KadIndex.MAX_RESULTS);
    }

    @Test
    void shouldCountAFileRepublishedWithAnotherSizeOnce() {
        givenTheFileIsPublished("a.avi", 10, IP);

        whenTheFileIsPublished("a.avi", 20, IP);

        thenTheIndexHoldsKeywordEntries(1);
    }

    @Test
    void shouldDropAFileRepublishedWithAnotherSizeOnlyOnce() {
        givenTheFileIsPublished("a.avi", 10, IP);
        givenTheFileIsPublished("a.avi", 20, IP);

        whenTheIndexIsCleanedAt(KEYWORD_END);

        thenTheIndexHoldsKeywordEntries(0);
    }

    @Test
    void shouldCountASourceRepublishedFromAnotherAddressOnce() {
        givenTheSourceIsPublishedFrom(IP);

        whenTheSourceIsPublishedFrom(IP + 1);

        thenTheIndexHoldsSourceEntries(1);
        thenASearchFindsTheOnlySourceFrom(IP + 1);
    }

    @Test
    void shouldDropASourceRepublishedFromAnotherAddressOnlyOnce() {
        givenTheSourceIsPublishedFrom(IP);
        givenTheSourceIsPublishedFrom(IP + 1);

        whenTheIndexIsCleanedAt(SOURCE_END);

        thenTheIndexHoldsSourceEntries(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-._", "\"\""})
    void shouldMatchNothingWithATermWithoutAnyWord(String term) {
        givenAnotherFileIsPublished("linux.iso", 10);

        whenTheKeywordIsSearchedForTheTerm(term);

        thenNothingIsFound();
    }

    @Test
    void shouldMatchTheWordOfATermWithinPunctuation() {
        givenAnotherFileIsPublished("linux.iso", 10);

        whenTheKeywordIsSearchedForTheTerm("(linux)");

        thenTheEntriesFoundAre(1);
    }

    @Test
    void shouldCountAPublisherOnceForItsName() {
        givenTheFileIsPublished("Popular.avi", 10, IP);
        givenTheFileIsPublished("Popular.avi", 10, IP + 1);

        whenTheFileIsPublishedAgainAndAgain("Pushed.avi", IP + 2, 5);

        thenTheNameOfTheFileIs("Popular.avi", "one address publishing again");
    }

    @Test
    void shouldNotAddUpNamesSwitchedBackAndForth() {
        givenTheFileIsPublished("Popular.avi", 10, IP);
        givenTheFileIsPublished("Popular.avi", 10, IP + 1);
        givenTheFileIsPublishedAgainAndAgain("Pushed.avi", IP + 2, 5);

        whenTheNameOfTheFileIsSwitchedBackAndForth("Pushed.avi", "Other.avi", IP + 3, 5);

        thenTheNameOfTheFileIs("Popular.avi");
    }

    @Test
    void shouldKeepFewNamesOfAFile() {
        givenTheFileIsPublished("Popular.avi", 10, IP);
        givenTheFileIsPublished("Popular.avi", 10, IP + 1);
        givenTheFileIsPublishedAgainAndAgain("Pushed.avi", IP + 2, 5);
        givenTheNameOfTheFileIsSwitchedBackAndForth("Pushed.avi", "Other.avi", IP + 3, 5);

        whenTheFileIsPublishedUnderDistinctNamesFromTheAddressesFrom(IP + 10, 1_000);

        thenAtMostDistinctNamesAreKept(255);
        thenTheNameOfTheFileIs("Popular.avi");
    }

    @Test
    void shouldFindASourceUntilTheLastInstantOfItsLifetime() {
        givenANewSourceIsPublishedFrom(IP);

        whenTheSourcesAreSearchedAt(SOURCE_END - 1);

        thenTheEntriesFoundAre(1);
    }

    @Test
    void shouldNotFindASourceAtTheExactEndOfItsLifetime() {
        givenANewSourceIsPublishedFrom(IP);

        whenTheSourcesAreSearchedAt(SOURCE_END);

        thenNothingIsFound();
    }

    @Test
    void shouldRefuseANewFileUnderAFullKeyword() {
        givenFilesFillTheKeyword(KadIndex.MAX_FILES_PER_KEYWORD);

        whenANewFileIsPublished("more");

        thenTheLoadIs(100);
        thenTheIndexHoldsKeywordEntries(KadIndex.MAX_FILES_PER_KEYWORD);
    }

    @Test
    void shouldTakeAKnownFileAgainUnderAFullKeyword() {
        givenFilesFillTheKeyword(KadIndex.MAX_FILES_PER_KEYWORD);
        givenANewFileIsRefused("more");

        whenTheFirstFileIsPublishedAgain();

        thenTheIndexHoldsKeywordEntries(KadIndex.MAX_FILES_PER_KEYWORD, "published again");
    }

    @Test
    void shouldRefuseANewFileWhenTheIndexIsFull() {
        givenFilesFillTheKeyword(KadIndex.MAX_FILES_PER_KEYWORD);
        givenFilesFillTheOtherKeyword(KadIndex.MAX_KEYWORD_ENTRIES - KadIndex.MAX_FILES_PER_KEYWORD);

        whenANewFileIsPublishedUnderTheOtherKeyword("x");

        thenTheLoadIs(100);
        thenTheIndexHoldsKeywordEntries(KadIndex.MAX_KEYWORD_ENTRIES);
    }

    @Test
    void shouldFindNothingWhenSkippingBeyondTheResults() {
        givenAnotherFileIsPublished("über alles.avi", 10);

        whenTheKeywordIsSearchedSkipping(5);

        thenNothingIsFound();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ÜBER", "\"über alles\""})
    void shouldMatchInTheNetworkLowerCase(String term) {
        givenAnotherFileIsPublished("über alles.avi", 10);

        whenTheKeywordIsSearchedForTheTerm(term);

        thenTheEntriesFoundAre(1);
    }

    static Stream<Arguments> expressions() {
        SearchExpr bitrate = new SearchExpr.NumericAttr(FileTag.MEDIA_BITRATE, Comparison.GREATER, 300);
        SearchExpr even = new SearchExpr.StringAttr(FileTag.MEDIA_ARTIST, "Even");
        return Stream.of(
                Arguments.of(new SearchExpr.Keyword("Linux ISO"), 5),
                Arguments.of(new SearchExpr.Keyword("windows"), 0),
                Arguments.of(new SearchExpr.StringAttr(FileTag.FILEFORMAT, "ISO"), 5),
                Arguments.of(new SearchExpr.StringAttr(FileTag.FILEFORMAT, "avi"), 0),
                Arguments.of(new SearchExpr.StringAttr(FileTag.MEDIA_ARTIST, "even"), 3),
                Arguments.of(new SearchExpr.StringAttr(FileTag.DESCRIPTION, "x"), 0),
                Arguments.of(size(Comparison.EQUAL, 2_000), 1),
                Arguments.of(size(Comparison.GREATER, 2_000), 3),
                Arguments.of(size(Comparison.LESS, 2_000), 1),
                Arguments.of(size(Comparison.GREATER_EQUAL, 2_000), 4),
                Arguments.of(size(Comparison.LESS_EQUAL, 2_000), 2),
                Arguments.of(size(Comparison.NOT_EQUAL, 2_000), 4),
                Arguments.of(bitrate, 3),
                Arguments.of(new SearchExpr.NumericAttr(FileTag.MEDIA_LENGTH, Comparison.GREATER, 0), 0),
                Arguments.of(new SearchExpr.And(even, bitrate), 2),
                Arguments.of(new SearchExpr.Or(even, bitrate), 4),
                Arguments.of(new SearchExpr.Not(new SearchExpr.Keyword("linux"), even), 2));
    }

    static Stream<Arguments> unusableSources() {
        List<Tag> badUdp = new ArrayList<>(sourceTags(4662));
        badUdp.add(new Tag.IntTag(TagName.id(FileTag.SOURCEUPORT), -1));
        return Stream.of(
                Arguments.of(List.of(new Tag.IntTag(TagName.id(FileTag.SOURCEPORT), 4662)), IP, "no source type"),
                Arguments.of(List.of(new Tag.IntTag(TagName.id(FileTag.SOURCETYPE), 1)), IP, "no TCP port"),
                Arguments.of(sourceTags(4662), 0, "no address"),
                Arguments.of(sourceTags(0x1_0000), IP, "TCP port out of range"),
                Arguments.of(badUdp, IP, "UDP port out of range"));
    }

    private void givenTheFileIsPublished(String name, long size, int ip) {
        this.index.publishKeyword(this.keyword, entry(this.file, name, size), ip, NOW);
    }

    private void givenTheFileIsPublishedWithTheLoad(String name, long size, int ip, int load) {
        assertThat(this.index.publishKeyword(this.keyword, entry(this.file, name, size), ip, NOW))
                .isEqualTo(load);
    }

    private void givenTheFileIsPublishedWithItsSizeOnly(long size) {
        this.index.publishKeyword(
                this.keyword,
                new KadMessages.Entry(this.file, List.of(new Tag.IntTag(TagName.id(FileTag.FILESIZE), size))),
                IP,
                NOW);
    }

    private void givenAnotherFileIsPublished(String name, long size) {
        this.index.publishKeyword(this.keyword, entry(UInt128.random(this.random), name, size), IP, NOW);
    }

    private void givenFiveLinuxDistrosArePublished() {
        for (int i = 0; i < 5; i++) {
            List<Tag> tags = new ArrayList<>();
            tags.add(new Tag.StringTag(TagName.id(FileTag.FILENAME), "linux distro " + i + ".iso"));
            tags.add(new Tag.IntTag(TagName.id(FileTag.FILESIZE), 1_000L * (i + 1)));
            tags.add(new Tag.StringTag(TagName.id(FileTag.MEDIA_ARTIST), i % 2 == 0 ? "Even" : "Odd"));
            tags.add(new Tag.IntTag(TagName.id(FileTag.MEDIA_BITRATE), 128L * (i + 1)));
            tags.add(new Tag.IntTag(TagName.id(FileTag.PUBLISHINFO), 7));
            this.index.publishKeyword(this.keyword, new KadMessages.Entry(UInt128.random(this.random), tags), IP, NOW);
        }
    }

    private void givenTheFileIsPublishedAgainAndAgain(String name, int ip, int times) {
        publishTheFileAgainAndAgain(name, ip, times);
    }

    private void givenTheNameOfTheFileIsSwitchedBackAndForth(String name, String other, int ip, int times) {
        switchTheNameOfTheFileBackAndForth(name, other, ip, times);
    }

    private void givenFilesFillTheKeyword(int count) {
        this.first = UInt128.random(this.random);
        for (int i = 0; i < count; i++) {
            this.index.publishKeyword(
                    this.keyword, entry(i == 0 ? this.first : UInt128.random(this.random), "file " + i, 10), IP, NOW);
        }
    }

    private void givenANewFileIsRefused(String name) {
        assertThat(this.index.publishKeyword(this.keyword, entry(UInt128.random(this.random), name, 10), IP, NOW))
                .isEqualTo(100);
        assertThat(this.index.keywordEntries()).isEqualTo(KadIndex.MAX_FILES_PER_KEYWORD);
    }

    private void givenFilesFillTheOtherKeyword(int count) {
        for (int i = 0; i < count; i++) {
            this.index.publishKeyword(this.otherKeyword, entry(UInt128.random(this.random), "other " + i, 10), IP, NOW);
        }
    }

    private void givenTheIndexIsCleanedAt(long at) {
        this.index.clean(at);
    }

    private void givenASourceClaimingAnotherAddressIsPublishedFrom(int ip, int udpPort) {
        publishASourceClaimingAnotherAddress(ip, udpPort);
    }

    private void givenTheSourceIsPublishedFrom(int ip) {
        this.index.publishSource(this.file, this.source, sourceTags(4662), ip, 4672, NOW);
    }

    private void givenANewSourceIsPublishedFrom(int ip) {
        this.index.publishSource(this.file, UInt128.random(this.random), sourceTags(4662), ip, 4672, NOW);
    }

    private void givenNewSourcesArePublishedFromTheAddressesAfter(int ip, int count) {
        publishNewSourcesFromTheAddressesAfter(ip, count);
    }

    private void givenNoSourceIsFoundAt(long at) {
        assertThat(this.index.searchSources(this.file, 0, 0, at)).isEmpty();
    }

    private void whenTheFileIsPublished(String name, long size, int ip) {
        this.index.publishKeyword(this.keyword, entry(this.file, name, size), ip, NOW);
    }

    private void whenTheFileIsPublishedAgainAndAgain(String name, int ip, int times) {
        publishTheFileAgainAndAgain(name, ip, times);
    }

    private void whenTheNameOfTheFileIsSwitchedBackAndForth(String name, String other, int ip, int times) {
        switchTheNameOfTheFileBackAndForth(name, other, ip, times);
    }

    private void whenTheFileIsPublishedUnderDistinctNamesFromTheAddressesFrom(int ip, int count) {
        for (int i = 0; i < count; i++) {
            this.index.publishKeyword(this.keyword, entry(this.file, "name " + i + ".avi", 10), ip + i, NOW);
        }
    }

    private void whenFilesArePublishedUnderTheKeyword(int count) {
        for (int i = 0; i < count; i++) {
            this.load = this.index.publishKeyword(
                    this.keyword, entry(UInt128.random(this.random), "file " + i, i + 1), IP, NOW);
        }
    }

    private void whenANewFileIsPublished(String name) {
        this.load = this.index.publishKeyword(this.keyword, entry(UInt128.random(this.random), name, 10), IP, NOW);
    }

    private void whenANewFileIsPublishedUnderTheOtherKeyword(String name) {
        this.load = this.index.publishKeyword(this.otherKeyword, entry(UInt128.random(this.random), name, 10), IP, NOW);
    }

    private void whenTheFirstFileIsPublishedAgain() {
        this.index.publishKeyword(this.keyword, entry(this.first, "file 0", 10), IP, NOW + 1);
    }

    private void whenTheKeywordIsSearched() {
        this.found = this.index.searchKeyword(this.keyword, Optional.empty(), 0, NOW);
    }

    private void whenTheKeywordIsSearchedAt(long at) {
        this.found = this.index.searchKeyword(this.keyword, Optional.empty(), 0, at);
    }

    private void whenTheKeywordIsSearchedSkipping(int start) {
        this.found = this.index.searchKeyword(this.keyword, Optional.empty(), start, NOW);
    }

    private void whenTheKeywordIsSearchedFor(SearchExpr expression) {
        this.found = search(expression);
    }

    private void whenTheKeywordIsSearchedForTheTerm(String term) {
        this.found = search(new SearchExpr.Keyword(term));
    }

    private void whenAnotherKeywordIsSearched() {
        this.found = this.index.searchKeyword(UInt128.random(this.random), Optional.empty(), 0, NOW);
    }

    private void whenTheIndexIsCleanedAt(long at) {
        this.index.clean(at);
    }

    private void whenASourceClaimingAnotherAddressIsPublishedFrom(int ip, int udpPort) {
        publishASourceClaimingAnotherAddress(ip, udpPort);
    }

    private void whenASourceIsPublishedWithTheTagsFrom(List<Tag> tags, int ip) {
        this.sourceLoad = this.index.publishSource(this.file, this.source, tags, ip, 4672, NOW);
    }

    private void whenTheSourceIsPublishedFrom(int ip) {
        this.index.publishSource(this.file, this.source, sourceTags(4662), ip, 4672, NOW);
    }

    private void whenANewSourceIsPublishedFrom(int ip) {
        this.index.publishSource(this.file, UInt128.random(this.random), sourceTags(4662), ip, 4672, NOW);
    }

    private void whenNewSourcesArePublishedFromTheAddressesAfter(int ip, int count) {
        publishNewSourcesFromTheAddressesAfter(ip, count);
    }

    private void whenTheSourcesAreSearched(long size, int start) {
        this.found = this.index.searchSources(this.file, size, start, NOW);
    }

    private void whenTheSourcesAreSearchedAt(long at) {
        this.found = this.index.searchSources(this.file, 0, 0, at);
    }

    private void whenTheSourcesOfAnotherFileAreSearched() {
        this.found = this.index.searchSources(UInt128.random(this.random), 0, 0, NOW);
    }

    private void thenTheEntriesFoundAre(int count) {
        assertThat(this.found).hasSize(count);
    }

    private void thenNothingIsFound() {
        assertThat(this.found).isEmpty();
    }

    private void thenTheNameFoundIs(String name) {
        assertThat(Tags.stringValue(this.found.getFirst().tags(), FileTag.FILENAME))
                .contains(name);
    }

    private void thenTheSizeFoundIs(long size) {
        assertThat(Tags.intValue(this.found.getFirst().tags(), FileTag.FILESIZE))
                .hasValue(size);
    }

    private void thenTheDistinctNamesAre(int names) {
        assertThat(publishInfo(this.found) >>> 24).isEqualTo(names);
    }

    private void thenThePublishersAre(int publishers) {
        assertThat(publishInfo(this.found) >>> 16 & 0xFF).isEqualTo(publishers);
    }

    // What only answers carry is not stored.
    private void thenTheFirstEntryFoundCarriesOnePublishInfo() {
        assertThat(this.found.getFirst().tags())
                .filteredOn(t -> t.name().equals(TagName.id(FileTag.PUBLISHINFO)))
                .hasSize(1);
    }

    private void thenTheIndexHoldsKeywordEntries(int entries) {
        assertThat(this.index.keywordEntries()).isEqualTo(entries);
    }

    private void thenTheIndexHoldsKeywordEntries(int entries, String why) {
        assertThat(this.index.keywordEntries()).as(why).isEqualTo(entries);
    }

    private void thenTheIndexHoldsSourceEntries(int entries) {
        assertThat(this.index.sourceEntries()).isEqualTo(entries);
    }

    private void thenTheLoadIs(int expected) {
        assertThat(this.load).isEqualTo(expected);
    }

    private void thenASearchFinds(int count) {
        assertThat(this.index.searchKeyword(this.keyword, Optional.empty(), 0, NOW))
                .hasSize(count);
    }

    private void thenASearchFindsSources(int count) {
        assertThat(this.index.searchSources(this.file, 0, 0, NOW)).hasSize(count);
    }

    private void thenASearchFindsTheOnlySourceFrom(int ip) {
        assertThat(this.index.searchSources(this.file, 0, 0, NOW))
                .singleElement()
                .satisfies(e ->
                        assertThat(Tags.intValue(e.tags(), FileTag.SOURCEIP)).hasValue(Integer.toUnsignedLong(ip)));
    }

    private void thenTheSourceLoadIsReported() {
        assertThat(this.sourceLoad).isPresent();
    }

    private void thenTheSourceIsRefused(String why) {
        assertThat(this.sourceLoad).as(why).isEmpty();
    }

    private void thenTheSourceFoundComesFrom(int ip) {
        assertThat(Tags.intValue(this.found.getFirst().tags(), FileTag.SOURCEIP))
                .hasValue(Integer.toUnsignedLong(ip));
    }

    private void thenTheSourceFoundHasTheUdpPort(int port) {
        assertThat(Tags.intValue(this.found.getFirst().tags(), FileTag.SOURCEUPORT))
                .hasValue(port);
    }

    private void thenTheNameOfTheFileIs(String name) {
        assertThat(name(this.file)).isEqualTo(name);
    }

    private void thenTheNameOfTheFileIs(String name, String why) {
        assertThat(name(this.file)).as(why).isEqualTo(name);
    }

    private void thenAtMostDistinctNamesAreKept(int names) {
        long info = publishInfo(this.index.searchKeyword(this.keyword, Optional.empty(), 0, NOW));
        assertThat(info >>> 24).as("distinct names kept").isLessThanOrEqualTo(names);
    }

    private void publishTheFileAgainAndAgain(String name, int ip, int times) {
        for (int i = 0; i < times; i++) {
            this.index.publishKeyword(this.keyword, entry(this.file, name, 10), ip, NOW);
        }
    }

    // Switching names back and forth does not add up either.
    private void switchTheNameOfTheFileBackAndForth(String name, String other, int ip, int times) {
        for (int i = 0; i < times; i++) {
            this.index.publishKeyword(this.keyword, entry(this.file, name, 10), ip, NOW);
            this.index.publishKeyword(this.keyword, entry(this.file, other, 10), ip, NOW);
        }
    }

    private void publishASourceClaimingAnotherAddress(int ip, int udpPort) {
        List<Tag> tags = List.of(
                new Tag.IntTag(TagName.id(FileTag.SOURCETYPE), 1),
                new Tag.IntTag(TagName.id(FileTag.SOURCEIP), Ipv4.of(1, 1, 1, 1)),
                new Tag.IntTag(TagName.id(FileTag.SOURCEPORT), 4662),
                new Tag.IntTag(TagName.id(FileTag.FILESIZE), 500));
        this.sourceLoad = this.index.publishSource(this.file, this.source, tags, ip, udpPort, NOW);
    }

    private void publishNewSourcesFromTheAddressesAfter(int ip, int count) {
        for (int i = 1; i <= count; i++) {
            this.index.publishSource(this.file, UInt128.random(this.random), sourceTags(4662), ip + i, 4672, NOW);
        }
    }

    private long publishInfo(List<KadMessages.Entry> entries) {
        return Tags.intValue(entries.getFirst().tags(), FileTag.PUBLISHINFO).orElseThrow();
    }

    private String name(UInt128 file) {
        return this.index.searchKeyword(this.keyword, Optional.empty(), 0, NOW).stream()
                .filter(e -> e.id().equals(file))
                .findFirst()
                .flatMap(e -> Tags.stringValue(e.tags(), FileTag.FILENAME))
                .orElseThrow();
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
