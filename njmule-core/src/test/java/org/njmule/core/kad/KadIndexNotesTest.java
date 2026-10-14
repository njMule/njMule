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
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.FileComment;
import org.njmule.core.protocol.kad.KadMessages;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.protocol.tag.Tags;
import org.njmule.core.util.Ipv4;

/** The notes other nodes publish to us: what is kept of them, how many, for how long, and what a search gets. */
class KadIndexNotesTest {

    private static final long NOW = 1_000_000_000_000L;
    private static final long HOUR = 3_600_000_000_000L;
    private static final long SIZE = 700_000_000L;
    private static final int IP = Ipv4.of(81, 2, 3, 4);

    /** A note as a publisher sends it. */
    private record Published(UInt128 publisher, int ip, List<Tag> tags) {}

    private final KadIndex index = new KadIndex();
    private final UInt128 file = id(1);
    private long now = NOW;
    private OptionalLong load;
    private final List<OptionalLong> loads = new ArrayList<>();
    private List<KadMessages.Entry> found;

    @Test
    void shouldAnswerASearchWithWhatTheNoteSays() {
        this.givenANote(id(100), IP, notes("Film.avi", SIZE, 4, "good copy", new Tag.IntTag(TagName.id(0x99), 7)));

        this.whenTheNotesAreSearchedFor(SIZE);

        this.thenTheLoadIs(0);
        this.thenTheNotesFoundComeFrom(id(100));
        this.thenTheNoteFoundIs("Film.avi", SIZE, 4, "good copy");
        this.thenTheNoteFoundHasOnlyItsOwnTags();
    }

    @Test
    void shouldKeepOneNotePerPublisherAndPerAddress() {
        this.givenANote(id(100), IP, notes("a.avi", SIZE, 1, "first", null));
        this.givenANote(id(100), IP + 1, notes("a.avi", SIZE, 2, "same publisher, new address", null));
        this.givenANote(id(101), IP + 1, notes("a.avi", SIZE, 3, "same address, new publisher", null));
        this.givenANote(id(102), IP + 2, notes("a.avi", SIZE, 5, "another", null));

        this.whenTheNotesAreSearchedFor(0);

        this.thenTheNotesFoundComeFrom(id(101), id(102));
        this.thenTheIndexHoldsNotes(2);
    }

    @Test
    void shouldRefuseANoteThatSaysNothingOrHasNoAddress() {
        this.whenTheseNotesArePublished(
                new Published(id(100), IP, notes("a.avi", SIZE, 0, "   ", null)),
                new Published(id(101), IP, notes("a.avi", SIZE, 9, "", null)),
                new Published(id(102), 0, notes("a.avi", SIZE, 3, "fine", null)));

        this.thenEveryNoteIsRefused();
        this.thenTheIndexHoldsNotes(0);
    }

    @Test
    void shouldKeepTheNewestNotesOfAFileWhenItHasTooMany() {
        this.givenNotesFromDifferentPublishers(KadIndex.MAX_NOTES_PER_FILE + 5);

        this.whenTheNotesAreSearchedFor(SIZE);

        this.thenTheLoadIs(100);
        this.thenSoManyNotesAreFound(KadIndex.MAX_NOTES_PER_FILE);
        this.thenTheOldestNotesAreGone(5);
    }

    @Test
    void shouldStopAtTheCapOfAllNotesTakingNoNewFile() {
        this.givenTheIndexIsFull();

        this.whenANoteOnANewFileComes();

        this.thenTheNoteIsRefused();
        this.thenTheIndexHoldsNotes(KadIndex.MAX_NOTES);
    }

    @Test
    void shouldForgetANoteNobodyRenewsWithinADay() {
        this.givenANote(id(100), IP, notes("a.avi", SIZE, 3, "fine", null));
        this.givenTimePasses(25 * HOUR);

        this.whenTheIndexIsCleaned();

        this.thenNoNoteIsFound();
        this.thenTheIndexHoldsNotes(0);
    }

    @Test
    void shouldLeaveOutNotesOnAFileOfAnotherSize() {
        this.givenANote(id(100), IP, notes("a.avi", SIZE, 3, "this size", null));
        this.givenANote(id(101), IP + 1, notes("a.avi", SIZE + 1, 3, "another size", null));
        this.givenANote(id(102), IP + 2, notes("a.avi", 0, 3, "no size", null));

        this.whenTheNotesAreSearchedFor(SIZE);

        this.thenTheNotesFoundComeFrom(id(100), id(102));
    }

