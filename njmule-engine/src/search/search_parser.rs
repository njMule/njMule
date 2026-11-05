// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Parses the search box syntax: words and quoted phrases, `AND`, `OR`, `NOT` (or a leading `-`), parentheses and
//! attributes such as `@size>700m`, `@type=video` or `@length>=1:30:00`. Adjacent terms are ANDed with the lowest
//! priority; `NOT` binds tighter than `OR`, which binds tighter than `AND`.

use data_encoding::HEXUPPER;

use crate::api::ed2k_link::Ed2kLink;
use crate::protocol::tag::file_tag;
use crate::search::search_expr::{Comparison, SearchExpr};
use crate::search::search_syntax_error::{Reason, SearchSyntaxError};

/// Operators allowed in one expression (the request adds its own attributes).
pub const MAX_OPERATORS: usize = 10;
/// UTF-16 units an expression has at most, as the Java engine counts its characters.
pub const MAX_LENGTH: usize = 1024;
const MAX_DEPTH: usize = 32;
// 2^63: numbers stay within a signed long, as the Java engine holds them.
const MAX_NUMBER: f64 = 9_223_372_036_854_775_808.0;

pub fn parse(expression: &str) -> Result<SearchExpr, SearchSyntaxError> {
    if expression.encode_utf16().count() > MAX_LENGTH {
        return Err(SearchSyntaxError::new(Reason::TooComplex, MAX_LENGTH, "expression too long"));
    }
    let tokens = Lexer::new(expression).run()?;
    Parser { tokens, next: 0, operators: 0, depth: 0 }.top()
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Kind {
    String,
    Number,
    TypeValue,
    Ed2kLink,
    Attribute,
    Operator,
    And,
    Or,
    Not,
    LeftParenthesis,
    RightParenthesis,
    End,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum State {
    Initial,
    Number,
    Size,
    Type,
    String,
    Length,
}

/// Attribute names; any prefix of at least three letters is accepted, the first in this order winning.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct Attribute {
    name: &'static str,
    tag: u8,
    numeric: bool,
    state: State,
}

const ATTRIBUTES: [Attribute; 13] = [
    Attribute { name: "size", tag: file_tag::FILESIZE, numeric: true, state: State::Size },
    Attribute { name: "type", tag: file_tag::FILETYPE, numeric: false, state: State::Type },
    Attribute { name: "ext", tag: file_tag::FILEFORMAT, numeric: false, state: State::String },
    Attribute { name: "availability", tag: file_tag::SOURCES, numeric: true, state: State::Number },
    Attribute { name: "sources", tag: file_tag::SOURCES, numeric: true, state: State::Number },
    Attribute { name: "complete", tag: file_tag::COMPLETE_SOURCES, numeric: true, state: State::Number },
    Attribute { name: "bitrate", tag: file_tag::MEDIA_BITRATE, numeric: true, state: State::Number },
    Attribute { name: "length", tag: file_tag::MEDIA_LENGTH, numeric: true, state: State::Length },
    Attribute { name: "codec", tag: file_tag::MEDIA_CODEC, numeric: false, state: State::String },
    Attribute { name: "rating", tag: file_tag::FILERATING, numeric: true, state: State::Number },
    Attribute { name: "title", tag: file_tag::MEDIA_TITLE, numeric: false, state: State::String },
    Attribute { name: "album", tag: file_tag::MEDIA_ALBUM, numeric: false, state: State::String },
    Attribute { name: "artist", tag: file_tag::MEDIA_ARTIST, numeric: false, state: State::String },
];

#[derive(Debug, Clone, PartialEq)]
struct Token {
    kind: Kind,
    text: String,
    number: u64,
    comparison: Option<Comparison>,
    attribute: Option<Attribute>,
    position: usize,
}

impl Token {
    fn of(kind: Kind, text: impl Into<String>, position: usize) -> Self {
        Self { kind, text: text.into(), number: 0, comparison: None, attribute: None, position }
    }

    fn operator(comparison: Comparison, position: usize) -> Self {
        Self { comparison: Some(comparison), ..Self::of(Kind::Operator, comparison.symbol(), position) }
    }

    fn describe(&self) -> String {
        if self.kind == Kind::End { "end of expression".to_owned() } else { format!("'{}'", self.text) }
    }
}

struct Parser {
    tokens: Vec<Token>,
    next: usize,
    operators: usize,
    depth: usize,
}

impl Parser {
    fn top(mut self) -> Result<SearchExpr, SearchSyntaxError> {
        let first = self.peek();
        if first.kind == Kind::Ed2kLink && self.tokens.get(1).is_some_and(|t| t.kind == Kind::End) {
            return Ok(SearchExpr::Keyword(first.text));
        }
        if first.kind == Kind::End {
            return Err(error(Reason::General, &first, "empty expression"));
        }
        let expr = self.and_list()?;
        let end = self.peek();
        if end.kind != Kind::End {
            return Err(error(Reason::General, &end, &format!("unexpected {}", end.describe())));
        }
        if self.operators > MAX_OPERATORS {
            return Err(error(Reason::TooComplex, &end, &format!("{} operators", self.operators)));
        }
        Ok(expr)
    }

    // and_list: expression | expression and_list (right-nested implicit AND)
    fn and_list(&mut self) -> Result<SearchExpr, SearchSyntaxError> {
        let first = self.and_expr()?;
        if !starts_expression(&self.peek()) {
            return Ok(first);
        }
        self.operators = self.operators.saturating_add(1);
        Ok(SearchExpr::and(first, self.and_list()?))
    }

    fn and_expr(&mut self) -> Result<SearchExpr, SearchSyntaxError> {
        let mut left = self.or_expr()?;
        while self.peek().kind == Kind::And {
            self.take();
            self.operators = self.operators.saturating_add(1);
            left = SearchExpr::and(left, self.operand(Reason::MissingAndOperand, Self::or_expr)?);
        }
        Ok(left)
    }

    fn or_expr(&mut self) -> Result<SearchExpr, SearchSyntaxError> {
        let mut left = self.not_expr()?;
        while self.peek().kind == Kind::Or {
            self.take();
            self.operators = self.operators.saturating_add(1);
            left = SearchExpr::or(left, self.operand(Reason::MissingOrOperand, Self::not_expr)?);
        }
        Ok(left)
    }

    fn not_expr(&mut self) -> Result<SearchExpr, SearchSyntaxError> {
        let mut left = self.primary()?;
        while self.peek().kind == Kind::Not {
            self.take();
            self.operators = self.operators.saturating_add(1);
            left = SearchExpr::not(left, self.operand(Reason::MissingNotOperand, Self::primary)?);
        }
        Ok(left)
    }

    fn operand(
        &mut self,
        missing: Reason,
        rule: fn(&mut Self) -> Result<SearchExpr, SearchSyntaxError>,
    ) -> Result<SearchExpr, SearchSyntaxError> {
        if !starts_expression(&self.peek()) {
            return Err(error(missing, &self.peek(), ""));
        }
        rule(self)
    }

    fn primary(&mut self) -> Result<SearchExpr, SearchSyntaxError> {
        let token = self.take();
        match token.kind {
            Kind::String => Ok(SearchExpr::Keyword(token.text)),
            Kind::Attribute => self.attribute(&token),
            Kind::LeftParenthesis => {
                self.depth = self.depth.saturating_add(1);
                if self.depth > MAX_DEPTH {
                    return Err(error(Reason::TooComplex, &token, "nested too deeply"));
                }
                if !starts_expression(&self.peek()) {
                    return Err(error(Reason::MissingExpressionInParentheses, &self.peek(), ""));
                }
                let inner = self.and_list()?;
                if self.peek().kind != Kind::RightParenthesis {
                    return Err(error(Reason::MissingClosingParenthesis, &self.peek(), ""));
                }
                self.take();
                self.depth = self.depth.saturating_sub(1);
                Ok(inner)
            }
            Kind::Operator => Err(error(Reason::OperatorWithoutAttribute, &token, &token.text)),
            _ => Err(error(Reason::General, &token, &format!("unexpected {}", token.describe()))),
        }
    }

    fn attribute(&mut self, token: &Token) -> Result<SearchExpr, SearchSyntaxError> {
        let Some(attribute) = token.attribute else {
            return Err(error(Reason::General, token, "an attribute without its name"));
        };
        let operator = self.take();
        let Some(comparison) = operator.comparison.filter(|_| operator.kind == Kind::Operator) else {
            return Err(error(Reason::InvalidAttributeValue, &operator, &format!("@{}", attribute.name)));
        };
        let value = self.take();
        if attribute.numeric {
            if value.kind != Kind::Number {
                return Err(error(Reason::InvalidAttributeValue, &value, &format!("@{}", attribute.name)));
            }
            return Ok(SearchExpr::NumericAttr { tag: attribute.tag, comparison, value: value.number });
        }
        if comparison != Comparison::Equal {
            return Err(error(Reason::InvalidAttributeValue, &operator, &format!("@{} takes '='", attribute.name)));
        }
        let expected = if attribute.state == State::Type { Kind::TypeValue } else { Kind::String };
        if value.kind != expected {
            return Err(error(Reason::InvalidAttributeValue, &value, &format!("@{}", attribute.name)));
        }
        Ok(SearchExpr::StringAttr { tag: attribute.tag, value: value.text })
    }

    fn peek(&self) -> Token {
        self.tokens.get(self.next).cloned().unwrap_or_else(|| Token::of(Kind::End, "", 0))
    }

    fn take(&mut self) -> Token {
        let token = self.peek();
        if token.kind != Kind::End {
            self.next = self.next.saturating_add(1);
        }
        token
    }
}

fn starts_expression(token: &Token) -> bool {
    matches!(token.kind, Kind::String | Kind::Attribute | Kind::LeftParenthesis | Kind::Operator)
}

fn error(reason: Reason, at: &Token, detail: &str) -> SearchSyntaxError {
    SearchSyntaxError::new(reason, at.position, detail)
}

struct Lexer {
    input: Vec<char>,
    pos: usize,
    state: State,
    out: Vec<Token>,
}

impl Lexer {
    fn new(input: &str) -> Self {
        Self { input: input.chars().collect(), pos: 0, state: State::Initial, out: Vec::new() }
    }

    fn run(mut self) -> Result<Vec<Token>, SearchSyntaxError> {
        loop {
            while self.at(self.pos) == Some(' ') {
                self.pos = self.pos.saturating_add(1);
            }
            if self.pos >= self.input.len() {
                self.out.push(Token::of(Kind::End, "", self.pos));
                return Ok(self.out);
            }
            match self.state {
                State::Initial => self.initial()?,
                State::Number | State::Size | State::Length => self.number()?,
                State::Type => self.type_value()?,
                State::String => self.string()?,
            }
        }
    }

    fn at(&self, index: usize) -> Option<char> {
        self.input.get(index).copied()
    }

    fn text(&self, start: usize, end: usize) -> String {
        self.input.get(start..end).unwrap_or_default().iter().collect()
    }

    fn initial(&mut self) -> Result<(), SearchSyntaxError> {
        let start = self.pos;
        let Some(c) = self.at(start) else { return Ok(()) };
        match c {
            '"' => return self.quoted(true),
            '(' | ')' => {
                self.pos = start.saturating_add(1);
                let kind = if c == '(' { Kind::LeftParenthesis } else { Kind::RightParenthesis };
                self.out.push(Token::of(kind, c, start));
                return Ok(());
            }
            '=' => {
                self.pos = start.saturating_add(1);
                self.out.push(Token::operator(Comparison::Equal, start));
                return Ok(());
            }
            '<' | '>' => return Err(SearchSyntaxError::new(Reason::OperatorWithoutAttribute, start, c)),
            _ => {}
        }
        let word_end = self.word_end(start);
        let word = self.text(start, word_end);
        if let Some(link_end) = self.ed2k_file_link_end(start).filter(|end| *end >= word_end) {
            let link = self.text(start, link_end);
            self.pos = link_end;
            self.out.push(Token::of(Kind::Ed2kLink, ed2k_term(&link, start)?, start));
            return Ok(());
        }
        match word.as_str() {
            "AND" => self.emit(Kind::And, word, start, word_end),
            "OR" => self.emit(Kind::Or, word, start, word_end),
            "NOT" => self.emit(Kind::Not, word, start, word_end),
            _ if c == '-' => self.emit(Kind::Not, "-".to_owned(), start, start.saturating_add(1)),
            _ if c == '@' => return self.attribute(start, &word),
            _ => self.emit(Kind::String, word, start, word_end),
        }
        Ok(())
    }

    // The end of `ed2k://|file|<name>|<digits>|<32 hex>|/` starting at `start`.
    fn ed2k_file_link_end(&self, start: usize) -> Option<usize> {
        let mut i = start;
        let literal = |text: &str, i: &mut usize| {
            let matched = text.chars().enumerate().all(|(n, c)| self.at(i.saturating_add(n)) == Some(c));
            *i = i.saturating_add(text.chars().count());
            matched
        };
        let run = |i: &mut usize, accepted: &dyn Fn(char) -> bool| {
            let from = *i;
            while self.at(*i).is_some_and(accepted) {
                *i = i.saturating_add(1);
            }
            i.saturating_sub(from)
        };
        if !literal("ed2k://|file|", &mut i) || run(&mut i, &|c| c != '|') == 0 || !literal("|", &mut i) {
            return None;
        }
        if run(&mut i, &|c| c.is_ascii_digit()) == 0 || !literal("|", &mut i) {
            return None;
        }
        let hex_start = i;
        run(&mut i, &|c| c.is_ascii_hexdigit());
        // Exactly 32 hex digits: a longer run is no hash.
        if i.saturating_sub(hex_start) < 32 {
            return None;
        }
        i = hex_start.saturating_add(32);
        literal("|/", &mut i).then_some(i)
    }

    fn attribute(&mut self, start: usize, word: &str) -> Result<(), SearchSyntaxError> {
        let mut end = start.saturating_add(1);
        while self.at(end).is_some_and(|c| c.is_ascii_lowercase()) {
            end = end.saturating_add(1);
        }
        let name = self.text(start.saturating_add(1), end);
        // A longer word (e.g. "@size5") is not an attribute, and '@' cannot start a keyword.
        let attribute = if word.chars().count() == end.saturating_sub(start) { find(&name) } else { None };
        let Some(attribute) = attribute else {
            return Err(SearchSyntaxError::new(Reason::UnknownAttribute, start, word));
        };
        self.pos = end;
        self.out.push(Token { attribute: Some(attribute), ..Token::of(Kind::Attribute, word, start) });
        self.state = attribute.state;
        Ok(())
    }

    fn number(&mut self) -> Result<(), SearchSyntaxError> {
        let start = self.pos;
        if self.operator() {
            return Ok(());
        }
        let too_large = || SearchSyntaxError::new(Reason::InvalidAttributeValue, start, "number too large");
        if self.state == State::Length
            && let Some((parts, end)) = self.hours_minutes_seconds(start)
        {
            // Signed long arithmetic, as the Java engine counts: beyond it the number is too large.
            let seconds = match parts {
                (Some(a), Some(b), None) => a.checked_mul(60).and_then(|m| m.checked_add(b)),
                (Some(a), Some(b), Some(Some(c))) => a
                    .checked_mul(3600)
                    .and_then(|h| b.checked_mul(60).and_then(|m| h.checked_add(m)))
                    .and_then(|hm| hm.checked_add(c)),
                _ => None,
            };
            let seconds = seconds.and_then(|n| u64::try_from(n).ok()).ok_or_else(too_large)?;
            self.pos = end;
            self.number_token(seconds, start);
            return Ok(());
        }
        let end = self.number_end(start);
        if end == start {
            return Err(SearchSyntaxError::new(Reason::InvalidAttributeValue, start, "number expected"));
        }
        let value: f64 = self.text(start, end).parse().map_err(|_| too_large())?;
        self.pos = end;
        let unit = self.at(self.pos).unwrap_or(' ');
        let factor: f64 = match self.state {
            State::Number => match unit {
                'k' => 1_000.0,
                'm' => 1_000_000.0,
                'g' => 1_000_000_000.0,
                _ => 0.0,
            },
            State::Size => match java_lower_case(unit) {
                'b' => 1.0,
                'k' => 1024.0,
                'm' => 1_048_576.0,
                'g' => 1_073_741_824.0,
                // No unit: megabytes.
                _ => -1_048_576.0,
            },
            _ => match unit {
                's' => 1.0,
                'm' => 60.0,
                'h' => 3600.0,
                _ => 0.0,
            },
        };
        if factor > 0.0 {
            self.pos = self.pos.saturating_add(1);
        }
        let scaled = value * if factor == 0.0 { 1.0 } else { factor.abs() };
        if scaled.is_nan() || scaled >= MAX_NUMBER {
            return Err(too_large());
        }
        // Rounded half up, as the Java engine rounds; below 2^63 the conversion cannot saturate.
        let rounded = (scaled + 0.5) as u64;
        self.number_token(rounded, start);
        Ok(())
    }

    // `h:m[:s]` at `start`: the parts, none for a part too large for a signed long; or none at all when the digits do
    // not take that form.
    #[allow(clippy::type_complexity)]
    fn hours_minutes_seconds(&self, start: usize) -> Option<((Option<i64>, Option<i64>, Option<Option<i64>>), usize)> {
        let digits_from = |i: usize| self.digits(i);
        let first_end = digits_from(start);
        if first_end == start || self.at(first_end) != Some(':') {
            return None;
        }
        let second_start = first_end.saturating_add(1);
        let second_end = digits_from(second_start);
        if second_end == second_start {
            return None;
        }
        let mut end = second_end;
        let mut third = None;
        if self.at(second_end) == Some(':') {
            let third_start = second_end.saturating_add(1);
            let third_end = digits_from(third_start);
            if third_end > third_start {
                third = Some((third_start, third_end));
                end = third_end;
            }
        }
        let value = |from: usize, to: usize| self.text(from, to).parse::<i64>().ok();
        let parts = (value(start, first_end), value(second_start, second_end), third.map(|(f, t)| value(f, t)));
        Some((parts, end))
    }

    // digits[.digits] or .digits, then an optional exponent; the longest form wins ("1.5").
    fn number_end(&self, start: usize) -> usize {
        let mut i = self.digits(start);
        let before = i > start;
        let mut after = false;
        if self.at(i) == Some('.') {
            let j = self.digits(i.saturating_add(1));
            after = j > i.saturating_add(1);
            if before || after {
                i = j;
            }
        }
        if !before && !after {
            return start;
        }
        if matches!(self.at(i), Some('e' | 'E')) {
            let mut j = i.saturating_add(1);
            if matches!(self.at(j), Some('+' | '-')) {
                j = j.saturating_add(1);
            }
            let k = self.digits(j);
            if k > j {
                i = k;
            }
        }
        i
    }

    fn digits(&self, from: usize) -> usize {
        let mut i = from;
        while self.at(i).is_some_and(|c| c.is_ascii_digit()) {
            i = i.saturating_add(1);
        }
        i
    }

    fn number_token(&mut self, value: u64, start: usize) {
        let text = self.text(start, self.pos);
        self.out.push(Token { number: value, ..Token::of(Kind::Number, text, start) });
        self.state = State::Initial;
    }

    fn operator(&mut self) -> bool {
        let start = self.pos;
        let comparison = match (self.at(start), self.at(start.saturating_add(1))) {
            (Some('>'), Some('=')) => Comparison::GreaterEqual,
            (Some('<'), Some('=')) => Comparison::LessEqual,
            (Some('<'), Some('>')) => Comparison::NotEqual,
            (Some('='), _) => Comparison::Equal,
            (Some('>'), _) => Comparison::Greater,
            (Some('<'), _) => Comparison::Less,
            _ => return false,
        };
        self.pos = start.saturating_add(comparison.symbol().len());
        self.out.push(Token::operator(comparison, start));
        true
    }

    fn type_value(&mut self) -> Result<(), SearchSyntaxError> {
        let start = self.pos;
        if self.at(start) == Some('=') {
            self.pos = start.saturating_add(1);
            self.out.push(Token::operator(Comparison::Equal, start));
            return Ok(());
        }
        let mut end = start;
        while self.at(end).is_some_and(|c| c.is_ascii_alphabetic()) {
            end = end.saturating_add(1);
        }
        let word = self.text(start, end).to_ascii_lowercase();
        let Some(value) = type_name(&word) else {
            return Err(SearchSyntaxError::new(Reason::InvalidAttributeValue, start, "@type"));
        };
        self.pos = end;
        self.out.push(Token::of(Kind::TypeValue, value, start));
        self.state = State::Initial;
        Ok(())
    }

    fn string(&mut self) -> Result<(), SearchSyntaxError> {
        let start = self.pos;
        match self.at(start) {
            Some('=') => {
                self.pos = start.saturating_add(1);
                self.out.push(Token::operator(Comparison::Equal, start));
                return Ok(());
            }
            Some('"') => {
                self.quoted(false)?;
                self.state = State::Initial;
                return Ok(());
            }
            _ => {}
        }
        let end = self.word_end(start);
        if end == start {
            return Err(SearchSyntaxError::new(Reason::InvalidAttributeValue, start, "value expected"));
        }
        let word = self.text(start, end);
        self.emit(Kind::String, word, start, end);
        self.state = State::Initial;
        Ok(())
    }

    fn quoted(&mut self, keep_quotes: bool) -> Result<(), SearchSyntaxError> {
        let start = self.pos;
        let mut text = String::new();
        let mut i = start.saturating_add(1);
        loop {
            let c = match self.at(i) {
                None | Some('\n') => return Err(SearchSyntaxError::new(Reason::UnterminatedString, start, "")),
                Some(c) => c,
            };
            i = i.saturating_add(1);
            if c == '"' {
                break;
            }
            match (c, self.at(i)) {
                ('\\', Some(escaped)) => {
                    i = i.saturating_add(1);
                    match escaped {
                        't' => text.push('\t'),
                        'n' => text.push('\n'),
                        'r' => text.push('\r'),
                        'f' => text.push('\u{C}'),
                        'v' => text.push('\u{B}'),
                        'a' => text.push('\u{7}'),
                        'b' => text.push('\u{8}'),
                        'x' => {
                            let mut value = 0u32;
                            let mut digits = 0u8;
                            while digits < 3
                                && let Some(digit) = self.at(i).and_then(|d| d.to_digit(16))
                            {
                                value = value.saturating_mul(16).saturating_add(digit);
                                i = i.saturating_add(1);
                                digits = digits.saturating_add(1);
                            }
                            // Three hex digits at most: never a surrogate.
                            text.push(if digits == 0 { 'x' } else { char::from_u32(value).unwrap_or('x') });
                        }
                        other => text.push(other),
                    }
                }
                _ => text.push(c),
            }
        }
        self.pos = i;
        if !text.is_empty() {
            let token = if keep_quotes { format!("\"{text}\"") } else { text };
            self.out.push(Token::of(Kind::String, token, start));
        }
        Ok(())
    }

    fn word_end(&self, start: usize) -> usize {
        let mut end = start;
        while self.at(end).is_some_and(|c| !" \"()<>=".contains(c)) {
            end = end.saturating_add(1);
        }
        end
    }

    fn emit(&mut self, kind: Kind, text: String, start: usize, end: usize) {
        self.out.push(Token::of(kind, text, start));
        self.pos = end;
    }
}

fn find(name: &str) -> Option<Attribute> {
    ATTRIBUTES.into_iter().find(|a| name.len() >= 3 && name.len() <= a.name.len() && a.name.starts_with(name))
}

fn type_name(word: &str) -> Option<&'static str> {
    let prefix = |name: &str| word.len() >= 3 && word.len() <= name.len() && name.starts_with(word);
    Some(match word {
        _ if prefix("audio") => "Audio",
        _ if prefix("video") => "Video",
        "img" => "Image",
        _ if prefix("image") => "Image",
        _ if prefix("document") => "Doc",
        _ if prefix("program") => "Pro",
        _ if prefix("archive") => "Arc",
        "iso" | "cd" => "Iso",
        _ => return None,
    })
}

// A link in a search looks for the file it names: its hash, which servers and Kad match exactly.
fn ed2k_term(link: &str, position: usize) -> Result<String, SearchSyntaxError> {
    match Ed2kLink::parse(link) {
        Ok(Ed2kLink::File(file)) => Ok(format!("ed2k::{}", HEXUPPER.encode(&file.hash))),
        Ok(_) => Err(SearchSyntaxError::new(Reason::InvalidEd2kLink, position, "not a file link")),
        Err(e) => Err(SearchSyntaxError::new(Reason::InvalidEd2kLink, position, e.to_string())),
    }
}

// The lower case the Java engine takes for a unit: one character to one, so that the Kelvin sign is a 'k'.
fn java_lower_case(c: char) -> char {
    let mut lower = c.to_lowercase();
    match (lower.next(), lower.next()) {
        (Some(one), None) => one,
        _ => c,
    }
}
