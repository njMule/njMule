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
import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.FileComment;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.KadSearchInfo;
import org.njmule.core.persist.format.NodesDat;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.kad.KadMessages;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.protocol.tag.Tags;
import org.njmule.core.util.Ipv4;

/** Notes over Kad as one node sees them: stored and answered for others, published and searched for us. */
class KadNodeNotesTest {

    private static final int PORT = 4672;
    private static final int PEER = Ipv4.of(20, 1, 1, 1);
    private static final int OTHER = Ipv4.of(21, 1, 1, 1);
    private static final int SEARCHER = Ipv4.of(23, 1, 1, 1);
    private static final long SECOND = 1_000_000_000L;
    private static final long HOUR = 3_600 * SECOND;
    private static final long SIZE = 700_000_000L;

    /** A packet the node sent. */
    private record Sent(int ip, Packet packet) {}

    @TempDir
    Path dir;

    private final SecureRandom random = new SecureRandom();
    private final AtomicLong time = new AtomicLong(1_000 * SECOND);
    private final Ticker ticker = new Ticker() {
        @Override
        public long read() {
            return KadNodeNotesTest.this.time.get();
        }
    };
    private final List<Sent> sent = new ArrayList<>();
    private final List<KadNode.Note> found = new ArrayList<>();
    private final AtomicReference<FileComment> ourComment = new AtomicReference<>();
    private KadNode node;
    private Hash16 file;
    private UInt128 target;
    private boolean started;
    private boolean ended;

    @BeforeEach
    void create() throws IOException {
        this.node = new KadNode(
                KadIdentity.loadOrCreate(this.dir, this.random),
                (ip, port, packet, receiverId, receiverKey, senderKey) -> this.sent.add(new Sent(ip, packet)),
                (ip, port, userHash, options) -> {},
                new KadNode.Settings(4662, Hash16.ZERO, 0, ip -> false, ip -> false),
                this.dir,
                this.random,
                this.ticker);
    }

    @Test
    void shouldStoreANotePublishedNearUsAndAnswerItsSearch() throws Exception {
        this.givenANotePublishedBy(PEER, this.node.id(), note("Film.avi", SIZE, 4, "a good copy"));

        this.whenTheNotesAreSearchedForBy(SEARCHER, this.node.id(), SIZE);

        this.thenThePublisherHeardItWasStored(PEER, this.node.id());
        this.thenTheNodeIndexesNotes(1);
        this.thenTheSearcherGetsTheNote(SEARCHER, "Film.avi", 4, "a good copy");
    }

    @Test
    void shouldNotStoreANotePublishedFarFromUs() {
        this.givenANotePublishedBy(PEER, farFrom(this.node.id()), note("Film.avi", SIZE, 4, "a good copy"));

        this.whenTheNotesAreSearchedForBy(SEARCHER, farFrom(this.node.id()), SIZE);

        this.thenNothingWasSentTo(PEER);
        this.thenNothingWasSentTo(SEARCHER);
        this.thenTheNodeIndexesNotes(0);
    }

    @Test
    void shouldServeOnlyTwoNotePublishesAMinuteFromOneAddress() {
        this.givenANotePublishedBy(PEER, near(this.node.id(), 1), note("a.avi", SIZE, 1, "one"));
        this.givenANotePublishedBy(PEER, near(this.node.id(), 2), note("b.avi", SIZE, 2, "two"));

        this.whenANotePublishedBy(PEER, near(this.node.id(), 3), note("c.avi", SIZE, 3, "three"));

        this.thenThePublisherHeardStoredSoManyTimes(PEER, 2);
        this.thenTheNodeIndexesNotes(2);
    }

    @Test
    void shouldForgetANoteNobodyRenewsWithinADay() {
        this.givenANotePublishedBy(PEER, this.node.id(), note("Film.avi", SIZE, 4, "a good copy"));
        this.givenTimePasses(25 * HOUR);

        this.whenTheNotesAreSearchedForBy(SEARCHER, this.node.id(), SIZE);

        this.thenNothingWasSentTo(SEARCHER);
    }

