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

import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.io.StringEncoding;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.tag.ClientTag;
import org.njmule.core.protocol.tag.Ed2kTagFormat;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.ServerTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagCodec;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.search.SearchExpr;
import org.njmule.core.search.SearchParser;
import org.njmule.core.util.Ipv4;

class Ed2kMessagesTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final Hash16 HASH = Hash16.parseHex("11".repeat(16));
    private static final FileEntry NAMED_FILE =
            new FileEntry(HASH, 0, 0, List.of(new Tag.StringTag(TagName.id(FileTag.FILENAME), "a")));

    // Default preferences, nick "x", no server, Kad off.
    private static final String EMULE_HELLO = "10"
            + "11".repeat(16)
            + "00000000"
            + "3612"
            + "06000000"
            + "020100010100"
            + "78"
            + "030100113c000000"
            + "030100f940120000"
            + "030100fa16421334"
            + "030100feba2d0000"
            + "030100fb00200100"
            + "00000000"
            + "0000";

    private Packet packet;
    private PacketWriter out;
    private int answerOpcode;
    private Object sent;
    private Object decoded;
    private ServerMessages.IdChange idChange;
    private Optional<ServerMessages.ServerStatus> serverStatus;
    private ServerMessages.ServerIdent serverIdent;
    private ServerMessages.SearchResults results;
    private List<FileEntry> files;
    private List<ServerMessages.Endpoint> servers;
    private List<ServerMessages.Endpoint> decodedServers;
    private ServerMessages.FoundSources foundSources;
    private ServerMessages.FoundSources decodedFoundSources;
    private ServerMessages.CallbackRequested callback;
    private ServerDatagrams.Status status;
    private List<String> lines;
    private int version;
    private SearchExpr search;
    private Optional<Packet> searchRequest;
    private Hello hello;
    private Hello decodedHello;
    private Packet answer;
    private int listenPort;
    private FileEntry entry;
    private int sourceCount;
    private int completeSourceCount;
    private byte[] garbage;
    private Throwable exception;

    @Test
    void shouldCarryTheFourLoginTagsInTheOldEncoding() {
        whenALoginIsEncoded(0, 4662, "x", 0x0719);

        thenThePacketOpcodeIs(0x01);
        thenThePayloadIs("11".repeat(16)
                + "00000000"
                + "3612"
                + "04000000"
                + "0201000101"
                + "0078"
                + "030100113c000000"
                + "0301002019070000"
                + "030100fb"
                + HEX.formatHex(le(ClientVersion.njMule())));
    }

    @Test
    void shouldReadALowIdChangeWithoutTheOptionalFields() {
        givenThePacket(0x40, "39300000");

        whenTheIdChangeIsDecoded();

        thenNoExceptionOccurred();
        thenTheIdIsLow();
        thenTheServerFlagsAreZero();
        thenThePublicIpIsZero();
    }

    @Test
    void shouldReadTheOptionalFieldsOfAHighIdChange() {
        // HighID 1.2.3.4 (network order on the wire), flags, unused port, reported IP.
        givenThePacket(0x40, "01020304" + "19050000" + "00000000" + "05060708");

        whenTheIdChangeIsDecoded();

        thenNoExceptionOccurred();
        thenTheIdIsHigh();
        thenThePublicIpIs("1.2.3.4");
        thenTheServerHasTheFlag(ServerMessages.TCP_COMPRESSION);
        thenTheServerHasTheFlag(ServerMessages.TCP_LARGEFILES);
        thenTheReportedIpIs("5.6.7.8");
    }

    @Test
    void shouldRefuseATruncatedIdChange() {
        givenThePacket(0x40, "0102");

        whenTheIdChangeIsDecoded();

        thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void shouldDecodeAServerStatus() throws DecodeException {
        givenThePacket(0x34, "0a000000" + "14000000");

        whenTheServerStatusIsDecoded();

        thenTheServerStatusIs(10, 20);
    }

    @Test
    void shouldIgnoreAServerStatusWithoutTheFileCount() throws DecodeException {
        givenThePacket(0x34, "0a000000");

        whenTheServerStatusIsDecoded();

        thenThereIsNoServerStatus();
    }

    @Test
    void shouldDecodeTheServerIdentity() throws DecodeException {
        givenAServerIdentity("9.8.7.6", "Test server", "for tests");

        whenTheServerIdentityIsDecoded();

        thenTheServerIpIs("9.8.7.6");
        thenTheServerNameIs("Test server");
        thenTheServerDescriptionIs("for tests");
    }

    @Test
    void shouldReadTheFilesAndTheMoreFlagOfSearchResults() {
        givenASearchAnswerAnnouncing(2);
        givenAResultFile(HASH, "ubuntu.iso", 5_000_000_000L, 42);
        givenAResultFile(Hash16.parseHex("22".repeat(16)), "small.txt", 3, 1);
        givenTheMoreFlag(1);

        whenTheSearchResultsAreDecoded();

        thenNoExceptionOccurred();
        thenThereAreMoreResults();
        thenTheResultCountIs(2);
        thenTheResultHasTheName(0, "ubuntu.iso");
        thenTheResultHasTheSize(0, 5_000_000_000L);
        thenTheResultHasTheSources(0, 42);
        thenTheResultHasTheSize(1, 3);
    }

    @Test
    void shouldRefuseSearchResultsAnnouncingMoreFilesThanTheyCarry() {
        givenASearchAnswerAnnouncing(1000);

        whenTheSearchResultsAreDecoded();

        thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void shouldKeepTheResultsBeforeTheCutOfASearchAnswer() {
        givenASearchAnswerAnnouncing(3);
        givenAResultFile(HASH, "a", 1, 1);
        givenAResultFile(Hash16.parseHex("22".repeat(16)), "b", 2, 2);
        givenAResultFileCutShort(Hash16.parseHex("33".repeat(16)), "c", 3, 3);

        whenTheSearchResultsAreDecoded();

        thenNoExceptionOccurred();
        thenTheResultNamesAre("a", "b");
        thenThereAreNoMoreResults();
    }

    @Test
    void shouldKeepTheResultsBeforeTheCutOfAGlobalSearchAnswer() throws DecodeException {
        givenAGlobalSearchAnswer();
        givenAResultFile(HASH, "a", 1, 1);
        givenAnotherGlobalResultFollows();
        givenAResultFile(Hash16.parseHex("22".repeat(16)), "b", 2, 2);
        givenAnotherGlobalResultFollows();
        givenAResultFileCutShort(Hash16.parseHex("33".repeat(16)), "c", 3, 3);

        whenTheGlobalSearchResultsAreDecoded();

        thenTheResultNamesAre("a", "b");
    }

    @Test
    void shouldEncodeAServerListOf255Servers() {
        givenAListOfServers(255);

        whenTheServerListIsEncoded();

        thenNoExceptionOccurred();
        thenAPacketIsEncoded();
    }

    @Test
    void shouldRefuseToEncodeAServerListOf256Servers() {
        givenAListOfServers(256);

        whenTheServerListIsEncoded();

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldRefuseToEncodeFoundSourcesOf256Sources() {
        givenFoundSourcesOf(Collections.nCopies(256, new ServerMessages.Source(7, 1, 0, Optional.empty())));

        whenTheFoundSourcesAreEncoded();

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldKeepNegativeVersionPartsOutOfTheCompatibleClientBits() {
        whenAVersionIsEncoded(ClientVersion.NJMULE_COMPAT, -1, -1, -1);

        thenTheVersionIs(ClientVersion.NJMULE_COMPAT << 24);
    }

    @Test
    void shouldKeepOversizedVersionPartsOutOfTheCompatibleClientBits() {
        whenAVersionIsEncoded(ClientVersion.NJMULE_COMPAT, 1000, 1000, 1000);

        thenTheCompatibleClientOfTheVersionIs(ClientVersion.NJMULE_COMPAT);
    }

    @Test
    void shouldSplitServerMessagesIntoLines() throws DecodeException {
        givenTheServerSentTheMessage("server version 17.15\r\n\r\nWelcome!\nERROR: x");

        whenTheServerMessageIsDecoded();

        thenTheLinesAre("server version 17.15", "Welcome!", "ERROR: x");
    }

    @Test
    void shouldDecodeAServerList() {
        givenThePacket(0x32, "01" + "01020304" + "3512");

        whenTheServerListIsDecoded();

        thenNoExceptionOccurred();
        thenTheServersAre(endpoint("1.2.3.4", 4661));
    }

    @Test
    void shouldRefuseAServerListAnnouncingMoreServersThanItCarries() {
        givenThePacket(0x32, "05" + "01020304");

        whenTheServerListIsDecoded();

        thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void shouldDecodeACallbackRequestWithoutAUserHash() throws DecodeException {
        givenThePacket(0x35, "0a000001" + "3612");

        whenTheCallbackRequestIsDecoded();

        thenTheCallbackEndpointIs(endpoint("10.0.0.1", 4662));
        thenTheCallbackCarriesNoUserHash();
    }

    @Test
    void shouldDecodeACallbackRequestWithTheUserHash() throws DecodeException {
        givenThePacket(0x35, "0a000001" + "3612" + "81" + "11".repeat(16));

        whenTheCallbackRequestIsDecoded();

        thenTheCallbackCryptOptionsAre(0x81);
        thenTheCallbackUserHashIs(HASH);
    }

    @Test
    void shouldPutTheChallengeInAStatusRequest() {
        whenAStatusRequestIsMadeWithTheChallenge(0x55AA1234L);

        thenThePayloadIs("3412aa55");
    }

    @Test
    void shouldDecodeAMinimalStatusAnswer() {
        givenThePacket(0x97, "3412aa55" + "64000000" + "c8000000");

        whenTheStatusIsDecoded();

        thenNoExceptionOccurred();
        thenTheStatusCountsUsersAndFiles(100, 200);
        thenTheStatusHasNoUdpFlags();
    }

    @Test
    void shouldDecodeAFullStatusAnswer() {
        givenThePacket(
                0x97,
                "3412aa55"
                        + "64000000"
                        + "c8000000"
                        + "e8030000"
                        + "10270000"
                        + "20270000"
                        + "0b030000"
                        + "05000000"
                        + "4112"
                        + "4012"
                        + "efbeadde");

        whenTheStatusIsDecoded();

        thenNoExceptionOccurred();
        thenTheStatusMaxUsersAre(1000);
        thenTheStatusHasTheUdpFlag(ServerDatagrams.UDP_OBFUSCATION);
        thenTheObfuscatedPortsAre(0x1241, 0x1240);
        thenTheUdpKeyIs(0xDEADBEEFL);
    }

    @Test
    void shouldRefuseATruncatedStatusAnswer() {
        givenThePacket(0x97, "3412aa55");

        whenTheStatusIsDecoded();

        thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void shouldAskAServerWithLargeFilesAndExtendedSearchesTheThirdGlobalSearch() {
        givenTheSearch("ubuntu iso");

        whenAGlobalSearchRequestIsMadeForTheFlags(ServerDatagrams.UDP_LARGEFILES | ServerDatagrams.UDP_EXT_GETFILES);

        thenTheSearchRequestOpcodeIs(Opcodes.ServerUdp.GLOBSEARCHREQ3.code());
        thenTheSearchRequestPayloadStartsWith("01000000890e01");
    }

    @Test
    void shouldAskAServerWithExtendedSearchesTheSecondGlobalSearch() {
        givenTheSearch("ubuntu iso");

        whenAGlobalSearchRequestIsMadeForTheFlags(ServerDatagrams.UDP_EXT_GETFILES);

        thenTheSearchRequestOpcodeIs(Opcodes.ServerUdp.GLOBSEARCHREQ2.code());
    }

    @Test
    void shouldAskAServerWithoutFlagsTheFirstGlobalSearch() {
        givenTheSearch("ubuntu iso");

        whenAGlobalSearchRequestIsMadeForTheFlags(0);

        thenTheSearchRequestOpcodeIs(Opcodes.ServerUdp.GLOBSEARCHREQ.code());
    }

    @Test
    void shouldNotAskAServerWithoutLargeFilesForLargeFiles() {
        givenTheSearch("movie @size>5g");

        whenAGlobalSearchRequestIsMadeForTheFlags(ServerDatagrams.UDP_EXT_GETFILES);

        thenNoSearchRequestIsMade();
    }

    @Test
    void shouldReadSeveralResultsFromOneGlobalAnswer() throws DecodeException {
        givenAGlobalSearchAnswer();
        givenAResultFile(HASH, "a", 1, 1);
        givenAnotherGlobalResultFollows();
        givenAResultFile(Hash16.parseHex("33".repeat(16)), "b", 2, 2);
        givenATrailingByte(0x00);

        whenTheGlobalSearchResultsAreDecoded();

        thenTheResultNamesAre("a", "b");
    }

    @Test
    void shouldReadTheHelloOfEmule() throws DecodeException {
        givenThePacket(0x01, EMULE_HELLO);

        whenTheHelloIsDecoded();

        thenTheHelloUserHashIs(HASH);
        thenTheHelloNickIs("x");
        thenTheHelloPortsAre(4662, 4672, 0);
        thenTheHelloMiscOptionsAre(0x34134216, 0x2DBA);
        thenTheHelloKadVersionIs(10);
        thenTheHelloEmuleVersionIs(0x00012000);
        thenTheHelloNamesNoServer();
    }

    @Test
    void shouldSaturateSourceCountsBeyondTheSignedRange() {
        givenAFileEntryWithTheTags(Tag.of(FileTag.SOURCES, -1L), Tag.of(FileTag.COMPLETE_SOURCES, Long.MIN_VALUE));

        whenItsSourceCountsAreRead();

        thenTheSourceCountIs(Integer.MAX_VALUE);
        thenTheCompleteSourceCountIs(Integer.MAX_VALUE);
    }

    @Test
    void shouldSaturateASourceCountBeyondThirtyTwoBits() {
        givenAFileEntryWithTheTags(Tag.of(FileTag.SOURCES, 1L << 32));

        whenItsSourceCountsAreRead();

        thenTheSourceCountIs(Integer.MAX_VALUE);
    }

    @Test
    void shouldCountNoSourcesWithoutTheSourceTags() {
        givenAFileEntryWithTheTags();

        whenItsSourceCountsAreRead();

        thenTheSourceCountIs(0);
        thenTheCompleteSourceCountIs(0);
    }

    @Test
    void shouldKeepSixteenBitsOfAWiderListenPortTag() {
        givenAHelloOnThePortWithTheTags(4662, Tag.of(ClientTag.PORT, 0x1_1234L));

        whenTheListenPortIsRead();

        thenTheListenPortIs(0x1234);
    }

    @Test
    void shouldKeepSixteenBitsOfAListenPortTagWithAllBitsSet() {
        givenAHelloOnThePortWithTheTags(4662, Tag.of(ClientTag.PORT, -1L));

        whenTheListenPortIsRead();

        thenTheListenPortIs(0xFFFF);
    }

    @Test
    void shouldListenOnTheTcpPortWithoutAListenPortTag() {
        givenAHelloOnThePortWithTheTags(4662);

        whenTheListenPortIsRead();

        thenTheListenPortIs(4662);
    }

    @Test
    void shouldGiveTheHelloAnswerNoSizeByte() {
        givenOurHello();

        whenOurHelloIsEncodedAsAHelloAndAsAnAnswer();

        thenTheAnswerIsOneByteShorterThanTheHello();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRoundTripOurHello(boolean answer) throws DecodeException {
        givenOurHello();
        givenOurHelloIsEncoded(answer);

        whenTheHelloIsDecoded();

        thenTheDecodedHelloIsOurs();
        thenTheHelloServerIpIs("9.8.7.6");
    }

    @Test
    void shouldRoundTripALogin() throws DecodeException {
        givenTheEncodedLogin(new ServerMessages.Login(HASH, 0x0100_0001L, 4662, "nick", 0x719));

        whenTheLoginIsDecoded();

        thenTheDecodedMessageIsTheSentOne();
    }

    @Test
    void shouldRoundTripAHighIdChange() {
        givenTheEncodedIdChange(
                new ServerMessages.IdChange(Ipv4.toEd2k(Ipv4.parse("1.2.3.4")), 0x519, Ipv4.parse("1.2.3.4")));

        whenTheIdChangeIsDecoded();

        thenNoExceptionOccurred();
        thenTheDecodedMessageIsTheSentOne();
    }

    @Test
    void shouldRoundTripALowIdChange() {
        givenTheEncodedIdChange(new ServerMessages.IdChange(12_345, 0x19, Ipv4.parse("5.6.7.8")));

        whenTheIdChangeIsDecoded();

        thenNoExceptionOccurred();
        thenTheDecodedMessageIsTheSentOne();
    }

    @Test
    void shouldRoundTripAServerStatus() throws DecodeException {
        givenTheEncodedServerStatus(new ServerMessages.ServerStatus(1, 2));

        whenTheServerStatusIsDecoded();

        thenTheServerStatusIs(1, 2);
    }

    @Test
    void shouldRoundTripAServerIdentity() throws DecodeException {
        givenTheEncodedServerIdentity(new ServerMessages.ServerIdent(
                HASH, Ipv4.parse("9.8.7.6"), 4661, Optional.of("name"), Optional.empty()));

        whenTheServerIdentityIsDecoded();

        thenTheDecodedMessageIsTheSentOne();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldRoundTripSearchResults(boolean more) {
        givenTheEncodedSearchResults(new ServerMessages.SearchResults(List.of(NAMED_FILE, NAMED_FILE), more));

        whenTheSearchResultsAreDecoded();

        thenNoExceptionOccurred();
        thenTheDecodedMessageIsTheSentOne();
    }

    @Test
    void shouldRoundTripAServerMessage() throws DecodeException {
        givenTheEncodedServerMessage("one\ntwo");

        whenTheServerMessageIsDecoded();

        thenTheLinesAre("one", "two");
    }

    @Test
    void shouldRoundTripAServerList() {
        givenAListOfServers(1);
        givenTheServerListIsEncoded();

        whenTheServerListIsDecoded();

        thenNoExceptionOccurred();
        thenTheServersAre(endpoint("1.1.1.1", 4242));
    }

    @Test
    void shouldRoundTripAFullStatus() {
        givenTheEncodedStatus(new ServerDatagrams.Status(7, 1, 2, 3, 4, 5, 0x30B, 6, 4665, 4667, 0xCAFEL));

        whenTheStatusIsDecoded();

        thenNoExceptionOccurred();
        thenTheDecodedMessageIsTheSentOne();
    }

    @Test
    void shouldRoundTripGlobalSearchResults() throws DecodeException {
        givenTheEncodedGlobalSearchResults(NAMED_FILE, NAMED_FILE, NAMED_FILE);

        whenTheGlobalSearchResultsAreDecoded();

        thenTheResultFilesAre(NAMED_FILE, NAMED_FILE, NAMED_FILE);
    }

    @Test
    void shouldAskForTheSourcesOfAFile() {
        whenTheSourcesAreAskedFor(1000, false);

        thenThePayloadIs("11".repeat(16) + "e8030000");
    }

    @Test
    void shouldAskForTheObfuscatedSourcesOfALargeFile() {
        whenTheSourcesAreAskedFor(5_000_000_000L, true);

        thenThePacketOpcodeIs(0x23);
        thenThePayloadIs("11".repeat(16) + "00000000" + "00f2052a01000000");
    }

    @Test
    void shouldDecodeFoundSources() {
        givenThePacket(0x42, "11".repeat(16) + "02" + "01020304" + "3612" + "39300000" + "3712");

        whenTheFoundSourcesAreDecoded();

        thenNoExceptionOccurred();
        thenTheSourcesAre(
                new ServerMessages.Source(0x04030201L, 4662, 0, Optional.empty()),
                new ServerMessages.Source(12_345, 4663, 0, Optional.empty()));
        thenTheSourceIsALowId(1);
    }

    @Test
    void shouldRoundTripObfuscatedFoundSources() {
        givenFoundSourcesOf(List.of(
                new ServerMessages.Source(0x04030201L, 4662, 0x01, Optional.empty()),
                new ServerMessages.Source(7, 1, 0x83, Optional.of(HASH))));
        givenTheFoundSourcesAreEncoded();

        whenTheFoundSourcesAreDecoded();

        thenNoExceptionOccurred();
        thenTheDecodedMessageIsTheSentOne();
    }

    @Test
    void shouldRefuseFoundSourcesAnnouncingMoreSourcesThanTheyCarry() {
        givenThePacket(0x42, "11".repeat(16) + "05" + "0102");

        whenTheFoundSourcesAreDecoded();

        thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void shouldCarryTheNjmuleCompatibleClientIdInAPreReleaseVersion() {
        whenAVersionIsMadeFrom("0.0.1-alpha.1");

        thenTheVersionIs(0x6E00_0080);
    }

    @Test
    void shouldCarryTheNjmuleCompatibleClientIdInAReleaseVersion() {
        whenAVersionIsMadeFrom("1.2.3");

        thenTheVersionIs(0x6E00_0000 | 1 << 17 | 2 << 10 | 3 << 7);
    }

    @Test
    void shouldCarryTheNjmuleCompatibleClientIdAloneForAnUnreadableVersion() {
        whenAVersionIsMadeFrom("garbage");

        thenTheVersionIs(0x6E00_0000);
    }

    @Property
    void shouldOnlyFailWithDecodeExceptionOnGarbage(@ForAll @Size(max = 200) byte[] data) {
        givenTheGarbage(data);

        whenEveryDecoderReadsIt();

        thenOnlyDecodeExceptionsOccurred();
    }

    private void givenThePacket(int opcode, String hex) {
        this.packet = packet(opcode, hex);
    }

    private void givenAServerIdentity(String ip, String name, String description) {
        PacketWriter ident = new PacketWriter()
                .hash16(HASH)
                .u32(Ipv4.toEd2k(Ipv4.parse(ip)))
                .u16(4661)
                .u32(2);
        TagCodec.writeEd2kList(
                ident,
                List.of(
                        new Tag.StringTag(TagName.id(ServerTag.SERVERNAME), name),
                        new Tag.StringTag(TagName.id(ServerTag.DESCRIPTION), description)),
                Ed2kTagFormat.NEW,
                StringEncoding.UTF8);
        this.packet = Packet.of(Protocol.EDONKEY, 0x41, ident);
    }

    private void givenASearchAnswerAnnouncing(int count) {
        this.answerOpcode = 0x33;
        this.out = new PacketWriter().u32(count);
        takeTheAnswerAsReceived();
    }

    private void givenAGlobalSearchAnswer() {
        this.answerOpcode = Opcodes.ServerUdp.GLOBSEARCHRES.code();
        this.out = new PacketWriter();
        takeTheAnswerAsReceived();
    }

    private void givenAResultFile(Hash16 hash, String name, long size, int sources) {
        writeFile(this.out, hash, name, size, sources);
        takeTheAnswerAsReceived();
    }

    private void givenAResultFileCutShort(Hash16 hash, String name, long size, int sources) {
        PacketWriter whole = new PacketWriter();
        writeFile(whole, hash, name, size, sources);
        this.out.bytes(whole.toByteArray(), 0, whole.size() - 3);
        takeTheAnswerAsReceived();
    }

    private void givenAnotherGlobalResultFollows() {
        this.out.u8(Protocol.EDONKEY).u8(Opcodes.ServerUdp.GLOBSEARCHRES.code());
        takeTheAnswerAsReceived();
    }

    private void givenTheMoreFlag(int flag) {
        this.out.u8(flag);
        takeTheAnswerAsReceived();
    }

    private void givenATrailingByte(int value) {
        this.out.u8(value);
        takeTheAnswerAsReceived();
    }

    private void givenAListOfServers(int count) {
        this.servers = Collections.nCopies(count, endpoint("1.1.1.1", 4242));
    }

    private void givenTheServerListIsEncoded() {
        this.packet = ServerMessages.serverList(this.servers);
    }

    private void givenFoundSourcesOf(List<ServerMessages.Source> sources) {
        this.foundSources = new ServerMessages.FoundSources(HASH, sources);
        this.sent = this.foundSources;
    }

    private void givenTheFoundSourcesAreEncoded() {
        this.packet = this.foundSources.encode();
    }

    private void givenTheServerSentTheMessage(String text) {
        this.packet = Packet.of(Protocol.EDONKEY, 0x38, new PacketWriter().string16(text, StringEncoding.UTF8));
    }

    private void givenTheSearch(String expression) {
        this.search = SearchParser.parse(expression);
    }

    private void givenAFileEntryWithTheTags(Tag... tags) {
        this.entry = new FileEntry(HASH, 0, 0, List.of(tags));
    }

    private void givenAHelloOnThePortWithTheTags(int tcpPort, Tag... tags) {
        this.hello = new Hello(HASH, 0, tcpPort, List.of(tags), 0, 0);
    }

    private void givenOurHello() {
        this.hello = Hello.of(
                HASH,
                0x0100_0001L,
                4662,
                "njMule user",
                4672,
                0,
                Hello.MISC1_UNICODE | Hello.MISC1_NO_VIEW_SHARED_FILES,
                Hello.MISC2_SUPPORTS_CRYPT,
                Ipv4.parse("9.8.7.6"),
                4661);
    }

    private void givenOurHelloIsEncoded(boolean answer) {
        this.packet = this.hello.encode(answer);
    }

    private void givenTheEncodedLogin(ServerMessages.Login login) {
        this.sent = login;
        this.packet = login.encode();
    }

    private void givenTheEncodedIdChange(ServerMessages.IdChange change) {
        this.sent = change;
        this.packet = change.encode();
    }

    private void givenTheEncodedServerStatus(ServerMessages.ServerStatus serverStatus) {
        this.packet = serverStatus.encode();
    }

    private void givenTheEncodedServerIdentity(ServerMessages.ServerIdent ident) {
        this.sent = ident;
        this.packet = ident.encode();
    }

    private void givenTheEncodedSearchResults(ServerMessages.SearchResults searchResults) {
        this.sent = searchResults;
        this.packet = searchResults.encode();
    }

    private void givenTheEncodedServerMessage(String text) {
        this.packet = ServerMessages.message(text);
    }

    private void givenTheEncodedStatus(ServerDatagrams.Status serverStatus) {
        this.sent = serverStatus;
        this.packet = serverStatus.encode();
    }

    private void givenTheEncodedGlobalSearchResults(FileEntry... results) {
        this.packet = ServerDatagrams.searchResults(List.of(results));
    }

    private void givenTheGarbage(byte[] data) {
        this.garbage = data;
    }

    private void whenALoginIsEncoded(long clientId, int tcpPort, String nick, int capabilities) {
        this.packet = new ServerMessages.Login(HASH, clientId, tcpPort, nick, capabilities).encode();
    }

    private void whenTheLoginIsDecoded() throws DecodeException {
        this.decoded = ServerMessages.Login.decode(this.packet);
    }

    private void whenTheIdChangeIsDecoded() {
        this.exception = catchThrowable(() -> this.idChange = ServerMessages.IdChange.decode(this.packet));
        this.decoded = this.idChange;
    }

    private void whenTheServerStatusIsDecoded() throws DecodeException {
        this.serverStatus = ServerMessages.ServerStatus.decode(this.packet);
    }

    private void whenTheServerIdentityIsDecoded() throws DecodeException {
        this.serverIdent = ServerMessages.ServerIdent.decode(this.packet);
        this.decoded = this.serverIdent;
    }

    private void whenTheSearchResultsAreDecoded() {
        this.exception = catchThrowable(() -> {
            this.results = ServerMessages.SearchResults.decode(this.packet);
            this.files = this.results.files();
        });
        this.decoded = this.results;
    }

    private void whenTheGlobalSearchResultsAreDecoded() throws DecodeException {
        this.files = ServerDatagrams.decodeSearchResults(this.packet);
    }

    private void whenTheServerListIsEncoded() {
        this.exception = catchThrowable(() -> this.packet = ServerMessages.serverList(this.servers));
    }

    private void whenTheServerListIsDecoded() {
        this.exception = catchThrowable(() -> this.decodedServers = ServerMessages.decodeServerList(this.packet));
    }

    private void whenTheFoundSourcesAreEncoded() {
        this.exception = catchThrowable(() -> this.packet = this.foundSources.encode());
    }

    private void whenTheFoundSourcesAreDecoded() {
        this.exception =
                catchThrowable(() -> this.decodedFoundSources = ServerMessages.FoundSources.decode(this.packet));
        this.decoded = this.decodedFoundSources;
    }

    private void whenAVersionIsEncoded(int compat, int major, int minor, int patch) {
        this.version = ClientVersion.encode(compat, major, minor, patch);
    }

    private void whenAVersionIsMadeFrom(String semVer) {
        this.version = ClientVersion.fromSemVer(semVer);
    }

    private void whenTheServerMessageIsDecoded() throws DecodeException {
        this.lines = ServerMessages.decodeMessage(this.packet);
    }

    private void whenTheCallbackRequestIsDecoded() throws DecodeException {
        this.callback = ServerMessages.CallbackRequested.decode(this.packet);
    }

    private void whenAStatusRequestIsMadeWithTheChallenge(long challenge) {
        this.packet = ServerDatagrams.statusRequest(challenge);
    }

    private void whenTheStatusIsDecoded() {
        this.exception = catchThrowable(() -> this.status = ServerDatagrams.Status.decode(this.packet));
        this.decoded = this.status;
    }

    private void whenAGlobalSearchRequestIsMadeForTheFlags(int udpFlags) {
        this.searchRequest = ServerDatagrams.searchRequest(this.search, udpFlags);
    }

    private void whenTheHelloIsDecoded() throws DecodeException {
        this.decodedHello = Hello.decode(this.packet);
    }

    private void whenItsSourceCountsAreRead() {
        this.sourceCount = this.entry.sources();
        this.completeSourceCount = this.entry.completeSources();
    }

    private void whenTheListenPortIsRead() {
        this.listenPort = this.hello.listenPort();
    }

    private void whenOurHelloIsEncodedAsAHelloAndAsAnAnswer() {
        this.packet = this.hello.encode(false);
        this.answer = this.hello.encode(true);
    }

    private void whenTheSourcesAreAskedFor(long size, boolean obfuscated) {
        this.packet = ServerMessages.getSources(HASH, size, obfuscated);
    }

    private void whenEveryDecoderReadsIt() {
        Packet received = Packet.of(Protocol.EDONKEY, Opcodes.Client.HELLO.code(), this.garbage);
        this.exception = catchThrowable(() -> {
            decodeQuietly(() -> Hello.decode(received));
            decodeQuietly(() -> ServerMessages.IdChange.decode(received));
            decodeQuietly(() -> ServerMessages.SearchResults.decode(received));
            decodeQuietly(() -> ServerMessages.ServerIdent.decode(received));
            decodeQuietly(() -> ServerMessages.decodeMessage(received));
            decodeQuietly(() -> ServerMessages.decodeServerList(received));
            decodeQuietly(() -> ServerMessages.CallbackRequested.decode(received));
            decodeQuietly(() -> ServerDatagrams.Status.decode(received));
            decodeQuietly(() -> ServerDatagrams.decodeSearchResults(received));
        });
    }

    private void thenNoExceptionOccurred() {
        assertThat(this.exception).isNull();
    }

    private void thenExceptionOccurred(Class<? extends Exception> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private void thenThePacketOpcodeIs(int opcode) {
        assertThat(this.packet.opcode()).isEqualTo(opcode);
    }

    private void thenThePayloadIs(String hex) {
        assertThat(HEX.formatHex(this.packet.payload())).isEqualTo(hex);
    }

    private void thenAPacketIsEncoded() {
        assertThat(this.packet).isNotNull();
    }

    private void thenTheDecodedMessageIsTheSentOne() {
        assertThat(this.decoded).isEqualTo(this.sent);
    }

    private void thenTheIdIsLow() {
        assertThat(this.idChange.isLowId()).isTrue();
    }

    private void thenTheIdIsHigh() {
        assertThat(this.idChange.isLowId()).isFalse();
    }

    private void thenTheServerFlagsAreZero() {
        assertThat(this.idChange.serverFlags()).isZero();
    }

    private void thenThePublicIpIsZero() {
        assertThat(this.idChange.publicIp()).isZero();
    }

    private void thenThePublicIpIs(String ip) {
        assertThat(this.idChange.publicIp()).isEqualTo(Ipv4.parse(ip));
    }

    private void thenTheServerHasTheFlag(int flag) {
        assertThat(this.idChange.has(flag)).isTrue();
    }

    private void thenTheReportedIpIs(String ip) {
        assertThat(this.idChange.reportedIp()).isEqualTo(Ipv4.parse(ip));
    }

    private void thenTheServerStatusIs(long users, long files) {
        assertThat(this.serverStatus).contains(new ServerMessages.ServerStatus(users, files));
    }

    private void thenThereIsNoServerStatus() {
        assertThat(this.serverStatus).isEmpty();
    }

    private void thenTheServerIpIs(String ip) {
        assertThat(this.serverIdent.ip()).isEqualTo(Ipv4.parse(ip));
    }

    private void thenTheServerNameIs(String name) {
        assertThat(this.serverIdent.name()).contains(name);
    }

    private void thenTheServerDescriptionIs(String description) {
        assertThat(this.serverIdent.description()).contains(description);
    }

    private void thenThereAreMoreResults() {
        assertThat(this.results.more()).isTrue();
    }

    private void thenThereAreNoMoreResults() {
        assertThat(this.results.more()).isFalse();
    }

    private void thenTheResultCountIs(int count) {
        assertThat(this.files).hasSize(count);
    }

    private void thenTheResultHasTheName(int index, String name) {
        assertThat(this.files.get(index).name()).contains(name);
    }

    private void thenTheResultHasTheSize(int index, long size) {
        assertThat(this.files.get(index).size()).hasValue(size);
    }

    private void thenTheResultHasTheSources(int index, int sources) {
        assertThat(this.files.get(index).sources()).isEqualTo(sources);
    }

    private void thenTheResultNamesAre(String... names) {
        assertThat(this.files).extracting(f -> f.name().orElseThrow()).containsExactly(names);
    }

    private void thenTheResultFilesAre(FileEntry... expected) {
        assertThat(this.files).containsExactly(expected);
    }

    private void thenTheVersionIs(int expected) {
        assertThat(this.version).isEqualTo(expected);
    }

    private void thenTheCompatibleClientOfTheVersionIs(int compat) {
        assertThat(this.version >>> 24).isEqualTo(compat);
    }

    private void thenTheLinesAre(String... expected) {
        assertThat(this.lines).containsExactly(expected);
    }

    private void thenTheServersAre(ServerMessages.Endpoint... expected) {
        assertThat(this.decodedServers).containsExactly(expected);
    }

    private void thenTheCallbackEndpointIs(ServerMessages.Endpoint expected) {
        assertThat(this.callback.endpoint()).isEqualTo(expected);
    }

    private void thenTheCallbackCarriesNoUserHash() {
        assertThat(this.callback.userHash()).isEmpty();
    }

    private void thenTheCallbackCryptOptionsAre(int cryptOptions) {
        assertThat(this.callback.cryptOptions()).isEqualTo(cryptOptions);
    }

    private void thenTheCallbackUserHashIs(Hash16 userHash) {
        assertThat(this.callback.userHash()).contains(userHash);
    }

    private void thenTheStatusCountsUsersAndFiles(long users, long files) {
        assertThat(this.status.users()).isEqualTo(users);
        assertThat(this.status.files()).isEqualTo(files);
    }

    private void thenTheStatusHasNoUdpFlags() {
        assertThat(this.status.udpFlags()).isZero();
    }

    private void thenTheStatusMaxUsersAre(long maxUsers) {
        assertThat(this.status.maxUsers()).isEqualTo(maxUsers);
    }

    private void thenTheStatusHasTheUdpFlag(int flag) {
        assertThat(this.status.has(flag)).isTrue();
    }

    private void thenTheObfuscatedPortsAre(int udpPort, int tcpPort) {
        assertThat(this.status.obfuscatedUdpPort()).isEqualTo(udpPort);
        assertThat(this.status.obfuscatedTcpPort()).isEqualTo(tcpPort);
    }

    private void thenTheUdpKeyIs(long udpKey) {
        assertThat(this.status.udpKey()).isEqualTo(udpKey);
    }

    private void thenTheSearchRequestOpcodeIs(int opcode) {
        assertThat(this.searchRequest.orElseThrow().opcode()).isEqualTo(opcode);
    }

    private void thenTheSearchRequestPayloadStartsWith(String hex) {
        assertThat(HEX.formatHex(this.searchRequest.orElseThrow().payload())).startsWith(hex);
    }

    private void thenNoSearchRequestIsMade() {
        assertThat(this.searchRequest).isEmpty();
    }

    private void thenTheHelloUserHashIs(Hash16 userHash) {
        assertThat(this.decodedHello.userHash()).isEqualTo(userHash);
    }

    private void thenTheHelloNickIs(String nick) {
        assertThat(this.decodedHello.nick()).contains(nick);
    }

    private void thenTheHelloPortsAre(int listenPort, int udpPort, int kadPort) {
        assertThat(this.decodedHello.listenPort()).isEqualTo(listenPort);
        assertThat(this.decodedHello.udpPort()).isEqualTo(udpPort);
        assertThat(this.decodedHello.kadPort()).isEqualTo(kadPort);
    }

    private void thenTheHelloKadVersionIs(int version) {
        assertThat(this.decodedHello.kadVersion()).isEqualTo(version);
    }

    private void thenTheHelloMiscOptionsAre(int miscOptions1, int miscOptions2) {
        assertThat(this.decodedHello.miscOptions1()).isEqualTo(miscOptions1);
        assertThat(this.decodedHello.miscOptions2()).isEqualTo(miscOptions2);
    }

    private void thenTheHelloEmuleVersionIs(long emuleVersion) {
        assertThat(this.decodedHello.emuleVersion()).hasValue(emuleVersion);
    }

    private void thenTheHelloNamesNoServer() {
        assertThat(this.decodedHello.serverIp()).isZero();
    }

    private void thenTheHelloServerIpIs(String ip) {
        assertThat(this.decodedHello.serverIp()).isEqualTo(Ipv4.parse(ip));
    }

    private void thenTheDecodedHelloIsOurs() {
        assertThat(this.decodedHello).isEqualTo(this.hello);
    }

    private void thenTheAnswerIsOneByteShorterThanTheHello() {
        assertThat(this.packet.length()).isEqualTo(this.answer.length() + 1);
    }

    private void thenTheSourceCountIs(int count) {
        assertThat(this.sourceCount).isEqualTo(count);
    }

    private void thenTheCompleteSourceCountIs(int count) {
        assertThat(this.completeSourceCount).isEqualTo(count);
    }

    private void thenTheListenPortIs(int port) {
        assertThat(this.listenPort).isEqualTo(port);
    }

    private void thenTheSourcesAre(ServerMessages.Source... expected) {
        assertThat(this.decodedFoundSources.sources()).containsExactly(expected);
    }

    private void thenTheSourceIsALowId(int index) {
        assertThat(this.decodedFoundSources.sources().get(index).isLowId()).isTrue();
    }

    private void thenOnlyDecodeExceptionsOccurred() {
        assertThat(this.exception).isNull();
    }

    private void takeTheAnswerAsReceived() {
        this.packet = Packet.of(Protocol.EDONKEY, this.answerOpcode, this.out);
    }

    private interface Decoder {
        void decode() throws DecodeException;
    }

    private static void decodeQuietly(Decoder decoder) {
        try {
            decoder.decode();
        } catch (DecodeException expected) {
            // malformed
        }
    }

    private static ServerMessages.Endpoint endpoint(String ip, int port) {
        return new ServerMessages.Endpoint(Ipv4.parse(ip), port);
    }

    private static void writeFile(PacketWriter out, Hash16 hash, String name, long size, int sources) {
        out.hash16(hash).u32(0).u16(0);
        List<Tag> tags = size > 0xFFFF_FFFFL
                ? List.of(
                        new Tag.StringTag(TagName.id(FileTag.FILENAME), name),
                        new Tag.IntTag(TagName.id(FileTag.FILESIZE), size & 0xFFFF_FFFFL),
                        new Tag.IntTag(TagName.id(0x3A), size >>> 32),
                        new Tag.IntTag(TagName.id(FileTag.SOURCES), sources))
                : List.of(
                        new Tag.StringTag(TagName.id(FileTag.FILENAME), name),
                        new Tag.IntTag(TagName.id(FileTag.FILESIZE), size),
                        new Tag.IntTag(TagName.id(FileTag.SOURCES), sources));
        out.u32(tags.size());
        TagCodec.writeEd2kList(out, tags, Ed2kTagFormat.NEW, StringEncoding.UTF8);
    }

    private static Packet packet(int opcode, String hex) {
        return Packet.of(Protocol.EDONKEY, opcode, HEX.parseHex(hex));
    }

    private static byte[] le(int value) {
        return new byte[] {(byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24)};
    }
}
