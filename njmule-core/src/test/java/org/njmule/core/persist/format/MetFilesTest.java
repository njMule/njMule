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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;
import java.util.List;
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

    @Test
    void serverMetStoresIpsInNetworkOrder() throws DecodeException {
        // One server 1.2.3.4:4661 with name "abc" (old tag encoding).
        byte[] file =
                HEX.parseHex(
                        "e0"
                                + "01000000"
                                + "01020304"
                                + "3512"
                                + "01000000"
                                + "020100010300616263");

        Server server = ServerMet.read(file).getFirst();

        assertThat(Ipv4.format(server.ip())).isEqualTo("1.2.3.4");
        assertThat(server.port()).isEqualTo(4661);
        assertThat(server.name()).contains("abc");
        assertThat(ServerMet.write(ServerMet.read(file))).isEqualTo(file);
    }

    @Test
    void serverMetKeepsUnknownTagsAndNamedCounters() throws DecodeException {
        Server server =
                new Server(
                        Ipv4.of(10, 0, 0, 1),
                        4242,
                        List.of(
                                Tag.of(ServerTag.SERVERNAME, "Città"),
                                new IntTag(TagName.of(ServerTag.USERS), 1234),
                                Tag.of(0x77, 9)));

        Server read = ServerMet.read(ServerMet.write(List.of(server))).getFirst();

        assertThat(read).isEqualTo(server);
        assertThat(read.users()).hasValue(1234);
    }

    @Test
    void knownMetRoundTripsAndChecksConsistency() throws DecodeException {
        List<Hash16> parts = List.of(H, Ed2k.EMPTY_PART_HASH);
        KnownFile good =
                new KnownFile(
                        1_700_000_000L,
                        Ed2k.fileHash(parts),
                        parts,
                        List.of(
                                Tag.of(FileTag.FILENAME, "a.bin"),
                                Tag.of(FileTag.FILESIZE, Ed2k.PARTSIZE)));
        KnownFile bad =
                new KnownFile(1, H, List.of(), List.of(Tag.of(FileTag.FILESIZE, Ed2k.PARTSIZE)));

        List<KnownFile> read = KnownMet.read(KnownMet.write(List.of(good, bad)));

        assertThat(read).containsExactly(good, bad);
        assertThat(read.get(0).isConsistent()).isTrue();
        assertThat(read.get(1).isConsistent()).isFalse();
        assertThat(read.get(0).size()).hasValue(Ed2k.PARTSIZE);
    }

    @Test
    void largeFilesSwitchKnownMetToSixtyFourBitTags() throws DecodeException {
        KnownFile big =
                new KnownFile(0, H, List.of(), List.of(Tag.of(FileTag.FILESIZE, 5_000_000_000L)));

        byte[] file = KnownMet.write(List.of(big));

        assertThat(file[0]).isEqualTo((byte) KnownMet.HEADER_64BIT);
        assertThat(KnownMet.read(file).getFirst().size()).hasValue(5_000_000_000L);
    }

    @Test
    void partMetGapsAreExclusiveAndClamped() throws DecodeException {
        PartMet.Contents base =
                new PartMet.Contents(
                        PartMet.VERSION,
                        0,
                        H,
                        List.of(),
                        List.of(Tag.of(FileTag.FILENAME, "x"), Tag.of(FileTag.FILESIZE, 1000)));
        PartMet.Contents withGaps = base.withGaps(List.of(new Gap(0, 100), new Gap(900, 1000)));

        PartMet.Contents read = PartMet.read(PartMet.write(withGaps));

        assertThat(read.gaps()).containsExactly(new Gap(0, 100), new Gap(900, 1000));
        assertThat(read.tags())
                .contains(new IntTag(TagName.of("\n1"), 1000), new IntTag(TagName.of("\t1"), 900));

        // A gap past the end is clamped, an incomplete pair is dropped.
        PartMet.Contents odd =
                new PartMet.Contents(
                        PartMet.VERSION,
                        0,
                        H,
                        List.of(),
                        List.of(
                                Tag.of(FileTag.FILESIZE, 1000),
                                new IntTag(TagName.of("\t0"), 500),
                                new IntTag(TagName.of("\n0"), 5000),
                                new IntTag(TagName.of("\t7"), 10)));
        assertThat(odd.gaps()).containsExactly(new Gap(500, 1000));
    }

    @Test
    void partMetChoosesTheLargeVersionAboveFourGigabytes() throws DecodeException {
        PartMet.Contents big =
                new PartMet.Contents(
                        PartMet.VERSION,
                        0,
                        H,
                        List.of(),
                        List.of(Tag.of(FileTag.FILESIZE, 5_000_000_000L)));

        byte[] file = PartMet.write(big);

        assertThat(file[0]).isEqualTo((byte) PartMet.VERSION_LARGE);
        assertThatThrownBy(() -> PartMet.read(HEX.parseHex("e1")))
                .isInstanceOf(DecodeException.class);
    }

    @Test
    void clientsMetRecordsHave119Bytes() throws DecodeException {
        Credit credit =
                new Credit(H, 5_000_000_000L, 42, 1_700_000_000L, Bytes.of(new byte[] {1, 2, 3}));

        byte[] file = ClientsMet.write(List.of(credit));

        assertThat(file).hasSize(1 + 4 + 119);
        assertThat(ClientsMet.read(file)).containsExactly(credit);
        file[1 + 4 + 16 + 4 * 5 + 2] = 81;
        assertThatThrownBy(() -> ClientsMet.read(file)).isInstanceOf(DecodeException.class);
    }

    @Test
    void oldClientsMetHasNoKeys() throws DecodeException {
        byte[] file =
                HEX.parseHex(
                        "11"
                                + "01000000"
                                + H.toHex()
                                + "0a000000"
                                + "14000000"
                                + "00000000"
                                + "01000000"
                                + "00000000"
                                + "0000");

        Credit credit = ClientsMet.read(file).getFirst();

        assertThat(credit.uploaded()).isEqualTo((1L << 32) + 10);
        assertThat(credit.downloaded()).isEqualTo(20);
        assertThat(credit.publicKey()).isEqualTo(Bytes.EMPTY);
    }

    @Test
    void friendsMetWritesTheKadIdInTheNewEncoding() throws DecodeException {
        Friend friend =
                new Friend(
                        H,
                        Ipv4.of(1, 2, 3, 4),
                        4662,
                        100,
                        200,
                        List.of(
                                Tag.of(FriendsMet.NAME, "pal"),
                                new HashTag(TagName.id(FriendsMet.KAD_ID), H)));

        byte[] file = FriendsMet.write(List.of(friend));

        assertThat(HEX.formatHex(file))
                .contains("8102" + H.toHex().toLowerCase(java.util.Locale.ROOT));
        Friend read = FriendsMet.read(file).getFirst();
        assertThat(read).isEqualTo(friend);
        assertThat(read.name()).contains("pal");
        assertThat(read.kadId()).contains(H);
    }

    @Test
    void cancelledMetHidesTheFileHashes() throws DecodeException {
        Hash16 key = CancelledMet.key(0x01020304, H);

        CancelledMet.Contents read =
                CancelledMet.read(CancelledMet.write(0x01020304, List.of(key)));

        assertThat(read.seed()).isEqualTo(0x01020304);
        assertThat(read.legacy()).isFalse();
        assertThat(read.keys()).containsExactly(key);
        assertThat(key).isNotEqualTo(H);
        assertThat(CancelledMet.read(HEX.parseHex("0e" + "01000000" + H.toHex() + "00")).legacy())
                .isTrue();
    }

    @Test
    void known2MetReadsBothVersions() throws DecodeException {
        Hash20 a = Hash20.of(new byte[20]);
        Known2Met.Entry entry = new Known2Met.Entry(a, List.of(a, a));

        assertThat(Known2Met.read(Known2Met.write(List.of(entry, entry))))
                .containsExactly(entry, entry);
        String zero = "00".repeat(20);
        assertThat(Known2Met.readOld(HEX.parseHex(zero + "0200" + zero + zero)))
                .containsExactly(entry);
        assertThatThrownBy(() -> Known2Met.read(HEX.parseHex("02" + zero + "ffffff7f")))
                .isInstanceOf(DecodeException.class);
    }

    @Test
    void preferencesDatHoldsTheUserHash() throws DecodeException {
        byte[] hash = H.toByteArray();
        hash[5] = 14;
        hash[14] = 111;
        byte[] file = new byte[61];
        file[0] = PreferencesDat.VERSION;
        System.arraycopy(hash, 0, file, 1, 16);

        PreferencesDat.Contents prefs = PreferencesDat.read(file);

        assertThat(prefs.userHash()).isEqualTo(Hash16.of(hash));
        assertThat(PreferencesDat.isMarkedUserHash(prefs.userHash())).isTrue();
        assertThat(prefs.windowPlacement().length()).isEqualTo(44);
    }

    @Property
    void garbageOnlyFailsWithDecodeException(@ForAll @Size(max = 200) byte[] data) {
        expectOnlyDecodeErrors(() -> ServerMet.read(data));
        expectOnlyDecodeErrors(() -> KnownMet.read(data));
        expectOnlyDecodeErrors(() -> PartMet.read(data));
        expectOnlyDecodeErrors(() -> ClientsMet.read(data));
        expectOnlyDecodeErrors(() -> FriendsMet.read(data));
        expectOnlyDecodeErrors(() -> CancelledMet.read(data));
        expectOnlyDecodeErrors(() -> Known2Met.read(data));
        expectOnlyDecodeErrors(() -> Known2Met.readOld(data));
        expectOnlyDecodeErrors(() -> PreferencesDat.read(data));
    }

    private interface Decoder {
        Object decode() throws DecodeException;
    }

    private static void expectOnlyDecodeErrors(Decoder decoder) {
        try {
            decoder.decode();
        } catch (DecodeException expected) {
            // malformed file
        }
    }
}
