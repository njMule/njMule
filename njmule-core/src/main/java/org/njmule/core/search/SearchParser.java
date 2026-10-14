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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.search.SearchExpr.Comparison;
import org.njmule.core.search.SearchSyntaxException.Reason;

/**
 * Parses the search box syntax: words and quoted phrases, {@code AND}, {@code OR}, {@code NOT}
 * (or a leading {@code -}), parentheses and attributes such as {@code @size>700m}, {@code
 * @type=video} or {@code @length>=1:30:00}. Adjacent terms are ANDed with the lowest priority;
 * {@code NOT} binds tighter than {@code OR}, which binds tighter than {@code AND}.
 */
public final class SearchParser {

    /** Operators allowed in one expression (the request adds its own attributes). */
    public static final int MAX_OPERATORS = 10;

    public static final int MAX_LENGTH = 1024;
    private static final int MAX_DEPTH = 32;

    private static final Pattern ED2K_FILE_LINK =
            Pattern.compile("ed2k://\\|file\\|[^|]+\\|[0-9]+\\|[a-fA-F0-9]{32}\\|/");
    private static final Pattern HMS = Pattern.compile("(\\d+):(\\d+)(?::(\\d+))?");

    private final List<Token> tokens;
    private int next;
    private int operators;
    private int depth;

    private SearchParser(List<Token> tokens) {
        this.tokens = tokens;
    }

    public static SearchExpr parse(String expression) {
        if (expression.length() > MAX_LENGTH) {
            throw new SearchSyntaxException(Reason.TOO_COMPLEX, MAX_LENGTH, "expression too long");
        }
        SearchParser parser = new SearchParser(new Lexer(expression).run());
        return parser.top();
    }

    private SearchExpr top() {
        Token first = this.peek();
        if (first.kind == Kind.ED2K_LINK && this.tokens.get(1).kind == Kind.EOF) {
            return new SearchExpr.Keyword(first.text);
        }
        if (first.kind == Kind.EOF) {
            throw this.error(Reason.GENERAL, first, "empty expression");
        }
        SearchExpr expr = this.andList();
        Token end = this.peek();
        if (end.kind != Kind.EOF) {
            throw this.error(Reason.GENERAL, end, "unexpected " + end);
        }
        if (this.operators > MAX_OPERATORS) {
            throw this.error(Reason.TOO_COMPLEX, end, this.operators + " operators");
        }
        return expr;
    }

    // and_searchexpr: searchexpr | searchexpr and_searchexpr (right-nested implicit AND)
    private SearchExpr andList() {
        SearchExpr first = this.andExpr();
        if (!this.startsExpression(this.peek())) {
            return first;
        }
        this.operators++;
        return new SearchExpr.And(first, this.andList());
    }

    private SearchExpr andExpr() {
        SearchExpr left = this.orExpr();
        while (this.peek().kind == Kind.AND) {
            this.take();
            this.operators++;
            left = new SearchExpr.And(left, this.operand(Reason.MISSING_AND_OPERAND, this::orExpr));
        }
        return left;
    }

    private SearchExpr orExpr() {
        SearchExpr left = this.notExpr();
        while (this.peek().kind == Kind.OR) {
            this.take();
            this.operators++;
            left = new SearchExpr.Or(left, this.operand(Reason.MISSING_OR_OPERAND, this::notExpr));
        }
        return left;
    }

    private SearchExpr notExpr() {
        SearchExpr left = this.primary();
        while (this.peek().kind == Kind.NOT) {
            this.take();
            this.operators++;
            left = new SearchExpr.Not(left, this.operand(Reason.MISSING_NOT_OPERAND, this::primary));
        }
        return left;
    }

    private SearchExpr operand(Reason missing, Supplier<SearchExpr> rule) {
        if (!this.startsExpression(this.peek())) {
            throw this.error(missing, this.peek(), "");
        }
        return rule.get();
    }

