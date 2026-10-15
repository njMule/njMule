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
package org.njmule.core.persist.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.persist.format.ClientsMet.Credit;
import org.njmule.core.persist.format.FriendsMet.Friend;
import org.njmule.core.persist.format.KnownMet.KnownFile;
import org.njmule.core.persist.format.PartMet.Gap;
import org.njmule.core.persist.format.ServerMet.Server;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.ServerTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.Tag.HashTag;
import org.njmule.core.protocol.tag.Tag.IntTag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Bytes;
import org.njmule.core.util.Ipv4;

class MetFilesTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final Hash16 H = Hash16.parseHex("000102030405060708090A0B0C0D0E0F");
    // One server 1.2.3.4:4661 with name "abc" (old tag encoding).
    private static final String SERVER_MET =
            "e0" + "01000000" + "01020304" + "3512" + "01000000" + "020100010300616263";
    private static final Credit CREDIT =
            new Credit(H, 5_000_000_000L, 42, 1_700_000_000L, Bytes.of(new byte[] {1, 2, 3}));
    private static final Friend FRIEND = new Friend(
            H,
            Ipv4.of(1, 2, 3, 4),
            4662,
            100,
            200,
            List.of(Tag.of(FriendsMet.NAME, "pal"), new HashTag(TagName.id(FriendsMet.KAD_ID), H)));
    private static final Hash20 ZERO_AICH = Hash20.of(new byte[20]);
    private static final Known2Met.Entry AICH_ENTRY = new Known2Met.Entry(ZERO_AICH, List.of(ZERO_AICH, ZERO_AICH));
    private static final String ZERO_AICH_HEX = "00".repeat(20);

    private byte[] file;
    private Throwable exception;
    private Map<String, Throwable> failures;
    private List<Server> servers;
    private List<Server> serversRead;
    private final List<KnownFile> knownFiles = new ArrayList<>();
    private List<KnownFile> knownFilesRead;
    private PartMet.Contents partMet;
    private PartMet.Contents partMetRead;
    private List<Gap> gapsTaken;
    private List<Credit> credits;
    private List<Credit> creditsRead;
    private List<Friend> friends;
    private List<Friend> friendsRead;
    private int seed;
    private Hash16 key;
    private CancelledMet.Contents cancelledRead;
    private List<Known2Met.Entry> aichEntries;
    private List<Known2Met.Entry> aichEntriesRead;
    private Hash16 userHash;
    private PreferencesDat.Contents preferencesRead;

    @Test
    void shouldReadTheIpOfAServerInNetworkOrder() throws DecodeException {
        givenTheFile(SERVER_MET);

        whenTheServersAreRead();

        thenTheFirstServerIs("1.2.3.4", 4661, "abc");
    }

    @Test
    void shouldWriteBackTheServerFileItReads() throws DecodeException {
        givenTheFile(SERVER_MET);
        givenTheServersRead();

        whenTheServersAreWritten();

        thenTheFileIs(SERVER_MET);
    }

    @Test
    void shouldKeepTheUnknownTagsAndNamedCountersOfAServer() throws DecodeException {
        givenTheServers(new Server(
                Ipv4.of(10, 0, 0, 1),
                4242,
                List.of(
                        Tag.of(ServerTag.SERVERNAME, "Città"),
                        new IntTag(TagName.of(ServerTag.USERS), 1234),
                        Tag.of(0x77, 9))));
        givenTheServersWritten();

        whenTheServersAreRead();

        thenTheServersReadAreTheOnesWritten();
        thenTheUsersOfTheFirstServerAre(1234);
    }

    @Test
    void shouldReadBackTheKnownFilesItWritesAndCheckTheirConsistency() throws DecodeException {
        givenAConsistentKnownFile();
        givenAnInconsistentKnownFile();
        givenTheKnownFilesWritten();

        whenTheKnownFilesAreRead();

        thenTheKnownFilesReadAreTheOnesWritten();
        thenTheKnownFileIsConsistent(0);
        thenTheKnownFileIsInconsistent(1);
        thenTheSizeOfTheKnownFileIs(0, Ed2k.PARTSIZE);
    }

    @Test
    void shouldSwitchKnownMetToSixtyFourBitTagsForLargeFiles() {
        givenAKnownFileOfTheSize(5_000_000_000L);

        whenTheKnownFilesAreWritten();

        thenTheFirstByteIs(KnownMet.HEADER_64BIT);
    }

    @Test
    void shouldReadBackTheSizeOfALargeKnownFile() throws DecodeException {
        givenAKnownFileOfTheSize(5_000_000_000L);
        givenTheKnownFilesWritten();

        whenTheKnownFilesAreRead();

        thenTheSizeOfTheKnownFileIs(0, 5_000_000_000L);
    }

    @Test
    void shouldWriteThePartMetGapsWithExclusiveEnds() {
        givenAPartMetWithTheGaps(new Gap(0, 100), new Gap(900, 1000));
        givenThePartMetWritten();

        whenThePartMetIsRead();

        thenThePartMetGapsReadAre(new Gap(0, 100), new Gap(900, 1000));
        thenThePartMetTagsReadInclude(new IntTag(TagName.of("\n1"), 1000), new IntTag(TagName.of("\t1"), 900));
    }

    @Test
    void shouldClampAGapPastTheEndAndDropAnIncompletePair() {
        givenAPartMetWithTheTags(
                Tag.of(FileTag.FILESIZE, 1000),
                new IntTag(TagName.of("\t0"), 500),
                new IntTag(TagName.of("\n0"), 5000),
                new IntTag(TagName.of("\t7"), 10));

        whenItsGapsAreTaken();

        thenTheGapsAre(new Gap(500, 1000));
    }

    @Test
    void shouldChooseTheLargeVersionOfPartMetAboveFourGigabytes() {
        givenAPartMetWithTheTags(Tag.of(FileTag.FILESIZE, 5_000_000_000L));

        whenThePartMetIsWritten();

        thenTheFirstByteIs(PartMet.VERSION_LARGE);
    }

    @Test
    void shouldRefuseAnUnknownVersionOfPartMet() {
        givenTheFile("e1");

        whenThePartMetIsRead();

        thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void shouldWriteClientsMetRecordsOf119Bytes() {
        givenTheCredits(CREDIT);

        whenTheCreditsAreWritten();

        thenTheFileHasTheSize(1 + 4 + 119);
    }

    @Test
    void shouldReadBackTheCreditsItWrites() {
        givenTheCredits(CREDIT);
        givenTheCreditsWritten();

        whenTheCreditsAreRead();

        thenTheCreditsReadAreTheOnesWritten();
    }

    @Test
    void shouldRefuseAPublicKeyOfMoreThanEightyBytesInTheCredits() {
        givenTheCredits(CREDIT);
        givenTheCreditsWritten();
        givenTheByteSetTo(1 + 4 + 16 + 4 * 5 + 2, 81);

        whenTheCreditsAreRead();

        thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void shouldReadOldCreditsWithoutKeys() {
        givenTheFile("11"
                + "01000000"
                + H.toHex()
                + "0a000000"
                + "14000000"
                + "00000000"
                + "01000000"
                + "00000000"
                + "0000");

        whenTheCreditsAreRead();

        thenTheFirstCreditHasTransferred((1L << 32) + 10, 20);
        thenTheFirstCreditHasNoKey();
    }

    @Test
    void shouldWriteTheKadIdOfAFriendInTheNewEncoding() {
        givenTheFriends(FRIEND);

        whenTheFriendsAreWritten();

        thenTheFileInHexContains("8102" + H.toHex().toLowerCase(Locale.ROOT));
    }

    @Test
    void shouldReadBackTheFriendsItWrites() throws DecodeException {
        givenTheFriends(FRIEND);
        givenTheFriendsWritten();

        whenTheFriendsAreRead();

        thenTheFriendsReadAreTheOnesWritten();
        thenTheFirstFriendIsNamed("pal");
        thenTheFirstFriendHasTheKadId(H);
    }

    @Test
    void shouldReadBackTheCancelledFilesItWrites() throws DecodeException {
        givenTheKeyOfTheFileHashWithTheSeed(0x01020304);
        givenTheCancelledFilesWritten();

        whenTheCancelledFilesAreRead();

        thenTheSeedReadIs(0x01020304);
        thenTheCancelledFilesAreNotLegacy();
        thenTheCancelledKeysReadAreTheOneWritten();
    }

    @Test
    void shouldHideTheHashOfACancelledFile() {
        whenTheKeyOfTheFileHashIsMadeWithTheSeed(0x01020304);

        thenTheKeyIsNotTheFileHash();
    }

    @Test
    void shouldRecogniseLegacyCancelledFiles() throws DecodeException {
        givenTheFile("0e" + "01000000" + H.toHex() + "00");

        whenTheCancelledFilesAreRead();

        thenTheCancelledFilesAreLegacy();
    }

    @Test
    void shouldReadBackTheAichTreesItWrites() {
        givenTheAichEntries(AICH_ENTRY, AICH_ENTRY);
        givenTheAichEntriesWritten();

        whenTheAichEntriesAreRead();

        thenTheAichEntriesReadAre(AICH_ENTRY, AICH_ENTRY);
    }

    @Test
    void shouldReadTheOldVersionOfTheAichTrees() {
        givenTheFile(ZERO_AICH_HEX + "0200" + ZERO_AICH_HEX + ZERO_AICH_HEX);

        whenTheOldAichEntriesAreRead();

        thenTheAichEntriesReadAre(AICH_ENTRY);
    }

    @Test
    void shouldRefuseAnImpossibleCountOfAichTrees() {
        givenTheFile("02" + ZERO_AICH_HEX + "ffffff7f");

        whenTheAichEntriesAreRead();

        thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void shouldHoldTheUserHashInPreferencesDat() throws DecodeException {
        givenAPreferencesDatWithAMarkedUserHash();

        whenThePreferencesDatIsRead();

        thenTheUserHashReadIsTheOneWritten();
        thenTheUserHashReadIsMarked();
        thenTheWindowPlacementHasTheLength(44);
    }

    @Property
    void shouldFailOnGarbageOnlyWithADecodeException(@ForAll @Size(max = 200) byte[] data) {
        whenEveryDecoderReadsTheGarbage(data);

        thenNothingButDecodeExceptionsOccurred();
    }

    private interface Decoder {
        Object decode() throws DecodeException;
    }

    private void givenTheFile(String hex) {
        this.file = HEX.parseHex(hex);
    }

    private void givenTheByteSetTo(int at, int value) {
        this.file[at] = (byte) value;
    }

    private void givenTheServers(Server... written) {
        this.servers = List.of(written);
    }

    private void givenTheServersWritten() {
        this.file = ServerMet.write(this.servers);
    }

    private void givenTheServersRead() throws DecodeException {
        this.servers = ServerMet.read(this.file);
    }

    private void givenAConsistentKnownFile() {
        List<Hash16> parts = List.of(H, Ed2k.EMPTY_PART_HASH);
        this.knownFiles.add(new KnownFile(
                1_700_000_000L,
                Ed2k.fileHash(parts),
                parts,
                List.of(Tag.of(FileTag.FILENAME, "a.bin"), Tag.of(FileTag.FILESIZE, Ed2k.PARTSIZE))));
    }

    private void givenAnInconsistentKnownFile() {
        this.knownFiles.add(new KnownFile(1, H, List.of(), List.of(Tag.of(FileTag.FILESIZE, Ed2k.PARTSIZE))));
    }

    private void givenAKnownFileOfTheSize(long size) {
        this.knownFiles.add(new KnownFile(0, H, List.of(), List.of(Tag.of(FileTag.FILESIZE, size))));
    }

    private void givenTheKnownFilesWritten() {
        this.file = KnownMet.write(this.knownFiles);
    }

    private void givenAPartMetWithTheTags(Tag... tags) {
        this.partMet = new PartMet.Contents(PartMet.VERSION, 0, H, List.of(), List.of(tags));
    }

    private void givenAPartMetWithTheGaps(Gap... gaps) {
        givenAPartMetWithTheTags(Tag.of(FileTag.FILENAME, "x"), Tag.of(FileTag.FILESIZE, 1000));
        this.partMet = this.partMet.withGaps(List.of(gaps));
    }

    private void givenThePartMetWritten() {
        this.file = PartMet.write(this.partMet);
    }

    private void givenTheCredits(Credit... written) {
        this.credits = List.of(written);
    }

    private void givenTheCreditsWritten() {
        this.file = ClientsMet.write(this.credits);
    }

    private void givenTheFriends(Friend... written) {
        this.friends = List.of(written);
    }

    private void givenTheFriendsWritten() {
        this.file = FriendsMet.write(this.friends);
    }

    private void givenTheKeyOfTheFileHashWithTheSeed(int value) {
        this.seed = value;
        this.key = CancelledMet.key(value, H);
    }

    private void givenTheCancelledFilesWritten() {
        this.file = CancelledMet.write(this.seed, List.of(this.key));
    }

    private void givenTheAichEntries(Known2Met.Entry... written) {
        this.aichEntries = List.of(written);
    }

    private void givenTheAichEntriesWritten() {
        this.file = Known2Met.write(this.aichEntries);
    }

    private void givenAPreferencesDatWithAMarkedUserHash() {
        byte[] hash = H.toByteArray();
        hash[5] = 14;
        hash[14] = 111;
        this.userHash = Hash16.of(hash);
        this.file = new byte[61];
        this.file[0] = PreferencesDat.VERSION;
        System.arraycopy(hash, 0, this.file, 1, 16);
    }

    private void whenTheServersAreRead() throws DecodeException {
        this.serversRead = ServerMet.read(this.file);
    }

    private void whenTheServersAreWritten() {
        this.file = ServerMet.write(this.servers);
    }

    private void whenTheKnownFilesAreRead() throws DecodeException {
        this.knownFilesRead = KnownMet.read(this.file);
    }

    private void whenTheKnownFilesAreWritten() {
        this.file = KnownMet.write(this.knownFiles);
    }

    private void whenThePartMetIsRead() {
        try {
            this.partMetRead = PartMet.read(this.file);
        } catch (DecodeException e) {
            this.exception = e;
        }
    }

    private void whenThePartMetIsWritten() {
        this.file = PartMet.write(this.partMet);
    }

    private void whenItsGapsAreTaken() {
        this.gapsTaken = this.partMet.gaps();
    }

    private void whenTheCreditsAreRead() {
        try {
            this.creditsRead = ClientsMet.read(this.file);
        } catch (DecodeException e) {
            this.exception = e;
        }
    }

    private void whenTheCreditsAreWritten() {
        this.file = ClientsMet.write(this.credits);
    }

    private void whenTheFriendsAreRead() throws DecodeException {
        this.friendsRead = FriendsMet.read(this.file);
    }

    private void whenTheFriendsAreWritten() {
        this.file = FriendsMet.write(this.friends);
    }

    private void whenTheCancelledFilesAreRead() throws DecodeException {
        this.cancelledRead = CancelledMet.read(this.file);
    }

    private void whenTheKeyOfTheFileHashIsMadeWithTheSeed(int value) {
        this.key = CancelledMet.key(value, H);
    }

    private void whenTheAichEntriesAreRead() {
        try {
            this.aichEntriesRead = Known2Met.read(this.file);
        } catch (DecodeException e) {
            this.exception = e;
        }
    }

    private void whenTheOldAichEntriesAreRead() {
        try {
            this.aichEntriesRead = Known2Met.readOld(this.file);
        } catch (DecodeException e) {
            this.exception = e;
        }
    }

    private void whenThePreferencesDatIsRead() throws DecodeException {
        this.preferencesRead = PreferencesDat.read(this.file);
    }

    private void whenEveryDecoderReadsTheGarbage(byte[] data) {
        this.failures = new LinkedHashMap<>();
        decode("server.met", () -> ServerMet.read(data));
        decode("known.met", () -> KnownMet.read(data));
        decode("part.met", () -> PartMet.read(data));
        decode("clients.met", () -> ClientsMet.read(data));
        decode("emfriends.met", () -> FriendsMet.read(data));
        decode("cancelled.met", () -> CancelledMet.read(data));
        decode("known2_64.met", () -> Known2Met.read(data));
        decode("old known2.met", () -> Known2Met.readOld(data));
        decode("preferences.dat", () -> PreferencesDat.read(data));
    }

    private void thenTheFileIs(String hex) {
        assertThat(this.file).isEqualTo(HEX.parseHex(hex));
    }

    private void thenTheFileHasTheSize(int size) {
        assertThat(this.file).hasSize(size);
    }

    private void thenTheFirstByteIs(int value) {
        assertThat(this.file[0]).isEqualTo((byte) value);
    }

    private void thenTheFileInHexContains(String hex) {
        assertThat(HEX.formatHex(this.file)).contains(hex);
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private void thenTheFirstServerIs(String ip, int port, String name) {
        Server server = this.serversRead.getFirst();
        assertThat(Ipv4.format(server.ip())).isEqualTo(ip);
        assertThat(server.port()).isEqualTo(port);
        assertThat(server.name()).contains(name);
    }

    private void thenTheServersReadAreTheOnesWritten() {
        assertThat(this.serversRead.getFirst()).isEqualTo(this.servers.getFirst());
    }

    private void thenTheUsersOfTheFirstServerAre(long users) {
        assertThat(this.serversRead.getFirst().users()).hasValue(users);
    }

    private void thenTheKnownFilesReadAreTheOnesWritten() {
        assertThat(this.knownFilesRead).containsExactlyElementsOf(this.knownFiles);
    }

    private void thenTheKnownFileIsConsistent(int index) {
        assertThat(this.knownFilesRead.get(index).isConsistent()).isTrue();
    }

    private void thenTheKnownFileIsInconsistent(int index) {
        assertThat(this.knownFilesRead.get(index).isConsistent()).isFalse();
    }

    private void thenTheSizeOfTheKnownFileIs(int index, long size) {
        assertThat(this.knownFilesRead.get(index).size()).hasValue(size);
    }

    private void thenThePartMetGapsReadAre(Gap... gaps) {
        assertThat(this.exception).isNull();
        assertThat(this.partMetRead.gaps()).containsExactly(gaps);
    }

    private void thenThePartMetTagsReadInclude(Tag... tags) {
        assertThat(this.partMetRead.tags()).contains(tags);
    }

    private void thenTheGapsAre(Gap... gaps) {
        assertThat(this.gapsTaken).containsExactly(gaps);
    }

    private void thenTheCreditsReadAreTheOnesWritten() {
        assertThat(this.exception).isNull();
        assertThat(this.creditsRead).containsExactlyElementsOf(this.credits);
    }

    private void thenTheFirstCreditHasTransferred(long uploaded, long downloaded) {
        assertThat(this.exception).isNull();
        Credit credit = this.creditsRead.getFirst();
        assertThat(credit.uploaded()).isEqualTo(uploaded);
        assertThat(credit.downloaded()).isEqualTo(downloaded);
    }

    private void thenTheFirstCreditHasNoKey() {
        assertThat(this.creditsRead.getFirst().publicKey()).isEqualTo(Bytes.EMPTY);
    }

    private void thenTheFriendsReadAreTheOnesWritten() {
        assertThat(this.friendsRead.getFirst()).isEqualTo(this.friends.getFirst());
    }

    private void thenTheFirstFriendIsNamed(String name) {
        assertThat(this.friendsRead.getFirst().name()).contains(name);
    }

    private void thenTheFirstFriendHasTheKadId(Hash16 kadId) {
        assertThat(this.friendsRead.getFirst().kadId()).contains(kadId);
    }

    private void thenTheSeedReadIs(int value) {
        assertThat(this.cancelledRead.seed()).isEqualTo(value);
    }

    private void thenTheCancelledFilesAreNotLegacy() {
        assertThat(this.cancelledRead.legacy()).isFalse();
    }

    private void thenTheCancelledFilesAreLegacy() {
        assertThat(this.cancelledRead.legacy()).isTrue();
    }

    private void thenTheCancelledKeysReadAreTheOneWritten() {
        assertThat(this.cancelledRead.keys()).containsExactly(this.key);
    }

    private void thenTheKeyIsNotTheFileHash() {
        assertThat(this.key).isNotEqualTo(H);
    }

    private void thenTheAichEntriesReadAre(Known2Met.Entry... entries) {
        assertThat(this.exception).isNull();
        assertThat(this.aichEntriesRead).containsExactly(entries);
    }

    private void thenTheUserHashReadIsTheOneWritten() {
        assertThat(this.preferencesRead.userHash()).isEqualTo(this.userHash);
    }

    private void thenTheUserHashReadIsMarked() {
        assertThat(PreferencesDat.isMarkedUserHash(this.preferencesRead.userHash()))
                .isTrue();
    }

    private void thenTheWindowPlacementHasTheLength(int length) {
        assertThat(this.preferencesRead.windowPlacement().length()).isEqualTo(length);
    }

    private void thenNothingButDecodeExceptionsOccurred() {
        this.failures.forEach((name, failure) -> {
            if (failure != null) {
                assertThat(failure).as(name).isInstanceOf(DecodeException.class);
            }
        });
    }

    private void decode(String name, Decoder decoder) {
        this.failures.put(name, catchThrowable(decoder::decode));
    }
}
