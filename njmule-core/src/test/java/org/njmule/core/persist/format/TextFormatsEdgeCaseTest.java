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

    @Test
    void linesEndInEveryStyle() {
        assertThat(TextFiles.lines("a\r\nb\rc\nd".getBytes(US_ASCII))).containsExactly("a", "b", "c", "d");
        assertThat(TextFiles.lines("a\n\nb\n".getBytes(US_ASCII))).containsExactly("a", "", "b");
        assertThat(TextFiles.lines(new byte[0])).isEmpty();
        assertThat(TextFiles.lines(HEX.parseHex("efbbbf78"))).containsExactly("x");
        assertThat(TextFiles.lines(HEX.parseHex("fffe"))).isEmpty();
        assertThat(TextFiles.lines(HEX.parseHex("fffe610062")))
                .singleElement()
                .asString()
                .startsWith("a");
    }

    @Property
    void writtenLinesReadBack(@ForAll("lines") List<String> lines) {
        assertThat(TextFiles.lines(TextFiles.writeLines(lines))).isEqualTo(lines);
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
    void staticServersWithoutAUsablePortAreLeftOut() {
        byte[] file =
                TextFiles.writeLines(List.of("a.org:0,1,zero", "b.org:65536,1,big", "c.org:65535,7,odd priority"));

        assertThat(TextFiles.staticServers(file))
                .containsExactly(new StaticServer("c.org", 65535, TextFiles.PRIORITY_HIGH, "odd priority"));
    }

    @Property
    void staticServersReadBack(@ForAll("staticServers") List<StaticServer> servers) {
        assertThat(TextFiles.staticServers(TextFiles.writeStaticServers(servers)))
                .isEqualTo(servers);
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
    void iniValuesAsTheReferenceReaderTakesThem() {
        byte[] file = String.join(
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
                        "")
                .getBytes(US_ASCII);

        IniFile ini = IniFile.parse(file);

        assertThat(ini.get(IniFile.GLOBAL, "key")).contains("value");
        assertThat(ini.get(IniFile.GLOBAL, "a")).contains("b=c");
        assertThat(ini.get(IniFile.GLOBAL, "k")).contains("1");
        assertThat(ini.get("eMule", "Empty")).contains("");
        assertThat(ini.getLong("eMule", "Plus")).hasValue(5);
        assertThat(ini.getLong("eMule", "Hex")).isEmpty();
        assertThat(ini.getLong("eMule", "Huge")).isEmpty();
        assertThat(IniFile.parse("﻿[x]\r\nk=é\r\n".getBytes(UTF_16LE)).get("x", "k"))
                .contains("é");
    }

    @Test
    void ipFilterLinesInTheirManySpellings() {
        byte[] file = """
                1.2.3.0-1.2.3.255,050,tight
                4.0.0.0\t-\t4.0.0.255\t,\t60\t,\ttabs
                010.000.000.008 - 010.000.000.009 , 0 , not octal
                256.0.0.0 - 256.0.0.1 , 0 , bad octet
                1000.0.0.0 - 1000.0.0.1 , 0 , four digits
                // comment 9.9.9.9 - 9.9.9.9
                255.255.255.0 - 255.255.255.255 , 7 , last
                """.getBytes(US_ASCII);

        assertThat(IpFilterDat.read(file, "ipfilter.dat"))
                .containsExactly(
                        new Range(Ipv4.of(1, 2, 3, 0), Ipv4.of(1, 2, 3, 255), 50, "tight"),
                        new Range(Ipv4.of(4, 0, 0, 0), Ipv4.of(4, 0, 0, 255), 60, "tabs"),
                        new Range(Ipv4.of(10, 0, 0, 8), Ipv4.of(10, 0, 0, 9), 0, "not octal"),
                        new Range(Ipv4.of(255, 255, 255, 0), Ipv4.of(255, 255, 255, 255), 7, "last"));
    }

    @Test
    void peerGuardianListsInEveryForm() {
        byte[] text = "Some: evil: corp:1.2.3.0-1.2.3.255\n".getBytes(UTF_8);
        byte[] latin1 = HEX.parseHex("ffffffff503242" + "01" + "e900" + "05000000" + "050000ff");
        byte[] truncated = HEX.parseHex("ffffffff503242" + "02" + "4100" + "05000000" + "050000ff" + "4200" + "0600");
        byte[] version3 = HEX.parseHex("ffffffff503242" + "03" + "4100" + "05000000" + "050000ff");

        assertThat(IpFilterDat.read(text, "guarding.p2p"))
                .containsExactly(new Range(Ipv4.of(1, 2, 3, 0), Ipv4.of(1, 2, 3, 255), 100, "Some: evil: corp"));
        assertThat(IpFilterDat.read(latin1, "x.p2b"))
                .extracting(Range::description)
                .containsExactly("é");
        assertThat(IpFilterDat.read(truncated, "x.p2b"))
                .extracting(Range::description)
                .containsExactly("A");
        assertThat(IpFilterDat.read(version3, "x.p2b")).isEmpty();
    }

    @Property
    void ipFilterRangesReadBack(@ForAll("ranges") List<Range> ranges) {
        assertThat(IpFilterDat.read(IpFilterDat.write(ranges), "ipfilter.dat")).isEqualTo(ranges);
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
    void aSectionLineMayEndWithAComment() {
        IniFile ini =
                IniFile.parse("[eMule]\nNick=a\n[Proxy] ;the proxy\nProxyName=h\n[a]b]\nk=v\n".getBytes(US_ASCII));

        assertThat(ini.get("Proxy", "ProxyName")).contains("h");
        assertThat(ini.get("eMule", "ProxyName")).isEmpty();
        assertThat(ini.get("a]b", "k")).contains("v");
        assertThat(ini.sections()).containsExactly("eMule", "Proxy", "a]b");
    }

    @Test
    void aLevelOfMoreThanThreeDigitsUsesTheFirstThree() {
        byte[] file = "001.002.003.000 - 001.002.003.255 , 0127 , Long level\n".getBytes(US_ASCII);

        assertThat(IpFilterDat.read(file, "ipfilter.dat"))
                .containsExactly(new Range(Ipv4.of(1, 2, 3, 0), Ipv4.of(1, 2, 3, 255), 12, "Long level"));
    }
}