    private SearchExpr primary() {
        Token t = this.take();
        return switch (t.kind) {
            case STRING -> new SearchExpr.Keyword(t.text);
            case ATTRIBUTE -> this.attribute(t);
            case LPAREN -> {
                if (++this.depth > MAX_DEPTH) {
                    throw this.error(Reason.TOO_COMPLEX, t, "nested too deeply");
                }
                if (!this.startsExpression(this.peek())) {
                    throw this.error(Reason.MISSING_EXPRESSION_IN_PARENTHESES, this.peek(), "");
                }
                SearchExpr inner = this.andList();
                if (this.peek().kind != Kind.RPAREN) {
                    throw this.error(Reason.MISSING_CLOSING_PARENTHESIS, this.peek(), "");
                }
                this.take();
                this.depth--;
                yield inner;
            }
            case OPERATOR -> throw this.error(Reason.OPERATOR_WITHOUT_ATTRIBUTE, t, t.text);
            default -> throw this.error(Reason.GENERAL, t, "unexpected " + t);
        };
    }

    private SearchExpr attribute(Token attr) {
        Attribute a = attr.attribute;
        Token op = this.take();
        if (op.kind != Kind.OPERATOR) {
            throw this.error(Reason.INVALID_ATTRIBUTE_VALUE, op, "@" + a.name);
        }
        Token value = this.take();
        if (a.numeric) {
            if (value.kind != Kind.NUMBER) {
                throw this.error(Reason.INVALID_ATTRIBUTE_VALUE, value, "@" + a.name);
            }
            return new SearchExpr.NumericAttr(a.tag, op.comparison, value.number);
        }
        if (op.comparison != Comparison.EQUAL) {
            throw this.error(Reason.INVALID_ATTRIBUTE_VALUE, op, "@" + a.name + " takes '='");
        }
        Kind expected = a == Attribute.TYPE ? Kind.TYPE_VALUE : Kind.STRING;
        if (value.kind != expected) {
            throw this.error(Reason.INVALID_ATTRIBUTE_VALUE, value, "@" + a.name);
        }
        return new SearchExpr.StringAttr(a.tag, value.text);
    }

    private boolean startsExpression(Token t) {
        return t.kind == Kind.STRING || t.kind == Kind.ATTRIBUTE || t.kind == Kind.LPAREN || t.kind == Kind.OPERATOR;
    }

    private Token peek() {
        return this.tokens.get(this.next);
    }

    private Token take() {
        Token t = this.tokens.get(this.next);
        if (t.kind != Kind.EOF) {
            this.next++;
        }
        return t;
    }

    private SearchSyntaxException error(Reason reason, Token at, String detail) {
        return new SearchSyntaxException(reason, at.position, detail);
    }

    private enum Kind {
        STRING,
        NUMBER,
        TYPE_VALUE,
        ED2K_LINK,
        ATTRIBUTE,
        OPERATOR,
        AND,
        OR,
        NOT,
        LPAREN,
        RPAREN,
        EOF
    }

    /** Attribute names; any prefix of at least three letters is accepted. */
    private enum Attribute {
        SIZE("size", FileTag.FILESIZE, true, State.SIZE),
        TYPE("type", FileTag.FILETYPE, false, State.TYPE),
        EXT("ext", FileTag.FILEFORMAT, false, State.STRING),
        AVAILABILITY("availability", FileTag.SOURCES, true, State.NUMBER),
        SOURCES("sources", FileTag.SOURCES, true, State.NUMBER),
        COMPLETE("complete", FileTag.COMPLETE_SOURCES, true, State.NUMBER),
        BITRATE("bitrate", FileTag.MEDIA_BITRATE, true, State.NUMBER),
        LENGTH("length", FileTag.MEDIA_LENGTH, true, State.LENGTH),
        CODEC("codec", FileTag.MEDIA_CODEC, false, State.STRING),
        RATING("rating", FileTag.FILERATING, true, State.NUMBER),
        TITLE("title", FileTag.MEDIA_TITLE, false, State.STRING),
        ALBUM("album", FileTag.MEDIA_ALBUM, false, State.STRING),
        ARTIST("artist", FileTag.MEDIA_ARTIST, false, State.STRING);

        final String name;
        final int tag;
        final boolean numeric;
        final State state;

        Attribute(String name, int tag, boolean numeric, State state) {
            this.name = name;
            this.tag = tag;
            this.numeric = numeric;
            this.state = state;
        }
    }

    private enum State {
        INITIAL,
        NUMBER,
        SIZE,
        TYPE,
        STRING,
        LENGTH
    }

