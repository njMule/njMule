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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.KadContactInfo;
import org.njmule.core.api.KadSearchInfo;
import org.njmule.core.persist.format.NodesDat;
import org.njmule.core.protocol.kad.KadMessages;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Ipv4;

class KadNodeTest {

    private static final int PORT = 4672;
    private static final int PEER = Ipv4.of(20, 1, 1, 1);
    private static final long SECOND = 1_000_000_000L;

    @TempDir
    Path dir;

    private final SecureRandom random = new SecureRandom();
    private final AtomicLong time = new AtomicLong(1_000 * SECOND);
    private final Ticker ticker = new Ticker() {
        @Override
        public long read() {
            return KadNodeTest.this.time.get();
        }
    };
    private final List<Packet> sentToPeer = new ArrayList<>();
    private final Set<Integer> blocked = new HashSet<>();
    private final List<Runnable> stops = new ArrayList<>();
    private final List<KadNode.FileResult> found = new ArrayList<>();
    private KadNode node;
    private UInt128 target;
    private Optional<Runnable> search;
    private List<KadContactInfo> contactInfos;
    private List<KadSearchInfo> lookupInfos;

    @BeforeEach
    void create() throws IOException {
        this.node = node();
    }

    @Test
    void shouldLearnNoContactsFromAnAnswerForAnotherTarget() {
        givenContactsAt(PEER);
        givenAKeywordSearchStarted("ubuntu");

        whenTheContactAnswersForAnotherTarget(Ipv4.of(30, 1, 1, 1), Ipv4.of(40, 1, 1, 1));

        thenTheContactsAre(1, "an answer for another target");
    }

    @Test
    void shouldLearnNoContactsFromAnAnswerWithMoreThanAskedFor() throws Exception {
        givenContactsAt(PEER);
        givenASourceSearchStarted();

        whenTheContactAnswersWithOneContactMoreThanAskedFor();

        thenTheContactsAre(1, "more contacts than asked for");
    }

    @Test
    void shouldLeaveOutAFilteredAddressOfAnAnswer() throws Exception {
        givenContactsAt(PEER);
        givenTheAddressIsFiltered(Ipv4.of(50, 1, 1, 1));
        givenASourceSearchStarted();

        whenTheContactAnswersTheLastRequestWith(Ipv4.of(30, 1, 1, 1), Ipv4.of(50, 1, 1, 1));

        thenTheContactsAre(2, "the filtered address is left out");
    }

    @Test
    void shouldListItsContactsClosestFirst() {
        givenContactsAt(PEER, Ipv4.of(70, 1, 1, 1), Ipv4.of(71, 1, 1, 1));
        givenAKeywordSearchStarted("ubuntu");

        whenTheContactsAreListed();

        thenTheContactsListedAre(3);
        thenTheContactsAreListedClosestFirst();
        thenTheDistanceOfAContactIsItsIdXorOurs();
    }

    @Test
    void shouldListItsLookups() {
        givenContactsAt(PEER, Ipv4.of(70, 1, 1, 1), Ipv4.of(71, 1, 1, 1));
        givenAKeywordSearchStarted("ubuntu");

        whenTheLookupsAreListed();

        thenTheOnlyLookupIs(KadSearchInfo.Kind.KEYWORD);
    }

    @Test
    void shouldNotStartAKeywordSearchAlreadyRunning() {
        givenContactsAt(PEER);
        givenAKeywordSearchStarted("ubuntu");

        whenAKeywordSearchStarts("ubuntu");

        thenTheSearchDidNotStart();
    }

    @Test
    void shouldStartAStoppedKeywordSearchAgainAtOnce() {
        givenContactsAt(PEER);
        givenAKeywordSearchStarted("ubuntu");
        givenAKeywordSearchWasRefused("ubuntu");
        givenTheFirstSearchStopped();

        whenAKeywordSearchStarts("ubuntu");

        thenTheSearchStarted();
    }

    // The first search's own end comes later.
    @Test
    void shouldLeaveANewerSearchForTheSameKeywordWhenAnOldOneStops() {
        givenContactsAt(PEER);
        givenAKeywordSearchStarted("ubuntu");
        givenTheFirstSearchStopped();
        givenAKeywordSearchStarted("ubuntu iso");

        whenTheFirstSearchStopsAgain();

        thenTheLookupsAre(1, "the newer search goes on");
    }

    @Test
    void shouldDropContactsAFilterNowRefuses() {
        givenContactsAt(PEER, Ipv4.of(70, 1, 1, 1));
        givenTheAddressIsFiltered(PEER);

        whenTheFiltersChange();

        thenTheContactAddressesAre("70.1.1.1:" + PORT);
    }

