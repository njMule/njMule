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

import com.google.common.base.Ticker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.FileComment;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.kad.KadMessages;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tags;

class KadPublisherTest {

    private static final Hash16 MOVIE = Hash16.parseHex("0123456789abcdef0123456789abcdef");
    private static final Hash16 SONG = Hash16.parseHex("fedcba9876543210fedcba9876543210");
    private static final Hash16 PARTIAL = Hash16.parseHex("00112233445566778899aabbccddeeff");

    /** A node that takes every publish and remembers it. */
    private static final class FakeNode implements KadPublisher.Node {
        boolean ready = true;
        boolean connected = true;
        boolean firewalled;
        boolean accepting = true;
        int runningKeywords;
        int runningSources;
        final Map<String, List<KadMessages.Entry>> keywords = new LinkedHashMap<>();
        final List<Hash16> sources = new ArrayList<>();

        @Override
        public boolean isReady() {
            return this.ready;
        }

        @Override
        public boolean isConnected() {
            return this.connected;
        }

        @Override
        public boolean isFirewalled() {
            return this.firewalled;
        }

        @Override
        public int runningKeywordPublishes() {
            return this.runningKeywords;
        }

        @Override
        public int runningSourcePublishes() {
            return this.runningSources;
        }

        @Override
        public boolean publishKeyword(String word, List<KadMessages.Entry> files) {
            if (this.accepting) {
                this.keywords.put(word, files);
            }
            return this.accepting;
        }

        @Override
        public boolean publishSource(Hash16 hash, long size) {
            if (this.accepting) {
                this.sources.add(hash);
            }
            return this.accepting;
        }

        @Override
        public int runningNotePublishes() {
            return 0;
        }

        @Override
        public boolean publishNote(Hash16 hash, String name, long size, Supplier<Optional<FileComment>> comment) {
            return false;
        }
    }

    private static final class FakeTicker extends Ticker {
        long now = 1_000_000_000L;

        @Override
        public long read() {
            return this.now;
        }

        void advance(Duration by) {
            this.now += by.toNanos();
        }
    }

    private final FakeNode node = new FakeNode();
    private final FakeTicker ticker = new FakeTicker();
    private final List<KadPublisher.Shared> shared = List.of(
            new KadPublisher.Shared(MOVIE, "Great Movie.avi", 700_000_000L, true),
            new KadPublisher.Shared(SONG, "great song.mp3", 5_000_000L, true),
            new KadPublisher.Shared(PARTIAL, "half done.iso", 9_000_000L, false));
    private final KadPublisher publisher = new KadPublisher(this.node, () -> this.shared, this.ticker);
    private List<KadPublisher.Shared> many;

    @Test
    void shouldPublishTheKeywordsOfCompleteFilesAndAllSourcesInTurn() {
        whenSecondsPassWithATickEach(20);

        thenTheKeywordsPublishedAre("great", "movie", "song");
        thenTheKeywordCarriesTheFirstFileFirst("great", 2, "Great Movie.avi", 700_000_000L);
        thenTheSourcesPublishedAre(MOVIE, SONG, PARTIAL);
    }

    @Test
    void shouldPublishTheSourcesAgainOnlyAfterTheirInterval() {
        givenTheFirstPublishesWereMadeAndForgotten();
        givenTimePassed(KadPublisher.SOURCE_EVERY);

        whenSecondsPassWithATickEach(20);

        thenNoKeywordIsPublished();
        thenTheSourcesPublishedAre(MOVIE, SONG, PARTIAL);
    }

    @Test
    void shouldPublishTheKeywordsAgainOnlyAfterTheirInterval() {
        givenTheFirstPublishesWereMadeAndForgotten();
        givenTimePassed(KadPublisher.SOURCE_EVERY);
        givenOnlyTheSourcesWerePublishedAgain();
        givenTimePassed(KadPublisher.KEYWORD_EVERY);

        whenThePublisherTicks();

        thenTheKeywordsPublishedAre("great");
    }

