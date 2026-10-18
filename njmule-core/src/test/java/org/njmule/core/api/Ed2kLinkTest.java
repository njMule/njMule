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
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.njmule.core.api.Ed2kLink.FileLink;
import org.njmule.core.api.Ed2kLink.Source;

class Ed2kLinkTest {

    private static final String HASH = "31D6CFE0D16AE931B73C59D7E0C089C0";
    private static final String AICH = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Ed2kLink written;
    private String uri;
    private Ed2kLink parsed;
    private Optional<Ed2kLink> found;
    private String html;
    private Throwable exception;

    @Test
    void shouldParseAPlainFileLink() {
        whenTheLinkIsParsed(" ed2k://|file|Ubuntu%2024.04.iso|5000000000|" + HASH + "|/ ");

        thenTheFileNameIs("Ubuntu 24.04.iso");
        thenTheFileSizeIs(5_000_000_000L);
        thenTheFileHashIs(HASH);
        thenTheFileHasNoAichHash();
        thenTheFileHasNoSources();
    }

    @Test
    void shouldReadALinkWithTheSeparatorsABrowserEscaped() {
        whenTheLinkIsParsed("ed2k://%7Cfile%7CUbuntu%2024.04.iso%7C5000%7C" + HASH + "%7c/");

        thenTheFileNameIs("Ubuntu 24.04.iso");
        thenTheFileSizeIs(5000);
    }

    @Test
    void shouldReadOptionalParameters() {
        whenTheLinkIsParsed("ed2k://|file|a.bin|19456001|"
                + HASH
                + "|h="
                + AICH
                + "|p="
                + HASH
                + ":"
                + HASH
                + "|s=http://example.org/a.bin|x=ignored|/");

        thenTheAichHashIs(AICH);
        thenThePartHashCountIs(2);
        thenTheWebSourcesAre("http://example.org/a.bin");
    }

    @Test
    void shouldDropInvalidOptionalParameters() {
        whenTheLinkIsParsed("ed2k://|file|a.bin|1|" + HASH + "|h=short|p=" + HASH + ":zz|/");

        thenTheFileHasNoAichHash();
        thenTheFileHasNoPartHashes();
    }

    @ParameterizedTest
    @MethodSource("linksWithAPaddedAichHash")
    void shouldDropAPaddedAichHash(String link) {
        whenTheLinkIsParsed(link);

        thenTheFileHasNoAichHash();
    }

    @Test
    void shouldKeepOnlyWebSourcesOverHttpOrHttps() {
        whenTheLinkIsParsed("ed2k://|file|a.bin|1|"
                + HASH
                + "|s=file:///etc/passwd|s=ftp://example.org/a|s=HTTPS://example.org/a|s=javascript:x|/");

        thenTheWebSourcesAre("HTTPS://example.org/a");
    }

    @Test
    void shouldReadTheValidSources() {
        whenTheLinkIsParsed(
                "ed2k://|file|a.bin|100|" + HASH + "|/|sources,1.2.3.4:4662,host.example.org:4663,bad,5.6.7.8:0|/");

        thenTheSourcesAre(new Source("1.2.3.4", 4662), new Source("host.example.org", 4663));
    }

    @Test
    void shouldReadAnIpv6SourceInBrackets() {
        whenTheLinkIsParsed("ed2k://|file|a.bin|100|" + HASH + "|/|sources,[2001:db8::1]:4662,[1.2.3.4]:4662,[x]:1|/");

        thenTheSourcesAre(new Source("2001:db8::1", 4662));
    }

    @Test
    void shouldWriteAnIpv6SourceInBracketsAndReadItBack() {
        givenAFileLinkWithTheSource(new Source("2001:db8::1", 4662));

        whenTheLinkIsWrittenAndParsedBack();

        thenTheLinkParsedBackIsTheSame();
        thenTheLinkContains("|sources,[2001:db8::1]:4662|");
    }

    @Test
    void shouldDropSourcesPastTheirExpiry() {
        whenTheLinkIsParsed("ed2k://|file|a.bin|100|" + HASH + "|/|sources@000101,1.2.3.4:4662|/");

        thenTheFileHasNoSources();
    }

    @Test
    void shouldKeepSourcesBeforeTheirExpiry() {
        whenTheLinkIsParsed("ed2k://|file|a.bin|100|" + HASH + "|/|sources@991231,1.2.3.4:4662|/");

        thenTheSourceCountIs(1);
    }

