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
package org.njmule.core.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.Ed2kLink.FileLink;
import org.njmule.core.api.Ed2kLink.Source;

class Ed2kLinkTest {

    private static final String HASH = "31D6CFE0D16AE931B73C59D7E0C089C0";
    private static final String AICH = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    @Test
    void parsesAPlainFileLink() {
        FileLink link = (FileLink) Ed2kLink.parse(" ed2k://|file|Ubuntu%2024.04.iso|5000000000|" + HASH + "|/ ");

        assertThat(link.name()).isEqualTo("Ubuntu 24.04.iso");
        assertThat(link.size()).isEqualTo(5_000_000_000L);
        assertThat(link.hash()).isEqualTo(Hash16.parseHex(HASH));
        assertThat(link.aichHash()).isEmpty();
        assertThat(link.sources()).isEmpty();
    }

    @Test
    void readsALinkWithTheSeparatorsABrowserEscaped() {
        FileLink link = (FileLink) Ed2kLink.parse("ed2k://%7Cfile%7CUbuntu%2024.04.iso%7C5000%7C" + HASH + "%7c/");

        assertThat(link.name()).isEqualTo("Ubuntu 24.04.iso");
        assertThat(link.size()).isEqualTo(5000);
    }

    @Test
    void readsOptionalParametersAndDropsInvalidOnes() {
        FileLink link = (FileLink) Ed2kLink.parse("ed2k://|file|a.bin|19456001|"
                + HASH
                + "|h="
                + AICH
                + "|p="
                + HASH
                + ":"
                + HASH
                + "|s=http://example.org/a.bin|x=ignored|/");
        FileLink bad = (FileLink) Ed2kLink.parse("ed2k://|file|a.bin|1|" + HASH + "|h=short|p=" + HASH + ":zz|/");

        assertThat(link.aichHash()).contains(Hash20.parseBase32(AICH));
        assertThat(link.partHashes()).hasSize(2);
        assertThat(link.httpSources()).containsExactly("http://example.org/a.bin");
        assertThat(bad.aichHash()).isEmpty();
        assertThat(bad.partHashes()).isEmpty();
    }

    @Test
    void dropsAPaddedAichHash() {
        String base = "ed2k://|file|a.bin|1|" + HASH + "|h=";

        assertThat(((FileLink) Ed2kLink.parse(base + "A".repeat(29) + "===|/")).aichHash())
                .isEmpty();
        assertThat(((FileLink) Ed2kLink.parse(base + "=".repeat(32) + "|/")).aichHash())
                .isEmpty();
        assertThat(((FileLink) Ed2kLink.parse(
                                "magnet:?xt=urn:ed2k:" + HASH + "&xl=1&dn=a&xt=urn:aich:" + "A".repeat(28) + "===="))
                        .aichHash())
                .isEmpty();
    }

    @Test
    void keepsOnlyWebSourcesOverHttpOrHttps() {
        FileLink link = (FileLink) Ed2kLink.parse("ed2k://|file|a.bin|1|"
                + HASH
                + "|s=file:///etc/passwd|s=ftp://example.org/a|s=HTTPS://example.org/a|s=javascript:x|/");

        assertThat(link.httpSources()).containsExactly("HTTPS://example.org/a");
    }

    @Test
    void readsSourcesAndHonoursTheirExpiry() {
        String base = "ed2k://|file|a.bin|100|" + HASH + "|/|";
        FileLink link = (FileLink) Ed2kLink.parse(base + "sources,1.2.3.4:4662,host.example.org:4663,bad,5.6.7.8:0|/");

        assertThat(link.sources()).containsExactly(new Source("1.2.3.4", 4662), new Source("host.example.org", 4663));
        assertThat(((FileLink) Ed2kLink.parse(base + "sources@000101,1.2.3.4:4662|/")).sources())
                .isEmpty();
        assertThat(((FileLink) Ed2kLink.parse(base + "sources@991231,1.2.3.4:4662|/")).sources())
                .hasSize(1);
    }

    @Test
    void decodesUtf8NamesAndKeepsRawUnicode() {
        assertThat(((FileLink) Ed2kLink.parse("ed2k://|file|citt%C3%A0.txt|1|" + HASH + "|/")).name())
                .isEqualTo("città.txt");
        assertThat(((FileLink) Ed2kLink.parse("ed2k://|file|città.txt|1|" + HASH + "|/")).name())
                .isEqualTo("città.txt");
        // Control characters are dropped.
        assertThat(((FileLink) Ed2kLink.parse("ed2k://|file|a%0Ab|1|" + HASH + "|/")).name())
                .isEqualTo("ab");
    }