    @Test
    void shouldCutLongNamesAndComments() {
        this.givenANote(id(100), IP, notes("n".repeat(1_000), SIZE, 3, "c".repeat(1_000), null));

        this.whenTheNotesAreSearchedFor(SIZE);

        this.thenTheNoteFoundIs("n".repeat(KadIndex.MAX_NOTE_NAME), SIZE, 3, "c".repeat(FileComment.MAX_LENGTH));
    }

    private void givenANote(UInt128 publisher, int ip, List<Tag> tags) {
        this.load = this.index.publishNote(this.file, publisher, tags, ip, this.now);
    }

    private void givenNotesFromDifferentPublishers(int count) {
        for (int i = 0; i < count; i++) {
            this.givenANote(id(1_000 + i), IP + i, notes("a.avi", SIZE, 3, "note " + i, null));
        }
    }

    private void givenTheIndexIsFull() {
        int files = KadIndex.MAX_NOTES / KadIndex.MAX_NOTES_PER_FILE + 1;
        int stored = 0;
        for (int f = 0; f < files && stored < KadIndex.MAX_NOTES; f++) {
            for (int n = 0; n < KadIndex.MAX_NOTES_PER_FILE && stored < KadIndex.MAX_NOTES; n++, stored++) {
                this.index.publishNote(id(10_000 + f), id(n), notes("a", SIZE, 1, "x", null), IP + n, this.now);
            }
        }
    }

    private void givenTimePasses(long nanos) {
        this.now += nanos;
    }

    private void whenANoteOnANewFileComes() {
        this.load = this.index.publishNote(id(2), id(3), notes("a", SIZE, 1, "x", null), IP, this.now);
    }

    private void whenTheseNotesArePublished(Published... notes) {
        for (Published n : notes) {
            this.loads.add(this.index.publishNote(this.file, n.publisher(), n.tags(), n.ip(), this.now));
        }
    }

    private void whenTheIndexIsCleaned() {
        this.index.clean(this.now);
        this.found = this.index.searchNotes(this.file, 0, this.now);
    }

    private void whenTheNotesAreSearchedFor(long size) {
        this.found = this.index.searchNotes(this.file, size, this.now);
    }

    private void thenTheLoadIs(int expected) {
        assertThat(this.load).hasValue(expected);
    }

    private void thenTheNoteIsRefused() {
        assertThat(this.load).isEmpty();
    }

    private void thenEveryNoteIsRefused() {
        assertThat(this.loads).isNotEmpty().allSatisfy(l -> assertThat(l).isEmpty());
    }

    private void thenTheNotesFoundComeFrom(UInt128... publishers) {
        assertThat(this.found).extracting(KadMessages.Entry::id).containsExactlyInAnyOrder(publishers);
    }

    private void thenTheNoteFoundIs(String name, long size, int rating, String comment) {
        assertThat(this.found).hasSize(1);
        List<Tag> tags = this.found.getFirst().tags();
        assertThat(Tags.stringValue(tags, FileTag.FILENAME)).contains(name);
        assertThat(Tags.intValue(tags, FileTag.FILESIZE)).hasValue(size);
        assertThat(Tags.intValue(tags, FileTag.FILERATING)).hasValue(rating);
        assertThat(Tags.stringValue(tags, FileTag.DESCRIPTION)).contains(comment);
    }

    private void thenTheNoteFoundHasOnlyItsOwnTags() {
        assertThat(this.found.getFirst().tags())
                .as("an unknown tag of the publisher is not passed on")
                .hasSize(4);
    }

    private void thenNoNoteIsFound() {
        assertThat(this.found).isEmpty();
    }

    private void thenSoManyNotesAreFound(int count) {
        assertThat(this.found).hasSize(count);
    }

    private void thenTheOldestNotesAreGone(int count) {
        List<UInt128> gone = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            gone.add(id(1_000 + i));
        }
        assertThat(this.found).extracting(KadMessages.Entry::id).doesNotContainAnyElementsOf(gone);
    }

    private void thenTheIndexHoldsNotes(int count) {
        assertThat(this.index.noteEntries()).isEqualTo(count);
    }

    private static UInt128 id(long low) {
        return new UInt128(0x1234L, low);
    }

    private static List<Tag> notes(String name, long size, long rating, String comment, Tag other) {
        List<Tag> tags = new ArrayList<>();
        tags.add(new Tag.StringTag(TagName.id(FileTag.FILENAME), name));
        if (rating != 0) {
            tags.add(new Tag.IntTag(TagName.id(FileTag.FILERATING), rating));
        }
        if (!comment.isEmpty()) {
            tags.add(new Tag.StringTag(TagName.id(FileTag.DESCRIPTION), comment));
        }
        if (size != 0) {
            tags.add(new Tag.IntTag(TagName.id(FileTag.FILESIZE), size));
        }
        if (other != null) {
            tags.add(other);
        }
        return tags;
    }
}
