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

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final String IP_FILTER_DAT = """
            # comment
            001.002.003.000 - 001.002.003.255 , 000 , Bad people
            10.0.0.0 - 10.0.0.255
            10.0.1.0 - 10.0.1.255 , 100 , adjacent, same level
            10.0.2.0 - 10.0.2.255 , 050 , adjacent, other level
            1.2.3.0 - 1.2.3.255 , 020 , identical
            """;

    private byte[] file;
    private List<String> lines;
    private List<String> linesRead;
    private List<StaticServer> staticServers;
    private List<StaticServer> staticServersRead;
    private List<SharedFile> sharedFilesRead;
    private List<Range> ranges;
    private List<Range> rangesRead;
    private IniFile ini;
    private LoadIndex loadIndex;
    private LoadIndex loadIndexRead;
    private SourceIndex sourceIndex;
    private SourceIndex sourceIndexRead;
    private KeywordIndex keywordIndex;
    private KeywordIndex keywordIndexRead;
    private List<StoredSearch> storedSearches;
    private List<StoredSearch> storedSearchesRead;
    private List<Tag> spam;
    private List<Tag> spamRead;
    private Map<String, Throwable> textFailures;
    private Map<String, Throwable> binaryFailures;

    @Test
    void shouldReadBackTheLoadIndexItWrites() throws DecodeException {
        givenALoadIndex();
        givenTheLoadIndexWritten();

        whenTheLoadIndexIsRead();

        thenTheLoadIndexReadIsTheOneWritten();
    }

    @Test
    void shouldReadBackTheSourceIndexItWrites() throws DecodeException {
        givenASourceIndex();
        givenTheSourceIndexWritten();

        whenTheSourceIndexIsRead();

        thenTheSourceIndexReadIsTheOneWritten();
    }

    @Test
    void shouldReadBackTheKeywordIndexItWrites() throws DecodeException {
        givenAKeywordIndexWithItsTracking();
        givenTheKeywordIndexWritten();

        whenTheKeywordIndexIsRead();

        thenTheKeywordIndexReadIsTheOneWritten();
    }

    @Test
    void shouldReadAKeywordIndexOfVersion3WithoutAichHashes() throws DecodeException {
        givenAKeywordIndexOfVersion3WithOnePublisherAndNoAichIndex();

        whenTheKeywordIndexIsRead();

        thenTheVersionOfTheKeywordIndexReadIs(3);
        thenTheFirstTrackingHasNoAichHashes();
        thenTheFirstTrackingHasThePublishers(new Publisher(Ipv4.of(1, 2, 3, 4), 0x30, 0xFFFF));
    }

    @Test
    void shouldReadBackTheStoredSearchesItWrites() throws DecodeException {
        givenAStoredSearchWithOneResult();
        givenTheStoredSearchesWritten();

        whenTheStoredSearchesAreRead();

        thenTheStoredSearchesReadAreTheOnesWritten();
    }

    @Test
    void shouldReadBackTheSpamItWrites() throws DecodeException {
        givenTheSpamTags(Tag.of(SearchFiles.SPAM_FULL_NAME, "fake.exe"), Tag.of(SearchFiles.SPAM_SIZE, 1L << 33));
        givenTheSpamWritten();

        whenTheSpamIsRead();

        thenTheSpamReadIsTheOneWritten();
    }

    @Test
    void shouldWriteTextFilesInUtf16() {
        givenTheLines("C:\\Città", "D:\\x");

        whenTheLinesAreWritten();

        thenTheFileStartsWithTheHex("fffe");
    }

    @Test
    void shouldReadBackTheUtf16LinesItWrites() {
        givenATextFileWithTheLines("C:\\Città", "D:\\x");

        whenTheLinesAreRead();

        thenTheLinesReadAre("C:\\Città", "D:\\x");
    }

    @Test
    void shouldReadAnsiLines() {
        givenTheFile(new byte[] {'a', (byte) 0xE9, '\r', '\n', 'b'});

        whenTheLinesAreRead();

        thenTheLinesReadAre("aé", "b");
    }

    @Test
    void shouldReadStaticServersInTheirLineFormat() {
        givenATextFileWithTheLines(
                "# comment",
                "server.example.org:4661,0,Normal one",
                "1.2.3.4:4242,5,Bad priority",
                "5.6.7.8:4243,Name, with comma",
                "nohostport");

        whenTheStaticServersAreRead();

        thenTheStaticServersReadAre(
                new StaticServer("server.example.org", 4661, TextFiles.PRIORITY_NORMAL, "Normal one"),
                new StaticServer("1.2.3.4", 4242, TextFiles.PRIORITY_HIGH, "Bad priority"),
                new StaticServer("5.6.7.8", 4243, TextFiles.PRIORITY_HIGH, "Name, with comma"));
    }

    @Test
    void shouldReadBackTheStaticServersItWrites() {
        givenTheStaticServers(new StaticServer("a.org", 1, 2, "x"));
        givenTheStaticServersWritten();

        whenTheStaticServersAreRead();

        thenTheStaticServersReadAreTheOnesWritten();
    }

    @Test
    void shouldMarkTheExclusionsOfTheSharedFiles() {
        givenATextFileWithTheLines("C:\\a.txt", "-C:\\b.txt", "");

        whenTheSharedFilesAreRead();

        thenTheSharedFilesReadAre(new SharedFile("C:\\a.txt", false), new SharedFile("C:\\b.txt", true));
    }

    @Test
    void shouldReadIpFilterLinesWithLevelsAndDescriptions() {
        givenTheAsciiFile(IP_FILTER_DAT);

        whenTheIpFilterIsRead("ipfilter.dat");

        thenTheRangesReadAre(
                new Range(Ipv4.of(1, 2, 3, 0), Ipv4.of(1, 2, 3, 255), 0, "Bad people"),
                new Range(Ipv4.of(10, 0, 0, 0), Ipv4.of(10, 0, 1, 255), 100, ""),
                new Range(Ipv4.of(10, 0, 2, 0), Ipv4.of(10, 0, 2, 255), 50, "adjacent, other level"));
        thenTheFirstRangeReadContains(Ipv4.of(1, 2, 3, 77));
    }

    @Test
    void shouldReadBackTheIpFilterItWrites() {
        givenTheAsciiFile(IP_FILTER_DAT);
        givenTheRangesOfTheIpFilter("ipfilter.dat");
        givenTheRangesWritten();

        whenTheIpFilterIsRead("ipfilter.dat");

        thenTheRangesReadAreTheOnesWritten();
    }

    @Test
    void shouldReadAPeerGuardianTextList() {
        givenTheAsciiFile("<b>Some list</b>Evil Corp PGIPDB:3.0.0.0-3.255.255.255\n");

        whenTheIpFilterIsRead("list.txt");

        thenTheRangesReadAre(new Range(Ipv4.of(3, 0, 0, 0), Ipv4.of(3, 255, 255, 255), 100, "Evil Corp"));
    }

    @Test
    void shouldReadAPeerGuardianBinaryList() {
        givenTheHexFile("ffffffff503242" + "02" + "4e616d6500" + "05000000" + "050000ff");

        whenTheIpFilterIsRead("list.p2b");

        thenTheRangesReadAre(new Range(Ipv4.of(5, 0, 0, 0), Ipv4.of(5, 0, 0, 255), 100, "Name"));
    }

    @Test
    void shouldReadIniFilesCaseInsensitively() {
        givenTheUtf8File("""
                Global=1
                [eMule]
                Nick = someone
                Port=4662
                port=1
                ; comment
                [Proxy]
                Name=caf\u00e9
                """);

        whenTheIniFileIsParsed();

        thenTheIniValueIs(IniFile.GLOBAL, "global", "1");
        thenTheIniValueIs("EMULE", "nick", "someone");
        thenTheIniNumberIs("eMule", "Port", 4662);
        thenTheIniFlagIs("eMule", "Port", true);
        thenTheIniValueIs("proxy", "name", "café");
        thenTheIniSectionsAre("eMule", "Proxy");
    }

    @Test
    void shouldDecodeAnsiIniFiles() {
        givenTheFile(new byte[] {'[', 'x', ']', '\n', 'k', '=', (byte) 0xE9});

        whenTheIniFileIsParsed();

        thenTheIniValueIs("x", "k", "é");
    }

    @Property
    void shouldNeverThrowFromTextParsersAndOnlyFailCleanlyFromBinaryOnes(@ForAll @Size(max = 200) byte[] data) {
        whenEveryParserReadsTheGarbage(data);

        thenTheTextParsersThrewNothing();
        thenTheBinaryParsersOnlyFailedWithDecodeExceptions();
    }

    private void givenTheFile(byte[] content) {
        this.file = content;
    }

    private void givenTheHexFile(String hex) {
        this.file = HEX.parseHex(hex);
    }

    private void givenTheAsciiFile(String text) {
        this.file = text.getBytes(StandardCharsets.US_ASCII);
    }

    private void givenTheUtf8File(String text) {
        this.file = text.getBytes(StandardCharsets.UTF_8);
    }

    private void givenTheLines(String... written) {
        this.lines = List.of(written);
    }

    private void givenATextFileWithTheLines(String... written) {
        this.file = TextFiles.writeLines(List.of(written));
    }

    private void givenTheStaticServers(StaticServer... written) {
        this.staticServers = List.of(written);
    }

    private void givenTheStaticServersWritten() {
        this.file = TextFiles.writeStaticServers(this.staticServers);
    }

    private void givenTheRangesOfTheIpFilter(String name) {
        this.ranges = IpFilterDat.read(this.file, name);
    }

    private void givenTheRangesWritten() {
        this.file = IpFilterDat.write(this.ranges);
    }

    private void givenALoadIndex() {
        this.loadIndex = new LoadIndex(1_700_000_000L, List.of(new Load(K1, 1_700_000_100L)));
    }

    private void givenTheLoadIndexWritten() {
        this.file = KadIndexFiles.writeLoad(this.loadIndex);
    }

    private void givenASourceIndex() {
        this.sourceIndex = new SourceIndex(
                2,
                1_700_018_000L,
                List.of(new IndexKey(
                        K1,
                        List.of(new Source(
                                K2, List.of(new Entry(1_700_001_000L, List.of(Tag.of(FileTag.SOURCEPORT, 4662)))))))));
    }

    private void givenTheSourceIndexWritten() {
        this.file = KadIndexFiles.writeSources(this.sourceIndex);
    }

    private void givenAKeywordIndexWithItsTracking() {
        Tracking tracking = new Tracking(
                List.of(Hash20.of(new byte[20])),
                List.of(new PublishedName("Città.mkv", 3)),
                List.of(
                        new Publisher(Ipv4.of(1, 2, 3, 4), 1_700_000_500L, 0),
                        new Publisher(Ipv4.of(5, 6, 7, 8), 1_700_000_600L, 0xFFFF)));
        this.keywordIndex = new KeywordIndex(
                4,
                1_700_086_400L,
                K2,
                List.of(new Keyword(
                        K1,
                        List.of(new KeywordSource(
                                K2,
                                List.of(new KeywordEntry(
                                        1_700_002_000L, tracking, List.of(Tag.of(FileTag.FILENAME, "a.mkv")))))))));
    }

    private void givenTheKeywordIndexWritten() {
        this.file = KadIndexFiles.writeKeywords(this.keywordIndex);
    }

    private void givenAKeywordIndexOfVersion3WithOnePublisherAndNoAichIndex() {
        String id = HEX.formatHex(K1.toWireBytes());
        this.file = HEX.parseHex("03000000"
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
    }

    private void givenAStoredSearchWithOneResult() {
        Result result = new Result(
                Hash16.parseHex("000102030405060708090A0B0C0D0E0F"),
                Ipv4.toEd2k(Ipv4.of(1, 2, 3, 4)),
                4662,
                List.of(Tag.of(FileTag.FILENAME, "x.iso"), Tag.of(FileTag.FILESIZE, 123)));
        this.storedSearches = List.of(new StoredSearch(7, 3, false, "", "linux iso", "Pro", List.of(result)));
    }

    private void givenTheStoredSearchesWritten() {
        this.file = SearchFiles.writeStored(this.storedSearches);
    }

    private void givenTheSpamTags(Tag... tags) {
        this.spam = List.of(tags);
    }

    private void givenTheSpamWritten() {
        this.file = SearchFiles.writeSpam(this.spam);
    }

    private void whenTheLinesAreWritten() {
        this.file = TextFiles.writeLines(this.lines);
    }

    private void whenTheLinesAreRead() {
        this.linesRead = TextFiles.lines(this.file);
    }

    private void whenTheStaticServersAreRead() {
        this.staticServersRead = TextFiles.staticServers(this.file);
    }

    private void whenTheSharedFilesAreRead() {
        this.sharedFilesRead = TextFiles.sharedFiles(this.file);
    }

    private void whenTheIpFilterIsRead(String name) {
        this.rangesRead = IpFilterDat.read(this.file, name);
    }

    private void whenTheIniFileIsParsed() {
        this.ini = IniFile.parse(this.file);
    }

    private void whenTheLoadIndexIsRead() throws DecodeException {
        this.loadIndexRead = KadIndexFiles.readLoad(this.file);
    }

    private void whenTheSourceIndexIsRead() throws DecodeException {
        this.sourceIndexRead = KadIndexFiles.readSources(this.file);
    }

    private void whenTheKeywordIndexIsRead() throws DecodeException {
        this.keywordIndexRead = KadIndexFiles.readKeywords(this.file);
    }

    private void whenTheStoredSearchesAreRead() throws DecodeException {
        this.storedSearchesRead = SearchFiles.readStored(this.file);
    }

    private void whenTheSpamIsRead() throws DecodeException {
        this.spamRead = SearchFiles.readSpam(this.file);
    }

    private void whenEveryParserReadsTheGarbage(byte[] data) {
        this.textFailures = new LinkedHashMap<>();
        this.textFailures.put("lines", catchThrowable(() -> TextFiles.lines(data)));
        this.textFailures.put("static servers", catchThrowable(() -> TextFiles.staticServers(data)));
        this.textFailures.put("shared files", catchThrowable(() -> TextFiles.sharedFiles(data)));
        this.textFailures.put("ip filter", catchThrowable(() -> IpFilterDat.read(data, "ipfilter.dat")));
        this.textFailures.put("ini", catchThrowable(() -> IniFile.parse(data)));
        this.binaryFailures = new LinkedHashMap<>();
        this.binaryFailures.put("keyword index", catchThrowable(() -> KadIndexFiles.readKeywords(data)));
        this.binaryFailures.put("source index", catchThrowable(() -> KadIndexFiles.readSources(data)));
        this.binaryFailures.put("stored searches", catchThrowable(() -> SearchFiles.readStored(data)));
    }

    private void thenTheFileStartsWithTheHex(String hex) {
        assertThat(HEX.formatHex(this.file, 0, hex.length() / 2)).isEqualTo(hex);
    }

    private void thenTheLinesReadAre(String... expected) {
        assertThat(this.linesRead).containsExactly(expected);
    }

    private void thenTheStaticServersReadAre(StaticServer... expected) {
        assertThat(this.staticServersRead).containsExactly(expected);
    }

    private void thenTheStaticServersReadAreTheOnesWritten() {
        assertThat(this.staticServersRead).isEqualTo(this.staticServers);
    }

    private void thenTheSharedFilesReadAre(SharedFile... expected) {
        assertThat(this.sharedFilesRead).containsExactly(expected);
    }

    private void thenTheRangesReadAre(Range... expected) {
        assertThat(this.rangesRead).containsExactly(expected);
    }

    private void thenTheFirstRangeReadContains(int ip) {
        assertThat(this.rangesRead.getFirst().contains(ip)).isTrue();
    }

    private void thenTheRangesReadAreTheOnesWritten() {
        assertThat(this.rangesRead).isEqualTo(this.ranges);
    }

    private void thenTheIniValueIs(String section, String key, String value) {
        assertThat(this.ini.get(section, key)).contains(value);
    }

    private void thenTheIniNumberIs(String section, String key, long value) {
        assertThat(this.ini.getLong(section, key)).hasValue(value);
    }

    private void thenTheIniFlagIs(String section, String key, boolean value) {
        assertThat(this.ini.getBoolean(section, key)).contains(value);
    }

    private void thenTheIniSectionsAre(String... sections) {
        assertThat(this.ini.sections()).containsExactly(sections);
    }

    private void thenTheLoadIndexReadIsTheOneWritten() {
        assertThat(this.loadIndexRead).isEqualTo(this.loadIndex);
    }

    private void thenTheSourceIndexReadIsTheOneWritten() {
        assertThat(this.sourceIndexRead).isEqualTo(this.sourceIndex);
    }

    private void thenTheKeywordIndexReadIsTheOneWritten() {
        assertThat(this.keywordIndexRead).isEqualTo(this.keywordIndex);
    }

    private void thenTheVersionOfTheKeywordIndexReadIs(int version) {
        assertThat(this.keywordIndexRead.version()).isEqualTo(version);
    }

    private void thenTheFirstTrackingHasNoAichHashes() {
        assertThat(firstTracking().aichHashes()).isEmpty();
    }

    private void thenTheFirstTrackingHasThePublishers(Publisher... publishers) {
        assertThat(firstTracking().publishers()).containsExactly(publishers);
    }

    private void thenTheStoredSearchesReadAreTheOnesWritten() {
        assertThat(this.storedSearchesRead).containsExactlyElementsOf(this.storedSearches);
    }

    private void thenTheSpamReadIsTheOneWritten() {
        assertThat(this.spamRead).isEqualTo(this.spam);
    }

    private void thenTheTextParsersThrewNothing() {
        this.textFailures.forEach(
                (parser, failure) -> assertThat(failure).as(parser).isNull());
    }

    private void thenTheBinaryParsersOnlyFailedWithDecodeExceptions() {
        this.binaryFailures.forEach((parser, failure) -> {
            if (failure != null) {
                assertThat(failure).as(parser).isInstanceOf(DecodeException.class);
            }
        });
    }

    private Tracking firstTracking() {
        return this.keywordIndexRead
                .keywords()
                .getFirst()
                .sources()
                .getFirst()
                .entries()
                .getFirst()
                .tracking();
    }
}
