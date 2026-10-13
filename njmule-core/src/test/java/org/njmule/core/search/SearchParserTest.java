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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

    @Test
    void adjacentWordsAreAndedFromTheRight() {
        assertThat(SearchParser.parse("linux iso")).isEqualTo(new And(kw("linux"), kw("iso")));
        assertThat(SearchParser.parse("a b c")).isEqualTo(new And(kw("a"), new And(kw("b"), kw("c"))));
    }

    @Test
    void notBindsTighterThanOrWhichBindsTighterThanAnd() {
        assertThat(SearchParser.parse("a OR b c")).isEqualTo(new And(new Or(kw("a"), kw("b")), kw("c")));
        assertThat(SearchParser.parse("a AND b OR c")).isEqualTo(new And(kw("a"), new Or(kw("b"), kw("c"))));
        assertThat(SearchParser.parse("a OR b NOT c")).isEqualTo(new Or(kw("a"), new Not(kw("b"), kw("c"))));
        assertThat(SearchParser.parse("(a OR b) AND c")).isEqualTo(new And(new Or(kw("a"), kw("b")), kw("c")));
    }

    @Test
    void aLeadingMinusMeansNot() {
        assertThat(SearchParser.parse("linux -windows")).isEqualTo(new Not(kw("linux"), kw("windows")));
        assertThat(SearchParser.parse("x-ray")).isEqualTo(kw("x-ray"));
    }

    @Test
    void operatorsAreUpperCaseWords() {
        assertThat(SearchParser.parse("rock or roll")).isEqualTo(new And(kw("rock"), new And(kw("or"), kw("roll"))));
        assertThat(SearchParser.parse("ORANGE")).isEqualTo(kw("ORANGE"));
    }

    @Test
    void quotedPhrasesKeepTheirQuotesAndEscapes() {
        assertThat(SearchParser.parse("\"exact phrase\" x")).isEqualTo(new And(kw("\"exact phrase\""), kw("x")));
        assertThat(SearchParser.parse("\"a\\\"b\"")).isEqualTo(kw("\"a\"b\""));
        assertThat(SearchParser.parse("\"\" x")).isEqualTo(kw("x"));
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
    void sizesUseBinaryUnitsAndDefaultToMegabytes(String text, Comparison op, long bytes) {
        assertThat(SearchParser.parse(text)).isEqualTo(new NumericAttr(FileTag.FILESIZE, op, bytes));
    }

    @Test
    void numericAttributesUseDecimalUnitsAndLengthsUseTime() {
        assertThat(SearchParser.parse("@sources>10"))
                .isEqualTo(new NumericAttr(FileTag.SOURCES, Comparison.GREATER, 10));
        assertThat(SearchParser.parse("@avail>=2k"))
                .isEqualTo(new NumericAttr(FileTag.SOURCES, Comparison.GREATER_EQUAL, 2000));
        assertThat(SearchParser.parse("@complete>5"))
                .isEqualTo(new NumericAttr(FileTag.COMPLETE_SOURCES, Comparison.GREATER, 5));
        assertThat(SearchParser.parse("@bitrate>=192"))
                .isEqualTo(new NumericAttr(FileTag.MEDIA_BITRATE, Comparison.GREATER_EQUAL, 192));
        assertThat(SearchParser.parse("@rating>3"))
                .isEqualTo(new NumericAttr(FileTag.FILERATING, Comparison.GREATER, 3));
        assertThat(SearchParser.parse("@length>1:30"))
                .isEqualTo(new NumericAttr(FileTag.MEDIA_LENGTH, Comparison.GREATER, 90));
        assertThat(SearchParser.parse("@len>=1:00:00"))
                .isEqualTo(new NumericAttr(FileTag.MEDIA_LENGTH, Comparison.GREATER_EQUAL, 3600));
        assertThat(SearchParser.parse("@length < 5m"))
                .isEqualTo(new NumericAttr(FileTag.MEDIA_LENGTH, Comparison.LESS, 300));
    }

    @Test
    void stringAttributesAndFileTypes() {
        assertThat(SearchParser.parse("@type=video")).isEqualTo(new StringAttr(FileTag.FILETYPE, "Video"));
        assertThat(SearchParser.parse("@typ=img")).isEqualTo(new StringAttr(FileTag.FILETYPE, "Image"));
        assertThat(SearchParser.parse("@type=doc")).isEqualTo(new StringAttr(FileTag.FILETYPE, "Doc"));
        assertThat(SearchParser.parse("@type=cd")).isEqualTo(new StringAttr(FileTag.FILETYPE, "Iso"));
        assertThat(SearchParser.parse("@ext=mkv")).isEqualTo(new StringAttr(FileTag.FILEFORMAT, "mkv"));
        assertThat(SearchParser.parse("@ext=\"mp3\"")).isEqualTo(new StringAttr(FileTag.FILEFORMAT, "mp3"));
        assertThat(SearchParser.parse("@artist=Queen song"))
                .isEqualTo(new And(new StringAttr(FileTag.MEDIA_ARTIST, "Queen"), kw("song")));
    }

    @Test
    void ed2kLinksBecomeHashTerms() {
        assertThat(SearchParser.parse("ed2k://|file|My File.iso|123|31d6cfe0d16ae931b73c59d7e0c089c0|/"))
                .isEqualTo(kw("ed2k::31D6CFE0D16AE931B73C59D7E0C089C0"));
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
    void reportsWhyAnExpressionIsInvalid(String text, Reason reason) {
        assertThatThrownBy(() -> SearchParser.parse(text))
                .isInstanceOfSatisfying(
                        SearchSyntaxException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }

    @Test
    void serverTreesArePrefixEncoded() {
        PacketWriter out = new PacketWriter();
        SearchTreeCodec.write(out, new And(kw("a"), new NumericAttr(FileTag.FILESIZE, Comparison.GREATER, 5)), false);

        assertThat(HexFormat.of().formatHex(out.toByteArray()))
                .isEqualTo("0000" + "01010061" + "0305000000" + "01" + "0100" + "02");
    }

    @Test
    void largeValuesNeed64BitServers() throws DecodeException {
        NumericAttr big = new NumericAttr(FileTag.FILESIZE, Comparison.GREATER_EQUAL, 5_000_000_000L);
        PacketWriter wide = new PacketWriter();
        SearchTreeCodec.write(wide, big, true);
        PacketWriter narrow = new PacketWriter();
        SearchTreeCodec.write(narrow, big, false);

        assertThat(SearchTreeCodec.read(PacketReader.of(wide.toByteArray()))).isEqualTo(big);
        assertThat(SearchTreeCodec.read(PacketReader.of(narrow.toByteArray())))
                .isEqualTo(new NumericAttr(FileTag.FILESIZE, Comparison.GREATER_EQUAL, 0xFFFF_FFFFL));
    }

    @Test
    void andedKeywordsAreSentAsOnePhrase() {
        assertThat(SearchTreeCodec.forServer(SearchParser.parse("ubuntu 24.04 iso")))
                .isEqualTo(kw("ubuntu 24.04 iso"));
        SearchExpr or = SearchParser.parse("a OR b");
        assertThat(SearchTreeCodec.forServer(or)).isSameAs(or);
    }

    @Property
    void treesRoundTrip(@ForAll("trees") SearchExpr tree) throws DecodeException {
        PacketWriter out = new PacketWriter();
        SearchTreeCodec.write(out, tree, true);
        PacketReader in = PacketReader.of(out.toByteArray());

        assertThat(SearchTreeCodec.read(in)).isEqualTo(tree);
        in.expectEnd();
    }

    @Property
    void anyTextParsesOrFailsWithASyntaxError(@ForAll @Size(max = 60) String text) {
        try {
            SearchParser.parse(text);
        } catch (SearchSyntaxException expected) {
            // invalid expression
        }
    }

    @Property
    void anyBytesDecodeOrFailCleanly(@ForAll @Size(max = 60) byte[] data) {
        try {
            SearchTreeCodec.read(PacketReader.of(data));
        } catch (DecodeException expected) {
            // malformed tree
        }
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

    private static Keyword kw(String text) {
        return new Keyword(text);
    }

    @Test
    void kadLooksUpTheLongestWordOfTheExpression() {
        assertThat(SearchManager.kadKeyword("the matrix reloaded")).isEqualTo("reloaded");
        assertThat(SearchManager.kadKeyword("\"linux\" AND (debian OR ubuntu) @size>700m"))
                .isEqualTo("debian");
        assertThat(SearchManager.kadKeyword("  único  ")).isEqualTo("único");
        assertThat(SearchManager.kadKeyword("NOT")).isEmpty();
    }
}
