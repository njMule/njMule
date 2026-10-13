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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.io.StringEncoding;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
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

    @Test
    void loginCarriesTheFourTagsInTheOldEncoding() {
        Packet login = new ServerMessages.Login(HASH, 0, 4662, "x", 0x0719).encode();

        assertThat(login.opcode()).isEqualTo(0x01);
        assertThat(HEX.formatHex(login.payload()))
                .isEqualTo(
                        "11".repeat(16)
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
    void idChangeReadsTheOptionalFields() throws DecodeException {
        ServerMessages.IdChange low = ServerMessages.IdChange.decode(packet(0x40, "39300000"));
        assertThat(low.isLowId()).isTrue();
        assertThat(low.serverFlags()).isZero();
        assertThat(low.publicIp()).isZero();

        // HighID 1.2.3.4 (network order on the wire), flags, unused port, reported IP.
        ServerMessages.IdChange high =
                ServerMessages.IdChange.decode(
                        packet(0x40, "01020304" + "19050000" + "00000000" + "05060708"));
        assertThat(high.isLowId()).isFalse();
        assertThat(high.publicIp()).isEqualTo(Ipv4.parse("1.2.3.4"));
        assertThat(high.has(ServerMessages.TCP_COMPRESSION)).isTrue();
        assertThat(high.has(ServerMessages.TCP_LARGEFILES)).isTrue();
        assertThat(high.reportedIp()).isEqualTo(Ipv4.parse("5.6.7.8"));

        assertThatThrownBy(() -> ServerMessages.IdChange.decode(packet(0x40, "0102")))
                .isInstanceOf(DecodeException.class);
    }

    @Test
    void serverStatusAndIdentity() throws DecodeException {
        assertThat(ServerMessages.ServerStatus.decode(packet(0x34, "0a000000" + "14000000")))
                .contains(new ServerMessages.ServerStatus(10, 20));
        assertThat(ServerMessages.ServerStatus.decode(packet(0x34, "0a000000"))).isEmpty();

        PacketWriter ident =
                new PacketWriter()
                        .hash16(HASH)
                        .u32(Ipv4.toEd2k(Ipv4.parse("9.8.7.6")))
                        .u16(4661)
                        .u32(2);
        TagCodec.writeEd2kList(
                ident,
                List.of(
                        new Tag.StringTag(TagName.id(ServerTag.SERVERNAME), "Test server"),
                        new Tag.StringTag(TagName.id(ServerTag.DESCRIPTION), "for tests")),
                Ed2kTagFormat.NEW,
                StringEncoding.UTF8);
        ServerMessages.ServerIdent decoded =
                ServerMessages.ServerIdent.decode(Packet.of(Protocol.EDONKEY, 0x41, ident));
        assertThat(decoded.ip()).isEqualTo(Ipv4.parse("9.8.7.6"));
        assertThat(decoded.name()).contains("Test server");
        assertThat(decoded.description()).contains("for tests");
    }

    @Test
    void searchResultsCarryFilesAndTheMoreFlag() throws DecodeException {
        PacketWriter out = new PacketWriter().u32(2);
        writeFile(out, HASH, "ubuntu.iso", 5_000_000_000L, 42);
        writeFile(out, Hash16.parseHex("22".repeat(16)), "small.txt", 3, 1);
        out.u8(1);

        ServerMessages.SearchResults results =
                ServerMessages.SearchResults.decode(Packet.of(Protocol.EDONKEY, 0x33, out));

        assertThat(results.more()).isTrue();
        assertThat(results.files()).hasSize(2);
        FileEntry big = results.files().getFirst();
        assertThat(big.name()).contains("ubuntu.iso");
        assertThat(big.size()).hasValue(5_000_000_000L);
        assertThat(big.sources()).isEqualTo(42);
        assertThat(results.files().get(1).size()).hasValue(3);

        PacketWriter liar = new PacketWriter().u32(1000);
        assertThatThrownBy(
                        () ->
                                ServerMessages.SearchResults.decode(
                                        Packet.of(Protocol.EDONKEY, 0x33, liar)))
                .isInstanceOf(DecodeException.class);
    }

    @Test
    void serverMessagesSplitIntoLines() throws DecodeException {
        PacketWriter out =
                new PacketWriter()
                        .string16(
                                "server version 17.15\r\n\r\nWelcome!\nERROR: x",
                                StringEncoding.UTF8);

        assertThat(ServerMessages.decodeMessage(Packet.of(Protocol.EDONKEY, 0x38, out)))
                .containsExactly("server version 17.15", "Welcome!", "ERROR: x");
    }

    @Test
    void serverListsAndCallbacks() throws DecodeException {
        assertThat(ServerMessages.decodeServerList(packet(0x32, "01" + "01020304" + "3512")))
                .containsExactly(new ServerMessages.Endpoint(Ipv4.parse("1.2.3.4"), 4661));
        assertThatThrownBy(() -> ServerMessages.decodeServerList(packet(0x32, "05" + "01020304")))
                .isInstanceOf(DecodeException.class);

        ServerMessages.CallbackRequested plain =
                ServerMessages.CallbackRequested.decode(packet(0x35, "0a000001" + "3612"));
        assertThat(plain.endpoint())
                .isEqualTo(new ServerMessages.Endpoint(Ipv4.parse("10.0.0.1"), 4662));
        assertThat(plain.userHash()).isEmpty();
        ServerMessages.CallbackRequested withHash =
                ServerMessages.CallbackRequested.decode(
                        packet(0x35, "0a000001" + "3612" + "81" + "11".repeat(16)));
        assertThat(withHash.cryptOptions()).isEqualTo(0x81);
        assertThat(withHash.userHash()).contains(HASH);
    }

    @Test
    void statusRequestsAndAnswers() throws DecodeException {
        assertThat(HEX.formatHex(ServerDatagrams.statusRequest(0x55AA1234L).payload()))
                .isEqualTo("3412aa55");

        ServerDatagrams.Status minimal =
                ServerDatagrams.Status.decode(packet(0x97, "3412aa55" + "64000000" + "c8000000"));
        assertThat(minimal.users()).isEqualTo(100);
        assertThat(minimal.files()).isEqualTo(200);
        assertThat(minimal.udpFlags()).isZero();

        ServerDatagrams.Status full =
                ServerDatagrams.Status.decode(
                        packet(
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
                                        + "efbeadde"));
        assertThat(full.maxUsers()).isEqualTo(1000);
        assertThat(full.has(ServerDatagrams.UDP_OBFUSCATION)).isTrue();
        assertThat(full.obfuscatedUdpPort()).isEqualTo(0x1241);
        assertThat(full.obfuscatedTcpPort()).isEqualTo(0x1240);
        assertThat(full.udpKey()).isEqualTo(0xDEADBEEFL);

        assertThatThrownBy(() -> ServerDatagrams.Status.decode(packet(0x97, "3412aa55")))
                .isInstanceOf(DecodeException.class);
    }

    @Test
    void globalSearchesPickTheRequestTheServerUnderstands() throws Exception {
        SearchExpr words = SearchParser.parse("ubuntu iso");
        Packet third =
                ServerDatagrams.searchRequest(
                                words,
                                ServerDatagrams.UDP_LARGEFILES | ServerDatagrams.UDP_EXT_GETFILES)
                        .orElseThrow();
        assertThat(third.opcode()).isEqualTo(Opcodes.ServerUdp.GLOBSEARCHREQ3.code());
        assertThat(HEX.formatHex(third.payload())).startsWith("01000000890e01");

        assertThat(
                        ServerDatagrams.searchRequest(words, ServerDatagrams.UDP_EXT_GETFILES)
                                .orElseThrow()
                                .opcode())
                .isEqualTo(Opcodes.ServerUdp.GLOBSEARCHREQ2.code());
        assertThat(ServerDatagrams.searchRequest(words, 0).orElseThrow().opcode())
                .isEqualTo(Opcodes.ServerUdp.GLOBSEARCHREQ.code());

        SearchExpr huge = SearchParser.parse("movie @size>5g");
        assertThat(ServerDatagrams.searchRequest(huge, ServerDatagrams.UDP_EXT_GETFILES)).isEmpty();
    }

    @Test
    void oneGlobalAnswerMayCarrySeveralResults() throws DecodeException {
        PacketWriter out = new PacketWriter();
        writeFile(out, HASH, "a", 1, 1);
        out.u8(Protocol.EDONKEY).u8(Opcodes.ServerUdp.GLOBSEARCHRES.code());
        writeFile(out, Hash16.parseHex("33".repeat(16)), "b", 2, 2);
        out.u8(0x00);

        List<FileEntry> files =
                ServerDatagrams.decodeSearchResults(
                        Packet.of(Protocol.EDONKEY, Opcodes.ServerUdp.GLOBSEARCHRES.code(), out));

        assertThat(files).extracting(f -> f.name().orElseThrow()).containsExactly("a", "b");
    }

    @Test
    void readsTheHelloOfEmule() throws DecodeException {
        // Default preferences, nick "x", no server, Kad off.
        String hex =
                "10"
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

        Hello hello = Hello.decode(packet(0x01, hex));

        assertThat(hello.userHash()).isEqualTo(HASH);
        assertThat(hello.nick()).contains("x");
        assertThat(hello.listenPort()).isEqualTo(4662);
        assertThat(hello.udpPort()).isEqualTo(4672);
        assertThat(hello.kadPort()).isZero();
        assertThat(hello.miscOptions1()).isEqualTo(0x34134216);
        assertThat(hello.miscOptions2()).isEqualTo(0x2DBA);
        assertThat(hello.emuleVersion()).hasValue(0x00012000);
        assertThat(hello.serverIp()).isZero();
    }

    @Test
    void ourHelloRoundTripsAndTheAnswerHasNoSizeByte() throws DecodeException {
        Hello ours =
                Hello.of(
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

        Packet hello = ours.encode(false);
        Packet answer = ours.encode(true);

        assertThat(hello.length()).isEqualTo(answer.length() + 1);
        assertThat(Hello.decode(hello)).isEqualTo(ours);
        assertThat(Hello.decode(answer)).isEqualTo(ours);
        assertThat(Hello.decode(answer).serverIp()).isEqualTo(Ipv4.parse("9.8.7.6"));
    }

    @Test
    void serverSideMessagesRoundTrip() throws DecodeException {
        ServerMessages.Login login =
                new ServerMessages.Login(HASH, 0x0100_0001L, 4662, "nick", 0x719);
        assertThat(ServerMessages.Login.decode(login.encode())).isEqualTo(login);

        ServerMessages.IdChange high =
                new ServerMessages.IdChange(
                        Ipv4.toEd2k(Ipv4.parse("1.2.3.4")), 0x519, Ipv4.parse("1.2.3.4"));
        ServerMessages.IdChange low =
                new ServerMessages.IdChange(12_345, 0x19, Ipv4.parse("5.6.7.8"));
        assertThat(ServerMessages.IdChange.decode(high.encode())).isEqualTo(high);
        assertThat(ServerMessages.IdChange.decode(low.encode())).isEqualTo(low);

        ServerMessages.ServerStatus status = new ServerMessages.ServerStatus(1, 2);
        assertThat(ServerMessages.ServerStatus.decode(status.encode())).contains(status);

        ServerMessages.ServerIdent ident =
                new ServerMessages.ServerIdent(
                        HASH, Ipv4.parse("9.8.7.6"), 4661, Optional.of("name"), Optional.empty());
        assertThat(ServerMessages.ServerIdent.decode(ident.encode())).isEqualTo(ident);

        FileEntry file =
                new FileEntry(
                        HASH, 0, 0, List.of(new Tag.StringTag(TagName.id(FileTag.FILENAME), "a")));
        for (boolean more : new boolean[] {true, false}) {
            ServerMessages.SearchResults results =
                    new ServerMessages.SearchResults(List.of(file, file), more);
            assertThat(ServerMessages.SearchResults.decode(results.encode())).isEqualTo(results);
        }

        assertThat(ServerMessages.decodeMessage(ServerMessages.message("one\ntwo")))
                .containsExactly("one", "two");
        List<ServerMessages.Endpoint> servers =
                List.of(new ServerMessages.Endpoint(Ipv4.parse("1.1.1.1"), 4242));
        assertThat(ServerMessages.decodeServerList(ServerMessages.serverList(servers)))
                .isEqualTo(servers);

        ServerDatagrams.Status full =
                new ServerDatagrams.Status(7, 1, 2, 3, 4, 5, 0x30B, 6, 4665, 4667, 0xCAFEL);
        assertThat(ServerDatagrams.Status.decode(full.encode())).isEqualTo(full);
        assertThat(
                        ServerDatagrams.decodeSearchResults(
                                ServerDatagrams.searchResults(List.of(file, file, file))))
                .containsExactly(file, file, file);
    }

    @Test
    void sourcesQueriesAndAnswers() throws DecodeException {
        assertThat(HEX.formatHex(ServerMessages.getSources(HASH, 1000, false).payload()))
                .isEqualTo("11".repeat(16) + "e8030000");
        Packet large = ServerMessages.getSources(HASH, 5_000_000_000L, true);
        assertThat(large.opcode()).isEqualTo(0x23);
        assertThat(HEX.formatHex(large.payload()))
                .isEqualTo("11".repeat(16) + "00000000" + "00f2052a01000000");

        ServerMessages.FoundSources plain =
                ServerMessages.FoundSources.decode(
                        packet(
                                0x42,
                                "11".repeat(16)
                                        + "02"
                                        + "01020304"
                                        + "3612"
                                        + "39300000"
                                        + "3712"));
        assertThat(plain.sources())
                .containsExactly(
                        new ServerMessages.Source(0x04030201L, 4662, 0, Optional.empty()),
                        new ServerMessages.Source(12_345, 4663, 0, Optional.empty()));
        assertThat(plain.sources().get(1).isLowId()).isTrue();

        ServerMessages.FoundSources obfuscated =
                new ServerMessages.FoundSources(
                        HASH,
                        List.of(
                                new ServerMessages.Source(
                                        0x04030201L, 4662, 0x01, Optional.empty()),
                                new ServerMessages.Source(7, 1, 0x83, Optional.of(HASH))));
        assertThat(ServerMessages.FoundSources.decode(obfuscated.encode())).isEqualTo(obfuscated);
        assertThatThrownBy(
                        () ->
                                ServerMessages.FoundSources.decode(
                                        packet(0x42, "11".repeat(16) + "05" + "0102")))
                .isInstanceOf(DecodeException.class);
    }

    @Test
    void versionsCarryTheNjmuleCompatibleClientId() {
        assertThat(ClientVersion.fromSemVer("0.0.1-alpha.1")).isEqualTo(0x4E00_0080);
        assertThat(ClientVersion.fromSemVer("1.2.3"))
                .isEqualTo(0x4E00_0000 | 1 << 17 | 2 << 10 | 3 << 7);
        assertThat(ClientVersion.fromSemVer("garbage")).isEqualTo(0x4E00_0000);
    }

    @Property
    void garbageOnlyFailsWithDecodeException(@ForAll @Size(max = 200) byte[] data) {
        Packet packet = Packet.of(Protocol.EDONKEY, Opcodes.Client.HELLO.code(), data);
        decodeQuietly(() -> Hello.decode(packet));
        decodeQuietly(() -> ServerMessages.IdChange.decode(packet));
        decodeQuietly(() -> ServerMessages.SearchResults.decode(packet));
        decodeQuietly(() -> ServerMessages.ServerIdent.decode(packet));
        decodeQuietly(() -> ServerMessages.decodeMessage(packet));
        decodeQuietly(() -> ServerMessages.decodeServerList(packet));
        decodeQuietly(() -> ServerMessages.CallbackRequested.decode(packet));
        decodeQuietly(() -> ServerDatagrams.Status.decode(packet));
        decodeQuietly(() -> ServerDatagrams.decodeSearchResults(packet));
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

    private static void writeFile(
            PacketWriter out, Hash16 hash, String name, long size, int sources) {
        out.hash16(hash).u32(0).u16(0);
        List<Tag> tags =
                size > 0xFFFF_FFFFL
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
        return new byte[] {
            (byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24)
        };
    }
}
