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
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.FileComment;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.kad.KadMessages;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tags;

/** Publishes take turns: one the node refuses waits for its next turn, and a keyword's files rotate. */
class KadPublisherTurnsTest {

    private static final Hash16 MOVIE = hash(1);
    private static final Hash16 SONG = hash(2);
    private static final Hash16 PARTIAL = hash(3);

    /** A publish the node was asked for, and whether it took it. */
    private record Asked(String what, boolean taken) {}

    /** A node that takes or refuses every publish, and remembers them. */
    private final class FakeNode implements KadPublisher.Node {
        boolean accepting = true;
        int running;
        final List<Asked> asked = new ArrayList<>();
        final List<List<KadMessages.Entry>> keywordFiles = new ArrayList<>();

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
            return false;
        }

        @Override
        public int runningKeywordPublishes() {
            return this.running;
        }

        @Override
        public int runningSourcePublishes() {
            return this.running;
        }

        @Override
        public int runningNotePublishes() {
            return this.running;
        }

        @Override
        public boolean publishKeyword(String word, List<KadMessages.Entry> files) {
            this.asked.add(new Asked(word, this.accepting));
            if (this.accepting) {
                this.keywordFiles.add(files);
            }
            return this.accepting;
        }

        @Override
        public boolean publishSource(Hash16 hash, long size) {
            this.asked.add(new Asked(hash.toHex(), this.accepting));
            return this.accepting;
        }

        @Override
        public boolean publishNote(Hash16 hash, String name, long size, Supplier<Optional<FileComment>> comment) {
            this.asked.add(new Asked("note " + hash.toHex(), this.accepting));
            return this.accepting;
        }
    }

    private static final class FakeTicker extends Ticker {
        long now = 1_000_000_000L;

        @Override
        public long read() {
            return this.now;
        }
    }

    private final FakeNode node = new FakeNode();
    private final FakeTicker ticker = new FakeTicker();
    private List<KadPublisher.Shared> shared = List.of(
            new KadPublisher.Shared(MOVIE, "Great Movie.avi", 700_000_000L, true),
            new KadPublisher.Shared(SONG, "great song.mp3", 5_000_000L, true),
            new KadPublisher.Shared(PARTIAL, "half done.iso", 9_000_000L, false));
    private final KadPublisher publisher =
            new KadPublisher(this.node, () -> this.shared, hash -> Optional.of(FileComment.of("fine", 4)), this.ticker);

    @Test
    void shouldGiveTheNextPublishesTheirTurnWhenTheNodeRefusesOne() {
        this.givenTheNodeRefusesPublishes();

        this.whenTheTurnsPass(1);
        this.whenTheNodeTakesPublishesAgain();
        this.whenTheTurnsPass(3);

        this.thenTheNodeWasAskedFor(
                new Asked("great", false),
                new Asked(MOVIE.toHex(), false),
                new Asked("note " + MOVIE.toHex(), false),
                new Asked("movie", true),
                new Asked(SONG.toHex(), true),
                new Asked("note " + SONG.toHex(), true),
                new Asked("song", true),
                new Asked(PARTIAL.toHex(), true),
                new Asked("note " + PARTIAL.toHex(), true),
                new Asked("great", true),
                new Asked(MOVIE.toHex(), true),
                new Asked("note " + MOVIE.toHex(), true));
    }

    @Test
    void shouldStartNoPublishWhileTheNodeIsBusy() {
        this.givenTheNodeIsBusy();

        this.whenTheTurnsPass(3);

        this.thenTheNodeWasAskedFor();
    }

    @Test
    void shouldRotateTheFilesOfAKeywordWithMoreThanItsShare() {
        this.givenFilesWithOneKeyword(KadPublisher.FILES_PER_KEYWORD + 10);

        this.whenTheTurnsPass(1);
        this.whenADayPasses();
        this.whenTheTurnsPass(1);

        this.thenTheKeywordCarriedTheFiles(0, 0, KadPublisher.FILES_PER_KEYWORD);
        this.thenTheKeywordCarriedTheFiles(1, KadPublisher.FILES_PER_KEYWORD, KadPublisher.FILES_PER_KEYWORD);
    }

    private void givenTheNodeRefusesPublishes() {
        this.node.accepting = false;
    }

    private void givenTheNodeIsBusy() {
        this.node.running = 10;
    }

    private void givenFilesWithOneKeyword(int count) {
        List<KadPublisher.Shared> many = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            many.add(new KadPublisher.Shared(hash(100 + i), name(i), 10, true));
        }
        this.shared = many;
    }

    private void whenTheNodeTakesPublishesAgain() {
        this.node.accepting = true;
    }

    // A turn starts a publish of each kind, two seconds apart.
    private void whenTheTurnsPass(int turns) {
        for (int i = 0; i < turns; i++) {
            this.publisher.tick();
            this.ticker.now += Duration.ofSeconds(2).toNanos();
        }
    }

    private void whenADayPasses() {
        this.ticker.now += KadPublisher.KEYWORD_EVERY.toNanos();
    }

    private void thenTheNodeWasAskedFor(Asked... asked) {
        assertThat(this.node.asked).containsExactly(asked);
    }

    // The files are named after their place among the shared ones, which wraps around.
    private void thenTheKeywordCarriedTheFiles(int publish, int first, int count) {
        List<KadMessages.Entry> files = this.node.keywordFiles.get(publish);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            names.add(name((first + i) % this.shared.size()));
        }
        assertThat(files)
                .extracting(e -> Tags.stringValue(e.tags(), FileTag.FILENAME).orElseThrow())
                .containsExactlyElementsOf(names);
    }

    // Two letters are no keyword: "common" is the only one.
    private static String name(int n) {
        return "common " + (char) ('a' + n / 26) + (char) ('a' + n % 26) + ".txt";
    }

    private static Hash16 hash(int n) {
        byte[] bytes = new byte[Hash16.LENGTH];
        bytes[0] = (byte) n;
        bytes[1] = (byte) (n >> 8);
        bytes[15] = 1;
        return Hash16.of(bytes);
    }
}
