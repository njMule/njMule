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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.FileComment;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.KadNote;
import org.njmule.core.files.CommentFilter;

/** The notes we collect from Kad: per publisher, through the comment filter, on request and for downloads. */
class KadNotesTest {

    private static final Hash16 FILM = Hash16.parseHex("0123456789abcdef0123456789abcdef");
    private static final Hash16 SONG = Hash16.parseHex("fedcba9876543210fedcba9876543210");
    private static final long SIZE = 700_000_000L;

    /** A search the finder was asked for, with what it may tell. */
    private record Search(Hash16 file, long size, Consumer<List<KadNode.Note>> notes, Runnable ended) {}

    /** Kad as the notes see it: every search starts unless told otherwise. */
    private static final class FakeKad implements KadNotes.Finder {
        final Map<Hash16, Search> searches = new LinkedHashMap<>();
        boolean ready = true;
        boolean busy;

        @Override
        public boolean isReady() {
            return this.ready;
        }

        @Override
        public boolean findNotes(Hash16 file, long size, Consumer<List<KadNode.Note>> notes, Runnable ended) {
            if (this.busy) {
                return false;
            }
            this.searches.put(file, new Search(file, size, notes, ended));
            return true;
        }
    }

    private final FakeKad finder = new FakeKad();
    private final Ticker ticker = new Ticker() {
        @Override
        public long read() {
            return KadNotesTest.this.now;
        }
    };
    private final List<KadNotes.Download> downloads = new ArrayList<>();
    private long now = 1_000_000_000L;
    private boolean connected = true;
    private String filter = "http://";
    private final KadNotes notes = new KadNotes(
            () -> this.connected ? Optional.of(this.finder) : Optional.empty(),
            () -> List.copyOf(this.downloads),
            () -> CommentFilter.of(this.filter),
            this.ticker);
    private CompletableFuture<List<KadNote>> search;
    private CompletableFuture<List<KadNote>> joined;

    @Test
    void shouldKeepEachPublishersLatestNote() throws Exception {
        givenASearchFor(FILM);
        givenKadFinds(FILM, note(1, "Film.avi", "first words", 2), note(2, "film.avi", "", 4));
        givenKadFinds(FILM, note(1, "Film.avi", "second thoughts", 5));

        whenTheSearchEnds(FILM);

        thenTheSearchGives(
                new KadNote(publisher(2), "film.avi", FileComment.of("", 4)),
                new KadNote(publisher(1), "Film.avi", FileComment.of("second thoughts", 5)));
    }

    @Test
    void shouldLeaveOutWhatTheCommentFilterHoldsInTheCommentOrTheName() throws Exception {
        givenASearchFor(FILM);
        givenKadFindsNotesWithSpam();

        whenTheSearchEnds(FILM);

        thenTheSearchGives(new KadNote(publisher(3), "Film.avi", FileComment.of("fine", 3)));
    }

    @Test
    void shouldShowWhatAChangedFilterNoLongerHolds() {
        givenASearchFor(FILM);
        givenKadFindsNotesWithSpam();
        givenTheSearchEnded(FILM);

        whenTheFilterBecomes("");

        thenTheFileHasNotes(FILM, 3);
    }

    @Test
    void shouldJoinTheSearchRunningForTheFile() throws Exception {
        givenASearchFor(FILM);
        givenTheFileIsSearchedAgain(FILM);

        whenTheSearchEnds(FILM);

        thenKadWasAskedOnlyOnce();
        thenBothSearchesEnded();
    }

    @Test
    void shouldFailWhenKadIsNotConnected() {
        givenKadIsNotConnected();

        whenTheFileIsSearched(FILM);

        thenTheSearchFails("Kad is not connected");
    }

    @Test
    void shouldFailWhenKadIsBusyWithTheFile() {
        givenKadIsBusy();

        whenTheFileIsSearched(FILM);

        thenTheSearchFails("busy");
    }

    @Test
    void shouldSearchEachRunningDownloadOnceARunOneAtATime() {
        givenADownload(FILM);
        givenADownload(SONG);

        whenTimePasses(Duration.ofSeconds(1));

        thenKadWasAskedFor(FILM);
    }

    @Test
    void shouldSearchTheNextDownloadOnlyAfterAPause() {
        givenADownload(FILM);
        givenADownload(SONG);
        givenTimePasses(Duration.ofSeconds(1));
        givenTheSearchEnded(FILM);
        givenTimePasses(Duration.ofSeconds(3));

        whenTimePasses(KadNotes.AUTOMATIC_EVERY);

        thenKadWasAskedFor(FILM, SONG);
    }

    @Test
    void shouldNotSearchForDownloadsBeforeKadKnowsTheNetwork() {
        givenADownload(FILM);
        givenKadIsNotReady();

        whenTimePasses(Duration.ofSeconds(30));

        thenKadWasAskedFor();
    }

