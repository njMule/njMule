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
package org.njmule.core.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.search.SearchExpr.And;
import org.njmule.core.search.SearchExpr.Comparison;
import org.njmule.core.search.SearchExpr.Keyword;
import org.njmule.core.search.SearchExpr.Not;
import org.njmule.core.search.SearchExpr.NumericAttr;
import org.njmule.core.search.SearchExpr.Or;
import org.njmule.core.search.SearchExpr.StringAttr;
import org.njmule.core.search.SearchSyntaxException.Reason;

class SearchParserTest {

    private static final String RELATED_SEARCH = "related::" + "ab".repeat(16) + "::" + "cd".repeat(16);
    private static final NumericAttr LARGE_SIZE =
            new NumericAttr(FileTag.FILESIZE, Comparison.GREATER_EQUAL, 5_000_000_000L);

    private String input;
    private SearchExpr parsed;
    private SearchExpr search;
    private SearchExpr serverSearch;
    private SearchExpr sent;
    private byte[] encoded;
    private String kadKeyword;
    private Throwable exception;

    @ParameterizedTest
    @MethodSource("adjacentWords")
    void shouldAndAdjacentWordsFromTheRight(String text, SearchExpr expected) {
        whenTheTextIsParsed(text);

        thenTheSearchIs(expected);
    }

    @Test
    void shouldTakeARelatedSearchAsOneWordForTheServer() {
        whenTheTextIsParsed(RELATED_SEARCH);

        thenTheSearchIs(kw(RELATED_SEARCH));
    }

    @ParameterizedTest
    @MethodSource("precedences")
    void shouldBindNotTighterThanOrAndOrTighterThanAnd(String text, SearchExpr expected) {
        whenTheTextIsParsed(text);

        thenTheSearchIs(expected);
    }

    @Test
    void shouldReadALeadingMinusAsNot() {
        whenTheTextIsParsed("linux -windows");

        thenTheSearchIs(new Not(kw("linux"), kw("windows")));
    }

    @Test
    void shouldKeepAMinusInsideAWord() {
        whenTheTextIsParsed("x-ray");

        thenTheSearchIs(kw("x-ray"));
    }

    @Test
    void shouldTakeLowerCaseOperatorsAsWords() {
        whenTheTextIsParsed("rock or roll");

        thenTheSearchIs(new And(kw("rock"), new And(kw("or"), kw("roll"))));
    }

    @Test
    void shouldTakeAnUpperCaseWordStartingLikeAnOperatorAsAWord() {
        whenTheTextIsParsed("ORANGE");

        thenTheSearchIs(kw("ORANGE"));
    }

    @ParameterizedTest
    @MethodSource("quotedPhrases")
    void shouldKeepTheQuotesAndEscapesOfQuotedPhrases(String text, SearchExpr expected) {
        whenTheTextIsParsed(text);

        thenTheSearchIs(expected);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = ';',
            value = {
                "@size>700m;GREATER;734003200",
                "@size>=1.5g;GREATER_EQUAL;1610612736",
                "@size<100;LESS;104857600",
                "@siz<=10k;LESS_EQUAL;10240",
                "@size=5b;EQUAL;5",
                "@size<>3m;NOT_EQUAL;3145728"
            })
    void shouldReadSizesInBinaryUnitsAndDefaultToMegabytes(String text, Comparison op, long bytes) {
        whenTheTextIsParsed(text);

        thenTheSearchIs(new NumericAttr(FileTag.FILESIZE, op, bytes));
    }

    @ParameterizedTest
    @MethodSource("numericAttributes")
    void shouldReadNumericAttributesInDecimalUnitsAndLengthsAsTime(String text, SearchExpr expected) {
        whenTheTextIsParsed(text);

        thenTheSearchIs(expected);
    }

    @ParameterizedTest
    @MethodSource("stringAttributes")
    void shouldReadStringAttributesAndFileTypes(String text, SearchExpr expected) {
        whenTheTextIsParsed(text);

        thenTheSearchIs(expected);
    }

