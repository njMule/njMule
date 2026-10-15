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
package org.njmule.core.protocol.kad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.njmule.core.kad.UInt128;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Ipv4;

class KadMessagesTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final UInt128 ID = UInt128.parseHex("00112233445566778899AABBCCDDEEFF");
    private static final UInt128 OTHER = UInt128.parseHex("FFEEDDCCBBAA99887766554433221100");
    private static final KadMessages.Contact CONTACT =
            new KadMessages.Contact(OTHER, Ipv4.of(1, 2, 3, 4), 4672, 4662, 10);
    private static final List<Tag> FILE_NAME_TAGS = List.of(new Tag.StringTag(TagName.id(0x01), "a file name.iso"));
    private static final KadMessages.Entry ENTRY = new KadMessages.Entry(OTHER, FILE_NAME_TAGS);
    private static final KadMessages.Entry EMPTY_ENTRY = new KadMessages.Entry(OTHER, List.of());
    private static final KadMessages.Entry SMALL_ENTRY =
            new KadMessages.Entry(OTHER, List.of(new Tag.StringTag(TagName.id(0x01), "a.iso")));
    private static final KadMessages.Entry HUGE_ENTRY =
            new KadMessages.Entry(ID, List.of(new Tag.StringTag(TagName.id(0x01), "x".repeat(2000))));

    private Object sent;
    private Supplier<Packet> encoding;
    private Decoding decoding;
    private Packet packet;
    private Object decoded;
    private KadMessages.Hello hello;
    private List<KadMessages.Entry> results;
    private List<Packet> answers;
    private byte[] garbage;
    private Throwable exception;

    @Test
    void shouldEncodeIdsByWordsAndAddressesInHostOrder() {
        givenAnAnswerWith(List.of(CONTACT));

        whenItIsEncoded();

        thenNoExceptionOccurred();
        thenThePayloadIs("33221100"
                + "77665544"
                + "bbaa9988"
                + "ffeeddcc"
                + "01"
                + "ccddeeff"
                + "8899aabb"
                + "44556677"
                + "00112233"
                + "04030201"
                + "4012"
                + "3612"
                + "0a");
    }

    @Test
    void shouldRoundTripAnAnswer() {
        givenAnAnswerWith(List.of(CONTACT));
        givenItIsEncoded();

        whenItIsDecoded();

        thenNoExceptionOccurred();
        thenTheDecodedMessageIsTheSentOne();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 11, KadMessages.MAX_LOOKUP_CONTACTS})
    void shouldRoundTripALookupRequestForOneToThirtyOneContacts(int count) {
        givenALookupRequestFor(count);
        givenItIsEncoded();

        whenItIsDecoded();

        thenNoExceptionOccurred();
        thenTheDecodedMessageIsTheSentOne();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, KadMessages.MAX_LOOKUP_CONTACTS + 1, 0x20, 0x100})
    void shouldRefuseALookupRequestForAnyOtherNumberOfContacts(int count) {
        whenALookupRequestIsMadeFor(count);

        thenExceptionOccurred(IllegalArgumentException.class, "count " + count);
    }

    @Test
    void shouldRoundTripAHello() {
        givenAHello();
        givenItIsEncoded();

        whenItIsDecoded();

        thenNoExceptionOccurred();
        thenTheDecodedMessageIsTheSentOne();
    }

    @Test
    void shouldGiveTheHelloAnswerItsOwnOpcode() {
        givenAHello();

        whenItIsEncodedAsAnAnswer();

        thenThePacketOpcodeIs(0x19);
    }

    @Test
    void shouldRoundTripABootstrapAnswer() {
        givenABootstrapAnswerWith(List.of(new KadMessages.Contact(OTHER, Ipv4.of(5, 6, 7, 8), 1, 2, 9)));
        givenItIsEncoded();

        whenItIsDecoded();

        thenNoExceptionOccurred();
        thenTheDecodedMessageIsTheSentOne();
    }

    @Test
    void shouldRoundTripAHelloAck() {
        givenAHelloAckFrom(ID);
        givenItIsEncoded();

        whenItIsDecoded();

        thenNoExceptionOccurred();
        thenTheDecodedMessageIsTheSentOne();
    }

    @Test
    void shouldRoundTripAPong() {
        givenAPongFromThePort(4672);
        givenItIsEncoded();

        whenItIsDecoded();

        thenNoExceptionOccurred();
        thenTheDecodedMessageIsTheSentOne();
    }

    @Test
    void shouldRoundTripAFirewallAnswer() {
        givenAFirewallAnswerFor(Ipv4.of(9, 8, 7, 6));
        givenItIsEncoded();

        whenItIsDecoded();

        thenNoExceptionOccurred();
        thenTheDecodedMessageIsTheSentOne();
    }

    @Test
    void shouldRoundTripAFirewallCheck() {
        givenAFirewallCheck(4662, OTHER, 1);
        givenItIsEncoded();

        whenItIsDecoded();

        thenNoExceptionOccurred();
        thenTheDecodedMessageIsTheSentOne();
    }

    @Test
    void shouldRoundTripAKeywordSearch() {
        givenAKeywordSearchFor(new byte[] {1, 3, 0, 'a', 'b', 'c'});
        givenItIsEncoded();

        whenItIsDecoded();

        thenNoExceptionOccurred();
        thenTheDecodedMessageIsTheSentOne();
    }

    @Test
    void shouldFlagTheStartOfAKeywordSearch() {
        givenAKeywordSearchFor(new byte[] {1, 3, 0, 'a', 'b', 'c'});

        whenItIsEncoded();

        thenNoExceptionOccurred();
        thenThePayloadContains("0080");
    }

    @Test
    void shouldRoundTripASourceSearch() {
        givenASourceSearchForTheSize(5_000_000_000L);
        givenItIsEncoded();

        whenItIsDecoded();

        thenNoExceptionOccurred();
        thenTheDecodedMessageIsTheSentOne();
    }

    @Test
    void shouldRoundTripAKeywordPublish() {
        givenAKeywordPublishOf(List.of(ENTRY));
        givenItIsEncoded();

        whenItIsDecoded();

        thenNoExceptionOccurred();
        thenTheDecodedMessageIsTheSentOne();
    }

    @Test
    void shouldRoundTripASourcePublish() {
        givenASourcePublishWith(FILE_NAME_TAGS);
        givenItIsEncoded();

        whenItIsDecoded();

        thenNoExceptionOccurred();
        thenTheDecodedMessageIsTheSentOne();
    }

    @Test
    void shouldRoundTripAPublishAnswer() {
        givenAPublishAnswerWithTheLoad(7);
        givenItIsEncoded();

        whenItIsDecoded();

        thenNoExceptionOccurred();
        thenTheDecodedMessageIsTheSentOne();
    }

    @Test
    void shouldSplitManyResultsIntoAnswersThatFitTheirSize() throws DecodeException {
        givenTheResults(Collections.nCopies(100, ENTRY));

        whenTheyAreSplitIntoAnswersOf(1420);

        thenThereAreSeveralAnswers();
        thenEveryAnswerFits(1420);
        thenEveryAnswerTargets(ID);
        thenTheAnswersCarryResults(100);
    }

    @Test
    void shouldLeaveOutAnEntryTooLargeForAnAnswer() throws DecodeException {
        givenTheResults(List.of(SMALL_ENTRY, HUGE_ENTRY, SMALL_ENTRY));

        whenTheyAreSplitIntoAnswersOf(1420);

        thenEveryAnswerFits(1420);
        thenTheAnswersCarryResults(2);
    }

    @Test
    void shouldMakeNoAnswerOfAnEntryTooLargeAlone() {
        givenTheResults(List.of(HUGE_ENTRY));

        whenTheyAreSplitIntoAnswersOf(1420);

        thenThereAreNoAnswers();
    }

    @Test
    void shouldEncodeAnAnswerOf255Contacts() {
        givenAnAnswerWith(Collections.nCopies(255, CONTACT));

        whenItIsEncoded();

        thenNoExceptionOccurred();
        thenAPacketIsEncoded();
    }

    @Test
    void shouldRefuseToEncodeAnAnswerOf256Contacts() {
        givenAnAnswerWith(Collections.nCopies(256, CONTACT));

        whenItIsEncoded();

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldRefuseToEncodeABootstrapAnswerOf65536Contacts() {
        givenABootstrapAnswerWith(Collections.nCopies(0x10000, CONTACT));

        whenItIsEncoded();

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldRefuseToEncodeASearchAnswerOf65536Results() {
        givenASearchAnswerWith(Collections.nCopies(0x10000, EMPTY_ENTRY));

        whenItIsEncoded();

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldRefuseToEncodeAKeywordPublishOf65536Files() {
        givenAKeywordPublishOf(Collections.nCopies(0x10000, EMPTY_ENTRY));

        whenItIsEncoded();

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldRefuseAnAnswerWithMoreContactsThanBytes() {
        givenAnAnswerPacketOf("00".repeat(16) + "02" + "00".repeat(25));

        whenItIsDecoded();

        thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void shouldRefuseALookupRequestForNoContacts() {
        givenALookupRequestPacketOf("00" + "00".repeat(32));

        whenItIsDecoded();

        thenExceptionOccurred(DecodeException.class);
    }

    @Property
    void shouldOnlyFailWithDecodeExceptionOnGarbage(@ForAll @Size(max = 120) byte[] data) {
        givenTheGarbage(data);

        whenEveryDecoderReadsIt();

        thenOnlyDecodeExceptionsOccurred();
    }

    private void givenAnAnswerWith(List<KadMessages.Contact> contacts) {
        KadMessages.Answer answer = new KadMessages.Answer(ID, contacts);
        this.sent = answer;
        this.encoding = answer::encode;
        this.decoding = KadMessages.Answer::decode;
    }

    private void givenALookupRequestFor(int count) {
        KadMessages.Request request = new KadMessages.Request(count, ID, OTHER);
        this.sent = request;
        this.encoding = request::encode;
        this.decoding = KadMessages.Request::decode;
    }

    private void givenAHello() {
        this.hello = new KadMessages.Hello(
                new KadMessages.Self(ID, 4662, KadMessages.VERSION), List.of(new Tag.IntTag(TagName.id(0xFC), 4672)));
        KadMessages.Hello request = this.hello;
        this.sent = request;
        this.encoding = () -> request.encode(false);
        this.decoding = KadMessages.Hello::decode;
    }

    private void givenABootstrapAnswerWith(List<KadMessages.Contact> contacts) {
        KadMessages.BootstrapAnswer bootstrap =
                new KadMessages.BootstrapAnswer(new KadMessages.Self(ID, 4662, 10), contacts);
        this.sent = bootstrap;
        this.encoding = bootstrap::encode;
        this.decoding = KadMessages.BootstrapAnswer::decode;
    }

    private void givenAHelloAckFrom(UInt128 sender) {
        this.sent = sender;
        this.encoding = () -> KadMessages.helloAck(sender);
        this.decoding = KadMessages::decodeHelloAck;
    }

    private void givenAPongFromThePort(int port) {
        this.sent = port;
        this.encoding = () -> KadMessages.pong(port);
        this.decoding = KadMessages::decodePong;
    }

    private void givenAFirewallAnswerFor(int ip) {
        this.sent = ip;
        this.encoding = () -> KadMessages.firewallAnswer(ip);
        this.decoding = KadMessages::decodeFirewallAnswer;
    }

    private void givenAFirewallCheck(int tcpPort, UInt128 userHash, int connectOptions) {
        KadMessages.FirewallCheck check = new KadMessages.FirewallCheck(tcpPort, userHash, connectOptions);
        this.sent = check;
        this.encoding = check::encode;
        this.decoding = KadMessages.FirewallCheck::decode;
    }

    private void givenAKeywordSearchFor(byte[] expression) {
        KadMessages.KeywordSearch keyword = new KadMessages.KeywordSearch(ID, 0, expression);
        this.sent = keyword;
        this.encoding = keyword::encode;
        this.decoding = KadMessages.KeywordSearch::decode;
    }

    private void givenASourceSearchForTheSize(long size) {
        KadMessages.SourceSearch sources = new KadMessages.SourceSearch(ID, 0, size);
        this.sent = sources;
        this.encoding = sources::encode;
        this.decoding = KadMessages.SourceSearch::decode;
    }

    private void givenAKeywordPublishOf(List<KadMessages.Entry> files) {
        KadMessages.KeywordPublish publish = new KadMessages.KeywordPublish(ID, files);
        this.sent = publish;
        this.encoding = publish::encode;
        this.decoding = KadMessages.KeywordPublish::decode;
    }

    private void givenASourcePublishWith(List<Tag> tags) {
        KadMessages.SourcePublish source = new KadMessages.SourcePublish(ID, OTHER, tags);
        this.sent = source;
        this.encoding = source::encode;
        this.decoding = KadMessages.SourcePublish::decode;
    }

    private void givenAPublishAnswerWithTheLoad(int load) {
        KadMessages.PublishAnswer published = new KadMessages.PublishAnswer(ID, load);
        this.sent = published;
        this.encoding = published::encode;
        this.decoding = KadMessages.PublishAnswer::decode;
    }

    private void givenASearchAnswerWith(List<KadMessages.Entry> entries) {
        KadMessages.SearchAnswer answer = new KadMessages.SearchAnswer(OTHER, ID, entries);
        this.sent = answer;
        this.encoding = answer::encode;
        this.decoding = KadMessages.SearchAnswer::decode;
    }

    private void givenAnAnswerPacketOf(String hex) {
        this.packet = Packet.of(Protocol.KAD, 0x29, HEX.parseHex(hex));
        this.decoding = KadMessages.Answer::decode;
    }

    private void givenALookupRequestPacketOf(String hex) {
        this.packet = Packet.of(Protocol.KAD, 0x21, HEX.parseHex(hex));
        this.decoding = KadMessages.Request::decode;
    }

    private void givenItIsEncoded() {
        this.packet = this.encoding.get();
    }

    private void givenTheResults(List<KadMessages.Entry> entries) {
        this.results = entries;
    }

    private void givenTheGarbage(byte[] data) {
        this.garbage = data;
    }

    private void whenItIsEncoded() {
        this.exception = catchThrowable(() -> this.packet = this.encoding.get());
    }

    private void whenItIsEncodedAsAnAnswer() {
        this.packet = this.hello.encode(true);
    }

    private void whenItIsDecoded() {
        this.exception = catchThrowable(() -> this.decoded = this.decoding.decode(this.packet));
    }

    private void whenALookupRequestIsMadeFor(int count) {
        this.exception = catchThrowable(() -> this.sent = new KadMessages.Request(count, ID, OTHER));
    }

    private void whenTheyAreSplitIntoAnswersOf(int maxBytes) {
        this.answers = KadMessages.SearchAnswer.split(OTHER, ID, this.results, maxBytes);
    }

    private void whenEveryDecoderReadsIt() {
        Packet received = Packet.of(Protocol.KAD, 0x29, this.garbage);
        this.exception = catchThrowable(() -> {
            for (Decoder d : List.<Decoder>of(
                    () -> KadMessages.Answer.decode(received),
                    () -> KadMessages.Request.decode(received),
                    () -> KadMessages.Hello.decode(received),
                    () -> KadMessages.BootstrapAnswer.decode(received),
                    () -> KadMessages.SearchAnswer.decode(received),
                    () -> KadMessages.KeywordPublish.decode(received),
                    () -> KadMessages.SourcePublish.decode(received),
                    () -> KadMessages.KeywordSearch.decode(received),
                    () -> KadMessages.SourceSearch.decode(received),
                    () -> KadMessages.PublishAnswer.decode(received),
                    () -> KadMessages.FirewallCheck.decode(received))) {
                try {
                    d.decode();
                } catch (DecodeException expected) {
                    // malformed
                }
            }
        });
    }

    private void thenNoExceptionOccurred() {
        assertThat(this.exception).isNull();
    }

    private void thenExceptionOccurred(Class<? extends Exception> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private void thenExceptionOccurred(Class<? extends Exception> type, String description) {
        assertThat(this.exception).as(description).isInstanceOf(type);
    }

    private void thenThePayloadIs(String hex) {
        assertThat(HEX.formatHex(this.packet.payload())).isEqualTo(hex);
    }

    private void thenThePayloadContains(String hex) {
        assertThat(HEX.formatHex(this.packet.payload())).contains(hex);
    }

    private void thenThePacketOpcodeIs(int opcode) {
        assertThat(this.packet.opcode()).isEqualTo(opcode);
    }

    private void thenAPacketIsEncoded() {
        assertThat(this.packet).isNotNull();
    }

    private void thenTheDecodedMessageIsTheSentOne() {
        assertThat(this.decoded).isEqualTo(this.sent);
    }

    private void thenThereAreSeveralAnswers() {
        assertThat(this.answers).hasSizeGreaterThan(1);
    }

    private void thenThereAreNoAnswers() {
        assertThat(this.answers).isEmpty();
    }

    private void thenEveryAnswerFits(int maxBytes) {
        for (Packet p : this.answers) {
            assertThat(p.length() + 2).isLessThanOrEqualTo(maxBytes);
        }
    }

    private void thenEveryAnswerTargets(UInt128 target) throws DecodeException {
        for (Packet p : this.answers) {
            assertThat(KadMessages.SearchAnswer.decode(p).target()).isEqualTo(target);
        }
    }

    private void thenTheAnswersCarryResults(int count) throws DecodeException {
        int total = 0;
        for (Packet p : this.answers) {
            total += KadMessages.SearchAnswer.decode(p).results().size();
        }
        assertThat(total).isEqualTo(count);
    }

    private void thenOnlyDecodeExceptionsOccurred() {
        assertThat(this.exception).isNull();
    }

    private interface Decoder {
        void decode() throws DecodeException;
    }

    @FunctionalInterface
    private interface Decoding {
        Object decode(Packet packet) throws DecodeException;
    }
}