    @Test
    void shouldPublishNothingWhileTheNodeIsFirewalled() {
        givenTheNodeIsFirewalled();

        whenTwoTurnsPass();

        thenNothingIsPublished();
    }

    @Test
    void shouldPublishNothingWhileTheNodeIsNotConnected() {
        givenTheNodeIsNotConnected();

        whenTwoTurnsPass();

        thenNothingIsPublished();
    }

    @Test
    void shouldPublishNothingWhileTheNodeIsNotReady() {
        givenTheNodeIsNotReady();

        whenTwoTurnsPass();

        thenNothingIsPublished();
    }

    @Test
    void shouldPublishAKeywordWithAtMostItsShareOfFiles() {
        givenSharedFilesWithOneKeyword(KadPublisher.FILES_PER_KEYWORD + 10);

        whenAPublisherOfThoseFilesTicks();

        thenTheKeywordCarriesFiles("common", KadPublisher.FILES_PER_KEYWORD);
    }

    private void givenTheFirstPublishesWereMadeAndForgotten() {
        whenSecondsPassWithATickEach(20);
        this.node.keywords.clear();
        this.node.sources.clear();
    }

    private void givenTimePassed(Duration time) {
        this.ticker.advance(time);
    }

    private void givenOnlyTheSourcesWerePublishedAgain() {
        whenSecondsPassWithATickEach(20);
        thenNoKeywordIsPublished();
        thenTheSourcesPublishedAre(MOVIE, SONG, PARTIAL);
    }

    private void givenTheNodeIsFirewalled() {
        this.node.firewalled = true;
    }

    private void givenTheNodeIsNotConnected() {
        this.node.connected = false;
    }

    private void givenTheNodeIsNotReady() {
        this.node.ready = false;
    }

    private void givenSharedFilesWithOneKeyword(int count) {
        this.many = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            byte[] hash = new byte[Hash16.LENGTH];
            hash[0] = (byte) i;
            hash[1] = (byte) (i >> 8);
            this.many.add(new KadPublisher.Shared(Hash16.of(hash), "common " + i + ".txt", 10, true));
        }
    }

    private void whenSecondsPassWithATickEach(int seconds) {
        for (int i = 0; i < seconds; i++) {
            this.publisher.tick();
            this.ticker.advance(Duration.ofSeconds(1));
        }
    }

    private void whenThePublisherTicks() {
        this.publisher.tick();
    }

    private void whenTwoTurnsPass() {
        this.publisher.tick();
        this.ticker.advance(Duration.ofSeconds(2));
        this.publisher.tick();
        this.ticker.advance(Duration.ofSeconds(2));
    }

    private void whenAPublisherOfThoseFilesTicks() {
        new KadPublisher(this.node, () -> this.many, this.ticker).tick();
    }

    private void thenTheKeywordsPublishedAre(String... words) {
        assertThat(this.node.keywords).containsOnlyKeys(words);
    }

    private void thenTheKeywordCarriesTheFirstFileFirst(String word, int files, String name, long size) {
        List<KadMessages.Entry> entries = this.node.keywords.get(word);
        assertThat(entries).hasSize(files);
        assertThat(Tags.stringValue(entries.getFirst().tags(), FileTag.FILENAME))
                .contains(name);
        assertThat(Tags.intValue(entries.getFirst().tags(), FileTag.FILESIZE)).hasValue(size);
        assertThat(Tags.stringValue(entries.getFirst().tags(), FileTag.FILETYPE))
                .isPresent();
    }

    private void thenTheSourcesPublishedAre(Hash16... hashes) {
        assertThat(this.node.sources).containsExactly(hashes);
    }

    private void thenNoKeywordIsPublished() {
        assertThat(this.node.keywords).isEmpty();
    }

    private void thenNothingIsPublished() {
        assertThat(this.node.keywords).isEmpty();
        assertThat(this.node.sources).isEmpty();
    }

    private void thenTheKeywordCarriesFiles(String word, int count) {
        assertThat(this.node.keywords.get(word)).hasSize(count);
    }
}
