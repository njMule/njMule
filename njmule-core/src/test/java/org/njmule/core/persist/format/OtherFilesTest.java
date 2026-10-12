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

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;
import org.njmule.core.kad.UInt128;
import org.njmule.core.persist.format.IpFilterDat.Range;
import org.njmule.core.persist.format.KadIndexFiles.Entry;
import org.njmule.core.persist.format.KadIndexFiles.IndexKey;
import org.njmule.core.persist.format.KadIndexFiles.Keyword;
import org.njmule.core.persist.format.KadIndexFiles.KeywordEntry;
import org.njmule.core.persist.format.KadIndexFiles.KeywordIndex;
import org.njmule.core.persist.format.KadIndexFiles.KeywordSource;
import org.njmule.core.persist.format.KadIndexFiles.Load;
import org.njmule.core.persist.format.KadIndexFiles.LoadIndex;
import org.njmule.core.persist.format.KadIndexFiles.PublishedName;
import org.njmule.core.persist.format.KadIndexFiles.Publisher;
import org.njmule.core.persist.format.KadIndexFiles.Source;
import org.njmule.core.persist.format.KadIndexFiles.SourceIndex;
import org.njmule.core.persist.format.KadIndexFiles.Tracking;
import org.njmule.core.persist.format.SearchFiles.Result;
import org.njmule.core.persist.format.SearchFiles.StoredSearch;
import org.njmule.core.persist.format.TextFiles.SharedFile;
import org.njmule.core.persist.format.TextFiles.StaticServer;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.util.Ipv4;

class OtherFilesTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final UInt128 K1 = UInt128.parseHex("0102030405060708090A0B0C0D0E0F10");
    private static final UInt128 K2 = UInt128.parseHex("F0E0D0C0B0A090807060504030201000");

    @Test
    void kadIndexesRoundTrip() throws DecodeException {
        LoadIndex load = new LoadIndex(1_700_000_000L, List.of(new Load(K1, 1_700_000_100L)));
        SourceIndex sources =
                new SourceIndex(
                        2,
                        1_700_018_000L,
                        List.of(
                                new IndexKey(
                                        K1,
                                        List.of(
                                                new Source(
                                                        K2,
                                                        List.of(
                                                                new Entry(
                                                                        1_700_001_000L,
                                                                        List.of(
                                                                                Tag.of(
                                                                                        FileTag
                                                                                                .SOURCEPORT,
                                                                                        4662)))))))));
        Tracking tracking =
                new Tracking(
                        List.of(Hash20.of(new byte[20])),
                        List.of(new PublishedName("Città.mkv", 3)),
                        List.of(
                                new Publisher(Ipv4.of(1, 2, 3, 4), 1_700_000_500L, 0),
                                new Publisher(Ipv4.of(5, 6, 7, 8), 1_700_000_600L, 0xFFFF)));
        KeywordIndex keywords =
                new KeywordIndex(
                        4,
                        1_700_086_400L,
                        K2,
                        List.of(
                                new Keyword(
                                        K1,
                                        List.of(
                                                new KeywordSource(
                                                        K2,
                                                        List.of(
                                                                new KeywordEntry(
                                                                        1_700_002_000L,
                                                                        tracking,
                                                                        List.of(
                                                                                Tag.of(
                                                                                        FileTag
                                                                                                .FILENAME,
                                                                                        "a.mkv")))))))));

        assertThat(KadIndexFiles.readLoad(KadIndexFiles.writeLoad(load))).isEqualTo(load);
        assertThat(KadIndexFiles.readSources(KadIndexFiles.writeSources(sources)))
                .isEqualTo(sources);
        assertThat(KadIndexFiles.readKeywords(KadIndexFiles.writeKeywords(keywords)))
                .isEqualTo(keywords);
    }

    @Test
    void readsKeywordIndexVersion3WithoutAichHashes() throws DecodeException {
        String id = HEX.formatHex(K1.toWireBytes());
        byte[] file =
                HEX.parseHex(
                        "03000000"
                                + "10000000"
                                + id
                                + "01000000"
                                + id
                                + "01000000"
                                + id
                                + "01000000"
                                + "20000000" // lifetime
                                + "00000000" // names
                                + "01000000"
                                + "04030201"
                                + "30000000" // one publisher, no AICH index
                                + "00"); // no tags

        KeywordIndex index = KadIndexFiles.readKeywords(file);

        Tracking t =
                index.keywords().getFirst().sources().getFirst().entries().getFirst().tracking();
        assertThat(index.version()).isEqualTo(3);
        assertThat(t.aichHashes()).isEmpty();
        assertThat(t.publishers())
                .containsExactly(new Publisher(Ipv4.of(1, 2, 3, 4), 0x30, 0xFFFF));
    }

    @Test
    void storedSearchesAndSpamRoundTrip() throws DecodeException {
        Result result =
                new Result(
                        Hash16.parseHex("000102030405060708090A0B0C0D0E0F"),
                        Ipv4.toEd2k(Ipv4.of(1, 2, 3, 4)),
                        4662,
                        List.of(Tag.of(FileTag.FILENAME, "x.iso"), Tag.of(FileTag.FILESIZE, 123)));
        StoredSearch search =
                new StoredSearch(7, 3, false, "", "linux iso", "Pro", List.of(result));
        List<Tag> spam =
                List.of(
                        Tag.of(SearchFiles.SPAM_FULL_NAME, "fake.exe"),
                        Tag.of(SearchFiles.SPAM_SIZE, 1L << 33));

        assertThat(SearchFiles.readStored(SearchFiles.writeStored(List.of(search))))
                .containsExactly(search);
        assertThat(SearchFiles.readSpam(SearchFiles.writeSpam(spam))).isEqualTo(spam);
    }

    @Test
    void textFilesDetectUtf16AndAnsi() {
        byte[] utf16 = TextFiles.writeLines(List.of("C:\\Città", "D:\\x"));

        assertThat(HEX.formatHex(utf16, 0, 2)).isEqualTo("fffe");
        assertThat(TextFiles.lines(utf16)).containsExactly("C:\\Città", "D:\\x");
        assertThat(TextFiles.lines(new byte[] {'a', (byte) 0xE9, '\r', '\n', 'b'}))
                .containsExactly("aé", "b");
    }

    @Test
    void staticServersFollowTheirLineFormat() {
        byte[] file =
                TextFiles.writeLines(
                        List.of(
                                "# comment",
                                "server.example.org:4661,0,Normal one",
                                "1.2.3.4:4242,5,Bad priority",
                                "5.6.7.8:4243,Name, with comma",
                                "nohostport"));

        assertThat(TextFiles.staticServers(file))
                .containsExactly(
                        new StaticServer(
                                "server.example.org",
                                4661,
                                TextFiles.PRIORITY_NORMAL,
                                "Normal one"),
                        new StaticServer("1.2.3.4", 4242, TextFiles.PRIORITY_HIGH, "Bad priority"),
                        new StaticServer(
                                "5.6.7.8", 4243, TextFiles.PRIORITY_HIGH, "Name, with comma"));
        List<StaticServer> servers = List.of(new StaticServer("a.org", 1, 2, "x"));
        assertThat(TextFiles.staticServers(TextFiles.writeStaticServers(servers)))
                .isEqualTo(servers);
    }

    @Test
    void sharedFilesMarkExclusions() {
        assertThat(
                        TextFiles.sharedFiles(
                                TextFiles.writeLines(List.of("C:\\a.txt", "-C:\\b.txt", ""))))
                .containsExactly(
                        new SharedFile("C:\\a.txt", false), new SharedFile("C:\\b.txt", true));
    }

    @Test
    void ipFilterDatLinesWithLevelsAndDescriptions() {
        byte[] file =
                """
                # comment
                001.002.003.000 - 001.002.003.255 , 000 , Bad people
                10.0.0.0 - 10.0.0.255
                10.0.1.0 - 10.0.1.255 , 100 , adjacent, same level
                10.0.2.0 - 10.0.2.255 , 050 , adjacent, other level
                1.2.3.0 - 1.2.3.255 , 020 , identical
                """
                        .getBytes(StandardCharsets.US_ASCII);

        List<Range> ranges = IpFilterDat.read(file, "ipfilter.dat");

        assertThat(ranges)
                .containsExactly(
                        new Range(Ipv4.of(1, 2, 3, 0), Ipv4.of(1, 2, 3, 255), 0, "Bad people"),
                        new Range(Ipv4.of(10, 0, 0, 0), Ipv4.of(10, 0, 1, 255), 100, ""),
                        new Range(
                                Ipv4.of(10, 0, 2, 0),
                                Ipv4.of(10, 0, 2, 255),
                                50,
                                "adjacent, other level"));
        assertThat(ranges.getFirst().contains(Ipv4.of(1, 2, 3, 77))).isTrue();
        assertThat(IpFilterDat.read(IpFilterDat.write(ranges), "ipfilter.dat")).isEqualTo(ranges);
    }

    @Test
    void ipFilterPeerGuardianFormats() {
        byte[] text =
                "<b>Some list</b>Evil Corp PGIPDB:3.0.0.0-3.255.255.255\n"
                        .getBytes(StandardCharsets.US_ASCII);
        byte[] p2b = HEX.parseHex("ffffffff503242" + "02" + "4e616d6500" + "05000000" + "050000ff");

        assertThat(IpFilterDat.read(text, "list.txt"))
                .containsExactly(
                        new Range(
                                Ipv4.of(3, 0, 0, 0), Ipv4.of(3, 255, 255, 255), 100, "Evil Corp"));
        assertThat(IpFilterDat.read(p2b, "list.p2b"))
                .containsExactly(
                        new Range(Ipv4.of(5, 0, 0, 0), Ipv4.of(5, 0, 0, 255), 100, "Name"));
    }

    @Test
    void iniFilesAreCaseInsensitiveAndDecodeAnsi() {
        byte[] file =
                """
                Global=1
                [eMule]
                Nick = someone
                Port=4662
                port=1
                ; comment
                [Proxy]
                Name=caf\u00e9
                """
                        .getBytes(StandardCharsets.UTF_8);
        byte[] ansi = new byte[] {'[', 'x', ']', '\n', 'k', '=', (byte) 0xE9};

        IniFile ini = IniFile.parse(file);

        assertThat(ini.get(IniFile.GLOBAL, "global")).contains("1");
        assertThat(ini.get("EMULE", "nick")).contains("someone");
        assertThat(ini.getLong("eMule", "Port")).hasValue(4662);
        assertThat(ini.getBoolean("eMule", "Port")).contains(true);
        assertThat(ini.get("proxy", "name")).contains("café");
        assertThat(ini.sections()).containsExactly("eMule", "Proxy");
        assertThat(IniFile.parse(ansi).get("x", "k")).contains("é");
    }

    @Property
    void textParsersNeverThrowAndBinaryOnesOnlyFailCleanly(@ForAll @Size(max = 200) byte[] data) {
        TextFiles.lines(data);
        TextFiles.staticServers(data);
        TextFiles.sharedFiles(data);
        IpFilterDat.read(data, "ipfilter.dat");
        IniFile.parse(data);
        try {
            KadIndexFiles.readKeywords(data);
        } catch (DecodeException expected) {
            // malformed
        }
        try {
            KadIndexFiles.readSources(data);
        } catch (DecodeException expected) {
            // malformed
        }
        try {
            SearchFiles.readStored(data);
        } catch (DecodeException expected) {
            // malformed
        }
    }
}