    private record Token(
            Kind kind, String text, long number, Comparison comparison, Attribute attribute, int position) {

        static Token of(Kind kind, String text, int position) {
            return new Token(kind, text, 0, null, null, position);
        }

        @Override
        public String toString() {
            return this.kind == Kind.EOF ? "end of expression" : "'" + this.text + "'";
        }
    }

    private static final class Lexer {
        private final String in;
        private int pos;
        private State state = State.INITIAL;
        private final List<Token> out = new ArrayList<>();

        Lexer(String in) {
            this.in = in;
        }

        List<Token> run() {
            while (true) {
                while (this.pos < this.in.length() && this.in.charAt(this.pos) == ' ') {
                    this.pos++;
                }
                if (this.pos >= this.in.length()) {
                    this.out.add(Token.of(Kind.EOF, "", this.pos));
                    return this.out;
                }
                switch (this.state) {
                    case INITIAL -> this.initial();
                    case NUMBER, SIZE, LENGTH -> this.number();
                    case TYPE -> this.type();
                    case STRING -> this.string();
                }
            }
        }

        private void initial() {
            int start = this.pos;
            char c = this.in.charAt(this.pos);
            if (c == '"') {
                this.quoted(true);
                return;
            }
            if (c == '(' || c == ')') {
                this.pos++;
                this.out.add(Token.of(c == '(' ? Kind.LPAREN : Kind.RPAREN, String.valueOf(c), start));
                return;
            }
            if (c == '=') {
                this.pos++;
                this.out.add(new Token(Kind.OPERATOR, "=", 0, Comparison.EQUAL, null, start));
                return;
            }
            if (c == '<' || c == '>') {
                throw new SearchSyntaxException(Reason.OPERATOR_WITHOUT_ATTRIBUTE, start, String.valueOf(c));
            }
            int wordEnd = this.wordEnd(start);
            String word = this.in.substring(start, wordEnd);
            Matcher link = ED2K_FILE_LINK.matcher(this.in).region(start, this.in.length());
            if (link.lookingAt() && link.end() >= wordEnd) {
                this.pos = link.end();
                this.out.add(Token.of(Kind.ED2K_LINK, ed2kTerm(link.group(), start), start));
                return;
            }
            switch (word) {
                case "AND" -> this.emit(Kind.AND, word, start, wordEnd);
                case "OR" -> this.emit(Kind.OR, word, start, wordEnd);
                case "NOT" -> this.emit(Kind.NOT, word, start, wordEnd);
                default -> {
                    if (c == '-') {
                        this.emit(Kind.NOT, "-", start, start + 1);
                    } else if (c == '@') {
                        this.attribute(start, word);
                    } else {
                        this.emit(Kind.STRING, word, start, wordEnd);
                    }
                }
            }
        }

        private void attribute(int start, String word) {
            int end = start + 1;
            while (end < this.in.length() && this.in.charAt(end) >= 'a' && this.in.charAt(end) <= 'z') {
                end++;
            }
            String name = this.in.substring(start + 1, end);
            // A longer word (e.g. "@size5") is not an attribute and '@' cannot start a keyword.
            Attribute match = word.length() == end - start ? find(name) : null;
            if (match == null) {
                throw new SearchSyntaxException(Reason.UNKNOWN_ATTRIBUTE, start, word);
            }
            this.pos = end;
            this.out.add(new Token(Kind.ATTRIBUTE, word, 0, null, match, start));
            this.state = match.state;
        }

        private static Attribute find(String name) {
            for (Attribute a : Attribute.values()) {
                if (name.length() >= 3 && name.length() <= a.name.length() && a.name.startsWith(name)) {
                    return a;
                }
            }
            return null;
        }

