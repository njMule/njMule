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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.FileComment;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.kad.KadMessages;

/** Our comments go to Kad as notes: once a day, again when they change, and no more once taken away. */
class KadPublisherNotesTest {

    private static final Hash16 MOVIE = Hash16.parseHex("0123456789abcdef0123456789abcdef");
    private static final Hash16 SONG = Hash16.parseHex("fedcba9876543210fedcba9876543210");
    private static final Hash16 PARTIAL = Hash16.parseHex("00112233445566778899aabbccddeeff");

    /** A note publish the node was asked for. */
    private record Publish(Hash16 hash, String name, long size, Supplier<Optional<FileComment>> comment) {}

    /** A node that takes every note publish and remembers it. */
    private static final class NotesNode implements KadPublisher.Node {
        final List<Publish> notes = new ArrayList<>();
        boolean firewalled;
        int running;

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public boolean isFirewalled() {
            return this.firewalled;
        }

        @Override
        public int runningKeywordPublishes() {
            return 3;
        }

        @Override
        public int runningSourcePublishes() {
            return 4;
        }

        @Override
        public int runningNotePublishes() {
            return this.running;
        }

        @Override
        public boolean publishKeyword(String word, List<KadMessages.Entry> files) {
            return false;
        }

        @Override
        public boolean publishSource(Hash16 hash, long size) {
            return false;
        }

        @Override
        public boolean publishNote(Hash16 hash, String name, long size, Supplier<Optional<FileComment>> comment) {
            this.notes.add(new Publish(hash, name, size, comment));
            return true;
        }
    }

    private static final class FakeTicker extends Ticker {
        long now = 1_000_000_000L;

        @Override
        public long read() {
            return this.now;
        }
    }

    private final NotesNode node = new NotesNode();
    private final FakeTicker ticker = new FakeTicker();
    private final Map<Hash16, FileComment> comments = new HashMap<>();
    private final KadPublisher publisher = new KadPublisher(
            this.node,
            () -> List.of(
                    new KadPublisher.Shared(MOVIE, "Great Movie.avi", 700_000_000L, true),
                    new KadPublisher.Shared(SONG, "great song.mp3", 5_000_000L, true),
                    new KadPublisher.Shared(PARTIAL, "half done.iso", 9_000_000L, false)),
            hash -> Optional.ofNullable(this.comments.get(hash)),
            this.ticker);

    @Test
    void shouldPublishTheCommentOfEachCommentedFileOnce() {
        this.givenOurComment(MOVIE, FileComment.of("great", 5));
        this.givenOurComment(PARTIAL, FileComment.of("", 1));

        this.whenTimePasses(Duration.ofSeconds(20));

        this.thenNotesWerePublishedFor(MOVIE, PARTIAL);
        this.thenTheFirstNoteIs(MOVIE, "Great Movie.avi", 700_000_000L, FileComment.of("great", 5));
    }

    @Test
    void shouldPublishACommentAgainAfterADay() {
        this.givenOurComment(MOVIE, FileComment.of("great", 5));
        this.givenTimePasses(Duration.ofSeconds(20));
        this.givenTimePasses(KadPublisher.NOTE_EVERY.minusMinutes(1));

        this.whenTimePasses(Duration.ofMinutes(2));

        this.thenNotesWerePublishedFor(MOVIE, MOVIE);
    }

    @Test
    void shouldPublishAChangedCommentAtOnce() {
        this.givenOurComment(MOVIE, FileComment.of("great", 5));
        this.givenTimePasses(Duration.ofSeconds(20));
        this.givenOurComment(MOVIE, FileComment.of("a fake", 1));

        this.whenTimePasses(Duration.ofSeconds(4));

        this.thenNotesWerePublishedFor(MOVIE, MOVIE);
        this.thenTheLastNoteSays(FileComment.of("a fake", 1));
    }

    @Test
    void shouldNoLongerPublishACommentTakenAway() {
        this.givenOurComment(MOVIE, FileComment.of("great", 5));
        this.givenTimePasses(Duration.ofSeconds(20));
        this.givenOurCommentIsTakenAway(MOVIE);

        this.whenTimePasses(KadPublisher.NOTE_EVERY.plusMinutes(1));

        this.thenNotesWerePublishedFor(MOVIE);
        this.thenTheFirstPublishNowReadsNoComment();
    }

    @Test
    void shouldPublishOneNoteAtATimeAndOnlyWhenReachable() {
        this.givenOurComment(MOVIE, FileComment.of("great", 5));
        this.givenANotePublishRunsAlready();
        this.givenTimePasses(Duration.ofSeconds(20));
        this.givenNoNotePublishRuns();
        this.givenTheNodeIsFirewalled();

        this.whenTimePasses(Duration.ofSeconds(20));

        this.thenNoNoteWasPublished();
    }

    private void givenOurComment(Hash16 file, FileComment comment) {
        this.comments.put(file, comment);
    }

    private void givenOurCommentIsTakenAway(Hash16 file) {
        this.comments.remove(file);
    }

    private void givenANotePublishRunsAlready() {
        this.node.running = 1;
    }

    private void givenNoNotePublishRuns() {
        this.node.running = 0;
    }

    private void givenTheNodeIsFirewalled() {
        this.node.firewalled = true;
    }

    private void givenTimePasses(Duration time) {
        this.whenTimePasses(time);
    }

    // The publisher runs every second, as the engine runs it.
    private void whenTimePasses(Duration time) {
        long end = this.ticker.now + time.toNanos();
        while (this.ticker.now < end) {
            this.publisher.tick();
            this.ticker.now += Math.min(Duration.ofSeconds(1).toNanos(), end - this.ticker.now);
        }
    }

    private void thenNotesWerePublishedFor(Hash16... files) {
        assertThat(this.node.notes).extracting(Publish::hash).containsExactly(files);
    }

    private void thenTheFirstNoteIs(Hash16 file, String name, long size, FileComment comment) {
        Publish first = this.node.notes.getFirst();
        assertThat(first.hash()).isEqualTo(file);
        assertThat(first.name()).isEqualTo(name);
        assertThat(first.size()).isEqualTo(size);
        assertThat(first.comment().get()).contains(comment);
    }

    private void thenTheLastNoteSays(FileComment comment) {
        assertThat(this.node.notes.getLast().comment().get()).contains(comment);
    }

    private void thenTheFirstPublishNowReadsNoComment() {
        assertThat(this.node.notes.getFirst().comment().get()).isEmpty();
    }

    private void thenNoNoteWasPublished() {
        assertThat(this.node.notes).isEmpty();
    }
}