    @Test
    void shouldEndTheSearchesWhenKadStops() throws Exception {
        givenASearchFor(FILM);
        givenKadFinds(FILM, note(1, "Film.avi", "good", 4));

        whenKadStops();

        thenTheSearchGives(new KadNote(publisher(1), "Film.avi", FileComment.of("good", 4)));
    }

    @Test
    void shouldKeepAtMostItsShareOfNotesForAFile() {
        givenASearchFor(FILM);

        whenKadFindsSoManyNotes(FILM, KadNotes.MAX_NOTES_PER_FILE + 10);

        thenTheFileHasNotes(FILM, KadNotes.MAX_NOTES_PER_FILE);
    }

    @Test
    void shouldForgetTheNotesOfTheFileSearchedLongestAgo() {
        givenASearchFor(FILM);
        givenKadFinds(FILM, note(1, "Film.avi", "good", 4));
        givenTheSearchEnded(FILM);

        whenSoManyOtherFilesAreSearched(KadNotes.MAX_FILES);

        thenTheFileHasNotes(FILM, 0);
    }

    private void givenASearchFor(Hash16 file) {
        whenTheFileIsSearched(file);
    }

    private void givenKadFinds(Hash16 file, KadNode.Note... found) {
        this.finder.searches.get(file).notes().accept(List.of(found));
    }

    private void givenTheSearchEnded(Hash16 file) {
        whenTheSearchEnds(file);
    }

    private void givenKadIsNotConnected() {
        this.connected = false;
    }

    private void givenKadIsBusy() {
        this.finder.busy = true;
    }

    private void givenKadIsNotReady() {
        this.finder.ready = false;
    }

    private void givenADownload(Hash16 file) {
        this.downloads.add(new KadNotes.Download(file, SIZE));
    }

    private void givenTimePasses(Duration time) {
        whenTimePasses(time);
    }

    private void givenKadFindsNotesWithSpam() {
        givenKadFinds(
                FILM,
                note(1, "Film.avi", "see http://spam.example", 1),
                note(2, "HTTP://spam.example Film.avi", "fine", 1),
                note(3, "Film.avi", "fine", 3));
    }

    private void givenTheFileIsSearchedAgain(Hash16 file) {
        this.joined = new CompletableFuture<>();
        this.notes.search(file, SIZE, this.joined);
    }

    private void whenTheFileIsSearched(Hash16 file) {
        this.search = new CompletableFuture<>();
        this.notes.search(file, SIZE, this.search);
    }

    private void whenTheSearchEnds(Hash16 file) {
        this.finder.searches.get(file).ended().run();
    }

    private void whenSoManyOtherFilesAreSearched(int count) {
        for (int i = 0; i < count; i++) {
            byte[] bytes = new byte[Hash16.LENGTH];
            bytes[0] = (byte) (i >> 8);
            bytes[1] = (byte) i;
            bytes[2] = 1;
            Hash16 other = Hash16.of(bytes);
            whenTheFileIsSearched(other);
            whenTheSearchEnds(other);
        }
    }

    private void whenTheFilterBecomes(String words) {
        this.filter = words;
    }

    private void whenKadStops() {
        this.notes.kadStopped();
    }

    private void whenKadFindsSoManyNotes(Hash16 file, int count) {
        List<KadNode.Note> found = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            found.add(note(100 + i, "Film.avi", "note " + i, 3));
        }
        this.finder.searches.get(file).notes().accept(found);
    }

    // The notes run every second, as the engine runs them.
    private void whenTimePasses(Duration time) {
        for (long passed = 0; passed < time.toNanos(); passed += 1_000_000_000L) {
            this.now += 1_000_000_000L;
            this.notes.tick();
        }
    }

    private void thenTheSearchGives(KadNote... expected) throws InterruptedException, ExecutionException {
        assertThat(this.search).isCompleted();
        assertThat(this.search.get()).containsExactly(expected);
    }

    private void thenTheFileHasNotes(Hash16 file, int count) {
        assertThat(this.notes.notes(file)).hasSize(count);
    }

    private void thenKadWasAskedOnlyOnce() {
        assertThat(this.finder.searches).hasSize(1);
    }

    private void thenBothSearchesEnded() {
        assertThat(this.search).isCompleted();
        assertThat(this.joined).isCompleted();
    }

    private void thenTheSearchFails(String why) {
        assertThat(this.search).isCompletedExceptionally();
        assertThat(this.search.exceptionNow()).hasMessageContaining(why);
    }

    private void thenKadWasAskedFor(Hash16... files) {
        assertThat(this.finder.searches.keySet()).containsExactly(files);
    }

    private static KadNode.Note note(long publisher, String name, String comment, int rating) {
        return new KadNode.Note(new UInt128(7, publisher), name, SIZE, FileComment.of(comment, rating));
    }

    private static String publisher(long id) {
        return new UInt128(7, id).toHex();
    }
}