    @Test
    void shouldAwaitANodeAskedToConnectToUs() {
        whenANodeSaysHello(PEER);

        thenAConnectionIsAwaitedFrom(PEER);
        thenNoConnectionIsAwaitedFrom(Ipv4.of(21, 1, 1, 1));
    }

    @Test
    void shouldNoLongerAwaitANodeThatConnectedToUs() {
        givenANodeSaidHello(PEER);

        whenItsConnectionIsConfirmed(PEER);

        thenNoConnectionIsAwaitedFrom(PEER);
    }

    @Test
    void shouldStayOpenWhileAFirewallCheckRuns() {
        givenNodesConnectedToUs(PEER, Ipv4.of(21, 1, 1, 1));

        whenTheFirewallIsRechecked();

        thenWeAreNotFirewalled("while the check runs");
    }

    @Test
    void shouldKeepTheLastOutcomeOfACheckStartedDuringAnother() {
        givenNodesConnectedToUs(PEER, Ipv4.of(21, 1, 1, 1));
        givenAFirewallCheckRuns();
        givenNodesConnectedToUs(Ipv4.of(22, 1, 1, 1));

        whenTheFirewallIsRechecked();

        thenWeAreNotFirewalled("a check started before the last one ended");
    }

    @Test
    void shouldStillPassAnAnswerThatComesAfterTheLastRequestToTheSearch() {
        givenAContactAtTheKeyword("ubuntu");
        givenAKeywordSearchStarted("ubuntu");
        givenTheContactAnsweredTheLookup();
        givenSecondsPassed(4);
        givenSecondsPassed(4);

        whenTheContactAnswersTheSearchWithAFile("ubuntu.iso", 1000);

        thenTheFilesFoundAre("ubuntu.iso");
    }

    @Test
    void shouldGoOnSearchingAKeywordAfterUnusableResults() {
        givenAContactAtTheKeyword("ubuntu");
        givenAKeywordSearchStarted("ubuntu");
        givenTheLookupReachedTheContact();
        givenUnusableResultsCame(300);

        whenAKeywordSearchStarts("ubuntu");

        thenTheSearchDidNotStart("the search still runs");
    }

    // With no node left to ask, it ends after waiting a little for late answers.
    @Test
    void shouldEndAKeywordSearchWithNoNodeLeftToAsk() {
        givenAContactAtTheKeyword("ubuntu");
        givenAKeywordSearchStarted("ubuntu");
        givenTheLookupReachedTheContact();
        givenUnusableResultsCame(300);
        givenAKeywordSearchWasRefused("ubuntu");
        givenSecondsPassedWithTwoTicks(16);

        whenAKeywordSearchStarts("ubuntu");

        thenTheSearchStarted();
    }

    @Test
    void shouldSaveItsContactsToNodesDat() throws Exception {
        givenContactsAt(PEER);

        whenTheContactsAreSaved();

        thenTheSavedContactsAre(1);
    }

    @Test
    void shouldNotReplaceASavedNodesDatWithAFewContacts() throws Exception {
        givenContactsAt(PEER);
        givenTheContactsWereSaved(1);
        givenANodesDatWithContacts(KadNode.MIN_SAVED_CONTACTS);

        whenTheContactsAreSaved();

        thenTheSavedContactsAre(KadNode.MIN_SAVED_CONTACTS);
    }

    @Test
    void shouldNoLongerBeConnectedWhenNothingArrivesForTwentyMinutes() {
        givenContactsAt(PEER);
        givenANodePingedUs(PEER);

        whenMinutesPassInStepsOfTenSeconds(21);

        thenTheNodeIsNotConnected();
    }

    // Steps.

    private void givenContactsAt(int... ips) {
        List<NodesDat.Contact> contacts = new ArrayList<>();
        for (int ip : ips) {
            contacts.add(record(ip));
        }
        this.node.addContacts(new NodesDat.Contents(2, false, contacts));
    }

    private void givenAContactAtTheKeyword(String word) {
        this.target = Keywords.id(word);
        this.node.addContacts(new NodesDat.Contents(
                2,
                false,
                List.of(new NodesDat.Contact(this.target, PEER, PORT, 4662, 8, 0, NodesDat.UdpKey.NONE, true))));
    }

    private void givenTheAddressIsFiltered(int ip) {
        this.blocked.add(ip);
    }

    private void givenAKeywordSearchStarted(String text) {
        whenAKeywordSearchStarts(text);
        thenTheSearchStarted();
        this.stops.add(this.search.orElseThrow());
    }

    private void givenAKeywordSearchWasRefused(String text) {
        whenAKeywordSearchStarts(text);
        thenTheSearchDidNotStart();
    }

    private void givenTheFirstSearchStopped() {
        this.stops.getFirst().run();
    }

    private void givenASourceSearchStarted() {
        assertThat(this.node.findSources(hash(), 1000, sources -> {})).isTrue();
    }

