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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Hash16;
import org.njmule.core.persist.format.KadIndexFiles;
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

/**
 * What other nodes published to us outlives a restart, as long as it would have lived anyway and within the limits a
 * live publish meets.
 */
class KadNodeIndexFilesTest {

    private static final int PORT = 4672;
    private static final int PUBLISHER = Ipv4.of(20, 1, 1, 1);
    private static final int SEARCHER = Ipv4.of(21, 1, 1, 1);
    private static final long SECOND = 1_000_000_000L;
    private static final long WALL = 1_800_000_000L;
    private static final long SIZE = 1_000;
    private static final String WORD = "ubuntu";

    @TempDir
    Path dir;

    private final SecureRandom random = new SecureRandom();
    private final AtomicLong time = new AtomicLong(1_000 * SECOND);
    private final Ticker ticker = new Ticker() {
        @Override
        public long read() {
            return KadNodeIndexFilesTest.this.time.get();
        }
    };
    private final List<Packet> sentToSearcher = new ArrayList<>();
    private KadNode node;
    private long wall = WALL;

    @BeforeEach
    void create() throws IOException {
        this.node = newNode();
    }

    @Test
    void shouldAnswerAfterARestartWhatWasPublishedBefore() throws IOException, DecodeException {
        givenAKeywordASourceAndANotePublishedToTheNode();
        givenTheNodeSavesItsIndex();

        whenTheNodeStartsAgainAfter(Duration.ofHours(1));

        thenAKeywordSearchFinds("ubuntu desktop.iso");
        thenASourceSearchFinds(1);
        thenANotesSearchFinds(1);
    }

    @Test
    void shouldNotLoadSourcesExpiredMeanwhile() throws IOException, DecodeException {
        givenAKeywordASourceAndANotePublishedToTheNode();
        givenTheNodeSavesItsIndex();

        whenTheNodeStartsAgainAfter(Duration.ofHours(6));

        thenAKeywordSearchFinds("ubuntu desktop.iso");
        thenASourceSearchFinds(0);
    }

    @Test
    void shouldNotLoadKeywordsAndNotesExpiredMeanwhile() throws IOException, DecodeException {
        givenAKeywordASourceAndANotePublishedToTheNode();
        givenTheNodeSavesItsIndex();
        givenTheNodeStartedAgainAfter(Duration.ofHours(6));

        whenTheNodeStartsAgainAfter(Duration.ofHours(19));

        thenAKeywordSearchFinds();
        thenANotesSearchFinds(0);
    }

    @Test
    void shouldLoadNoMoreThanALivePublishWouldStore() throws IOException, DecodeException {
        givenAnIndexFileOfSourcesForOneFile(KadIndex.MAX_SOURCES_PER_FILE + 100);
        givenAKeywordIndexFileWithAName("x".repeat(1_000), this.node.id());

        whenTheNodeStartsAgainAfter(Duration.ZERO);

        thenTheNodeHoldsSources(KadIndex.MAX_SOURCES_PER_FILE);
        thenAKeywordSearchFinds("x".repeat(KadIndex.MAX_KEYWORD_NAME));
    }

    @Test
    void shouldIgnoreTheKeywordsOfAnotherNodeAndTheKeysFarFromUs() throws IOException, DecodeException {
        givenAKeywordIndexFileWithAName("ubuntu other.iso", new UInt128(1, 2));
        givenAnIndexFileOfSourcesFarFromUs();

        whenTheNodeStartsAgainAfter(Duration.ZERO);

        thenAKeywordSearchFinds();
        thenTheNodeHoldsSources(0);
    }

    @Test
    void shouldStartEmptyFromDamagedFiles() throws IOException, DecodeException {
        givenDamagedIndexFiles();

        whenTheNodeStartsAgainAfter(Duration.ZERO);

        thenTheNodeHoldsSources(0);
        thenAKeywordSearchFinds();
    }

    @Test
    void shouldStillSpareALoadedKeywordAfterARestart() throws IOException {
        givenAKeywordPublishOfOursWhoseNodeReportsTheLoad(80);
        givenTheNodeSavesItsIndex();

        whenTheNodeStartsAgainAfter(Duration.ofDays(1));

        thenOurKeywordCannotBePublished();
    }

