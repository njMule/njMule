// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Tidies the names files travel under on the network: escapes, separators used for spaces, tags in brackets and words
//! of advertising go, and the words start with a capital. The name stays a name to edit; it is made safe for a file
//! system where it is used.

use percent_encoding::percent_decode_str;
use unicode_general_category::{GeneralCategory, get_general_category};

use crate::util::white_space;

// Removed outright: a name keeps no separator or wildcard of a file system.
const UNWANTED: &str = "\"*<>?|\\:/";

/// `name` tidied, without the `words` (apart by `|`, in any case); the name as it was when nothing would be left of it.
pub fn clean_up(name: &str, words: &str) -> String {
    let mut clean = decoded(name).to_lowercase();
    for word in words.to_lowercase().split('|').filter(|word| !word.is_empty()) {
        clean = clean.replace(word, "");
    }
    let clean = dots_as_spaces(&clean).replace(['_', '+', '='], " ");
    let kept: String = clean.chars().filter(|c| !UNWANTED.contains(*c)).collect();
    let clean = capitalized(&without_tags(&kept)).replace("()", "");
    let clean = joined_spaces(&clean)
        .replace(" .", ".")
        .replace("( ", "(")
        .replace(" )", ")")
        .replace("()", "")
        .replace("{ ", "{")
        .replace(" }", "}")
        .replace("{}", "");
    let clean = white_space::strip(&clean);
    if clean.is_empty() || clean.starts_with('.') { name.to_owned() } else { clean.to_owned() }
}

// %20 and the like as the characters they stand for; a name with a lone % stays as it is.
fn decoded(name: &str) -> String {
    let bytes = name.as_bytes();
    let escapes_whole = bytes.iter().enumerate().filter(|(_, b)| **b == b'%').all(|(i, _)| {
        bytes.get(i.saturating_add(1)..i.saturating_add(3)).is_some_and(|hex| hex.iter().all(u8::is_ascii_hexdigit))
    });
    if escapes_whole { percent_decode_str(name).decode_utf8_lossy().replace('+', " ") } else { name.to_owned() }
}

// Dots before the extension's are spaces, but for those between digits, as in a version number.
fn dots_as_spaces(name: &str) -> String {
    let mut chars: Vec<char> = name.chars().collect();
    let extension = chars.iter().rposition(|c| *c == '.').unwrap_or(0);
    for i in 0..extension {
        let between_digits = i > 0
            && chars.get(i.wrapping_sub(1)).is_some_and(|c| is_digit(*c))
            && chars.get(i.saturating_add(1)).is_some_and(|c| is_digit(*c));
        if let Some(c) = chars.get_mut(i)
            && *c == '.'
            && !between_digits
        {
            *c = ' ';
        }
    }
    chars.into_iter().collect()
}

// Tags in square brackets go, such as a site or a group; a bracket mostly of digits, such as a year, stays.
fn without_tags(name: &str) -> String {
    let mut out: Vec<char> = name.chars().collect();
    let mut from = 0;
    while let Some(open) = out.iter().skip(from).position(|c| *c == '[').map(|at| at.saturating_add(from)) {
        let Some(close) = out.iter().skip(open).position(|c| *c == ']').map(|at| at.saturating_add(open)) else {
            break;
        };
        let inside = out.get(open.saturating_add(1)..close).unwrap_or_default();
        let digits = inside.iter().filter(|c| is_digit(**c)).count();
        if digits.saturating_mul(2) > inside.len() {
            from = open.saturating_add(1);
        } else {
            out.drain(open..=close);
            from = open;
        }
    }
    out.into_iter().collect()
}

// Each word starts with a capital, up to the extension; a letter a digit follows stays as it is, as in x264.
fn capitalized(name: &str) -> String {
    let mut chars: Vec<char> = name.chars().collect();
    let length = chars.len();
    if length < 2 {
        return name.to_owned();
    }
    upper(&mut chars, 0);
    let last = match chars.iter().rposition(|c| *c == '.') {
        Some(dot) if dot > 0 => dot.saturating_sub(1),
        _ => length.saturating_sub(1),
    };
    for i in 0..last {
        let starts_word = chars.get(i).is_some_and(|c| !is_letter(*c) && *c != '\'')
            && (i >= length.saturating_sub(2) || !chars.get(i.saturating_add(2)).is_some_and(|c| is_digit(*c)));
        if starts_word {
            upper(&mut chars, i.saturating_add(1));
        }
    }
    chars.into_iter().collect()
}

// Runs of spaces as one.
fn joined_spaces(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for c in text.chars() {
        if !(c == ' ' && out.ends_with(' ')) {
            out.push(c);
        }
    }
    out
}

// One character for one: a letter whose capital is longer, as ß, stays as it is.
fn upper(chars: &mut [char], i: usize) {
    if let Some(c) = chars.get_mut(i) {
        let mut capital = c.to_uppercase();
        if let (Some(single), None) = (capital.next(), capital.next()) {
            *c = single;
        }
    }
}

fn is_digit(c: char) -> bool {
    get_general_category(c) == GeneralCategory::DecimalNumber
}

fn is_letter(c: char) -> bool {
    matches!(
        get_general_category(c),
        GeneralCategory::UppercaseLetter
            | GeneralCategory::LowercaseLetter
            | GeneralCategory::TitlecaseLetter
            | GeneralCategory::ModifierLetter
            | GeneralCategory::OtherLetter
    )
}