        private void number() {
            int start = this.pos;
            if (this.operator()) {
                return;
            }
            if (this.state == State.LENGTH) {
                Matcher hms = HMS.matcher(this.in).region(start, this.in.length());
                if (hms.lookingAt()) {
                    long seconds;
                    try {
                        long a = Long.parseLong(hms.group(1));
                        long b = Long.parseLong(hms.group(2));
                        seconds = hms.group(3) == null
                                ? Math.addExact(Math.multiplyExact(a, 60), b)
                                : Math.addExact(
                                        Math.addExact(Math.multiplyExact(a, 3600), Math.multiplyExact(b, 60)),
                                        Long.parseLong(hms.group(3)));
                    } catch (NumberFormatException | ArithmeticException e) {
                        throw new SearchSyntaxException(Reason.INVALID_ATTRIBUTE_VALUE, start, "number too large");
                    }
                    this.pos = hms.end();
                    this.numberToken(seconds, start);
                    return;
                }
            }
            int end = this.numberEnd(start);
            if (end == start) {
                throw new SearchSyntaxException(Reason.INVALID_ATTRIBUTE_VALUE, start, "number expected");
            }
            double value = Double.parseDouble(this.in.substring(start, end));
            this.pos = end;
            char unit = this.pos < this.in.length() ? this.in.charAt(this.pos) : ' ';
            double factor = switch (this.state) {
                case NUMBER ->
                    switch (unit) {
                        case 'k' -> 1_000;
                        case 'm' -> 1_000_000;
                        case 'g' -> 1_000_000_000;
                        default -> 0;
                    };
                case SIZE ->
                    switch (Character.toLowerCase(unit)) {
                        case 'b' -> 1;
                        case 'k' -> 1024;
                        case 'm' -> 1024 * 1024;
                        case 'g' -> 1024 * 1024 * 1024;
                        default -> -1024 * 1024; // no unit: megabytes
                    };
                default ->
                    switch (unit) {
                        case 's' -> 1;
                        case 'm' -> 60;
                        case 'h' -> 3600;
                        default -> 0;
                    };
            };
            if (factor > 0) {
                this.pos++;
            }
            double scaled = value * (factor == 0 ? 1 : Math.abs(factor));
            if (!(scaled < 0x1p63)) {
                throw new SearchSyntaxException(Reason.INVALID_ATTRIBUTE_VALUE, start, "number too large");
            }
            this.numberToken((long) (scaled + 0.5), start);
        }

        // digits[.digits] or .digits, then an optional exponent; the longest form wins ("1.5").
        private int numberEnd(int start) {
            int i = this.digits(start);
            boolean before = i > start;
            boolean after = false;
            if (i < this.in.length() && this.in.charAt(i) == '.') {
                int j = this.digits(i + 1);
                after = j > i + 1;
                if (before || after) {
                    i = j;
                }
            }
            if (!before && !after) {
                return start;
            }
            if (i < this.in.length() && (this.in.charAt(i) == 'e' || this.in.charAt(i) == 'E')) {
                int j = i + 1;
                if (j < this.in.length() && (this.in.charAt(j) == '+' || this.in.charAt(j) == '-')) {
                    j++;
                }
                int k = this.digits(j);
                if (k > j) {
                    i = k;
                }
            }
            return i;
        }

        private int digits(int from) {
            int i = from;
            while (i < this.in.length() && this.in.charAt(i) >= '0' && this.in.charAt(i) <= '9') {
                i++;
            }
            return i;
        }

        private void numberToken(long value, int start) {
            this.out.add(new Token(Kind.NUMBER, this.in.substring(start, this.pos), value, null, null, start));
            this.state = State.INITIAL;
        }

        private boolean operator() {
            int start = this.pos;
            String two = this.in.substring(start, Math.min(this.in.length(), start + 2));
            Comparison c = switch (two) {
                case ">=" -> Comparison.GREATER_EQUAL;
                case "<=" -> Comparison.LESS_EQUAL;
                case "<>" -> Comparison.NOT_EQUAL;
                default ->
                    switch (this.in.charAt(start)) {
                        case '=' -> Comparison.EQUAL;
                        case '>' -> Comparison.GREATER;
                        case '<' -> Comparison.LESS;
                        default -> null;
                    };
            };
            if (c == null) {
                return false;
            }
            this.pos += c.symbol().length();
            this.out.add(new Token(Kind.OPERATOR, c.symbol(), 0, c, null, start));
            return true;
        }

