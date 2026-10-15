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

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_16LE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;
import org.njmule.core.persist.format.IpFilterDat.Range;
import org.njmule.core.persist.format.TextFiles.StaticServer;
import org.njmule.core.util.Ipv4;

/** Corner cases of the text formats of an eMule profile. */
class TextFormatsEdgeCaseTest {

    private static final HexFormat HEX = HexFormat.of();

    private byte[] file;
    private List<String> lines;
    private List<String> linesRead;
    private List<StaticServer> staticServers;
    private List<StaticServer> staticServersRead;
    private List<Range> ranges;
    private List<Range> rangesRead;
    private IniFile ini;

    @Test
    void shouldEndTheLinesInEveryStyle() {
        givenTheAsciiFile("a\r\nb\rc\nd");

        whenTheLinesAreRead();

        thenTheLinesReadAre("a", "b", "c", "d");
    }

    @Test
    void shouldKeepTheEmptyLinesBeforeTheLastLineEnd() {
        givenTheAsciiFile("a\n\nb\n");

        whenTheLinesAreRead();

        thenTheLinesReadAre("a", "", "b");
    }

    @Test
    void shouldReadNoLinesFromAnEmptyFile() {
        givenTheFile(new byte[0]);

        whenTheLinesAreRead();

        thenNoLinesAreRead();
    }

    @Test
    void shouldSkipTheUtf8ByteOrderMark() {
        givenTheHexFile("efbbbf78");

        whenTheLinesAreRead();

        thenTheLinesReadAre("x");
    }

    @Test
    void shouldReadNoLinesFromAUtf16ByteOrderMarkAlone() {
        givenTheHexFile("fffe");

        whenTheLinesAreRead();

        thenNoLinesAreRead();
    }

    @Test
    void shouldReadAUtf16FileOfAnOddLength() {
        givenTheHexFile("fffe610062");

        whenTheLinesAreRead();

        thenTheOnlyLineReadStartsWith("a");
    }

    @Property
    void shouldReadBackTheLinesItWrites(@ForAll("lines") List<String> written) {
        givenTheLines(written);
        givenTheLinesWritten();

        whenTheLinesAreRead();

        thenTheLinesReadAreTheOnesWritten();
    }

    @Provide
    Arbitrary<List<String>> lines() {
        return text().list().ofMaxSize(10);
    }

    // Text of one line: no line ends, and no lone surrogate, which no encoding keeps.
    private static Arbitrary<String> text() {
        return Arbitraries.strings()
                .withCharRange('\u0000', '퟿')
                .withCharRange('', '�')
                .excludeChars('\r', '\n')
                .ofMaxLength(30);
    }

    @Test
    void shouldLeaveOutTheStaticServersWithoutAUsablePort() {
        givenATextFileWithTheLines("a.org:0,1,zero", "b.org:65536,1,big", "c.org:65535,7,odd priority");

        whenTheStaticServersAreRead();

        thenTheStaticServersReadAre(new StaticServer("c.org", 65535, TextFiles.PRIORITY_HIGH, "odd priority"));
    }

    @Property
    void shouldReadBackTheStaticServersItWrites(@ForAll("staticServers") List<StaticServer> servers) {
        givenTheStaticServers(servers);
        givenTheStaticServersWritten();

        whenTheStaticServersAreRead();

        thenTheStaticServersReadAreTheOnesWritten();
    }

    @Provide
    Arbitrary<List<StaticServer>> staticServers() {
        Arbitrary<String> host = Arbitraries.strings()
                .withCharRange('a', 'z')
                .numeric()
                .withChars('.', '-')
                .ofMinLength(1)
                .ofMaxLength(20);
        return Combinators.combine(
                        host,
                        Arbitraries.integers().between(1, 0xFFFF),
                        Arbitraries.integers().between(TextFiles.PRIORITY_NORMAL, TextFiles.PRIORITY_LOW),
                        text())
                .as(StaticServer::new)
                .list()
                .ofMaxSize(5);
    }

    @Test
    void shouldTakeTheIniValuesAsTheReferenceReaderDoes() {
        givenTheAsciiFile(String.join(
                "\r\n",
                "Key = value",
                "a=b=c",
                "k=1",
                "k=2",
                "[ eMule ]",
                "[]",
                "Empty=",
                "Plus=+5",
                "Hex=0x10",
                "Huge=99999999999999999999",
                ""));

        whenTheIniFileIsParsed();

        thenTheIniValueIs(IniFile.GLOBAL, "key", "value");
        thenTheIniValueIs(IniFile.GLOBAL, "a", "b=c");
        thenTheIniValueIs(IniFile.GLOBAL, "k", "1");
        thenTheIniValueIs("eMule", "Empty", "");
        thenTheIniNumberIs("eMule", "Plus", 5);
        thenThereIsNoIniNumber("eMule", "Hex");
        thenThereIsNoIniNumber("eMule", "Huge");
    }

