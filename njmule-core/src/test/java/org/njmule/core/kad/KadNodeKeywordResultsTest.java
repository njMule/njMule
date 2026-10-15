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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Hash16;
import org.njmule.core.persist.format.NodesDat;
import org.njmule.core.protocol.kad.KadMessages;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.search.SearchExpr;
import org.njmule.core.util.Ipv4;

/** The files a keyword search finds must match the whole expression searched for, not only the word looked up. */
class KadNodeKeywordResultsTest {

    private static final int PORT = 4672;
    private static final int PEER = Ipv4.of(20, 1, 1, 1);
    private static final long SECOND = 1_000_000_000L;
    private static final String WORD = "ubuntu";

    @TempDir
    Path dir;

    private final SecureRandom random = new SecureRandom();
    private final AtomicLong time = new AtomicLong(1_000 * SECOND);
    private final Ticker ticker = new Ticker() {
        @Override
        public long read() {
            return KadNodeKeywordResultsTest.this.time.get();
        }
    };
    private final List<KadNode.FileResult> found = new ArrayList<>();
    private KadNode node;

    @BeforeEach
    void create() throws IOException {
        this.node = new KadNode(
                KadIdentity.loadOrCreate(this.dir, this.random),
                (ip, port, packet, receiverId, receiverKey, senderKey) -> {},
                (ip, port, userHash, options) -> {},
                new KadNode.Settings(4662, Hash16.ZERO, 0, ip -> false, ip -> false, PORT),
                this.dir,
                this.random,
                this.ticker);
        this.node.addContacts(new NodesDat.Contents(
                2,
                false,
                List.of(new NodesDat.Contact(Keywords.id(WORD), PEER, PORT, 4662, 8, 0, NodesDat.UdpKey.NONE, true))));
    }

    @Test
    void shouldKeepOnlyTheFilesMatchingTheWholeExpression() {
        givenASearchFor(new SearchExpr.And(
                new SearchExpr.Not(new SearchExpr.Keyword(WORD), new SearchExpr.Keyword("server")),
                new SearchExpr.NumericAttr(FileTag.FILESIZE, SearchExpr.Comparison.GREATER, 500)));

        whenTheNodeFinds(
                file("ubuntu desktop.iso", 1_000), file("ubuntu server.iso", 1_000), file("ubuntu small.iso", 100));

        thenTheFilesFoundAre("ubuntu desktop.iso");
    }

    @Test
    void shouldKeepOnlyTheFilesWithTheWordLookedUpWithoutAnExpression() {
        givenASearchWithoutAnExpression();

        whenTheNodeFinds(file("ubuntu desktop.iso", 1_000), file("kubuntu.iso", 1_000));

        thenTheFilesFoundAre("ubuntu desktop.iso");
    }

    private void givenASearchFor(SearchExpr expression) {
        search(Optional.of(expression));
    }

    private void givenASearchWithoutAnExpression() {
        search(Optional.empty());
    }

    private void search(Optional<SearchExpr> expression) {
        assertThat(this.node.searchKeyword(WORD, expression, this.found::addAll))
                .isPresent();
        this.node.received(PEER, PORT, new KadMessages.Answer(Keywords.id(WORD), List.of()).encode(), false, 0, 0);
        this.time.addAndGet(4 * SECOND);
        this.node.tick();
    }

    private void whenTheNodeFinds(KadMessages.Entry... files) {
        UInt128 target = Keywords.id(WORD);
        this.node.received(
                PEER, PORT, new KadMessages.SearchAnswer(target, target, List.of(files)).encode(), false, 0, 0);
    }

    private void thenTheFilesFoundAre(String... names) {
        assertThat(this.found).extracting(KadNode.FileResult::name).containsExactly(names);
    }

    private KadMessages.Entry file(String name, long size) {
        return new KadMessages.Entry(
                UInt128.random(this.random),
                List.of(
                        new Tag.StringTag(TagName.id(FileTag.FILENAME), name),
                        new Tag.IntTag(TagName.id(FileTag.FILESIZE), size)));
    }
}