    @Test
    void shouldTurnAnEd2kLinkIntoAHashTerm() {
        whenTheTextIsParsed("ed2k://|file|My File.iso|123|31d6cfe0d16ae931b73c59d7e0c089c0|/");

        thenTheSearchIs(kw("ed2k::31D6CFE0D16AE931B73C59D7E0C089C0"));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = ';',
            value = {
                "a AND;MISSING_AND_OPERAND",
                "a OR;MISSING_OR_OPERAND",
                "a NOT;MISSING_NOT_OPERAND",
                "( );MISSING_EXPRESSION_IN_PARENTHESES",
                "(a;MISSING_CLOSING_PARENTHESIS",
                "@foo=1;UNKNOWN_ATTRIBUTE",
                "@size5;UNKNOWN_ATTRIBUTE",
                "@size;INVALID_ATTRIBUTE_VALUE",
                "@type=foo;INVALID_ATTRIBUTE_VALUE",
                "@ext>mp3;INVALID_ATTRIBUTE_VALUE",
                "\"abc;UNTERMINATED_STRING",
                "=5;OPERATOR_WITHOUT_ATTRIBUTE",
                "a > b;OPERATOR_WITHOUT_ATTRIBUTE",
                "-a;GENERAL",
                "a b c d e f g h i j k l;TOO_COMPLEX"
            })
    void shouldReportWhyAnExpressionIsInvalid(String text, Reason reason) {
        whenTheTextIsParsed(text);

        thenTheSyntaxErrorIs(reason);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "@length>99999999999999999999:00",
                "@length>1:99999999999999999999",
                "@length>1:00:99999999999999999999",
                "@length>3000000000000000:00:00",
                "@length>5000000000000000:00:00",
                "@length>2562047788015215:30:08",
                "@length>153722867280912931:00"
            })
    void shouldRefuseLengthsTooLargeForANumber(String text) {
        whenTheTextIsParsed(text);

        thenTheSyntaxErrorIs(Reason.INVALID_ATTRIBUTE_VALUE);
    }

    @Test
    void shouldAcceptTheLongestLengthANumberHolds() {
        whenTheTextIsParsed("@length>2562047788015215:30:07");

        thenTheSearchIs(new NumericAttr(FileTag.MEDIA_LENGTH, Comparison.GREATER, Long.MAX_VALUE));
    }

    @Test
    void shouldAcceptALengthOfZero() {
        whenTheTextIsParsed("@length>0:0:0");

        thenTheSearchIs(new NumericAttr(FileTag.MEDIA_LENGTH, Comparison.GREATER, 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"@size>1e999", "@sources>1e30", "@size>9999999999999999g", "@bitrate>."})
    void shouldRefuseNumbersTooLargeOrMalformed(String text) {
        whenTheTextIsParsed(text);

        thenExceptionOccurred(SearchSyntaxException.class);
    }

    @Test
    void shouldReadAFractionWithoutALeadingZero() {
        whenTheTextIsParsed("@size>.5k");

        thenTheSearchIs(new NumericAttr(FileTag.FILESIZE, Comparison.GREATER, 512));
    }

    @Property
    void shouldParseFragmentsOfTheSyntaxOrFailWithASyntaxError(@ForAll("fragments") List<String> parts) {
        whenTheTextIsParsed(String.join("", parts));

        thenTheTextIsASearchOrASyntaxError();
        thenAParsedSearchCanBeReadBackFromA64BitServer();
    }

    @Provide
    Arbitrary<List<String>> fragments() {
        return Arbitraries.of(
                        "(",
                        ")",
                        "\"",
                        " ",
                        "a",
                        "b1",
                        "@size>",
                        "@length>",
                        "@length=",
                        "@type=",
                        "@ext=",
                        "AND",
                        "OR",
                        "NOT",
                        "-",
                        ":",
                        "99999999999999999999",
                        "123",
                        "1.5",
                        "e9",
                        "k",
                        "m",
                        "g",
                        "=",
                        "<",
                        ">",
                        "video",
                        "\\")
                .list()
                .ofMaxSize(12);
    }

    @Test
    void shouldPrefixEncodeTheTreesForServers() {
        whenTheTreeIsWrittenFor32BitServers(new And(kw("a"), new NumericAttr(FileTag.FILESIZE, Comparison.GREATER, 5)));

        thenTheBytesWrittenAre("0000" + "01010061" + "0305000000" + "01" + "0100" + "02");
    }

    @Test
    void shouldKeepALargeValueFor64BitServers() throws DecodeException {
        whenTheTreeIsWrittenFor64BitServers(LARGE_SIZE);

        thenTheTreeReadBackIs(LARGE_SIZE);
    }

    @Test
    void shouldCapALargeValueFor32BitServers() throws DecodeException {
        whenTheTreeIsWrittenFor32BitServers(LARGE_SIZE);

        thenTheTreeReadBackIs(new NumericAttr(FileTag.FILESIZE, Comparison.GREATER_EQUAL, 0xFFFF_FFFFL));
    }

    @Test
    void shouldSendAndedKeywordsAsOnePhrase() {
        givenTheSearch("ubuntu 24.04 iso");

        whenItIsPreparedForAServer();

        thenTheServerSearchIs(kw("ubuntu 24.04 iso"));
    }

    @Test
    void shouldSendOtherSearchesAsTheyAre() {
        givenTheSearch("a OR b");

        whenItIsPreparedForAServer();

        thenTheServerSearchIsTheSearchItself();
    }

    @Test
    void shouldAskServersForArchivesAsPrograms() throws DecodeException {
        givenTheSearch("linux @type=arc");

        whenItIsSentToAServer();

        thenTheServerIsAskedFor(new And(kw("linux"), new StringAttr(FileTag.FILETYPE, "Pro")));
    }

    @Test
    void shouldAskServersForDiscImagesAsPrograms() throws DecodeException {
        givenTheSearch("@type=iso linux");

        whenItIsSentToAServer();

        thenTheServerIsAskedFor(new And(new StringAttr(FileTag.FILETYPE, "Pro"), kw("linux")));
    }

    @Property
    void shouldReadBackEveryTreeItWrites(@ForAll("trees") SearchExpr tree) throws DecodeException {
        whenTheTreeIsWrittenFor64BitServers(tree);

        thenTheTreeReadBackIs(tree);
        thenNothingFollowsTheTree();
    }

    @Property
    void shouldParseAnyTextOrFailWithASyntaxError(@ForAll @Size(max = 60) String text) {
        whenTheTextIsParsed(text);

        thenTheTextIsASearchOrASyntaxError();
    }

    @Property
    void shouldDecodeAnyBytesOrFailCleanly(@ForAll @Size(max = 60) byte[] data) {
        whenTheBytesAreRead(data);

        thenTheBytesAreATreeOrADecodeError();
    }

    @Provide
    Arbitrary<SearchExpr> trees() {
        Arbitrary<SearchExpr> leaf = Arbitraries.oneOf(
                Arbitraries.strings()
                        .withCharRange('a', 'z')
                        .ofMinLength(1)
                        .ofMaxLength(8)
                        .map(Keyword::new),
                Combinators.combine(
                                Arbitraries.integers().between(0, 255),
                                Arbitraries.strings().withCharRange(' ', '퟿').ofMaxLength(8))
                        .as(StringAttr::new),
                Combinators.combine(
                                Arbitraries.integers().between(0, 255),
                                Arbitraries.of(Comparison.class),
                                Arbitraries.longs().between(0, Long.MAX_VALUE))
                        .as(NumericAttr::new));
        return Arbitraries.recursive(
                () -> leaf,
                inner -> Arbitraries.oneOf(
                        Combinators.combine(inner, inner).as(And::new),
                        Combinators.combine(inner, inner).as(Or::new),
                        Combinators.combine(inner, inner).as(Not::new)),
                0,
                4);
    }

    @Test
    void shouldLookUpTheLongestWordInKad() {
        whenKadPicksTheKeywordOf("the matrix reloaded");

        thenKadLooksUp("reloaded");
    }

    @Test
    void shouldLookUpAWordEveryResultHasInKad() {
        whenKadPicksTheKeywordOf("\"linux\" AND (debian OR ubuntu) @size>700m");

        thenKadLooksUp("linux", "a word every result has");
    }

    @Test
    void shouldLookUpTheTrimmedLowerCaseWordInKad() {
        whenKadPicksTheKeywordOf("  Único  ");

        thenKadLooksUp("único");
    }

    @Test
    void shouldLookUpAWordKadIndexes() {
        whenKadPicksTheKeywordOf("ubuntu 22.04.3");

        thenKadLooksUp("ubuntu", "a word Kad indexes");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = ';',
            value = {"linux -distribution;linux", "linux NOT (distribution OR distro);linux"})
    void shouldNotLookUpAnExcludedWordInKad(String expression, String keyword) {
        whenKadPicksTheKeywordOf(expression);

        thenKadLooksUp(keyword);
    }

    @Test
    void shouldPreferAWordOutsideAPhraseInKad() {
        whenKadPicksTheKeywordOf("iso \"the matrix\"");

        thenKadLooksUp("iso");
    }

    @Test
    void shouldRefuseAlternativesInKad() {
        whenKadPicksTheKeywordOf("linux OR windows");

        thenExceptionOccurred(IllegalArgumentException.class, "OR");
    }

    @Test
    void shouldRefuseAKadSearchWithoutAWordKadIndexes() {
        whenKadPicksTheKeywordOf("22.04 @size>700m");

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    static Stream<Arguments> adjacentWords() {
        return Stream.of(
                arguments("linux iso", new And(kw("linux"), kw("iso"))),
                arguments("a b c", new And(kw("a"), new And(kw("b"), kw("c")))));
    }

    static Stream<Arguments> precedences() {
        return Stream.of(
                arguments("a OR b c", new And(new Or(kw("a"), kw("b")), kw("c"))),
                arguments("a AND b OR c", new And(kw("a"), new Or(kw("b"), kw("c")))),
                arguments("a OR b NOT c", new Or(kw("a"), new Not(kw("b"), kw("c")))),
                arguments("(a OR b) AND c", new And(new Or(kw("a"), kw("b")), kw("c"))));
    }

    static Stream<Arguments> quotedPhrases() {
        return Stream.of(
                arguments("\"exact phrase\" x", new And(kw("\"exact phrase\""), kw("x"))),
                arguments("\"a\\\"b\"", kw("\"a\"b\"")),
                arguments("\"\" x", kw("x")));
    }

    static Stream<Arguments> numericAttributes() {
        return Stream.of(
                arguments("@sources>10", new NumericAttr(FileTag.SOURCES, Comparison.GREATER, 10)),
                arguments("@avail>=2k", new NumericAttr(FileTag.SOURCES, Comparison.GREATER_EQUAL, 2000)),
                arguments("@complete>5", new NumericAttr(FileTag.COMPLETE_SOURCES, Comparison.GREATER, 5)),
                arguments("@bitrate>=192", new NumericAttr(FileTag.MEDIA_BITRATE, Comparison.GREATER_EQUAL, 192)),
                arguments("@rating>3", new NumericAttr(FileTag.FILERATING, Comparison.GREATER, 3)),
                arguments("@length>1:30", new NumericAttr(FileTag.MEDIA_LENGTH, Comparison.GREATER, 90)),
                arguments("@len>=1:00:00", new NumericAttr(FileTag.MEDIA_LENGTH, Comparison.GREATER_EQUAL, 3600)),
                arguments("@length < 5m", new NumericAttr(FileTag.MEDIA_LENGTH, Comparison.LESS, 300)));
    }

    static Stream<Arguments> stringAttributes() {
        return Stream.of(
                arguments("@type=video", new StringAttr(FileTag.FILETYPE, "Video")),
                arguments("@typ=img", new StringAttr(FileTag.FILETYPE, "Image")),
                arguments("@type=doc", new StringAttr(FileTag.FILETYPE, "Doc")),
                arguments("@type=cd", new StringAttr(FileTag.FILETYPE, "Iso")),
                arguments("@ext=mkv", new StringAttr(FileTag.FILEFORMAT, "mkv")),
                arguments("@ext=\"mp3\"", new StringAttr(FileTag.FILEFORMAT, "mp3")),
                arguments("@artist=Queen song", new And(new StringAttr(FileTag.MEDIA_ARTIST, "Queen"), kw("song"))));
    }

    private void givenTheSearch(String expression) {
        this.search = SearchParser.parse(expression);
    }

    // jqwik reuses the instance across the tries of a property: every field is assigned again here.
    private void whenTheTextIsParsed(String text) {
        this.input = text;
        this.parsed = null;
        this.exception = catchThrowable(() -> this.parsed = SearchParser.parse(text));
    }

    private void whenItIsPreparedForAServer() {
        this.serverSearch = SearchTreeCodec.forServer(this.search);
    }

    private void whenItIsSentToAServer() throws DecodeException {
        PacketWriter out = new PacketWriter();
        SearchTreeCodec.write(out, SearchTreeCodec.forServer(this.search), false);
        this.sent = SearchTreeCodec.read(PacketReader.of(out.toByteArray()));
    }

    private void whenTheTreeIsWrittenFor64BitServers(SearchExpr tree) {
        this.encoded = write(tree, true);
    }

    private void whenTheTreeIsWrittenFor32BitServers(SearchExpr tree) {
        this.encoded = write(tree, false);
    }

    private void whenTheBytesAreRead(byte[] data) {
        this.exception = catchThrowable(() -> SearchTreeCodec.read(PacketReader.of(data)));
    }

    private void whenKadPicksTheKeywordOf(String expression) {
        this.input = expression;
        this.exception = catchThrowable(() -> this.kadKeyword = kad(expression));
    }

    private void thenTheSearchIs(SearchExpr expected) {
        assertThat(this.exception).as(this.input).isNull();
        assertThat(this.parsed).isEqualTo(expected);
    }

    private void thenTheSyntaxErrorIs(Reason reason) {
        assertThat(this.exception)
                .as(this.input)
                .isInstanceOfSatisfying(
                        SearchSyntaxException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }

    private void thenTheTextIsASearchOrASyntaxError() {
        if (!(this.exception instanceof SearchSyntaxException)) {
            assertThat(this.exception).as(this.input).isNull();
        }
    }

    private void thenAParsedSearchCanBeReadBackFromA64BitServer() {
        if (this.parsed != null) {
            try {
                assertThat(SearchTreeCodec.read(PacketReader.of(write(this.parsed, true))))
                        .isNotNull();
            } catch (DecodeException e) {
                throw new AssertionError(this.input, e);
            }
        }
    }

    private void thenTheBytesWrittenAre(String hex) {
        assertThat(HexFormat.of().formatHex(this.encoded)).isEqualTo(hex);
    }

    private void thenTheTreeReadBackIs(SearchExpr expected) throws DecodeException {
        assertThat(SearchTreeCodec.read(PacketReader.of(this.encoded))).isEqualTo(expected);
    }

    private void thenNothingFollowsTheTree() throws DecodeException {
        PacketReader in = PacketReader.of(this.encoded);
        SearchTreeCodec.read(in);
        in.expectEnd();
    }

    private void thenTheBytesAreATreeOrADecodeError() {
        if (!(this.exception instanceof DecodeException)) {
            assertThat(this.exception).isNull();
        }
    }

    private void thenTheServerSearchIs(SearchExpr expected) {
        assertThat(this.serverSearch).isEqualTo(expected);
    }

    private void thenTheServerSearchIsTheSearchItself() {
        assertThat(this.serverSearch).isSameAs(this.search);
    }

    private void thenTheServerIsAskedFor(SearchExpr expected) {
        assertThat(this.sent).isEqualTo(expected);
    }

    private void thenKadLooksUp(String keyword) {
        assertThat(this.exception).isNull();
        assertThat(this.kadKeyword).isEqualTo(keyword);
    }

    private void thenKadLooksUp(String keyword, String why) {
        assertThat(this.exception).isNull();
        assertThat(this.kadKeyword).as(why).isEqualTo(keyword);
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).as(this.input).isInstanceOf(type);
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type, String messagePart) {
        assertThat(this.exception).isInstanceOf(type).hasMessageContaining(messagePart);
    }

    private static byte[] write(SearchExpr tree, boolean supports64Bit) {
        PacketWriter out = new PacketWriter();
        SearchTreeCodec.write(out, tree, supports64Bit);
        return out.toByteArray();
    }

    private static Keyword kw(String text) {
        return new Keyword(text);
    }

    private static String kad(String expression) {
        return SearchManager.kadKeyword(SearchParser.parse(expression));
    }
}