    private void givenAKeywordASourceAndANotePublishedToTheNode() {
        fromPublisher(new KadMessages.KeywordPublish(near(1), List.of(file("ubuntu desktop.iso"))).encode());
        fromPublisher(new KadMessages.SourcePublish(
                        near(2),
                        new UInt128(7, 7),
                        List.of(
                                new Tag.IntTag(TagName.id(FileTag.SOURCETYPE), 1),
                                new Tag.IntTag(TagName.id(FileTag.SOURCEPORT), 4662),
                                new Tag.IntTag(TagName.id(FileTag.FILESIZE), SIZE)))
                .encode());
        fromPublisher(new KadMessages.NotesPublish(
                        near(3),
                        new UInt128(8, 8),
                        List.of(
                                new Tag.StringTag(TagName.id(FileTag.FILENAME), "ubuntu.iso"),
                                new Tag.IntTag(TagName.id(FileTag.FILERATING), 4),
                                new Tag.StringTag(TagName.id(FileTag.DESCRIPTION), "good"),
                                new Tag.IntTag(TagName.id(FileTag.FILESIZE), SIZE)))
                .encode());
    }

    private void givenTheNodeSavesItsIndex() {
        this.node.saveIndex(this.wall);
    }

    private void givenAnIndexFileOfSourcesForOneFile(int sources) throws IOException {
        List<KadIndexFiles.Source> list = new ArrayList<>();
        for (int i = 0; i < sources; i++) {
            list.add(new KadIndexFiles.Source(
                    new UInt128(100, i), List.of(new KadIndexFiles.Entry(this.wall + 3_600, sourceTags(i)))));
        }
        writeSources(List.of(new KadIndexFiles.IndexKey(near(2), list)));
    }

    private void givenAnIndexFileOfSourcesFarFromUs() throws IOException {
        UInt128 far = this.node.id().xor(new UInt128(-1L, 0));
        writeSources(List.of(new KadIndexFiles.IndexKey(
                far,
                List.of(new KadIndexFiles.Source(
                        new UInt128(100, 1), List.of(new KadIndexFiles.Entry(this.wall + 3_600, sourceTags(1))))))));
    }

    private void givenAKeywordIndexFileWithAName(String name, UInt128 ownId) throws IOException {
        KadIndexFiles.Tracking tracking = new KadIndexFiles.Tracking(
                List.of(),
                List.of(new KadIndexFiles.PublishedName(name, 1)),
                List.of(new KadIndexFiles.Publisher(PUBLISHER, this.wall - 60, 0xFFFF)));
        KadIndexFiles.KeywordEntry entry = new KadIndexFiles.KeywordEntry(
                this.wall + 3_600,
                tracking,
                List.of(
                        new Tag.StringTag(TagName.id(FileTag.FILENAME), name),
                        new Tag.IntTag(TagName.id(FileTag.FILESIZE), SIZE)));
        KadIndexFiles.Keyword keyword = new KadIndexFiles.Keyword(
                near(1), List.of(new KadIndexFiles.KeywordSource(new UInt128(9, 9), List.of(entry))));
        Files.write(
                this.dir.resolve(KadNode.KEYWORD_INDEX_FILE),
                KadIndexFiles.writeKeywords(
                        new KadIndexFiles.KeywordIndex(4, this.wall + 3_600, ownId, List.of(keyword))));
    }

    private void givenDamagedIndexFiles() throws IOException {
        for (String name : List.of(
                KadNode.KEYWORD_INDEX_FILE,
                KadNode.SOURCE_INDEX_FILE,
                KadNode.NOTE_INDEX_FILE,
                KadNode.LOAD_INDEX_FILE)) {
            Files.write(this.dir.resolve(name), new byte[] {0, 0, 0, 0, 1, 2, 3});
        }
    }

    private void givenAKeywordPublishOfOursWhoseNodeReportsTheLoad(int load) {
        this.node.addContacts(new NodesDat.Contents(
                2,
                false,
                List.of(new NodesDat.Contact(
                        Keywords.id(WORD), PUBLISHER, PORT, 4662, 8, 0, NodesDat.UdpKey.NONE, true))));
        UInt128 target = Keywords.id(WORD);
        assertThat(this.node.publishKeyword(WORD, List.of(file("ubuntu.iso")))).isTrue();
        this.node.received(PUBLISHER, PORT, new KadMessages.Answer(target, List.of()).encode(), false, 0, 0);
        seconds(4);
        this.node.received(PUBLISHER, PORT, new KadMessages.PublishAnswer(target, load).encode(), false, 0, 0);
        seconds(30);
        assertThat(this.node.runningKeywordPublishes()).isZero();
    }