    @Test
    void shouldPublishOurNoteWithItsNameRatingCommentAndSize() throws Exception {
        this.givenAContactAtTheFile();
        this.givenOurComment(FileComment.of("great film", 5));
        this.givenOurNoteIsPublished("Film.avi");

        this.whenTheLookupReachesTheNodeAt(PEER);

        this.thenThePublishWasStarted();
        this.thenTheLookupShowsAs(KadSearchInfo.Kind.STORE_NOTES);
        this.thenTheNodeAtWasSentOurNote(PEER, "Film.avi", 5, "great film");
    }

    @Test
    void shouldStopPublishingACommentTakenAwayMeanwhile() {
        this.givenAContactAtTheFile();
        this.givenOurComment(FileComment.of("great film", 5));
        this.givenOurNoteIsPublished("Film.avi");
        this.givenOurComment(FileComment.NONE);

        this.whenTheLookupReachesTheNodeAt(PEER);

        this.thenThePublishWasStarted();
        this.thenNoNoteWasSentTo(PEER);
        this.thenNoNotePublishRuns();
    }

    @Test
    void shouldAskTheNodesNearTheFileForItsNotesOfThatSize() throws Exception {
        this.givenAContactAtTheFile();
        this.givenTheNotesAreSearchedFor();

        this.whenTheLookupReachesTheNodeAt(PEER);

        this.thenTheSearchWasStarted();
        this.thenTheLookupShowsAs(KadSearchInfo.Kind.NOTES);
        this.thenTheNodeAtWasAskedForNotesOfSize(PEER, SIZE);
    }

    @Test
    void shouldTakeEachPublishersNoteOnceAndOnlyNotesOnThisFile() {
        this.givenAContactAtTheFile();
        this.givenTheNotesAreSearchedFor();
        this.givenTheLookupReachedTheNodeAt(PEER);

        this.whenTheNodeAtAnswers(
                PEER,
                new KadMessages.Entry(id(1), note("Film.avi", SIZE, 4, "good")),
                new KadMessages.Entry(id(1), note("Film.avi", SIZE, 1, "the same publisher again")),
                new KadMessages.Entry(id(2), note("Film.avi", SIZE, 0, "")),
                new KadMessages.Entry(id(3), note("Other.avi", SIZE + 1, 2, "another size")),
                new KadMessages.Entry(id(4), note("", 0, 3, "no name nor size")));

        this.thenTheNotesFoundComeFrom(id(1), id(4));
        this.thenTheNoteFromIs(id(1), "Film.avi", FileComment.of("good", 4));
    }

    @Test
    void shouldIgnoreNotesFromANodeThisSearchDidNotAsk() {
        this.givenAContactAtTheFile();
        this.givenAContactAt(OTHER, farFrom(this.target));
        this.givenASourceSearchReachedTheNodeAt(OTHER);
        this.givenTheNotesAreSearchedFor();
        this.givenTheLookupReachedTheNodeAt(PEER);

        this.whenTheNodeAtAnswers(OTHER, new KadMessages.Entry(id(1), note("Film.avi", SIZE, 4, "planted")));

        this.thenNoNoteIsFound();
    }

    @Test
    void shouldTellWhenTheSearchEnds() {
        this.givenAContactAtTheFile();
        this.givenTheNotesAreSearchedFor();
        this.givenTheLookupReachedTheNodeAt(PEER);

        this.whenTimePassesForTheNode(60 * SECOND);

        this.thenTheSearchEnded();
        this.thenNoNotesSearchRuns();
    }

    @Test
    void shouldNotSearchAFileThatIsLookedUpAlready() {
        this.givenAContactAtTheFile();
        this.givenASourceSearchOfTheFile();

        this.whenTheNotesAreSearchedFor();

        this.thenTheSearchWasNotStarted();
    }

    private void givenAContactAtTheFile() {
        this.file = hash(this.random);
        this.target = UInt128.of(this.file);
        this.givenAContactAt(PEER, this.target);
    }

    private void givenAContactAt(int ip, UInt128 id) {
        this.node.addContacts(new NodesDat.Contents(
                2, false, List.of(new NodesDat.Contact(id, ip, PORT, 4662, 8, 0, NodesDat.UdpKey.NONE, true))));
    }

    private void givenASourceSearchReachedTheNodeAt(int ip) {
        assertThat(this.node.findSources(farFrom(this.target).toHash16(), SIZE, s -> {}))
                .isTrue();
        this.receive(ip, new KadMessages.Answer(farFrom(this.target), List.of()).encode());
    }