    @ParameterizedTest
    @CsvSource({"citt%C3%A0.txt, città.txt", "città.txt, città.txt"})
    void shouldDecodeUtf8NamesAndKeepRawUnicode(String name, String expected) {
        whenTheLinkIsParsed("ed2k://|file|" + name + "|1|" + HASH + "|/");

        thenTheFileNameIs(expected);
    }

    @Test
    void shouldDropControlCharactersFromTheName() {
        whenTheLinkIsParsed("ed2k://|file|a%0Ab|1|" + HASH + "|/");

        thenTheFileNameIs("ab");
    }

    @ParameterizedTest
    @MethodSource("incompleteOrInvalidLinks")
    void shouldRejectIncompleteOrInvalidLinks(String link) {
        whenTheLinkIsParsed(link);

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldFindNoLinkInAnAddressOfAnotherKind() {
        whenALinkIsLookedForIn("http://example.org");

        thenNoLinkIsFound();
    }

    @Test
    void shouldParseAServerLink() {
        whenTheLinkIsParsed("ed2k://|server|176.123.5.89|4725|/");

        thenTheLinkIs(new Ed2kLink.ServerLink("176.123.5.89", 4725));
    }

    @Test
    void shouldParseAServerListLink() {
        whenTheLinkIsParsed("ed2k://|serverlist|http://example.org/server.met|/");

        thenTheLinkIs(new Ed2kLink.ServerListLink("http://example.org/server.met"));
    }

    @Test
    void shouldParseANodesListLink() {
        whenTheLinkIsParsed("ed2k://|nodeslist|https://example.org/nodes.dat|/");

        thenTheLinkIs(new Ed2kLink.NodesListLink("https://example.org/nodes.dat"));
    }

    @Test
    void shouldParseASearchLink() {
        whenTheLinkIsParsed("ed2k://|search|free%20music|/");

        thenTheLinkIs(new Ed2kLink.SearchLink("free music"));
    }

    @Test
    void shouldParseAnEd2kMagnetLink() {
        whenTheLinkIsParsed("magnet:?xt=urn:ed2k:"
                + HASH
                + "&xl=123&dn=a%20b.txt&xt=urn:aich:"
                + AICH
                + "&xs=ed2kftp://1.2.3.4:4662/"
                + HASH
                + "/123/&as=http://example.org/x&tr=ignored");

        thenTheFileNameIs("a b.txt");
        thenTheFileSizeIs(123);
        thenTheAichHashIs(AICH);
        thenTheSourcesAre(new Source("1.2.3.4", 4662));
        thenTheWebSourcesAre("http://example.org/x");
    }

    @Test
    void shouldRejectAMagnetLinkWithoutAnEd2kHash() {
        whenTheLinkIsParsed("magnet:?xt=urn:btih:abc&dn=x");

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldWriteLinksThatParseBack() {
        givenAFileLinkWithEveryParameter("100% città | test.mkv", 19_456_001);

        whenTheLinkIsWrittenAndParsedBack();

        thenTheAddressStartsWith("ed2k://|file|100%25%20citt%C3%A0%20%7C%20test.mkv|19456001|");
        thenTheLinkParsedBackIsTheSame();
    }

    @Test
    void shouldWriteAnHtmlAnchorWithTheNameEscaped() {
        givenAFileLink("Tom & Jerry <1>.avi", 1000);

        whenTheLinkIsWrittenAsHtml();

        thenTheAnchorShowsTheEscapedName("Tom &amp; Jerry &lt;1&gt;.avi");
    }

    @Property
    void shouldRoundTripFileLinks(@ForAll("names") String name, @ForAll @LongRange(min = 1, max = 1L << 38) long size) {
        givenAFileLink(name, size);

        whenTheLinkIsWrittenAndParsedBack();

        thenTheLinkParsedBackIsTheSame();
    }

    @Test
    void shouldAcceptTheLargestFileSize() {
        whenTheLinkIsParsed("ed2k://|file|a|" + (1L << 38) + "|" + HASH + "|/");

        thenTheFileSizeIs(1L << 38);
    }

    @Test
    void shouldAcceptALowerCaseHash() {
        whenTheLinkIsParsed("ed2k://|file|a|1|" + HASH.toLowerCase(Locale.ROOT) + "|/");

        thenTheFileHashIs(HASH);
    }

    @ParameterizedTest
    @MethodSource("linksBeyondTheBounds")
    void shouldRejectSizesHashesAndPortsBeyondTheirBounds(String link) {
        whenTheLinkIsParsed(link);

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldAcceptTheHighestPort() {
        whenTheLinkIsParsed("ed2k://|server|1.2.3.4|65535|/");

        thenTheLinkIs(new Ed2kLink.ServerLink("1.2.3.4", 65535));
    }

    @Test
    void shouldDropBadPartHashesButKeepTheLink() {
        whenTheLinkIsParsed("ed2k://|file|a.bin|100|" + HASH + "|p=" + HASH + "::" + HASH + "|/");

        thenTheFileHasNoPartHashes();
    }

    @Test
    void shouldDropBadSourcesButKeepTheLink() {
        whenTheLinkIsParsed(
                "ed2k://|file|a.bin|100|" + HASH + "|/|sources,1.2.3.4:0,1.2.3.4:65536,:4662,1.2.3.4:x,1.2.3.4:4662|/");

        thenTheSourcesAre(new Source("1.2.3.4", 4662));
    }

    @ParameterizedTest
    @CsvSource({"991399, an impossible date", "99, a short date"})
    void shouldDropSourcesWithABadExpiryButKeepTheLink(String expiry, String reason) {
        whenTheLinkIsParsed("ed2k://|file|a.bin|100|" + HASH + "|/|sources@" + expiry + ",1.2.3.4:4662|/");

        thenTheSourcesAreDroppedFor(reason);
    }

    @Test
    void shouldKeepAtMostTheMaximumNumberOfSources() {
        whenTheLinkIsParsed(linkWithSources(Ed2kLink.MAX_SOURCES + 10));

        thenTheSourceCountIs(Ed2kLink.MAX_SOURCES);
    }

    @Property
    void shouldRoundTripNamesOfAnyScript(@ForAll("anyNames") String name) {
        givenAFileLink(name, 1);

        whenTheLinkIsWrittenAndParsedBack();

        thenTheLinkParsedBackIsTheSame();
    }

    @Property
    void shouldRoundTripServerLinks(@ForAll("hosts") String host, @ForAll @IntRange(min = 1, max = 65535) int port) {
        givenAServerLink(host, port);

        whenTheLinkIsWrittenAndParsedBack();

        thenTheLinkParsedBackIsTheSame();
    }

    @Property
    void shouldOnlyFailWithIllegalArgumentForAnythingInsideAFileLink(@ForAll String text) {
        whenFileLinksWithTheTextInsideAreParsed(text);

        thenAnyFailureIsAnIllegalArgument();
    }

    @Property
    void shouldOnlyFailWithIllegalArgumentForGarbage(@ForAll String text) {
        whenLinksMadeOfTheTextAreParsed(text);

        thenAnyFailureIsAnIllegalArgument();
    }

    static Stream<String> linksWithAPaddedAichHash() {
        String base = "ed2k://|file|a.bin|1|" + HASH + "|h=";
        return Stream.of(
                base + "A".repeat(29) + "===|/",
                base + "=".repeat(32) + "|/",
                "magnet:?xt=urn:ed2k:" + HASH + "&xl=1&dn=a&xt=urn:aich:" + "A".repeat(28) + "====");
    }

    static Stream<String> incompleteOrInvalidLinks() {
        return Stream.of(
                "ed2k://|file|a|1|" + HASH + "|",
                "ed2k://|file|a|0|" + HASH + "|/",
                "ed2k://|file|a|" + (Ed2kLink.MAX_FILE_SIZE + 1) + "|" + HASH + "|/",
                "ed2k://|file|a|1|1234|/",
                "ed2k://|server|1.2.3.4|70000|/",
                "ed2k://|serverlist|file:///etc/passwd|/");
    }

    static Stream<String> linksBeyondTheBounds() {
        return Stream.of(
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
                "magnet:?xt=urn:ed2k:" + HASH + "&xl=1&dn=%20");
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

    @Provide
    Arbitrary<String> names() {
        return Arbitraries.strings()
                .withCharRange(' ', '\uD7FF')
                .ofMinLength(1)
                .ofMaxLength(40)
                .map(String::strip)
                .filter(s -> !s.isEmpty());
    }

    private void givenAFileLink(String name, long size) {
        this.written = FileLink.of(name, size, Hash16.parseHex(HASH));
    }

    private void givenAFileLinkWithEveryParameter(String name, long size) {
        this.written = new FileLink(
                name,
                size,
                Hash16.parseHex(HASH),
                Optional.of(Hash20.parseBase32(AICH)),
                List.of(Hash16.parseHex(HASH), Hash16.parseHex(HASH), Hash16.parseHex(HASH)),
                List.of(new Source("1.2.3.4", 4662)),
                List.of());
    }

    private void givenAFileLinkWithTheSource(Source source) {
        this.written = new FileLink(
                "a.bin", 100, Hash16.parseHex(HASH), Optional.empty(), List.of(), List.of(source), List.of());
    }

    private void givenAServerLink(String host, int port) {
        this.written = new Ed2kLink.ServerLink(host, port);
    }

    private void whenTheLinkIsParsed(String link) {
        this.uri = link;
        this.parsed = null;
        this.exception = catchThrowable(() -> this.parsed = Ed2kLink.parse(link));
    }

    private void whenALinkIsLookedForIn(String text) {
        this.found = Ed2kLink.tryParse(text);
    }

    private void whenTheLinkIsWrittenAndParsedBack() {
        this.uri = this.written.toUri();
        this.parsed = Ed2kLink.parse(this.uri);
    }

    private void whenTheLinkIsWrittenAsHtml() {
        this.html = ((FileLink) this.written).toHtml();
    }

    private void whenFileLinksWithTheTextInsideAreParsed(String text) {
        this.exception = catchThrowable(() -> {
            Ed2kLink.parse("ed2k://|file|a.bin|100|" + HASH + "|" + text + "|/");
            Ed2kLink.parse("ed2k://|file|" + text + "|100|" + HASH + "|/");
            Ed2kLink.parse("ed2k://|file|a.bin|100|" + HASH + "|/|" + text + "|/");
        });
    }

    private void whenLinksMadeOfTheTextAreParsed(String text) {
        this.exception = catchThrowable(() -> {
            Ed2kLink.parse("ed2k://|" + text);
            Ed2kLink.parse("magnet:?" + text);
        });
    }

    private void thenTheFileNameIs(String expected) {
        assertThat(fileLink().name()).isEqualTo(expected);
    }

    private void thenTheFileSizeIs(long expected) {
        assertThat(fileLink().size()).isEqualTo(expected);
    }

    private void thenTheFileHashIs(String hex) {
        assertThat(fileLink().hash()).isEqualTo(Hash16.parseHex(hex));
    }

    private void thenTheAichHashIs(String base32) {
        assertThat(fileLink().aichHash()).contains(Hash20.parseBase32(base32));
    }

    private void thenTheFileHasNoAichHash() {
        assertThat(fileLink().aichHash()).isEmpty();
    }

    private void thenThePartHashCountIs(int expected) {
        assertThat(fileLink().partHashes()).hasSize(expected);
    }

    private void thenTheFileHasNoPartHashes() {
        assertThat(fileLink().partHashes()).isEmpty();
    }

    private void thenTheWebSourcesAre(String... expected) {
        assertThat(fileLink().httpSources()).containsExactly(expected);
    }

    private void thenTheSourcesAre(Source... expected) {
        assertThat(fileLink().sources()).containsExactly(expected);
    }

    private void thenTheSourceCountIs(int expected) {
        assertThat(fileLink().sources()).hasSize(expected);
    }

    private void thenTheFileHasNoSources() {
        assertThat(fileLink().sources()).isEmpty();
    }

    private void thenTheSourcesAreDroppedFor(String reason) {
        assertThat(fileLink().sources()).as(reason).isEmpty();
    }

    private void thenTheLinkIs(Ed2kLink expected) {
        assertThat(this.exception).isNull();
        assertThat(this.parsed).isEqualTo(expected);
    }

    private void thenNoLinkIsFound() {
        assertThat(this.found).isEmpty();
    }

    private void thenTheAddressStartsWith(String prefix) {
        assertThat(this.uri).startsWith(prefix);
    }

    private void thenTheLinkParsedBackIsTheSame() {
        assertThat(this.parsed).isEqualTo(this.written);
    }

    private void thenTheLinkContains(String text) {
        assertThat(this.uri).contains(text);
    }

    private void thenTheAnchorShowsTheEscapedName(String escapedName) {
        String href =
                this.written.toUri().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        assertThat(this.html)
                .as("the name escaped in the address too")
                .isEqualTo("<a href=\"" + href + "\">" + escapedName + "</a>")
                .doesNotContain("<1>");
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).as(this.uri).isInstanceOf(type);
    }

    private void thenAnyFailureIsAnIllegalArgument() {
        if (this.exception != null) {
            assertThat(this.exception).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private FileLink fileLink() {
        assertThat(this.exception).isNull();
        assertThat(this.parsed).isInstanceOf(FileLink.class);
        return (FileLink) this.parsed;
    }

    private static String linkWithSources(int count) {
        StringBuilder many = new StringBuilder("ed2k://|file|a.bin|100|" + HASH + "|/|sources");
        for (int i = 0; i < count; i++) {
            many.append(",1.2.").append(i / 250).append('.').append(i % 250 + 1).append(":4662");
        }
        return many.append("|/").toString();
    }
}