    private void givenTheNodeStartedAgainAfter(Duration pause) throws IOException {
        whenTheNodeStartsAgainAfter(pause);
    }

    // The index is saved by the steps before, at the time the test sets, and is not saved again here.
    private void whenTheNodeStartsAgainAfter(Duration pause) throws IOException {
        this.node.saveNodes();
        this.time.addAndGet(pause.toNanos());
        this.wall += pause.toSeconds();
        this.node = newNode();
        this.node.loadNodes();
        this.node.loadIndex(this.wall);
    }

    private void thenAKeywordSearchFinds(String... names) throws DecodeException {
        List<KadMessages.Entry> found = search(new KadMessages.KeywordSearch(near(1), 0, new byte[0]).encode());
        assertThat(found)
                .extracting(e -> Tags.stringValue(e.tags(), FileTag.FILENAME).orElseThrow())
                .containsExactly(names);
    }

    private void thenASourceSearchFinds(int count) throws DecodeException {
        assertThat(search(new KadMessages.SourceSearch(near(2), 0, SIZE).encode()))
                .hasSize(count);
    }

    private void thenANotesSearchFinds(int count) throws DecodeException {
        assertThat(search(new KadMessages.NotesSearch(near(3), SIZE).encode())).hasSize(count);
    }

    private void thenTheNodeHoldsSources(int count) {
        assertThat(this.node.indexed()[1]).isEqualTo(count);
    }

    private void thenOurKeywordCannotBePublished() {
        assertThat(this.node.contacts())
                .as("the contact came back from nodes.dat")
                .isEqualTo(1);
        assertThat(this.node.publishKeyword(WORD, List.of(file("ubuntu.iso")))).isFalse();
    }

    private KadNode newNode() throws IOException {
        return new KadNode(
                KadIdentity.loadOrCreate(this.dir, this.random),
                (ip, port, packet, receiverId, receiverKey, senderKey) -> {
                    if (ip == SEARCHER) {
                        this.sentToSearcher.add(packet);
                    }
                },
                (ip, port, userHash, options) -> {},
                new KadNode.Settings(4662, Hash16.ZERO, 0, ip -> false, ip -> false, PORT),
                this.dir,
                this.random,
                this.ticker);
    }

    private void fromPublisher(Packet packet) {
        this.node.received(PUBLISHER, PORT, packet, false, 0, 0);
    }

    private List<KadMessages.Entry> search(Packet request) throws DecodeException {
        this.sentToSearcher.clear();
        this.node.received(SEARCHER, PORT, request, false, 0, 0);
        List<KadMessages.Entry> found = new ArrayList<>();
        for (Packet p : this.sentToSearcher) {
            if (p.opcode() == Opcodes.Kad.SEARCH_RES.code()) {
                found.addAll(KadMessages.SearchAnswer.decode(p).results());
            }
        }
        return found;
    }

    private void writeSources(List<KadIndexFiles.IndexKey> keys) throws IOException {
        Files.write(
                this.dir.resolve(KadNode.SOURCE_INDEX_FILE),
                KadIndexFiles.writeSources(new KadIndexFiles.SourceIndex(2, this.wall + 3_600, keys)));
    }

    private void seconds(int seconds) {
        for (int s = 0; s < seconds; s++) {
            this.time.addAndGet(SECOND);
            this.node.tick();
        }
    }

    // Keys within the tolerance of our id, where publishes are taken.
    private UInt128 near(long n) {
        return this.node.id().xor(new UInt128(0, n));
    }

    private static List<Tag> sourceTags(int i) {
        return List.of(
                new Tag.IntTag(TagName.id(FileTag.SOURCETYPE), 1),
                new Tag.IntTag(TagName.id(FileTag.SOURCEIP), Integer.toUnsignedLong(Ipv4.of(30, i / 250, i % 250, 1))),
                new Tag.IntTag(TagName.id(FileTag.SOURCEPORT), 4662),
                new Tag.IntTag(TagName.id(FileTag.SOURCEUPORT), 4672),
                new Tag.IntTag(TagName.id(FileTag.FILESIZE), SIZE));
    }

    private static KadMessages.Entry file(String name) {
        return new KadMessages.Entry(
                new UInt128(5, 5),
                List.of(
                        new Tag.StringTag(TagName.id(FileTag.FILENAME), name),
                        new Tag.IntTag(TagName.id(FileTag.FILESIZE), SIZE)));
    }
}