    @Test
    void shouldReadAUtf16IniFile() {
        givenTheUtf16LeFile("﻿[x]\r\nk=é\r\n");

        whenTheIniFileIsParsed();

        thenTheIniValueIs("x", "k", "é");
    }

    @Test
    void shouldReadIpFilterLinesInTheirManySpellings() {
        givenTheAsciiFile("""
                1.2.3.0-1.2.3.255,050,tight
                4.0.0.0\t-\t4.0.0.255\t,\t60\t,\ttabs
                010.000.000.008 - 010.000.000.009 , 0 , not octal
                256.0.0.0 - 256.0.0.1 , 0 , bad octet
                1000.0.0.0 - 1000.0.0.1 , 0 , four digits
                // comment 9.9.9.9 - 9.9.9.9
                255.255.255.0 - 255.255.255.255 , 7 , last
                """);

        whenTheIpFilterIsRead("ipfilter.dat");

        thenTheRangesReadAre(
                new Range(Ipv4.of(1, 2, 3, 0), Ipv4.of(1, 2, 3, 255), 50, "tight"),
                new Range(Ipv4.of(4, 0, 0, 0), Ipv4.of(4, 0, 0, 255), 60, "tabs"),
                new Range(Ipv4.of(10, 0, 0, 8), Ipv4.of(10, 0, 0, 9), 0, "not octal"),
                new Range(Ipv4.of(255, 255, 255, 0), Ipv4.of(255, 255, 255, 255), 7, "last"));
    }

    @Test
    void shouldReadAPeerGuardianTextListWithColonsInTheDescription() {
        givenTheUtf8File("Some: evil: corp:1.2.3.0-1.2.3.255\n");

        whenTheIpFilterIsRead("guarding.p2p");

        thenTheRangesReadAre(new Range(Ipv4.of(1, 2, 3, 0), Ipv4.of(1, 2, 3, 255), 100, "Some: evil: corp"));
    }

    @Test
    void shouldReadALatin1PeerGuardianBinaryList() {
        givenTheHexFile("ffffffff503242" + "01" + "e900" + "05000000" + "050000ff");

        whenTheIpFilterIsRead("x.p2b");

        thenTheDescriptionsReadAre("é");
    }

    @Test
    void shouldReadATruncatedPeerGuardianBinaryListUpToItsLastWholeRange() {
        givenTheHexFile("ffffffff503242" + "02" + "4100" + "05000000" + "050000ff" + "4200" + "0600");

        whenTheIpFilterIsRead("x.p2b");

        thenTheDescriptionsReadAre("A");
    }

    @Test
    void shouldIgnoreAnUnknownVersionOfPeerGuardianBinaryList() {
        givenTheHexFile("ffffffff503242" + "03" + "4100" + "05000000" + "050000ff");

        whenTheIpFilterIsRead("x.p2b");

        thenNoRangesAreRead();
    }

    @Property
    void shouldReadBackTheIpFilterRangesItWrites(@ForAll("ranges") List<Range> written) {
        givenTheRanges(written);
        givenTheRangesWritten();

        whenTheIpFilterIsRead("ipfilter.dat");

        thenTheRangesReadAreTheOnesWritten();
    }

    @Provide
    Arbitrary<List<Range>> ranges() {
        // Sorted, apart by at least one address, so that no two of them merge.
        Arbitrary<String> description = Arbitraries.strings()
                .alpha()
                .numeric()
                .withChars(' ', ',', '.', ':', '-')
                .ofMaxLength(40)
                .map(String::strip);
        return Combinators.combine(
                        Arbitraries.longs().between(0, 0xFFFF_FFFFL).set().ofMaxSize(20),
                        Arbitraries.integers().between(0, 255).list().ofSize(10),
                        description.list().ofSize(10))
                .as((points, levels, descriptions) -> {
                    List<Long> sorted = points.stream().sorted().toList();
                    List<Range> ranges = new ArrayList<>();
                    for (int i = 0; i + 1 < sorted.size(); i += 2) {
                        long start = sorted.get(i);
                        long end = sorted.get(i + 1);
                        if (!ranges.isEmpty()
                                && start
                                                - Integer.toUnsignedLong(
                                                        ranges.getLast().end())
                                        < 2) {
                            continue;
                        }
                        int n = ranges.size() % levels.size();
                        ranges.add(new Range((int) start, (int) end, levels.get(n), descriptions.get(n)));
                    }
                    return ranges;
                });
    }