    private void givenANodeSaidHello(int ip) {
        whenANodeSaysHello(ip);
        thenAConnectionIsAwaitedFrom(ip);
    }

    private void givenNodesConnectedToUs(int... ips) {
        for (int ip : ips) {
            hello(ip);
            this.node.reachableConfirmed(ip);
        }
        thenWeAreNotFirewalled();
    }

    private void givenAFirewallCheckRuns() {
        whenTheFirewallIsRechecked();
        thenWeAreNotFirewalled("while the check runs");
    }

    private void givenTheContactAnsweredTheLookup() {
        answer(this.target);
    }

    private void givenSecondsPassed(int seconds) {
        this.time.addAndGet(seconds * SECOND);
        this.node.tick();
    }

    private void givenSecondsPassedWithTwoTicks(int seconds) {
        this.time.addAndGet(seconds * SECOND);
        this.node.tick();
        this.node.tick();
    }

    private void givenTheLookupReachedTheContact() {
        givenTheContactAnsweredTheLookup();
        givenSecondsPassed(4);
        assertThat(this.sentToPeer).extracting(Packet::opcode).contains(Opcodes.Kad.SEARCH_KEY_REQ.code());
    }

    private void givenUnusableResultsCame(int count) {
        List<KadMessages.Entry> junk = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            junk.add(new KadMessages.Entry(UInt128.random(this.random), List.of()));
        }
        this.node.received(
                PEER, PORT, new KadMessages.SearchAnswer(this.target, this.target, junk).encode(), false, 0, 0);
        this.node.tick();
    }

    private void givenTheContactsWereSaved(int count) throws IOException {
        whenTheContactsAreSaved();
        thenTheSavedContactsAre(count);
    }

    private void givenANodesDatWithContacts(int count) throws IOException {
        List<NodesDat.Contact> many = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            many.add(record(Ipv4.of(70 + i, 1, 1, 1)));
        }
        Files.write(nodesFile(), NodesDat.write(many));
    }

    private void givenANodePingedUs(int ip) {
        this.node.received(ip, PORT, Packet.of(Protocol.KAD, Opcodes.Kad.PING.code(), new byte[0]), false, 0, 0);
        assertThat(this.node.isConnected()).isTrue();
    }

    private void whenTheContactAnswersForAnotherTarget(int... ips) {
        answer(UInt128.random(this.random), contacts(ips));
    }

    private void whenTheContactAnswersWithOneContactMoreThanAskedFor() throws Exception {
        KadMessages.Request request = lastRequest();
        List<KadMessages.Contact> tooMany = new ArrayList<>();
        for (int i = 0; i <= request.count(); i++) {
            tooMany.add(contact(Ipv4.of(60 + i, 1, 1, 1)));
        }
        answer(request.target(), tooMany.toArray(KadMessages.Contact[]::new));
    }

    private void whenTheContactAnswersTheLastRequestWith(int... ips) throws Exception {
        answer(lastRequest().target(), contacts(ips));
    }

    private void whenTheContactsAreListed() {
        this.contactInfos = this.node.contactInfos();
    }

    private void whenTheLookupsAreListed() {
        this.lookupInfos = this.node.lookupInfos();
    }

    private void whenAKeywordSearchStarts(String text) {
        this.search = this.node.searchKeyword(text, Optional.empty(), this.found::addAll);
    }

    private void whenTheFirstSearchStopsAgain() {
        this.stops.getFirst().run();
    }

    private void whenTheFiltersChange() {
        this.node.filtersChanged();
    }

    private void whenANodeSaysHello(int ip) {
        hello(ip);
    }

    private void whenItsConnectionIsConfirmed(int ip) {
        this.node.reachableConfirmed(ip);
    }

    private void whenTheFirewallIsRechecked() {
        this.node.recheckFirewall();
        this.node.tick();
    }

    private void whenTheContactAnswersTheSearchWithAFile(String name, long size) {
        KadMessages.Entry file = new KadMessages.Entry(
                UInt128.random(this.random),
                List.of(
                        new Tag.StringTag(TagName.id(FileTag.FILENAME), name),
                        new Tag.IntTag(TagName.id(FileTag.FILESIZE), size)));
        this.node.received(
                PEER,
                PORT,
                new KadMessages.SearchAnswer(this.target, this.target, List.of(file)).encode(),
                false,
                0,
                0);
    }

    private void whenTheContactsAreSaved() {
        this.node.saveNodes();
    }

    private void whenMinutesPassInStepsOfTenSeconds(int minutes) {
        for (int s = 0; s < minutes * 60; s += 10) {
            this.time.addAndGet(10 * SECOND);
            this.node.tick();
        }
    }

    private void thenTheContactsAre(int count, String because) {
        assertThat(this.node.contacts()).as(because).isEqualTo(count);
    }

    private void thenTheContactsListedAre(int count) {
        assertThat(this.contactInfos).hasSize(count);
    }

    private void thenTheContactsAreListedClosestFirst() {
        assertThat(this.contactInfos)
                .extracting(KadContactInfo::distance)
                .as("closest first")
                .isSorted();
    }

    private void thenTheDistanceOfAContactIsItsIdXorOurs() {
        assertThat(this.contactInfos.getFirst().distance())
                .isEqualTo(UInt128.parseHex(this.contactInfos.getFirst().id())
                        .xor(this.node.id())
                        .toHex());
    }

    private void thenTheOnlyLookupIs(KadSearchInfo.Kind kind) {
        assertThat(this.lookupInfos)
                .singleElement()
                .satisfies(l -> assertThat(l.kind()).isEqualTo(kind));
    }

    private void thenTheSearchStarted() {
        assertThat(this.search).isPresent();
    }

    private void thenTheSearchDidNotStart() {
        assertThat(this.search).isEmpty();
    }

    private void thenTheSearchDidNotStart(String because) {
        assertThat(this.search).as(because).isEmpty();
    }

    private void thenTheLookupsAre(int count, String because) {
        assertThat(this.node.lookupInfos()).as(because).hasSize(count);
    }

    private void thenTheContactAddressesAre(String... addresses) {
        assertThat(this.node.contactInfos()).extracting(KadContactInfo::address).containsExactly(addresses);
    }

    private void thenAConnectionIsAwaitedFrom(int ip) {
        assertThat(this.node.awaitsConnectionFrom(ip)).isTrue();
    }

    private void thenNoConnectionIsAwaitedFrom(int ip) {
        assertThat(this.node.awaitsConnectionFrom(ip)).isFalse();
    }

    private void thenWeAreNotFirewalled() {
        assertThat(this.node.isFirewalled()).isFalse();
    }

    private void thenWeAreNotFirewalled(String because) {
        assertThat(this.node.isFirewalled()).as(because).isFalse();
    }

    private void thenTheFilesFoundAre(String... names) {
        assertThat(this.found).extracting(KadNode.FileResult::name).containsExactly(names);
    }

    private void thenTheSavedContactsAre(int count) throws IOException {
        assertThat(NodesDat.read(Files.readAllBytes(nodesFile())).contacts()).hasSize(count);
    }

    private void thenTheNodeIsNotConnected() {
        assertThat(this.node.isConnected()).isFalse();
    }

    // Helpers.

    private KadNode node() throws IOException {
        return new KadNode(
                KadIdentity.loadOrCreate(this.dir, this.random),
                (ip, port, packet, receiverId, receiverKey, senderKey) -> {
                    if (ip == PEER) {
                        this.sentToPeer.add(packet);
                    }
                },
                (ip, port, userHash, options) -> {},
                new KadNode.Settings(4662, Hash16.ZERO, 0, ip -> false, this.blocked::contains),
                this.dir,
                this.random,
                this.ticker);
    }

    // A hello from a node recent enough to check our TCP port.
    private void hello(int ip) {
        KadMessages.Self sender = new KadMessages.Self(UInt128.random(this.random), 4662, 8);
        this.node.received(ip, PORT, new KadMessages.Hello(sender, List.of()).encode(false), false, 0, 0);
    }

    private void answer(UInt128 lookup, KadMessages.Contact... contacts) {
        this.node.received(PEER, PORT, new KadMessages.Answer(lookup, List.of(contacts)).encode(), false, 0, 0);
    }

    private Path nodesFile() {
        return this.dir.resolve(KadNode.NODES_FILE);
    }

    private KadMessages.Contact[] contacts(int... ips) {
        KadMessages.Contact[] contacts = new KadMessages.Contact[ips.length];
        for (int i = 0; i < ips.length; i++) {
            contacts[i] = contact(ips[i]);
        }
        return contacts;
    }

    private KadMessages.Request lastRequest() throws Exception {
        for (int i = this.sentToPeer.size() - 1; i >= 0; i--) {
            Packet packet = this.sentToPeer.get(i);
            if (packet.opcode() == Opcodes.Kad.REQ.code()) {
                return KadMessages.Request.decode(packet);
            }
        }
        throw new AssertionError("no lookup request sent");
    }

    private Hash16 hash() {
        byte[] bytes = new byte[16];
        this.random.nextBytes(bytes);
        return Hash16.of(bytes);
    }

    private KadMessages.Contact contact(int ip) {
        return new KadMessages.Contact(UInt128.random(this.random), ip, PORT, 4662, 8);
    }

    private NodesDat.Contact record(int ip) {
        return new NodesDat.Contact(UInt128.random(this.random), ip, PORT, 4662, 8, 0, NodesDat.UdpKey.NONE, true);
    }
}
