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
import org.junit.jupiter.api.Test;
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

    @Test
    void keywordsOfCompleteFilesAndAllSourcesArePublishedInTurn() {
        for (int i = 0; i < 20; i++) {
            this.publisher.tick();
            this.ticker.advance(Duration.ofSeconds(1));
        }

        assertThat(this.node.keywords).containsOnlyKeys("great", "movie", "song");
        List<KadMessages.Entry> great = this.node.keywords.get("great");
        assertThat(great).hasSize(2);
        assertThat(Tags.stringValue(great.getFirst().tags(), FileTag.FILENAME)).contains("Great Movie.avi");
        assertThat(Tags.intValue(great.getFirst().tags(), FileTag.FILESIZE)).hasValue(700_000_000L);
        assertThat(Tags.stringValue(great.getFirst().tags(), FileTag.FILETYPE)).isPresent();
        assertThat(this.node.sources).containsExactly(MOVIE, SONG, PARTIAL);
    }

    @Test
    void publishesComeAgainOnlyAfterTheirInterval() {
        for (int i = 0; i < 20; i++) {
            this.publisher.tick();
            this.ticker.advance(Duration.ofSeconds(1));
        }
        this.node.keywords.clear();
        this.node.sources.clear();

        this.ticker.advance(KadPublisher.SOURCE_EVERY);
        for (int i = 0; i < 20; i++) {
            this.publisher.tick();
            this.ticker.advance(Duration.ofSeconds(1));
        }
        assertThat(this.node.keywords).isEmpty();
        assertThat(this.node.sources).containsExactly(MOVIE, SONG, PARTIAL);

        this.ticker.advance(KadPublisher.KEYWORD_EVERY);
        this.publisher.tick();
        assertThat(this.node.keywords).containsOnlyKeys("great");
    }

    @Test
    void nothingIsPublishedUnlessTheNodeIsReadyAndReachable() {
        this.node.firewalled = true;
        this.tickTwice();
        this.node.firewalled = false;
        this.node.connected = false;
        this.tickTwice();
        this.node.connected = true;
        this.node.ready = false;
        this.tickTwice();

        assertThat(this.node.keywords).isEmpty();
        assertThat(this.node.sources).isEmpty();
    }

    @Test
    void busyOrRefusingNodesGetNoNewPublish() {
        this.node.runningKeywords = 3;
        this.node.runningSources = 4;
        this.tickTwice();
        assertThat(this.node.keywords).isEmpty();
        assertThat(this.node.sources).isEmpty();

        this.node.runningKeywords = 0;
        this.node.runningSources = 0;
        this.node.accepting = false;
        this.tickTwice();
        this.node.accepting = true;
        this.publisher.tick();
        // A refused publish is tried again at the next turn.
        assertThat(this.node.keywords).containsOnlyKeys("great");
        assertThat(this.node.sources).containsExactly(MOVIE);
    }

    @Test
    void aKeywordCarriesAtMostItsShareOfFiles() {
        List<KadPublisher.Shared> many = new ArrayList<>();
        for (int i = 0; i < KadPublisher.FILES_PER_KEYWORD + 10; i++) {
            byte[] hash = new byte[Hash16.LENGTH];
            hash[0] = (byte) i;
            hash[1] = (byte) (i >> 8);
            many.add(new KadPublisher.Shared(Hash16.of(hash), "common " + i + ".txt", 10, true));
        }
        new KadPublisher(this.node, () -> many, this.ticker).tick();

        assertThat(this.node.keywords.get("common")).hasSize(KadPublisher.FILES_PER_KEYWORD);
    }

    private void tickTwice() {
        this.publisher.tick();
        this.ticker.advance(Duration.ofSeconds(2));
        this.publisher.tick();
        this.ticker.advance(Duration.ofSeconds(2));
    }
}