    private void givenASourceSearchOfTheFile() {
        assertThat(this.node.findSources(this.file, SIZE, s -> {})).isTrue();
    }

    private void givenTheNotesAreSearchedFor() {
        this.whenTheNotesAreSearchedFor();
    }

    private void givenTheLookupReachedTheNodeAt(int ip) {
        this.whenTheLookupReachesTheNodeAt(ip);
    }

    private void givenOurComment(FileComment comment) {
        this.ourComment.set(comment);
    }

    private void givenOurNoteIsPublished(String name) {
        this.started = this.node.publishNote(this.file, name, SIZE, () -> Optional.of(this.ourComment.get()));
    }

    private void givenANotePublishedBy(int ip, UInt128 file, List<Tag> tags) {
        this.whenANotePublishedBy(ip, file, tags);
    }

    private void givenTimePasses(long nanos) {
        this.time.addAndGet(nanos);
    }

    private void whenANotePublishedBy(int ip, UInt128 file, List<Tag> tags) {
        this.receive(ip, new KadMessages.NotesPublish(file, id(77), tags).encode());
    }

    private void whenTheNotesAreSearchedForBy(int ip, UInt128 file, long size) {
        this.receive(ip, new KadMessages.NotesSearch(file, size).encode());
    }

    private void whenTheNotesAreSearchedFor() {
        this.started = this.node.findNotes(this.file, SIZE, this.found::addAll, () -> this.ended = true);
    }

    // The node answers the lookup, which then sends it the actual request.
    private void whenTheLookupReachesTheNodeAt(int ip) {
        this.receive(ip, new KadMessages.Answer(this.target, List.of()).encode());
        this.time.addAndGet(4 * SECOND);
        this.node.tick();
    }

    private void whenTheNodeAtAnswers(int ip, KadMessages.Entry... notes) {
        this.receive(ip, new KadMessages.SearchAnswer(id(9), this.target, List.of(notes)).encode());
    }

    private void whenTimePassesForTheNode(long nanos) {
        for (long passed = 0; passed < nanos; passed += SECOND) {
            this.time.addAndGet(SECOND);
            this.node.tick();
        }
    }

    private void thenThePublisherHeardItWasStored(int ip, UInt128 file) throws DecodeException {
        assertThat(KadMessages.PublishAnswer.decode(this.last(ip, Opcodes.Kad.PUBLISH_RES))
                        .target())
                .isEqualTo(file);
    }

    private void thenThePublisherHeardStoredSoManyTimes(int ip, int times) {
        assertThat(this.sent).filteredOn(s -> s.ip() == ip).hasSize(times);
    }

    private void thenTheNodeIndexesNotes(int count) {
        assertThat(this.node.indexedNotes()).isEqualTo(count);
    }

    private void thenTheSearcherGetsTheNote(int ip, String name, int rating, String comment) throws DecodeException {
        KadMessages.SearchAnswer answer = KadMessages.SearchAnswer.decode(this.last(ip, Opcodes.Kad.SEARCH_RES));
        assertThat(answer.sender()).isEqualTo(this.node.id());
        assertThat(answer.results()).singleElement().satisfies(e -> {
            assertThat(e.id()).as("the publisher's id").isEqualTo(id(77));
            assertThat(Tags.stringValue(e.tags(), FileTag.FILENAME)).contains(name);
            assertThat(Tags.intValue(e.tags(), FileTag.FILERATING)).hasValue(rating);
            assertThat(Tags.stringValue(e.tags(), FileTag.DESCRIPTION)).contains(comment);
        });
    }

    private void thenNothingWasSentTo(int ip) {
        assertThat(this.sent).filteredOn(s -> s.ip() == ip).isEmpty();
    }

    private void thenThePublishWasStarted() {
        assertThat(this.started).isTrue();
    }

    private void thenTheSearchWasStarted() {
        assertThat(this.started).isTrue();
    }

    private void thenTheSearchWasNotStarted() {
        assertThat(this.started).isFalse();
    }

    private void thenTheLookupShowsAs(KadSearchInfo.Kind kind) {
        assertThat(this.node.lookupInfos()).extracting(KadSearchInfo::kind).containsExactly(kind);
    }

