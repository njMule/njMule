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
package org.njmule.core.protocol.ed2k;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.google.common.net.InetAddresses;
import java.net.Inet6Address;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.util.Ipv4;

class SourceExchangeTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final Hash16 FILE = Hash16.parseHex("ab".repeat(16));
    private static final Hash16 USER = Hash16.parseHex("cd".repeat(16));
    private static final int IP = Ipv4.of(1, 2, 3, 4);
    private static final Inet6Address IPV6 = (Inet6Address) InetAddresses.forString("2001:db8::1");

    private final List<SourceExchange.Source> sources = new ArrayList<>();
    private int version;
    private Packet packet;
    private String payload;
    private SourceExchange.Answer answer;
    private SourceExchange.Request request;
    private Throwable exception;

    @Test
    void shouldWriteIdsInEd2kOrderUpToVersionTwo() {
        givenAHighIdSource(3);

        whenAnAnswerIsEncodedInVersion(2);

        thenNoExceptionOccurred();
        thenThePayloadStartsWith("02" + "ab".repeat(16) + "0100" + "01020304" + "3612");
    }

    @Test
    void shouldWriteIdsInHostOrderFromVersionThree() {
        givenAHighIdSource(3);

        whenAnAnswerIsEncodedInVersion(4);

        thenNoExceptionOccurred();
        thenThePayloadStartsWith("04" + "ab".repeat(16) + "0100" + "04030201" + "3612");
        thenThePayloadEndsWith("cd".repeat(16) + "03");
    }

    @ParameterizedTest
    @MethodSource("everyVersion")
    void shouldRoundTripAnAnswerInEveryVersion(int answerVersion, boolean carriesUserHash, int cryptOptions) {
        givenAHighIdSource(1);
        givenLowIdSources(1);
        givenTheAnswerIsEncodedInVersion(answerVersion);

        whenTheAnswerIsDecoded();

        thenNoExceptionOccurred();
        thenTheAnswerVersionIs(answerVersion);
        thenTheSourceIsTheAddress(0, IP, 4662);
        thenTheServerOfTheSourceIs(0, Ipv4.of(9, 9, 9, 9));
        thenTheSourceIsTheLowId(1, 1234);
        thenTheSourceCarriesTheUserHash(0, carriesUserHash);
        thenTheCryptOptionsOfTheSourceAre(0, cryptOptions);
    }

    @Test
    void shouldReadALowIdWithItsBytesSwappedAsALowIdNotAnAddressEndingInZero() {
        givenTheAnswerPayload(
                "03" + "ab".repeat(16) + "0100" + "000004d2" + "3612" + "00000000" + "0000" + "cd".repeat(16));

        whenTheAnswerIsDecoded();

        thenNoExceptionOccurred();
        thenTheSourceIsTheLowId(0, 1234);
    }

    @Test
    void shouldPutTheVersionAndOptionsBeforeTheFileInARequest() {
        whenASourceRequestIsMade();

        thenTheRequestPayloadIs("04" + "0000" + "ab".repeat(16));
    }

    @Test
    void shouldDecodeASourceRequest() throws DecodeException {
        givenASourceRequest();

        whenTheRequestIsDecoded();

        thenTheRequestIs(new SourceExchange.Request(4, FILE));
    }

    @Test
    void shouldCarryTheMostSourcesItsReaderTakes() {
        givenLowIdSources(SourceExchange.MAX_SOURCES);
        givenTheAnswerIsEncodedInVersion(4);

        whenTheAnswerIsDecoded();

        thenNoExceptionOccurred();
        thenTheAnswerCarriesSources(SourceExchange.MAX_SOURCES);
    }

    @Test
    void shouldRefuseToCarryMoreSourcesThanItsReaderTakes() {
        givenLowIdSources(SourceExchange.MAX_SOURCES + 1);

        whenAnAnswerIsEncodedInVersion(4);

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldRefuseAnAnswerWhoseSizeDoesNotMatch() {
        givenTheAnswerPayload("04" + "ab".repeat(16) + "0200" + "00".repeat(29));

        whenTheAnswerIsDecoded();

        thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void shouldRefuseAnAnswerOfAnUnknownVersion() {
        givenTheAnswerPayload("07" + "ab".repeat(16) + "0000");

        whenTheAnswerIsDecoded();

        thenExceptionOccurred(DecodeException.class);
    }

    @Property
    void shouldReadEverySourceAsAnAddressOrALowId(
            @ForAll int id, @ForAll @IntRange(min = 1, max = 4) int answerVersion) {
        givenAnAnswerOfOneSourceWithTheId(id, answerVersion);

        whenTheAnswerIsDecoded();

        thenNoExceptionOccurred();
        thenTheFirstSourceIsALowIdOrAnAddress();
    }

    @Test
    void shouldReadAnExtendedAnswerAndSkipTheTagsItDoesNotKnow() {
        givenTheAnswerPayload("01" + "ab".repeat(16) + "0100" + "01020304" + "3612" + "04"
                // An address tag of a LowID, which it does not use, then the server and the IPv6 address.
                + "83b0" + "05060708"
                + "83ba" + "09090909"
                + "88bb" + "3512"
                + "81ae" + "20010db8000000000000000000000001");

        whenTheExtendedAnswerIsDecoded();

        thenNoExceptionOccurred();
        thenTheSourceIsTheAddress(0, IP, 4662);
        thenTheServerOfTheSourceIs(0, Ipv4.of(9, 9, 9, 9));
        thenTheIpv6AddressOfTheSourceIs(0, IPV6);
        thenTheSourceCarriesTheUserHash(0, false);
    }

    @Test
    void shouldRoundTripAnExtendedAnswer() {
        givenAHighIdSourceWithItsIpv6Address();
        givenASourceAtItsIpv6AddressAlone();
        givenLowIdSources(1);
        givenTheAnswerIsEncodedExtended();

        whenTheExtendedAnswerIsDecoded();

        thenNoExceptionOccurred();
        thenTheAnswerVersionIs(SourceExchange.EXTENDED_VERSION);
        thenTheSourceIsTheAddress(0, IP, 4662);
        thenTheServerOfTheSourceIs(0, Ipv4.of(9, 9, 9, 9));
        thenTheIpv6AddressOfTheSourceIs(0, IPV6);
        thenTheSourceIsAtItsIpv6AddressAlone(1, 4664);
        thenTheSourceIsTheLowId(2, 1234);
    }

    @Test
    void shouldLeaveSourcesAtAnIpv6AddressAloneOutOfAnAnswerOfTheOtherForm() {
        givenAHighIdSource(1);
        givenASourceAtItsIpv6AddressAlone();
        givenTheAnswerIsEncodedInVersion(4);

        whenTheAnswerIsDecoded();

        thenNoExceptionOccurred();
        thenTheAnswerCarriesSources(1);
    }

    @Test
    void shouldAskForTheExtendedFormWithVersionOne() {
        whenAnExtendedSourceRequestIsMade();

        thenTheRequestPayloadIs("01" + "0000" + "ab".repeat(16));
    }

    @Property
    void shouldOnlyFailWithDecodeExceptionOnGarbageReadAsAnExtendedAnswer(@ForAll @Size(max = 200) byte[] data) {
        givenAnAnswerCarrying(data);

        whenTheExtendedAnswerIsDecoded();

        thenItIsReadOrRefusedAsMalformed();
    }

    @Property
    void shouldOnlyFailWithDecodeExceptionOnGarbageReadAsAnAnswer(@ForAll @Size(max = 200) byte[] data) {
        givenAnAnswerCarrying(data);

        whenTheAnswerIsDecoded();

        thenItIsReadOrRefusedAsMalformed();
    }

    @Property
    void shouldOnlyFailWithDecodeExceptionOnGarbageReadAsARequest(@ForAll @Size(max = 200) byte[] data) {
        givenAnAnswerCarrying(data);

        whenItIsReadAsARequest();

        thenItIsReadOrRefusedAsMalformed();
    }

    private static Stream<Arguments> everyVersion() {
        return IntStream.rangeClosed(1, SourceExchange.VERSION)
                .mapToObj(version -> Arguments.of(version, version >= 2, version >= 4 ? 1 : 0));
    }

    private void givenAHighIdSource(int cryptOptions) {
        this.sources.add(
                new SourceExchange.Source(IP, 0, 4662, Ipv4.of(9, 9, 9, 9), 4661, Optional.of(USER), cryptOptions));
    }

    private void givenAHighIdSourceWithItsIpv6Address() {
        this.sources.add(new SourceExchange.Source(
                IP, 0, 4662, Ipv4.of(9, 9, 9, 9), 4661, Optional.empty(), 0, Optional.of(IPV6)));
    }

    private void givenASourceAtItsIpv6AddressAlone() {
        this.sources.add(SourceExchange.Source.ipv6Only(IPV6, 4664, Optional.empty()));
    }

    private void givenTheAnswerIsEncodedExtended() {
        this.packet = new SourceExchange.Answer(SourceExchange.EXTENDED_VERSION, FILE, this.sources).encodeExtended();
    }

    private void givenLowIdSources(int count) {
        this.sources.addAll(
                Collections.nCopies(count, new SourceExchange.Source(0, 1234, 4663, 0, 0, Optional.of(USER), 0)));
    }

    private void givenTheAnswerIsEncodedInVersion(int answerVersion) {
        this.packet = new SourceExchange.Answer(answerVersion, FILE, this.sources).encode();
    }

    private void givenTheAnswerPayload(String hex) {
        givenAnAnswerCarrying(HEX.parseHex(hex));
    }

    private void givenAnAnswerCarrying(byte[] data) {
        this.packet = Packet.of(Protocol.EMULE, 0x84, data);
    }

    private void givenASourceRequest() {
        this.packet = SourceExchange.request(FILE);
    }

    private void givenAnAnswerOfOneSourceWithTheId(int id, int answerVersion) {
        this.version = answerVersion;
        byte[] entry = new byte[] {(byte) id, (byte) (id >>> 8), (byte) (id >>> 16), (byte) (id >>> 24)};
        PacketWriter out = new PacketWriter()
                .u8(answerVersion)
                .hash16(FILE)
                .u16(1)
                .bytes(entry)
                .u16(4662)
                .u32(0)
                .u16(0);
        if (answerVersion >= 2) {
            out.hash16(USER);
        }
        if (answerVersion >= 4) {
            out.u8(0);
        }
        this.packet = Packet.of(Protocol.EMULE, 0x84, out);
    }

    private void whenAnAnswerIsEncodedInVersion(int answerVersion) {
        this.exception = catchThrowable(
                () -> this.payload = HEX.formatHex(new SourceExchange.Answer(answerVersion, FILE, this.sources)
                        .encode()
                        .payload()));
    }

    private void whenTheAnswerIsDecoded() {
        Packet received = this.packet;
        this.exception = catchThrowable(() -> this.answer = SourceExchange.Answer.decode(received));
    }

    private void whenTheExtendedAnswerIsDecoded() {
        Packet received = this.packet;
        this.exception = catchThrowable(() -> this.answer = SourceExchange.Answer.decodeExtended(received));
    }

    private void whenAnExtendedSourceRequestIsMade() {
        this.packet = SourceExchange.request(FILE, SourceExchange.EXTENDED_VERSION);
    }

    private void whenASourceRequestIsMade() {
        this.packet = SourceExchange.request(FILE);
    }

    private void whenTheRequestIsDecoded() throws DecodeException {
        this.request = SourceExchange.Request.decode(this.packet);
    }

    private void whenItIsReadAsARequest() {
        Packet received = this.packet;
        this.exception = catchThrowable(() -> SourceExchange.Request.decode(received));
    }

    private void thenNoExceptionOccurred() {
        assertThat(this.exception).isNull();
    }

    private void thenExceptionOccurred(Class<? extends Exception> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private void thenThePayloadStartsWith(String hex) {
        assertThat(this.payload).startsWith(hex);
    }

    private void thenThePayloadEndsWith(String hex) {
        assertThat(this.payload).endsWith(hex);
    }

    private void thenTheAnswerVersionIs(int answerVersion) {
        assertThat(this.answer.version()).isEqualTo(answerVersion);
    }

    private void thenTheSourceIsTheAddress(int index, int ip, int port) {
        assertThat(this.answer.sources().get(index).ip()).isEqualTo(ip);
        assertThat(this.answer.sources().get(index).port()).isEqualTo(port);
    }

    private void thenTheServerOfTheSourceIs(int index, int serverIp) {
        assertThat(this.answer.sources().get(index).serverIp()).isEqualTo(serverIp);
    }

    private void thenTheSourceIsTheLowId(int index, long lowId) {
        assertThat(this.answer.sources().get(index).isLowId()).isTrue();
        assertThat(this.answer.sources().get(index).lowId()).isEqualTo(lowId);
    }

    private void thenTheIpv6AddressOfTheSourceIs(int index, Inet6Address address) {
        assertThat(this.answer.sources().get(index).ipv6()).contains(address);
    }

    private void thenTheSourceIsAtItsIpv6AddressAlone(int index, int port) {
        SourceExchange.Source source = this.answer.sources().get(index);
        assertThat(source.isIpv6Only()).isTrue();
        assertThat(source.isLowId()).isFalse();
        assertThat(source.ipv6()).contains(IPV6);
        assertThat(source.port()).isEqualTo(port);
    }

    private void thenTheSourceCarriesTheUserHash(int index, boolean carried) {
        assertThat(this.answer.sources().get(index).userHash())
                .isEqualTo(carried ? Optional.of(USER) : Optional.empty());
    }

    private void thenTheCryptOptionsOfTheSourceAre(int index, int cryptOptions) {
        assertThat(this.answer.sources().get(index).cryptOptions()).isEqualTo(cryptOptions);
    }

    private void thenTheRequestPayloadIs(String hex) {
        assertThat(HEX.formatHex(this.packet.payload())).isEqualTo(hex);
    }

    private void thenTheRequestIs(SourceExchange.Request expected) {
        assertThat(this.request).isEqualTo(expected);
    }

    private void thenTheAnswerCarriesSources(int count) {
        assertThat(this.answer.sources()).hasSize(count);
    }

    private void thenTheFirstSourceIsALowIdOrAnAddress() {
        SourceExchange.Source source = this.answer.sources().getFirst();
        if (source.isLowId()) {
            assertThat(source.lowId()).isLessThan(ServerMessages.LOW_ID_LIMIT);
        } else {
            assertThat(source.ip()).isNotZero();
            if (this.version >= 3) {
                assertThat(source.ip() & 0xFF).as("no address ends in .0").isNotZero();
            }
        }
    }

    private void thenItIsReadOrRefusedAsMalformed() {
        if (this.exception != null) {
            assertThat(this.exception).isInstanceOf(DecodeException.class);
        }
    }
}