    @Test
    void shouldLetASectionLineEndWithAComment() {
        givenTheAsciiFile("[eMule]\nNick=a\n[Proxy] ;the proxy\nProxyName=h\n[a]b]\nk=v\n");

        whenTheIniFileIsParsed();

        thenTheIniValueIs("Proxy", "ProxyName", "h");
        thenThereIsNoIniValue("eMule", "ProxyName");
        thenTheIniValueIs("a]b", "k", "v");
        thenTheIniSectionsAre("eMule", "Proxy", "a]b");
    }

    @Test
    void shouldUseTheFirstThreeDigitsOfALongerLevel() {
        givenTheAsciiFile("001.002.003.000 - 001.002.003.255 , 0127 , Long level\n");

        whenTheIpFilterIsRead("ipfilter.dat");

        thenTheRangesReadAre(new Range(Ipv4.of(1, 2, 3, 0), Ipv4.of(1, 2, 3, 255), 12, "Long level"));
    }

    private void givenTheFile(byte[] content) {
        this.file = content;
    }

    private void givenTheHexFile(String hex) {
        this.file = HEX.parseHex(hex);
    }

    private void givenTheAsciiFile(String text) {
        this.file = text.getBytes(US_ASCII);
    }

    private void givenTheUtf8File(String text) {
        this.file = text.getBytes(UTF_8);
    }

    private void givenTheUtf16LeFile(String text) {
        this.file = text.getBytes(UTF_16LE);
    }

    private void givenTheLines(List<String> written) {
        this.lines = written;
    }

    private void givenTheLinesWritten() {
        this.file = TextFiles.writeLines(this.lines);
    }

    private void givenATextFileWithTheLines(String... written) {
        this.file = TextFiles.writeLines(List.of(written));
    }

    private void givenTheStaticServers(List<StaticServer> written) {
        this.staticServers = written;
    }

    private void givenTheStaticServersWritten() {
        this.file = TextFiles.writeStaticServers(this.staticServers);
    }

    private void givenTheRanges(List<Range> written) {
        this.ranges = written;
    }

    private void givenTheRangesWritten() {
        this.file = IpFilterDat.write(this.ranges);
    }

    private void whenTheLinesAreRead() {
        this.linesRead = TextFiles.lines(this.file);
    }

    private void whenTheStaticServersAreRead() {
        this.staticServersRead = TextFiles.staticServers(this.file);
    }

    private void whenTheIpFilterIsRead(String name) {
        this.rangesRead = IpFilterDat.read(this.file, name);
    }

    private void whenTheIniFileIsParsed() {
        this.ini = IniFile.parse(this.file);
    }

    private void thenTheLinesReadAre(String... expected) {
        assertThat(this.linesRead).containsExactly(expected);
    }

    private void thenNoLinesAreRead() {
        assertThat(this.linesRead).isEmpty();
    }

    private void thenTheOnlyLineReadStartsWith(String start) {
        assertThat(this.linesRead).singleElement().asString().startsWith(start);
    }

    private void thenTheLinesReadAreTheOnesWritten() {
        assertThat(this.linesRead).isEqualTo(this.lines);
    }

    private void thenTheStaticServersReadAre(StaticServer... expected) {
        assertThat(this.staticServersRead).containsExactly(expected);
    }

    private void thenTheStaticServersReadAreTheOnesWritten() {
        assertThat(this.staticServersRead).isEqualTo(this.staticServers);
    }

    private void thenTheRangesReadAre(Range... expected) {
        assertThat(this.rangesRead).containsExactly(expected);
    }

    private void thenTheDescriptionsReadAre(String... descriptions) {
        assertThat(this.rangesRead).extracting(Range::description).containsExactly(descriptions);
    }

    private void thenNoRangesAreRead() {
        assertThat(this.rangesRead).isEmpty();
    }

    private void thenTheRangesReadAreTheOnesWritten() {
        assertThat(this.rangesRead).isEqualTo(this.ranges);
    }

    private void thenTheIniValueIs(String section, String key, String value) {
        assertThat(this.ini.get(section, key)).contains(value);
    }

    private void thenThereIsNoIniValue(String section, String key) {
        assertThat(this.ini.get(section, key)).isEmpty();
    }

    private void thenTheIniNumberIs(String section, String key, long value) {
        assertThat(this.ini.getLong(section, key)).hasValue(value);
    }

    private void thenThereIsNoIniNumber(String section, String key) {
        assertThat(this.ini.getLong(section, key)).isEmpty();
    }

    private void thenTheIniSectionsAre(String... sections) {
        assertThat(this.ini.sections()).containsExactly(sections);
    }
}