    @Test
    void rejectsIncompleteOrInvalidLinks() {
        assertThatThrownBy(() -> Ed2kLink.parse("ed2k://|file|a|1|" + HASH + "|"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ed2kLink.parse("ed2k://|file|a|0|" + HASH + "|/"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ed2kLink.parse("ed2k://|file|a|" + (Ed2kLink.MAX_FILE_SIZE + 1) + "|" + HASH + "|/"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ed2kLink.parse("ed2k://|file|a|1|1234|/"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ed2kLink.parse("ed2k://|server|1.2.3.4|70000|/"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ed2kLink.parse("ed2k://|serverlist|file:///etc/passwd|/"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Ed2kLink.tryParse("http://example.org")).isEmpty();
    }

    @Test
    void parsesServerListNodesAndSearchLinks() {
        assertThat(Ed2kLink.parse("ed2k://|server|176.123.5.89|4725|/"))
                .isEqualTo(new Ed2kLink.ServerLink("176.123.5.89", 4725));
        assertThat(Ed2kLink.parse("ed2k://|serverlist|http://example.org/server.met|/"))
                .isEqualTo(new Ed2kLink.ServerListLink("http://example.org/server.met"));
        assertThat(Ed2kLink.parse("ed2k://|nodeslist|https://example.org/nodes.dat|/"))
                .isEqualTo(new Ed2kLink.NodesListLink("https://example.org/nodes.dat"));
        assertThat(Ed2kLink.parse("ed2k://|search|free%20music|/")).isEqualTo(new Ed2kLink.SearchLink("free music"));
    }

    @Test
    void parsesEd2kMagnetLinks() {
        FileLink link = (FileLink) Ed2kLink.parse("magnet:?xt=urn:ed2k:"
                + HASH
                + "&xl=123&dn=a%20b.txt&xt=urn:aich:"
                + AICH
                + "&xs=ed2kftp://1.2.3.4:4662/"
                + HASH
                + "/123/&as=http://example.org/x&tr=ignored");

        assertThat(link.name()).isEqualTo("a b.txt");
        assertThat(link.size()).isEqualTo(123);
        assertThat(link.aichHash()).contains(Hash20.parseBase32(AICH));
        assertThat(link.sources()).containsExactly(new Source("1.2.3.4", 4662));
        assertThat(link.httpSources()).containsExactly("http://example.org/x");
        assertThatThrownBy(() -> Ed2kLink.parse("magnet:?xt=urn:btih:abc&dn=x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writesLinksThatParseBack() {
        FileLink link = new FileLink(
                "100% città | test.mkv",
                19_456_001,
                Hash16.parseHex(HASH),
                Optional.of(Hash20.parseBase32(AICH)),
                List.of(Hash16.parseHex(HASH), Hash16.parseHex(HASH), Hash16.parseHex(HASH)),
                List.of(new Source("1.2.3.4", 4662)),
                List.of());

        assertThat(link.toUri()).startsWith("ed2k://|file|100%25%20citt%C3%A0%20%7C%20test.mkv|19456001|");
        assertThat(Ed2kLink.parse(link.toUri())).isEqualTo(link);
    }

    @Test
    void writesAnHtmlAnchorWithTheNameEscaped() {
        FileLink link = FileLink.of("Tom & Jerry <1>.avi", 1000, Hash16.parseHex(HASH));

        String href = link.toUri().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        assertThat(link.toHtml())
                .as("the name escaped in the address too")
                .isEqualTo("<a href=\"" + href + "\">Tom &amp; Jerry &lt;1&gt;.avi</a>")
                .doesNotContain("<1>");
    }

    @Property
    void fileLinksRoundTrip(@ForAll("names") String name, @ForAll @LongRange(min = 1, max = 1L << 38) long size) {
        FileLink link = FileLink.of(name, size, Hash16.parseHex(HASH));

        assertThat(Ed2kLink.parse(link.toUri())).isEqualTo(link);
    }

    @Test
    void sizesHashesAndPortsAreCheckedAtTheirBounds() {
        assertThat(((FileLink) Ed2kLink.parse("ed2k://|file|a|" + (1L << 38) + "|" + HASH + "|/")).size())
                .isEqualTo(1L << 38);
        assertThat(((FileLink) Ed2kLink.parse("ed2k://|file|a|1|" + HASH.toLowerCase(java.util.Locale.ROOT) + "|/"))
                        .hash())
                .isEqualTo(Hash16.parseHex(HASH));
        for (String bad : new String[] {
            "ed2k://|file|a|-1|" + HASH + "|/",
            "ed2k://|file|a|+1|" + HASH + "|/",
            "ed2k://|file|a| 1|" + HASH + "|/",
            "ed2k://|file|a|1234567890123456|" + HASH + "|/",
            "ed2k://|file|a|1|" + HASH.substring(1) + "|/",
            "ed2k://|file|a|1|" + HASH + "0|/",
            "ed2k://|file|a|1|" + "G".repeat(32) + "|/",
            "ed2k://|file||1|" + HASH + "|/",
            "ed2k://|file| |1|" + HASH + "|/",
            "ed2k://|server|1.2.3.4|0|/",
            "ed2k://|server|1.2.3.4|65536|/",
            "ed2k://|server|1.2.3.4|123456|/",
            "ed2k://|server|1.2.3.4|4661",
            "ed2k://|search| |/",
            "magnet:?xt=urn:ed2k:" + HASH + "&dn=a",
            "magnet:?xt=urn:ed2k:" + HASH + "&xl=1&dn=%20"
        }) {
            assertThatThrownBy(() -> Ed2kLink.parse(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(Ed2kLink.parse("ed2k://|server|1.2.3.4|65535|/"))
                .isEqualTo(new Ed2kLink.ServerLink("1.2.3.4", 65535));
    }

    @Test
    void dropsBadSourcesAndPartHashesButKeepsTheLink() {
        String base = "ed2k://|file|a.bin|100|" + HASH + "|";

        assertThat(((FileLink) Ed2kLink.parse(base + "p=" + HASH + "::" + HASH + "|/")).partHashes())
                .isEmpty();
        assertThat(((FileLink) Ed2kLink.parse(
                                base + "/|sources,1.2.3.4:0,1.2.3.4:65536,:4662,1.2.3.4:x,1.2.3.4:4662|/"))
                        .sources())
                .containsExactly(new Source("1.2.3.4", 4662));
        assertThat(((FileLink) Ed2kLink.parse(base + "/|sources@991399,1.2.3.4:4662|/")).sources())
                .as("an impossible date")
                .isEmpty();
        assertThat(((FileLink) Ed2kLink.parse(base + "/|sources@99,1.2.3.4:4662|/")).sources())
                .as("a short date")
                .isEmpty();
        StringBuilder many = new StringBuilder(base + "/|sources");
        for (int i = 0; i < Ed2kLink.MAX_SOURCES + 10; i++) {
            many.append(",1.2.").append(i / 250).append('.').append(i % 250 + 1).append(":4662");
        }
        assertThat(((FileLink) Ed2kLink.parse(many + "|/")).sources()).hasSize(Ed2kLink.MAX_SOURCES);
    }

    @Property
    void namesOfAnyScriptRoundTrip(@ForAll("anyNames") String name) {
        FileLink link = FileLink.of(name, 1, Hash16.parseHex(HASH));

        assertThat(Ed2kLink.parse(link.toUri())).isEqualTo(link);
    }

    @Property
    void serverLinksRoundTrip(@ForAll("hosts") String host, @ForAll @IntRange(min = 1, max = 65535) int port) {
        Ed2kLink.ServerLink link = new Ed2kLink.ServerLink(host, port);

        assertThat(Ed2kLink.parse(link.toUri())).isEqualTo(link);
    }

    @Property
    void anythingInsideAFileLinkOnlyFailsWithIllegalArgument(@ForAll String text) {
        try {
            Ed2kLink.parse("ed2k://|file|a.bin|100|" + HASH + "|" + text + "|/");
            Ed2kLink.parse("ed2k://|file|" + text + "|100|" + HASH + "|/");
            Ed2kLink.parse("ed2k://|file|a.bin|100|" + HASH + "|/|" + text + "|/");
        } catch (IllegalArgumentException expected) {
            // invalid link
        }
    }

    @Provide
    Arbitrary<String> anyNames() {
        return Arbitraries.oneOf(
                        Arbitraries.strings().withCharRange(' ', 'ÿ'),
                        Arbitraries.strings().withCharRange(' ', '\uD7FF'),
                        Arbitraries.strings().ofMinLength(1).ofMaxLength(4).map(s -> s + "\uD83C\uDFB5"))
                .map(s -> s.codePoints()
                        .filter(c -> !Character.isISOControl(c) && !Character.isSurrogate((char) c))
                        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                        .toString()
                        .strip())
                .filter(s -> !s.isEmpty());
    }

    @Provide
    Arbitrary<String> hosts() {
        return Arbitraries.oneOf(
                Arbitraries.integers()
                        .between(0, 255)
                        .list()
                        .ofSize(4)
                        .map(l -> l.get(0) + "." + l.get(1) + "." + l.get(2) + "." + l.get(3)),
                Arbitraries.strings()
                        .withCharRange('a', 'z')
                        .ofMinLength(1)
                        .ofMaxLength(20)
                        .map(s -> s + ".example"));
    }

    @Property
    void garbageOnlyFailsWithIllegalArgument(@ForAll String text) {
        try {
            Ed2kLink.parse("ed2k://|" + text);
            Ed2kLink.parse("magnet:?" + text);
        } catch (IllegalArgumentException expected) {
            // invalid link
        }
    }

    @Provide
    Arbitrary<String> names() {
        return Arbitraries.strings()
                .withCharRange(' ', '\uD7FF')
                .ofMinLength(1)
                .ofMaxLength(40)
                .map(String::strip)
                .filter(s -> !s.isEmpty());
    }
}