    private void thenTheNodeAtWasSentOurNote(int ip, String name, int rating, String comment) throws DecodeException {
        KadMessages.NotesPublish publish =
                KadMessages.NotesPublish.decode(this.last(ip, Opcodes.Kad.PUBLISH_NOTES_REQ));
        assertThat(publish.file()).isEqualTo(this.target);
        assertThat(publish.publisher()).as("our Kad id").isEqualTo(this.node.id());
        assertThat(publish.tags())
                .extracting(t -> t.name().id())
                .containsExactly(FileTag.FILENAME, FileTag.FILERATING, FileTag.DESCRIPTION, FileTag.FILESIZE);
        assertThat(Tags.stringValue(publish.tags(), FileTag.FILENAME)).contains(name);
        assertThat(Tags.intValue(publish.tags(), FileTag.FILERATING)).hasValue(rating);
        assertThat(Tags.stringValue(publish.tags(), FileTag.DESCRIPTION)).contains(comment);
        assertThat(Tags.intValue(publish.tags(), FileTag.FILESIZE)).hasValue(SIZE);
    }

    private void thenNoNoteWasSentTo(int ip) {
        assertThat(this.last(ip, Opcodes.Kad.PUBLISH_NOTES_REQ)).isNull();
    }

    private void thenNoNotePublishRuns() {
        this.node.tick();
        assertThat(this.node.runningNotePublishes()).isZero();
    }

    private void thenTheNodeAtWasAskedForNotesOfSize(int ip, long size) throws DecodeException {
        KadMessages.NotesSearch search = KadMessages.NotesSearch.decode(this.last(ip, Opcodes.Kad.SEARCH_NOTES_REQ));
        assertThat(search.target()).isEqualTo(this.target);
        assertThat(search.size()).isEqualTo(size);
    }

    private void thenTheNotesFoundComeFrom(UInt128... publishers) {
        assertThat(this.found).extracting(KadNode.Note::publisher).containsExactly(publishers);
    }

    private void thenTheNoteFromIs(UInt128 publisher, String name, FileComment comment) {
        assertThat(this.found)
                .filteredOn(n -> n.publisher().equals(publisher))
                .singleElement()
                .satisfies(n -> {
                    assertThat(n.fileName()).isEqualTo(name);
                    assertThat(n.size()).isEqualTo(SIZE);
                    assertThat(n.comment()).isEqualTo(comment);
                });
    }

    private void thenNoNoteIsFound() {
        assertThat(this.found).isEmpty();
    }

    private void thenTheSearchEnded() {
        assertThat(this.ended).isTrue();
    }

    private void thenNoNotesSearchRuns() {
        assertThat(this.node.lookupInfos()).extracting(KadSearchInfo::kind).doesNotContain(KadSearchInfo.Kind.NOTES);
    }

    private void receive(int ip, Packet packet) {
        this.node.received(ip, PORT, packet, false, 0, 0);
    }

    private Packet last(int ip, Opcodes.Kad opcode) {
        for (int i = this.sent.size() - 1; i >= 0; i--) {
            Sent s = this.sent.get(i);
            if (s.ip() == ip && s.packet().opcode() == opcode.code()) {
                return s.packet();
            }
        }
        return null;
    }

    private static Hash16 hash(SecureRandom random) {
        byte[] bytes = new byte[Hash16.LENGTH];
        random.nextBytes(bytes);
        return Hash16.of(bytes);
    }

    private static UInt128 id(long low) {
        return new UInt128(0x5678L, low);
    }

    // Within the tolerance of a publish to us.
    private static UInt128 near(UInt128 id, long low) {
        return new UInt128(id.high(), id.low() ^ low);
    }

    // An id whose top bits differ from ours: far beyond the tolerance of a publish.
    private static UInt128 farFrom(UInt128 id) {
        return new UInt128(~id.high(), id.low());
    }

    private static List<Tag> note(String name, long size, long rating, String comment) {
        List<Tag> tags = new ArrayList<>();
        if (!name.isEmpty()) {
            tags.add(new Tag.StringTag(TagName.id(FileTag.FILENAME), name));
        }
        if (rating != 0) {
            tags.add(new Tag.IntTag(TagName.id(FileTag.FILERATING), rating));
        }
        if (!comment.isEmpty()) {
            tags.add(new Tag.StringTag(TagName.id(FileTag.DESCRIPTION), comment));
        }
        if (size != 0) {
            tags.add(new Tag.IntTag(TagName.id(FileTag.FILESIZE), size));
        }
        return tags;
    }
}