        private void type() {
            int start = this.pos;
            if (this.in.charAt(start) == '=') {
                this.pos++;
                this.out.add(new Token(Kind.OPERATOR, "=", 0, Comparison.EQUAL, null, start));
                return;
            }
            int end = start;
            while (end < this.in.length() && Character.isLetter(this.in.charAt(end)) && this.in.charAt(end) < 0x80) {
                end++;
            }
            String word = this.in.substring(start, end).toLowerCase(Locale.ROOT);
            String value = typeValue(word);
            if (value == null) {
                throw new SearchSyntaxException(Reason.INVALID_ATTRIBUTE_VALUE, start, "@type");
            }
            this.pos = end;
            this.out.add(Token.of(Kind.TYPE_VALUE, value, start));
            this.state = State.INITIAL;
        }

        private static String typeValue(String word) {
            if (prefix(word, "audio")) {
                return "Audio";
            }
            if (prefix(word, "video")) {
                return "Video";
            }
            if (prefix(word, "image") || word.equals("img")) {
                return "Image";
            }
            if (prefix(word, "document")) {
                return "Doc";
            }
            if (prefix(word, "program")) {
                return "Pro";
            }
            if (prefix(word, "archive")) {
                return "Arc";
            }
            if (word.equals("iso") || word.equals("cd")) {
                return "Iso";
            }
            return null;
        }

        private static boolean prefix(String word, String name) {
            return word.length() >= 3 && word.length() <= name.length() && name.startsWith(word);
        }

        private void string() {
            int start = this.pos;
            char c = this.in.charAt(start);
            if (c == '=') {
                this.pos++;
                this.out.add(new Token(Kind.OPERATOR, "=", 0, Comparison.EQUAL, null, start));
                return;
            }
            if (c == '"') {
                this.quoted(false);
                this.state = State.INITIAL;
                return;
            }
            int end = this.wordEnd(start);
            if (end == start) {
                throw new SearchSyntaxException(Reason.INVALID_ATTRIBUTE_VALUE, start, "value expected");
            }
            this.emit(Kind.STRING, this.in.substring(start, end), start, end);
            this.state = State.INITIAL;
        }

        private void quoted(boolean keepQuotes) {
            int start = this.pos;
            StringBuilder text = new StringBuilder();
            int i = start + 1;
            while (true) {
                if (i >= this.in.length() || this.in.charAt(i) == '\n') {
                    throw new SearchSyntaxException(Reason.UNTERMINATED_STRING, start, "");
                }
                char c = this.in.charAt(i++);
                if (c == '"') {
                    break;
                }
                if (c == '\\' && i < this.in.length()) {
                    char e = this.in.charAt(i++);
                    switch (e) {
                        case 't' -> text.append('\t');
                        case 'n' -> text.append('\n');
                        case 'r' -> text.append('\r');
                        case 'f' -> text.append('\f');
                        case 'v' -> text.append('\u000B');
                        case 'a' -> text.append('\u0007');
                        case 'b' -> text.append('\b');
                        case 'x' -> {
                            int value = 0;
                            int digits = 0;
                            while (digits < 3 && i < this.in.length() && Character.digit(this.in.charAt(i), 16) >= 0) {
                                value = value * 16 + Character.digit(this.in.charAt(i), 16);
                                i++;
                                digits++;
                            }
                            text.append(digits == 0 ? 'x' : (char) value);
                        }
                        default -> text.append(e);
                    }
                } else {
                    text.append(c);
                }
            }
            this.pos = i;
            if (text.isEmpty()) {
                return;
            }
            this.out.add(Token.of(Kind.STRING, keepQuotes ? "\"" + text + "\"" : text.toString(), start));
        }

        private int wordEnd(int start) {
            int end = start;
            while (end < this.in.length() && " \"()<>=".indexOf(this.in.charAt(end)) < 0) {
                end++;
            }
            return end;
        }

        private void emit(Kind kind, String text, int start, int end) {
            this.out.add(Token.of(kind, text, start));
            this.pos = end;
        }

        private static String ed2kTerm(String link, int position) {
            try {
                if (Ed2kLink.parse(link) instanceof Ed2kLink.FileLink file) {
                    return "ed2k::" + file.hash().toHex();
                }
            } catch (IllegalArgumentException e) {
                throw new SearchSyntaxException(Reason.INVALID_ED2K_LINK, position, e.getMessage());
            }
            throw new SearchSyntaxException(Reason.INVALID_ED2K_LINK, position, "not a file link");
        }
    }
}
